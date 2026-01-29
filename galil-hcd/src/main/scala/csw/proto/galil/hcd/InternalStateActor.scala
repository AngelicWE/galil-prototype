package csw.proto.galil.hcd

import org.apache.pekko.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import java.time.Instant

/**
 * Internal State Actor - Central repository for all HCD operational data.
 * 
 * As described in SDD Section 4.6.6, this actor:
 * - Maintains current values for HCD status, per-axis state, and I/O data
 * - Provides thread-safe access for all other actors
 * - Notifies interested actors when state changes occur
 * 
 * All state updates are atomic and thread-safe through the actor model.
 */
object InternalStateActor:
  
  // ========================================
  // Protocol
  // ========================================
  
  sealed trait Command
  
  /**
   * Update HCD-level state variables.
   * 
   * @param updates Map of field names to new values
   * @param replyTo Actor to send acknowledgment to
   */
  case class UpdateHcdState(
    updates: Map[String, Any], 
    replyTo: ActorRef[UpdateResponse]
  ) extends Command
  
  /**
   * Update state for a specific axis.
   * 
   * @param axis The axis to update (A-H)
   * @param updates Map of field names to new values
   * @param replyTo Actor to send acknowledgment to
   */
  case class UpdateAxisState(
    axis: Axis,
    updates: Map[String, Any],
    replyTo: ActorRef[UpdateResponse]
  ) extends Command
  
  /**
   * Query current HCD state.
   */
  case class GetHcdState(replyTo: ActorRef[HcdState]) extends Command
  
  /**
   * Query state for a specific axis.
   */
  case class GetAxisState(axis: Axis, replyTo: ActorRef[Option[AxisState]]) extends Command
  
  /**
   * Subscribe to state changes.
   * Subscriber will receive StateChanged messages when state is updated.
   * 
   * @param subscriber Actor to receive notifications
   * @param filter Optional filter for which changes to receive
   */
  case class Subscribe(
    subscriber: ActorRef[StateChanged],
    filter: Option[SubscriptionFilter] = None
  ) extends Command
  
  /**
   * Unsubscribe from state changes.
   */
  case class Unsubscribe(subscriber: ActorRef[StateChanged]) extends Command
  
  // ========================================
  // Responses
  // ========================================
  
  sealed trait Response
  
  case class UpdateResponse(success: Boolean, message: String = "") extends Response
  
  /**
   * Notification sent to subscribers when state changes.
   */
  case class StateChanged(
    hcdState: HcdState,
    changedFields: Set[String],
    changedAxes: Set[Axis]
  ) extends Response
  
  // ========================================
  // Subscription Filters
  // ========================================
  
  /**
   * Filter for subscription - allows selective notification.
   */
  sealed trait SubscriptionFilter:
    def matches(changedFields: Set[String], changedAxes: Set[Axis]): Boolean
  
  /**
   * Notify only when specific axes change.
   */
  case class AxisFilter(axes: Set[Axis]) extends SubscriptionFilter:
    def matches(changedFields: Set[String], changedAxes: Set[Axis]): Boolean =
      changedAxes.intersect(axes).nonEmpty
  
  /**
   * Notify only when specific fields change.
   */
  case class FieldFilter(fields: Set[String]) extends SubscriptionFilter:
    def matches(changedFields: Set[String], changedAxes: Set[Axis]): Boolean =
      changedFields.intersect(fields).nonEmpty
  
  /**
   * Notify when any axis reaches inPosition.
   */
  case object InPositionFilter extends SubscriptionFilter:
    def matches(changedFields: Set[String], changedAxes: Set[Axis]): Boolean =
      changedFields.contains("inPosition")
  
  // ========================================
  // Factory
  // ========================================
  
  def apply(initialState: HcdState = HcdState()): Behavior[Command] =
    Behaviors.setup { context =>
      new InternalStateActor(context, initialState)
    }

/**
 * Actor implementation using Pekko Typed.
 */
