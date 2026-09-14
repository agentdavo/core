package axiom

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import scala.util.Random

/** The divider on its own.
  *
  * Sixty-four iterations and four sign conventions is the kind of arithmetic
  * that is much cheaper to check at its own ports than through programs and
  * registers afterwards. Every case here is compared against `Isa.divide`,
  * which is the same function the reference model uses, so the hardware and
  * the model cannot drift apart on the awkward cases.
  */
class DivideUnitSpec extends AnyFunSuite {

  private lazy val compiled =
    SimConfig.withVerilator.workspacePath("simWorkspace")
      .workspaceName("DivideUnit").compile(DivideUnit())

  private val Mask = (BigInt(1) << 64) - 1
  private def unsigned(value: Long): BigInt = BigInt(value) & Mask

  private def run(dut: DivideUnit, fn: Int, a: Long, b: Long): Long = {
    dut.io.start #= true
    dut.io.unsigned #= Isa.DivFn.unsigned(fn)
    dut.io.word #= Isa.DivFn.word(fn)
    dut.io.dividend #= unsigned(a)
    dut.io.divisor #= unsigned(b)
    dut.clockDomain.waitSampling()
    dut.io.start #= false

    // busy is a register, so it does not report the start until the edge
    // after it. Polling it straight away reads the state before the request.
    dut.clockDomain.waitSampling()

    var spent = 0
    while (dut.io.busy.toBoolean && spent < 200) {
      dut.clockDomain.waitSampling()
      spent += 1
    }
    assert(spent < 200, "the divider never finished")
    val out = if (Isa.DivFn.remainder(fn)) dut.io.remainder else dut.io.quotient
    out.toBigInt.toLong
  }

  private def check(name: String)(cases: Seq[(Long, Long)]): Unit =
    test(name) {
      compiled.doSim(seed = 1) { dut =>
        dut.io.start #= false
        dut.io.unsigned #= false
        dut.io.word #= false
        dut.io.dividend #= 0
        dut.io.divisor #= 0
        dut.clockDomain.forkStimulus(10)
        SimTimeout(2000000)
        dut.clockDomain.waitSampling(3)

        for ((a, b) <- cases; fn <- Isa.DivFn.ALL.toSeq.sorted) {
          val got = run(dut, fn, a, b)
          val want = Isa.divide(fn, a, b)
          assert(got == want,
            f"${Isa.DivFn.NAMES(fn)} of 0x$a%016x by 0x$b%016x: got 0x$got%016x want 0x$want%016x")
        }
      }
    }

  check("small values in every sign combination") {
    for (a <- Seq(0L, 1L, 7L, 100L, -1L, -7L, -100L); b <- Seq(1L, 3L, 10L, -1L, -3L, -10L))
      yield (a, b)
  }

  check("division by zero, which is defined rather than trapped") {
    Seq(0L, 1L, -1L, 12345L, Long.MinValue, Long.MaxValue).map(a => (a, 0L))
  }

  check("the one signed overflow, and its neighbours") {
    Seq(
      (Long.MinValue, -1L), (Long.MinValue, 1L), (Long.MinValue + 1, -1L),
      (Long.MaxValue, -1L), (Int.MinValue.toLong, -1L), (Int.MinValue.toLong + 1, -1L),
      (Int.MaxValue.toLong, -1L)
    )
  }

  check("values that only differ in the high half, which the W forms discard") {
    Seq(
      (0x1234567800000007L, 3L), (0xffffffff00000007L, 3L),
      (0x00000000fffffff9L, 3L), (0x7fffffffffffffffL, 0x100000000L),
      (0xffffffffffffffffL, 2L)
    )
  }

  test("random values against the reference") {
    compiled.doSim(seed = 2) { dut =>
      dut.io.start #= false
      dut.io.unsigned #= false
      dut.io.word #= false
      dut.io.dividend #= 0
      dut.io.divisor #= 0
      dut.clockDomain.forkStimulus(10)
      SimTimeout(20000000)
      dut.clockDomain.waitSampling(3)

      val rng = new Random(9)
      for (_ <- 0 until 150) {
        val a = rng.nextLong()
        // A mixture of wide and narrow divisors, so short quotients and long
        // ones both get exercised.
        val b = rng.nextInt(4) match {
          case 0 => rng.nextLong()
          case 1 => (rng.nextInt(255) + 1).toLong
          case 2 => rng.nextInt().toLong
          case _ => 1L << rng.nextInt(63)
        }
        val fn = Isa.DivFn.ALL.toSeq(rng.nextInt(8))
        val got = run(dut, fn, a, b)
        val want = Isa.divide(fn, a, b)
        assert(got == want,
          f"${Isa.DivFn.NAMES(fn)} of 0x$a%016x by 0x$b%016x: got 0x$got%016x want 0x$want%016x")
      }
    }
  }
}
