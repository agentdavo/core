package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline._

/** Branches, jumps and the link they write.
  *
  * A conditional branch resolves in execute, against forwarded operands, so it
  * may immediately follow the compare that produced its predicate. The front
  * end always predicts not taken, so a taken conditional branch costs the
  * three instructions behind it.
  *
  * An unconditional relative branch does not wait for that. Its target is the
  * program counter plus a displacement, and both are known in decode, so it
  * redirects from there and costs one instruction instead of three. That is
  * worth doing because the pipeline has a decode stage that was otherwise idle
  * and because calls and returns dominate the taken branches in real code:
  * this recovers most of what the separate register-read stage cost.
  *
  * The two redirects are exclusive by opcode, so nothing has to reconcile
  * them. The program counter plugin is told which stage each one comes from
  * and discards only the instructions younger than it.
  */
class BranchPlugin extends AxiomPlugin {

  val SEL    = Payload(Bool())
  val RESULT = Payload(Bits(AxiomParam.XLEN bits))

  private var redirect: Flow[UInt] = null
  private var earlyRedirect: Flow[UInt] = null

  val setupLogic = during setup new Area {
    host[DecoderService].claim(SEL, Seq(Isa.B, Isa.BL, Isa.BP, Isa.JALR))
    // BL and JALR write a return address; B and BP write nothing, and the
    // decoder already knows which is which.
    host[RegFileService].addResult(SEL, RESULT)
    earlyRedirect = host[PcService].newRedirect(Stages.DECODE)
    redirect = host[PcService].newRedirect(Stages.EXECUTE)
  }

  val logic = during build new Area {
    // ---- the unconditional relative branch, folded in decode -------------
    val early = new Area {
      val node = ctrl(Stages.DECODE)
      val opcode = node(Global.INSTRUCTION)(Isa.OP_HI downto Isa.OP_LO)
      val isRelative = opcode === B(Isa.B, 6 bits) || opcode === B(Isa.BL, 6 bits)

      // isFiring, not isValid: a branch held in decode must redirect on the
      // cycle it leaves, not on every cycle it waits, or it would bump the
      // fetch generation repeatedly and discard its own target fetch.
      earlyRedirect.valid := node.down.isFiring && node(SEL) && isRelative
      earlyRedirect.payload := node(Global.PC) + node(Global.BRANCH_OFF)
    }

    val node = ctrl(Stages.EXECUTE)
    val instr = node(Global.INSTRUCTION)
    val opcode = instr(Isa.OP_HI downto Isa.OP_LO)

    def opIs(value: Int): Bool = opcode === B(value, 6 bits)

    val isIndirect = opIs(Isa.JALR)
    val isConditional = opIs(Isa.BP)

    val predicate = host[PredicateService].read(instr(25 downto 23).asUInt)
    val sense = instr(22)

    // B and BL are absent here: decode has already redirected for them, and
    // repeating it would throw the correct successors it just fetched.
    val taken = (isIndirect || isConditional) && (!isConditional || (predicate ^ sense))

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
