package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline._

/** Drives the instruction bus and hands the word to decode.
  *
  * The memory promises a word for every command, in order, and nothing about
  * when. So a fetch is two halves that need not be in the same stage: commands
  * go out as fast as the memory will take them, decode waits for answers, and
  * between the two sit three small queues in issue order — the addresses
  * accepted and not yet moved into the pipeline, a tag per command accepted
  * and not yet answered, and the words that have arrived and not yet been
  * taken.
  *
  * **The program counter is not a pipeline stage.** It advances when a
  * command is accepted, not when a transaction leaves fetch, so the second
  * command goes out while the first answer is still on its way. That is the
  * difference between a memory that answers in two cycles halving the
  * instruction rate and it costing nothing, and it is what the block RAM's
  * output register needs from the front end to be worth using. The fetch
  * stage proper is the head of the address queue: it holds what the counter
  * has already asked for, and moves it into decode as decode can take it.
  *
  * The invariant that makes it safe is that everything is in order. Decode
  * takes the next word, always, because the next word is always its own: a
  * transaction that was thrown before its word arrived marks its tag dead
  * where it stands in the queue, and the answer is dropped when it comes. A
  * redirect empties the address queue and kills every tag, keeping only the
  * one for the instruction in decode if that instruction stays: it is the
  * one that asked.
  */
class FetchPlugin extends AxiomPlugin with FetchService {

  private val present = spinal.core.fiber.Handle[Bool]()

  override def instructionPresent: Bool = present.get

  private var bus: IBus = null
  private var perfFetch: Bool = null

  val setupLogic = during setup new Area {
    bus = host[MemoryService].newInstructionPort()
    perfFetch = host[PerfService].newCounter("fetch")
  }

