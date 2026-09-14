package axiom

import axiom.plugins._
import spinal.core._
import spinal.lib._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{Hostable, PluginHost}

/** The plugin sets that make up a conforming implementation.
  *
  * A profile is a list, not a class hierarchy. Adding an instruction to the
  * architecture means adding a plugin to this list; nothing else in the core
  * changes, and nothing else needs to know.
  */
object AxiomProfile {

  /** The Base profile on a tightly coupled memory. */
  def base: Seq[Hostable] = Seq(
    new PipelinePlugin,
    new PcPlugin,
    new FetchPlugin,
    new DecoderPlugin,
    new RegFilePlugin,
    new PredicateFilePlugin,
    new AluPlugin,
    new CmpPlugin,
    new SelectPlugin,
    new BranchPlugin,
    new LsuPlugin,
    new SystemPlugin,
    new TrapPlugin,
    new TcmPlugin
  )
}

/** The external interface of an Axiom-64 system.
  *
  * Declared as its own bundle so that a plugin can reach it through
  * [[InterfaceService]] without holding a reference to the component.
  */
case class AxiomIo(memWordAddressBits: Int) extends Bundle {
  /** Execution has stopped, on a HALT or on a trap. */
  val halted  = out Bool ()
  val trapped = out Bool ()
  val cause   = out UInt (Isa.Cause.WIDTH bits)
  val trapPc  = out UInt (Isa.XLEN bits)

  val cycleCount  = out UInt (32 bits)
  val retireCount = out UInt (32 bits)

  /** One pulse per instruction retired, with its address. Used by simulation
    * to compare the executed instruction stream against the reference model.
    */
  val dbgRetireValid = out Bool ()
  val dbgRetirePc    = out UInt (Isa.XLEN bits)

  val dbgRegAddr    = in UInt (Isa.REG_ADDR_BITS bits)
  val dbgRegData    = out Bits (Isa.XLEN bits)
  val dbgPredicates = out Bits (Isa.PRED_COUNT bits)

  /** Debug memory port. While enabled it owns the data side of the memory. */
  val dbgMemEnable = in Bool ()
  val dbgMemWrite  = in Bool ()
  val dbgMemAddr   = in UInt (memWordAddressBits bits)
  val dbgMemWData  = in Bits (Isa.XLEN bits)
  val dbgMemRData  = out Bits (Isa.XLEN bits)
}

/** Handed to the plugin host so plugins can drive the interface.
  *
  * A plain holder rather than the component itself: PluginHost reparents any
  * service that is a hardware context user, and reparenting a component to its
  * own scope makes it its own ancestor.
  */
class InterfaceService(val io: AxiomIo)

/** An Axiom-64 Base profile core with its memory.
  *
  * Everything below the interface is a plugin. This component owns three
  * things and nothing else: the external interface, the parameter database,
  * and the plugin host. The pipeline, the register file, every execution unit
  * and the memory itself are all plugins hanging off that host.
  */
class AxiomSoc(
    val memWords: Int = 4096,
    val resetVector: BigInt = 0,
    val withMultiplier: Boolean = true,
    val withAtomics: Boolean = true,
    val forwardLateFromWriteback: Boolean = true,
    val plugins: Seq[Hostable] = AxiomProfile.base
) extends Component {

  require(isPow2(memWords), "memWords must be a power of two")

  val io = AxiomIo(log2Up(memWords))

  val database = new Database

  val host = database on {
    AxiomParam.base(resetVector, memWords, withMultiplier, withAtomics, forwardLateFromWriteback)
    val created = new PluginHost()
    // Registering a holder, not the component, lets a plugin reach the
    // interface without the interface having to know which plugins exist.
    created.addService(new InterfaceService(io))
    created.asHostOf(plugins)
    created
  }
}
