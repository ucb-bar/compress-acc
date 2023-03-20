package compressacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants

class HufDecompressorRawCommandRouterIO()(implicit val p: Parameters) 
  extends Bundle {
  val src_info_in = Flipped(Decoupled(new DecompressPtrInfo))
  val dst_info_in = Flipped(Decoupled(new DecompressPtrInfo))


  val src_info = Decoupled(new StreamInfo)
  val dst_info = Decoupled(new DecompressDstInfo)

  val no_memops_inflight = Input(Bool())
  val write_complete_stream_cnt = Input(UInt(64.W))

  val finished = Decoupled(Bool())
}

class HufDecompressorRawCommandRouter(val cmd_que_depth: Int)(implicit p: Parameters) 
  extends Module {

  val io = IO(new HufDecompressorRawCommandRouterIO)

  val dispatched_src_info = RegInit(0.U(64.W))

  val src_info_q = Module(new Queue(new StreamInfo, 2)).io
  src_info_q.enq <> io.src_info_in
  src_info_q.enq.bits.isize := 0.U
  io.src_info <> src_info_q.deq

  when (src_info_q.deq.fire) {
    val nxt_dispatched_src_info = dispatched_src_info + 1.U
    dispatched_src_info := nxt_dispatched_src_info

    CompressAccelLogger.logInfo("CommandRouter, dispatched src cnt: %d\n", 
      nxt_dispatched_src_info)
  }


  val dst_info_q = Module(new Queue(new DecompressDstInfo, 2)).io
  io.dst_info_in.ready := dst_info_q.enq.ready
  dst_info_q.enq.valid := io.dst_info_in.valid
  dst_info_q.enq.bits.op := io.dst_info_in.bits.ip
  dst_info_q.enq.bits.cmpflag := 0.U
  io.dst_info <> dst_info_q.deq

  val src_fired = RegInit(false.B)
  val dst_fired = RegInit(false.B)

  when (src_info_q.deq.fire) {
    src_fired := true.B
  }

  when (dst_info_q.deq.fire) {
    dst_fired := true.B
  }

  val finished_q = Module(new Queue(Bool(), cmd_que_depth)).io
  io.finished <> finished_q.deq

  val do_check_completion_fire = DecoupledHelper(
    src_fired,
    dst_fired,
    finished_q.enq.ready,
    io.no_memops_inflight,
    dispatched_src_info === io.write_complete_stream_cnt
  )

  finished_q.enq.valid := do_check_completion_fire.fire(finished_q.enq.ready)
  finished_q.enq.bits := true.B

  when (do_check_completion_fire.fire) {
    src_fired := false.B
    dst_fired := false.B
    CompressAccelLogger.logInfo("Huffman CommandRouter, do_check_completion_fire.fire\n")
    CompressAccelLogger.logInfo("Huffman CommandRouter, dispatched_src_info: %d\n", dispatched_src_info)
  }


}
