package axiom

import spinal.core._
import spinal.lib.misc.pipeline._
import spinal.lib.misc.plugin.FiberPlugin

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

  val buildLogic = during patch new Area {
    pipe.build()
  }
}
