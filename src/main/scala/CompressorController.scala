package compressacc

import chisel3._
import chisel3.util._
import chisel3.{Printable}
import org.chipsalliance.cde.config._
import freechips.rocketchip.util.DecoupledHelper
import ZstdConsts._
import CompressorConsts._


class SnappyCompressorControllerIO extends Bundle {
  val src_info  = Flipped(Decoupled(new StreamInfo))
  val dst_info  = Flipped(Decoupled(new DstInfo))
  val finished_cnt = Decoupled(UInt(64.W))

  val shared_control = new SharedControlIO
}

class SnappyCompressorController(implicit p: Parameters) extends Module {
  val io = IO(new SnappyCompressorControllerIO)

  val track_buf_cnt = RegInit(0.U(64.W))


  val src_info_q = Module(new Queue(new StreamInfo, 2))
  val src_info2_q = Module(new Queue(new StreamInfo, 2))

  val src_info_fire = DecoupledHelper(
    io.src_info.valid,
    src_info_q.io.enq.ready,
    src_info2_q.io.enq.ready)

  src_info_q.io.enq.bits := io.src_info.bits
  src_info2_q.io.enq.bits := io.src_info.bits

  io.shared_control.mf_src.compress_src_info <> src_info_q.io.deq
  io.shared_control.mf_src.compress_src_info2 <> src_info2_q.io.deq

  io.src_info.ready := src_info_fire.fire(io.src_info.valid)
  src_info_q.io.enq.valid := src_info_fire.fire(src_info_q.io.enq.ready)
  src_info2_q.io.enq.valid := src_info_fire.fire(src_info2_q.io.enq.ready)

  when (src_info_fire.fire) {
    track_buf_cnt := track_buf_cnt + 1.U
  }

  when (io.src_info.valid) {
    CompressAccelLogger.logInfo("SnappyCompressorController io.src_info.valid, io.shared_control.mf_src.compress_src_info.ready: %d, %d\n", 
      io.shared_control.mf_src.compress_src_info.ready,
      io.shared_control.mf_src.compress_src_info2.ready)
  }

  io.shared_control.mf_dst.seq_dst_info <> io.dst_info

  io.shared_control.mf_dst.lit_dst_info.valid := false.B
  io.shared_control.mf_dst.lit_dst_info.bits.op := 0.U
  io.shared_control.mf_dst.lit_dst_info.bits.cmpflag := 0.U

  io.shared_control.mf_maxoffset := 0.U // connect these from the upper level
  io.shared_control.mf_runtime_ht_num_entries_log2 := 0.U

  io.shared_control.mf_buff_consumed.lit_consumed_bytes.ready := true.B

  val finished_cnt_fire = DecoupledHelper(
    io.shared_control.mf_buff_consumed.seq_consumed_bytes.valid,
    io.finished_cnt.ready)

  io.shared_control.mf_buff_consumed.seq_consumed_bytes.ready := finished_cnt_fire.fire(io.shared_control.mf_buff_consumed.seq_consumed_bytes.valid)
  io.finished_cnt.valid := finished_cnt_fire.fire(io.finished_cnt.ready)
  io.finished_cnt.bits := track_buf_cnt
}



class CompressorControllerIO(implicit p: Parameters) extends Bundle {
  val ALGORITHM = Input(UInt(1.W))
  val SNAPPY_MAX_OFFSET_ALLOWED = Input(UInt(64.W))
  val SNAPPY_RUNTIME_HT_NUM_ENTRIES_LOG2 = Input(UInt(5.W))

  val src_info  = Flipped(Decoupled(new StreamInfo))
  val dst_info  = Flipped(Decoupled(new DstInfo))
  val buff_info = Flipped(new ZstdBuffInfo)
  val clevel_info = Flipped(Decoupled(UInt(5.W)))

  val zstd_control = new ZstdControlIO
  val shared_control = new SharedControlIO

  val zstd_finished_cnt = Decoupled(UInt(64.W))
  val snappy_finished_cnt = Decoupled(UInt(64.W))
}

class CompressorController(implicit p: Parameters) extends Module {
  val io = IO(new CompressorControllerIO)

