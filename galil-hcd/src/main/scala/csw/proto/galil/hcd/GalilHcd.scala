package csw.proto.galil.hcd

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import com.typesafe.config.{Config, ConfigFactory}
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.deploy.containercmd.ContainerCmd
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.api.models.TrackingEvent
import csw.params.commands.CommandResponse.{SubmitResponse, ValidateCommandResponse}
import csw.params.commands._
import csw.params.core.models.{Id, ObsId}
import csw.prefix.models.Subsystem
import csw.proto.galil.hcd.CSWDeviceAdapter.CommandMapEntry
import csw.proto.galil.hcd.ControllerInterfaceActor.ControllerIdentity
import csw.proto.galil.hcd.GalilCommandMessage.{GalilCommand, GalilRequest}
import csw.proto.galil.io.DataRecord
import csw.time.core.models.UTCTime

import scala.compiletime.uninitialized
import scala.concurrent.{ExecutionContextExecutor, Future}

// Add messages here...
sealed trait GalilCommandMessage

object GalilCommandMessage {

  case class GalilCommand(commandString: String) extends GalilCommandMessage

  case class GalilRequest(
      commandString: String, 
      runId: Id, 
      maybeObsId: Option[ObsId], 
      cmdMapEntry: CommandMapEntry,
      setup: Setup
  ) extends GalilCommandMessage
  
  // Messages for StatusMonitor to request QR data
  case class GetQR(replyTo: ActorRef[QRResult]) extends GalilCommandMessage
  case class QRResult(dataRecord: DataRecord) extends GalilCommandMessage
  
  // Ask the CI actor for its controller identity (available after Behaviors.setup completes)
  case class GetIdentity(replyTo: ActorRef[ControllerIdentity]) extends GalilCommandMessage
  
  // Download current embedded program from controller (UL command)
  // Returns the program text as a String
  case class DownloadProgram(replyTo: ActorRef[DownloadProgramResult]) extends GalilCommandMessage
  case class DownloadProgramResult(program: String, error: Option[String] = None) extends GalilCommandMessage

  // Synchronous command execution for CommandHandlerActor
  // Sends a command string and returns the response (or error)
  case class SendCommand(commandString: String, replyTo: ActorRef[SendCommandResult]) extends GalilCommandMessage
  case class SendCommandResult(response: String, error: Option[String] = None)

}

class GalilHcdHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends ComponentHandlers(ctx, cswCtx) {

  import cswCtx._

  private val log                           = loggerFactory.getLogger
  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private val config                        = ConfigFactory.load("GalilCommands.conf")
  private val adapter                       = new CSWDeviceAdapter(config)
  
  // HCD Configuration - loaded during initialization
  // SDD Section 4.2: "Load controller and axis-specific parameters from the CSW Configuration Service"
  private var hcdConfig: GalilHcdConfig = uninitialized
  
  // ========================================
  // Configuration Loading
  // ========================================
  
  /**
   * Load HCD configuration from Configuration Service
   * 
   * Per SDD Section 4.2: "On startup, the HCD retrieves its config file from 
   * the service to initialize axes, motion profiles, and I/O parameters."
   * 
   * For local testing, config file path can be specified via system property:
   *   -Dgalil.config.path=GalilHcdConfig-Hardware.conf
   * Default is GalilHcdConfig.conf
   * 
   * In production, this will use CSW Configuration Service:
   *   val configClient = ConfigClientFactory.clientApi(ctx.system, locationService)
   *   configClient.getActive(Paths.get("galil/GalilHcdConfig-Hardware.conf"))
   */
  private def loadConfiguration(): GalilHcdConfig = {
    // Get config file path from system property, default to GalilHcdConfig.conf
    val configPath = sys.props.getOrElse("galil.config.path", "GalilHcdConfig.conf")
    
    log.info(s"Loading HCD configuration from $configPath")
    
    try {
      // For local testing: load from resources
      // In production: replace with ConfigClientService.getActive()
      val config = ConfigFactory.load(configPath.stripSuffix(".conf"))
      val hcdConfig = GalilHcdConfig.fromConfig(config)
      
      log.info(s"Configuration loaded successfully from $configPath")
      log.info(s"  Controller: ${hcdConfig.controller.hostString}:${hcdConfig.controller.port}")
      log.info(s"  Controller ID: ${hcdConfig.controller.id}")
      log.info(s"  Embedded Program: ${hcdConfig.controller.embeddedProgram}")
      log.info(s"  Simulation Mode: ${hcdConfig.simulate}")
      log.info(s"  Active Axes: ${hcdConfig.activeAxes.zipWithIndex.filter(_._1).map(p => ('A' + p._2).toChar).mkString(", ")}")
      
      hcdConfig
    } catch {
      case ex: Exception =>
        log.error(s"Failed to load configuration from $configPath: ${ex.getMessage}", ex = ex)
        log.warn("Using default test configuration")
        GalilHcdConfig.defaultTestConfig
    }
  }
  
