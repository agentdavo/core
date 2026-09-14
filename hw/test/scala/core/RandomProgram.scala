package core

import scala.util.Random

/** Generator of random but well-formed CORE-32 programs.
  *
  * Two properties are guaranteed by construction, so that every generated
  * program is a valid experiment rather than a coin flip:
  *
  *  - it terminates, because every branch and jump goes forward
  *  - it never traps, because every memory address is masked into a reserved
  *    scratch region with the alignment the access size needs
  *
  * Everything else is left free: which registers are read and written, how
  * close producers and consumers are, whether a branch is taken. That is what
  * puts pressure on forwarding, the interlock and the branch shadow.
  */
object RandomProgram {

  /** Scratch region, well clear of any generated program. */
  val DataByte: Int  = 8192
  val DataWord: Int  = DataByte / 4
  val DataWords: Int = 256

  val dataRange: Range = DataWord until (DataWord + DataWords)

  private type Emit3 = (Assembler, Int, Int, Int) => Unit

  private val registerOps: Seq[(String, Emit3)] = Seq(
    "add" -> (_.add(_, _, _)), "sub" -> (_.sub(_, _, _)),
    "and" -> (_.and(_, _, _)), "or" -> (_.or(_, _, _)), "xor" -> (_.xor(_, _, _)),
    "shl" -> (_.shl(_, _, _)), "shr" -> (_.shr(_, _, _)), "sar" -> (_.sar(_, _, _)),
    "slt" -> (_.slt(_, _, _)), "sltu" -> (_.sltu(_, _, _)),
    "mul" -> (_.mul(_, _, _)), "mulh" -> (_.mulh(_, _, _)), "mulhu" -> (_.mulhu(_, _, _)),
    "seq" -> (_.seq(_, _, _)), "sne" -> (_.sne(_, _, _)), "ror" -> (_.ror(_, _, _))
  )

  private val immediateOps: Seq[(String, Emit3)] = Seq(
    "addi" -> (_.addi(_, _, _)), "andi" -> (_.andi(_, _, _)), "ori" -> (_.ori(_, _, _)),
    "xori" -> (_.xori(_, _, _)), "shli" -> (_.shli(_, _, _)), "shri" -> (_.shri(_, _, _)),
    "sari" -> (_.sari(_, _, _)), "slti" -> (_.slti(_, _, _)), "sltui" -> (_.sltui(_, _, _)),
    "seqi" -> (_.seqi(_, _, _)), "snei" -> (_.snei(_, _, _)), "rori" -> (_.rori(_, _, _))
  )

  private val branchOps: Seq[(Assembler, Int, Int, String) => Unit] = Seq(
    (a, x, y, l) => a.beq(x, y, l), (a, x, y, l) => a.bne(x, y, l),
    (a, x, y, l) => a.blt(x, y, l), (a, x, y, l) => a.bge(x, y, l),
    (a, x, y, l) => a.bltu(x, y, l), (a, x, y, l) => a.bgeu(x, y, l)
  )

  /** @param groups      how many random instruction groups to emit
    * @param writePool   registers the program is allowed to write; a small
    *                    pool makes producers and consumers land close together
    * @param memoryBias  extra weight on memory groups, 0 for the default mix
    */
  def generate(
      rng: Random,
      groups: Int,
      writePool: Seq[Int] = 1 until Isa.REG_COUNT,
      memoryBias: Int = 0
  ): Array[Int] = {
    val asm = new Assembler(0)
    import asm._

    def anyReg(): Int   = rng.nextInt(Isa.REG_COUNT)
    def destReg(): Int  = writePool(rng.nextInt(writePool.length))
    def scratchReg(): Int = {
      // A destination that is never r0, so the masked address survives.
      val r = destReg()
      if (r == 0) 1 else r
    }
    def smallImm(): Int = rng.nextInt(4096) - 2048
    def wideImm(): Int  = rng.nextInt(262144) - 131072

    // Seed every register with something interesting.
    for (r <- 1 until Isa.REG_COUNT) {
      val v = rng.nextInt(6) match {
        case 0 => 0
        case 1 => -1
        case 2 => rng.nextInt(256)
        case 3 => 0x80000000
        case 4 => 0x7fffffff
        case _ => rng.nextInt()
      }
      li(r, v)
    }

    val kinds: Seq[String] =
      Seq.fill(4)("reg") ++ Seq.fill(4)("imm") ++ Seq.fill(2)("const") ++
        Seq.fill(3 + memoryBias * 4)("mem") ++ Seq.fill(2)("branch") ++
        Seq.fill(1)("jump") ++ Seq.fill(1)("call")

    for (i <- 0 until groups) {
      label(s"g$i")
      def forwardLabel(): String = s"g${math.min(i + 1 + rng.nextInt(4), groups)}"

      kinds(rng.nextInt(kinds.length)) match {
        case "reg" =>
          registerOps(rng.nextInt(registerOps.length))._2(asm, destReg(), anyReg(), anyReg())

        case "imm" =>
          val imm = if (rng.nextBoolean()) smallImm() else wideImm()
          immediateOps(rng.nextInt(immediateOps.length))._2(asm, destReg(), anyReg(), imm)

        case "const" =>
          rng.nextInt(3) match {
            case 0 => movi(destReg(), rng.nextInt(4194304) - 2097152)
            case 1 => li(destReg(), rng.nextInt())
            case _ => addpc(destReg(), rng.nextInt(2048) - 1024)
          }

        case "mem" =>
          val base = scratchReg()
          val size = rng.nextInt(3)
          val mask = size match {
            case 0 => 0x3fc // word aligned
            case 1 => 0x3fe // halfword aligned
            case _ => 0x3ff // any byte
          }
          andi(base, anyReg(), mask)
          val other = destReg()
          if (rng.nextBoolean()) {
            size match {
              case 0 => ldw(other, base, DataByte)
              case 1 => if (rng.nextBoolean()) ldh(other, base, DataByte) else ldhu(other, base, DataByte)
              case _ => if (rng.nextBoolean()) ldb(other, base, DataByte) else ldbu(other, base, DataByte)
            }
          } else {
            val source = anyReg()
            size match {
              case 0 => stw(source, base, DataByte)
              case 1 => sth(source, base, DataByte)
              case _ => stb(source, base, DataByte)
            }
          }

        case "branch" =>
          branchOps(rng.nextInt(branchOps.length))(asm, anyReg(), anyReg(), forwardLabel())

        case "jump" =>
          jmp(forwardLabel())

        case _ =>
          call(forwardLabel())
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