  val logic = during build new Area {
    val fetchNode = ctrl(Stages.FETCH)
    val decodeNode = ctrl(Stages.DECODE)
    val pcs = host[PcService]

    val InFlight = FetchPlugin.InFlight

    /** Addresses the counter has been asked for and the pipeline has not
      * taken: the fetch stage's transactions, before they are transactions.
      */
    val records = new FetchRing(InFlight, FetchRecord())

    /** Tags, one per command accepted and not yet answered, in issue order.
      *
      * A tag says only whether anybody still wants the answer. The transaction
      * in decode owns the oldest tag while it is waiting for its word; every
      * other tag is owned by a record, or by nobody.
      */
    val tags = new Area {
      val alive = Vec.fill(InFlight)(Reg(Bool()) init False)
      val valid = Vec.fill(InFlight)(Reg(Bool()) init False)
      val count = Reg(UInt(log2Up(InFlight + 1) bits)) init 0
      val full = count === InFlight

      val push = Bool()
      val pushAlive = Bool()
      val pop = bus.rvalid
      val killHead = Bool()
      val killAll = Bool()
      val keepHead = Bool()

      // The head is entry zero; a pop shifts everything down. A kill applies
      // to where an entry is now, and the shift then moves it.
      def killedAt(j: Int): Bool =
        (killHead && Bool(j == 0)) || (killAll && !(keepHead && Bool(j == 0)))
      def aliveAt(j: Int): Bool = if (j < InFlight) valid(j) && alive(j) && !killedAt(j) else False
      def validAt(j: Int): Bool = if (j < InFlight) valid(j) else False
      // Whether the answer arriving now is wanted is read from the registers
      // alone, which keeps decode's readiness out of whether its word is
      // present. A kill this cycle is then applied to the word count instead.
      val headAlive = valid(0) && alive(0)

      val slot = Mux(pop, count - 1, count)
      for (i <- 0 until InFlight) {
        val pushHere = push && slot === i
        alive(i) := Mux(pushHere, pushAlive, Mux(pop, aliveAt(i + 1), aliveAt(i)))
        valid(i) := Mux(pushHere, True, Mux(pop, validAt(i + 1), validAt(i)))
      }
      count := count + push.asUInt - pop.asUInt
    }

    /** Words that have arrived and not been taken, in order.
      *
      * Every command out is a word owed, and every word stored is a word not
      * yet taken, so the queue is sized for both together: one deeper than
      * the commands, because with a memory answering next cycle a word
      * arrives while its record is still in fetch and is stored for one
      * cycle while the next command is already out.
      */
    val words = new Area {
      val Depth = InFlight + 1
      // A shift queue, not a ring: the head is a register, not a register
      // behind a pointer and a multiplexer. Decode folds branches from this
      // word, and that is the longest path in the core; a ring here put a
      // pointer's fanout and a four to one multiplexer in front of it and
      // cost three nanoseconds.
      val data = Vec.fill(Depth)(Reg(Bits(Isa.INSTR_BITS bits)) init 0)
      val count = Reg(UInt(log2Up(Depth + 1) bits)) init 0
      val nonEmpty = count =/= 0
      val owed = count +^ tags.count
      val full = owed >= Depth

      // An answer that arrives to a live tag is a word; one arriving to a dead
      // tag is dropped here and never enters the queue.
      val arriving = bus.rvalid && tags.headAlive
      // Arriving and stored are both acceptable, which is what keeps a
      // one-cycle memory at one cycle.
      val present = nonEmpty || arriving
      val word = Mux(nonEmpty, data(0), bus.data)

      val leaving = decodeNode.up.isMoving
      val taken = leaving && present

      // A word taken straight off the bus never enters the queue, and taking
      // it that way does not shorten the queue either: only a word taken out
      // of the queue does. Getting that wrong once made the count wrap and
      // the queue serve zeros for ever.
      val dequeue = taken && nonEmpty
      val enqueue = arriving && !(taken && !nonEmpty)
      val afterTake = count - dequeue.asUInt
      for (i <- 0 until Depth) {
        val shifted = if (i + 1 < Depth) data(i + 1) else B(0, Isa.INSTR_BITS bits)
        val kept = Mux(dequeue, shifted, data(i))
        val appendHere = enqueue && afterTake === i
        data(i) := Mux(appendHere, bus.data, kept)
      }
      val after = afterTake + enqueue.asUInt
      count := after
    }

    decodeNode.up(Global.INSTRUCTION) := words.word
    // A transaction being thrown is not waiting for anything, and holding
    // the stage for its word would hold the instruction the redirect wants
    // out of the stage for that cycle: a halt says no to the stage in front
    // as well.
    decodeNode.haltWhen(!words.present && !decodeNode.up.isCancel)
    present.load(words.present)

    // Counted only when decode could otherwise have moved, so that a cycle
    // lost to something deeper in the pipeline is charged to that instead of
    // being counted twice.
    perfFetch := !words.present && decodeNode.down.isReady

    /** The command side. */
    val command = new Area {
      val offer = pcs.offer
      bus.address := offer.pc

      // Room means a tag for the command, a record for its address and a
      // slot for its word when it comes, counting the words still owed. Each
      // queue is read from its registers, so it is a cycle behind, and
      // covering a two-cycle memory takes a third command out.
      val room = !tags.full && !words.full && !records.full
      bus.enable := room
      val accepted = bus.enable && bus.ready
      pcs.accepted.load(accepted)

      // Read only after the accept is loaded: the counter waits on that.
      val redirected = pcs.redirectNow

      // A command accepted on the very cycle the counter moves is for the old
      // counter: it goes out dead. Its record is pushed and flushed in the
      // same cycle, or offered to the stage under the old generation and
      // thrown by decode's generation compare, rather than gated here: the
      // redirect is the end of the longest path in the core, and the record
      // queue's enables are a hundred and thirty flip-flops wide.
      tags.push := accepted
      tags.pushAlive := !redirected
      records.pushed.pc := offer.pc
      records.pushed.gen := offer.gen
      records.pushed.next := offer.next
      records.flush := redirected

      // The transaction in decode leaving without its word abandons the
      // oldest tag. A redirect abandons every tag but that one, and that one
      // only while the instruction in decode stays and its word is still on
      // its way: if decode holds a word, the oldest tag belongs to a record.
      val decodeStays = decodeNode.up.isValid && !decodeNode.up.isMoving
      tags.killHead := words.leaving && !words.present && decodeNode.up.isValid
      tags.killAll := redirected
      tags.keepHead := decodeStays && !words.nonEmpty

      // The word queue keeps at most decode's own word through a redirect. A
      // word arriving this cycle for a tag killed this cycle went in above,
      // because the tag's liveness is read from its registers, and is taken
      // back out here.
      // Keeping decode's word means keeping the head: decode is staying, so
      // nothing is taken this cycle, and a word arriving now for it goes in
      // at the front of an empty queue.
      val keepWord = decodeStays && (words.nonEmpty || words.arriving)
      when(redirected) {
        words.count := keepWord.asUInt.resized
      }
    }

    /** The fetch stage is the head of the address queue.
      *
      * An address accepted while the queue is empty is offered to the stage
      * in the same cycle, and is queued only if the stage cannot take it.
      * That is the cycle after a redirect, and without the pass-through
      * every branch would cost one more.
      */
    val stage = new Area {
      val queued = records.nonEmpty
      val direct = command.accepted && !queued
      fetchNode.up.valid := queued || direct
      fetchNode.up(Global.PC) := Mux(queued, records.first.pc, command.offer.pc)
      fetchNode.up(Global.FETCH_GEN) := Mux(queued, records.first.gen, command.offer.gen)
      fetchNode.up(Global.PREDICTED_NEXT) := Mux(queued, records.first.next, command.offer.next)
      // Nothing throws the fetch stage: a record that left without becoming a
      // transaction would leave its word to the next one.
      val firing = fetchNode.up.isFiring
      records.pop := firing && queued
      records.push := command.accepted && !(direct && firing)
    }

  }
}

