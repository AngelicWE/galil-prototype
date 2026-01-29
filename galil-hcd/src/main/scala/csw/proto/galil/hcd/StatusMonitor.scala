package csw.proto.galil.hcd

import org.apache.pekko.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors, TimerScheduler}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import csw.proto.galil.io.DataRecord
import csw.proto.galil.io.DataRecord.{GalilAxisStatus, GeneralState}

import java.time.Instant
import scala.concurrent.duration._

/**
 * StatusMonitor Actor (SDD Section 4.6.3)
 * 
 * Responsibilities:
 * - Periodically poll controller with QR command
 * - Parse DataRecord response
 * - Update InternalStateActor with current positions, velocities, switches, etc.
 * - Handle errors and maintain polling even on failures
 * 
 * Integration:
 * - Requests QR from ControllerInterfaceActor
 * - Updates state via InternalStateActor
 * - Runs at configurable rate (default: 10Hz / 100ms)
 */
object StatusMonitor:
  
  // Protocol
  sealed trait Command
  
  /**
   * Periodic polling trigger (internal timer message)
   */
  private case object PollController extends Command
  
  /**
   * Response from ControllerInterface with QR data
   */
  case class QRResponse(dataRecord: DataRecord) extends Command
  
  /**
   * Error from ControllerInterface
   */
  case class QRError(error: String) extends Command
  
  /**
   * Command to pause QR polling (for file operations - UL/DL)
   * CRITICAL: Must be called before file operations to prevent buffer corruption
   */
  case object PauseQRPolling extends Command
  
  /**
   * Command to resume QR polling (after file operations)
   */
  case object ResumeQRPolling extends Command
  
  /**
   * Command to start/stop polling (deprecated - use Pause/Resume instead)
   */
  case class SetPolling(enabled: Boolean) extends Command
  
  /**
   * Command to change polling rate
   */
  case class SetPollingRate(rateHz: Double) extends Command
  
  /**
   * Query current polling status
   */
  case class GetPollingStatus(replyTo: ActorRef[PollingStatus]) extends Command
  
  /**
   * Response to GetPollingStatus
   */
  case class PollingStatus(
    enabled: Boolean, 
    rateHz: Double, 
    lastPollTime: Option[Long], 
    errorCount: Int,
    paused: Boolean  // NEW: Indicates if paused for file operations
  )
  
  /**
   * Create StatusMonitor actor
   * 
   * @param controllerInterface Actor to request QR data from
   * @param internalState Actor to update with parsed data
   * @param pollingRateHz Polling frequency in Hz (default: 10Hz = 100ms period)
   */
  def apply(
    controllerInterface: ActorRef[GalilCommandMessage],
    internalState: ActorRef[InternalStateActor.Command],
    pollingRateHz: Double = 10.0
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new StatusMonitor(context, timers, controllerInterface, internalState, pollingRateHz)
      }
    }

/**
 * Actor implementation
 */
