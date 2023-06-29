package compressacc

import chisel3._
import chisel3.util._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import CompressorConsts._

class ZstdBuffInfo extends Bundle {
  val lit = Decoupled(new StreamInfo)
  val seq = Decoupled(new StreamInfo)
}

class ZstdCompressorCommandRouterIO()(implicit val p: Parameters) 
  extends Bundle {
  val rocc_in = Flipped(Decoupled(new RoCCCommand))
  val rocc_out = Decoupled(new RoCCResponse)

  val dmem_status_out = Valid(new RoCCCommand)
  val sfence_out = Output(Bool())

// val no_memops_inflight = Input(Bool())
// val finished_src_info = Input(UInt(64.W))

  val ALGORITHM = Output(UInt(1.W))

  val src_info = Decoupled(new StreamInfo)
  val dst_info = Decoupled(new DstInfo)
  val buff_info = new ZstdBuffInfo
  val clevel_info = Decoupled(UInt(5.W))

  val SNAPPY_MAX_OFFSET_ALLOWED = Output(UInt(64.W))
  val SNAPPY_RUNTIME_HT_NUM_ENTRIES_LOG2 = Output(UInt(5.W))

  val zstd_finished_cnt = Flipped(Decoupled(UInt(64.W)))
  val snappy_finished_cnt = Flipped(Decoupled(UInt(64.W)))
}

