package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline._

/** Branches, jumps and the link they write.
  *
  * A conditional branch resolves in execute, against forwarded operands, so it
  * may immediately follow the compare that produced its predicate. Resolving
  * it there costs the three instructions behind it whenever the guess made in
  * decode turns out wrong.
  *
  * The guess is the direction of the displacement: a conditional branch that
  * goes backwards is predicted taken, one that goes forwards is predicted not
  * taken. That is the whole predictor, and it is nearly free, because the
  * target of a relative branch is known in decode already. It is worth having
  * because a loop closes with a backward branch taken every iteration but the
  * last, and the counters said that branch shadow was the largest single cost
  * in the core: around thirty per cent of a loop of independent adds.
  *
  * A prediction is not a second answer. Execute still computes the real one
  * and redirects whenever the two disagree, which is what makes a wrong guess
  * cost cycles rather than correctness. What decode predicted travels with the
  * instruction, so execute compares against what actually happened to that
  * instruction rather than against whatever the front end is doing now.
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

  /** What decode guessed about this instruction, carried down with it. */
  val PREDICTED = Payload(Bool())

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
      val isConditional = opcode === B(Isa.BP, 6 bits)

      /** Backwards is a loop; forwards is an error check or an else branch.
        *
        * The sign of the displacement is the top bit of the sign-extended
        * offset the decoder already produced, so the prediction costs one wire
        * and no logic. A table indexed by the program counter would predict
        * better and would need a memory, a tag, and an update path from
        * execute; this is the version worth having first, and the counters say
        * how much of the shadow it removed.
        */
      val predictTaken = isConditional && node(Global.BRANCH_OFF).msb

      // The guess travels with the instruction. Execute compares against this
      // rather than against the front end's state, which has moved on.
      node(PREDICTED) := node(SEL) && predictTaken

      // The upstream side, plus the generation compare on its own, rather
      // than the downstream side.
      //
      // Firing matters: a branch held in decode must redirect on the cycle it
      // leaves, not on every cycle it waits, or it would bump the fetch
      // generation repeatedly and discard its own target fetch. Backpressure
      // is what decides that, and the upstream side carries it.
      //
      // What the downstream side adds is every reason the stage might be
      // cancelled, and that is what made this the critical path: it chained
      // the trap decode, the execute redirect and the whole arbitration
      // network into the fetch generation register. Of those reasons only a
      // stale generation changes the answer, so it is asked for directly. A
      // branch cancelled for any other reason may still redirect: an execute
      // redirect is the one that cancels it, it is applied after this one and
      // so wins the program counter, and the generation moves once either way.
      earlyRedirect.valid := node.up.isFiring && node(SEL) && (isRelative || predictTaken) &&
        host[PcService].generationOk(node.up)
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

    // A predicted-taken branch that is taken needs nothing from this stage:
    // decode already sent the front end to the right place. One that is not
    // taken has to be undone, and the way back is the instruction after it.
    val predicted = node(PREDICTED)
    val wrong = taken =/= predicted

    val relativeTarget = node(Global.PC) + node(Global.BRANCH_OFF)
    // A computed target has its low two bits cleared rather than trapping, so
    // returning through a tagged pointer stays safe. Written as a slice rather
    // than a shift pair so the width is exact.
    val computed = node(Global.RS_N).asUInt + node(Global.IMM).asUInt
    val indirectTarget = computed(AxiomParam.PC_WIDTH.get - 1 downto 2) @@ U"00"

    // The upstream side again, and for the same reason. The downstream side
    // here carried the trap decode: a trap thrown in this stage clears it, so
    // reading it put seven nanoseconds of cause decoding in front of the
    // redirect. No branch raises a trap, so that term could never change the
    // answer; it only had to be computed.
    //
    // Backpressure is still respected, because a branch held by a stalled
    // memory stage does not fire upstream either, and would otherwise bump the
    // fetch generation on every cycle it waits.
    redirect.valid := node.up.isFiring && node(SEL) && wrong
    redirect.payload := Mux(taken, Mux(isIndirect, indirectTarget, relativeTarget),
      node(Global.PC) + 4)

    node(RESULT) := (node(Global.PC) + 4).asBits
  }
}
