package compressacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants

class SnappyDecompressorOffchipHistoryLookup()(implicit p: Parameters) extends Module with MemoryOpConstants {

  val io = IO(new Bundle {
    val internal_commands = (Decoupled(new SnappyInternalCommandRep)).flip
    val literal_chunks = (Decoupled(new LiteralChunk)).flip
    val l2helperUser = new L2MemHelperBundle
    val decompress_dest_info = (Decoupled(new SnappyDecompressDestInfo)).flip


    val internal_commands_out = (Decoupled(new SnappyInternalCommandRep))
    val literal_chunks_out = (Decoupled(new LiteralChunk))

    val onChipHistLenConfig = Input(UInt(32.W))


  })

  val hist_memloader = Module(new MemLoader())
  io.l2helperUser <> hist_memloader.io.l2helperUser

  val onChipHistLen = io.onChipHistLenConfig

  val intermediate_internal_commands = Module(new Queue(new SnappyInternalCommandRep, 10))
  val intermediate_literal_chunks = Module(new Queue(new LiteralChunk, 10))
  when (!intermediate_internal_commands.io.enq.ready) {
    CompressAccelLogger.logInfo("WARN! intermediate internal commands queue full.\n")
  }
  when (!intermediate_literal_chunks.io.enq.ready) {
    CompressAccelLogger.logInfo("WARN! intermediate literal chunks queue full.\n")
  }

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

  // pop on final command
  io.decompress_dest_info.ready := io.internal_commands.fire() && io.internal_commands.bits.final_command

  val BASE_OUTPUT_POINTER = io.decompress_dest_info.bits.op
  hist_memloader.io.src_info.bits.ip := (BASE_OUTPUT_POINTER + offset_into_output_so_far) - io.internal_commands.bits.copy_offset
  hist_memloader.io.src_info.bits.isize := io.internal_commands.bits.copy_length

  when (!io.internal_commands.bits.is_copy) {
    // literal passthrough, just update dist
    io.internal_commands.ready := literal_fire.fire(io.internal_commands.valid)
    io.literal_chunks.ready := literal_fire.fire(io.literal_chunks.valid)
    intermediate_internal_commands.io.enq.valid := literal_fire.fire(intermediate_internal_commands.io.enq.ready)
    intermediate_literal_chunks.io.enq.valid := literal_fire.fire(intermediate_literal_chunks.io.enq.ready)
    when (literal_fire.fire()) {
      CompressAccelLogger.logInfo("offchip s1: literal pass: is_copy %d, copy_offset %d, copy_length %d, final_command %d, chunk_data 0x%x, chunk size %d\n",
        io.internal_commands.bits.is_copy,
        io.internal_commands.bits.copy_offset,
        io.internal_commands.bits.copy_length,
        io.internal_commands.bits.final_command,
        io.literal_chunks.bits.chunk_data,
        io.literal_chunks.bits.chunk_size_bytes
      )
      when (io.internal_commands.bits.final_command) {
        offset_into_output_so_far := 0.U
      } .otherwise {
        offset_into_output_so_far := offset_into_output_so_far + io.literal_chunks.bits.chunk_size_bytes
      }
    }
  } .elsewhen (io.internal_commands.bits.copy_offset <= onChipHistLen) {
    // the above condition is correct because offsets are 1 ... onChipHistLen
    io.internal_commands.ready := intermediate_internal_commands.io.enq.ready
    intermediate_internal_commands.io.enq.valid := io.internal_commands.valid
    when (io.internal_commands.fire()) {
      CompressAccelLogger.logInfo("offchip s1: nearcopy pass: is_copy %d, copy_offset %d, copy_length %d, final_command %d\n",
        io.internal_commands.bits.is_copy,
        io.internal_commands.bits.copy_offset,
        io.internal_commands.bits.copy_length,
        io.internal_commands.bits.final_command
      )
      when (io.internal_commands.bits.final_command) {
        offset_into_output_so_far := 0.U
      } .otherwise {
        offset_into_output_so_far := offset_into_output_so_far + io.internal_commands.bits.copy_length
      }
    }
  } .otherwise {
    // far away copy
    io.internal_commands.ready := far_copy_fire.fire(io.internal_commands.valid)
    intermediate_internal_commands.io.enq.valid := far_copy_fire.fire(intermediate_internal_commands.io.enq.ready)
    hist_memloader.io.src_info.valid := far_copy_fire.fire(hist_memloader.io.src_info.ready)

    when (far_copy_fire.fire()) {
      CompressAccelLogger.logInfo("offchip s1: farcopy issued: is_copy %d, copy_offset %d, copy_length %d, final_command %d, memreq_addr 0x%x, memreq_size %d\n",
        io.internal_commands.bits.is_copy,
        io.internal_commands.bits.copy_offset,
        io.internal_commands.bits.copy_length,
        io.internal_commands.bits.final_command,
        hist_memloader.io.src_info.bits.ip,
        hist_memloader.io.src_info.bits.isize
      )
      when (io.internal_commands.bits.final_command) {
        offset_into_output_so_far := 0.U
      } .otherwise {
        offset_into_output_so_far := offset_into_output_so_far + io.internal_commands.bits.copy_length
      }
    }
  }