class ZstdCompressorCommandRouter(implicit p: Parameters) 
  extends ZstdCompressorModule {
  val io = IO(new ZstdCompressorCommandRouterIO)

  val FUNCT_SFENCE                           = 0.U
  val FUNCT_ZSTD_SRC_INFO                    = 1.U
  val FUNCT_ZSTD_LIT_BUFF_INFO               = 2.U
  val FUNCT_ZSTD_SEQ_BUFF_INFO               = 3.U
  val FUNCT_ZSTD_DST_INFO                    = 4.U
  val FUNCT_ZSTD_COMPRESSION_LEVEL           = 5.U
  val FUNCT_SNPY_SRC_INFO                    = 6.U
  val FUNCT_SNPY_DST_INFO                    = 7.U
  val FUNCT_SNPY_MAX_OFFSET_ALLOWED          = 8.U
  val FUNCT_SNPY_RUNTIME_HT_NUM_ENTRIES_LOG2 = 9.U
  val FUNCT_CHECK_COMPLETION                 = 10.U

  val snappy_dispatched_src_info = RegInit(0.U(64.W))
  val zstd_dispatched_src_info = RegInit(0.U(64.W))

  val cur_funct = io.rocc_in.bits.inst.funct
  val cur_rs1 = io.rocc_in.bits.rs1
  val cur_rs2 = io.rocc_in.bits.rs2

  val sfence_fire = DecoupledHelper(io.rocc_in.valid,
                                    cur_funct === FUNCT_SFENCE)
  io.sfence_out := sfence_fire.fire
  io.dmem_status_out.bits <> io.rocc_in.bits
  io.dmem_status_out.valid <> io.rocc_in.fire

  when (io.rocc_in.fire) {
    CompressAccelLogger.logInfo("rocc_in_data, opcode: %d, rs1: 0x%x, rs2: 0x%x\n", cur_funct, cur_rs1, cur_rs2)
  }

  val ALGORITHM = RegInit(ZSTD.U(1.W))
  io.ALGORITHM := ALGORITHM

  val SNAPPY_MAX_OFFSET_ALLOWED = RegInit(((64 * 1024) - 64).U(64.W))
  io.SNAPPY_MAX_OFFSET_ALLOWED := SNAPPY_MAX_OFFSET_ALLOWED

  val max_offset_allowed_fire = DecoupledHelper(
    io.rocc_in.valid,
    cur_funct === FUNCT_SNPY_MAX_OFFSET_ALLOWED
  )

  when (max_offset_allowed_fire.fire) {
    SNAPPY_MAX_OFFSET_ALLOWED := cur_rs1
  }

  val SNAPPY_RUNTIME_HT_NUM_ENTRIES_LOG2 = RegInit(14.U(5.W))
  io.SNAPPY_RUNTIME_HT_NUM_ENTRIES_LOG2 := SNAPPY_RUNTIME_HT_NUM_ENTRIES_LOG2

  val runtime_ht_num_entries_fire = DecoupledHelper(
    io.rocc_in.valid,
    cur_funct === FUNCT_SNPY_RUNTIME_HT_NUM_ENTRIES_LOG2
  )

  when (runtime_ht_num_entries_fire.fire) {
    SNAPPY_RUNTIME_HT_NUM_ENTRIES_LOG2 := io.rocc_in.bits.rs1
  }

  when (io.rocc_in.fire && (cur_funct === FUNCT_ZSTD_SRC_INFO)) {
    val nxt_dispatched_src_info = zstd_dispatched_src_info + 1.U
    zstd_dispatched_src_info := nxt_dispatched_src_info
    CompressAccelLogger.logInfo("CommandRouter, zstd dispatched src cnt: %d\n", nxt_dispatched_src_info)
  }
  
  when (io.rocc_in.fire && (cur_funct === FUNCT_SNPY_SRC_INFO)) {
    val nxt_dispatched_src_info = snappy_dispatched_src_info + 1.U
    snappy_dispatched_src_info := nxt_dispatched_src_info
    CompressAccelLogger.logInfo("CommandRouter, snappy dispatched src cnt: %d\n", nxt_dispatched_src_info)
  }

  val src_info_queue = Module(new Queue(new StreamInfo, queDepth))
  val src_info_fire = DecoupledHelper(io.rocc_in.valid,
                                      src_info_queue.io.enq.ready,
                                      (cur_funct === FUNCT_ZSTD_SRC_INFO) || (cur_funct === FUNCT_SNPY_SRC_INFO))
  src_info_queue.io.enq.bits.ip := cur_rs1
  src_info_queue.io.enq.bits.isize := cur_rs2
  src_info_queue.io.enq.valid := src_info_fire.fire(src_info_queue.io.enq.ready)
  io.src_info <> src_info_queue.io.deq

  when (src_info_fire.fire) {
    when (cur_funct === FUNCT_ZSTD_SRC_INFO) {
      ALGORITHM := ZSTD.U
    } .otherwise {
      ALGORITHM := Snappy.U
    }
  }

  when (io.src_info.fire) {
    CompressAccelLogger.logInfo("CommandRouter, io.src_info.fire!\n")
    CompressAccelLogger.logInfo("CommandRouter, io.src_info.ptr: 0x%x\n", io.src_info.bits.ip)
    CompressAccelLogger.logInfo("CommandRouter, io.src_info.size: %d\n", io.src_info.bits.isize)
  }

  val lit_buff_info_queue = Module(new Queue(new StreamInfo, queDepth))
  val lit_buff_info_fire = DecoupledHelper(lit_buff_info_queue.io.enq.ready,
                                           io.rocc_in.valid,
                                           cur_funct === FUNCT_ZSTD_LIT_BUFF_INFO)
  lit_buff_info_queue.io.enq.valid := lit_buff_info_fire.fire(lit_buff_info_queue.io.enq.ready)
  lit_buff_info_queue.io.enq.bits.ip := cur_rs1
  lit_buff_info_queue.io.enq.bits.isize := cur_rs2
  io.buff_info.lit <> lit_buff_info_queue.io.deq

  when (io.buff_info.lit.fire) {
    CompressAccelLogger.logInfo("CommandRouter, io.buff_info.lit.fire!\n")
    CompressAccelLogger.logInfo("CommandRouter, io.buff_info.lit.ip: 0x%x\n", io.buff_info.lit.bits.ip)
    CompressAccelLogger.logInfo("CommandRouter, io.buff_info.lit.isize: %d\n", io.buff_info.lit.bits.isize)
  }

  val seq_buff_info_queue = Module(new Queue(new StreamInfo, queDepth))
  val seq_buff_info_fire = DecoupledHelper(seq_buff_info_queue.io.enq.ready,
                                           io.rocc_in.valid,
                                           cur_funct === FUNCT_ZSTD_SEQ_BUFF_INFO)
  seq_buff_info_queue.io.enq.valid := seq_buff_info_fire.fire(seq_buff_info_queue.io.enq.ready)
  seq_buff_info_queue.io.enq.bits.ip := cur_rs1
  seq_buff_info_queue.io.enq.bits.isize := cur_rs2
  io.buff_info.seq <> seq_buff_info_queue.io.deq

  when (io.buff_info.seq.fire) {
    CompressAccelLogger.logInfo("CommandRouter, io.buff_info.seq.fire!\n")
    CompressAccelLogger.logInfo("CommandRouter, io.buff_info.seq.ip: 0x%x\n", io.buff_info.seq.bits.ip)
    CompressAccelLogger.logInfo("CommandRouter, io.buff_info.seq.isize: %d\n", io.buff_info.seq.bits.isize)
  }

  val dst_info_queue = Module(new Queue(new DstInfo, queDepth))
  val dst_info_fire = DecoupledHelper(io.rocc_in.valid,
                                      dst_info_queue.io.enq.ready,
                                      (cur_funct === FUNCT_ZSTD_DST_INFO) || (cur_funct === FUNCT_SNPY_DST_INFO))
  dst_info_queue.io.enq.bits.op := cur_rs1
  dst_info_queue.io.enq.bits.cmpflag := cur_rs2
  dst_info_queue.io.enq.valid := dst_info_fire.fire(dst_info_queue.io.enq.ready)
  io.dst_info <> dst_info_queue.io.deq

  when (io.dst_info.fire) {
    CompressAccelLogger.logInfo("CommandRouter, io.dst_info.fire!\n")
    CompressAccelLogger.logInfo("CommandRouter, io.dst_info.op: 0x%x\n", io.dst_info.bits.op)
    CompressAccelLogger.logInfo("CommandRouter, io.dst_info.cmpflag: %d\n", io.dst_info.bits.cmpflag)
  }

  val clevel_info_queue = Module(new Queue(UInt(5.W), queDepth))
  val clevel_info_fire = DecoupledHelper(io.rocc_in.valid,
                                         clevel_info_queue.io.enq.ready,
                                         cur_funct === FUNCT_ZSTD_COMPRESSION_LEVEL)
  clevel_info_queue.io.enq.bits := cur_rs1
  clevel_info_queue.io.enq.valid := clevel_info_fire.fire(clevel_info_queue.io.enq.ready)
  io.clevel_info <> clevel_info_queue.io.deq

  when (io.clevel_info.fire) {
    CompressAccelLogger.logInfo("CommandRouter, io.clevel_info.fire!\n")
    CompressAccelLogger.logInfo("CommandRouter, io.clevel_info: %d\n", io.clevel_info.bits)
  }

  val zstd_finished_q = Module(new Queue(UInt(64.W), queDepth))
  zstd_finished_q.io.enq <> io.zstd_finished_cnt

  val snappy_finished_q = Module(new Queue(UInt(64.W), queDepth))
  snappy_finished_q.io.enq <> io.snappy_finished_cnt

  val zstd_done = zstd_finished_q.io.deq.valid && (zstd_dispatched_src_info === zstd_finished_q.io.deq.bits)
  val snappy_done = snappy_finished_q.io.deq.valid && (snappy_dispatched_src_info === snappy_finished_q.io.deq.bits)
  val compression_done = zstd_done || snappy_done
  val do_check_completion_fire = DecoupledHelper(
                                  cur_funct === FUNCT_CHECK_COMPLETION,
                                  io.rocc_in.valid,
                                  compression_done
                                  )

  zstd_finished_q.io.deq.ready := do_check_completion_fire.fire(compression_done)
  snappy_finished_q.io.deq.ready := do_check_completion_fire.fire(compression_done)

  when (do_check_completion_fire.fire) {
    CompressAccelLogger.logInfo("Zstd Compressor CommandRouter, do_check_completion_fire.fire\n")
    CompressAccelLogger.logInfo("Zstd Compressor CommandRouter, zstd_dispatched_src_info: %d\n", zstd_dispatched_src_info)
    CompressAccelLogger.logInfo("Snappy Compressor CommandRouter, Snappy_dispatched_src_info: %d\n", snappy_dispatched_src_info)
  }

  io.rocc_in.ready := sfence_fire.fire(io.rocc_in.valid) ||
                      runtime_ht_num_entries_fire.fire(io.rocc_in.valid) ||
                      max_offset_allowed_fire.fire(io.rocc_in.valid) ||
                      src_info_fire.fire(io.rocc_in.valid) ||
                      lit_buff_info_fire.fire(io.rocc_in.valid) ||
                      seq_buff_info_fire.fire(io.rocc_in.valid) ||
                      dst_info_fire.fire(io.rocc_in.valid) ||
                      clevel_info_fire.fire(io.rocc_in.valid) ||
                      do_check_completion_fire.fire(io.rocc_in.valid)

  io.rocc_out.valid := do_check_completion_fire.fire
  io.rocc_out.bits.rd := io.rocc_in.bits.inst.rd
  io.rocc_out.bits.data := Mux(ALGORITHM === ZSTD.U, zstd_dispatched_src_info, snappy_dispatched_src_info)
}
