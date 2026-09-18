package axiom

/** A program with a known answer, used for measuring rather than for testing.
  *
  * The correctness suite already says the core computes the right things. What
  * these are for is saying how long it takes and where the time goes, so each
  * one is shaped around a different way of spending cycles: a dependent chain,
  * a load-use pair, a taken branch every iteration, a store every iteration.
  * The answer is checked all the same, because a measurement of a program that
  * did the wrong thing measures nothing.
  *
  * @param expected the value [[Assembler.a1]] holds when the program halts
  */
case class Workload(
    name: String,
    program: Array[Int],
    data: Map[Int, Long],
    expected: Long
)

object AxiomWorkloads {

  /** The register every workload leaves its answer in: a1. The names are
    * members of an Assembler instance rather than constants, so the number is
    * written down once here instead of at every use.
    */
  val ResultReg = 2

  /** Arrays live well past any program here, and well inside the memory. */
  val SrcWord = 256
  val DstWord = 512
  val SrcByte: Int = SrcWord * 8
  val DstByte: Int = DstWord * 8

  val Count = 64

  private def values: Map[Int, Long] =
    (0 until Count).map(i => (SrcWord + i) -> (i.toLong * 7 + 3)).toMap

  /** Sum of the squares of one to twenty, with the running total kept in
    * memory. A load feeding an add is the hazard the interlock exists for, and
    * the loop is short enough that the branch shadow is a large share of it.
    */
  val sumSquares: Workload = {
    val scratch = 1024
    val program = Assembler() { a =>
      import a._
      li(a0, 20)
      li(t0, 1)
      li(t4, scratch * 8)
      li(t1, 0)
      std(t1, t4, 0)
      label("loop")
      cmpGt(p0, t0, a0)
      bp(p0, "done")
      mul(t2, t0, t0)
      ldd(t3, t4, 0)
      add(t3, t3, t2)
      std(t3, t4, 0)
      addi(t0, t0, 1)
      b("loop")
      label("done")
      ldd(a1, t4, 0)
      halt()
    }
    Workload("sum-of-squares", program, Map.empty, (1 to 20).map(i => i.toLong * i).sum)
  }

  /** A load and a store every iteration and nothing else. This is the one a
    * store buffer should move, and the one a write-through cache is worst at.
    */
  val memcpy: Workload = {
    val program = Assembler() { a =>
      import a._
      li(t0, SrcByte)
      li(t1, DstByte)
      li(t2, Count)
      label("loop")
      ldd(t3, t0, 0)
      std(t3, t1, 0)
      addi(t0, t0, 8)
      addi(t1, t1, 8)
      addi(t2, t2, -1)
      cmpGti(p0, t2, 0)
      bp(p0, "loop")
      ldd(a1, t1, -8)
      halt()
    }
    Workload("memcpy", program, values, values(SrcWord + Count - 1))
  }

  /** Two loads, a multiply and an accumulate: a dependent chain with the
    * multiplier's latency in it, and no store at all.
    */
  val dotProduct: Workload = {
    val program = Assembler() { a =>
      import a._
      li(t0, SrcByte)
      li(t2, Count)
      li(a1, 0)
      label("loop")
      ldd(t3, t0, 0)
      ldd(t4, t0, 8)
      mul(t5, t3, t4)
      add(a1, a1, t5)
      addi(t0, t0, 16)
      addi(t2, t2, -2)
      cmpGti(p0, t2, 0)
      bp(p0, "loop")
      halt()
    }
    val expected = (0 until Count by 2)
      .map(i => values(SrcWord + i) * values(SrcWord + i + 1)).sum
    Workload("dot-product", program, values, expected)
  }

  /** A branch whose direction depends on the data, taken about half the time,
    * inside a loop with a branch of its own. Nothing but control flow.
    */
  val branchy: Workload = {
    val threshold = 7L * (Count / 2) + 3
    val program = Assembler() { a =>
      import a._
      li(t0, SrcByte)
      li(t2, Count)
      li(a1, 0)
      li(t6, threshold)
      label("loop")
      ldd(t3, t0, 0)
      cmpGt(p0, t3, t6)
      bp(p0, "over", invert = true)
      addi(a1, a1, 1)
      label("over")
      addi(t0, t0, 8)
      addi(t2, t2, -1)
      cmpGti(p0, t2, 0)
      bp(p0, "loop")
      halt()
    }
    val expected = (0 until Count).count(i => values(SrcWord + i) > threshold).toLong
    Workload("branchy", program, values, expected)
  }

