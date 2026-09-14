package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib._

/** Drives the instruction bus and hands the word to decode.
  *
  * No shadow register is needed to hold an instruction across a stall. The bus
  * contract says the memory holds its output while `enable` is low, so gating
  * `enable` with the fetch stage firing freezes the instruction in decode as a
  * side effect of the stall it already caused.
  */
class FetchPlugin extends AxiomPlugin {

  private var bus: IBus = null

  val setupLogic = during setup new Area {
    bus = host[MemoryService].newInstructionPort()
  }

  val logic = during build new Area {
    val fetchNode = ctrl(Stages.FETCH)
    bus.address := fetchNode.down(Global.PC)
    bus.enable  := fetchNode.down.isFiring

    // The word for the address issued last cycle is on the bus now, which is
    // exactly when the program counter it belongs to arrives at decode.
    ctrl(Stages.DECODE).up(Global.INSTRUCTION) := bus.data
  }
}
