package csw.proto.galil.hcd

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{AskPattern, Behaviors}
import org.apache.pekko.util.Timeout
import csw.command.client.CommandResponseManager
import csw.logging.client.scaladsl.LoggerFactory
import csw.params.commands.CommandResponse.{Completed, Error}
import csw.params.commands.Setup
import csw.params.core.models.{Id, ObsId}
import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion`._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Command Handler Actor - Primary entry point for incoming commands from Assemblies.
 *
 * As described in SDD Section 4.6.1:
 * - Validates incoming commands and determines command type (immediate vs long-running)
 * - For immediate commands: processes synchronously and returns final response
 * - For long-running commands: sends Started, delegates monitoring to CommandWatcher (future)
 * - Updates Internal State Actor with state changes from command execution
 *
 * Currently implements immediate commands only:
 *   configAxis, configRotatingAxis, configLinearAxis, setBit, setAO
 */
object CommandHandlerActor {

  // ========================================
  // Protocol
  // ========================================

  sealed trait Command

  /**
   * Handle a submitted command from onSubmit.
   * The CommandHandler will classify it, execute it, and update the CRM.
   */
  case class HandleCommand(
    setup: Setup,
    runId: Id,
    maybeObsId: Option[ObsId]
  ) extends Command

  // Internal message for receiving InternalState update responses
  private case class StateUpdateResult(response: InternalStateActor.UpdateResponse) extends Command

  // ========================================
  // Immediate command classification
  // ========================================

  private val immediateCommands = Set(
    "configAxis", "configRotatingAxis", "configLinearAxis",
    "setBit", "setAO"
  )

  def isImmediate(commandName: String): Boolean = immediateCommands.contains(commandName)

  // ========================================
  // Factory
  // ========================================

  def behavior(
    controllerInterfaceActor: ActorRef[GalilCommandMessage],
    internalStateActor: ActorRef[InternalStateActor.Command],
    commandResponseManager: CommandResponseManager,
    loggerFactory: LoggerFactory
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      val log = loggerFactory.getLogger(ctx)
      log.info("CommandHandlerActor started")

      // Adapter for InternalState update responses
      val stateUpdateAdapter = ctx.messageAdapter[InternalStateActor.UpdateResponse](StateUpdateResult.apply)

      // Timeout and scheduler for ask pattern (passed explicitly to avoid Scala 3 implicit ambiguity)
      val askTimeout: Timeout = Timeout(5.seconds)
      val askScheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler

      Behaviors.receiveMessage {
        case HandleCommand(setup, runId, maybeObsId) =>
          val commandName = setup.commandName.name

          if (isImmediate(commandName)) {
            try {
              log.info(s"Handling immediate command: $commandName")
              commandName match {
                case "configAxis" =>
                  handleConfigAxis(setup, runId, controllerInterfaceActor, internalStateActor,
                    stateUpdateAdapter, commandResponseManager, log, askTimeout, askScheduler)
                case "configRotatingAxis" =>
                  handleConfigRotatingAxis(setup, runId, internalStateActor,
                    stateUpdateAdapter, commandResponseManager, log)
                case "configLinearAxis" =>
                  handleConfigLinearAxis(setup, runId, internalStateActor,
                    stateUpdateAdapter, commandResponseManager, log)
                case "setBit" =>
                  handleSetBit(setup, runId, controllerInterfaceActor, commandResponseManager, log,
                    askTimeout, askScheduler)
                case "setAO" =>
                  handleSetAO(setup, runId, controllerInterfaceActor, commandResponseManager, log,
                    askTimeout, askScheduler)
                case other =>
                  commandResponseManager.updateCommand(Error(runId, s"Unknown immediate command: $other"))
              }
            } catch {
              case ex: Exception =>
                log.error(s"Immediate command $commandName failed: ${ex.getMessage}")
                commandResponseManager.updateCommand(Error(runId, s"$commandName failed: ${ex.getMessage}"))
            }
          } else {
            // Long-running commands — placeholder
            // TODO: Add CommandWatcher delegation here
            log.warn(s"Long-running command not yet implemented in CommandHandler: $commandName")
            commandResponseManager.updateCommand(
              Error(runId, s"Command '$commandName' not yet supported by CommandHandler")
            )
          }

          Behaviors.same

        case StateUpdateResult(response) =>
          // Log state update failures but don't fail the command (already completed)
          if (!response.success) {
            log.warn(s"InternalState update failed: ${response.message}")
          }
          Behaviors.same
      }
    }

  // ========================================
  // configAxis — SDD 4.8.2
  // ========================================

  /**
   * Configures motion parameters for a single axis.
   *
   * Builds a compound Galil command string from the optional parameters present
   * in the Setup, sends it to the controller, then updates InternalState.
   *
   * Example for axis A with velocity=50000 and acceleration=100000:
   *   "speed[0]=50000;accel[0]=100000"
   */
  private def handleConfigAxis(
    setup: Setup,
    runId: Id,
    ciActor: ActorRef[GalilCommandMessage],
    internalStateActor: ActorRef[InternalStateActor.Command],
    stateUpdateAdapter: ActorRef[InternalStateActor.UpdateResponse],
    crm: CommandResponseManager,
    log: csw.logging.api.scaladsl.Logger,
    askTimeout: Timeout,
    askScheduler: org.apache.pekko.actor.typed.Scheduler
  ): Unit = {
    val axisChoice = setup(ConfigAxisCommand.axisKey).head
    val axis = Axis.fromChar(axisChoice.name.head)
    val idx = axis.index

    // Build compound command from present (optional) parameters
    // Each maps ICD param -> embedded array[axisIndex]=value
    val commands = scala.collection.mutable.ListBuffer[String]()
    val stateUpdates = scala.collection.mutable.Map[String, Any]()

    setup.get(ConfigAxisCommand.velocityKey).foreach { param =>
      commands += s"speed[$idx]=${param.head}"
    }
    setup.get(ConfigAxisCommand.accelerationKey).foreach { param =>
      commands += s"accel[$idx]=${param.head}"
    }
    setup.get(ConfigAxisCommand.decelerationKey).foreach { param =>
      commands += s"decel[$idx]=${param.head}"
    }
    setup.get(ConfigAxisCommand.indexOffsetKey).foreach { param =>
      commands += s"hoff[$idx]=${param.head}"
    }
    setup.get(ConfigAxisCommand.indexSpeedKey).foreach { param =>
      commands += s"hspd[$idx]=${param.head}"
    }
    setup.get(ConfigAxisCommand.inPositionThresholdKey).foreach { param =>
      stateUpdates("inPositionThreshold") = param.head.toDouble
    }

    // Send compound command to controller if there are any Galil commands
    // GalilIo.send() handles splitting compound responses correctly, even when
    // multiple ":" arrive in a single TCP packet (e.g. for assignment commands).
    if (commands.nonEmpty) {
      val cmdString = commands.mkString(";")
      log.info(s"configAxis $axis: sending $cmdString")
      sendToController(ciActor, cmdString, log, askTimeout, askScheduler) match {
        case Success(_) =>
          log.info(s"configAxis $axis: controller updated")
        case Failure(ex) =>
          crm.updateCommand(Error(runId, s"configAxis $axis failed: ${ex.getMessage}"))
          return
      }
    }

    // Update InternalState (inPositionThreshold is HCD-only, not sent to controller)
    if (stateUpdates.nonEmpty) {
      internalStateActor ! InternalStateActor.UpdateAxisState(axis, stateUpdates.toMap, stateUpdateAdapter)
    }

    log.info(s"configAxis $axis: completed (${commands.size} controller params, ${stateUpdates.size} state params)")
    crm.updateCommand(Completed(runId))
  }

  // ========================================
  // configRotatingAxis — InternalState only
  // ========================================

  private def handleConfigRotatingAxis(
    setup: Setup,
    runId: Id,
    internalStateActor: ActorRef[InternalStateActor.Command],
    stateUpdateAdapter: ActorRef[InternalStateActor.UpdateResponse],
    crm: CommandResponseManager,
    log: csw.logging.api.scaladsl.Logger
  ): Unit = {
    val axisChoice = setup(ConfigRotatingAxisCommand.axisKey).head
    val axis = Axis.fromChar(axisChoice.name.head)
    val algorithmChoice = setup(ConfigRotatingAxisCommand.algorithmKey).head

    val algorithm = algorithmChoice.name match {
      case "forward"  => RotatingAlgorithm.Forward
      case "reverse"  => RotatingAlgorithm.Reverse
      case "shortest" => RotatingAlgorithm.Shortest
      case other => throw new IllegalArgumentException(s"Unknown algorithm: $other")
    }

    val updates = Map[String, Any](
      "mechanismType" -> MechanismType.Rotating,
      "algorithm" -> algorithm
    )

    internalStateActor ! InternalStateActor.UpdateAxisState(axis, updates, stateUpdateAdapter)
    log.info(s"configRotatingAxis $axis: algorithm=$algorithm")
    crm.updateCommand(Completed(runId))
  }

  // ========================================
  // configLinearAxis — InternalState only
  // ========================================

  private def handleConfigLinearAxis(
    setup: Setup,
    runId: Id,
    internalStateActor: ActorRef[InternalStateActor.Command],
    stateUpdateAdapter: ActorRef[InternalStateActor.UpdateResponse],
    crm: CommandResponseManager,
    log: csw.logging.api.scaladsl.Logger
  ): Unit = {
    val axisChoice = setup(ConfigLinearAxisCommand.axisKey).head
    val axis = Axis.fromChar(axisChoice.name.head)
    val upperLimit = setup(ConfigLinearAxisCommand.upperLimitKey).head.toDouble
    val lowerLimit = setup(ConfigLinearAxisCommand.lowerLimitKey).head.toDouble

    val updates = Map[String, Any](
      "mechanismType" -> MechanismType.Linear,
      "upperLimit" -> upperLimit,
      "lowerLimit" -> lowerLimit
    )

    internalStateActor ! InternalStateActor.UpdateAxisState(axis, updates, stateUpdateAdapter)
    log.info(s"configLinearAxis $axis: upper=$upperLimit, lower=$lowerLimit")
    crm.updateCommand(Completed(runId))
  }

  // ========================================
  // setBit — SB or CB based on value
  // ========================================

  /**
   * Sets or clears a digital output bit.
   * ICD defines: address (int), value (int: 0 or 1)
   * Galil: SB address (set) or CB address (clear)
   */
  private def handleSetBit(
    setup: Setup,
    runId: Id,
    ciActor: ActorRef[GalilCommandMessage],
    crm: CommandResponseManager,
    log: csw.logging.api.scaladsl.Logger,
    askTimeout: Timeout,
    askScheduler: org.apache.pekko.actor.typed.Scheduler
  ): Unit = {
    val address = setup(SetBitCommand.addressKey).head
    val value = setup(SetBitCommand.valueKey).head

    val cmdString = if (value != 0) s"SB $address" else s"CB $address"
    log.info(s"setBit: $cmdString")

    sendToController(ciActor, cmdString, log, askTimeout, askScheduler) match {
      case Success(_) =>
        crm.updateCommand(Completed(runId))
      case Failure(ex) =>
        crm.updateCommand(Error(runId, s"setBit failed: ${ex.getMessage}"))
    }
  }

  // ========================================
  // setAO — AO command
  // ========================================

  /**
   * Sets an analog output channel value.
   * ICD defines: address (int), value (float)
   * Galil: AO address,value
   */
  private def handleSetAO(
    setup: Setup,
    runId: Id,
    ciActor: ActorRef[GalilCommandMessage],
    crm: CommandResponseManager,
    log: csw.logging.api.scaladsl.Logger,
    askTimeout: Timeout,
    askScheduler: org.apache.pekko.actor.typed.Scheduler
  ): Unit = {
    val address = setup(SetAOCommand.addressKey).head
    val value = setup(SetAOCommand.valueKey).head

    val cmdString = s"AO $address,$value"
    log.info(s"setAO: $cmdString")

    sendToController(ciActor, cmdString, log, askTimeout, askScheduler) match {
      case Success(_) =>
        crm.updateCommand(Completed(runId))
      case Failure(ex) =>
        crm.updateCommand(Error(runId, s"setAO failed: ${ex.getMessage}"))
    }
  }

  // ========================================
  // Helpers
  // ========================================

  /**
   * Send a command string to the controller via CI actor and wait for response.
   * Uses the ask pattern with SendCommand message.
   */
  private def sendToController(
    ciActor: ActorRef[GalilCommandMessage],
    cmdString: String,
    log: csw.logging.api.scaladsl.Logger,
    askTimeout: Timeout,
    askScheduler: org.apache.pekko.actor.typed.Scheduler
  ): Try[String] = {
    Try {
      val future = AskPattern.Askable(ciActor).ask[GalilCommandMessage.SendCommandResult](
        ref => GalilCommandMessage.SendCommand(cmdString, ref)
      )(askTimeout, askScheduler)
      val result = Await.result(future, askTimeout.duration)
      result.error match {
        case Some(errMsg) => throw new RuntimeException(errMsg)
        case None => result.response
      }
    }
  }
}