  val zstd_controller = Module(new ZstdCompressorFrameController)
  io.zstd_control <> zstd_controller.io.zstd_control
  zstd_controller.io.src_info.bits := io.src_info.bits
  zstd_controller.io.dst_info.bits := io.dst_info.bits
  zstd_controller.io.buff_info <> io.buff_info
  zstd_controller.io.clevel_info <> io.clevel_info

  val zstd_src_valid = DecoupledHelper(
    zstd_controller.io.src_info.ready,
    io.src_info.valid,
    io.ALGORITHM === ZSTD.U)

  val zstd_dst_valid = DecoupledHelper(
    zstd_controller.io.dst_info.ready,
    io.dst_info.valid,
    io.ALGORITHM === ZSTD.U)

  zstd_controller.io.src_info.valid := zstd_src_valid.fire(zstd_controller.io.src_info.ready)
  zstd_controller.io.dst_info.valid := zstd_dst_valid.fire(zstd_controller.io.dst_info.ready)


  val snappy_controller = Module(new SnappyCompressorController)
  snappy_controller.io.src_info.bits := io.src_info.bits
  snappy_controller.io.dst_info.bits := io.dst_info.bits

  val snappy_src_valid = DecoupledHelper(
    snappy_controller.io.src_info.ready,
    io.src_info.valid,
    io.ALGORITHM === Snappy.U)

  val snappy_dst_valid = DecoupledHelper(
    snappy_controller.io.dst_info.ready,
    io.dst_info.valid,
    io.ALGORITHM === Snappy.U)

  snappy_controller.io.src_info.valid := snappy_src_valid.fire(snappy_controller.io.src_info.ready)
  snappy_controller.io.dst_info.valid := snappy_dst_valid.fire(snappy_controller.io.dst_info.ready)

  io.src_info.ready := zstd_src_valid.fire(io.src_info.valid) ||
                       snappy_src_valid.fire(io.src_info.valid)

  io.dst_info.ready := zstd_dst_valid.fire(io.dst_info.valid) ||
                       snappy_dst_valid.fire(io.dst_info.valid)

  val use_zstd = io.ALGORITHM === ZSTD.U
  when (io.src_info.fire) {
    CompressAccelLogger.logInfo("CommonCompressorControl io.src_info.fire use_zstd: %d\n", use_zstd)
  }

  when (io.dst_info.fire) {
    CompressAccelLogger.logInfo("CommonCompressorControl io.dst_info.fire use_zstd: %d\n", use_zstd)
  }

  val zstd_shared_control = zstd_controller.io.shared_control
  val snappy_shared_control = snappy_controller.io.shared_control

  io.shared_control.mf_src.compress_src_info.valid := (use_zstd && zstd_shared_control.mf_src.compress_src_info.valid) || 
                                                      (!use_zstd && snappy_shared_control.mf_src.compress_src_info.valid)
  zstd_shared_control.mf_src.compress_src_info.ready := (use_zstd && io.shared_control.mf_src.compress_src_info.ready)
  snappy_shared_control.mf_src.compress_src_info.ready := (!use_zstd && io.shared_control.mf_src.compress_src_info.ready)

  io.shared_control.mf_src.compress_src_info.bits := Mux(use_zstd,
                                                         zstd_shared_control.mf_src.compress_src_info.bits,
                                                         snappy_shared_control.mf_src.compress_src_info.bits)

  io.shared_control.mf_src.compress_src_info2.valid := (use_zstd && zstd_shared_control.mf_src.compress_src_info2.valid) ||
                                                       (!use_zstd && snappy_shared_control.mf_src.compress_src_info2.valid)
  zstd_shared_control.mf_src.compress_src_info2.ready := (use_zstd && io.shared_control.mf_src.compress_src_info2.ready)
  snappy_shared_control.mf_src.compress_src_info2.ready := (!use_zstd && io.shared_control.mf_src.compress_src_info2.ready)

  io.shared_control.mf_src.compress_src_info2.bits := Mux(use_zstd,
                                                          zstd_shared_control.mf_src.compress_src_info2.bits,
                                                          snappy_shared_control.mf_src.compress_src_info2.bits)

