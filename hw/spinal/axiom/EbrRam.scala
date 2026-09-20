package axiom

import spinal.core._
import spinal.lib._

/** One ECP5 block RAM, instantiated as the cell rather than inferred.
  *
  * yosys 0.33 maps every inferred memory to this cell with `REGMODE=NOREG`
  * and has no option to do otherwise, and in that mode the cell's clock to
  * data out is 5.8 ns. With `OUTREG` it is 1.0. The register is inside the
  * cell and costs nothing but a cycle, so this is the only way to a memory
  * that is not the binding structure of the design.
  *
  * The pins are the cell's, one Bool each, because that is how the cell is
  * declared. `hw/verilog/DP16KD.v` is the model simulation uses for it.
  */
class DP16KD(dataWidth: Int, outputRegister: Boolean) extends BlackBox {
  require(dataWidth == 9 || dataWidth == 18, "the cell is used nine or eighteen bits wide")

  private val regmode = if (outputRegister) "OUTREG" else "NOREG"
  addGeneric("DATA_WIDTH_A", dataWidth)
  addGeneric("DATA_WIDTH_B", dataWidth)
  addGeneric("REGMODE_A", regmode)
  addGeneric("REGMODE_B", regmode)
  addGeneric("CSDECODE_A", "0b000")
  addGeneric("CSDECODE_B", "0b000")
  addGeneric("WRITEMODE_A", "NORMAL")
  addGeneric("WRITEMODE_B", "NORMAL")
  addGeneric("GSR", "DISABLED")
  addGeneric("CLKAMUX", "CLKA")
  addGeneric("CLKBMUX", "CLKB")

  private def pins(name: String, count: Int, input: Boolean): Seq[Bool] =
    (0 until count).map { i =>
      val pin = if (input) in Bool () else out Bool ()
      pin.setName(s"$name$i")
      pin
    }

  val CLKA = in Bool ()
  val CLKB = in Bool ()
  val CEA = in Bool ()
  val CEB = in Bool ()
  val OCEA = in Bool ()
  val OCEB = in Bool ()
  val WEA = in Bool ()
  val WEB = in Bool ()
  val RSTA = in Bool ()
  val RSTB = in Bool ()
  val CSA = pins("CSA", 3, input = true)
  val CSB = pins("CSB", 3, input = true)
  val ADA = pins("ADA", 14, input = true)
  val ADB = pins("ADB", 14, input = true)
  val DIA = pins("DIA", 18, input = true)
  val DIB = pins("DIB", 18, input = true)
  val DOA = pins("DOA", 18, input = false)
  val DOB = pins("DOB", 18, input = false)

  addRTLPath(new java.io.File("hw/verilog/DP16KD.v").getAbsolutePath)
}

/** A memory that answers two cycles after its address, as the block RAM does
  * with its output register on.
  *
  * Port A reads and writes with a byte mask; port B reads. Both answer two
  * cycles after the clock edge that took the address, always: there is no
  * enable, because the cell's clock enable and output enable are tied on and
  * the pipeline behind it counts cycles from acceptance.
  *
  * `native` instantiates the cells nine bits wide, one per byte, so that the
  * byte mask is a write enable per cell and no read-modify-write is needed.
  * A bank of cells holds 2,048 words; a larger memory is several banks and
  * a multiplexer on the way out, selected by the address bits delayed two
  * cycles to match the data. Otherwise the same memory is a `Mem` with two
  * registers behind it, which is what every test that is not about the cell
  * runs on.
  */
case class EbrRam(words: Int, width: Int, native: Boolean) extends Component {
  require(width % 8 == 0, "the memory is a whole number of bytes wide")
  require(isPow2(words), "the memory is a power of two words deep")

  val Latency = EbrRam.Latency

  val io = new Bundle {
    val a = new Bundle {
      val address = in UInt (log2Up(words) bits)
      val write = in Bool ()
      val wdata = in Bits (width bits)
      val mask = in Bits (width / 8 bits)
      val rdata = out Bits (width bits)
    }
    val b = new Bundle {
      val address = in UInt (log2Up(words) bits)
      val rdata = out Bits (width bits)
    }
  }

  val model = !native generate new Area {
    val mem = Mem(Bits(width bits), words)
    mem.init(Seq.fill(words)(B(0, width bits)))
    mem.write(address = io.a.address, data = io.a.wdata, enable = io.a.write, mask = io.a.mask)
    io.a.rdata := RegNext(mem.readSync(io.a.address)) init 0
    io.b.rdata := RegNext(mem.readSync(io.b.address)) init 0
  }

  val cells = native generate new Area {
    /** Rows in one cell at nine bits wide. */
    val BankWords = 2048
    val bytes = width / 8
    val banks = (words + BankWords - 1) / BankWords
    val rowBits = log2Up(scala.math.min(words, BankWords))
    val bankBits = log2Up(banks)
    val clock = ClockDomain.current.readClockWire

    def row(address: UInt): UInt = address(rowBits - 1 downto 0)
    def bank(address: UInt): UInt =
      if (banks > 1) address(rowBits + bankBits - 1 downto rowBits) else U(0, 1 bits)

    val bankA = bank(io.a.address)
    val bankB = bank(io.b.address)

    /** The cell's bit address: nine bits wide, the row is `AD[13:3]`. */
    def drive(pins: Seq[Bool], address: UInt): Unit = {
      for (i <- 0 until 3) pins(i) := False
      for (i <- 0 until 11) pins(3 + i) := (if (i < rowBits) address(i) else False)
    }

    val bankData = for (b <- 0 until banks) yield new Area {
      val a = Bits(width bits)
      val bb = Bits(width bits)
      for (byte <- 0 until bytes) {
        val cell = new DP16KD(9, outputRegister = true)
        cell.setName(s"bank${b}_byte$byte")
        cell.CLKA := clock
        cell.CLKB := clock
        cell.CEA := True
        cell.CEB := True
        cell.OCEA := True
        cell.OCEB := True
        cell.RSTA := False
        cell.RSTB := False
        cell.CSA.foreach(_ := False)
        cell.CSB.foreach(_ := False)
        drive(cell.ADA, row(io.a.address))
        drive(cell.ADB, row(io.b.address))
        cell.WEA := io.a.write && io.a.mask(byte) && (bankA === b)
        cell.WEB := False
        for (i <- 0 until 18) {
          cell.DIA(i) := (if (i < 8) io.a.wdata(byte * 8 + i) else False)
          cell.DIB(i) := False
        }
        for (i <- 0 until 8) {
          a(byte * 8 + i) := cell.DOA(i)
          bb(byte * 8 + i) := cell.DOB(i)
        }
      }
    }

    if (banks == 1) {
      io.a.rdata := bankData(0).a
      io.b.rdata := bankData(0).bb
    } else {
      io.a.rdata := Vec(bankData.map(_.a))(Delay(bankA, Latency))
      io.b.rdata := Vec(bankData.map(_.bb))(Delay(bankB, Latency))
    }
  }
}

object EbrRam {

  /** Cycles from the clock edge that takes an address to the data. */
  val Latency = 2
}
