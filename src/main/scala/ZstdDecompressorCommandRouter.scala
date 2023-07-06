package compressacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants

class ZstdDecompressorCommandRouterIO()(implicit val p: Parameters) 
  extends Bundle {
  val rocc_in = Flipped(Decoupled(new RoCCCommand))
  val rocc_out = Decoupled(new RoCCResponse)

  val dmem_status_out = Valid(new RoCCCommand)
  val sfence_out = Output(Bool())

// val no_memops_inflight = Input(Bool())
// val finished_src_info = Input(UInt(64.W))

  val src_info = Decoupled(new StreamInfo)
  val wksp_info = Decoupled(new DecompressPtrInfo)
  val dst_info = Decoupled(new DecompressDstInfo)

  val MAX_OFFSET_ALLOWED = Output(UInt(64.W))
  val ALGORITHM = Output(UInt(1.W))
  val LATENCY_INJECTION_CYCLES = Output(UInt(32.W))
  val HAS_INTERMEDIATE_CACHE = Output(Bool())

  val finished_cnt = Flipped(Decoupled(UInt(64.W)))

  //Snappy IOs
  val snappy_decompress_src_info = Decoupled(new StreamInfo)
  val snappy_decompress_dest_info = Decoupled(new SnappyDecompressDestInfo)
  val snappy_decompress_dest_info_offchip = Decoupled(new SnappyDecompressDestInfo)
  val snappy_bufs_completed = Input(UInt(64.W))
  val snappy_no_writes_inflight = Input(Bool())
}

