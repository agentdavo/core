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
    val node = ctrl(Stages.EXECUTE)
    val instr = node(Global.INSTRUCTION)

    val isImmediate = instr(Isa.OP_HI downto Isa.OP_LO) === B(Isa.CMP_I, 6 bits)
    val a = node(Global.RS_N)
    val b = Mux(isImmediate, node(Global.IMM), node(Global.RS_M))

    val equal        = a === b
    val lessSigned   = a.asSInt < b.asSInt
    val lessUnsigned = a.asUInt < b.asUInt

    val value = Bool()
    value := False
    switch(conditionOf(instr).asUInt) {
      is(Isa.Cc.EQ)  { value := equal }
      is(Isa.Cc.NE)  { value := !equal }
      is(Isa.Cc.LT)  { value := lessSigned }
      is(Isa.Cc.GE)  { value := !lessSigned }
      is(Isa.Cc.LTU) { value := lessUnsigned }
      is(Isa.Cc.GEU) { value := !lessUnsigned }
      is(Isa.Cc.LE)  { value := lessSigned || equal }
      is(Isa.Cc.GT)  { value := !(lessSigned || equal) }
      is(Isa.Cc.LEU) { value := lessUnsigned || equal }
      is(Isa.Cc.GTU) { value := !(lessUnsigned || equal) }
    }
    node(VALUE) := value
  }
}
