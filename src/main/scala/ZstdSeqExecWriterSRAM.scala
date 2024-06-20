package compressacc

import chisel3._
import chisel3.util._
import chisel3.{Printable, SyncReadMem}
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

class ZstdSeqExecWriterSRAM32(history_len: Int)(implicit p: Parameters) extends Module with MemoryOpConstants {
  val io = IO(new Bundle {
    // Inputs from ZstdSeqExecLoader
    val internal_commands = Flipped(Decoupled(new ZstdSeqInfo))
    val literal_chunks = Flipped(Decoupled(new LiteralChunk))
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
  
  val HIST_BUF_WIDTH = 32 // This is why the module's name is 32
  val HIST_BUF_ELEMS_PER_CHUNK = history_len/HIST_BUF_WIDTH 
  val HIST_SIZE_BYTES = HIST_BUF_WIDTH * HIST_BUF_ELEMS_PER_CHUNK
  val HIST_BUF_INDEX_WIDTH = log2Up(HIST_SIZE_BYTES)
  val BYTE_SIZE = 8
  println(s"HIST BUF WIDTH: ${HIST_BUF_WIDTH}")
  println(s"HIST BUF ELEMS PER CHUNK: ${HIST_BUF_ELEMS_PER_CHUNK}")
  println(s"TOTAL HIST BUF SIZE (B): ${HIST_SIZE_BYTES}")
  val recent_history_vec = Array.fill(HIST_BUF_WIDTH) {SyncReadMem(HIST_BUF_ELEMS_PER_CHUNK, UInt(BYTE_SIZE.W))}
  val read_indexing_vec = Wire(Vec(HIST_BUF_WIDTH, UInt(HIST_BUF_INDEX_WIDTH.W)))
  val read_ports_vec = Wire(Vec(HIST_BUF_WIDTH, UInt(BYTE_SIZE.W)))

  // shift amount to remove memindex part of addr (low # of bits required to count HIST BUF WIDTH items)
  val MEMINDEX_BITS = log2Up(HIST_BUF_WIDTH)
  // mask to get only memindex part of addr
  val MEMINDEX_MASK = (1 << MEMINDEX_BITS) - 1


  /////////// STAGE 1

  val s1_can_proceed_wire = Wire(Bool())
  val stage2_internal_command = Reg(Valid(new ZstdSeqInfo))

  // SOME STAGE2 stuff so we can peek ahead:
  val s2_has_literal = !stage2_internal_command.bits.is_match

  // END SOME STAGE2 stuff

  // where to read from from s1's perspective
  val addr_base_ptr_intermediate_s1 = RegInit(0.U(HIST_BUF_INDEX_WIDTH.W))

  // we only account for the len of a literal when it commits in the next
  // stage, so need to add it in during the same cycle for copy offset
  // correctness.
  val addr_base_ptr_s1 = Mux(s2_has_literal && stage2_internal_command.valid,
    addr_base_ptr_intermediate_s1 + io.literal_chunks.bits.chunk_size_bytes,
    addr_base_ptr_intermediate_s1)

  val offset_s1 = io.internal_commands.bits.offset

  io.internal_commands.ready := s1_can_proceed_wire

  when (s1_can_proceed_wire) {
    when (io.internal_commands.valid && io.internal_commands.bits.is_match) {
      addr_base_ptr_intermediate_s1 := addr_base_ptr_s1 + io.internal_commands.bits.ml
    } .otherwise {
      addr_base_ptr_intermediate_s1 := addr_base_ptr_s1
    }
  }

  when (s1_can_proceed_wire) {
    stage2_internal_command.bits := io.internal_commands.bits
    stage2_internal_command.valid := io.internal_commands.valid
  }

  for (elemno <- 0 until HIST_BUF_WIDTH) {
    read_ports_vec(elemno) := recent_history_vec(elemno)(read_indexing_vec(elemno))
  }

  val is_literal_s1 = !io.internal_commands.bits.is_match
  for (elemno <- 0 until HIST_BUF_WIDTH) {
    when (!is_literal_s1) {
      //is_copy
      val read_memaddr = (addr_base_ptr_s1 + io.internal_commands.bits.ml - offset_s1 - elemno.U - 1.U) >> MEMINDEX_BITS
      val read_memno = (addr_base_ptr_s1 + io.internal_commands.bits.ml - offset_s1 - elemno.U - 1.U) & MEMINDEX_MASK.U
      read_indexing_vec(read_memno) := read_memaddr
      // recent_history_vec_next(elemno) := read_ports_vec(read_memno)
      // val print_read_ports_vec = Wire(UInt(BYTE_SIZE.W))
      // print_read_ports_vec := read_ports_vec(read_memno)
      when (s1_can_proceed_wire) {
        CompressAccelLogger.logInfo("s1: issued hist_read(elemno:%d): from memno:%d,memaddr:%d\n", elemno.U, read_memno, read_memaddr)
      }
    }
  }

val is_copy_or_have_literal = stage2_internal_command.bits.is_match || (!stage2_internal_command.bits.is_match && io.literal_chunks.valid)
  // write into the recent hist + memwriter
  val fire_write_s2 = DecoupledHelper(
    stage2_internal_command.valid,
    is_copy_or_have_literal,
    memwriter.io.memwrites_in.ready
  )

  s1_can_proceed_wire := fire_write_s2.fire() || !stage2_internal_command.valid
  // This block only receives legitimate literal chunk. Doesn't receive if match, but receives some literals if !is_match.
  io.literal_chunks.ready := fire_write_s2.fire(is_copy_or_have_literal) && !stage2_internal_command.bits.is_match

  // whether it's a literal
  val is_literal_s2 = !stage2_internal_command.bits.is_match
  val literal_data = io.literal_chunks.bits.chunk_data

  // whether it's a copy
  val is_copy_s2 = !is_literal_s2

  // for literal or copy, the # of bytes to be added
  val write_num_bytes_s2 = Mux(is_literal_s2,
    io.literal_chunks.bits.chunk_size_bytes,
    // already trimmed to size by CopyExpander:
    stage2_internal_command.bits.ml)

  val offset_s2 = stage2_internal_command.bits.offset

  val write_indexing_vec = Wire(Vec(HIST_BUF_WIDTH, UInt(HIST_BUF_INDEX_WIDTH.W)))
  val write_ports_vec = Wire(Vec(HIST_BUF_WIDTH, UInt(BYTE_SIZE.W)))
  val write_ports_write_enable = Wire(Vec(HIST_BUF_WIDTH, Bool()))

  for (elemno <- 0 until HIST_BUF_WIDTH) {
    write_ports_write_enable(elemno) := false.B
  }

  for (elemno <- 0 until HIST_BUF_WIDTH) {
    when (write_ports_write_enable(elemno)) {
      recent_history_vec(elemno)(write_indexing_vec(elemno)) := write_ports_vec(elemno)
    }
  }

  val recent_history_vec_next = Wire(Vec(HIST_BUF_WIDTH, UInt(BYTE_SIZE.W)))
  val recent_history_vec_next_with_bypass = Wire(Vec(HIST_BUF_WIDTH, UInt(BYTE_SIZE.W)))
  val recent_history_vec_next_bypassed_reg = Reg(Vec(HIST_BUF_WIDTH, UInt(BYTE_SIZE.W)))


  // keep read_ports_vec data in case s2 stalls after read issued on prev cycle
  val read_ports_vec_s2_stall = Reg(Vec(HIST_BUF_WIDTH, UInt(BYTE_SIZE.W)))

  val s2_stall_in_progress = RegInit(false.B)
  val s2_stall_start = stage2_internal_command.valid && stage2_internal_command.bits.is_match && (!memwriter.io.memwrites_in.ready) && !s2_stall_in_progress

  when (s2_stall_start) {
    CompressAccelLogger.logInfo("s2: stall start\n")
    s2_stall_in_progress := true.B
  }

  when (s2_stall_in_progress) {
    CompressAccelLogger.logInfo("s2: stall in progress\n")
  }


  when (fire_write_s2.fire()) {
    s2_stall_in_progress := false.B
    when (s2_stall_in_progress) {
      CompressAccelLogger.logInfo("s2: stall ending\n")
    }
  }

  for (elemno <- 0 until HIST_BUF_WIDTH) {
    when (s2_stall_start) {
      read_ports_vec_s2_stall(elemno) := read_ports_vec(elemno)
    }
  }




  val last_cycle_bytes_written = RegInit(0.U(64.W))
  when (fire_write_s2.fire()) {
    last_cycle_bytes_written := write_num_bytes_s2
  } .elsewhen (!stage2_internal_command.valid) {
    last_cycle_bytes_written := 0.U
  }

  for (elemno <- 0 until HIST_BUF_WIDTH) {
    // default
    recent_history_vec_next_with_bypass(elemno.U) := recent_history_vec_next(elemno.U)
    when (fire_write_s2.fire) {
      val rhvn_currentval = recent_history_vec_next(elemno.U)
      val rhvnbR_currentval = recent_history_vec_next_bypassed_reg(elemno.U)
      CompressAccelLogger.logInfo("s2: PRE-BYPASS              rhvn(elemno:%d) contains val:0x%x\n", elemno.U, rhvn_currentval)
      CompressAccelLogger.logInfo("s2: AVAILABLE-BYPASS-VALS rhvnbR(elemno:%d) contains val:0x%x\n", elemno.U, rhvnbR_currentval)
    }
    when (!is_literal_s2) {
      // END INDEX AND START INDEX ARE FROM THE PERSPECTIVE OF THE BYPASS REG
      // end_index = 0
      val end_index = stage2_internal_command.bits.offset - stage2_internal_command.bits.ml
      // start_index = 8: 8 is not > 9, so copy_offset, so 8
      val start_index = Mux(stage2_internal_command.bits.offset > last_cycle_bytes_written,
        last_cycle_bytes_written,
        stage2_internal_command.bits.offset)
      // 0 < 9
      when (end_index < last_cycle_bytes_written) {


        // if elemno > (8 - 8)
        when ((elemno.U >= (end_index-end_index)) && (elemno.U < (start_index-end_index))) {
          val rhvnwb_index = elemno.U
          val rhvnbR_index = elemno.U + (stage2_internal_command.bits.offset - stage2_internal_command.bits.ml)
          val replaced_value = recent_history_vec_next(rhvnwb_index)
          val bypass_value = recent_history_vec_next_bypassed_reg(rhvnbR_index)
          recent_history_vec_next_with_bypass(rhvnwb_index) := bypass_value
          when (fire_write_s2.fire) {
            CompressAccelLogger.logInfo("s2: bypassing rhvnwb(elemno:%d): replace rhvn(elemno:%d) containing val:0x%x with rhvnbR(elemno:%d) containing val:0x%x\n", rhvnwb_index, rhvnwb_index, replaced_value, rhvnbR_index, bypass_value)
          }
        }
      }
    }
  }

  // for literal writes, advance pointer and write to the mems
  // for copies, "wrap" stage which maps mem's output to mem's input: this is offset % 16
  //    addr for mem, which is offset >> 4
  val addr_base_ptr_s2 = RegInit(0.U(HIST_BUF_INDEX_WIDTH.W))

  for (elemno <- 0 until HIST_BUF_WIDTH) {
    when (is_literal_s2) {
      recent_history_vec_next(write_num_bytes_s2 - elemno.U - 1.U) := literal_data(((elemno+1) << 3) - 1, elemno << 3)
    } .otherwise {
      //is_copy
      val read_memaddr = (addr_base_ptr_s2 + write_num_bytes_s2 - offset_s2 - elemno.U - 1.U) >> MEMINDEX_BITS
      val read_memno = (addr_base_ptr_s2 + write_num_bytes_s2 - offset_s2 - elemno.U - 1.U) & MEMINDEX_MASK.U
      when (s2_stall_in_progress) {
        recent_history_vec_next(elemno) := read_ports_vec_s2_stall(read_memno)
      } .otherwise {
        recent_history_vec_next(elemno) := read_ports_vec(read_memno)
      }
      val print_read_ports_vec = Wire(UInt(BYTE_SIZE.W))
      when (s2_stall_in_progress) {
        print_read_ports_vec := read_ports_vec_s2_stall(read_memno)
      } .otherwise {
        print_read_ports_vec := read_ports_vec(read_memno)
      }
      when (fire_write_s2.fire) {
        CompressAccelLogger.logInfo("s2: rhvn(elemno:%d): from memno:%d,memaddr:%d = val:0x%x\n", elemno.U, read_memno, read_memaddr, print_read_ports_vec)
      }
    }
  }

  for (elemno <- 0 until HIST_BUF_WIDTH) {
    when (fire_write_s2.fire && (elemno.U(MEMINDEX_BITS.W) < write_num_bytes_s2)) {
      val full_address = addr_base_ptr_s2 + write_num_bytes_s2 - elemno.U - 1.U
      val memno = full_address & (MEMINDEX_MASK).U
      val memaddr = full_address >> MEMINDEX_BITS
      write_indexing_vec(memno) := memaddr
      write_ports_vec(memno) := recent_history_vec_next_with_bypass(elemno)
      write_ports_write_enable(memno) := true.B
      val print_recent_history_vec = Wire(UInt(BYTE_SIZE.W))
      //recent_history_vec_next(elemno))
      print_recent_history_vec := recent_history_vec_next_with_bypass(elemno)
      CompressAccelLogger.logInfo("s2: mem(memno:%d,memaddr:%d): from rhvnwb(elemno:%d) = val:0x%x\n", memno, memaddr, elemno.U, print_recent_history_vec)
    }
  }

  when (fire_write_s2.fire) {
    addr_base_ptr_s2 := addr_base_ptr_s2 + write_num_bytes_s2
    recent_history_vec_next_bypassed_reg := recent_history_vec_next_with_bypass
  }

  memwriter.io.memwrites_in.bits.data := Cat(recent_history_vec_next_with_bypass.reverse)

  memwriter.io.memwrites_in.bits.validbytes := write_num_bytes_s2
  memwriter.io.memwrites_in.bits.end_of_message := stage2_internal_command.bits.is_final_command
  memwriter.io.memwrites_in.valid := fire_write_s2.fire(memwriter.io.memwrites_in.ready)
  io.bufs_completed := memwriter.io.bufs_completed
  io.no_writes_inflight := memwriter.io.no_writes_inflight
}
