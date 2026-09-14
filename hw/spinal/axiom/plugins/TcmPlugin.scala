package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib._

/** A tightly coupled memory holding both code and data, plus the debug port
  * that simulation uses to load programs and read results back.
  *
  * The memory is 64 bits wide. Instructions are 32, so the fetch port reads a
  * doubleword and picks the half, with the selection delayed by one cycle to
  * match the registered read and held while the read enable is low. That hold
  * is the contract the fetch plugin relies on to keep an instruction stable
  * across a stall without a shadow register.
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

    /** Byte address to doubleword index. A slice, not a divide. */
    def wordIndex(byteAddress: UInt): UInt =
      byteAddress(AxiomParam.PC_WIDTH.get - 1 downto 3).resize(wordAddressBits)

    // ---- instruction port -----------------------------------------------
    val fetched = ram.readSync(
      address = wordIndex(instructionPort.address),
      enable = instructionPort.enable
    )
    val fetchedHigh = RegNextWhen(instructionPort.address(2), instructionPort.enable) init False
    instructionPort.data := Mux(fetchedHigh, fetched(xlen - 1 downto 32), fetched(31 downto 0))

    // ---- data port, shared with the debug port ---------------------------
    // The debug port wins while it is enabled, which is only meant to happen
    // while the core is held in reset or has halted.
    val address = Mux(soc.io.dbgMemEnable, soc.io.dbgMemAddr, wordIndex(dataPort.address))
    val write   = Mux(soc.io.dbgMemEnable, soc.io.dbgMemWrite, dataPort.enable && dataPort.write)
    val wdata   = Mux(soc.io.dbgMemEnable, soc.io.dbgMemWData, dataPort.wdata)
    val mask    = Mux(soc.io.dbgMemEnable, B(0xff, 8 bits), dataPort.mask)
    val read    = soc.io.dbgMemEnable || (dataPort.enable && !dataPort.write)

    ram.write(address = address, data = wdata, enable = write, mask = mask)
    val readData = ram.readSync(address = address, enable = read)

    dataPort.rdata := readData
    soc.io.dbgMemRData := readData
  }
}