  // stage 2: most commands just pass through. far away copies block for mem resp
  // from memloader
  val final_internal_commands = Module(new Queue(new SnappyInternalCommandRep, 5))
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
    final_literal_chunks.io.enq.ready,
    intermediate_internal_commands.io.deq.valid,
    hist_memloader.io.consumer.output_valid,
    hist_memloader.io.consumer.available_output_bytes >= intermediate_internal_commands.io.deq.bits.copy_length
  )

  hist_memloader.io.consumer.user_consumed_bytes := intermediate_internal_commands.io.deq.bits.copy_length

  when (!intermediate_internal_commands.io.deq.bits.is_copy) {
    // literal passthrough, just update dist
    intermediate_internal_commands.io.deq.ready := literal_fire_s2.fire(intermediate_internal_commands.io.deq.valid)
    intermediate_literal_chunks.io.deq.ready := literal_fire_s2.fire(intermediate_literal_chunks.io.deq.valid)
    final_internal_commands.io.enq.valid := literal_fire_s2.fire(final_internal_commands.io.enq.ready)
    final_literal_chunks.io.enq.valid := literal_fire_s2.fire(final_literal_chunks.io.enq.ready)

    when (literal_fire_s2.fire()) {
      CompressAccelLogger.logInfo("offchip s2: literal pass: is_copy %d, copy_offset %d, copy_length %d, final_command %d, chunk_data 0x%x, chunk size %d\n",
        intermediate_internal_commands.io.deq.bits.is_copy,
        intermediate_internal_commands.io.deq.bits.copy_offset,
        intermediate_internal_commands.io.deq.bits.copy_length,
        intermediate_internal_commands.io.deq.bits.final_command,
        intermediate_literal_chunks.io.deq.bits.chunk_data,
        intermediate_literal_chunks.io.deq.bits.chunk_size_bytes
      )
    }

  } .elsewhen (intermediate_internal_commands.io.deq.bits.copy_offset <= onChipHistLen) {
    // the above condition is correct because offsets are 1 ... onChipHistLen
    final_internal_commands.io.enq.valid := intermediate_internal_commands.io.deq.valid
    intermediate_internal_commands.io.deq.ready := final_internal_commands.io.enq.ready
    when (final_internal_commands.io.enq.fire()) {
      CompressAccelLogger.logInfo("offchip s2: nearcopy pass: is_copy %d, copy_offset %d, copy_length %d, final_command %d\n",
        intermediate_internal_commands.io.deq.bits.is_copy,
        intermediate_internal_commands.io.deq.bits.copy_offset,
        intermediate_internal_commands.io.deq.bits.copy_length,
        intermediate_internal_commands.io.deq.bits.final_command
      )
    }
  } .otherwise {
    // far copy
    hist_memloader.io.consumer.output_ready := far_copy_fire_s2.fire(hist_memloader.io.consumer.output_valid)
    final_literal_chunks.io.enq.bits.chunk_data := hist_memloader.io.consumer.output_data
    final_literal_chunks.io.enq.bits.chunk_size_bytes := intermediate_internal_commands.io.deq.bits.copy_length
    // converted to a literal by already loading the data.
    final_internal_commands.io.enq.bits.is_copy := false.B
    final_internal_commands.io.enq.valid := far_copy_fire_s2.fire(final_internal_commands.io.enq.ready)
    final_literal_chunks.io.enq.valid := far_copy_fire_s2.fire(final_literal_chunks.io.enq.ready)
    intermediate_internal_commands.io.deq.ready := far_copy_fire_s2.fire(intermediate_internal_commands.io.deq.valid)
    when (far_copy_fire_s2.fire()) {
      CompressAccelLogger.logInfo("offchip s2: farcopy done: is_copy %d, copy_offset %d, copy_length %d, final_command %d, memloader_data 0x%x, memloader_valid_bytes %d\n",
        intermediate_internal_commands.io.deq.bits.is_copy,
        intermediate_internal_commands.io.deq.bits.copy_offset,
        intermediate_internal_commands.io.deq.bits.copy_length,
        intermediate_internal_commands.io.deq.bits.final_command,
        hist_memloader.io.consumer.output_data,
        hist_memloader.io.consumer.available_output_bytes
      )
    }
  }
}

