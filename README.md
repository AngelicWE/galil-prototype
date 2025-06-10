# Prototype Galil Controller HCD Implementation

This project implements an HCD (Hardware Control Daemon) that talks to a Galil controller using 
the TMT Common Software ([CSW](https://github.com/tmtsoftware/csw)) APIs. 
An example device might be the DMC-50040(DIN, ISCNTL)-C023-I000-D4020.

## Subprojects

* galil-assembly - an assembly that talks to the Galil HCD
* galil-client - client applications that talk to the Galil assembly or HCD
* galil-hcd - an HCD that talks to the Galil hardware
* galil-io - library implementing the communication with the Galil hardware
* galil-repl - a command line client for a Galil device where you can enter Galil commands and see the responses
* galil-simulator - implements a simulator for a Galil device (Only a small subset of Galil commands are simulated)

## Build Instructions

The build is based on sbt and depends on libraries generated from the
[csw](https://github.com/tmtsoftware/csw) project.

See [here](https://www.scala-sbt.org/1.0/docs/Setup.html) for instructions on installing sbt.

Note: The version of CSW used by this project is declared in [project/build.properties](project/build.properties).

Run:

    sbt stage

to compile everything and create the start scripts for the components.
After this command, you can find the start scripts in ./target/universal/stage/bin.

## Prerequisites for running Components

The CSW services need to be running before starting the components.
(They are started automatically for the tests.)
See [here](https://tmtsoftware.github.io/csw/apps/csinstallation.html) for how to install csw-services using coursier (cs).

* Run `csw-services start -e` command to start the CSW services: i.e. Location, Event Service.

See [csw-services](https://tmtsoftware.github.io/csw/apps/cswservices.html) for more information.

## Running the galil-prototype applications

To run the Galil HCD using an actual Galil device, run the `galil-hcd` command with the options:
```
galil-hcd --local galil-hcd/src/main/resources/GalilHcd.conf -Dgalil.host=myhost -Dgalil.port=23
```

An example GalilHcd.conf file can be found [here](galil-hcd/src/main/resources/GalilHcd.conf). 
If `--local` is not given, the file would be fetched from the Config Service, if available.

To run using a Galil simulator, run these commands in separate terminal windows:
```
galil-simulator
galil-hcd --local galil-hcd/src/main/resources/GalilHcd.conf
```


