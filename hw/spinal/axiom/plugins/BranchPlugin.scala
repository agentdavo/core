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
  * The guess comes from thirty-two two-bit counters indexed by the program
  * counter, read in decode and moved towards what happened in execute. The
  * target of a relative branch is known in decode already, so a branch that is
  * predicted taken redirects from there and costs one instruction rather than
  * three. The counters said this shadow was the largest single cost in the
  * core: around thirty per cent of a loop of independent adds.
  *
  * The first version of the predictor was the sign of the displacement alone,
  * backwards taken and forwards not, which is right about loops and wrong
  * every time about the other common shape: a forward branch that is nearly
  * always taken, which is what a filter or a bounds check looks like. The
  * table costs sixty-four registers and gets both.
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
class BranchPlugin extends AxiomPlugin with PredictorService {

  /** The front end's view of the predictor: does the instruction at this
    * address jump, and where to. A Handle because the program counter asks in
    * its own build, which may run before this one.
    */
  private val frontEnd = spinal.core.fiber.Handle[UInt => UInt]()

  override def nextPc(pc: UInt): UInt = frontEnd.get.apply(pc)

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

    /** The branch history table.
      *
      * Flip-flops and a multiplexer rather than a memory: thirty-two entries
      * of two bits is sixty-four registers, where a distributed RAM would need
      * an initial value the ECP5's LUT RAM does not promise to keep and a read
      * port in the shallowest stage of the pipeline.
      */
    val history = new Area {
      val Entries = 32
      val IndexBits = log2Up(Entries)

      val counters = Vec.fill(Entries)(Reg(UInt(2 bits)) init 1)
      def indexOf(pc: UInt): UInt = pc(IndexBits + 1 downto 2)
      def predict(pc: UInt): Bool = counters.read(indexOf(pc)).msb

      /** Where the branch at this entry went last time it was folded.
        *
        * Untagged, and it does not need a tag: the front end acts on an entry
        * before it has read the instruction, and decode checks the result
        * against the instruction it then has. An entry left by a different
        * address costs a redirect and nothing else.
        *
        * Distributed RAM rather than registers, unlike the counters: thirty-two
        * addresses is two thousand bits, where thirty-two counters is sixty-four.
        */
      val targets = Mem(UInt(AxiomParam.PC_WIDTH bits), Entries)
      val known = Vec.fill(Entries)(Reg(Bool()) init False)

      def jumps(pc: UInt): Bool = known.read(indexOf(pc)) && predict(pc)
      def target(pc: UInt): UInt = targets.readAsync(indexOf(pc))

      /** Remember a branch decode folded, so the front end can fold it before
        * decode next time.
        *
        * An unconditional branch has its counter driven to the top rather than
        * nudged: it has no direction to learn and execute never resolves one
        * for it, so left to the counters it would sit at the cold value and
        * never be folded early at all. That was worth a cycle per iteration of
        * every loop that closes with a plain branch.
        */
      def remember(pc: UInt, to: UInt, always: Bool): Unit = {
        targets.write(indexOf(pc), to)
        known.onSel(indexOf(pc)) { entry => entry := True }
        when(always) { counters.onSel(indexOf(pc)) { counter => counter := 3 } }
      }

      /** Move one step towards what actually happened, saturating. */
      def record(pc: UInt, taken: Bool): Unit = {
        val index = indexOf(pc)
        for (entry <- 0 until Entries) when(index === entry) {
          val counter = counters(entry)
          when(taken) { when(counter =/= 3) { counter := counter + 1 } }
            .otherwise { when(counter =/= 0) { counter := counter - 1 } }
        }
      }
    }

    /** What the front end fetches after `pc`, before anything has read the
      * instruction there. A branch it has folded before jumps; everything else
      * carries on to the next instruction.
      */
    frontEnd.load { (pc: UInt) =>
      Mux(history.jumps(pc), history.target(pc), pc + 4)
    }

    // ---- the unconditional relative branch, folded in decode -------------
    val early = new Area {
      val node = ctrl(Stages.DECODE)
      val opcode = node(Global.INSTRUCTION)(Isa.OP_HI downto Isa.OP_LO)
      val isRelative = opcode === B(Isa.B, 6 bits) || opcode === B(Isa.BL, 6 bits)
      val isConditional = opcode === B(Isa.BP, 6 bits)

      /** What this branch did last time, if it has been here before.
        *
        * The sign of the displacement is a good first guess — backwards is a
        * loop, forwards is a check — and it is wrong every time for the other
        * common shape: a forward branch that is nearly always taken, which is
        * what a filter, a bounds check or an error path looks like. A workload
        * built around one spent a quarter of its cycles in the branch shadow.
        *
        * Two bits per entry, so a single exception does not flip the
        * prediction, indexed by the program counter with no tag: two branches
        * that share an entry confuse each other, which costs cycles and never
        * correctness. Cold entries read weakly not taken, so a loop is
        * mispredicted once on the way in rather than once per iteration.
        */
      val predictTaken = isConditional && history.predict(node(Global.PC))

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

      /** Where this instruction really goes, as far as decode can tell.
        *
        * An indirect jump is the exception: its target is a register and does
        * not exist yet, so decode calls it not taken and execute corrects it,
        * which is what it did before any of this.
        */
      val folding = node(SEL) && (isRelative || predictTaken)
      val target = node(Global.PC) + node(Global.BRANCH_OFF)
      val wanted = Mux(folding, target, node(Global.PC) + 4)

      // Redirect when the front end went somewhere else, rather than whenever
      // this is a taken branch. The two are the same thing for a branch the
      // front end has not seen before, and not the same for one it has: that
      // one was already folded a stage earlier and costs nothing here. It is
      // also what catches a prediction made from an entry belonging to some
      // other address, without the table needing a tag to prevent it.
      //
      // Validity is not enough on its own. Decode's instruction payload comes
      // straight off the fetch unit's buffer, so a stalled decode with an
      // empty buffer is holding a valid transaction and the previous
      // instruction's bits. Acting on those was worth twenty times the cycles
      // behind a cache: every branch decoded from a stale word threw away the
      // fetch in progress, and the front end never got a line in.
      val mispredicted = node(Global.PREDICTED_NEXT) =/= wanted
      val redirecting = node.up.isValid && host[FetchService].instructionPresent &&
        !fired && mispredicted && host[PcService].generationOk(node.up)
      fired := (fired || redirecting) && node.up.isValid && !node.up.isMoving

      earlyRedirect.valid := redirecting
      earlyRedirect.payload := wanted

      // Teach the front end the branches decode has folded. Only the ones it
      // can fold: an indirect jump has no target to remember, and a branch
      // predicted not taken is one the front end is already right about.
      when(node.up.isValid && host[FetchService].instructionPresent && folding &&
        host[PcService].generationOk(node.up)) {
        history.remember(node(Global.PC), target, isRelative)
      }
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

    /** Recording is once per branch, on the same terms as redirecting: the
      * instruction is in execute and has resolved, whether or not the stage is
      * allowed to move on. Waiting for it to move would put the whole
      * arbitration network in front of the table's write enable, which is the
      * path this stopped putting in front of the program counter.
      */
    val recorded = Reg(Bool()) init False
    val recording = node.up.isValid && !recorded && node(SEL) && isConditional
    recorded := (recorded || recording) && node.up.isValid && !node.up.isMoving
    when(recording) { history.record(node(Global.PC), taken) }
    redirect.payload := Mux(taken, Mux(isIndirect, indirectTarget, relativeTarget),
      node(Global.PC) + 4)

    node(RESULT) := (node(Global.PC) + 4).asBits

  }
}
