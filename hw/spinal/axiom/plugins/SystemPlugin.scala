package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib.misc.pipeline._

/** HALT and the fences.
  *
  * The fences decode, check their kind and then do nothing, which is the
  * correct implementation for this core rather than a shortcut: a single hart
  * against a fixed-latency tightly coupled memory has no store buffer, no
  * speculation past a load and no other observer, so there is no order for a
  * fence to impose. The instruction still has to exist and still has to trap
  * on a reserved kind, because software compiled for the architecture will
  * emit it and a future implementation will need it.
  */
class SystemPlugin extends AxiomPlugin {

  val SEL = Payload(Bool())

  private def subFunctionLegal(instr: Bits): Bool = {
    val isFence = instr(Isa.OP_HI downto Isa.OP_LO) === B(Isa.FENCE, 6 bits)
    val fenceOk = Isa.FenceKind.ALL.map(v => instr(3 downto 0).asUInt === v).reduce(_ || _)
    val systemOk = Isa.SystemFn.ALL.map(v => instr(4 downto 0).asUInt === v).reduce(_ || _)
    Mux(isFence, fenceOk, systemOk)
  }

  private var trap: TrapCmd = null

  val setupLogic = during setup new Area {
    host[DecoderService].claim(SEL, Seq(Isa.SYSTEM, Isa.FENCE), subFunctionLegal)
    trap = host[TrapService].newTrapPort()
  }

  val logic = during build new Area {
    val node = ctrl(Stages.EXECUTE)
    val instr = node(Global.INSTRUCTION)
    val isSystem = instr(Isa.OP_HI downto Isa.OP_LO) === B(Isa.SYSTEM, 6 bits)
    val isHalt = instr(4 downto 0).asUInt === Isa.SystemFn.HALT

    // A cause of NONE is what distinguishes a clean stop from a trap.
    trap.raise(node.isValid && node(SEL) && isSystem && isHalt, Isa.Cause.NONE)
  }
}
