package csw.proto.galil.deploy

import csw.framework.deploy.hostconfig.HostConfig
import csw.prefix.models.Subsystem.CSW

object GalilHostConfigApp {
  def main(args: Array[String]): Unit = {
    HostConfig.start("galil_host_config_app", CSW, args)
  }
}