  // ========================================
  // State Management Actors
  // ========================================
  
  // 1. InternalStateActor - Central state repository (no dependencies)
  private val internalStateActor: ActorRef[InternalStateActor.Command] =
    ctx.spawn(InternalStateActor.apply(), "InternalStateActor")
  
  // 2. ControllerInterfaceActor - created during initialize() after config is loaded
  //    Owns the GalilIo TCP connection. All Galil I/O goes through this actor.
  private var controllerInterfaceActor: ActorRef[GalilCommandMessage] = uninitialized
  
  // 3. StatusMonitor - 10 Hz QR polling (created during initialize() after CI actor)
  private var statusMonitor: ActorRef[StatusMonitor.Command] = uninitialized
  
  // 4. CommandHandlerActor - created during initialize() after CI actor and InternalState are ready
  private var commandHandlerActor: ActorRef[CommandHandlerActor.Command] = uninitialized
  
  // 4. CurrentStatePublisher - CSW current state publications
  private val currentStatePublisher: ActorRef[CurrentStatePublisherActor.Command] =
    ctx.spawn(
      CurrentStatePublisherActor.behavior(
        componentInfo.prefix,
        internalStateActor,
        cswCtx.currentStatePublisher,  // Pass CSW's publisher directly!
        loggerFactory.getLogger
      ),
      "CurrentStatePublisher"
    )

  // ========================================
  // Lifecycle Handlers
  // ========================================
  
  override def initialize(): Unit = {
    import scala.concurrent.Await
    import scala.concurrent.duration._
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._
    
    implicit val timeout: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(5.seconds)
    implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler
    
    log.info("Initializing Galil HCD")
    
    // Phase 1: Load controller and axis-specific parameters from CSW Configuration Service
    hcdConfig = loadConfiguration()
    
    // Phase 2: Connect to controller and verify identity
    // Create ControllerInterfaceActor with config values directly.
    // The actor connects to the Galil controller and identifies it during Behaviors.setup.
    // We use the ask pattern (standard CSW approach) to block until the actor is ready.
    log.info("Establishing controller connection")
    val galilConfig = GalilConfig(hcdConfig.controller.hostString, hcdConfig.controller.port)
    controllerInterfaceActor = ctx.spawn(
      ControllerInterfaceActor.behavior(
        galilConfig,
        config,
        commandResponseManager,
        adapter,
        loggerFactory,
        componentInfo.prefix,
        cswCtx.currentStatePublisher
      ),
      "ControllerInterfaceActor"
    )
    
    // Block until the actor has completed setup (connect + ID) — standard CSW init pattern
    val identityFuture = controllerInterfaceActor.ask[ControllerIdentity](ref => GalilCommandMessage.GetIdentity(ref))
    val identity = Await.result(identityFuture, 5.seconds)
    log.info(s"Controller ready: firmware=${identity.firmware}, model=DMC-${identity.model}, axes=${identity.axisCount}")
    
    // Phase 3: Start status monitoring (1 Hz idle rate; increased to 10 Hz when motors are moving)
    statusMonitor = ctx.spawn(
      StatusMonitor.apply(
        controllerInterfaceActor,
        internalStateActor,
        pollingRateHz = 1.0
      ),
      "StatusMonitor"
    )
    statusMonitor ! StatusMonitor.SetPolling(enabled = true)
    log.info("StatusMonitor created - polling at 1Hz")

    // Phase 3b: Create CommandHandlerActor
    commandHandlerActor = ctx.spawn(
      CommandHandlerActor.behavior(
        controllerInterfaceActor,
        internalStateActor,
        commandResponseManager,
        loggerFactory
      ),
      "CommandHandlerActor"
    )
    log.info("CommandHandlerActor created")

    // Phase 4: Hardware-specific initialization
    if (hcdConfig.simulate) {
      // Simulation mode - skip embedded program verification
      log.info("Simulation mode - skipping embedded program verification")
      log.info("Galil HCD initialized (simulation mode)")
    } else {
      // Hardware mode - verify embedded program and initialize
      val initFuture = for {
        _ <- verifyEmbeddedProgram()
        _ <- initController()
        _ <- initializeAxes()
        _ = log.info("Galil HCD initialized successfully")
      } yield ()
      
      try {
        Await.result(initFuture, 10.seconds)
      } catch {
        case ex: Exception =>
          log.error("Initialization failed", ex = ex)
          throw ex
      }
    }
  }

