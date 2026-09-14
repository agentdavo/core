package axiom.plugins

import axiom._
import spinal.core._

/** Brings the two memory ports out of the design instead of implementing them.
  *
  * The same service the tightly coupled memory implements, so fetch and the
  * load/store unit cannot tell the difference: they ask for a port either way.
  * Swapping this plugin for [[TcmPlugin]] is the whole of the change between a
  * self-contained system and a core meant to sit on somebody else's bus, and it
  * is what a cache plugin would slot into as well.
  */
class ExternalBusPlugin extends AxiomPlugin with MemoryService {

  private var instructionPort: IBus = null
  private var dataPort: DBus = null

  override def newInstructionPort(): IBus = {
    require(instructionPort == null, "there is one instruction port")
    instructionPort = IBus(AxiomParam.PC_WIDTH.get)
    instructionPort
  }

  override def newDataPort(): DBus = {
    require(dataPort == null, "there is one data port")
    dataPort = DBus(AxiomParam.PC_WIDTH.get, AxiomParam.XLEN.get)
    dataPort
  }

  val logic = during build new Area {
    val io = host[BusInterfaceService]
    io.ibus.enable := instructionPort.enable
    io.ibus.address := instructionPort.address
    instructionPort.data := io.ibus.data

    io.dbus.enable := dataPort.enable
    io.dbus.write := dataPort.write
    io.dbus.address := dataPort.address
    io.dbus.mask := dataPort.mask
    io.dbus.wdata := dataPort.wdata
    dataPort.rdata := io.dbus.rdata
  }
}
