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

/* This unit gets the following outputs of ZstdSeqExecLoader as inputs:
command_out = Decoupled(new ZstdSeqInfo)
literal_chunk = Decoupled(new literal_chunk)
*/

class ZstdSeqExecWriter32(history_len: Int)(implicit p: Parameters) extends Module with MemoryOpConstants {
  val io = IO(new Bundle {
    // Inputs from ZstdSeqExecLoader
    val internal_commands = Flipped(Decoupled(new ZstdSeqInfo))
    val literal_chunks = Flipped(Decoupled(new LiteralChunk))
    val final_command = Flipped(Decoupled(Bool()))
    // Input from ZstdSeqExecDecoder
    val decompress_dest_info = Flipped(Decoupled(new SnappyDecompressDestInfo))

    // Output to outer mem
    val l2helperUser = new L2MemHelperBundle
    // Feedback outputs to ZstdSeqExecDecoder
    val bufs_completed = Output(UInt(64.W))
    val no_writes_inflight = Output(Bool())
  })

  val memwriter = Module(new ZstdSeqExecMemwriter32)
  io.l2helperUser <> memwriter.io.l2io
  memwriter.io.decompress_dest_info <> io.decompress_dest_info

  val is_copy_or_have_literal = io.internal_commands.bits.is_match || (!io.internal_commands.bits.is_match && io.literal_chunks.valid)

  // write into the recent hist + memwriter
  val fire_write = DecoupledHelper(
    io.internal_commands.valid,
    is_copy_or_have_literal,
    memwriter.io.memwrites_in.ready
  )

  io.internal_commands.ready := fire_write.fire(io.internal_commands.valid)

  // Just receive dummy value in literal_chunks and don't use it when is_match=True
  // Update: this block only receives legitimate literal chunk. Doesn't receive if match, not literal.
  io.literal_chunks.ready := fire_write.fire(is_copy_or_have_literal) && !io.internal_commands.bits.is_match
  // io.final_command.ready := io.internal_commands.ready

  // whether it's a literal
  val is_literal = !io.internal_commands.bits.is_match
  val literal_data = io.literal_chunks.bits.chunk_data

  // whether it's a copy
  val is_copy = !is_literal

  // for literal or copy, the # of bytes to be added
  val write_num_bytes = Mux(is_literal,
    io.literal_chunks.bits.chunk_size_bytes,
    // already trimmed to size by CopyExpander:
    io.internal_commands.bits.ml)

  val offset = io.internal_commands.bits.offset

  val recent_history_vec = Array.fill(32) {Mem(history_len/32, UInt(8.W))}//4096
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
      recent_history_vec_next(write_num_bytes - elemno.U - 1.U) := literal_data(((elemno+1) << 3) - 1, elemno << 3)
    } .otherwise {//is_copy
      val read_memaddr = (addr_base_ptr + write_num_bytes - offset - elemno.U - 1.U) >> 5
      val read_memno = (addr_base_ptr + write_num_bytes - offset - elemno.U - 1.U) & (0x1F).U
      read_indexing_vec(read_memno) := read_memaddr
      recent_history_vec_next(elemno) := read_ports_vec(read_memno)
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
  memwriter.io.memwrites_in.bits.end_of_message := io.internal_commands.bits.is_final_command
  memwriter.io.memwrites_in.valid := fire_write.fire(memwriter.io.memwrites_in.ready)

  io.bufs_completed := memwriter.io.bufs_completed
  io.no_writes_inflight := memwriter.io.no_writes_inflight
}

class ZstdSeqExecWriter16(history_len: Int)(implicit p: Parameters) extends Module with MemoryOpConstants {
  val io = IO(new Bundle {
    // Inputs from ZstdSeqExecLoader
    val internal_commands = Flipped(Decoupled(new ZstdSeqInfo))
    val literal_chunks = Flipped(Decoupled(new LiteralChunk))
    // val final_command = (Decoupled(Bool())).flip
    // Input from ZstdSeqExecDecoder
    val decompress_dest_info = Flipped(Decoupled(new SnappyDecompressDestInfo))

    // Output to outer mem
    val l2helperUser = new L2MemHelperBundle
    // Feedback outputs to ZstdSeqExecDecoder
    val bufs_completed = Output(UInt(64.W))
    val no_writes_inflight = Output(Bool())
  })
  val memwriter = Module(new ZstdSeqExecMemwriter)
  io.l2helperUser <> memwriter.io.l2io
  memwriter.io.decompress_dest_info <> io.decompress_dest_info