  io.shared_control.mf_dst.lit_dst_info.valid := (use_zstd && zstd_shared_control.mf_dst.lit_dst_info.valid) ||
                                                 (!use_zstd && snappy_shared_control.mf_dst.lit_dst_info.valid)
  zstd_shared_control.mf_dst.lit_dst_info.ready := (use_zstd && io.shared_control.mf_dst.lit_dst_info.ready)
  snappy_shared_control.mf_dst.lit_dst_info.ready := (!use_zstd && io.shared_control.mf_dst.lit_dst_info.ready)

  io.shared_control.mf_dst.lit_dst_info.bits := Mux(use_zstd,
    zstd_shared_control.mf_dst.lit_dst_info.bits,
    snappy_shared_control.mf_dst.lit_dst_info.bits)

  io.shared_control.mf_dst.seq_dst_info.valid := (use_zstd && zstd_shared_control.mf_dst.seq_dst_info.valid) ||
                                                 (!use_zstd && snappy_shared_control.mf_dst.seq_dst_info.valid)
  zstd_shared_control.mf_dst.seq_dst_info.ready := (use_zstd && io.shared_control.mf_dst.seq_dst_info.ready)
  snappy_shared_control.mf_dst.seq_dst_info.ready := (!use_zstd && io.shared_control.mf_dst.seq_dst_info.ready)

  io.shared_control.mf_dst.seq_dst_info.bits := Mux(use_zstd,
    zstd_shared_control.mf_dst.seq_dst_info.bits,
    snappy_shared_control.mf_dst.seq_dst_info.bits)

  io.shared_control.mf_maxoffset := Mux(use_zstd,
    zstd_shared_control.mf_maxoffset,
    io.SNAPPY_MAX_OFFSET_ALLOWED)

  io.shared_control.mf_runtime_ht_num_entries_log2 := Mux(use_zstd,
    zstd_shared_control.mf_runtime_ht_num_entries_log2,
    io.SNAPPY_RUNTIME_HT_NUM_ENTRIES_LOG2)

  zstd_shared_control.mf_buff_consumed.seq_consumed_bytes.bits := io.shared_control.mf_buff_consumed.seq_consumed_bytes.bits
  snappy_shared_control.mf_buff_consumed.seq_consumed_bytes.bits := io.shared_control.mf_buff_consumed.seq_consumed_bytes.bits

  io.shared_control.mf_buff_consumed.seq_consumed_bytes.ready := (use_zstd && zstd_shared_control.mf_buff_consumed.seq_consumed_bytes.ready) ||
                                                                 (!use_zstd && snappy_shared_control.mf_buff_consumed.seq_consumed_bytes.ready)
  zstd_shared_control.mf_buff_consumed.seq_consumed_bytes.valid := (use_zstd && io.shared_control.mf_buff_consumed.seq_consumed_bytes.valid)
  snappy_shared_control.mf_buff_consumed.seq_consumed_bytes.valid := (!use_zstd && io.shared_control.mf_buff_consumed.seq_consumed_bytes.valid)

  zstd_shared_control.mf_buff_consumed.lit_consumed_bytes.bits := io.shared_control.mf_buff_consumed.lit_consumed_bytes.bits
  snappy_shared_control.mf_buff_consumed.lit_consumed_bytes.bits := io.shared_control.mf_buff_consumed.lit_consumed_bytes.bits

  io.shared_control.mf_buff_consumed.lit_consumed_bytes.ready := (use_zstd && zstd_shared_control.mf_buff_consumed.lit_consumed_bytes.ready) ||
                                                                 (!use_zstd && snappy_shared_control.mf_buff_consumed.lit_consumed_bytes.ready)
  zstd_shared_control.mf_buff_consumed.lit_consumed_bytes.valid := (use_zstd && io.shared_control.mf_buff_consumed.lit_consumed_bytes.valid)
  snappy_shared_control.mf_buff_consumed.lit_consumed_bytes.valid := (!use_zstd && io.shared_control.mf_buff_consumed.lit_consumed_bytes.valid)

  io.zstd_finished_cnt <> zstd_controller.io.finished_cnt
  io.snappy_finished_cnt <> snappy_controller.io.finished_cnt
}