class StatusMonitor(
  context: ActorContext[StatusMonitor.Command],
  timers: TimerScheduler[StatusMonitor.Command],
  controllerInterface: ActorRef[GalilCommandMessage],
  internalState: ActorRef[InternalStateActor.Command],
  initialPollingRateHz: Double
) extends AbstractBehavior[StatusMonitor.Command](context):
  
  import StatusMonitor._
  
  // Current state (mutable, but only accessed within actor)
  private var pollingEnabled: Boolean = true
  private var pollingPaused: Boolean = false  // NEW: Pause for file operations
  private var pollingRateHz: Double = initialPollingRateHz
  private var lastPollTime: Option[Long] = None
  private var errorCount: Int = 0
  
  // Start periodic polling
  startPolling()
  
  context.log.info(s"StatusMonitor started with polling rate: ${pollingRateHz}Hz (${pollingPeriod.toMillis}ms)")
  
  override def onMessage(msg: Command): Behavior[Command] =
    msg match
      case PollController =>
        handlePollController()
        
      case QRResponse(dataRecord) =>
        handleQRResponse(dataRecord)
        
      case QRError(error) =>
        handleQRError(error)
        
      case PauseQRPolling =>
        handlePauseQRPolling()
        
      case ResumeQRPolling =>
        handleResumeQRPolling()
        
      case SetPolling(enabled) =>
        handleSetPolling(enabled)
        
      case SetPollingRate(newRateHz) =>
        handleSetPollingRate(newRateHz)
        
      case GetPollingStatus(replyTo) =>
        replyTo ! PollingStatus(pollingEnabled, pollingRateHz, lastPollTime, errorCount, pollingPaused)
        Behaviors.same
  
  /**
   * Handle periodic poll trigger
   * 
   * CRITICAL: Checks pollingPaused flag to prevent interference with file operations
   */
  private def handlePollController(): Behavior[Command] =
    // Guard: Skip if paused for file operations (handles queued timer messages)
    if pollingPaused then
      context.log.debug("Skipping QR - polling is paused for file operation")
      return Behaviors.same
    
    if pollingEnabled then
      context.log.debug("Polling controller for QR data")
      
      // Request QR from ControllerInterface
      // NOTE: Actual implementation depends on ControllerInterfaceActor protocol
      // For now, we'll use a simplified approach - this will need to be updated
      // when ControllerInterfaceActor protocol is finalized
      
      // TODO: Replace with actual ControllerInterface protocol
      // controllerInterface ! ControllerInterfaceActor.GetQR(context.self.narrow[QRResponse])
      
      context.log.warn("QR request not yet implemented - waiting for ControllerInterface protocol")
    
    Behaviors.same
  
  /**
   * Handle QR response from controller
   */
  private def handleQRResponse(dataRecord: DataRecord): Behavior[Command] =
    try
      lastPollTime = Some(System.currentTimeMillis())
      errorCount = 0  // Reset error count on success
      
      context.log.debug(s"Received QR data, sample: ${dataRecord.generalState.sampleNumber}")
      
      // Update HCD-level state
      updateHcdState(dataRecord.generalState)
      
      // Update each active axis
      dataRecord.header.blocksPresent
        .filter(axis => DataRecord.axes.contains(axis))
        .zip(dataRecord.axisStatuses)
        .foreach { case (axisChar, axisStatus) =>
          updateAxisState(axisChar, axisStatus)
        }
      
      Behaviors.same
    catch
      case ex: Exception =>
        context.log.error(s"Error processing QR response: ${ex.getMessage}", ex)
        errorCount += 1
        Behaviors.same
  
  /**
   * Handle QR error from controller
   */
  private def handleQRError(error: String): Behavior[Command] =
    context.log.error(s"QR request failed: $error")
    errorCount += 1
    Behaviors.same
  
  /**
   * Pause QR polling for file operations (UL/DL)
   * 
   * CRITICAL: Must be called BEFORE file operations to prevent buffer corruption.
   * Pattern from existing ControllerInterfaceActor:
   * 1. Set pause flag (prevents new QR requests)
   * 2. Cancel timer (stops scheduling new requests)
   * 3. Caller should wait ~100ms for in-flight QR to complete
   * 4. Then safe to execute file operation
   */
  private def handlePauseQRPolling(): Behavior[Command] =
    if !pollingPaused then
      context.log.info("Pausing QR polling for file operation")
      pollingPaused = true
      stopPolling()
    else
      context.log.debug("QR polling already paused")
    Behaviors.same
  
  /**
   * Resume QR polling after file operations
   */
  private def handleResumeQRPolling(): Behavior[Command] =
    if pollingPaused then
      context.log.info("Resuming QR polling after file operation")
      pollingPaused = false
      if pollingEnabled then
        startPolling()
    else
      context.log.debug("QR polling was not paused")
    Behaviors.same
  
  /**
   * Enable/disable polling
   */
  private def handleSetPolling(enabled: Boolean): Behavior[Command] =
    if enabled != pollingEnabled then
      pollingEnabled = enabled
      if enabled then
        context.log.info("Polling enabled")
        startPolling()
      else
        context.log.info("Polling disabled")
        stopPolling()
    Behaviors.same
  
  /**
   * Change polling rate
   */
  private def handleSetPollingRate(newRateHz: Double): Behavior[Command] =
    if newRateHz > 0 && newRateHz != pollingRateHz then
      pollingRateHz = newRateHz
      context.log.info(s"Polling rate changed to ${pollingRateHz}Hz (${pollingPeriod.toMillis}ms)")
      if pollingEnabled then
        stopPolling()
        startPolling()
    Behaviors.same
  
  /**
   * Update HCD-level state from GeneralState
   */
  private def updateHcdState(generalState: GeneralState): Unit =
    val updates = Map(
      "digitalInputs" -> generalState.inputs.map(_ != 0),
      "digitalOutputs" -> generalState.outputs.map(_ != 0),
      "lastPollingTime" -> Instant.ofEpochMilli(System.currentTimeMillis())
    )
    
    // Send update (fire and forget - we don't wait for response)
    internalState ! InternalStateActor.UpdateHcdState(updates, context.system.ignoreRef)
  
  /**
   * Update axis state from GalilAxisStatus
   */
  private def updateAxisState(axisChar: Char, axisStatus: GalilAxisStatus): Unit =
    // Map axis character to Axis enum
    val axis = Axis.fromChar(axisChar)
    
    // Build update map with motor data
    val updates = Map(
      "position" -> axisStatus.motorPosition.toDouble,
      "velocity" -> axisStatus.velocity.toDouble,
      "positionError" -> axisStatus.positionError.toDouble,
      "switches" -> parseSwitches(axisStatus.switches)
      // TODO: Map other fields as needed
    )
    
    // Send update (fire and forget)
    internalState ! InternalStateActor.UpdateAxisState(axis, updates, context.system.ignoreRef)
  
  /**
   * Parse switch byte into boolean array
   */
  private def parseSwitches(switchByte: Byte): Array[Boolean] =
    (0 until 7).map(i => (switchByte & (1 << i)) != 0).toArray
  
  /**
   * Calculate polling period from rate
   */
  private def pollingPeriod: FiniteDuration =
    (1000.0 / pollingRateHz).toInt.milliseconds
  
  /**
   * Start periodic polling timer
   */
  private def startPolling(): Unit =
    timers.startTimerWithFixedDelay(PollController, pollingPeriod)
  
  /**
   * Stop polling timer
   */
  private def stopPolling(): Unit =
    timers.cancel(PollController)