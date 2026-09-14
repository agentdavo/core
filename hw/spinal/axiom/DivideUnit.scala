package axiom

import spinal.core._
import spinal.lib._

/** A restoring divider, one bit per cycle, as a component of its own.
  *
  * A component rather than plugin internals, for the reason the cache is one:
  * arithmetic with sixty-four iterations and four sign conventions is much
  * easier to test by driving its ports than by writing programs and reading
  * registers afterwards.
  *
  * Restoring rather than anything cleverer. A radix-4 or SRT divider halves or
  * quarters the iteration count and costs a quotient-digit selection table, a
  * redundant representation and a much harder correctness argument; on a core
  * that is routing bound and where division is rare, that is the wrong trade.
  * What this costs is a cycle per bit, and the stall protocol already knows how
  * to wait.
  *
  * Both operands are made non-negative on the way in and the signs are applied
  * on the way out, so the iteration itself is unsigned and there is one of it
  * rather than two. The sign of a remainder follows the dividend, which is what
  * makes `(a / b) * b + (a % b) == a` hold for negative operands.
  */
case class DivideUnit(width: Int = Isa.XLEN) extends Component {

  val io = new Bundle {
    /** Raised for one cycle to start; ignored while `busy`. */
    val start = in Bool ()
    val unsigned = in Bool ()
    val word = in Bool ()
    val dividend = in Bits (width bits)
    val divisor = in Bits (width bits)

    val busy = out Bool ()
    val quotient = out Bits (width bits)
    val remainder = out Bits (width bits)
  }

  /** Narrow to the 32-bit forms on the way in.
    *
    * A W form divides the low halves and sign extends the answer, so the
    * operands are extended to the full width first and the same iteration runs
    * on both. Thirty-two of its steps are then guaranteed to do nothing, which
    * is a cycle each; the alternative is a second, narrower divider.
    */
  val narrowed = new Area {
    def take(value: Bits): Bits = {
      val full = Bits(width bits)
      full := value
      when(io.word) {
        full := Mux(io.unsigned,
          value(31 downto 0).asUInt.resize(width).asBits,
          value(31 downto 0).asSInt.resize(width).asBits)
      }
      full
    }
    val dividend = take(io.dividend)
    val divisor = take(io.divisor)
  }

  /** The sign of each operand, and what the answers have to be negated by.
    *
    * An unsigned divide has no signs; a signed one divides magnitudes. The
    * quotient is negative when the operands disagree in sign and the remainder
    * takes the sign of the dividend, which is the convention that keeps the
    * division identity true.
    */
  val signs = new Area {
    val signed = !io.unsigned
    val dividendNegative = signed && narrowed.dividend.msb
    val divisorNegative = signed && narrowed.divisor.msb

    def magnitude(value: Bits, negative: Bool): UInt =
      Mux(negative, ~value.asUInt + 1, value.asUInt)

    val dividend = magnitude(narrowed.dividend, dividendNegative)
    val divisor = magnitude(narrowed.divisor, divisorNegative)
  }

  val counter = Reg(UInt(log2Up(width + 1) bits)) init 0
  val running = Reg(Bool()) init False

  val remainder = Reg(UInt(width bits)) init 0
  val quotient = Reg(UInt(width bits)) init 0
  val divisor = Reg(UInt(width bits)) init 0

  /** Held from the start, because the operands are gone by the time the
    * answers are needed.
    */
  val negateQuotient = Reg(Bool()) init False
  val negateRemainder = Reg(Bool()) init False
  val byZero = Reg(Bool()) init False
  val overflow = Reg(Bool()) init False
  val rawDividend = Reg(Bits(width bits)) init 0
  val wasWord = Reg(Bool()) init False

  io.busy := running

  when(io.start && !running) {
    running := True
    counter := width
    remainder := 0
    quotient := signs.dividend
    divisor := signs.divisor
    rawDividend := narrowed.dividend
    wasWord := io.word

    // A negative zero has no sign, so a quotient of zero must not be negated
    // into one. Comparing magnitudes rather than the original operands keeps
    // that right without a special case later.
    negateQuotient := signs.dividendNegative =/= signs.divisorNegative
    negateRemainder := signs.dividendNegative

    byZero := signs.divisor === 0

    // The one signed overflow: the most negative value divided by minus one.
    // Its magnitude is the most negative value again, which is why it has to
    // be spotted here rather than found in the answer.
    val mostNegative = narrowed.dividend.msb && narrowed.dividend(width - 2 downto 0) === 0
    overflow := !io.unsigned && mostNegative && narrowed.divisor.asSInt === -1
  }

  /** One restoring step: shift the partial remainder up by a bit of the
    * quotient, subtract, and keep the subtraction if it did not borrow. The
    * quotient register doubles as the shift register the dividend comes out
    * of, which is what keeps this to two wide registers rather than three.
    */
  when(running) {
    val shifted = (remainder @@ quotient.msb).resize(width + 1)
    val trial = shifted - divisor.resize(width + 1)
    val fits = !trial.msb

    remainder := Mux(fits, trial, shifted).resize(width)
    quotient := quotient(width - 2 downto 0) @@ fits

    counter := counter - 1
    when(counter === 1) { running := False }
  }

  /** The answers, with the signs put back and the two defined special cases
    * substituted. Both substitutions happen here rather than by skipping the
    * iteration, so a division takes the same time whatever it divides.
    */
  val result = new Area {
    def negate(value: UInt): Bits = (~value + 1).asBits

    val q = Mux(negateQuotient, negate(quotient), quotient.asBits)
    val r = Mux(negateRemainder, negate(remainder), remainder.asBits)

    val allOnes = B(width bits, default -> True)
    val quotientOut = Bits(width bits)
    val remainderOut = Bits(width bits)

    quotientOut := q
    remainderOut := r
    when(overflow) {
      quotientOut := rawDividend
      remainderOut := B(0, width bits)
    }
    when(byZero) {
      quotientOut := allOnes
      remainderOut := rawDividend
    }

    def widen(value: Bits): Bits =
      Mux(wasWord, value(31 downto 0).asSInt.resize(width).asBits, value)
  }

  io.quotient := result.widen(result.quotientOut)
  io.remainder := result.widen(result.remainderOut)
}
