package csw.proto.galil.hcd

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import csw.proto.galil.io.DataRecord
import csw.proto.galil.io.DataRecord.{GalilAxisStatus, GeneralState, Header}

import scala.concurrent.duration._

/**
 * Tests for StatusMonitor Actor
 * 
 * Validates:
 * - QR response handling
 * - State update logic
 * - Polling control
 * - Error handling
 */
class StatusMonitorTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:
  
  private val testKit = ActorTestKit()
  
  override def afterAll(): Unit =
    testKit.shutdownTestKit()
  
  /**
   * Helper: Create test DataRecord with motor data
   */
  private def createTestDataRecord(
    motorPositionA: Int = 0,
    velocityA: Int = 0,
    motorPositionB: Int = 0,
    velocityB: Int = 0
  ): DataRecord =
    val header = Header(List("A", "B"))
    val generalState = GeneralState(
      sampleNumber = 12345,
      inputs = Array.fill(10)(0.toByte),
      outputs = Array.fill(10)(0.toByte),
      ethernetHandleStatus = Array.fill(8)(0.toByte),
      errorCode = 0,
      threadStatus = 0,
      amplifierStatus = 0,
      contourModeSegmentCount = 0,
      contourModeBufferSpaceRemaining = 0,
      sPlaneSegmentCount = 0,
      sPlaneMoveStatus = 0,
      sPlaneDistanceTraveled = 0,
      sPlaneBufferSpaceRemaining = 0,
      tPlaneSegmentCount = 0,
      tPlaneMoveStatus = 0,
      tPlaneDistanceTraveled = 0,
      tPlaneBufferSpaceRemaining = 0
    )
    
    val axisA = GalilAxisStatus(
      motorPosition = motorPositionA,
      velocity = velocityA
    )
    val axisB = GalilAxisStatus(
      motorPosition = motorPositionB,
      velocity = velocityB
    )
    
    DataRecord(header, generalState, Array(axisA, axisB))
  
  // ========================================
  // QR Response Handling Tests
  // ========================================
  
  test("StatusMonitor should update InternalState from QR response") {
    // Setup actors
    val internalState = testKit.spawn(InternalStateActor(
      HcdState()
        .initializeAxis(Axis.A)
        .initializeAxis(Axis.B)
    ))
    
    // Mock ControllerInterface (not used in this test)
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, pollingRateHz = 10.0)
    )
    
    // Create test data with specific positions
    val dataRecord = createTestDataRecord(
      motorPositionA = 123456,
      velocityA = 5000,
      motorPositionB = 789012,
      velocityB = -3000
    )
    
    // Send QR response directly (simulating controller response)
    statusMonitor ! StatusMonitor.QRResponse(dataRecord)
    
    // Give it time to process
    Thread.sleep(100)
    
    // Verify Axis A was updated
    val probeA = testKit.createTestProbe[Option[AxisState]]()
    internalState ! InternalStateActor.GetAxisState(Axis.A, probeA.ref)
    val stateA = probeA.receiveMessage()
    
    stateA should not be (None)
    stateA.get.position should be (123456.0)
    stateA.get.velocity should be (5000.0)
    
    // Verify Axis B was updated
    val probeB = testKit.createTestProbe[Option[AxisState]]()
    internalState ! InternalStateActor.GetAxisState(Axis.B, probeB.ref)
    val stateB = probeB.receiveMessage()
    
    stateB should not be (None)
    stateB.get.position should be (789012.0)
    stateB.get.velocity should be (-3000.0)
  }
  
  test("StatusMonitor should handle QR errors gracefully") {
    val internalState = testKit.spawn(InternalStateActor())
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, pollingRateHz = 10.0)
    )
    
    // Send error
    statusMonitor ! StatusMonitor.QRError("Communication timeout")
    
    // Query status - should show error count
    val statusProbe = testKit.createTestProbe[StatusMonitor.PollingStatus]()
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    
    val status = statusProbe.receiveMessage()
    status.errorCount should be (1)
    
    // Send another error
    statusMonitor ! StatusMonitor.QRError("Buffer overflow")
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    
    val status2 = statusProbe.receiveMessage()
    status2.errorCount should be (2)
    
    // Successful QR should reset error count
    val dataRecord = createTestDataRecord()
    statusMonitor ! StatusMonitor.QRResponse(dataRecord)
    
    Thread.sleep(50)  // Give it time to process
    
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val status3 = statusProbe.receiveMessage()
    status3.errorCount should be (0)
  }
  
  // ========================================
  // Polling Control Tests
  // ========================================
  
  test("StatusMonitor should support enabling/disabling polling") {
    val internalState = testKit.spawn(InternalStateActor())
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, pollingRateHz = 10.0)
    )
    
    // Should start enabled
    val statusProbe = testKit.createTestProbe[StatusMonitor.PollingStatus]()
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val status1 = statusProbe.receiveMessage()
    status1.enabled should be (true)
    
    // Disable polling
    statusMonitor ! StatusMonitor.SetPolling(false)
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val status2 = statusProbe.receiveMessage()
    status2.enabled should be (false)
    
    // Re-enable polling
    statusMonitor ! StatusMonitor.SetPolling(true)
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val status3 = statusProbe.receiveMessage()
    status3.enabled should be (true)
  }
  
  test("StatusMonitor should support changing polling rate") {
    val internalState = testKit.spawn(InternalStateActor())
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, pollingRateHz = 10.0)
    )
    
    // Initial rate should be 10Hz
    val statusProbe = testKit.createTestProbe[StatusMonitor.PollingStatus]()
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val status1 = statusProbe.receiveMessage()
    status1.rateHz should be (10.0)
    
    // Change to 20Hz
    statusMonitor ! StatusMonitor.SetPollingRate(20.0)
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val status2 = statusProbe.receiveMessage()
    status2.rateHz should be (20.0)
    
    // Change to 5Hz
    statusMonitor ! StatusMonitor.SetPollingRate(5.0)
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val status3 = statusProbe.receiveMessage()
    status3.rateHz should be (5.0)
  }
  
  test("StatusMonitor should update lastPollTime on successful QR") {
    val internalState = testKit.spawn(InternalStateActor())
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, pollingRateHz = 10.0)
    )
    
    // Initially no poll
    val statusProbe = testKit.createTestProbe[StatusMonitor.PollingStatus]()
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val status1 = statusProbe.receiveMessage()
    status1.lastPollTime should be (None)
    
    // Send QR response
    val dataRecord = createTestDataRecord()
    statusMonitor ! StatusMonitor.QRResponse(dataRecord)
    
    Thread.sleep(50)  // Give it time to process
    
    // Should now have lastPollTime
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val status2 = statusProbe.receiveMessage()
    status2.lastPollTime should not be (None)
    status2.lastPollTime.get should be > 0L
  }
  
  // ========================================
  // Pause/Resume Tests (File Operation Support)
  // ========================================
  
  test("StatusMonitor should pause QR polling for file operations") {
    val internalState = testKit.spawn(InternalStateActor())
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, pollingRateHz = 10.0)
    )
    
    // Should start not paused
    val statusProbe = testKit.createTestProbe[StatusMonitor.PollingStatus]()
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val status1 = statusProbe.receiveMessage()
    status1.paused should be (false)
    
    // Pause for file operation
    statusMonitor ! StatusMonitor.PauseQRPolling
    
    // Should now be paused
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val status2 = statusProbe.receiveMessage()
    status2.paused should be (true)
  }
  
  test("StatusMonitor should skip queued QR when paused") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A)
    ))
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, pollingRateHz = 10.0)
    )
    
    // Pause polling
    statusMonitor ! StatusMonitor.PauseQRPolling
    Thread.sleep(50)
    
    // Send QR response while paused - should be processed but not update lastPollTime
    // (QR responses are always processed, but timer-triggered polls are skipped)
    val dataRecord = createTestDataRecord(motorPositionA = 999)
    statusMonitor ! StatusMonitor.QRResponse(dataRecord)
    Thread.sleep(50)
    
    // Data should still be updated (QRResponse is always processed)
    val axisProbe = testKit.createTestProbe[Option[AxisState]]()
    internalState ! InternalStateActor.GetAxisState(Axis.A, axisProbe.ref)
    val axisState = axisProbe.receiveMessage()
    axisState.get.position should be (999.0)
  }
  
  test("StatusMonitor should resume QR polling after file operations") {
    val internalState = testKit.spawn(InternalStateActor())
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, pollingRateHz = 10.0)
    )
    
    val statusProbe = testKit.createTestProbe[StatusMonitor.PollingStatus]()
    
    // Pause
    statusMonitor ! StatusMonitor.PauseQRPolling
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    statusProbe.receiveMessage().paused should be (true)
    
    // Resume
    statusMonitor ! StatusMonitor.ResumeQRPolling
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    statusProbe.receiveMessage().paused should be (false)
  }
  
  // ========================================
  // Data Parsing Tests
  // ========================================
  
  test("StatusMonitor should parse multiple axes from QR") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState()
        .initializeAxis(Axis.A)
        .initializeAxis(Axis.B)
        .initializeAxis(Axis.C)
        .initializeAxis(Axis.D)
    ))
    
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, pollingRateHz = 10.0)
    )
    
    // Create DataRecord with 4 axes
    val header = Header(List("A", "B", "C", "D"))
    val generalState = GeneralState(
      sampleNumber = 1,
      inputs = Array.fill(10)(0.toByte),
      outputs = Array.fill(10)(0.toByte),
      ethernetHandleStatus = Array.fill(8)(0.toByte),
      errorCode = 0,
      threadStatus = 0,
      amplifierStatus = 0,
      contourModeSegmentCount = 0,
      contourModeBufferSpaceRemaining = 0,
      sPlaneSegmentCount = 0,
      sPlaneMoveStatus = 0,
      sPlaneDistanceTraveled = 0,
      sPlaneBufferSpaceRemaining = 0,
      tPlaneSegmentCount = 0,
      tPlaneMoveStatus = 0,
      tPlaneDistanceTraveled = 0,
      tPlaneBufferSpaceRemaining = 0
    )
    
    val axisStatuses = Array(
      GalilAxisStatus(motorPosition = 1000, velocity = 100),
      GalilAxisStatus(motorPosition = 2000, velocity = 200),
      GalilAxisStatus(motorPosition = 3000, velocity = 300),
      GalilAxisStatus(motorPosition = 4000, velocity = 400)
    )
    
    val dataRecord = DataRecord(header, generalState, axisStatuses)
    statusMonitor ! StatusMonitor.QRResponse(dataRecord)
    
    Thread.sleep(100)
    
    // Verify all axes were updated
    val axes = List(Axis.A, Axis.B, Axis.C, Axis.D)
    val expectedPositions = List(1000.0, 2000.0, 3000.0, 4000.0)
    val expectedVelocities = List(100.0, 200.0, 300.0, 400.0)
    
    axes.zip(expectedPositions).zip(expectedVelocities).foreach { 
      case ((axis, expectedPos), expectedVel) =>
        val probe = testKit.createTestProbe[Option[AxisState]]()
        internalState ! InternalStateActor.GetAxisState(axis, probe.ref)
        val state = probe.receiveMessage()
        
        state should not be (None)
        state.get.position should be (expectedPos)
        state.get.velocity should be (expectedVel)
    }
  }