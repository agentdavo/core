package axiom

import java.util.zip.CRC32
import scala.util.Random

/** Whole programs, each checked against an oracle computed independently in
  * Scala rather than against a value read off the machine.
  *
  * These are the tests that catch a pipeline which is correct on every
  * instruction in isolation and wrong on the combinations real code produces:
  * a loop with the branch at the bottom, a load feeding the compare that feeds
  * that branch, several live call frames at once, address arithmetic on a
  * value that came from memory, and a read-modify-write in a loop.
  *
  * Nothing here inspects the hardware to decide what the right answer is. The
  * factorial is checked against Scala's own product, the sort against
  * `.sorted`, and the checksum against `java.util.zip.CRC32`, so a program
  * that computes something plausible but wrong has nowhere to hide.
  */
class AxiomProgramSpec extends AxiomSpec {

  /** Lay a byte string out in memory the way the machine sees it: little
    * endian, eight bytes to a doubleword.
    */
  private def packBytes(bytes: Seq[Int], atWord: Int): Map[Int, Long] =
    bytes.zipWithIndex.groupBy { case (_, i) => i / 8 }.map { case (w, group) =>
      (atWord + w) -> group.foldLeft(0L) { case (acc, (b, i)) =>
        acc | ((b.toLong & 0xff) << (8 * (i % 8)))
      }
    }

  test("sum of 1 to 100") {
    val r = cosim() { a =>
      import a._
      li(a0, 0)
      li(t0, 1)
      label("loop")
      add(a0, a0, t0)
      addi(t0, t0, 1)
      cmpLei(p0, t0, 100)
      bp(p0, "loop")
      halt()
    }
    expectReg(r, 1, (1 to 100).sum.toLong)
  }

  test("iterative fibonacci") {
    // Computed in Long here and in 64-bit registers there. fib(90) is the
    // largest that fits, so the last case is right at the edge.
    def fib(n: Int): Long = (0 until n).foldLeft((0L, 1L)) { case ((x, y), _) => (y, x + y) }._1

    for (n <- Seq(0, 1, 2, 10, 46, 90)) {
      val r = cosim() { a =>
        import a._
        li(a1, n)
        li(t0, 0)
        li(t1, 1)
        label("loop")
        cmpEqi(p0, a1, 0)
        bp(p0, "done")
        add(t2, t0, t1)
        mov(t0, t1)
        mov(t1, t2)
        addi(a1, a1, -1)
        b("loop")
        label("done")
        mov(a0, t0)
        halt()
      }
      expectReg(r, 1, fib(n), s"fib($n)")
    }
  }

  test("bubble sort of twenty-four doublewords in memory") {
    val rng = new Random(20260914)
    val n = 24
    val values = Array.fill(n)(rng.nextLong())
    val data = values.zipWithIndex.map { case (v, i) => (DataWord + i) -> v }.toMap

    val r = cosim(data = data, readRange = DataWord until (DataWord + n), maxCycles = 500000) { a =>
      import a._
      li(s0, DataByte)
      li(s1, n)
      li(s2, 0) // i
      label("outer")
      addi(t0, s1, -1)
      cmpGe(p0, s2, t0)
      bp(p0, "outerDone")
      li(s3, 0) // j
      label("inner")
      sub(t0, s1, s2)
      addi(t0, t0, -1)
      cmpGe(p0, s3, t0)
      bp(p0, "innerDone")
      shli(t1, s3, 3)
      add(t1, t1, s0)
      ldp(t2, t3, t1, 0)   // the adjacent pair, in one instruction
      cmpLe(p1, t2, t3)    // a signed comparison, so negatives sort first
      bp(p1, "noSwap")
      stp(t3, t2, t1, 0)
      label("noSwap")
      addi(s3, s3, 1)
      b("inner")
      label("innerDone")
      addi(s2, s2, 1)
      b("outer")
      label("outerDone")
      halt()
    }

    val sorted = values.sorted
    for (i <- 0 until n) {
      assert(r.word(DataWord + i) == sorted(i),
        f"element $i is 0x${r.word(DataWord + i)}%016x, expected 0x${sorted(i)}%016x")
    }
  }

