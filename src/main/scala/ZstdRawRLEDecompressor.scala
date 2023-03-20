package compressacc

import Chisel._
import chisel3.{Printable, SyncReadMem}
import freechips.rocketchip.tile._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheArbiter}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._

class ZstdRawRLEDecompressor(l2bw: Int)(implicit p: Parameters) extends Module{
  val io = IO(new Bundle{
    // from ZstdBlockDecompressor
    val ip = Input(UInt(64.W)) //input address of block content
    val op = Input(UInt(64.W)) //output address of block content
    val block_type = Input(UInt(1.W)) //0: Raw, 1: RLE
    val block_size = Input(UInt(21.W)) //number of bytes to write
    val enable = Input(Bool()) // only put data request when state is STATE_RAWRLE
    // to ZstdBlockDecompressor
    val completion = Output(Bool())

    // to MemLoader (memloader_rawrle)
    val src_info = Decoupled(new StreamInfo)
    // from MemLoader (memloader_rawrle)
    val mem_stream = (new MemLoaderConsumerBundle).flip

    // to ZstdSeqExecWriter
    val rawrle_data = Decoupled(new LiteralChunk)
    val dest_info = Decoupled(new SnappyDecompressDestInfo)
    val commands = Decoupled(new ZstdSeqInfo)
    // from ZstdSeqExecWriter
    val bufs_completed = Input(UInt(64.W))
    val no_writes_inflight = Input(Bool())
  })

  val write_requests_to_put = (io.block_size-1.U)/(l2bw/8).U + 1.U
  val write_requests_so_far = RegInit(0.U(20.W))
  val read_requests_so_far = RegInit(0.U(1.W))
  val dest_requests_so_far = RegInit(0.U(1.W))
  
  // Queue 1. src_info_queue
  val src_info_queue = Module(new Queue(new StreamInfo, 4))
  io.src_info <> src_info_queue.io.deq
  
  // Queue 2. data_queue, command_queue (to feed to SeqExecWriter)
  val data_queue = Module(new Queue(new LiteralChunk, 4))
  io.rawrle_data <> data_queue.io.deq
  val command_queue = Module(new Queue(new ZstdSeqInfo, 4))
  io.commands <> command_queue.io.deq

  // Queue 3. dest_info_queue
  val dest_info_queue = Module(new Queue(new SnappyDecompressDestInfo, 4))
  io.dest_info <> dest_info_queue.io.deq

  val rle_byte = Wire(UInt(8.W))
  val rle_byte_reg = RegInit(0.U(8.W))
  rle_byte := Mux(write_requests_so_far===0.U, io.mem_stream.output_data(7,0), rle_byte_reg)
  when(io.block_type===1.U && write_requests_so_far===0.U && io.mem_stream.output_valid){
    rle_byte_reg := io.mem_stream.output_data(7, 0)
  }
  val rle_replicate = Wire(UInt(l2bw.W))
  // Assuming l2bw of 32B
  rle_replicate := Cat(
    rle_byte, rle_byte, rle_byte, rle_byte, rle_byte, rle_byte, rle_byte, rle_byte,
    rle_byte, rle_byte, rle_byte, rle_byte, rle_byte, rle_byte, rle_byte, rle_byte,
    rle_byte, rle_byte, rle_byte, rle_byte, rle_byte, rle_byte, rle_byte, rle_byte,
    rle_byte, rle_byte, rle_byte, rle_byte, rle_byte, rle_byte, rle_byte, rle_byte
  )
  val data_write_size = Mux(write_requests_so_far < write_requests_to_put - 1.U,
    (l2bw/8).U,
    io.block_size - write_requests_so_far*(l2bw/8).U)
  val data_read_size = Mux(io.block_type===0.U, data_write_size, 1.U)

  // 1. Put a read request to the src_info_queue which will be sent to the MemLoader.
  src_info_queue.io.enq.valid := io.enable && read_requests_so_far===0.U
  src_info_queue.io.enq.bits.ip := io.ip
  src_info_queue.io.enq.bits.isize := Mux(io.block_type===0.U, io.block_size, 1.U)
  when(src_info_queue.io.enq.valid && src_info_queue.io.enq.ready){
    read_requests_so_far := 1.U
  }

  // 2. Put data received from the MemLoader to data_queue.
  data_queue.io.enq.bits.chunk_data := Mux(io.block_type===0.U,
    io.mem_stream.output_data,
    rle_replicate)
  data_queue.io.enq.bits.chunk_size_bytes := data_write_size
  val enqueue_cond = write_requests_so_far < write_requests_to_put &&
    Mux(io.block_type===0.U || write_requests_so_far===0.U,
    io.mem_stream.output_valid && (data_read_size <= io.mem_stream.available_output_bytes),
    true.B)
  data_queue.io.enq.valid := io.enable && enqueue_cond && command_queue.io.enq.ready
  io.mem_stream.output_ready := data_queue.io.enq.ready &&
    (data_read_size <= io.mem_stream.available_output_bytes)
  io.mem_stream.user_consumed_bytes := data_read_size
  when(write_requests_so_far < write_requests_to_put &&
    data_queue.io.enq.valid && data_queue.io.enq.ready){
    write_requests_so_far := write_requests_so_far + 1.U
  }
  command_queue.io.enq.bits.is_match := false.B
  command_queue.io.enq.bits.ll := data_write_size
  command_queue.io.enq.bits.ml := 0.U
  command_queue.io.enq.bits.offset := 0.U
  command_queue.io.enq.bits.is_final_command := write_requests_so_far === write_requests_to_put-1.U
  command_queue.io.enq.valid := io.enable && enqueue_cond && data_queue.io.enq.ready
  // 3. Put write requests to dest_info_queue, synchronized with data_queue.
  when(dest_info_queue.io.enq.valid && dest_info_queue.io.enq.ready){
    dest_requests_so_far := dest_requests_so_far + 1.U
  }
  dest_info_queue.io.enq.valid := io.enable && dest_requests_so_far===0.U
  dest_info_queue.io.enq.bits.op := io.op

  //completion check
  io.completion := write_requests_so_far === write_requests_to_put && io.no_writes_inflight
  when(io.completion){
    read_requests_so_far := 0.U
    write_requests_so_far := 0.U
    dest_requests_so_far := 0.U
    rle_byte_reg := 0.U
  }
}
