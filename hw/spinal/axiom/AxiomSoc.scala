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

  /** Everything except the memory. */
  private def core: Seq[Hostable] = Seq(
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
    new TrapPlugin
  )

  /** The Base profile with a tightly coupled memory inside. */
  def base: Seq[Hostable] = core :+ new TcmPlugin

  /** The Base profile with its buses brought out, for dropping into a larger
    * system or for synthesising the core on its own.
    */
  def bare: Seq[Hostable] = core :+ new ExternalBusPlugin

  /** The Base profile with first level caches in front of a memory slow enough
    * to be worth caching. The core cannot tell: it asks for a port and gets
    * one, which is the whole point of the memory being a service.
    */
  def cached: Seq[Hostable] = core ++ Seq(new CachePlugin, new BackingMemoryPlugin)
}

/** A bare Axiom-64 core: everything but the memory, with the two buses
  * exposed. Same plugins, one swapped.
  */
class AxiomCore(
    val resetVector: BigInt = 0,
    val withMultiplier: Boolean = true,
    val withAtomics: Boolean = true,
    val forwardLateFromWriteback: Boolean = true,
    val forwardBase: Boolean = true,
    val withDebugRegFilePort: Boolean = true,
    val memoryStall: Int = 0,
    val memoryLatency: Int = 1,
    val icacheBytes: Int = 4096,
    val dcacheBytes: Int = 4096,
    val cacheLineBytes: Int = 32,
    val plugins: Seq[Hostable] = AxiomProfile.bare
) extends Component {

  val io = AxiomCoreIo()

  val database = new Database

  val host = database on {
    // The memory size only matters to a memory plugin, and there is not one
    // here, but the key is blocking so it still has to be set.
    AxiomParam.base(resetVector, 4096, withMultiplier, withAtomics, forwardLateFromWriteback,
      forwardBase, withDebugRegFilePort, memoryStall, memoryLatency,
      icacheBytes, dcacheBytes, cacheLineBytes)
    val created = new PluginHost()
    created.addService(new InterfaceService(io))
    created.addService(new BusInterfaceService(io.ibus, io.dbus))
    created.asHostOf(plugins)
    created
  }
}

/** The status and debug signals every Axiom-64 top level exposes.
  *
  * A trait rather than a bundle, so the two top levels can lay their own
  * interfaces out as they like while plugins still reach these through one
  * service. The bare core adds bus ports; the system adds a debug memory port.
  */
trait AxiomStatus {
  val halted: Bool
  val trapped: Bool
  val cause: UInt
  val trapPc: UInt
  val cycleCount: UInt
  val retireCount: UInt
  val dbgRetireValid: Bool
  val dbgRetirePc: UInt
  val dbgRegAddr: UInt
  val dbgRegData: Bits
  val dbgPredicates: Bits
}

/** The debug memory port, present only when the memory is inside the design. */
trait AxiomDebugMemory {
  val dbgMemEnable: Bool
  val dbgMemWrite: Bool
  val dbgMemAddr: UInt
  val dbgMemWData: Bits
  val dbgMemRData: Bits
}

/** The external interface of an Axiom-64 system with its memory inside. */
case class AxiomIo(memWordAddressBits: Int) extends Bundle with AxiomStatus with AxiomDebugMemory {
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

/** The external interface of a bare Axiom-64 core, with its buses exposed.
  *
  * This is the shape you drop into a larger system, and it is also the one to
  * synthesise when the question is how big and how fast the core is: the
  * tightly coupled memory is thirty-two block RAMs and a great deal of lane
  * multiplexing, and measuring it alongside the core answers a different
  * question.
  */
case class AxiomCoreIo() extends Bundle with AxiomStatus {
  val halted  = out Bool ()
  val trapped = out Bool ()
  val cause   = out UInt (Isa.Cause.WIDTH bits)
  val trapPc  = out UInt (Isa.XLEN bits)

  val cycleCount  = out UInt (32 bits)
  val retireCount = out UInt (32 bits)

  val dbgRetireValid = out Bool ()
  val dbgRetirePc    = out UInt (Isa.XLEN bits)

  val dbgRegAddr    = in UInt (Isa.REG_ADDR_BITS bits)
  val dbgRegData    = out Bits (Isa.XLEN bits)
  val dbgPredicates = out Bits (Isa.PRED_COUNT bits)

  val ibus = master(IBus(Isa.XLEN))
  val dbus = master(DBus(Isa.XLEN, Isa.XLEN))
}

/** Handed to the plugin host so plugins can drive the interface.
  *
  * A plain holder rather than the component itself: PluginHost reparents any
  * service that is a hardware context user, and reparenting a component to its
  * own scope makes it its own ancestor.
  */
class InterfaceService(val io: AxiomStatus)

/** Present only when the memory lives inside the design. */
class DebugMemoryService(val io: AxiomDebugMemory)

/** Present only when the buses leave the design. */
class BusInterfaceService(val ibus: IBus, val dbus: DBus)

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
    val forwardBase: Boolean = true,
    val withDebugRegFilePort: Boolean = true,
    val memoryStall: Int = 0,
    val memoryLatency: Int = 1,
    val icacheBytes: Int = 4096,
    val dcacheBytes: Int = 4096,
    val cacheLineBytes: Int = 32,
    val plugins: Seq[Hostable] = AxiomProfile.base
) extends Component {

  require(isPow2(memWords), "memWords must be a power of two")

  val io = AxiomIo(log2Up(memWords))

  val database = new Database

  val host = database on {
    AxiomParam.base(resetVector, memWords, withMultiplier, withAtomics, forwardLateFromWriteback,
      forwardBase, withDebugRegFilePort, memoryStall, memoryLatency,
      icacheBytes, dcacheBytes, cacheLineBytes)
    val created = new PluginHost()
    // Registering a holder, not the component, lets a plugin reach the
    // interface without the interface having to know which plugins exist.
    created.addService(new InterfaceService(io))
    created.addService(new DebugMemoryService(io))
    created.asHostOf(plugins)
    created
  }
}
