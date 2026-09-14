package core

import spinal.core._

/** The CORE-32 integer ALU.
  *
  * `fn` is the low nibble of the opcode, unmodified. Because the opcode map
  * puts `ADD` at `0x00` and `ADDI` at `0x10`, register and immediate forms of
  * the same operation arrive here with the same function code and the decoder
  * never has to translate.
  *
  * The three multiply results share a single 33x33 signed multiplier. Only
  * `MULH` needs signed operands; `MULHU` needs unsigned ones, and the low half
  * produced by `MUL` is identical either way, so one sign-extension mux is
  * enough to cover all three.
  */
case class Alu(hasMultiplier: Boolean = true) extends Component {
  val io = new Bundle {
    val fn     = in Bits (4 bits)
    val a      = in Bits (Isa.XLEN bits)
    val b      = in Bits (Isa.XLEN bits)
    val result = out Bits (Isa.XLEN bits)
  }

  private val a = io.a
  private val b = io.b
  private val shamt = b(4 downto 0).asUInt

  private val ltSigned   = a.asSInt < b.asSInt
  private val ltUnsigned = a.asUInt < b.asUInt
  private val equal      = a === b

  // Rotate right by concatenating the operand with itself and slicing.
  private val doubled = (a ## a).asUInt
  private val rotated = (doubled >> shamt)(Isa.XLEN - 1 downto 0).asBits

  private val product = if (hasMultiplier) {
    val signedOp = io.fn === B(Isa.Fn.MULH, 4 bits)
    val aExt = ((signedOp && a.msb) ## a).asSInt
    val bExt = ((signedOp && b.msb) ## b).asSInt
    (aExt * bExt).asBits
  } else null

  private def mulLow  = if (hasMultiplier) product(Isa.XLEN - 1 downto 0) else B(0, Isa.XLEN bits)
  private def mulHigh = if (hasMultiplier) product(2 * Isa.XLEN - 1 downto Isa.XLEN) else B(0, Isa.XLEN bits)

  io.result := io.fn.mux(
    B(Isa.Fn.ADD, 4 bits)   -> (a.asUInt + b.asUInt).asBits,
    B(Isa.Fn.SUB, 4 bits)   -> (a.asUInt - b.asUInt).asBits,
    B(Isa.Fn.AND, 4 bits)   -> (a & b),
    B(Isa.Fn.OR, 4 bits)    -> (a | b),
    B(Isa.Fn.XOR, 4 bits)   -> (a ^ b),
    B(Isa.Fn.SHL, 4 bits)   -> (a.asUInt |<< shamt).asBits,
    B(Isa.Fn.SHR, 4 bits)   -> (a.asUInt |>> shamt).asBits,
    B(Isa.Fn.SAR, 4 bits)   -> (a.asSInt |>> shamt).asBits,
    B(Isa.Fn.SLT, 4 bits)   -> ltSigned.asBits.resize(Isa.XLEN),
    B(Isa.Fn.SLTU, 4 bits)  -> ltUnsigned.asBits.resize(Isa.XLEN),
    B(Isa.Fn.MUL, 4 bits)   -> mulLow,
    B(Isa.Fn.MULH, 4 bits)  -> mulHigh,
    B(Isa.Fn.MULHU, 4 bits) -> mulHigh,
    B(Isa.Fn.SEQ, 4 bits)   -> equal.asBits.resize(Isa.XLEN),
    B(Isa.Fn.SNE, 4 bits)   -> (!equal).asBits.resize(Isa.XLEN),
    B(Isa.Fn.ROR, 4 bits)   -> rotated
  )
}