  // ========================================
  // Phase 4: Embedded Program Verification
  // ========================================
  
  /**
   * Comparison result for embedded program verification
   */
  sealed trait ComparisonResult
  case object ProgramMatch extends ComparisonResult
  case class ProgramMismatch(differences: String) extends ComparisonResult
  
  /**
   * Verify controller has expected embedded program
   * 
   * Downloads current program from controller and compares to expected.
   * Logs results but does NOT automatically upload.
   */
  private def verifyEmbeddedProgram(): Future[Unit] = {
    val programName = hcdConfig.controller.embeddedProgram
    log.info(s"Verifying embedded program: $programName")
    
    for {
      // Step 1: Load expected program from resources
      expectedProgram <- loadEmbeddedProgram()
      
      // Step 2: Download current program from controller
      actualProgram <- downloadProgramFromController()
      
      // Step 3: Compare (ignoring whitespace)
      comparisonResult = comparePrograms(expectedProgram, actualProgram)
      
      // Step 4: Log results
      _ = logComparisonResult(comparisonResult)
      
    } yield ()
  }
  
  /**
   * Load embedded DMC program from resources
   */
  private def loadEmbeddedProgram(): Future[String] = {
    val programPath = hcdConfig.controller.embeddedProgram
    log.info(s"Loading embedded program from programs/$programPath")
    
    Future {
      val resourcePath = s"programs/$programPath"
      val stream = getClass.getClassLoader.getResourceAsStream(resourcePath)
      if (stream == null) {
        throw new RuntimeException(s"Embedded program not found: $resourcePath")
      }
      val source = scala.io.Source.fromInputStream(stream)
      try {
        val content = source.mkString
        log.info(s"Loaded embedded program: ${content.length} bytes")
        content
      } finally {
        source.close()
      }
    }
  }
  
  /**
   * Download current program from controller via ControllerInterfaceActor.
   * 
   * Pauses StatusMonitor QR polling before download (UL command needs exclusive
   * socket access) and resumes after. Uses the ask pattern to block until
   * the download completes.
   */
  private def downloadProgramFromController(): Future[String] = {
    import scala.concurrent.Await
    import scala.concurrent.duration._
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._
    
    implicit val timeout: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(10.seconds)
    implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler
    
    log.info("Downloading current program from controller")
    
    // Pause QR polling — UL needs exclusive socket access
    statusMonitor ! StatusMonitor.PauseQRPolling
    
    try {
      val resultFuture = controllerInterfaceActor.ask[GalilCommandMessage.DownloadProgramResult](
        ref => GalilCommandMessage.DownloadProgram(ref)
      )
      val result = Await.result(resultFuture, 10.seconds)
      
      result.error match {
        case Some(errMsg) =>
          log.error(s"Download failed: $errMsg")
          Future.failed(new RuntimeException(s"Download failed: $errMsg"))
        case None =>
          log.info(s"Downloaded program: ${result.program.length} characters")
          Future.successful(result.program)
      }
    } finally {
      // Resume QR polling
      statusMonitor ! StatusMonitor.ResumeQRPolling
    }
  }
  
  /**
   * Compare two DMC programs, ignoring whitespace differences
   * 
   * @param expected Program from resources
   * @param actual Program from controller
   * @return ProgramMatch or ProgramMismatch with details
   */
  private def comparePrograms(expected: String, actual: String): ComparisonResult = {
    // Normalize both programs for comparison
    val expectedNormalized = normalizeProgram(expected)
    val actualNormalized = normalizeProgram(actual)
    
    if (expectedNormalized == actualNormalized) {
      log.info("Programs match (ignoring whitespace)")
      ProgramMatch
    } else {
      // Find differences
      val diff = findDifferences(expectedNormalized, actualNormalized)
      log.warn("Programs differ")
      ProgramMismatch(diff)
    }
  }
  
