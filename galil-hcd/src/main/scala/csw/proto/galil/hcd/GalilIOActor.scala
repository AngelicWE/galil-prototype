package csw.proto.galil.hcd

import java.io.IOException

import org.apache.pekko.actor.typed.Behavior
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
 */
private[hcd] object GalilIOActor {
  // Command to publish the data record as current state
  val publishDataRecord = "publishDataRecord"

  def behavior(
      galilConfig: GalilConfig,
      config: Config,
      commandResponseManager: CommandResponseManager,
      adapter: CSWDeviceAdapter,
      loggerFactory: LoggerFactory,
      galilPrefix: Prefix,
      currentStatePublisher: CurrentStatePublisher
  ): Behavior[GalilCommandMessage] =
    Behaviors.withTimers { timers =>
      Behaviors.setup { ctx =>
        val log = loggerFactory.getLogger
        
        // Timer key for QR polling
        val QRPollingTimer = "qr-polling-timer"

        // Connect to Galikl device and throw error if that doesn't work
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
        
        // Flag to prevent queued QR messages from executing during pause
        var qrPollingEnabled = true

        // Pause QR polling (before file operations)
        def pauseQRPolling(): Unit = {
          log.info("Pausing QR polling for file operation")
          qrPollingEnabled = false
          timers.cancel(QRPollingTimer)
        }
        
        // Resume QR polling (after file operations)
        def resumeQRPolling(): Unit = {
          log.info("Resuming QR polling")
          qrPollingEnabled = true
          timers.startTimerWithFixedDelay(QRPollingTimer, GalilCommand(GalilIOActor.publishDataRecord), 1.second)
        }

        def galilSend(cmd: String): String = {
          log.info(s"Sending '$cmd' to Galil")
          val responses = galilIo.send(cmd)
          if (responses.lengthCompare(1) != 0)
            throw new RuntimeException(s"Received ${responses.size} responses to Galil $cmd")
          val resp = responses.head._2.utf8String
          log.debug(s"Response from Galil: $resp")
          resp
        }

        // Check that there is a Galil device on the other end of the socket (Is there good Galil command to use here?)
        def verifyGalil(): Unit = {
          val s = galilSend("")
          if (s.nonEmpty)
            throw new IOException(
              s"Unexpected response to empty galil command: '$s': " +
                s"Check if Galil device is really located at ${galilConfig.host}:${galilConfig.port}"
            )
        }

        verifyGalil()

        // Start QR polling timer (repeats every 1 second)
        timers.startTimerWithFixedDelay(QRPollingTimer, GalilCommand(GalilIOActor.publishDataRecord), 1.second)

        // Publish the contents of the data record as a CurrentState object
        def publishDataRecord(): Unit = {
          // Guard: Skip if QR polling is paused (handles queued messages)
          if (!qrPollingEnabled) {
            log.debug("Skipping QR - polling is paused")
            return
          }
          
          try {
            // Synchronized access to prevent interference with LS/DL commands
            val response = galilIo.synchronized {
              galilIo.send("QR")
            }
            val bs       = response.head._2
            val dr       = DataRecord(bs)
            val cs       = CurrentState(galilPrefix, StateName("DataRecord"), dr.toParamSet)
            currentStatePublisher.publish(cs)
          } catch {
            case ex: Exception =>
              log.warn(s"Failed to publish data record: ${ex.getMessage}")
              // Don't crash - just skip this update and try again next time
          }
        }

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
          
          // Pause QR polling during file operation to prevent buffer corruption
          pauseQRPolling()
          
          try {
            // Small delay to let any in-flight QR command complete
            Thread.sleep(100)
            
            ProgramFileManager.readProgramFile(filename, config) match {
              case Success(programText) =>
                // Prepare program for upload (strip comments, etc.)
                val cleanedProgram = ProgramFileManager.prepareProgramForUpload(programText)
                log.info(s"Prepared program: ${cleanedProgram.length} characters")
                
                try {
                  // Program is preprocessed by prepareProgramForUpload:
                  // - REM comments and blank lines are stripped
                  // - Inline ' comments are preserved (controller accepts these)
                  log.info(s"Program size: ${cleanedProgram.length} characters")
                  
                  // Entire upload sequence must be synchronized to prevent interference from background QR polling
                  galilIo.synchronized {
                    // Step 0: Halt any running programs
                    log.info("Halting programs (HX)")
                    val hxResponses = galilIo.send("HX")
                    log.info(s"HX got: ${hxResponses.map(_._2.utf8String).mkString}")
                    
                    // Step 1: Enter DL mode (may not respond until terminator sent)
                    log.info("Entering DL mode (using writeRaw)")
                    galilIo.writeRaw("DL")
                    
                    // Step 2: Stream program data
                    log.info(s"Streaming ${cleanedProgram.length} characters of program data")
                    galilIo.writeRaw(cleanedProgram)
                    
                    // Step 3: Send terminator and NOW wait for response
                    log.info("Sending terminator")
                    val termResponses = galilIo.send("\\")
                    val termResponse = termResponses.map(_._2.utf8String).mkString.trim
                    log.info(s"Terminator response: '$termResponse'")
                    
                    // Empty response means we got ":" which was stripped by receiveReplies
                    // Non-empty response containing ":" also means success
                    if (termResponse.nonEmpty && !termResponse.contains(":")) {
                      throw new RuntimeException(s"Upload terminator failed: $termResponse")
                    }
                    
                    // DIAGNOSTIC: Check what data is left in the buffer after DL
                    log.info("DIAGNOSTIC: Checking for residual data after DL")
                    val residual = galilIo.drainAndShowBuffer()
                    if (residual.nonEmpty) {
                      log.warn(s"DIAGNOSTIC: Found ${residual.length} bytes of residual data!")
                      log.warn(s"DIAGNOSTIC: First 200 chars: ${residual.take(200)}")
                    } else {
                      log.info("DIAGNOSTIC: Buffer is clean after DL")
                    }
                  }
                  
                  log.info(s"Successfully uploaded program from $filename")
                  log.info("Program uploaded to controller memory")
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
            // Always resume QR polling, even if upload failed
            Thread.sleep(100)  // Small delay before resuming QR
            resumeQRPolling()
          }
        }

        def handleDownloadPrograms(filename: String, runId: Id, maybeObsId: Option[ObsId], cmdMapEntry: CommandMapEntry): Unit = {
          log.info(s"Downloading programs to file: $filename")
          
          // Pause QR polling during file operation to prevent buffer corruption
          pauseQRPolling()
          
          try {
            // Small delay to let any in-flight QR command complete
            Thread.sleep(100)
            
            // Send UL command - returns program without line numbers
            // Synchronized to prevent interference from background QR polling
            log.info("Sending UL command to controller")
            val responses = galilIo.synchronized {
              // DIAGNOSTIC: Check buffer state before UL
              log.info("DIAGNOSTIC: Checking buffer state before UL")
              val preUL = galilIo.drainAndShowBuffer()
              if (preUL.nonEmpty) {
                // Single ':' is normal controller prompt after previous DL - not an error
                if (preUL.length == 1 && preUL == ":") {
                  log.debug("DIAGNOSTIC: Found controller prompt (':') - this is normal")
                } else {
                  // Multiple bytes or non-colon data indicates a real problem
                  log.warn(s"DIAGNOSTIC: Found ${preUL.length} bytes BEFORE UL command!")
                  log.warn(s"DIAGNOSTIC: This indicates QR polling was not properly paused!")
                }
              }
              
              galilIo.send("UL")
            }
            log.info(s"Received ${responses.size} response chunks from UL")
            
            // Collect all response chunks - UL returns raw program without line numbers
            val allText = responses.map(_._2.utf8String).mkString
            log.info(s"Total response: ${allText.length} characters")
            
            // UL terminates with \ - remove it if present
            val cleanedProgram = allText
              .stripSuffix("\\")
              .stripSuffix("\u001A")  // Control-Z (alternative terminator)
              .trim
            
            log.info(s"Cleaned program: ${cleanedProgram.length} characters")
            
            // Write to file - no line number parsing needed!
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
            // Always resume QR polling, even if download failed
            Thread.sleep(100)  // Small delay before resuming QR
            resumeQRPolling()
          }
        }

        def handleGalilResponse(response: String, runId: Id, maybeObsId: Option[ObsId], cmdMapEntry: CommandMapEntry): Unit = {
          log.debug(s"handleGalilResponse $response")
          val returnResponse = adapter.makeResponse(runId, maybeObsId, cmdMapEntry, response)
          commandResponseManager.updateCommand(returnResponse)
        }

        Behaviors.receiveMessage[GalilCommandMessage] {
          case GalilCommand(commandString) =>
            log.debug(s"doing command: $commandString")
            if (commandString == GalilIOActor.publishDataRecord) {
              publishDataRecord()
              // Timer automatically repeats - no need to reschedule
            }
            Behaviors.same

          case GalilRequest(commandString, runId, maybeObsId, commandKey, setup) =>
            log.info(s"doing command: $commandString")
            
            // Check for special commands that need file handling
            commandKey.name match {
              case "uploadProgram" =>
                // Extract filename from setup
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
                
              case "downloadPrograms" =>
                // Extract filename from setup
                setup.get(CSWDeviceAdapter.filenameKey) match {
                  case Some(param) =>
                    val filename = param.head
                    handleDownloadPrograms(filename, runId, maybeObsId, commandKey)
                  case None =>
                    log.error("downloadPrograms command missing filename parameter")
                    commandResponseManager.updateCommand(
                      Error(runId, "Missing filename parameter")
                    )
                }
                Behaviors.same
                
              case "getDataRecord" | "getDataRecordRaw" if commandString.startsWith("QR") =>
                // Special handling for QR commands
                val response = galilIo.send(commandString)
                val bs       = response.head._2
                log.debug(s"Data Record size: ${bs.size})")
                if (commandKey.name.equals("getDataRecord")) {
                  // parse the data record
                  val dr = DataRecord(bs)
                  log.debug(s"Data Record: $dr")
                  handleDataRecordResponse(dr, runId, maybeObsId, commandKey)
                }
                else {
                  // return a paramset with the raw data record bytes
                  handleDataRecordRawResponse(bs, runId, maybeObsId, commandKey)
                }
                Behaviors.same
                
              case _ =>
                // Normal command - send to Galil
                val response = galilSend(commandString)
                handleGalilResponse(response, runId, maybeObsId, commandKey)
                Behaviors.same
            }
        }
      }
    }
}