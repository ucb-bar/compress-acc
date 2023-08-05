package compressacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants


class SnappyDecompressDestInfo extends Bundle {
  val op = UInt(64.W)
  val boolptr = UInt(64.W)
}

class SnappyDecompressorCommandRouter()(implicit p: Parameters) extends Module {
  val FUNCT_SFENCE = UInt(0)

  val FUNCT_SRC_INFO = UInt(1)
  val FUNCT_DEST_INFO_AND_START = UInt(2)
  val FUNCT_CHECK_COMPLETION = UInt(3)
  val FUNCT_SET_ONCHIP_HIST = UInt(4)

  val io = IO(new Bundle{
    val rocc_in = Decoupled(new RoCCCommand).flip
    val rocc_out = Decoupled(new RoCCResponse)

    val sfence_out = Bool(OUTPUT)
    val dmem_status_out = Valid(new RoCCCommand)

    val decompress_src_info = Decoupled(new StreamInfo)
    val decompress_dest_info = Decoupled(new SnappyDecompressDestInfo)
    val decompress_dest_info_offchip = Decoupled(new SnappyDecompressDestInfo)

    val bufs_completed = Input(UInt(64.W))
    val no_writes_inflight = Input(Bool())

    val onChipHistLenConfig = Output(UInt(32.W))
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


  val set_onchip_hist_fire = DecoupledHelper(
    io.rocc_in.valid,
    current_funct === FUNCT_SET_ONCHIP_HIST
  )

  val onChipHistLenConfig = RegInit((64*64).U(32.W))
  when (set_onchip_hist_fire.fire()) {
    CompressAccelLogger.logInfo("Updating onChipHistLenConfig to: %d\n", io.rocc_in.bits.rs1)
    onChipHistLenConfig := io.rocc_in.bits.rs1
  }
  io.onChipHistLenConfig := onChipHistLenConfig

  val decompress_src_info_queue = Module(new Queue(new StreamInfo, 4))
  io.decompress_src_info <> decompress_src_info_queue.io.deq

  val decompress_dest_info_queue = Module(new Queue(new SnappyDecompressDestInfo, 4))
  io.decompress_dest_info <> decompress_dest_info_queue.io.deq

  val decompress_dest_info_queue_offchip = Module(new Queue(new SnappyDecompressDestInfo, 4))
  io.decompress_dest_info_offchip <> decompress_dest_info_queue_offchip.io.deq



  val decompress_src_info_fire = DecoupledHelper(
    io.rocc_in.valid,
    decompress_src_info_queue.io.enq.ready,
    current_funct === FUNCT_SRC_INFO
  )

  decompress_src_info_queue.io.enq.bits.ip := io.rocc_in.bits.rs1
  decompress_src_info_queue.io.enq.bits.isize := io.rocc_in.bits.rs2
  decompress_src_info_queue.io.enq.valid := decompress_src_info_fire.fire(decompress_src_info_queue.io.enq.ready)


  val decompress_dest_info_fire = DecoupledHelper(
    io.rocc_in.valid,
    decompress_dest_info_queue.io.enq.ready,
    decompress_dest_info_queue_offchip.io.enq.ready,
    current_funct === FUNCT_DEST_INFO_AND_START
  )

  decompress_dest_info_queue.io.enq.bits.op := io.rocc_in.bits.rs1
  decompress_dest_info_queue.io.enq.bits.boolptr := io.rocc_in.bits.rs2
  decompress_dest_info_queue.io.enq.valid := decompress_dest_info_fire.fire(decompress_dest_info_queue.io.enq.ready)

  decompress_dest_info_queue_offchip.io.enq.bits.op := io.rocc_in.bits.rs1
  decompress_dest_info_queue_offchip.io.enq.bits.boolptr := io.rocc_in.bits.rs2
  decompress_dest_info_queue_offchip.io.enq.valid := decompress_dest_info_fire.fire(decompress_dest_info_queue_offchip.io.enq.ready)


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

  io.rocc_in.ready := sfence_fire.fire(io.rocc_in.valid) || decompress_src_info_fire.fire(io.rocc_in.valid) ||  decompress_dest_info_fire.fire(io.rocc_in.valid) || do_check_completion_fire.fire(io.rocc_in.valid) || set_onchip_hist_fire.fire(io.rocc_in.valid)

}