/** A queue in issue order, as a ring: an entry is written once, when it is
  * pushed, and the head is chosen by a pointer, so nothing shifts. A shifting
  * queue of three addresses cost a multiplexer per bit per entry; this costs
  * a clock enable, which the flip-flop has anyway, and one multiplexer at the
  * head.
  */
class FetchRing[T <: Data](depth: Int, payload: HardType[T]) extends Area {
  val entries = Vec.fill(depth)(Reg(payload()))
  val head = Reg(UInt(log2Up(depth) bits)) init 0
  val tail = Reg(UInt(log2Up(depth) bits)) init 0
  val count = Reg(UInt(log2Up(depth + 1) bits)) init 0
  val nonEmpty = count =/= 0
  val full = count === depth
  val first = entries(head)

  val push = Bool()
  val pushed = payload()
  val pop = Bool()
  val flush = Bool()

  def after(pointer: UInt): UInt =
    if (isPow2(depth)) pointer + 1 else Mux(pointer === depth - 1, U(0, pointer.getWidth bits), pointer + 1)

  // A push and a flush in the same cycle write an entry nobody will read:
  // the flush comes last and wins the pointers.
  when(push) {
    entries(tail) := pushed
    tail := after(tail)
  }
  when(pop) { head := after(head) }
  count := count + push.asUInt - pop.asUInt
  when(flush) {
    head := 0
    tail := 0
    count := 0
  }
}

/** An address the counter has been asked for: what a fetch transaction is
  * made of once it enters the pipeline.
  */
case class FetchRecord() extends Bundle {
  val pc = UInt(AxiomParam.PC_WIDTH bits)
  val gen = UInt(2 bits)
  val next = UInt(AxiomParam.PC_WIDTH bits)
}

object FetchPlugin {

  /** How many commands may be out at once.
    *
    * Two are enough to cover a memory that answers in two cycles at one
    * instruction a cycle; the third is what lets the room check read the
    * queues from their registers rather than from this cycle's answer.
    */
  val InFlight = 3
}
