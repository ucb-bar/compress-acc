// Reused Snappy Decompressor's OffchipHistoryLookup Design
package compressacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants

class ZstdOffchipHistoryLookup(history_len: Int)(implicit p: Parameters) 
  extends Module with MemoryOpConstants{

  val io = IO(new Bundle{
    val algorithm = Input(Bool())
    val internal_commands = (Decoupled(new ZstdSeqInfo)).flip //from SeqExecLoader
    val literal_chunks = (Decoupled(new LiteralChunk)).flip //from SeqExecLoader
    // val final_command = (Decoupled(Bool())).flip //from SeqExecLoader
    val decompress_dest_info = (Decoupled(new SnappyDecompressDestInfo)).flip //from SeqExecControl

    val l2helperUser = new L2MemHelperBundle

    val internal_commands_out = (Decoupled(new ZstdSeqInfo)) //to SeqExecWriter
    val literal_chunks_out = (Decoupled(new LiteralChunk)) //to SeqExecWriter
    // val final_command_out = (Decoupled(Bool())) //to SeqExecWriter
    val MAX_OFFSET_ALLOWED = UInt(INPUT, 64.W)
  })
  val nosnappy = p(NoSnappy)

  val hist_memloader = Module(new MemLoader())
  io.l2helperUser <> hist_memloader.io.l2helperUser

  val intermediate_internal_commands = Module(new Queue(new ZstdSeqInfo, 10))
  val intermediate_literal_chunks = Module(new Queue(new LiteralChunk, 10))

  // stage 1: most commands just pass through. far away copies get dispatched
  // to memloader
  intermediate_internal_commands.io.enq.bits <> io.internal_commands.bits
  intermediate_literal_chunks.io.enq.bits <> io.literal_chunks.bits

  io.internal_commands.ready := false.B
  io.literal_chunks.ready := false.B
  intermediate_internal_commands.io.enq.valid := false.B
  intermediate_literal_chunks.io.enq.valid := false.B
  hist_memloader.io.src_info.valid := false.B

  val offset_into_output_so_far = RegInit(0.U(64.W))

  val literal_fire = DecoupledHelper(
    io.internal_commands.valid,
    io.literal_chunks.valid,
    intermediate_internal_commands.io.enq.ready,
    intermediate_literal_chunks.io.enq.ready,
    io.decompress_dest_info.valid
  )

  val far_copy_fire = DecoupledHelper(
    io.internal_commands.valid,
    intermediate_internal_commands.io.enq.ready,
    hist_memloader.io.src_info.ready,
    io.decompress_dest_info.valid
  )

  if(nosnappy){
    io.decompress_dest_info.ready := io.internal_commands.fire
  }else{
    io.decompress_dest_info.ready := io.internal_commands.fire &&
      Mux(io.algorithm===0.U, true.B, io.internal_commands.bits.is_final_command)
  }

  // val BASE_OUTPUT_POINTER = io.decompress_dest_info.bits.op
  // hist_memloader.io.src_info.bits.ip := (BASE_OUTPUT_POINTER + offset_into_output_so_far) - io.internal_commands.bits.offset
  if(nosnappy){
    hist_memloader.io.src_info.bits.ip := io.decompress_dest_info.bits.op -
      io.internal_commands.bits.offset 
  } else{
    hist_memloader.io.src_info.bits.ip := io.decompress_dest_info.bits.op +& 
      Mux(io.algorithm===0.U, 0.U, offset_into_output_so_far) - 
      io.internal_commands.bits.offset 
  }
  
  hist_memloader.io.src_info.bits.isize := io.internal_commands.bits.ml

  when (!io.internal_commands.bits.is_match) {
    // literal passthrough, just update dist
    io.internal_commands.ready := literal_fire.fire(io.internal_commands.valid)
    io.literal_chunks.ready := literal_fire.fire(io.literal_chunks.valid)
    intermediate_internal_commands.io.enq.valid := literal_fire.fire(intermediate_internal_commands.io.enq.ready)
    intermediate_literal_chunks.io.enq.valid := literal_fire.fire(intermediate_literal_chunks.io.enq.ready)

    if(!nosnappy){
      when(io.algorithm===1.U && literal_fire.fire) {
        when (io.internal_commands.bits.is_final_command) {
          offset_into_output_so_far := 0.U
        } .otherwise {
          offset_into_output_so_far := offset_into_output_so_far + io.literal_chunks.bits.chunk_size_bytes
        }
      }
    }
    
  } .elsewhen (io.internal_commands.bits.offset <= io.MAX_OFFSET_ALLOWED) {
    // the above condition is correct because offsets are 1 ... (history_len)
    io.internal_commands.ready := intermediate_internal_commands.io.enq.ready
    intermediate_internal_commands.io.enq.valid := io.internal_commands.valid
    if(!nosnappy){
      when(io.algorithm===1.U && io.internal_commands.fire) {
        when (io.internal_commands.bits.is_final_command) {
          offset_into_output_so_far := 0.U
        } .otherwise {
          offset_into_output_so_far := offset_into_output_so_far + io.internal_commands.bits.ml
        }
      }
    }    
  } .otherwise {
    // far away copy
    io.internal_commands.ready := far_copy_fire.fire(io.internal_commands.valid)
    intermediate_internal_commands.io.enq.valid := far_copy_fire.fire(intermediate_internal_commands.io.enq.ready)
    hist_memloader.io.src_info.valid := far_copy_fire.fire(hist_memloader.io.src_info.ready)

    if(!nosnappy){
      when (io.algorithm===1.U && far_copy_fire.fire) {
        when (io.internal_commands.bits.is_final_command) {
          offset_into_output_so_far := 0.U
        } .otherwise {
          offset_into_output_so_far := offset_into_output_so_far + io.internal_commands.bits.ml
        }
      }
    }    
  }


  // stage 2: most commands just pass through. far away copies block for mem resp
  // from memloader
  val final_internal_commands = Module(new Queue(new ZstdSeqInfo, 5))
  val final_literal_chunks = Module(new Queue(new LiteralChunk, 5))
  io.internal_commands_out <> final_internal_commands.io.deq
  io.literal_chunks_out <> final_literal_chunks.io.deq

  final_internal_commands.io.enq.bits <> intermediate_internal_commands.io.deq.bits
  final_literal_chunks.io.enq.bits <> intermediate_literal_chunks.io.deq.bits

  final_internal_commands.io.enq.valid := false.B
  final_literal_chunks.io.enq.valid := false.B
  intermediate_internal_commands.io.deq.ready := false.B
  intermediate_literal_chunks.io.deq.ready := false.B
  hist_memloader.io.consumer.output_ready := false.B

  val literal_fire_s2 = DecoupledHelper(
    final_internal_commands.io.enq.ready,
    final_literal_chunks.io.enq.ready,
    intermediate_internal_commands.io.deq.valid,
    intermediate_literal_chunks.io.deq.valid
  )

  val far_copy_fire_s2 = DecoupledHelper(
    final_internal_commands.io.enq.ready,
    // yes, this belongs here. we're going to put the data we read in literal chunks
    final_literal_chunks.io.enq.ready,
    intermediate_internal_commands.io.deq.valid,
    hist_memloader.io.consumer.output_valid,
    hist_memloader.io.consumer.available_output_bytes >= intermediate_internal_commands.io.deq.bits.ml
  )

  hist_memloader.io.consumer.user_consumed_bytes := intermediate_internal_commands.io.deq.bits.ml

  when (!intermediate_internal_commands.io.deq.bits.is_match) {
    // literal passthrough, just update dist
    intermediate_internal_commands.io.deq.ready := literal_fire_s2.fire(intermediate_internal_commands.io.deq.valid)
    intermediate_literal_chunks.io.deq.ready := literal_fire_s2.fire(intermediate_literal_chunks.io.deq.valid)
    final_internal_commands.io.enq.valid := literal_fire_s2.fire(final_internal_commands.io.enq.ready)
    final_literal_chunks.io.enq.valid := literal_fire_s2.fire(final_literal_chunks.io.enq.ready)
  } .elsewhen (intermediate_internal_commands.io.deq.bits.offset <= io.MAX_OFFSET_ALLOWED) {
    // the above condition is correct because offsets are 1 ... onChipHistLen
    final_internal_commands.io.enq.valid := intermediate_internal_commands.io.deq.valid
    intermediate_internal_commands.io.deq.ready := final_internal_commands.io.enq.ready
  } .otherwise {
    // far copy
    hist_memloader.io.consumer.output_ready := far_copy_fire_s2.fire(hist_memloader.io.consumer.output_valid)
    final_literal_chunks.io.enq.bits.chunk_data := hist_memloader.io.consumer.output_data
    final_literal_chunks.io.enq.bits.chunk_size_bytes := intermediate_internal_commands.io.deq.bits.ml

    // converted to a literal by already loading the data.
    final_internal_commands.io.enq.bits.is_match := false.B
    final_internal_commands.io.enq.valid := far_copy_fire_s2.fire(final_internal_commands.io.enq.ready)
    final_literal_chunks.io.enq.valid := far_copy_fire_s2.fire(final_literal_chunks.io.enq.ready)
    intermediate_internal_commands.io.deq.ready := far_copy_fire_s2.fire(intermediate_internal_commands.io.deq.valid)
  }
}
