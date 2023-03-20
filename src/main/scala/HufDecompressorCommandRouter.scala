package compressacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants

class HufDecompressorCommandRouterIO()(implicit val p: Parameters) 
  extends Bundle {
  val rocc_in = Flipped(Decoupled(new RoCCCommand))
  val rocc_out = Decoupled(new RoCCResponse)

  val src_info = Decoupled(new StreamInfo)
  val dst_info = Decoupled(new DecompressDstInfo)
// val dic_info = Decoupled(new StreamInfo)

  val no_memops_inflight = Input(Bool())
  val write_complete_stream_cnt = Input(UInt(64.W))

  val dmem_status_out = Valid(new RoCCCommand)
  val sfence_out = Output(Bool())
}

class HufDecompressorCommandRouter(val cmd_que_depth: Int)(implicit p: Parameters) 
  extends Module {

  val io = IO(new HufDecompressorCommandRouterIO)

  val FUNCT_SFENCE           = 0.U
  val FUNCT_SRC_INFO         = 1.U
  val FUNCT_DST_INFO         = 2.U
  val FUNCT_CHECK_COMPLETION = 3.U

  val dispatched_src_info = RegInit(0.U(64.W))

  val cur_funct = io.rocc_in.bits.inst.funct
  val cur_rs1 = io.rocc_in.bits.rs1
  val cur_rs2 = io.rocc_in.bits.rs2

  val sfence_fire = DecoupledHelper(io.rocc_in.valid,
                                    cur_funct === FUNCT_SFENCE)
  io.sfence_out := sfence_fire.fire
  io.dmem_status_out.bits <> io.rocc_in.bits
  io.dmem_status_out.valid <> io.rocc_in.fire

  when (io.rocc_in.fire) {
    CompressAccelLogger.logInfo("rocc_in_data, opcode: %d, rs1: 0x%x, rs2: 0x%x\n",
      cur_funct, cur_rs1, cur_rs2)
  }

  when (io.rocc_in.fire) {
    when (cur_funct === FUNCT_SRC_INFO) {
      val nxt_dispatched_src_info = dispatched_src_info + 1.U
      dispatched_src_info := nxt_dispatched_src_info

      CompressAccelLogger.logInfo("CommandRouter, dispatched src cnt: %d\n", 
        nxt_dispatched_src_info)
    }
  }

  // Compressed src info
  val src_info_queue = Module(new Queue(new StreamInfo, cmd_que_depth))
  val src_info_fire = DecoupledHelper(io.rocc_in.valid, 
                                      src_info_queue.io.enq.ready,
                                      cur_funct === FUNCT_SRC_INFO) 
  src_info_queue.io.enq.bits.ip := cur_rs1
  src_info_queue.io.enq.bits.isize := cur_rs2
  src_info_queue.io.enq.valid := src_info_fire.fire(src_info_queue.io.enq.ready)
  io.src_info <> src_info_queue.io.deq

  when (io.src_info.fire) {
    CompressAccelLogger.logInfo("CommandRouter, io.src_info.fire!\n")
    CompressAccelLogger.logInfo("CommandRouter, io.src_info.ip: 0x%x\n", io.src_info.bits.ip)
    CompressAccelLogger.logInfo("CommandRouter, io.src_info.isize: %d\n", io.src_info.bits.isize)
  }

  // Huf decompressed destination info
  val dst_info_queue = Module(new Queue(new DecompressDstInfo, cmd_que_depth))
  val dst_info_fire = DecoupledHelper(io.rocc_in.valid,
                                      dst_info_queue.io.enq.ready,
                                      cur_funct === FUNCT_DST_INFO);
  dst_info_queue.io.enq.bits.op := cur_rs1
  dst_info_queue.io.enq.bits.cmpflag := cur_rs2
  dst_info_queue.io.enq.valid := dst_info_fire.fire(dst_info_queue.io.enq.ready)
  io.dst_info <> dst_info_queue.io.deq

  when (io.dst_info.fire) {
    CompressAccelLogger.logInfo("CommandRouter, io.dst_info.fire!\n")
    CompressAccelLogger.logInfo("CommandRouter, op: 0x%x\n", io.dst_info.bits.op)
    CompressAccelLogger.logInfo("CommandRouter, cmpflag: %d\n", io.dst_info.bits.cmpflag)
  }

  val do_check_completion_fire = DecoupledHelper(
    cur_funct === FUNCT_CHECK_COMPLETION,
    io.no_memops_inflight,
    dispatched_src_info === io.write_complete_stream_cnt,
    io.rocc_in.valid
  )

  when (do_check_completion_fire.fire) {
    CompressAccelLogger.logInfo("CommandRouter, do_check_completion_fire.fire\n")
    CompressAccelLogger.logInfo("CommandRouter, dispatched_src_info: %d\n", dispatched_src_info)
  }

  io.rocc_in.ready := sfence_fire.fire(io.rocc_in.valid) ||
                      src_info_fire.fire(io.rocc_in.valid) ||
                      dst_info_fire.fire(io.rocc_in.valid) ||
                      do_check_completion_fire.fire(io.rocc_in.valid)

  io.rocc_out.valid := do_check_completion_fire.fire
  io.rocc_out.bits.rd := io.rocc_in.bits.inst.rd
  io.rocc_out.bits.data := dispatched_src_info
}
