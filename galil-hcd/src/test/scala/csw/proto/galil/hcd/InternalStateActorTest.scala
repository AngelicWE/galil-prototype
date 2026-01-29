package csw.proto.galil.hcd

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration._

/**
 * Tests for Internal State Actor and State Model.
 * 
 * Validates:
 * - State model update logic
 * - InPosition calculation
 * - Actor state management
 * - Query operations
 * - Subscription mechanism
 */
class InternalStateActorTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:
  
  private val testKit = ActorTestKit()
  
  override def afterAll(): Unit =
    testKit.shutdownTestKit()
  
  // ========================================
  // State Model Tests
  // ========================================
  
  test("AxisState should calculate inPosition correctly") {
    val axis = AxisState(
      position = 100.0,
      demand = 100.0,
      inPositionThreshold = 0.1
    )
    
    axis.calculateInPosition should be (true)
    
    val outOfPosition = axis.copy(position = 100.5)
    outOfPosition.calculateInPosition should be (false)
    
    val justInPosition = axis.copy(position = 100.099)
    justInPosition.calculateInPosition should be (true)
  }
  
  test("AxisState.update should modify fields correctly") {
    val initial = AxisState()
    
    val updated = initial.update(Map(
      "position" -> 123.45,
      "velocity" -> 10.0,
      "axisState" -> AxisStateEnum.Moving,
      "activeCommand" -> ActiveCommand.Move
    ))
    
    updated.position should be (123.45)
    updated.velocity should be (10.0)
    updated.axisState should be (AxisStateEnum.Moving)
    updated.activeCommand should be (Some(ActiveCommand.Move))
  }
  
  test("AxisState.update should recalculate inPosition when position changes") {
    val initial = AxisState(
      position = 100.0,
      demand = 200.0,
      inPositionThreshold = 0.1
    )
    
    initial.inPosition should be (false)
    
    // Update position to be in range
    val updated = initial.update(Map("position" -> 200.05))
    updated.inPosition should be (true)
  }
  
  test("HcdState should initialize axes correctly") {
    val state = HcdState()
    
    val withAxisA = state.initializeAxis(Axis.A, MechanismType.Linear)
    
    withAxisA.activeAxes(Axis.A.index) should be (true)
    withAxisA.getAxis(Axis.A) should not be (None)
    withAxisA.getAxis(Axis.A).get.mechanismType should be (MechanismType.Linear)
  }
  
  test("HcdState.updateAxis should update specific axis") {
    val state = HcdState().initializeAxis(Axis.A)
    
    val updated = state.updateAxis(Axis.A, Map(
      "position" -> 500.0,
      "axisState" -> AxisStateEnum.Moving
    ))
    
    val axisA = updated.getAxis(Axis.A).get
    axisA.position should be (500.0)
    axisA.axisState should be (AxisStateEnum.Moving)
  }
  
  test("HcdState.update should update HCD-level fields") {
    val initial = HcdState()
    
    val updated = initial.update(Map(
      "state" -> HcdStateEnum.Faulted,
      "controllerErrorMsg" -> "Test error",
      "version" -> 12345,
      "debug" -> true
    ))
    
    updated.state should be (HcdStateEnum.Faulted)
    updated.controllerErrorMsg should be ("Test error")
    updated.version should be (12345)
    updated.debug should be (true)
  }
  
  // ========================================
  // Actor Tests - Basic Operations
  // ========================================
  
  test("InternalStateActor should handle HCD state updates") {
    val actor = testKit.spawn(InternalStateActor())
    val probe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    
    // Update HCD state
    actor ! InternalStateActor.UpdateHcdState(
      Map("version" -> 99999, "debug" -> true),
      probe.ref
    )
    
    // Should get acknowledgment
    val response = probe.receiveMessage()
    response.success should be (true)
    
    // Verify state was updated
    val queryProbe = testKit.createTestProbe[HcdState]()
    actor ! InternalStateActor.GetHcdState(queryProbe.ref)
    
    val state = queryProbe.receiveMessage()
    state.version should be (99999)
    state.debug should be (true)
  }
  
  test("InternalStateActor should handle axis state updates") {
    val initialState = HcdState().initializeAxis(Axis.A)
    val actor = testKit.spawn(InternalStateActor(initialState))
    val probe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    
    // Update axis A
    actor ! InternalStateActor.UpdateAxisState(
      Axis.A,
      Map("position" -> 100.0, "velocity" -> 5.0),
      probe.ref
    )
    
    // Should get acknowledgment
    val response = probe.receiveMessage()
    response.success should be (true)
    
    // Verify axis was updated
    val queryProbe = testKit.createTestProbe[Option[AxisState]]()
    actor ! InternalStateActor.GetAxisState(Axis.A, queryProbe.ref)
    
    val axisState = queryProbe.receiveMessage()
    axisState should not be (None)
    axisState.get.position should be (100.0)
    axisState.get.velocity should be (5.0)
  }
  
  test("InternalStateActor should query non-existent axis") {
    val actor = testKit.spawn(InternalStateActor())
    val probe = testKit.createTestProbe[Option[AxisState]]()
    
    // Query axis that doesn't exist
    actor ! InternalStateActor.GetAxisState(Axis.B, probe.ref)
    
    val result = probe.receiveMessage()
    result should be (None)
  }
  
  // ========================================
  // Subscription Tests
  // ========================================
  
  test("InternalStateActor should notify subscribers on HCD state changes") {
    val actor = testKit.spawn(InternalStateActor())
    val subscriberProbe = testKit.createTestProbe[InternalStateActor.StateChanged]()
    
    // Subscribe
    actor ! InternalStateActor.Subscribe(subscriberProbe.ref)
    
    // Update state
    val updateProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    actor ! InternalStateActor.UpdateHcdState(
      Map("version" -> 123),
      updateProbe.ref
    )
    updateProbe.receiveMessage()  // Wait for update to complete
    
    // Should receive notification
    val notification = subscriberProbe.receiveMessage()
    notification.changedFields should contain ("version")
    notification.hcdState.version should be (123)
  }
  
  test("InternalStateActor should notify subscribers on axis state changes") {
    val initialState = HcdState().initializeAxis(Axis.C)
    val actor = testKit.spawn(InternalStateActor(initialState))
    val subscriberProbe = testKit.createTestProbe[InternalStateActor.StateChanged]()
    
    // Subscribe
    actor ! InternalStateActor.Subscribe(subscriberProbe.ref)
    
    // Update axis
    val updateProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    actor ! InternalStateActor.UpdateAxisState(
      Axis.C,
      Map("position" -> 999.0),
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // Should receive notification
    val notification = subscriberProbe.receiveMessage()
    notification.changedAxes should contain (Axis.C)
    notification.changedFields should contain ("position")
  }
  
  test("InternalStateActor should support filtered subscriptions") {
    val initialState = HcdState()
      .initializeAxis(Axis.A)
      .initializeAxis(Axis.B)
    
    val actor = testKit.spawn(InternalStateActor(initialState))
    val subscriberProbe = testKit.createTestProbe[InternalStateActor.StateChanged]()
    
    // Subscribe only to Axis A changes
    val filter = InternalStateActor.AxisFilter(Set(Axis.A))
    actor ! InternalStateActor.Subscribe(subscriberProbe.ref, Some(filter))
    
    // Update Axis B (should NOT notify)
    val updateProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    actor ! InternalStateActor.UpdateAxisState(
      Axis.B,
      Map("position" -> 100.0),
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // Should NOT receive notification
    subscriberProbe.expectNoMessage(200.millis)
    
    // Update Axis A (should notify)
    actor ! InternalStateActor.UpdateAxisState(
      Axis.A,
      Map("position" -> 200.0),
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // Should receive notification
    val notification = subscriberProbe.receiveMessage()
    notification.changedAxes should contain (Axis.A)
  }
  
  test("InternalStateActor should support unsubscribe") {
    val actor = testKit.spawn(InternalStateActor())
    val subscriberProbe = testKit.createTestProbe[InternalStateActor.StateChanged]()
    
    // Subscribe
    actor ! InternalStateActor.Subscribe(subscriberProbe.ref)
    
    // Unsubscribe
    actor ! InternalStateActor.Unsubscribe(subscriberProbe.ref)
    
    // Update state
    val updateProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    actor ! InternalStateActor.UpdateHcdState(
      Map("version" -> 456),
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // Should NOT receive notification
    subscriberProbe.expectNoMessage(200.millis)
  }
  
  // ========================================
  // Integration Tests
  // ========================================
  
  test("InternalStateActor should support multiple simultaneous subscribers") {
    val actor = testKit.spawn(InternalStateActor())
    val subscriber1 = testKit.createTestProbe[InternalStateActor.StateChanged]()
    val subscriber2 = testKit.createTestProbe[InternalStateActor.StateChanged]()
    val subscriber3 = testKit.createTestProbe[InternalStateActor.StateChanged]()
    
    // All subscribe
    actor ! InternalStateActor.Subscribe(subscriber1.ref)
    actor ! InternalStateActor.Subscribe(subscriber2.ref)
    actor ! InternalStateActor.Subscribe(subscriber3.ref)
    
    // Update
    val updateProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    actor ! InternalStateActor.UpdateHcdState(
      Map("simulation" -> true),
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // All should receive notification
    subscriber1.receiveMessage().hcdState.simulation should be (true)
    subscriber2.receiveMessage().hcdState.simulation should be (true)
    subscriber3.receiveMessage().hcdState.simulation should be (true)
  }
  
  test("InternalStateActor should track command completion scenario") {
    // Simulate a command completion workflow
    // Initialize with demand != position so inPosition starts as false
    val initialState = HcdState()
      .initializeAxis(Axis.D)
      .updateAxis(Axis.D, Map("demand" -> 100.0))  // position=0, demand=100, so inPosition=false
    
    val actor = testKit.spawn(InternalStateActor(initialState))
    
    val commandWatcher = testKit.createTestProbe[InternalStateActor.StateChanged]()
    val updateProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    
    // CommandWatcher subscribes to Axis D with inPosition filter
    actor ! InternalStateActor.Subscribe(
      commandWatcher.ref,
      Some(InternalStateActor.InPositionFilter)
    )
    
    // 1. Command starts - set demand and activeCommand
    actor ! InternalStateActor.UpdateAxisState(
      Axis.D,
      Map(
        "demand" -> 500.0,
        "activeCommand" -> ActiveCommand.Move,
        "thread" -> 3,
        "axisState" -> AxisStateEnum.Moving
      ),
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // CommandWatcher should NOT be notified (inPosition still false)
    commandWatcher.expectNoMessage(200.millis)
    
    // 2. Motor is moving - position updates but not at target
    actor ! InternalStateActor.UpdateAxisState(
      Axis.D,
      Map("position" -> 250.0),
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // Still not in position
    commandWatcher.expectNoMessage(200.millis)
    
    // 3. Motor reaches target
    actor ! InternalStateActor.UpdateAxisState(
      Axis.D,
      Map(
        "position" -> 500.0,  // This will trigger inPosition = true
        "velocity" -> 0.0,
        "axisState" -> AxisStateEnum.Idle
      ),
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // CommandWatcher SHOULD be notified (inPosition changed from false to true)
    val notification = commandWatcher.receiveMessage()
    notification.changedFields should contain ("inPosition")
    notification.hcdState.getAxis(Axis.D).get.inPosition should be (true)
    notification.hcdState.getAxis(Axis.D).get.position should be (500.0)
  }