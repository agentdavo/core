package axiom

import scala.util.Random

/** Generator of random but well-formed Axiom-64 programs.
  *
  * Two properties hold by construction, so every generated program is a valid
  * experiment rather than a coin flip:
  *
  *  - it terminates, because every branch and jump goes forward
  *  - it never traps, because every address is masked into a scratch region
  *    with the alignment its access size needs, in every addressing mode
  *
  * Everything else is free: which registers and predicates are read and
  * written, how close producers and consumers land, whether a branch is taken,
  * and which of the multi-pass memory forms appear. That is what puts pressure
  * on the forwarding network, the interlock and the branch shadow.
  */
object RandomAxiomProgram {

  /** Scratch region, clear of any generated program. */
  val DataByte: Int = 0x2000
  val DataWord: Int = DataByte / 8
  val DataWords: Int = 128

  val dataRange: Range = DataWord until (DataWord + DataWords)

  private type Emit3 = (Assembler, Int, Int, Int) => Unit

  private val registerOps: Seq[(String, Emit3)] = Seq(
    "add" -> (_.add(_, _, _)), "sub" -> (_.sub(_, _, _)), "and" -> (_.and(_, _, _)),
    "or" -> (_.or(_, _, _)), "xor" -> (_.xor(_, _, _)), "andn" -> (_.andn(_, _, _)),
    "orn" -> (_.orn(_, _, _)), "xnor" -> (_.xnor(_, _, _)), "shl" -> (_.shl(_, _, _)),
    "shr" -> (_.shr(_, _, _)), "sar" -> (_.sar(_, _, _)), "ror" -> (_.ror(_, _, _)),
    "slt" -> (_.slt(_, _, _)), "sltu" -> (_.sltu(_, _, _)), "mul" -> (_.mul(_, _, _)),
    "mulh" -> (_.mulh(_, _, _)), "mulhu" -> (_.mulhu(_, _, _)), "addw" -> (_.addw(_, _, _)),
    "subw" -> (_.subw(_, _, _)), "mulw" -> (_.mulw(_, _, _)), "shlw" -> (_.shlw(_, _, _)),
    "shrw" -> (_.shrw(_, _, _)), "sarw" -> (_.sarw(_, _, _)), "rorw" -> (_.rorw(_, _, _)),
    "min" -> (_.min(_, _, _)), "max" -> (_.max(_, _, _)), "minu" -> (_.minu(_, _, _)),
    "maxu" -> (_.maxu(_, _, _))
  )

  private val shiftOps: Seq[Emit3] = Seq(
    _.shli(_, _, _), _.shri(_, _, _), _.sari(_, _, _), _.rori(_, _, _),
    _.shlwi(_, _, _), _.shrwi(_, _, _), _.sarwi(_, _, _), _.rorwi(_, _, _)
  )

  private val immediateOps: Seq[Emit3] = Seq(
    _.addi(_, _, _), _.andi(_, _, _), _.ori(_, _, _),
    _.xori(_, _, _), _.slti(_, _, _), _.sltui(_, _, _)
  )

  private val loadOps: Seq[(Int, (Assembler, Int, Int, Int, Int) => Unit)] = Seq(
    Isa.SIZE_B -> ((a, t, n, o, m) => a.ldb(t, n, o, m)),
    Isa.SIZE_B -> ((a, t, n, o, m) => a.ldbu(t, n, o, m)),
    Isa.SIZE_H -> ((a, t, n, o, m) => a.ldh(t, n, o, m)),
    Isa.SIZE_H -> ((a, t, n, o, m) => a.ldhu(t, n, o, m)),
    Isa.SIZE_W -> ((a, t, n, o, m) => a.ldw(t, n, o, m)),
    Isa.SIZE_W -> ((a, t, n, o, m) => a.ldwu(t, n, o, m)),
    Isa.SIZE_D -> ((a, t, n, o, m) => a.ldd(t, n, o, m))
  )

