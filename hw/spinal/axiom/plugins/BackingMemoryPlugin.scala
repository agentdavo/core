package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib._

/** Wiring for [[BackingRam]]: the memory a cache sits in front of.
  *
  * The plugin is the wiring and the component is the memory, for the same
  * reason the cache is split that way: a handshake is much easier to watch
  * from outside than to infer from a wrong register hundreds of cycles later.
  */
class BackingMemoryPlugin extends AxiomPlugin with BackingMemoryService {

  private val ports = scala.collection.mutable.ArrayBuffer[DBus]()

  override def newBackingPort(): DBus = {
    require(ports.length < 2, "the backing memory has two ports")
    val port = DBus(AxiomParam.PC_WIDTH.get, AxiomParam.XLEN.get)
    ports += port
    port
  }

  val logic = during build new Area {
    val soc = host[DebugMemoryService]
    val ram = BackingRam(
      words = AxiomParam.MEM_WORDS.get,
      latency = AxiomParam.MEMORY_LATENCY.get,
      ports = ports.length
    )

    for ((outer, inner) <- ports.zip(ram.io.port)) {
      inner.enable := outer.enable
      inner.write := outer.write
      inner.address := outer.address
      inner.mask := outer.mask
      inner.wdata := outer.wdata
      outer.ready := inner.ready
      outer.rvalid := inner.rvalid
      outer.rdata := inner.rdata
    }

    ram.io.debug.enable := soc.io.dbgMemEnable
    ram.io.debug.write := soc.io.dbgMemWrite
    ram.io.debug.address := soc.io.dbgMemAddr
    ram.io.debug.wdata := soc.io.dbgMemWData
    soc.io.dbgMemRData := ram.io.debug.rdata
  }
}
