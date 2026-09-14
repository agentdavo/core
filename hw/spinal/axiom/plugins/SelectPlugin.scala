package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib.misc.pipeline._

/** Conditional select, the reason predicate registers exist.
  *
  * This is the entire concession to predication. Making the select explicit,
  * with both candidate values named in the encoding, keeps the dependency on
  * the old value visible instead of hiding it in the rename logic, which is
  * what general predication would have done.
  */
class SelectPlugin extends AxiomPlugin {

  val SEL    = Payload(Bool())
  val RESULT = Payload(Bits(AxiomParam.XLEN bits))

  val setupLogic = during setup new Area {
    host[DecoderService].claim(SEL, Seq(Isa.SEL))
    host[RegFileService].addResult(SEL, RESULT)
  }

  val logic = during build new Area {
    val node = ctrl(Stages.EXECUTE)
    val instr = node(Global.INSTRUCTION)

    val predicate = host[PredicateService].read(instr(10 downto 8).asUInt)
    val sense = instr(7)

    node(RESULT) := Mux(predicate ^ sense, node(Global.RS_N), node(Global.RS_M))
  }
}