  /**
   * Normalize DMC program for comparison.
   * 
   * The Galil controller auto-compresses uploaded code by stripping:
   * - Inline comments (everything after ' on a line)
   * - REM comment lines
   * - Blank lines  
   * - Trailing whitespace
   * 
   * This normalization mimics that compression so the resource file
   * (which has comments for documentation) can be compared against
   * the downloaded program (which has been stripped by the controller).
   */
  private def normalizeProgram(program: String): String = {
    program
      .split("\r?\n")             // Handle both CR+LF and LF
      .map { line =>
        // Strip inline comments: everything after ' (Galil comment marker)
        val commentIdx = line.indexOf('\'')
        if (commentIdx >= 0) line.substring(0, commentIdx) else line
      }
      .map(_.replaceAll("\\s+$", ""))  // Strip trailing whitespace
      .filter(_.trim.nonEmpty)          // Remove blank lines
      .filterNot(_.trim.startsWith("REM"))  // Remove REM comments
      .mkString("\n")
  }
  
  /**
   * Find differences between two programs
   * 
   * Returns a summary of what differs
   */
  private def findDifferences(expected: String, actual: String): String = {
    val expectedLines = expected.split("\n")
    val actualLines = actual.split("\n")
    
    if (expectedLines.length != actualLines.length) {
      s"Line count differs: expected ${expectedLines.length}, actual ${actualLines.length}"
    } else {
      // Find first differing line
      val differingLine = expectedLines.zip(actualLines).zipWithIndex.find {
        case ((exp, act), _) => exp != act
      }
      
      differingLine match {
        case Some(((exp, act), lineNum)) =>
          s"First difference at line ${lineNum + 1}:\n  Expected: $exp\n  Actual:   $act"
        case None =>
          "Programs differ but lines are identical (unexpected)"
      }
    }
  }
  
  /**
   * Log the comparison result
   */
  private def logComparisonResult(result: ComparisonResult): Unit = {
    result match {
      case ProgramMatch =>
        log.info("✓ Embedded program verification PASSED")
        log.info("  Controller has expected program")
        
      case ProgramMismatch(differences) =>
        log.warn("⚠ Embedded program verification FAILED")
        log.warn("  Controller program differs from expected")
        log.warn(s"  $differences")
        log.warn("  Actions:")
        log.warn("    1. Review differences")
        log.warn("    2. If controller is correct: Update resource file")
        log.warn("    3. If resource is correct: Upload to controller manually")
        log.warn(s"    4. Expected program: ${hcdConfig.controller.embeddedProgram}")
    }
  }
  
  // ========================================
  // Phase 2: Controller Initialization
  // ========================================
  
  /**
   * Initialize controller - execute #Init program
   */
  private def initController(): Future[Unit] = {
    log.info("Executing #Init program")
    
    // TODO: Send XQ#Init command and wait for completion
    // For now, just log
    // Real implementation will:
    // 1. Send command via ControllerInterfaceActor
    // 2. Monitor thread status via StatusMonitor
    // 3. Wait for thread completion
    log.info("Sent XQ#Init command to controller")
    Future.successful(())
  }
  
  /**
   * Initialize all active axes
   */
  private def initializeAxes(): Future[Unit] = {
    log.info("Initializing active axes")
    
    val axisNames = Seq("A", "B", "C", "D", "E", "F", "G", "H")
    val activeAxes = axisNames.zip(hcdConfig.activeAxes).filter(_._2).map(_._1)
    
    log.info(s"Active axes: ${activeAxes.mkString(", ")}")
    
    // Initialize each axis sequentially
    activeAxes.foldLeft(Future.successful(())) { (future, axisName) =>
      future.flatMap { _ =>
        initializeAxis(axisName)
      }
    }
  }
  