class SnappyDecompressorCommandExpander()(implicit p: Parameters) extends Module with MemoryOpConstants {

  val io = IO(new Bundle {
    val internal_commands = (Decoupled(new SnappyInternalCommandRep)).flip
    val literal_chunks = (Decoupled(new LiteralChunk)).flip
    val l2helperUser = new L2MemHelperBundle
    val decompress_dest_info = (Decoupled(new SnappyDecompressDestInfo)).flip

    val bufs_completed = Output(UInt(64.W))
    val no_writes_inflight = Output(Bool())
  })


  when (io.internal_commands.fire) {
    CompressAccelLogger.logInfo("converted_cmd: is_copy:%d, copy_offset:%d, copy_length:%d, final_command:%d\n",
      io.internal_commands.bits.is_copy,
      io.internal_commands.bits.copy_offset,
      io.internal_commands.bits.copy_length,
      io.internal_commands.bits.final_command
    )
  }

  when (io.literal_chunks.fire) {
    CompressAccelLogger.logInfo("literal_chunks: chunk_size_bytes:%d, chunk_data:0x%x\n",
      io.literal_chunks.bits.chunk_size_bytes,
      io.literal_chunks.bits.chunk_data
    )
  }

  val memwriter = Module(new SnappyDecompressorMemwriter)
  io.l2helperUser <> memwriter.io.l2io
  memwriter.io.decompress_dest_info <> io.decompress_dest_info

  val is_copy_or_have_literal = io.internal_commands.bits.is_copy || io.literal_chunks.valid

  // write into the recent hist + memwriter
  val fire_write = DecoupledHelper(
    io.internal_commands.valid,
    is_copy_or_have_literal,
    memwriter.io.memwrites_in.ready
  )

  io.internal_commands.ready := fire_write.fire(io.internal_commands.valid)
  io.literal_chunks.ready := fire_write.fire(is_copy_or_have_literal) && (!io.internal_commands.bits.is_copy)

  // whether it's a literal
  val is_literal = !io.internal_commands.bits.is_copy
  val literal_data = io.literal_chunks.bits.chunk_data
  // whether it's a copy
  val is_copy = !is_literal
  // for literal or copy, the # of bytes to be added
  val write_num_bytes = Mux(is_literal,
    io.literal_chunks.bits.chunk_size_bytes,
    // already trimmed to size by CopyExpander:
    io.internal_commands.bits.copy_length
  )

  val offset = io.internal_commands.bits.copy_offset


  val recent_history_vec = Array.fill(32) {Mem(2 * 1024, UInt(8.W))}
  val read_indexing_vec = Wire(Vec(32, UInt(16.W)))
  val read_ports_vec = Wire(Vec(32, UInt(8.W)))
  val write_indexing_vec = Wire(Vec(32, UInt(16.W)))
  val write_ports_vec = Wire(Vec(32, UInt(8.W)))
  val write_ports_write_enable = Wire(Vec(32, Bool()))


  for (elemno <- 0 until 32) {
    write_ports_write_enable(elemno) := false.B
  }

