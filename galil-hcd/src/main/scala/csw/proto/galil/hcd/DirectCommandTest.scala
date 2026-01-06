package csw.proto.galil.hcd

import com.typesafe.config.ConfigFactory
import csw.params.commands.{CommandName, Setup}
import csw.params.core.generics.KeyType
import csw.prefix.models.Prefix

/**
 * Simple test to verify command translation without needing CSW services
 */
object DirectCommandTest {
  
  def main(args: Array[String]): Unit = {
    println("=== Testing HCD Command Translation ===\n")
    
    // Load configuration
    val config = ConfigFactory.load("GalilCommands.conf")
    val adapter = new CSWDeviceAdapter(config)
    
    // Define keys for new interface
    val labelKey = KeyType.StringKey.make("label")
    val threadKey = KeyType.IntKey.make("thread")
    val addressKey = KeyType.IntKey.make("address")
    val channelKey = KeyType.IntKey.make("channel")
    val valueKey = KeyType.DoubleKey.make("value")
    val commandStringKey = KeyType.StringKey.make("commandString")
    
    val prefix = Prefix("CSW.test.client")
    
    // Test 1: executeProgram
    println("Test 1: executeProgram")
    val execProgramSetup = Setup(prefix, CommandName("executeProgram"))
      .add(labelKey.set("MoveA"))
      .add(threadKey.set(1))
    
    val execResult = for {
      cmdMapEntry <- adapter.getCommandMapEntry(execProgramSetup)
      cmdString <- adapter.validateSetup(execProgramSetup, cmdMapEntry)
    } yield (cmdMapEntry, cmdString)
    
    execResult match {
      case scala.util.Success((entry, cmdString)) =>
        println(s"  Command: ${entry.name}")
        println(s"  Template: ${entry.command}")
        println(s"  Generated: $cmdString")
        println(s"  Expected: XQ #MoveA,1")
        println(s"  ✓ ${if (cmdString == "XQ #MoveA,1") "PASS" else "FAIL"}\n")
      case scala.util.Failure(ex) =>
        println(s"  ✗ FAILED: ${ex.getMessage}\n")
    }
    
    // Test 2: haltExecution
    println("Test 2: haltExecution")
    val haltSetup = Setup(prefix, CommandName("haltExecution"))
      .add(threadKey.set(1))
    
    val haltResult = for {
      cmdMapEntry <- adapter.getCommandMapEntry(haltSetup)
      cmdString <- adapter.validateSetup(haltSetup, cmdMapEntry)
    } yield (cmdMapEntry, cmdString)
    
    haltResult match {
      case scala.util.Success((entry, cmdString)) =>
        println(s"  Command: ${entry.name}")
        println(s"  Template: ${entry.command}")
        println(s"  Generated: $cmdString")
        println(s"  Expected: HX 1")
        println(s"  ✓ ${if (cmdString == "HX 1") "PASS" else "FAIL"}\n")
      case scala.util.Failure(ex) =>
        println(s"  ✗ FAILED: ${ex.getMessage}\n")
    }
    
    // Test 3: setBit
    println("Test 3: setBit")
    val setBitSetup = Setup(prefix, CommandName("setBit"))
      .add(addressKey.set(5))
    
    val setBitResult = for {
      cmdMapEntry <- adapter.getCommandMapEntry(setBitSetup)
      cmdString <- adapter.validateSetup(setBitSetup, cmdMapEntry)
    } yield (cmdMapEntry, cmdString)
    
    setBitResult match {
      case scala.util.Success((entry, cmdString)) =>
        println(s"  Command: ${entry.name}")
        println(s"  Template: ${entry.command}")
        println(s"  Generated: $cmdString")
        println(s"  Expected: SB 5")
        println(s"  ✓ ${if (cmdString == "SB 5") "PASS" else "FAIL"}\n")
      case scala.util.Failure(ex) =>
        println(s"  ✗ FAILED: ${ex.getMessage}\n")
    }
    
    // Test 4: clearBit
    println("Test 4: clearBit")
    val clearBitSetup = Setup(prefix, CommandName("clearBit"))
      .add(addressKey.set(5))
    
    val clearBitResult = for {
      cmdMapEntry <- adapter.getCommandMapEntry(clearBitSetup)
      cmdString <- adapter.validateSetup(clearBitSetup, cmdMapEntry)
    } yield (cmdMapEntry, cmdString)
    
    clearBitResult match {
      case scala.util.Success((entry, cmdString)) =>
        println(s"  Command: ${entry.name}")
        println(s"  Template: ${entry.command}")
        println(s"  Generated: $cmdString")
        println(s"  Expected: CB 5")
        println(s"  ✓ ${if (cmdString == "CB 5") "PASS" else "FAIL"}\n")
      case scala.util.Failure(ex) =>
        println(s"  ✗ FAILED: ${ex.getMessage}\n")
    }
    
    // Test 5: readAnalogInput
    println("Test 5: readAnalogInput")
    val readAISetup = Setup(prefix, CommandName("readAnalogInput"))
      .add(channelKey.set(0))
    
    val readAIResult = for {
      cmdMapEntry <- adapter.getCommandMapEntry(readAISetup)
      cmdString <- adapter.validateSetup(readAISetup, cmdMapEntry)
    } yield (cmdMapEntry, cmdString)
    
    readAIResult match {
      case scala.util.Success((entry, cmdString)) =>
        println(s"  Command: ${entry.name}")
        println(s"  Template: ${entry.command}")
        println(s"  Generated: $cmdString")
        println(s"  Expected: MG @AN[0]")
        println(s"  ✓ ${if (cmdString == "MG @AN[0]") "PASS" else "FAIL"}\n")
      case scala.util.Failure(ex) =>
        println(s"  ✗ FAILED: ${ex.getMessage}\n")
    }
    
    // Test 6: writeAnalogOutput
    println("Test 6: writeAnalogOutput")
    val writeAOSetup = Setup(prefix, CommandName("writeAnalogOutput"))
      .add(channelKey.set(1))
      .add(valueKey.set(2.5))
    
    val writeAOResult = for {
      cmdMapEntry <- adapter.getCommandMapEntry(writeAOSetup)
      cmdString <- adapter.validateSetup(writeAOSetup, cmdMapEntry)
    } yield (cmdMapEntry, cmdString)
    
    writeAOResult match {
      case scala.util.Success((entry, cmdString)) =>
        println(s"  Command: ${entry.name}")
        println(s"  Template: ${entry.command}")
        println(s"  Generated: $cmdString")
        println(s"  Expected: AO 1,2.5")
        println(s"  ✓ ${if (cmdString == "AO 1,2.5") "PASS" else "FAIL"}\n")
      case scala.util.Failure(ex) =>
        println(s"  ✗ FAILED: ${ex.getMessage}\n")
    }
    
    // Test 7: sendCommand
    println("Test 7: sendCommand (arbitrary DMC)")
    val sendCmdSetup = Setup(prefix, CommandName("sendCommand"))
      .add(commandStringKey.set("posn[0]=100"))
    
    val sendCmdResult = for {
      cmdMapEntry <- adapter.getCommandMapEntry(sendCmdSetup)
      cmdString <- adapter.validateSetup(sendCmdSetup, cmdMapEntry)
    } yield (cmdMapEntry, cmdString)
    
    sendCmdResult match {
      case scala.util.Success((entry, cmdString)) =>
        println(s"  Command: ${entry.name}")
        println(s"  Template: ${entry.command}")
        println(s"  Generated: $cmdString")
        println(s"  Expected: posn[0]=100")
        println(s"  ✓ ${if (cmdString == "posn[0]=100") "PASS" else "FAIL"}\n")
      case scala.util.Failure(ex) =>
        println(s"  ✗ FAILED: ${ex.getMessage}\n")
    }
    
    // Test 8: getDataRecord (still works)
    println("Test 8: getDataRecord")
    val dataRecordSetup = Setup(prefix, CommandName("getDataRecord"))
    
    val dataRecordResult = for {
      cmdMapEntry <- adapter.getCommandMapEntry(dataRecordSetup)
      cmdString <- adapter.validateSetup(dataRecordSetup, cmdMapEntry)
    } yield (cmdMapEntry, cmdString)
    
    dataRecordResult match {
      case scala.util.Success((entry, cmdString)) =>
        println(s"  Command: ${entry.name}")
        println(s"  Template: ${entry.command}")
        println(s"  Generated: $cmdString")
        println(s"  Expected: QR")
        println(s"  ✓ ${if (cmdString == "QR") "PASS" else "FAIL"}\n")
      case scala.util.Failure(ex) =>
        println(s"  ✗ FAILED: ${ex.getMessage}\n")
    }
    
    println("=== Test Complete ===")
  }
}
