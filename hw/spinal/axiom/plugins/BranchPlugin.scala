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

      // Once per branch, and not on the cycle it happens to leave.
      //
      // A redirect must happen exactly once for a given branch, or the fetch
      // generation moves twice and the second move discards the target fetch
      // the first one asked for. The obvious way to say "once" is to fire on
      // the cycle the branch leaves the stage, and that is what this did.
      //
      // Leaving is the wrong event to wait for. Whether a stage may move is
      // the whole arbitration network: every halt in every stage below,
      // including a load/store unit waiting on memory. Measured on an ECP5
      // that chain, from a stall in the memory stage back through the ready
      // network and into the program counter, was the critical path of the
      // core in every seed. And waiting on it is not only slow to elaborate:
      // it delays the target fetch until the stall clears, when the front end
      // could have been fetching it all along.
      //
      // A register saying this branch has already redirected says "once"
      // without asking anything about readiness. Validity and the generation
      // compare are all that is left, and both are close by.
      /** This transaction has redirected already.
        *
        * Held while the same transaction is still in the stage and dropped the
        * moment it leaves or the stage empties. Clearing it only on departure
        * is not enough: a stage left holding a bubble never departs, so the
        * flag would still be set when the next real instruction arrived and
        * that one's redirect would be swallowed.
        */
      val fired = Reg(Bool()) init False

      // Validity is not enough here. Decode's instruction payload comes
      // straight off the fetch unit's buffer, so a stalled decode with an
      // empty buffer is holding a valid transaction and the previous
      // instruction's bits. Acting on those was worth twenty times the cycles
      // behind a cache: every branch decoded from a stale word threw away the
      // fetch in progress, and the front end never got a line in.
      val redirecting = node.up.isValid && host[FetchService].instructionPresent &&
        !fired && node(SEL) && (isRelative || predictTaken) &&
        host[PcService].generationOk(node.up)
      fired := (fired || redirecting) && node.up.isValid && !node.up.isMoving

      earlyRedirect.valid := redirecting
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

    // Once per branch, as in decode, and for the same two reasons: the ready
    // network is long, and a branch that knows where it is going has no reason
    // to keep it to itself while the memory stage finishes something.
    //
    // The generation is not asked about here. A younger branch in decode can
    // move it while this one waits, and this one still has to be obeyed: it is
    // the older instruction, and its redirect is applied after and wins.
    val fired = Reg(Bool()) init False
    val redirecting = node.up.isValid && !fired && node(SEL) && wrong
    fired := (fired || redirecting) && node.up.isValid && !node.up.isMoving

    redirect.valid := redirecting
    redirect.payload := Mux(taken, Mux(isIndirect, indirectTarget, relativeTarget),
      node(Global.PC) + 4)

    node(RESULT) := (node(Global.PC) + 4).asBits

  }
}