  val is_copy_or_have_literal = io.internal_commands.bits.is_match || (!io.internal_commands.bits.is_match && io.literal_chunks.valid)

  // write into the recent hist + memwriter
  val fire_write = DecoupledHelper(
    io.internal_commands.valid,
    is_copy_or_have_literal,
    memwriter.io.memwrites_in.ready
  )

  io.internal_commands.ready := fire_write.fire(io.internal_commands.valid)
  // Just receive dummy value in literal_chunks and don't use it when is_match=True
  // Update: this block only receives legitimate literal chunk. Doesn't receive if match, not literal.
  io.literal_chunks.ready := fire_write.fire(is_copy_or_have_literal) && !io.internal_commands.bits.is_match
  // io.final_command.ready := io.internal_commands.ready

  // whether it's a literal
  val is_literal = !io.internal_commands.bits.is_match
  val literal_data = io.literal_chunks.bits.chunk_data

  // whether it's a copy
  val is_copy = !is_literal

  // for literal or copy, the # of bytes to be added
  val write_num_bytes = Mux(is_literal,
    io.literal_chunks.bits.chunk_size_bytes,
    // already trimmed to size by CopyExpander:
    io.internal_commands.bits.ml)

  val offset = io.internal_commands.bits.offset

  val recent_history_vec = Array.fill(16) {Mem(history_len/16, UInt(8.W))}//4096
  val read_indexing_vec = Wire(Vec(16, UInt(16.W)))
  val read_ports_vec = Wire(Vec(16, UInt(8.W)))
  val write_indexing_vec = Wire(Vec(16, UInt(16.W)))
  val write_ports_vec = Wire(Vec(16, UInt(8.W)))
  val write_ports_write_enable = Wire(Vec(16, Bool()))

  for (elemno <- 0 until 16) {
    write_ports_write_enable(elemno) := false.B
  }

  for (elemno <- 0 until 16) {
    read_ports_vec(elemno) := recent_history_vec(elemno)(read_indexing_vec(elemno))
    when (write_ports_write_enable(elemno)) {
      recent_history_vec(elemno)(write_indexing_vec(elemno)) := write_ports_vec(elemno)
    }
  }

  val recent_history_vec_next = Wire(Vec(16, UInt(8.W)))
  val addr_base_ptr = RegInit(0.U(16.W))

  // for literal writes, advance pointer and write to the mems
  // for copies, "wrap" stage which maps mem's output to mem's input: this is offset % 16
  //    addr for mem, which is offset >> 4

  for (elemno <- 0 until 16) {
    when (is_literal) {
      recent_history_vec_next(write_num_bytes - elemno.U - 1.U) := literal_data(((elemno+1) << 3) - 1, elemno << 3)
    } .otherwise {//is_copy
      val read_memaddr = (addr_base_ptr + write_num_bytes - offset - elemno.U - 1.U) >> 4
      val read_memno = (addr_base_ptr + write_num_bytes - offset - elemno.U - 1.U) & (0xF).U
      read_indexing_vec(read_memno) := read_memaddr
      recent_history_vec_next(elemno) := read_ports_vec(read_memno)
    }
  }

  for (elemno <- 0 until 16) {
    when (fire_write.fire && (elemno.U(5.W) < write_num_bytes)) {
      val full_address = addr_base_ptr + write_num_bytes - elemno.U - 1.U
      val memno = full_address & (0xF).U
      val memaddr = full_address >> 4
      write_indexing_vec(memno) := memaddr
      write_ports_vec(memno) := recent_history_vec_next(elemno)
      write_ports_write_enable(memno) := true.B
    }
  }

  when (fire_write.fire) {
    addr_base_ptr := addr_base_ptr + write_num_bytes
  }

  memwriter.io.memwrites_in.bits.data := Cat(
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
  memwriter.io.memwrites_in.bits.end_of_message := io.internal_commands.bits.is_final_command
  memwriter.io.memwrites_in.valid := fire_write.fire(memwriter.io.memwrites_in.ready)

  io.bufs_completed := memwriter.io.bufs_completed
  io.no_writes_inflight := memwriter.io.no_writes_inflight
}
