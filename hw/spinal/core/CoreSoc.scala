package core

import spinal.core._
import spinal.lib._

/** A minimal system built around the C1 core: the CPU plus a single unified
  * tightly coupled memory holding both code and data.
  *
  * The memory has three ports. One is the instruction fetch read port. The
  * second is the data port, shared by the CPU's data bus and by a debug port
  * that the testbench uses to load programs and to read results back. The debug
  * port takes priority, and is only meant to be driven while the CPU is held in
  * reset or has halted.
  *
  * Addresses wrap within the memory rather than faulting; there is no bus
  * decoder yet because there is nothing else on the bus.
  */
class CoreSoc(val cfg: CoreConfig = CoreConfig(), val memWords: Int = 4096) extends Component {
  require(isPow2(memWords), "memWords must be a power of two")

  private val wordAddrBits = log2Up(memWords)

  val io = new Bundle {
    val halted  = out Bool ()
    val trapped = out Bool ()
    val cause   = out UInt (Isa.Cause.WIDTH bits)
    val trapPc  = out UInt (cfg.addressWidth bits)

    val cycleCount  = out UInt (32 bits)
    val retireCount = out UInt (32 bits)

    val dbgRegAddr = in UInt (Isa.REG_ADDR_BITS bits)
    val dbgRegData = out Bits (Isa.XLEN bits)

    val dbgRetireValid = out Bool ()
    val dbgRetirePc    = out UInt (cfg.addressWidth bits)

    /** Debug memory port. While `enable` is high it owns the data port. */
    val dbgMemEnable = in Bool ()
    val dbgMemWrite  = in Bool ()
    val dbgMemAddr   = in UInt (wordAddrBits bits)
    val dbgMemWData  = in Bits (Isa.XLEN bits)
    val dbgMemRData  = out Bits (Isa.XLEN bits)
  }

  val cpu = new Core(cfg)
  val ram = Mem(Bits(Isa.XLEN bits), memWords)

  // Power-on contents are all zero, which decodes as ADD r0, r0, r0 -- a NOP
  // that writes nothing. That makes an unprogrammed system harmlessly idle
  // instead of executing whatever the RAM happened to power up with, and gives
  // simulation a deterministic starting point.
  ram.init(Seq.fill(memWords)(B(0, Isa.XLEN bits)))

  /** Byte address to word index. Instructions and data words are four bytes
    * wide, so this is a slice rather than a divide; the low two bits are
    * carried separately as the byte lane select.
    */
  private def wordIndex(byteAddress: UInt): UInt =
    byteAddress(cfg.addressWidth - 1 downto 2).resize(wordAddrBits)

  // ---- instruction port ---------------------------------------------------
  cpu.io.ibus.data := ram.readSync(
    address = wordIndex(cpu.io.ibus.address),
    enable  = cpu.io.ibus.enable
  )

  // ---- data port, shared with the debug port ------------------------------
  val dataPort = new Area {
    val cpuWordAddr = wordIndex(cpu.io.dbus.address)

    val address = Mux(io.dbgMemEnable, io.dbgMemAddr, cpuWordAddr)
    val write   = Mux(io.dbgMemEnable, io.dbgMemWrite, cpu.io.dbus.enable && cpu.io.dbus.write)
    val wdata   = Mux(io.dbgMemEnable, io.dbgMemWData, cpu.io.dbus.wdata)
    val mask    = Mux(io.dbgMemEnable, B"1111", cpu.io.dbus.mask)
    val read    = io.dbgMemEnable || (cpu.io.dbus.enable && !cpu.io.dbus.write)

    ram.write(address = address, data = wdata, enable = write, mask = mask)
    val rdata = ram.readSync(address = address, enable = read)
  }

  cpu.io.dbus.rdata := dataPort.rdata
  io.dbgMemRData    := dataPort.rdata

  // ---- status -------------------------------------------------------------
  cpu.io.dbgRegAddr := io.dbgRegAddr

  io.halted         := cpu.io.halted
  io.trapped        := cpu.io.trapped
  io.cause          := cpu.io.cause
  io.trapPc         := cpu.io.trapPc
  io.cycleCount     := cpu.io.cycleCount
  io.retireCount    := cpu.io.retireCount
  io.dbgRegData     := cpu.io.dbgRegData
  io.dbgRetireValid := cpu.io.dbgRetireValid
  io.dbgRetirePc    := cpu.io.dbgRetirePc
}
