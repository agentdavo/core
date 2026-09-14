package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline._

/** Branches, jumps and the link they write.
  *
  * Resolution is in execute, against forwarded operands, so a branch may
  * immediately follow the compare that produced its predicate. The front end
  * always predicts not taken, so a taken branch costs the two instructions
  * behind it; the program counter plugin discards those by generation rather
  * than this plugin having to know how many there are.
  */
class BranchPlugin extends AxiomPlugin {

  val SEL    = Payload(Bool())
  val RESULT = Payload(Bits(AxiomParam.XLEN bits))

  private var redirect: Flow[UInt] = null

  val setupLogic = during setup new Area {
    host[DecoderService].claim(SEL, Seq(Isa.B, Isa.BL, Isa.BP, Isa.JALR))
    // BL and JALR write a return address; B and BP write nothing, and the
    // decoder already knows which is which.
    host[RegFileService].addResult(SEL, RESULT)
    redirect = host[PcService].newRedirect()
  }

  val logic = during build new Area {
    val node = ctrl(Stages.EXECUTE)
    val instr = node(Global.INSTRUCTION)
    val opcode = instr(Isa.OP_HI downto Isa.OP_LO)

    def opIs(value: Int): Bool = opcode === B(value, 6 bits)

    val isIndirect = opIs(Isa.JALR)
    val isConditional = opIs(Isa.BP)

    val predicate = host[PredicateService].read(instr(25 downto 23).asUInt)
    val sense = instr(22)

    val taken = !isConditional || (predicate ^ sense)

    val relativeTarget = node(Global.PC) + node(Global.BRANCH_OFF)
    // A computed target has its low two bits cleared rather than trapping, so
    // returning through a tagged pointer stays safe. Written as a slice rather
    // than a shift pair so the width is exact.
    val computed = node(Global.RS_N).asUInt + node(Global.IMM).asUInt
    val indirectTarget = computed(AxiomParam.PC_WIDTH.get - 1 downto 2) @@ U"00"

    // Gated on the branch actually leaving execute, not merely sitting there
    // valid. A branch held by downstream backpressure would otherwise redirect
    // on every cycle it waits, bumping the fetch generation repeatedly and
    // discarding the target fetch more than once.
    redirect.valid := node.down.isFiring && node(SEL) && taken
    redirect.payload := Mux(isIndirect, indirectTarget, relativeTarget)

    node(RESULT) := (node(Global.PC) + 4).asBits
  }
}