  test("byte-wise memcpy") {
    val rng = new Random(7)
    val length = 37
    val source = Array.fill(length)(rng.nextInt(256))
    val srcWord = DataWord
    val dstWord = DataWord + 32
    val srcByte = srcWord * 8
    val dstByte = dstWord * 8

    val r = cosim(
      data = packBytes(source.toSeq, srcWord),
      readRange = dstWord until (dstWord + 8)
    ) { a =>
      import a._
      li(a0, dstByte)
      li(a1, srcByte)
      li(a2, length)
      label("loop")
      cmpEqi(p0, a2, 0)
      bp(p0, "done")
      ldbu(t0, a1, 1, Isa.Mode.POST) // load, then step the source pointer
      stb(t0, a0, 1, Isa.Mode.POST)  // the store consumes the load immediately
      addi(a2, a2, -1)
      b("loop")
      label("done")
      halt()
    }

    val copied = (0 until length).map { i =>
      ((r.word(dstWord + i / 8) >>> (8 * (i % 8))) & 0xff).toInt
    }
    assert(copied == source.toSeq, s"memcpy produced $copied")
    expectReg(r, 1, dstByte + length, "the destination pointer walked the whole string")
    expectReg(r, 2, srcByte + length, "the source pointer walked the whole string")
  }

  test("strlen over a byte string") {
    val text = "the quick brown fox jumps over the lazy dog"
    val bytes = text.map(_.toInt) :+ 0
    val r = cosim(data = packBytes(bytes, DataWord)) { a =>
      import a._
      li(a1, DataByte)
      li(a0, 0)
      label("loop")
      ldbu(t0, a1, 0)
      cmpEqi(p0, t0, 0) // the compare consumes the load immediately
      bp(p0, "done")
      addi(a0, a0, 1)
      addi(a1, a1, 1)
      b("loop")
      label("done")
      halt()
    }
    expectReg(r, 1, text.length.toLong)
  }

  test("recursive factorial through a stack frame built with STP and LDP") {
    val stackTop = DataByte + 2048
    for (n <- Seq(1, 2, 5, 12, 20)) {
      val expected = (1 to n).foldLeft(1L)((acc, i) => acc * i)
      val r = cosim() { a =>
        import a._
        li(sp, stackTop)
        li(a0, n)
        bl("fact")
        halt()

        label("fact")
        cmpLti(p0, a0, 2)
        bp(p0, "base")
        // Push the return address and the argument in one instruction, with
        // the pre-index doing the stack adjustment for free.
        stp(lr, a0, sp, -16, Isa.Mode.PRE)
        addi(a0, a0, -1)
        bl("fact")
        ldp(lr, t1, sp, 16, Isa.Mode.POST)
        mul(a0, a0, t1)
        ret()

        label("base")
        li(a0, 1)
        ret()
      }
      expectReg(r, 1, expected, s"$n factorial")
      expectReg(r, 31, stackTop, "the stack pointer must come back to where it started")
    }
  }

  test("CRC32 of a byte string matches java.util.zip") {
    val text = "Axiom-64 is a load/store machine with thirty-two registers."
    val bytes = text.map(_.toInt & 0xff)

    val expected = {
      val crc = new CRC32()
      crc.update(text.getBytes("US-ASCII"))
      crc.getValue // already a non-negative value below 2^32
    }

    val r = cosim(data = packBytes(bytes, DataWord), maxCycles = 500000) { a =>
      import a._
      li(a1, DataByte)
      li(a2, bytes.length)
      li(a0, 0xffffffffL)
      li(s0, 0xedb88320L) // the reflected polynomial
      li(s1, 0xffffffffL)

      label("byteLoop")
      cmpEqi(p0, a2, 0)
      bp(p0, "done")
      ldbu(t0, a1, 0)
      xor(a0, a0, t0)
      li(t1, 8)

      label("bitLoop")
      andi(t2, a0, 1)
      neg(t2, t2)       // zero or all ones
      and(t2, t2, s0)
      shri(a0, a0, 1)   // the value stays inside 32 bits, so a logical shift is right
      xor(a0, a0, t2)
      addi(t1, t1, -1)
      cmpNei(p1, t1, 0)
      bp(p1, "bitLoop")

      addi(a1, a1, 1)
      addi(a2, a2, -1)
      b("byteLoop")

      label("done")
      xor(a0, a0, s1)   // the final inversion, within 32 bits
      halt()
    }
    expectReg(r, 1, expected, "CRC32")
  }

