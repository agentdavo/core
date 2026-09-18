package axiom

import spinal.core._
import spinal.lib.misc.pipeline._
import spinal.lib.misc.plugin.FiberPlugin
import scala.collection.mutable.ArrayBuffer

/** Owns the pipeline itself and nothing else.
  *
  * Every stage is a [[CtrlLink]], which is what gives the rest of the core its
  * backpressure for free: a plugin that needs to stall calls `haltWhen` on its
  * stage and the arbitration propagates upstream without any plugin having to
  * coordinate with any other. That is the piece the earlier fixed-latency core
  * did not have, and it is what a stallable bus, a multi-cycle divider or a
  * cache miss would all need.
  *
  * The links are connected in the patch phase, which runs after every other
  * plugin's build phase, so plugins may create payloads and arbitration
  * requests on any stage in any order.
  */
// Extends FiberPlugin rather than AxiomPlugin: AxiomPlugin reaches the
// pipeline through this plugin, so inheriting from it would be circular.
class PipelinePlugin extends FiberPlugin {

  val pipe = new StageCtrlPipeline()

  def ctrl(stage: Int): CtrlLink = pipe.ctrl(stage)

  /** Touch every stage during setup so the pipeline has a known length even if
    * some stage ends up with no plugin using it.
    */
  val setupLogic = during setup new Area {
    for (stage <- 0 until Stages.COUNT) pipe.ctrl(stage)
  }

  /** Connect the stages, with a skid buffer at the elastic boundaries.
    *
    * A [[StageLink]] registers the data going down and passes the ready coming
    * back straight through, so a stall anywhere reaches every stage in front of
    * it in the same cycle. That chain, from the load/store unit's halt in the
    * memory stage back through four stages of arbitration and into the program
    * counter, is the measured critical path of this core: about ten of its
    * twenty-five nanoseconds, most of it the wires between plugins that sit
    * far apart on the die.
    *
    * An [[S2MLink]] registers the ready instead, holding one transaction in a
    * buffer of its own so the stage in front may still move when the stage
    * behind cannot. Putting one at a boundary cuts the chain there: the stages
    * in front of it see a registered ready and nothing of what is happening
    * below. It costs a second copy of every payload that crosses, which is why
    * the boundary is chosen where the payloads are narrow and the chain is
    * long, rather than everywhere.
    */
  val buildLogic = during patch new Area {
    val links = ArrayBuffer[Link]()
    for (stage <- 0 until Stages.COUNT - 1) {
      val up = pipe.ctrl(stage).down
      val down = pipe.ctrl(stage + 1).up
      if (Stages.ELASTIC.contains(stage)) {
        // The data still wants a register, so both links are needed: the stage
        // link to carry the payload over the clock edge, the skid buffer to
        // stop the ready coming back through it.
        val skid = new Node().setCompositeName(this, s"skid_${stage + 1}")
        links += StageLink(up, skid).setCompositeName(this, s"stage_${stage + 1}")
        links += S2MLink(skid, down).setCompositeName(this, s"elastic_${stage + 1}")
      } else {
        links += StageLink(up, down).setCompositeName(this, s"stage_${stage + 1}")
      }
    }
    Builder(links ++ pipe.ctrls.values)
  }
}
