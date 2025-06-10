package csw.proto.galil.client

import csw.logging.client.scaladsl.{GenericLoggerFactory, LoggingSystemFactory}
import csw.params.commands.CommandResponse.Completed
import csw.params.core.generics.KeyType
import csw.prefix.models.Prefix
import csw.proto.galil.io.DataRecord.GalilAxisStatus
import csw.proto.galil.simulator.GalilSimulator
import csw.testkit.scaladsl.CSWService.LocationServer
import csw.testkit.scaladsl.ScalaTestFrameworkTestKit
import org.scalatest.funsuite.AnyFunSuiteLike
import csw.proto.galil.hcd.GalilHcdHandlers
import java.net.InetAddress
import scala.concurrent.duration.*
import scala.concurrent.Await

class GalilClientTest extends ScalaTestFrameworkTestKit(LocationServer) with AnyFunSuiteLike {
  import frameworkTestKit._

  GalilSimulator()
  Thread.sleep(500)
  frameworkTestKit.spawnHCD(Prefix("csw.galil.hcd.GalilHcd"), (ctx, cswCtx) => new GalilHcdHandlers(ctx, cswCtx))

  private val galilHcdClient  = GalilHcdClient(Prefix("csw.galil.client"), locationService)(actorSystem, ec)
  private val maybeObsId      = None
  private val host            = InetAddress.getLocalHost.getHostName

  LoggingSystemFactory.start("GalilClientTest", "0.1", host, actorSystem)
  private val log = GenericLoggerFactory.getLogger
  log.info("Starting GalilClientTest")

  test("Test ") {
    val resp1 = Await.result(galilHcdClient.setRelTarget(maybeObsId, 'A', 3), 3.seconds)
    println(s"setRelTarget: $resp1")
    assert(resp1.isInstanceOf[Completed])

    val resp2 = Await.result(galilHcdClient.getRelTarget(maybeObsId, 'A'), 3.seconds)
    println(s"getRelTarget: $resp2")
    assert(resp2.isInstanceOf[Completed])

    val resp3 = Await.result(galilHcdClient.getDataRecord(maybeObsId), 3.seconds)
    println(s"getDataRecord: $resp3")
    assert(resp3.isInstanceOf[Completed])

    val result = resp3.asInstanceOf[Completed].result

    // Example of how you could extract the motor position for each axis.
    // The axis status parameters for each axis are stored in params with the key name axis-$axis-$paramName
    // (For example, "axis-A-motorPosition").
    val blocksPresent = result.get(KeyType.CharKey.make("blocksPresent")).get.values
    blocksPresent.filter(b => b >= 'A' && b <= 'F').foreach { axis =>
      implicit val a: Char = axis
      val motorPos         = result.get(GalilAxisStatus.motorPositionKey)
      println(s"Axis $axis: motor position: $motorPos")
    }

  }
}