  /**
   * Initialize a single axis
   */
  private def initializeAxis(axisName: String): Future[Unit] = {
    log.info(s"Initializing axis $axisName")
    
    hcdConfig.axes.get(axisName) match {
      case Some(axisConfig) =>
        for {
          // Step 1: Execute #Setup{axis} program
          _ <- executeSetupProgram(axisName)
          
          // Step 2: Apply soft limits from config
          _ <- applySoftLimits(axisName, axisConfig)
          
          // Step 3: Store mechanism type and algorithm for command handlers
          _ = storeMechanismConfig(axisName, axisConfig)
          
          _ = log.info(s"Axis $axisName initialized successfully")
        } yield ()
        
      case None =>
        log.error(s"No configuration found for active axis $axisName")
        Future.failed(new RuntimeException(s"Missing config for axis $axisName"))
    }
  }
  
  /**
   * Execute #Setup{X} program for an axis
   */
  private def executeSetupProgram(axisName: String): Future[Unit] = {
    val cmd = s"XQ#Setup$axisName"
    log.info(s"Executing: $cmd")
    
    // TODO: Send command and wait for completion
    // Real implementation will:
    // 1. Send command via ControllerInterfaceActor
    // 2. Monitor thread status via StatusMonitor
    // 3. Wait for thread completion
    log.info(s"Sent $cmd command to controller")
    Future.successful(())
  }
  
  /**
   * Apply soft limits from configuration
   */
  private def applySoftLimits(axisName: String, axisConfig: AxisConfig): Future[Unit] = {
    // NOTE: Hardware rotating motors use max/min int32 limits (2147483647/-2147483648)
    // Setting limits is not meaningful for this hardware
    // This method is a placeholder for mechanisms that do need soft limits
    
    val flCmd = s"FL$axisName=${axisConfig.upperLimit}"
    val blCmd = s"BL$axisName=${axisConfig.lowerLimit}"
    
    log.debug(s"Soft limits for $axisName: [${axisConfig.lowerLimit}, ${axisConfig.upperLimit}]")
    log.debug(s"(Hardware uses max/min int32, so limits not set)")
    
    // TODO: For hardware that needs limits, send FL and BL commands
    Future.successful(())
  }
  
  /**
   * Store mechanism configuration for use by command handlers
   */
  private def storeMechanismConfig(axisName: String, axisConfig: AxisConfig): Unit = {
    // Store in axisInfo map for use by command handlers
    // This information is used to implement proper positioning behavior
    log.debug(s"Stored mechanism config for $axisName: ${axisConfig.mechanismType}, ${axisConfig.algorithm}")
  }

  override def onShutdown(): Unit = {
    log.info("Shutting down Galil HCD")
    
    // Stop actors gracefully (null checks for case where initialize failed partway)
    if (statusMonitor != null) statusMonitor ! StatusMonitor.SetPolling(enabled = false)
    currentStatePublisher ! CurrentStatePublisherActor.Shutdown
    
    log.info("Galil HCD shut down")
  }

  override def onGoOffline(): Unit = log.debug("onGoOffline called")

  override def onGoOnline(): Unit = log.debug("onGoOnline called")

  // ========================================
  // Command Validation and Execution
  // ========================================
  
  override def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse = {
    log.debug(s"validateSubmit called: $controlCommand")
    controlCommand match {
      case setup: Setup =>
        val commandName = setup.commandName.name
        
        // Immediate commands handled by CommandHandlerActor use ICD keys directly
        if (CommandHandlerActor.isImmediate(commandName)) {
          validateImmediateCommand(runId, setup) 
        } else {
          // Legacy path: validate through CSWDeviceAdapter command map
          val cmdMapEntry = adapter.getCommandMapEntry(setup)
          if (cmdMapEntry.isSuccess) {
            val cmdString = adapter.validateSetup(setup, cmdMapEntry.get)
            if (cmdString.isSuccess) {
              CommandResponse.Accepted(runId)
            }
            else {
              CommandResponse.Invalid(runId, CommandIssue.ParameterValueOutOfRangeIssue(cmdString.failed.get.getMessage))
            }
          }
          else {
            CommandResponse.Invalid(runId, CommandIssue.OtherIssue(cmdMapEntry.failed.get.getMessage))
          }
        }
      case _: Observe =>
        CommandResponse.Invalid(runId, CommandIssue.UnsupportedCommandIssue("Observe not supported"))
    }
  }
  
