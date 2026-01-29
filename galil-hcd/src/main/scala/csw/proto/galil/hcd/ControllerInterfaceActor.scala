package csw.proto.galil.hcd

import java.io.IOException

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString
import com.typesafe.config.Config
import csw.command.client.CommandResponseManager
import csw.framework.CurrentStatePublisher
import csw.logging.client.scaladsl.LoggerFactory
import csw.params.commands.CommandResponse.{Completed, Error}
import csw.params.commands.Result
import csw.params.core.models.{Id, ObsId}
import csw.params.core.states.{CurrentState, StateName}
import csw.prefix.models.Prefix
import csw.proto.galil.hcd.CSWDeviceAdapter.CommandMapEntry
import csw.proto.galil.hcd.GalilCommandMessage.{GalilCommand, GalilRequest}
import csw.proto.galil.io.{DataRecord, GalilIo, GalilIoTcp}

import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * Worker actor that handles the Galil I/O
 * 
 * Note: GetQR and QRResult are defined in GalilHcd.scala as part of GalilCommandMessage
 */
private[hcd] object ControllerInterfaceActor {

  def behavior(
      galilConfig: GalilConfig,
      config: Config,
      commandResponseManager: CommandResponseManager,
      adapter: CSWDeviceAdapter,
      loggerFactory: LoggerFactory,
      galilPrefix: Prefix,
      currentStatePublisher: CurrentStatePublisher,
      statusMonitor: ActorRef[StatusMonitor.Command]
  ): Behavior[GalilCommandMessage] =
    Behaviors.withTimers { timers =>
      Behaviors.setup { ctx =>
        val log = loggerFactory.getLogger

        // Connect to Galil device and throw error if that doesn't work
        def connectToGalil(): GalilIo = {
          try {
            GalilIoTcp(galilConfig.host, galilConfig.port)
          }
          catch {
            case ex: Exception =>
              log.error(s"Failed to connect to Galil device at ${galilConfig.host}:${galilConfig.port}")
              throw ex
          }
        }
        val galilIo = connectToGalil()

        def galilSend(cmd: String): String = {
          log.debug(s"Sending '$cmd' to Galil")
          val responses = galilIo.send(cmd)
          if (responses.lengthCompare(1) != 0)
            throw new RuntimeException(s"Received ${responses.size} responses to Galil $cmd")
          val resp = responses.head._2.utf8String
          log.debug(s"Response from Galil: $resp")
          resp
        }

        // Check that there is a Galil device on the other end of the socket
        def verifyGalil(): Unit = {
          val s = galilSend("")
          if (s.nonEmpty)
            throw new IOException(
              s"Unexpected response to empty galil command: '$s': " +
                s"Check if Galil device is really located at ${galilConfig.host}:${galilConfig.port}"
            )
        }

        verifyGalil()

        def handleDataRecordResponse(dr: DataRecord, runId: Id, maybeObsId: Option[ObsId], cmdMapEntry: CommandMapEntry): Unit = {
          log.debug(s"handleDataRecordResponse $dr")
          val returnResponse = DataRecord.makeCommandResponse(runId, maybeObsId, dr)
          commandResponseManager.updateCommand(returnResponse)
        }

        def handleDataRecordRawResponse(
            bs: ByteString,
            runId: Id,
            maybeObsId: Option[ObsId],
            cmdMapEntry: CommandMapEntry
        ): Unit = {
          val returnResponse = Completed(runId, new Result().add(DataRecord.key.set(bs.toByteBuffer.array())))
          commandResponseManager.updateCommand(returnResponse)
        }

        def handleUploadProgram(filename: String, runId: Id, maybeObsId: Option[ObsId], cmdMapEntry: CommandMapEntry): Unit = {
          log.info(s"Uploading program from file: $filename")
          
          // Pause StatusMonitor QR polling during file operation to prevent buffer corruption
          statusMonitor ! StatusMonitor.PauseQRPolling
          
          try {
            // Small delay to let any in-flight QR command complete
            Thread.sleep(100)
            
            ProgramFileManager.readProgramFile(filename, config) match {
              case Success(programText) =>
                // Prepare program for upload (strip comments, etc.)
                val cleanedProgram = ProgramFileManager.prepareProgramForUpload(programText)
                log.info(s"Prepared program: ${cleanedProgram.length} characters")
                
                try {
                  log.info(s"Program size: ${cleanedProgram.length} characters")
                  
                  // Entire upload sequence must be synchronized
                  galilIo.synchronized {
                    // Step 0: Halt any running programs
                    log.info("Halting programs (HX)")
                    val hxResponses = galilIo.send("HX")
                    log.info(s"HX got: ${hxResponses.map(_._2.utf8String).mkString}")
                    
                    // Step 1: Enter DL mode
                    log.info("Entering DL mode (using writeRaw)")
                    galilIo.writeRaw("DL")
                    
                    // Step 2: Stream program data
                    log.info(s"Streaming ${cleanedProgram.length} characters of program data")
                    galilIo.writeRaw(cleanedProgram)
                    
                    // Step 3: Send terminator
                    log.info("Sending terminator")
                    val termResponses = galilIo.send("\\")
                    val termResponse = termResponses.map(_._2.utf8String).mkString.trim
                    log.info(s"Terminator response: '$termResponse'")
                    
                    if (termResponse.nonEmpty && !termResponse.contains(":")) {
                      throw new RuntimeException(s"Upload terminator failed: $termResponse")
                    }
                    
                    // DIAGNOSTIC: Check for residual data
                    log.debug("DIAGNOSTIC: Checking for residual data after DL")
                    val residual = galilIo.drainAndShowBuffer()
                    if (residual.nonEmpty) {
                      log.warn(s"DIAGNOSTIC: Found ${residual.length} bytes of residual data!")
                      log.warn(s"DIAGNOSTIC: First 200 chars: ${residual.take(200)}")
                    } else {
                      log.debug("DIAGNOSTIC: Buffer is clean after DL")
                    }
                  }
                  
                  log.info(s"Successfully uploaded program from $filename")
                  commandResponseManager.updateCommand(Completed(runId, new Result()))
                } catch {
                  case ex: Exception =>
                    log.error(s"Failed to upload program: ${ex.getMessage}")
                    commandResponseManager.updateCommand(
                      Error(runId, s"Upload failed: ${ex.getMessage}")
                    )
                }
                
              case Failure(ex) =>
                log.error(s"Failed to read program file $filename: ${ex.getMessage}")
                commandResponseManager.updateCommand(
                  Error(runId, s"Failed to read program file: ${ex.getMessage}")
                )
            }
          } finally {
            // Always resume StatusMonitor QR polling
            Thread.sleep(100)
            statusMonitor ! StatusMonitor.ResumeQRPolling
          }
        }

        def handledownloadProgram(filename: String, runId: Id, maybeObsId: Option[ObsId], cmdMapEntry: CommandMapEntry): Unit = {
          log.info(s"Downloading programs to file: $filename")
          
          // Pause StatusMonitor QR polling during file operation
          statusMonitor ! StatusMonitor.PauseQRPolling
          
          try {
            // Small delay to let any in-flight QR complete
            Thread.sleep(100)
            
            log.info("Sending UL command to controller")
            val responses = galilIo.synchronized {
              log.debug("DIAGNOSTIC: Checking buffer state before UL")
              val preUL = galilIo.drainAndShowBuffer()
              if (preUL.nonEmpty) {
                if (preUL.length == 1 && preUL == ":") {
                  log.debug("DIAGNOSTIC: Found controller prompt (':') - this is normal")
                } else {
                  log.warn(s"DIAGNOSTIC: Found ${preUL.length} bytes BEFORE UL command!")
                }
              }
              
              galilIo.send("UL")
            }
            log.info(s"Received ${responses.size} response chunks from UL")
            
            val allText = responses.map(_._2.utf8String).mkString
            log.info(s"Total response: ${allText.length} characters")
            
            val cleanedProgram = allText
              .stripSuffix("\\")
              .stripSuffix("\u001A")
              .trim
            
            log.info(s"Cleaned program: ${cleanedProgram.length} characters")
            
            ProgramFileManager.writeProgramFile(filename, cleanedProgram, config) match {
              case Success(filePath) =>
                log.info(s"Successfully downloaded programs to $filePath")
                commandResponseManager.updateCommand(
                  Completed(runId, new Result().add(CSWDeviceAdapter.filenameKey.set(filePath)))
                )
                
              case Failure(ex) =>
                log.error(s"Failed to write program file: ${ex.getMessage}")
                commandResponseManager.updateCommand(
                  Error(runId, s"Failed to write program file: ${ex.getMessage}")
                )
            }
          } catch {
            case ex: Exception =>
              log.error(s"Failed to download programs: ${ex.getMessage}")
              commandResponseManager.updateCommand(
                Error(runId, s"Download failed: ${ex.getMessage}")
              )
          } finally {
            // Always resume StatusMonitor QR polling
            Thread.sleep(100)
            statusMonitor ! StatusMonitor.ResumeQRPolling
          }
        }

        def handleGalilResponse(response: String, runId: Id, maybeObsId: Option[ObsId], cmdMapEntry: CommandMapEntry): Unit = {
          log.debug(s"handleGalilResponse $response")
          val returnResponse = adapter.makeResponse(runId, maybeObsId, cmdMapEntry, response)
          commandResponseManager.updateCommand(returnResponse)
        }

        Behaviors.receiveMessage[GalilCommandMessage] {
          // GetQR handler for StatusMonitor integration
          case GalilCommandMessage.GetQR(replyTo) =>
            try {
              // Synchronized access to prevent interference with file operations
              val response = galilIo.synchronized {
                galilIo.send("QR")
              }
              val bs = response.head._2
              val dr = DataRecord(bs)
              replyTo ! GalilCommandMessage.QRResult(dr)
            } catch {
              case ex: Exception =>
                log.error(s"QR command failed: ${ex.getMessage}")
                // Don't send reply on error - StatusMonitor will timeout and retry
            }
            Behaviors.same

          case GalilRequest(commandString, runId, maybeObsId, commandKey, setup) =>
            log.info(s"doing command: $commandString")
            
            // Check for special commands that need file handling
            commandKey.name match {
              case "uploadProgram" =>
                setup.get(CSWDeviceAdapter.filenameKey) match {
                  case Some(param) =>
                    val filename = param.head
                    handleUploadProgram(filename, runId, maybeObsId, commandKey)
                  case None =>
                    log.error("uploadProgram command missing filename parameter")
                    commandResponseManager.updateCommand(
                      Error(runId, "Missing filename parameter")
                    )
                }
                Behaviors.same
                
              case "downloadProgram" =>
                setup.get(CSWDeviceAdapter.filenameKey) match {
                  case Some(param) =>
                    val filename = param.head
                    handledownloadProgram(filename, runId, maybeObsId, commandKey)
                  case None =>
                    log.error("downloadProgram command missing filename parameter")
                    commandResponseManager.updateCommand(
                      Error(runId, "Missing filename parameter")
                    )
                }
                Behaviors.same
                
              case "getDataRecord" | "getDataRecordRaw" if commandString.startsWith("QR") =>
                val response = galilIo.send(commandString)
                val bs       = response.head._2
                log.debug(s"Data Record size: ${bs.size})")
                if (commandKey.name.equals("getDataRecord")) {
                  val dr = DataRecord(bs)
                  log.debug(s"Data Record: $dr")
                  handleDataRecordResponse(dr, runId, maybeObsId, commandKey)
                }
                else {
                  handleDataRecordRawResponse(bs, runId, maybeObsId, commandKey)
                }
                Behaviors.same
                
              case _ =>
                val response = galilSend(commandString)
                handleGalilResponse(response, runId, maybeObsId, commandKey)
                Behaviors.same
            }
            
          case _ =>
            // Handle other GalilCommandMessage types if needed
            Behaviors.same
        }
      }
    }
}