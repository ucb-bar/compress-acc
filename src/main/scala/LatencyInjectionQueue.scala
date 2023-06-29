package compressacc

import chisel3._
import chisel3.util._
import freechips.rocketchip.util.DecoupledHelper


class LatencyInjectionQueue[T <: Data](data: T, depth: Int) extends Module {
  val io = IO(new Bundle {
    val latency_cycles = Input(UInt(64.W))
    val enq = Flipped(Decoupled(data))
    val deq = Decoupled(data)
  })

  val cur_cycle = RegInit(0.U(64.W))
  cur_cycle := cur_cycle + 1.U
  val queue = Module(new Queue(data, depth))
  val release_ready_cycle_q = Module(new Queue(UInt(64.W), depth))

  release_ready_cycle_q.io.enq.bits := cur_cycle + io.latency_cycles
  queue.io.enq.bits := io.enq.bits
  io.deq.bits := queue.io.deq.bits

  val enq_fire = DecoupledHelper(
    queue.io.enq.ready,
    release_ready_cycle_q.io.enq.ready,
    io.enq.valid
  )

  queue.io.enq.valid := enq_fire.fire(queue.io.enq.ready)
  release_ready_cycle_q.io.enq.valid := enq_fire.fire(release_ready_cycle_q.io.enq.ready)
  io.enq.ready := enq_fire.fire(io.enq.valid)

  val deq_fire = DecoupledHelper(
    queue.io.deq.valid,
    release_ready_cycle_q.io.deq.valid,
    release_ready_cycle_q.io.deq.bits <= cur_cycle,
    io.deq.ready
  )

  queue.io.deq.ready := deq_fire.fire(queue.io.deq.valid)
  release_ready_cycle_q.io.deq.ready := deq_fire.fire(release_ready_cycle_q.io.deq.valid)
  io.deq.valid := deq_fire.fire(io.deq.ready)
}