  test("a jump table of function pointers dispatched through JALR") {
    val tableWord = DataWord + 64
    val tableByte = tableWord * 8

    for ((selector, expected) <- Seq(0 -> 11L, 1 -> 22L, 2 -> 33L)) {
      val r = cosim() { a =>
        import a._
        // The table is built at run time, so the addresses really do come from
        // the assembler's label resolution and travel through memory.
        li(s0, tableByte)
        la(t0, "handler0"); std(t0, s0, 0)
        la(t0, "handler1"); std(t0, s0, 8)
        la(t0, "handler2"); std(t0, s0, 16)

        li(t1, selector)
        shli(t1, t1, 3)
        add(t1, t1, s0)
        ldd(t2, t1, 0)
        jalr(s1, t2, 0) // the target comes straight out of memory
        halt()

        label("handler0"); li(a0, 11); br(s1)
        label("handler1"); li(a0, 22); br(s1)
        label("handler2"); li(a0, 33); br(s1)
      }
      expectReg(r, 1, expected, s"dispatch on $selector")
    }
  }

  test("an atomic counter loop adds up to the expected total") {
    val iterations = 50
    val step = 7L
    val r = cosim(data = Map(DataWord -> 0L), readRange = DataWord until (DataWord + 1)) { a =>
      import a._
      li(s0, DataByte)
      li(s1, step)
      li(t0, iterations)
      li(a0, 0)
      label("loop")
      ldadd(t1, s0, s1) // t1 is the counter's value before this increment
      add(a0, a0, t1)   // accumulate the old values, immediately after the atomic
      addi(t0, t0, -1)
      cmpNei(p0, t0, 0)
      bp(p0, "loop")
      ldd(a1, s0, 0)
      halt()
    }
    // The counter takes the values 0, step, 2 * step and so on, so the sum of
    // the values returned is step times the sum of 0 to iterations - 1.
    val expectedSum = step * (iterations.toLong * (iterations - 1) / 2)
    expectReg(r, 1, expectedSum, "the sum of every old value the atomic returned")
    expectReg(r, 2, step * iterations, "the final counter")
    assert(r.word(DataWord) == step * iterations, "memory holds the final counter")
  }

  test("branchless min, max and absolute value with CMP and SEL") {
    val rng = new Random(4242)
    val pairs: Seq[(Long, Long)] = Seq(
      (0L, 0L), (5L, -5L), (-1L, 1L),
      (Long.MinValue, Long.MaxValue), (Long.MinValue, 0L), (Long.MaxValue, -1L)
    ) ++ Seq.fill(4)((rng.nextLong(), rng.nextLong()))

    val r = cosim(readRange = DataWord until (DataWord + pairs.length * 3), maxCycles = 500000) { a =>
      import a._
      li(s0, DataByte)
      for (((x, y), i) <- pairs.zipWithIndex) {
        li(t0, x)
        li(t1, y)
        cmpLt(p0, t0, t1)
        sel(t2, t0, t1, p0)              // min
        std(t2, s0, i * 24)
        sel(t2, t1, t0, p0)              // max, the same predicate read the other way
        std(t2, s0, i * 24 + 8)
        cmpLti(p1, t0, 0)
        neg(t3, t0)
        sel(t2, t3, t0, p1)              // absolute value
        std(t2, s0, i * 24 + 16)
      }
      halt()
    }

    for (((x, y), i) <- pairs.zipWithIndex) {
      assert(r.word(DataWord + i * 3) == math.min(x, y), f"min of 0x$x%016x and 0x$y%016x")
      assert(r.word(DataWord + i * 3 + 1) == math.max(x, y), f"max of 0x$x%016x and 0x$y%016x")
      // Two's complement has no positive counterpart for the most negative
      // value, so its absolute value is itself. Scala's math.abs agrees.
      assert(r.word(DataWord + i * 3 + 2) == math.abs(x), f"abs of 0x$x%016x")
    }
  }
}