class InternalStateActor(
  context: ActorContext[InternalStateActor.Command],
  initialState: HcdState
) extends AbstractBehavior[InternalStateActor.Command](context):
  
  import InternalStateActor._
  
  // Current state (mutable, but only accessed within actor)
  private var currentState: HcdState = initialState
  
  // Subscribers to state changes
  private var subscribers: Set[ActorRef[StateChanged]] = Set.empty
  private var subscriptionFilters: Map[ActorRef[StateChanged], Option[SubscriptionFilter]] = Map.empty
  
  context.log.info("InternalStateActor started")
  
  override def onMessage(msg: Command): Behavior[Command] =
    msg match
      case UpdateHcdState(updates, replyTo) =>
        handleUpdateHcdState(updates, replyTo)
        
      case UpdateAxisState(axis, updates, replyTo) =>
        handleUpdateAxisState(axis, updates, replyTo)
        
      case GetHcdState(replyTo) =>
        replyTo ! currentState
        Behaviors.same
        
      case GetAxisState(axis, replyTo) =>
        replyTo ! currentState.getAxis(axis)
        Behaviors.same
        
      case Subscribe(subscriber, filter) =>
        context.log.debug(s"New subscriber: $subscriber")
        subscribers = subscribers + subscriber
        subscriptionFilters = subscriptionFilters + (subscriber -> filter)
        Behaviors.same
        
      case Unsubscribe(subscriber) =>
        context.log.debug(s"Unsubscribing: $subscriber")
        subscribers = subscribers - subscriber
        subscriptionFilters = subscriptionFilters - subscriber
        Behaviors.same
  
  /**
   * Update HCD-level state and notify subscribers.
   */
  private def handleUpdateHcdState(
    updates: Map[String, Any],
    replyTo: ActorRef[UpdateResponse]
  ): Behavior[Command] =
    try
      // Apply updates
      val oldState = currentState
      currentState = currentState.update(updates)
      
      // Notify subscribers
      notifySubscribers(updates.keySet, Set.empty)
      
      // Acknowledge
      replyTo ! UpdateResponse(success = true)
      Behaviors.same
    catch
      case ex: Exception =>
        context.log.error("Error updating HCD state", ex)
        replyTo ! UpdateResponse(success = false, message = ex.getMessage)
        Behaviors.same
  
  /**
   * Update axis state and notify subscribers.
   */
  private def handleUpdateAxisState(
    axis: Axis,
    updates: Map[String, Any],
    replyTo: ActorRef[UpdateResponse]
  ): Behavior[Command] =
    try
      // Get old axis state to detect changes
      val oldAxisState = currentState.getAxis(axis)
      
      // Apply updates
      currentState = currentState.updateAxis(axis, updates)
      
      // Get new axis state
      val newAxisState = currentState.getAxis(axis)
      
      // Detect ALL changed fields (including auto-calculated ones like inPosition)
      val allChangedFields = (oldAxisState, newAxisState) match
        case (Some(oldAxis), Some(newAxis)) =>
          var changed = updates.keySet
          // Check if inPosition changed (auto-calculated field)
          if oldAxis.inPosition != newAxis.inPosition then
            changed = changed + "inPosition"
          changed
        case _ =>
          updates.keySet
      
      // Notify subscribers
      notifySubscribers(allChangedFields, Set(axis))
      
      // Acknowledge
      replyTo ! UpdateResponse(success = true)
      Behaviors.same
    catch
      case ex: Exception =>
        context.log.error(s"Error updating axis $axis state", ex)
        replyTo ! UpdateResponse(success = false, message = ex.getMessage)
        Behaviors.same
  
  /**
   * Notify all subscribers that match the filter.
   */
  private def notifySubscribers(changedFields: Set[String], changedAxes: Set[Axis]): Unit =
    subscribers.foreach { subscriber =>
      val filter = subscriptionFilters.getOrElse(subscriber, None)
      
      // Check if this subscriber should be notified
      val shouldNotify = filter match
        case None => true  // No filter = notify always
        case Some(f) => f.matches(changedFields, changedAxes)
      
      if shouldNotify then
        subscriber ! StateChanged(currentState, changedFields, changedAxes)
    }