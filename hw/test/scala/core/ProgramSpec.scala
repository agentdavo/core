package core

import java.util.zip.CRC32
import scala.util.Random

/** Whole programs, checked against results computed independently in Scala.
  *
  * These are the tests that would catch a pipeline that is correct on every
  * instruction in isolation but wrong on the combinations real code produces:
  * loops with the branch at the bottom, loads feeding branches, a call depth
  * of several frames, and address arithmetic on values that came from memory.
  */
class ProgramSpec extends CoreSpec {

  test("sum of 1 to 100") {
    val r = cosim() { a =>
      import a._
      movi(a0, 0)
      movi(t0, 1)
      label("loop")
      add(a0, a0, t0)
      addi(t0, t0, 1)
      slti(t1, t0, 101)
      bnez(t1, "loop")
      halt()
    }
    expectReg(r, 1, 5050)
  }

  test("iterative fibonacci") {
    def fib(n: Int): Int = (0 until n).foldLeft((0, 1)) { case ((x, y), _) => (y, x + y) }._1

    for (n <- Seq(0, 1, 2, 10, 24, 46)) {
      val r = cosim() { a =>
        import a._
        li(a1, n)
        movi(t0, 0)
        movi(t1, 1)
        label("loop")
        beqz(a1, "done")
        add(t2, t0, t1)
        mov(t0, t1)
        mov(t1, t2)
        addi(a1, a1, -1)
        jmp("loop")
        label("done")
        mov(a0, t0)
        halt()
      }
      expectReg(r, 1, fib(n), s"fib($n)")
    }
  }

  test("bubble sort on an array in memory") {
    val rng = new Random(20260914)
    val n = 24
    val values = Array.fill(n)(rng.nextInt(2000) - 1000)
    val data = values.zipWithIndex.map { case (v, i) => (DataWord + i) -> v }.toMap

    val r = cosim(data = data, readRange = DataWord until (DataWord + n)) { a =>
      import a._
      li(s0, DataByte)
      li(s1, n)
      movi(s2, 0) // i
      label("outer")
      addi(t0, s1, -1)
      bge(s2, t0, "outerDone")
      movi(s3, 0) // j
      label("inner")
      sub(t0, s1, s2)
      addi(t0, t0, -1)
      bge(s3, t0, "innerDone")
      shli(t1, s3, 2)
      add(t1, t1, s0)
      ldw(t2, t1, 0)
      ldw(a3, t1, 4)
      ble(t2, a3, "noSwap")
      stw(a3, t1, 0)
      stw(t2, t1, 4)
      label("noSwap")
      addi(s3, s3, 1)
      jmp("inner")
      label("innerDone")
      addi(s2, s2, 1)
      jmp("outer")
      label("outerDone")
      halt()
    }

    val sorted = values.sorted
    for (i <- 0 until n) {
      assert(r.word(DataWord + i) == sorted(i),
        s"element $i is ${r.word(DataWord + i)}, expected ${sorted(i)}")
    }
  }

  test("byte-wise memcpy") {
    val rng = new Random(7)
    val length = 37
    val source = Array.fill(length)(rng.nextInt(256))
    val srcWord = DataWord
    val dstWord = DataWord + 32
    val srcByte = srcWord * 4
    val dstByte = dstWord * 4

    val r = cosim(
      data = packBytes(source.toSeq, srcWord),
      readRange = dstWord until (dstWord + 16)
    ) { a =>
      import a._
      li(a0, dstByte)
      li(a1, srcByte)
      li(a2, length)
      label("loop")
      beqz(a2, "done")
      ldbu(t0, a1, 0)
      stb(t0, a0, 0) // the store consumes the load result immediately
      addi(a0, a0, 1)
      addi(a1, a1, 1)
      addi(a2, a2, -1)
      jmp("loop")
      label("done")
      halt()
    }

    val copied = (0 until length).map { i =>
      (r.word(dstWord + i / 4) >>> (8 * (i % 4))) & 0xff
    }
    assert(copied == source.toSeq, s"memcpy produced $copied")
    assert(!r.trapped, s"got $r")
  }

  test("strlen over a byte string") {
    val text = "the quick brown fox jumps over the lazy dog"
    val bytes = text.map(_.toInt) :+ 0
    val r = cosim(data = packBytes(bytes, DataWord)) { a =>
      import a._
      li(a1, DataByte)
      movi(a0, 0)
      label("loop")
      ldbu(t0, a1, 0)
      beqz(t0, "done") // the branch consumes the load result immediately
      addi(a0, a0, 1)
      addi(a1, a1, 1)
      jmp("loop")
      label("done")
      halt()
    }
    expectReg(r, 1, text.length)
  }