  /** A chain of independent adds. Nothing stalls; this is the ceiling the
    * others are measured against.
    */
  val straightLine: Workload = {
    val program = Assembler() { a =>
      import a._
      li(a1, 0)
      li(t0, 1)
      li(t1, 2)
      li(t2, 3)
      li(t3, 4)
      li(t4, 100)
      label("loop")
      add(a1, a1, t0)
      add(a1, a1, t1)
      add(a1, a1, t2)
      add(a1, a1, t3)
      addi(t4, t4, -1)
      cmpGti(p0, t4, 0)
      bp(p0, "loop")
      halt()
    }
    Workload("straight-line", program, Map.empty, 100L * 10)
  }

  /** A forward branch that is nearly always taken.
    *
    * Every conditional branch in the other workloads is either a loop closing
    * backwards or a check that falls through, which is exactly what the
    * static prediction rule assumes. Real code is full of the other kind: a
    * bounds check, an error path, a filter that rejects most of what it sees.
    * The branch here skips its body seven times in eight, and the front end
    * guesses wrong every one of those times.
    */
  val filter: Workload = {
    val program = Assembler() { a =>
      import a._
      li(t0, SrcByte)
      li(t2, Count)
      li(a1, 0)
      label("loop")
      ldd(t3, t0, 0)
      andi(t4, t3, 7)       // seven values in eight are not a multiple of eight
      cmpNei(p0, t4, 0)
      bp(p0, "skip")        // forward, and taken seven times in eight
      addi(a1, a1, 1)
      label("skip")
      addi(t0, t0, 8)
      addi(t2, t2, -1)
      cmpGti(p0, t2, 0)
      bp(p0, "loop")
      halt()
    }
    val expected = (0 until Count).count(i => values(SrcWord + i) % 8 == 0).toLong
    Workload("filter", program, values, expected)
  }

  /** The same copy, written the way the instruction set means it to be.
    *
    * Post-indexed accesses fold the pointer bump into the load and the store,
    * so the loop is five instructions rather than seven — and every iteration
    * reads a base register the iteration before it wrote. That is what the
    * base forwarding path exists for, and no other workload here touches it:
    * they all bump their pointers with an add, which is the case the ordinary
    * result forwarding covers.
    */
  val indexed: Workload = {
    val program = Assembler() { a =>
      import a._
      li(t0, SrcByte)
      li(t1, DstByte)
      li(t2, Count)
      label("loop")
      ldd(t3, t0, 8, Isa.Mode.POST)
      std(t3, t1, 8, Isa.Mode.POST)
      addi(t2, t2, -1)
      cmpGti(p0, t2, 0)
      bp(p0, "loop")
      ldd(a1, t1, -8)
      halt()
    }
    Workload("indexed-copy", program, values, values(SrcWord + Count - 1))
  }

  /** Indexed accesses back to back on one pointer, which is the case the base
    * forwarding path is actually for: each access reads the pointer the one
    * before it wrote, one instruction earlier.
    */
  val indexedChain: Workload = {
    val program = Assembler() { a =>
      import a._
      li(t0, SrcByte)
      li(t1, DstByte)
      li(t2, Count / 4)
      label("loop")
      ldd(t3, t0, 8, Isa.Mode.POST)
      ldd(t4, t0, 8, Isa.Mode.POST)
      ldd(t5, t0, 8, Isa.Mode.POST)
      ldd(t6, t0, 8, Isa.Mode.POST)
      std(t3, t1, 8, Isa.Mode.POST)
      std(t4, t1, 8, Isa.Mode.POST)
      std(t5, t1, 8, Isa.Mode.POST)
      std(t6, t1, 8, Isa.Mode.POST)
      addi(t2, t2, -1)
      cmpGti(p0, t2, 0)
      bp(p0, "loop")
      ldd(a1, t1, -8)
      halt()
    }
    Workload("indexed-chain", program, values, values(SrcWord + Count - 1))
  }

  val all: Seq[Workload] =
    Seq(straightLine, sumSquares, memcpy, dotProduct, branchy, filter, indexed, indexedChain)
}
