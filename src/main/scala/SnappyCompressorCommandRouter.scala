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


class SnappyCompressDestInfo extends Bundle {
  val op = UInt(64.W)
  val boolptr = UInt(64.W)
}

class SnappyCompressorCommandRouter()(implicit p: Parameters) extends Module {
  val FUNCT_SFENCE = 0.U

  val FUNCT_SRC_INFO = 1.U
  val FUNCT_DEST_INFO_AND_START = 2.U
  val FUNCT_CHECK_COMPLETION = 3.U
  val FUNCT_MAX_OFFSET_ALLOWED = 4.U
  val FUNCT_RUNTIME_HT_NUM_ENTRIES_LOG2 = 5.U


  val io = IO(new Bundle{
    val rocc_in = Flipped(Decoupled(new RoCCCommand))
    val rocc_out = Decoupled(new RoCCResponse)

    val sfence_out = Output(Bool())
    val dmem_status_out = Valid(new RoCCCommand)

    val compress_src_info = Decoupled(new StreamInfo)
    val compress_src_info2 = Decoupled(new StreamInfo)

    val compress_dest_info = Decoupled(new SnappyCompressDestInfo)

    val bufs_completed = Input(UInt(64.W))
    val no_writes_inflight = Input(Bool())

    val MAX_OFFSET_ALLOWED = Output(UInt(64.W))
    val RUNTIME_HT_NUM_ENTRIES_LOG2 = Output(UInt(5.W))
  })

  val track_dispatched_src_infos = RegInit(0.U(64.W))
  when (io.rocc_in.fire) {
    when (io.rocc_in.bits.inst.funct === FUNCT_SRC_INFO) {
      val next_track_dispatched_src_infos = track_dispatched_src_infos + 1.U
      track_dispatched_src_infos := next_track_dispatched_src_infos
      CompressAccelLogger.logInfo("dispatched src info commands: current 0x%x, next 0x%x\n",
        track_dispatched_src_infos,
        next_track_dispatched_src_infos)
    }
  }

  when (io.rocc_in.fire) {
    CompressAccelLogger.logInfo("gotcmd funct %x, rd %x, rs1val %x, rs2val %x\n", io.rocc_in.bits.inst.funct, io.rocc_in.bits.inst.rd, io.rocc_in.bits.rs1, io.rocc_in.bits.rs2)
  }

  io.dmem_status_out.bits <> io.rocc_in.bits
  io.dmem_status_out.valid := io.rocc_in.fire

  val current_funct = io.rocc_in.bits.inst.funct

  val sfence_fire = DecoupledHelper(
    io.rocc_in.valid,
    current_funct === FUNCT_SFENCE
  )
  io.sfence_out := sfence_fire.fire

  val MAX_OFFSET_ALLOWED = RegInit(((64 * 1024) - 64).U(64.W))
  io.MAX_OFFSET_ALLOWED := MAX_OFFSET_ALLOWED

  val max_offset_allowed_fire = DecoupledHelper(
    io.rocc_in.valid,
    current_funct === FUNCT_MAX_OFFSET_ALLOWED
  )

  when (max_offset_allowed_fire.fire) {
    MAX_OFFSET_ALLOWED := io.rocc_in.bits.rs1    
  }


  val RUNTIME_HT_NUM_ENTRIES_LOG2 = RegInit(14.U(5.W))
  io.RUNTIME_HT_NUM_ENTRIES_LOG2 := RUNTIME_HT_NUM_ENTRIES_LOG2

  val runtime_ht_num_entries_fire = DecoupledHelper(
    io.rocc_in.valid,
    current_funct === FUNCT_RUNTIME_HT_NUM_ENTRIES_LOG2
  )

  when (runtime_ht_num_entries_fire.fire) {
    RUNTIME_HT_NUM_ENTRIES_LOG2 := io.rocc_in.bits.rs1
  }

  val compress_src_info_queue = Module(new Queue(new StreamInfo, 4))
  io.compress_src_info <> compress_src_info_queue.io.deq

  val compress_src_info_queue2 = Module(new Queue(new StreamInfo, 4))
  io.compress_src_info2 <> compress_src_info_queue2.io.deq

  val compress_dest_info_queue = Module(new Queue(new SnappyCompressDestInfo, 4))
  io.compress_dest_info <> compress_dest_info_queue.io.deq

  val compress_src_info_fire = DecoupledHelper(
    io.rocc_in.valid,
    compress_src_info_queue.io.enq.ready,
    compress_src_info_queue2.io.enq.ready,
    current_funct === FUNCT_SRC_INFO
  )

  compress_src_info_queue.io.enq.bits.ip := io.rocc_in.bits.rs1
  compress_src_info_queue.io.enq.bits.isize := io.rocc_in.bits.rs2
  compress_src_info_queue.io.enq.valid := compress_src_info_fire.fire(compress_src_info_queue.io.enq.ready)
  compress_src_info_queue2.io.enq.bits.ip := io.rocc_in.bits.rs1
  compress_src_info_queue2.io.enq.bits.isize := io.rocc_in.bits.rs2
  compress_src_info_queue2.io.enq.valid := compress_src_info_fire.fire(compress_src_info_queue2.io.enq.ready)

  val compress_dest_info_fire = DecoupledHelper(
    io.rocc_in.valid,
    compress_dest_info_queue.io.enq.ready,
    current_funct === FUNCT_DEST_INFO_AND_START
  )

  compress_dest_info_queue.io.enq.bits.op := io.rocc_in.bits.rs1
  compress_dest_info_queue.io.enq.bits.boolptr := io.rocc_in.bits.rs2
  compress_dest_info_queue.io.enq.valid := compress_dest_info_fire.fire(compress_dest_info_queue.io.enq.ready)

  val do_check_completion_fire = DecoupledHelper(
    io.rocc_in.valid,
    current_funct === FUNCT_CHECK_COMPLETION,
    io.no_writes_inflight,
    io.bufs_completed === track_dispatched_src_infos,
    io.rocc_out.ready
  )

  when (io.rocc_in.valid && current_funct === FUNCT_CHECK_COMPLETION) {
    CompressAccelLogger.logInfo("[commandrouter] WAITING FOR COMPLETION. no_writes_inflight 0x%d, completed 0x%x, dispatched 0x%x, rocc_out.ready 0x%x\n",
      io.no_writes_inflight, io.bufs_completed, track_dispatched_src_infos, io.rocc_out.ready)
  }

  io.rocc_out.valid := do_check_completion_fire.fire(io.rocc_out.ready)
  io.rocc_out.bits.rd := io.rocc_in.bits.inst.rd
  io.rocc_out.bits.data := track_dispatched_src_infos

  io.rocc_in.ready := sfence_fire.fire(io.rocc_in.valid) || compress_src_info_fire.fire(io.rocc_in.valid) ||  compress_dest_info_fire.fire(io.rocc_in.valid) || do_check_completion_fire.fire(io.rocc_in.valid) || max_offset_allowed_fire.fire(io.rocc_in.valid) || runtime_ht_num_entries_fire.fire(io.rocc_in.valid)

}

