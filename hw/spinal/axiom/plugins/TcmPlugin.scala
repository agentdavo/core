package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib._

/** A tightly coupled memory holding both code and data, plus the debug port
  * that simulation uses to load programs and read results back.
  *
  * The memory is 64 bits wide. Instructions are 32, so the fetch port reads a
  * doubleword and picks the half, with the selection delayed by one cycle to
  * match the registered read.
  *
  * It answers the cycle after a command is accepted, which is the best a
  * memory can do and what the core is tuned for. What it no longer promises is
  * to hold that answer until somebody reads it: the core buffers responses
  * itself, because a cache could not make that promise.
  *
  * `MEMORY_STALL` makes it refuse commands pseudo-randomly instead of always
  * accepting. That is not a feature of the memory, it is how the stallable
  * protocol is tested: the same programs must produce the same answers through
  * a memory that says no, only taking more cycles. It lives here rather than
  * in the test bench because the test bench drives the core through its ports
  * and cannot reach inside the memory.
  *
  * Power-on contents are zero, which decodes as an ALU add into x0: a NOP that
  * writes nothing. An unprogrammed system is therefore harmlessly idle rather
  * than executing whatever the RAM powered up with.
  */
class TcmPlugin extends AxiomPlugin with MemoryService {

  private var instructionPort: IBus = null
  private var dataPort: DBus = null

  override def newInstructionPort(): IBus = {
    require(instructionPort == null, "the tightly coupled memory has one instruction port")
    instructionPort = IBus(AxiomParam.PC_WIDTH.get)
    instructionPort
  }

  override def newDataPort(): DBus = {
    require(dataPort == null, "the tightly coupled memory has one data port")
    dataPort = DBus(AxiomParam.PC_WIDTH.get, AxiomParam.XLEN.get)
    dataPort
  }

  val logic = during build new Area {
    val soc = host[DebugMemoryService]
    val words = AxiomParam.MEM_WORDS.get
    val xlen = AxiomParam.XLEN.get
    val wordAddressBits = log2Up(words)

    val ram = Mem(Bits(xlen bits), words)
    ram.init(Seq.fill(words)(B(0, xlen bits)))

    /** Refuse a command now and then, so the wait states get exercised.
      *
      * A Galois LFSR rather than a counter: a counter stalls in a fixed
      * pattern, which lines up with the pipeline's own periods and can hide
      * exactly the cases worth finding.
      */
    val stall = new Area {
      val rate = AxiomParam.MEMORY_STALL.get
      require(rate >= 0 && rate < 16, "MEMORY_STALL is out of sixteen")

      val busy = if (rate == 0) False else {
        val lfsr = Reg(Bits(16 bits)) init 0xace1
        lfsr := (lfsr |>> 1) ^ Mux(lfsr.lsb, B(0xb400, 16 bits), B(0, 16 bits))
        lfsr(3 downto 0).asUInt < rate
      }
    }

    /** Byte address to doubleword index. A slice, not a divide. */
    def wordIndex(byteAddress: UInt): UInt =
      byteAddress(AxiomParam.PC_WIDTH.get - 1 downto 3).resize(wordAddressBits)

    // ---- instruction port -----------------------------------------------
    val fetchAccepted = instructionPort.enable && !stall.busy
    instructionPort.ready := !stall.busy
    instructionPort.rvalid := RegNext(fetchAccepted) init False

    val fetched = ram.readSync(
      address = wordIndex(instructionPort.address),
      enable = fetchAccepted
    )
    val fetchedHigh = RegNextWhen(instructionPort.address(2), fetchAccepted) init False
    instructionPort.data := Mux(fetchedHigh, fetched(xlen - 1 downto 32), fetched(31 downto 0))

    // ---- data port, shared with the debug port ---------------------------
    // The debug port wins while it is enabled, which is only meant to happen
    // while the core is held in reset or has halted.
    // The data port stalls on the same signal as the instruction port, so a
    // stalling build exercises both at once and their wait states overlap the
    // way they would behind a shared interconnect.
    val dataAccepted = dataPort.enable && !stall.busy
    dataPort.ready := !stall.busy
    dataPort.rvalid := RegNext(dataAccepted && !dataPort.write) init False

    val address = Mux(soc.io.dbgMemEnable, soc.io.dbgMemAddr, wordIndex(dataPort.address))
    val write   = Mux(soc.io.dbgMemEnable, soc.io.dbgMemWrite, dataAccepted && dataPort.write)
    val wdata   = Mux(soc.io.dbgMemEnable, soc.io.dbgMemWData, dataPort.wdata)
    val mask    = Mux(soc.io.dbgMemEnable, B(0xff, 8 bits), dataPort.mask)
    val read    = soc.io.dbgMemEnable || (dataAccepted && !dataPort.write)

    ram.write(address = address, data = wdata, enable = write, mask = mask)
    val readData = ram.readSync(address = address, enable = read)

    dataPort.rdata := readData
    soc.io.dbgMemRData := readData
  }
}
