package csw.proto.galil.hcd

import java.time.Instant

/**
 * State model for the GalilMotion HCD Internal State Actor.
 * 
 * This represents the complete internal state as defined in SDD Section 4.5.
 * All state is immutable and thread-safe.
 */

// ========================================
// Enums
// ========================================

enum HcdStateEnum:
  case Ready, Faulted

enum AxisStateEnum:
  case Lost, Idle, Moving, Tracking, Error

enum MechanismType:
  case Linear, Rotating

enum RotatingAlgorithm:
  case Forward, Reverse, Shortest

enum ActiveCommand:
  case Home, Move, Track, Select, Stop

enum Axis:
  case A, B, C, D, E, F, G, H
  
  def index: Int = this.ordinal
  def char: Char = this.toString.head

object Axis:
  /**
   * Convert character to Axis enum
   */
  def fromChar(c: Char): Axis = c.toUpper match
    case 'A' => Axis.A
    case 'B' => Axis.B
    case 'C' => Axis.C
    case 'D' => Axis.D
    case 'E' => Axis.E
    case 'F' => Axis.F
    case 'G' => Axis.G
    case 'H' => Axis.H
    case _ => throw new IllegalArgumentException(s"Invalid axis: $c")

// ========================================
// Per-Axis State
// ========================================

/**
 * State for a single axis (A-H).
 * 
 * @param axisState Current state of the axis
 * @param axisError Error message if in error state
 * @param position Current motor position
 * @param velocity Current motor velocity
 * @param positionError Current position error
 * @param switches Status of 7 switches [forward limit, reverse limit, home switch, 
 *                 stepper, moving, negative direction, motor off]
 * @param demand Requested motor target (for calculating inPosition)
 * @param mechanismType Type of mechanism (linear or rotating)
 * @param upperLimit Upper soft limit (for linear mechanisms)
 * @param lowerLimit Lower soft limit (for linear mechanisms)
 * @param algorithm Target approach algorithm (for rotating mechanisms)
 * @param inPositionThreshold Threshold for calculating inPosition
 * @param inPosition Whether axis is in position (calculated)
 * @param activeCommand Currently active command if any
 * @param thread Thread number command is running on (-1 for none)
 * @param commandHalted Flag set when command is interrupted
 */
case class AxisState(
  axisState: AxisStateEnum = AxisStateEnum.Idle,
  axisError: String = "",
  position: Double = 0.0,
  velocity: Double = 0.0,
  positionError: Double = 0.0,
  switches: Array[Boolean] = Array.fill(7)(false),
  demand: Double = 0.0,
  mechanismType: MechanismType = MechanismType.Linear,
  upperLimit: Option[Double] = None,
  lowerLimit: Option[Double] = None,
  algorithm: Option[RotatingAlgorithm] = None,
  inPositionThreshold: Double = 0.001,
  inPosition: Boolean = false,
  activeCommand: Option[ActiveCommand] = None,
  thread: Int = -1,
  commandHalted: Boolean = false
):
  /**
   * Calculate inPosition based on position, demand, and threshold.
   */
  def calculateInPosition: Boolean = 
    Math.abs(position - demand) <= inPositionThreshold
  
  /**
   * Update this axis state with new values.
   * Uses a map of field names to values.
   */
  def update(updates: Map[String, Any]): AxisState =
    var updated = this
    
    updates.foreach {
      case ("axisState", v: AxisStateEnum) => updated = updated.copy(axisState = v)
      case ("axisError", v: String) => updated = updated.copy(axisError = v)
      case ("position", v: Double) => updated = updated.copy(position = v)
      case ("velocity", v: Double) => updated = updated.copy(velocity = v)
      case ("positionError", v: Double) => updated = updated.copy(positionError = v)
      case ("switches", v: Array[Boolean]) => updated = updated.copy(switches = v)
      case ("demand", v: Double) => updated = updated.copy(demand = v)
      case ("mechanismType", v: MechanismType) => updated = updated.copy(mechanismType = v)
      case ("upperLimit", v: Double) => updated = updated.copy(upperLimit = Some(v))
      case ("lowerLimit", v: Double) => updated = updated.copy(lowerLimit = Some(v))
      case ("algorithm", v: RotatingAlgorithm) => updated = updated.copy(algorithm = Some(v))
      case ("inPositionThreshold", v: Double) => updated = updated.copy(inPositionThreshold = v)
      case ("inPosition", v: Boolean) => updated = updated.copy(inPosition = v)
      case ("activeCommand", v: ActiveCommand) => updated = updated.copy(activeCommand = Some(v))
      case ("thread", v: Int) => updated = updated.copy(thread = v)
      case ("commandHalted", v: Boolean) => updated = updated.copy(commandHalted = v)
      case (key, value) => 
        // Log unknown keys but don't fail
        println(s"Warning: Unknown axis state field: $key = $value")
    }
    
    // Recalculate inPosition if position or demand changed
    if (updates.contains("position") || updates.contains("demand") || updates.contains("inPositionThreshold"))
      updated.copy(inPosition = updated.calculateInPosition)
    else
      updated