  private val storeOps: Seq[(Int, (Assembler, Int, Int, Int, Int) => Unit)] = Seq(
    Isa.SIZE_B -> ((a, t, n, o, m) => a.stb(t, n, o, m)),
    Isa.SIZE_H -> ((a, t, n, o, m) => a.sth(t, n, o, m)),
    Isa.SIZE_W -> ((a, t, n, o, m) => a.stw(t, n, o, m)),
    Isa.SIZE_D -> ((a, t, n, o, m) => a.std(t, n, o, m))
  )

  private val atomicFns: Seq[Int] = Isa.AtomicFn.ALL.toSeq.sorted

  /** @param groups     how many random instruction groups to emit
    * @param writePool  registers the program may write; a small pool makes
    *                   producers and consumers land close together
    * @param memoryBias extra weight on the memory groups
    */
  def generate(
      rng: Random,
      groups: Int,
      writePool: Seq[Int] = 1 until Isa.REG_COUNT,
      memoryBias: Int = 0
  ): Array[Int] = {
    val asm = new Assembler(0)
    import asm._

    def anyReg(): Int = rng.nextInt(Isa.REG_COUNT)
    def destReg(): Int = writePool(rng.nextInt(writePool.length))
    def scratchReg(): Int = { val r = destReg(); if (r == 0) 1 else r }
    def anyPred(): Int = rng.nextInt(Isa.PRED_COUNT)
    def smallImm(): Int = rng.nextInt(4096) - 2048

    // Seed the registers with values that exercise the edges.
    for (r <- 1 until Isa.REG_COUNT) {
      val v = rng.nextInt(8) match {
        case 0 => 0L
        case 1 => -1L
        case 2 => rng.nextInt(256).toLong
        case 3 => Long.MinValue
        case 4 => Long.MaxValue
        case 5 => 0xffffffffL
        case 6 => rng.nextInt().toLong
        case _ => rng.nextLong()
      }
      li(r, v)
    }
    for (p <- 0 until Isa.PRED_COUNT) cmpi(Isa.Cc.LT, p, rng.nextInt(Isa.REG_COUNT), smallImm())

    /** Put an aligned, in-range scratch address into `base`, and return the
      * largest displacement that stays inside the region.
      */
    def formAddress(base: Int, size: Int): Int = {
      val alignMask = 0x1ff & ~((1 << size) - 1)
      andi(base, anyReg(), alignMask)
      ori(base, base, DataByte)
      size
    }

    val kinds: Seq[String] =
      Seq.fill(5)("reg") ++ Seq.fill(2)("shift") ++ Seq.fill(4)("imm") ++
        Seq.fill(2)("const") ++ Seq.fill(2)("cmp") ++ Seq.fill(2)("sel") ++
        Seq.fill(3 + memoryBias * 4)("mem") ++ Seq.fill(1 + memoryBias)("pair") ++
        Seq.fill(1 + memoryBias)("atomic") ++ Seq.fill(1)("ordered") ++
        Seq.fill(3)("branch") ++ Seq.fill(1)("jump") ++ Seq.fill(1)("call") ++
        Seq.fill(1)("indirect") ++ Seq.fill(1)("pcrel")

    for (i <- 0 until groups) {
      label(s"g$i")
      def forwardLabel(): String = s"g${math.min(i + 1 + rng.nextInt(4), groups)}"
      def randomMode(): Int = rng.nextInt(3)

      kinds(rng.nextInt(kinds.length)) match {
        case "reg" =>
          registerOps(rng.nextInt(registerOps.length))._2(asm, destReg(), anyReg(), anyReg())

        case "shift" =>
          val which = rng.nextInt(shiftOps.length)
          val limit = if (which >= 4) 32 else 64
          shiftOps(which)(asm, destReg(), anyReg(), rng.nextInt(limit))

        case "imm" =>
          immediateOps(rng.nextInt(immediateOps.length))(asm, destReg(), anyReg(), smallImm())

        case "const" =>
          rng.nextInt(4) match {
            case 0 => movz(destReg(), rng.nextInt(0x10000), rng.nextInt(4))
            case 1 => movn(destReg(), rng.nextInt(0x10000), rng.nextInt(4))
            case 2 => movk(destReg(), rng.nextInt(0x10000), rng.nextInt(4))
            case _ => li(destReg(), rng.nextLong())
          }

        case "cmp" =>
          val cc = Isa.Cc.ALL.toSeq.sorted.apply(rng.nextInt(Isa.Cc.ALL.size))
          if (rng.nextBoolean()) cmp(cc, anyPred(), anyReg(), anyReg())
          else cmpi(cc, anyPred(), anyReg(), smallImm())

        case "sel" =>
          sel(destReg(), anyReg(), anyReg(), anyPred(), rng.nextBoolean())

        case "mem" =>
          val base = scratchReg()
          val isLoad = rng.nextBoolean()
          val (size, emit) =
            if (isLoad) loadOps(rng.nextInt(loadOps.length))
            else storeOps(rng.nextInt(storeOps.length))
          formAddress(base, size)
          val bytes = 1 << size
          val offset = rng.nextInt(32) * bytes
          val mode = randomMode()
          // A data register equal to the base is architecturally undefined
          // once the base is also written, so keep them apart.
          var data = if (isLoad) destReg() else anyReg()
          if (mode != Isa.Mode.OFFSET && data == base) data = if (base == 1) 2 else 1
          emit(asm, data, base, offset, mode)

        case "pair" =>
          val base = scratchReg()
          formAddress(base, Isa.SIZE_D)
          val offset = rng.nextInt(16) * 8
          val mode = randomMode()
          val isLoad = rng.nextBoolean()
          val candidates = (1 until Isa.REG_COUNT).filter(_ != base)
          val t1 = candidates(rng.nextInt(candidates.length))
          val t2 = candidates.filter(_ != t1).apply(rng.nextInt(candidates.length - 1))
          if (isLoad) ldp(t1, t2, base, offset, mode) else stp(t1, t2, base, offset, mode)

        case "atomic" =>
          val size = if (rng.nextBoolean()) Isa.SIZE_W else Isa.SIZE_D
          val base = scratchReg()
          formAddress(base, size)
          val fn = atomicFns(rng.nextInt(atomicFns.length))
          val candidates = (1 until Isa.REG_COUNT).filter(_ != base)
          val rd = candidates(rng.nextInt(candidates.length))
          val rs = anyReg()
          atomic(fn, rd, base, rs, size, rng.nextInt(4))

        case "ordered" =>
          val size = rng.nextInt(4)
          val base = scratchReg()
          formAddress(base, size)
          val ord = if (rng.nextBoolean()) Isa.Ord.ACQREL else Isa.Ord.SEQ
          val data = { val d = destReg(); if (d == base) (if (base == 1) 2 else 1) else d }
          if (rng.nextBoolean()) ldOrd(data, base, size, ord) else stOrd(data, base, size, ord)

        case "branch" =>
          bp(anyPred(), forwardLabel(), rng.nextBoolean())

        case "jump" =>
          b(forwardLabel())

        case "indirect" =>
          // A computed target, still forward so the program terminates. This
          // is the only way the generator reaches JALR and the low-bit masking
          // it does.
          val holder = scratchReg()
          val target = forwardLabel()
          la(holder, target)
          if (rng.nextBoolean()) ori(holder, holder, rng.nextInt(4))
          jalr(if (rng.nextBoolean()) 0 else destReg(), holder, 0)

        case "pcrel" =>
          addpc(destReg(), (rng.nextInt(2048) - 1024) * 4)

        case _ =>
          bl(forwardLabel())
      }
    }

    label(s"g$groups")
    halt()

    val program = asm.assemble()
    require(program.length * 4 < DataByte,
      s"generated program of ${program.length} words would overlap the scratch region")
    program
  }
}