  test("recursive factorial through the stack") {
    val stackTop = DataByte + 1024
    for (n <- Seq(1, 2, 5, 10, 12)) {
      val expected = (1 to n).foldLeft(1)(_ * _)
      val r = cosim() { a =>
        import a._
        li(sp, stackTop)
        li(a0, n)
        call("fact")
        halt()

        label("fact")
        slti(t0, a0, 2)
        beqz(t0, "recurse")
        movi(a0, 1)
        ret()

        label("recurse")
        addi(sp, sp, -8)
        stw(lr, sp, 0)
        stw(a0, sp, 4)
        addi(a0, a0, -1)
        call("fact")
        ldw(t1, sp, 4)
        ldw(lr, sp, 0)
        addi(sp, sp, 8)
        mul(a0, a0, t1)
        ret()
      }
      expectReg(r, 1, expected, s"$n!")
      expectReg(r, 14, stackTop, "the stack pointer must be restored")
    }
  }

  test("recursive fibonacci, two calls per frame") {
    def fib(n: Int): Int = if (n < 2) n else fib(n - 1) + fib(n - 2)
    val stackTop = DataByte + 1024
    val n = 12

    val r = cosim(maxCycles = 500000) { a =>
      import a._
      li(sp, stackTop)
      li(a0, n)
      call("fib")
      halt()

      label("fib")
      slti(t0, a0, 2)
      beqz(t0, "rec")
      ret() // fib(0) = 0, fib(1) = 1, already in a0

      label("rec")
      addi(sp, sp, -12)
      stw(lr, sp, 0)
      stw(a0, sp, 4)
      addi(a0, a0, -1)
      call("fib")
      stw(a0, sp, 8) // save fib(n-1)
      ldw(a0, sp, 4)
      addi(a0, a0, -2)
      call("fib")
      ldw(t1, sp, 8)
      add(a0, a0, t1)
      ldw(lr, sp, 0)
      addi(sp, sp, 12)
      ret()
    }
    expectReg(r, 1, fib(n), s"fib($n)")
    expectReg(r, 14, stackTop, "the stack pointer must be restored")
  }

  test("CRC32 of a byte string matches java.util.zip") {
    val text = "CORE-32 is a load/store machine with sixteen registers."
    val bytes = text.map(_.toInt & 0xff)

    val expected = {
      val crc = new CRC32()
      crc.update(text.getBytes("US-ASCII"))
      crc.getValue.toInt
    }

    val r = cosim(data = packBytes(bytes, DataWord), maxCycles = 500000) { a =>
      import a._
      li(a1, DataByte)
      li(a2, bytes.length)
      li(a0, -1)
      li(s0, 0xedb88320)

      label("byteLoop")
      beqz(a2, "done")
      ldbu(t0, a1, 0)
      xor(a0, a0, t0)
      movi(t1, 8)

      label("bitLoop")
      andi(t2, a0, 1)
      sub(t2, zero, t2)  // 0 or all ones
      and(t2, t2, s0)
      shri(a0, a0, 1)
      xor(a0, a0, t2)
      addi(t1, t1, -1)
      bnez(t1, "bitLoop")

      addi(a1, a1, 1)
      addi(a2, a2, -1)
      jmp("byteLoop")

      label("done")
      xori(a0, a0, -1)
      halt()
    }
    expectReg(r, 1, expected, "CRC32")
  }

  test("a jump table of function pointers") {
    val tableWord = DataWord + 64
    val tableByte = tableWord * 4

    for ((selector, expected) <- Seq(0 -> 11, 1 -> 22, 2 -> 33)) {
      val r = cosim() { a =>
        import a._
        // Build the table at run time so the addresses come from la.
        li(gp, tableByte)
        la(t0, "handler0"); stw(t0, gp, 0)
        la(t0, "handler1"); stw(t0, gp, 4)
        la(t0, "handler2"); stw(t0, gp, 8)

        li(t1, selector)
        shli(t1, t1, 2)
        add(t1, t1, gp)
        ldw(t2, t1, 0)
        callr(s0, t2, 0) // the target comes straight out of memory
        halt()

        label("handler0"); movi(a0, 11); jmpr(s0, 0)
        label("handler1"); movi(a0, 22); jmpr(s0, 0)
        label("handler2"); movi(a0, 33); jmpr(s0, 0)
      }
      expectReg(r, 1, expected, s"dispatch on $selector")
    }
  }

  test("cycles per instruction on a straight-line block is close to one") {
    val r = cosim() { a =>
      import a._
      movi(t0, 1)
      for (_ <- 0 until 200) addi(t1, t0, 1) // no dependencies between them
      halt()
    }
    val ipc = r.retired.toDouble / r.cycles.toDouble
    assert(ipc > 0.9, f"instructions per cycle was $ipc%.3f (${r.retired} in ${r.cycles})")
  }
}