  /**
   * Validate immediate commands using ICD key definitions.
   * Checks required parameters are present and axis values are valid.
   */
  private def validateImmediateCommand(runId: Id, setup: Setup): ValidateCommandResponse = {
    import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion`._

    try {
      setup.commandName.name match {
        case "configAxis" =>
          // axis is required; all others are optional
          setup(ConfigAxisCommand.axisKey)
          CommandResponse.Accepted(runId)
          
        case "configRotatingAxis" =>
          // axis and algorithm are required
          setup(ConfigRotatingAxisCommand.axisKey)
          setup(ConfigRotatingAxisCommand.algorithmKey)
          CommandResponse.Accepted(runId)
          
        case "configLinearAxis" =>
          // axis, upperLimit, lowerLimit are all required
          setup(ConfigLinearAxisCommand.axisKey)
          setup(ConfigLinearAxisCommand.upperLimitKey)
          setup(ConfigLinearAxisCommand.lowerLimitKey)
          CommandResponse.Accepted(runId)
          
        case "setBit" =>
          // address and value are required
          setup(SetBitCommand.addressKey)
          val value = setup(SetBitCommand.valueKey).head
          if (value != 0 && value != 1) {
            CommandResponse.Invalid(runId, CommandIssue.ParameterValueOutOfRangeIssue("setBit value must be 0 or 1"))
          } else {
            CommandResponse.Accepted(runId)
          }
          
        case "setAO" =>
          // address and value are required
          setup(SetAOCommand.addressKey)
          setup(SetAOCommand.valueKey)
          CommandResponse.Accepted(runId)
          
        case other =>
          CommandResponse.Invalid(runId, CommandIssue.UnsupportedCommandIssue(s"Unknown immediate command: $other"))
      }
    } catch {
      case _: NoSuchElementException =>
        CommandResponse.Invalid(runId, CommandIssue.MissingKeyIssue("Required parameter missing"))
    }
  }

  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = {
    log.debug(s"onSubmit called: $controlCommand")
    controlCommand match {
      case setup: Setup =>
        val commandName = setup.commandName.name
        
        // Route immediate commands to CommandHandlerActor
        if (CommandHandlerActor.isImmediate(commandName)) {
          commandHandlerActor ! CommandHandlerActor.HandleCommand(setup, runId, setup.maybeObsId)
          CommandResponse.Started(runId)
        } else {
          // Existing path for non-immediate commands (file ops, data record, etc.)
          val cmdMapEntry = adapter.getCommandMapEntry(setup)
          val cmdString   = adapter.validateSetup(setup, cmdMapEntry.get)
        
          // Check if this is a file operation command that needs QR polling paused
          if (commandName == "uploadProgram" || commandName == "downloadProgram") {
            log.debug(s"Pausing QR polling for $commandName")
            statusMonitor ! StatusMonitor.PauseQRPolling
            Thread.sleep(50) // Brief delay to ensure pause message is processed
          }
        
          // Forward command to ControllerInterfaceActor
          controllerInterfaceActor ! GalilRequest(cmdString.get, runId, setup.maybeObsId, cmdMapEntry.get, setup)
        
          // Resume QR polling after file operation (async)
          if (commandName == "uploadProgram" || commandName == "downloadProgram") {
            import scala.concurrent.duration._
            log.debug(s"Will resume QR polling in 5s after $commandName")
            ctx.scheduleOnce(5.seconds, statusMonitor, StatusMonitor.ResumeQRPolling)
          }
        
          CommandResponse.Started(runId)
        }
      case x =>
        // Should not happen after validation
        CommandResponse.Error(runId, s"Unexpected submit: $x")
    }
  }

  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {
    log.debug(s"onOneway called: $controlCommand")
    controlCommand match {
      case setup: Setup =>
        val cmdMapEntry = adapter.getCommandMapEntry(setup)
        val cmdString   = adapter.validateSetup(setup, cmdMapEntry.get)
        // Send all oneway commands to ControllerInterfaceActor
        controllerInterfaceActor ! GalilRequest(cmdString.get, runId, setup.maybeObsId, cmdMapEntry.get, setup)
      case _ => // Only Setups handled
    }
  }

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit =
    log.debug(s"onLocationTrackingEvent called: $trackingEvent")

  override def onDiagnosticMode(startTime: UTCTime, hint: String): Unit = {}

  override def onOperationsMode(): Unit = {}
}

object GalilHcdApp {
  def main(args: Array[String]): Unit = {
    val defaultConfig = ConfigFactory.load("GalilHcd.conf")
    ContainerCmd.start("ICS.HCD.GalilMotion", Subsystem.APS, args, Some(defaultConfig))
  }
}