// ========================================
// HCD-Level State
// ========================================

/**
 * Overall HCD state.
 * 
 * @param state Current HCD state
 * @param controllerId Controller number (1-4)
 * @param controllerErrorMsg Controller error message
 * @param version Embedded version number
 * @param activeAxes Which axes (A-H) are configured for use
 * @param digitalInputs Current values of optoisolated inputs (16 bits)
 * @param digitalOutputs Current values of optoisolated outputs (16 bits)
 * @param analogInputs Current values of analog inputs (8 channels)
 * @param lastPollingTime Timestamp of last status monitor execution
 * @param debug Verbose logging flag
 * @param simulation Software-only simulation mode
 * @param axes State for each configured axis
 */
case class HcdState(
  state: HcdStateEnum = HcdStateEnum.Ready,
  controllerId: Int = 1,
  controllerErrorMsg: String = "",
  version: Int = 0,
  activeAxes: Array[Boolean] = Array.fill(8)(false),
  digitalInputs: Array[Boolean] = Array.fill(16)(false),
  digitalOutputs: Array[Boolean] = Array.fill(16)(false),
  analogInputs: Array[Float] = Array.fill(8)(0.0f),
  lastPollingTime: Instant = Instant.EPOCH,
  debug: Boolean = false,
  simulation: Boolean = false,
  axes: Map[Axis, AxisState] = Map.empty
):
  /**
   * Update this HCD state with new values.
   * Uses a map of field names to values.
   */
  def update(updates: Map[String, Any]): HcdState =
    var updated = this
    
    updates.foreach {
      case ("state", v: HcdStateEnum) => updated = updated.copy(state = v)
      case ("controllerId", v: Int) => updated = updated.copy(controllerId = v)
      case ("controllerErrorMsg", v: String) => updated = updated.copy(controllerErrorMsg = v)
      case ("version", v: Int) => updated = updated.copy(version = v)
      case ("activeAxes", v: Array[Boolean]) => updated = updated.copy(activeAxes = v)
      case ("digitalInputs", v: Array[Boolean]) => updated = updated.copy(digitalInputs = v)
      case ("digitalOutputs", v: Array[Boolean]) => updated = updated.copy(digitalOutputs = v)
      case ("analogInputs", v: Array[Float]) => updated = updated.copy(analogInputs = v)
      case ("lastPollingTime", v: Instant) => updated = updated.copy(lastPollingTime = v)
      case ("debug", v: Boolean) => updated = updated.copy(debug = v)
      case ("simulation", v: Boolean) => updated = updated.copy(simulation = v)
      case (key, value) => 
        println(s"Warning: Unknown HCD state field: $key = $value")
    }
    
    updated
  
  /**
   * Update a specific axis state.
   */
  def updateAxis(axis: Axis, updates: Map[String, Any]): HcdState =
    val currentAxisState = axes.getOrElse(axis, AxisState())
    val updatedAxisState = currentAxisState.update(updates)
    copy(axes = axes + (axis -> updatedAxisState))
  
  /**
   * Get state for a specific axis.
   */
  def getAxis(axis: Axis): Option[AxisState] = axes.get(axis)
  
  /**
   * Initialize an axis with default state.
   */
  def initializeAxis(axis: Axis, mechanismType: MechanismType = MechanismType.Linear): HcdState =
    copy(
      activeAxes = activeAxes.updated(axis.index, true),
      axes = axes + (axis -> AxisState(mechanismType = mechanismType))
    )