  for (elemno <- 0 until 32) {
    read_ports_vec(elemno) := recent_history_vec(elemno)(read_indexing_vec(elemno))
    when (write_ports_write_enable(elemno)) {
      recent_history_vec(elemno)(write_indexing_vec(elemno)) := write_ports_vec(elemno)
    }
  }

  val recent_history_vec_next = Wire(Vec(32, UInt(8.W)))


  val addr_base_ptr = RegInit(0.U(16.W))

  // for literal writes, advance pointer and write to the mems
  // for copies, "wrap" stage which maps mem's output to mem's input: this is offset % 16
  //    addr for mem, which is offset >> 4

  for (elemno <- 0 until 32) {
    when (is_literal) {
      recent_history_vec_next(write_num_bytes - UInt(elemno) - 1.U) := literal_data(((elemno+1) << 3) - 1, elemno << 3)
    } .otherwise {
      //is_copy
      val read_memaddr = (addr_base_ptr + write_num_bytes - offset - elemno.U - 1.U) >> 5
      val read_memno = (addr_base_ptr + write_num_bytes - offset - elemno.U - 1.U) & (0x1F).U
      read_indexing_vec(read_memno) := read_memaddr
      recent_history_vec_next(elemno) := read_ports_vec(read_memno)
      val print_read_ports_vec = Wire(0.U(8.W))
      print_read_ports_vec := read_ports_vec(read_memno)
      when (fire_write.fire) {
        CompressAccelLogger.logInfo("rhvn(elemno:%d): from memno:%d,memaddr:%d = val:0x%x\n", UInt(elemno), read_memno, read_memaddr, print_read_ports_vec)
      }
    }
  }

  for (elemno <- 0 until 32) {
    when (fire_write.fire && (elemno.U(5.W) < write_num_bytes)) {
      val full_address = addr_base_ptr + write_num_bytes - elemno.U - 1.U
      val memno = full_address & (0x1F).U
      val memaddr = full_address >> 5
      write_indexing_vec(memno) := memaddr
      write_ports_vec(memno) := recent_history_vec_next(elemno)
      write_ports_write_enable(memno) := true.B
      val print_recent_history_vec = Wire(0.U(8.W))
      //recent_history_vec_next(elemno))
      print_recent_history_vec := recent_history_vec_next(elemno)
      CompressAccelLogger.logInfo("mem(memno:%d,memaddr:%d): from rhvn(elemno:%d) = val:0x%x\n", memno, memaddr, UInt(elemno), print_recent_history_vec)
    }
  }

  when (fire_write.fire) {
    addr_base_ptr := addr_base_ptr + write_num_bytes
  }

  memwriter.io.memwrites_in.bits.data := Cat(
    recent_history_vec_next(31),
    recent_history_vec_next(30),
    recent_history_vec_next(29),
    recent_history_vec_next(28),
    recent_history_vec_next(27),
    recent_history_vec_next(26),
    recent_history_vec_next(25),
    recent_history_vec_next(24),
    recent_history_vec_next(23),
    recent_history_vec_next(22),
    recent_history_vec_next(21),
    recent_history_vec_next(20),
    recent_history_vec_next(19),
    recent_history_vec_next(18),
    recent_history_vec_next(17),
    recent_history_vec_next(16),
    recent_history_vec_next(15),
    recent_history_vec_next(14),
    recent_history_vec_next(13),
    recent_history_vec_next(12),
    recent_history_vec_next(11),
    recent_history_vec_next(10),
    recent_history_vec_next(9),
    recent_history_vec_next(8),
    recent_history_vec_next(7),
    recent_history_vec_next(6),
    recent_history_vec_next(5),
    recent_history_vec_next(4),
    recent_history_vec_next(3),
    recent_history_vec_next(2),
    recent_history_vec_next(1),
    recent_history_vec_next(0)
  )

  memwriter.io.memwrites_in.bits.validbytes := write_num_bytes
  memwriter.io.memwrites_in.bits.end_of_message := io.internal_commands.bits.final_command
  memwriter.io.memwrites_in.valid := fire_write.fire(memwriter.io.memwrites_in.ready)
  io.bufs_completed := memwriter.io.bufs_completed
  io.no_writes_inflight := memwriter.io.no_writes_inflight


}
