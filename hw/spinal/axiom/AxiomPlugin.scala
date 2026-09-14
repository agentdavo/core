package axiom

import spinal.lib.misc.plugin.FiberPlugin
import spinal.lib.misc.pipeline._

/** Base class for every Axiom-64 plugin.
  *
  * Adds nothing to [[FiberPlugin]] except shortcuts to the pipeline, so that a
  * plugin body reads as `ctrl(EXECUTE)` rather than as a chain of lookups.
  *
  * The elaboration phases carry the whole ordering contract between plugins,
  * and there are only three rules:
  *
  *  - `during setup` registers services and claims opcodes. No hardware.
  *  - `during build` creates hardware and reads what setup registered.
  *  - `during patch` is reserved for the pipeline plugin, which connects the
  *    stages once every other plugin has finished using them.
  *
  * Because the Fiber runs the phases in that order, a plugin never needs to
  * know which other plugins exist or when they run.
  */
class AxiomPlugin extends FiberPlugin {

  /** The control link at a given stage. */
  def ctrl(stage: Int): CtrlLink = host[PipelinePlugin].ctrl(stage)

  def pipeline: PipelinePlugin = host[PipelinePlugin]
}
