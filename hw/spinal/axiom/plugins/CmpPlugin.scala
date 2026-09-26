package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib.misc.pipeline._

/** Compare instructions, the only writers of the predicate registers.
  *
  * Ten condition codes rather than six. `LE` and `GT` cannot be had by
  * swapping operands once one of them is an immediate, so they earn their own
  * encodings instead of costing an extra instruction at every use.
  */
class CmpPlugin extends AxiomPlugin {

  val SEL   = Payload(Bool())
  val VALUE = Payload(Bool())

  /** The compare, decoded into what execute has to do with it.
    *
    * Execute decoded the operand select and the condition code out of the
    * instruction itself, in front of the comparison, which put the opcode
    * compare and a ten-way switch on a path that was already a sixty-four bit
    * carry chain. It is decoded once instead: which operand, whether to test
    * equality, ordering or both, whether ordering is unsigned, and whether the
    * answer is inverted. Every condition code is one of those shapes.
    */
  val USE_IMM = Payload(Bool())
  val TEST_EQ = Payload(Bool())
  val TEST_LT = Payload(Bool())
  val UNSIGNED = Payload(Bool())
  val INVERT = Payload(Bool())

  /** The condition code sits at different bit positions in the register and
    * immediate forms, because the immediate needs the low twelve bits.
    */
  private def conditionOf(instr: Bits): Bits = {
    val isImmediate = instr(Isa.OP_HI downto Isa.OP_LO) === B(Isa.CMP_I, 6 bits)
    Mux(isImmediate, instr(15 downto 12), instr(10 downto 7))
  }

  private def subFunctionLegal(instr: Bits): Bool = {
    val cc = conditionOf(instr).asUInt
    Isa.Cc.ALL.map(value => cc === value).reduce(_ || _)
  }

  val setupLogic = during setup new Area {
    host[DecoderService].claim(SEL, Seq(Isa.CMP_R, Isa.CMP_I), subFunctionLegal)
    host[PredicateService].addWriter(SEL, VALUE)
  }

  val logic = during build new Area {
    val decode = new Area {
      val node = ctrl(Stages.DECODE)
      val instr = node(Global.INSTRUCTION)
      val cc = conditionOf(instr).asUInt
      def ccIs(values: Int*): Bool = values.map(v => cc === v).reduce(_ || _)

      node(USE_IMM) := instr(Isa.OP_HI downto Isa.OP_LO) === B(Isa.CMP_I, 6 bits)
      node(TEST_EQ) := ccIs(Isa.Cc.EQ, Isa.Cc.NE, Isa.Cc.LE, Isa.Cc.GT, Isa.Cc.LEU, Isa.Cc.GTU)
      node(TEST_LT) := !ccIs(Isa.Cc.EQ, Isa.Cc.NE)
      node(UNSIGNED) := ccIs(Isa.Cc.LTU, Isa.Cc.GEU, Isa.Cc.LEU, Isa.Cc.GTU)
      node(INVERT) := ccIs(Isa.Cc.NE, Isa.Cc.GE, Isa.Cc.GEU, Isa.Cc.GT, Isa.Cc.GTU)
    }

    val node = ctrl(Stages.EXECUTE)

    val a = node(Global.RS_N)
    val b = Mux(node(USE_IMM), node(Global.IMM), node(Global.RS_M))

    val equal = a === b
    val less = Mux(node(UNSIGNED), a.asUInt < b.asUInt, a.asSInt < b.asSInt)

    node(VALUE) := node(INVERT) ^ ((node(TEST_EQ) && equal) || (node(TEST_LT) && less))
  }
}