class ZstdDecompressorCommandRouter(val cmd_que_depth: Int)(implicit p: Parameters) 
  extends Module {

  val io = IO(new ZstdDecompressorCommandRouterIO)

  val FUNCT_SFENCE                        = 0.U
  val FUNCT_ALGORITHM                     = 1.U
  val FUNCT_LATENCY                       = 2.U
  //ZSTD
  val FUNCT_ZSTD_SRC_INFO                 = 3.U
  val FUNCT_ZSTD_WKSP_INFO                = 4.U
  val FUNCT_ZSTD_DST_INFO                 = 5.U 
  val FUNCT_ZSTD_CHECK_COMPLETION         = 6.U 
  val FUNCT_ZSTD_MAX_OFFSET_ALLOWED       = 7.U 
  //Snappy
  val FUNCT_SNAPPY_SRC_INFO               = 8.U 
  val FUNCT_SNAPPY_DEST_INFO_AND_START    = 9.U 
  val FUNCT_SNAPPY_CHECK_COMPLETION       = 10.U 
  val FUNCT_SNAPPY_SET_ONCHIP_HIST        = 11.U 

  val algorithm = RegInit(0.U(1.W)) //0: Zstd, 1: Snappy
  val latency_injection_cycles = RegInit(0.U(32.W))
  val has_intermediate_cache = RegInit(false.B)
  io.ALGORITHM := algorithm
  io.LATENCY_INJECTION_CYCLES := latency_injection_cycles
  io.HAS_INTERMEDIATE_CACHE := has_intermediate_cache
  val nosnappy = p(NoSnappy)

  val dispatched_src_info = RegInit(0.U(64.W))

  val cur_funct = io.rocc_in.bits.inst.funct
  val cur_rs1 = io.rocc_in.bits.rs1
  val cur_rs2 = io.rocc_in.bits.rs2

  val sfence_fire = DecoupledHelper(io.rocc_in.valid,
                                    cur_funct === FUNCT_SFENCE)
  io.sfence_out := sfence_fire.fire

  val MAX_OFFSET_ALLOWED = RegInit((64 * 1024).U(64.W))
  io.MAX_OFFSET_ALLOWED := MAX_OFFSET_ALLOWED

  val max_offset_allowed_fire = DecoupledHelper(
    io.rocc_in.valid,
    cur_funct === FUNCT_ZSTD_MAX_OFFSET_ALLOWED || cur_funct === FUNCT_SNAPPY_SET_ONCHIP_HIST
  )

  when (max_offset_allowed_fire.fire) {
    MAX_OFFSET_ALLOWED := io.rocc_in.bits.rs1    
  }


  io.dmem_status_out.bits <> io.rocc_in.bits
  io.dmem_status_out.valid <> io.rocc_in.fire

  when (io.rocc_in.fire) {
    CompressAccelLogger.logInfo("rocc_in_data, opcode: %d, rs1: 0x%x, rs2: 0x%x\n", cur_funct, cur_rs1, cur_rs2)
  }

  when (io.rocc_in.fire) {
    when (cur_funct === FUNCT_ZSTD_SRC_INFO) {
      val nxt_dispatched_src_info = dispatched_src_info + 1.U
      dispatched_src_info := nxt_dispatched_src_info

      CompressAccelLogger.logInfo("CommandRouter, dispatched src cnt: %d\n", 
        nxt_dispatched_src_info)
    }
  }

  val algorithm_fire = DecoupledHelper(io.rocc_in.valid,
    cur_funct === FUNCT_ALGORITHM)
  when(algorithm_fire.fire){
    algorithm := io.rocc_in.bits.rs1
  }

  val latency_injection_fire = DecoupledHelper(io.rocc_in.valid,
    cur_funct === FUNCT_LATENCY)
  when(latency_injection_fire.fire){
    latency_injection_cycles := io.rocc_in.bits.rs1
    has_intermediate_cache := io.rocc_in.bits.rs2
  }
  
  ////////// ZSTD-Specific Part //////////
  val src_info_queue = Module(new Queue(new StreamInfo, cmd_que_depth))
  val src_info_fire = DecoupledHelper(io.rocc_in.valid,
                                      src_info_queue.io.enq.ready,
                                      cur_funct === FUNCT_ZSTD_SRC_INFO)
  src_info_queue.io.enq.bits.ip := cur_rs1
  src_info_queue.io.enq.bits.isize := cur_rs2
  src_info_queue.io.enq.valid := src_info_fire.fire(src_info_queue.io.enq.ready)
  io.src_info <> src_info_queue.io.deq

  when (io.src_info.fire) {
    CompressAccelLogger.logInfo("CommandRouter, io.src_info.fire!\n")
    CompressAccelLogger.logInfo("CommandRouter, io.src_info.ptr: 0x%x\n", io.src_info.bits.ip)
    CompressAccelLogger.logInfo("CommandRouter, io.src_info.size: %d\n", io.src_info.bits.isize)
  }

  val wksp_info_queue = Module(new Queue(new DecompressPtrInfo, cmd_que_depth))
  val wksp_info_fire = DecoupledHelper(io.rocc_in.valid,
                                       wksp_info_queue.io.enq.ready,
                                       cur_funct === FUNCT_ZSTD_WKSP_INFO)
  wksp_info_queue.io.enq.valid := wksp_info_fire.fire(wksp_info_queue.io.enq.ready)
  wksp_info_queue.io.enq.bits.ip := cur_rs1
  io.wksp_info <> wksp_info_queue.io.deq


  val dst_info_queue = Module(new Queue(new DecompressDstInfo, cmd_que_depth))
  val dst_info_fire = DecoupledHelper(io.rocc_in.valid,
                                      dst_info_queue.io.enq.ready,
                                      cur_funct === FUNCT_ZSTD_DST_INFO)
  dst_info_queue.io.enq.bits.op := cur_rs1
  dst_info_queue.io.enq.bits.cmpflag := cur_rs2
  dst_info_queue.io.enq.valid := dst_info_fire.fire(dst_info_queue.io.enq.ready)
  io.dst_info <> dst_info_queue.io.deq

  when (io.dst_info.fire) {
    CompressAccelLogger.logInfo("CommandRouter, io.dst_info.fire!\n")
    CompressAccelLogger.logInfo("CommandRouter, io.dst_info.op: 0x%x\n", io.dst_info.bits.op)
    CompressAccelLogger.logInfo("CommandRouter, io.dst_info.cmpflag: %d\n", io.dst_info.bits.cmpflag)
  }

  val finished_q = Module(new Queue(UInt(64.W), cmd_que_depth))
  finished_q.io.enq <> io.finished_cnt

  val zstd_do_check_completion_fire = DecoupledHelper(
    io.rocc_in.valid,
    cur_funct === FUNCT_ZSTD_CHECK_COMPLETION,
    finished_q.io.deq.valid,
    dispatched_src_info === finished_q.io.deq.bits
  )

  finished_q.io.deq.ready := zstd_do_check_completion_fire.fire(finished_q.io.deq.valid)

  when (zstd_do_check_completion_fire.fire) {
    CompressAccelLogger.logInfo("Zstd Decompressor CommandRouter, zstd_do_check_completion_fire.fire\n")
    CompressAccelLogger.logInfo("Zstd Decompressor CommandRouter, dispatched_src_info: %d\n", dispatched_src_info)
  }

  if(nosnappy){
    io.rocc_out.valid := zstd_do_check_completion_fire.fire
    io.rocc_out.bits.data := dispatched_src_info
    io.rocc_in.ready := sfence_fire.fire(io.rocc_in.valid) || //Common
      algorithm_fire.fire(io.rocc_in.valid) ||
      latency_injection_fire.fire(io.rocc_in.valid) ||
      //Zstd
      src_info_fire.fire(io.rocc_in.valid) ||
      wksp_info_fire.fire(io.rocc_in.valid) ||
      dst_info_fire.fire(io.rocc_in.valid) ||
      zstd_do_check_completion_fire.fire(io.rocc_in.valid) ||
      //Common
      max_offset_allowed_fire.fire(io.rocc_in.valid)
  }

  ////////// Snappy-Specific Part //////////
  if(!nosnappy){
    val track_dispatched_src_infos = RegInit(0.U(64.W))
    val bufs_completed_when_start = RegInit(0.U(64.W))
    when (io.rocc_in.fire) {
      when (io.rocc_in.bits.inst.funct === FUNCT_SNAPPY_SRC_INFO) {
        val next_track_dispatched_src_infos = track_dispatched_src_infos + 1.U
        track_dispatched_src_infos := next_track_dispatched_src_infos
        CompressAccelLogger.logInfo("dispatched src info commands: current 0x%x, next 0x%x\n",
          track_dispatched_src_infos,
          next_track_dispatched_src_infos)
        bufs_completed_when_start := io.snappy_bufs_completed
      }
    }

    val snappy_decompress_src_info_queue = Module(new Queue(new StreamInfo, 4))
    io.snappy_decompress_src_info <> snappy_decompress_src_info_queue.io.deq

    val snappy_decompress_dest_info_queue = Module(new Queue(new SnappyDecompressDestInfo, 4))
    io.snappy_decompress_dest_info <> snappy_decompress_dest_info_queue.io.deq

    val snappy_decompress_dest_info_queue_offchip = Module(new Queue(new SnappyDecompressDestInfo, 4))
    io.snappy_decompress_dest_info_offchip <> snappy_decompress_dest_info_queue_offchip.io.deq

    val snappy_decompress_src_info_fire = DecoupledHelper(
      io.rocc_in.valid,
      snappy_decompress_src_info_queue.io.enq.ready,
      cur_funct === FUNCT_SNAPPY_SRC_INFO
    )
    snappy_decompress_src_info_queue.io.enq.bits.ip := io.rocc_in.bits.rs1
    snappy_decompress_src_info_queue.io.enq.bits.isize := io.rocc_in.bits.rs2
    snappy_decompress_src_info_queue.io.enq.valid := snappy_decompress_src_info_fire.fire(
      snappy_decompress_src_info_queue.io.enq.ready)

    val snappy_decompress_dest_info_fire = DecoupledHelper(
      io.rocc_in.valid,
      snappy_decompress_dest_info_queue.io.enq.ready,
      snappy_decompress_dest_info_queue_offchip.io.enq.ready,
      cur_funct === FUNCT_SNAPPY_DEST_INFO_AND_START
    )
    snappy_decompress_dest_info_queue.io.enq.bits.op := io.rocc_in.bits.rs1
    snappy_decompress_dest_info_queue.io.enq.bits.boolptr := io.rocc_in.bits.rs2
    snappy_decompress_dest_info_queue.io.enq.valid := 
      snappy_decompress_dest_info_fire.fire(snappy_decompress_dest_info_queue.io.enq.ready)
    snappy_decompress_dest_info_queue_offchip.io.enq.bits.op := io.rocc_in.bits.rs1
    snappy_decompress_dest_info_queue_offchip.io.enq.bits.boolptr := io.rocc_in.bits.rs2
    snappy_decompress_dest_info_queue_offchip.io.enq.valid := 
      snappy_decompress_dest_info_fire.fire(snappy_decompress_dest_info_queue_offchip.io.enq.ready)

    val snappy_do_check_completion_fire = DecoupledHelper(
      io.rocc_in.valid,
      cur_funct === FUNCT_SNAPPY_CHECK_COMPLETION,
      io.snappy_no_writes_inflight,
      track_dispatched_src_infos =/= 0.U,
      io.snappy_bufs_completed - bufs_completed_when_start === track_dispatched_src_infos,
      io.rocc_out.ready
    )
    when(io.rocc_in.fire && cur_funct ===FUNCT_SNAPPY_CHECK_COMPLETION){
      track_dispatched_src_infos := 0.U
      bufs_completed_when_start := io.snappy_bufs_completed
    }

    when (io.rocc_in.valid && cur_funct === FUNCT_SNAPPY_CHECK_COMPLETION) {
      CompressAccelLogger.logInfo("[commandrouter] WAITING FOR COMPLETION. no_writes_inflight 0x%d, completed 0x%x, dispatched 0x%x, rocc_out.ready 0x%x\n",
        io.snappy_no_writes_inflight, io.snappy_bufs_completed, track_dispatched_src_infos, io.rocc_out.ready)
    }

    io.rocc_out.valid := Mux(algorithm===0.U, 
      zstd_do_check_completion_fire.fire,
      snappy_do_check_completion_fire.fire(io.rocc_out.ready))

    io.rocc_out.bits.data := Mux(algorithm===0.U, 
      dispatched_src_info,
      track_dispatched_src_infos)

    io.rocc_in.ready := sfence_fire.fire(io.rocc_in.valid) || //Common
      algorithm_fire.fire(io.rocc_in.valid) ||
      latency_injection_fire.fire(io.rocc_in.valid) || 
      //Zstd
      src_info_fire.fire(io.rocc_in.valid) ||
      wksp_info_fire.fire(io.rocc_in.valid) ||
      dst_info_fire.fire(io.rocc_in.valid) ||
      zstd_do_check_completion_fire.fire(io.rocc_in.valid) ||
      //Snappy
      snappy_decompress_src_info_fire.fire(io.rocc_in.valid) || 
      snappy_decompress_dest_info_fire.fire(io.rocc_in.valid) ||
      snappy_do_check_completion_fire.fire(io.rocc_in.valid) ||
      //Common
      max_offset_allowed_fire.fire(io.rocc_in.valid)
  }
  ////////// Common Part /////////
  io.rocc_out.bits.rd := io.rocc_in.bits.inst.rd
}
