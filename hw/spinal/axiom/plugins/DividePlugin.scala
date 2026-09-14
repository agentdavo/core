package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline._

/** Divide and remainder, taking as many cycles as it needs.
  *
  * This is the first unit in the core that cannot say when it will be
  * finished, and it is the reason the stall protocol came first. It holds the
  * execute stage until the answer exists rather than declaring a result some
  * stages later, because a division is long enough that no fixed number of
  * stages would cover it and rare enough that stalling costs little.
  *
  * Holding execute stops everything behind it, which for a sixty-five cycle
  * operation is the honest cost of not building an out-of-order machine. What
  * it buys is that the result is ordinary: available in execute like an ALU
  * result, forwarded the same way, with nothing else in the core needing to
  * know that division is slow.
  */
class DividePlugin extends AxiomPlugin {

  val SEL    = Payload(Bool())
  val RESULT = Payload(Bits(AxiomParam.XLEN bits))

  val setupLogic = during setup new Area {
    // The opcode is claimed either way, so that a build without a divider
    // rejects it here rather than leaving it to fall through as undefined.
    // The result is only offered when there is something to produce it.
    host[DecoderService].claim(SEL, Seq(Isa.ALU_DIV), subFunctionLegal)
    if (AxiomParam.WITH_DIVIDER.get) host[RegFileService].addResult(SEL, RESULT)
  }

  /** A build without a divider rejects the whole opcode, so the forms raise
    * the same illegal instruction trap as an undefined encoding rather than
    * quietly computing something else.
    */
  private def subFunctionLegal(instr: Bits): Bool = {
    if (!AxiomParam.WITH_DIVIDER.get) False
    else {
      val fn = instr(8 downto 6).asUInt
      Isa.DivFn.ALL.toSeq.map(value => fn === value).reduce(_ || _)
    }
  }

  // The parameter is read inside the build, not in the constructor: plugins
  // are built into a list before the database that holds the parameters is
  // in scope, so a constructor that reads one fails before elaboration starts.
  val logic = during build new Area {
    val present = AxiomParam.WITH_DIVIDER.get

    val divider = present generate new Area {
    val node = ctrl(Stages.EXECUTE)
    val instr = node(Global.INSTRUCTION)
    val fn = instr(8 downto 6).asUInt

    val unit = DivideUnit(AxiomParam.XLEN.get)
    unit.io.unsigned := fn(0)
    unit.io.word := fn(2)
    unit.io.dividend := node(Global.RS_N)
    unit.io.divisor := node(Global.RS_M)

    val active = node.isValid && node(SEL)

    /** Started, so the request is not repeated.
      *
      * The same shape as the load/store unit's `issued`: the unit is asked
      * once, and the stage holds until it answers. Without it the request
      * would still be raised on the cycle the division finished, and the unit
      * would start it again.
      */
    val started = Reg(Bool()) init False
    unit.io.start := active && !started
    when(unit.io.start) { started := True }
    when(node.down.isMoving) { started := False }

    // Held until the answer exists: not started yet, or started and still
    // working. The divider reports busy from the edge after it is asked, which
    // is why both halves are needed rather than just the second.
    node.haltWhen(active && (!started || unit.io.busy))

    node(RESULT) := Mux(fn(1), unit.io.remainder, unit.io.quotient)
    }
  }
}
