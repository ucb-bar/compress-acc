package compressacc

import chisel3._
import chisel3.util._
import chisel3.util._
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheArbiter}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._

class HufDecompressorLiteralExpanderIO(val decomp_at_once: Int)(implicit p: Parameters) 
extends Bundle {
  val literal_src_info_in = Flipped(Decoupled(new StreamInfo))
  val literal_cmd = Flipped(Decoupled(new HufDecompressLiteralExpanderCommand))

  val literal_src_info_out = Decoupled(new StreamInfo)
  val src_stream = Flipped(new MemLoaderConsumerBundle)

  val rawrle_src_info_out = Decoupled(new StreamInfo)
  val rawrle_stream = Flipped(new MemLoaderConsumerBundle)

  val lookup_idx = Vec(decomp_at_once, Decoupled(new HufDecompressorLookupIdx))
  val dic_entry = Vec(decomp_at_once, Flipped(Decoupled(new HufDecompressorDicEntry)))

  val memwrites_out = Decoupled(new WriterBundle)

  val literal_expander_done = Decoupled(Bool())

  val decompressed_bytes = Flipped(Decoupled(UInt(64.W)))
}
class WriterBundleWithRAWFlag extends Bundle{
  val writerbundle = new WriterBundle
  val raw_flag = Bool()
}

class HufDecompressorLiteralExpander(val decomp_at_once: Int, val cmd_que_depth: Int)
(implicit p: Parameters) extends Module {
  val io = IO(new HufDecompressorLiteralExpanderIO(decomp_at_once))

  val decomp_at_once_log2 = log2Ceil(decomp_at_once + 1)

  val SBUS_BYTES = 32
  val SBUS_BYTES_LOG2 = log2Ceil(SBUS_BYTES + 1)
  val SBUS_BITS = SBUS_BYTES * 8
  val SBUS_BITS_LOG2 = log2Ceil(SBUS_BITS + 1)
  val HUF_MAX_SYMBOL_BITS = 11

  val MAX_NUMBITS_AT_ONCE = HUF_MAX_SYMBOL_BITS * decomp_at_once
  val MAX_NUMBITS_AT_ONCE_LOG2 = log2Ceil(MAX_NUMBITS_AT_ONCE + 1)


  val STATE_IDLE = 0.U
  val STATE_READ_SRC = 1.U
  val STATE_REMOVE_PADDING = 2.U
  val STATE_DECOMPRESS_LOOKUP = 3.U
  val STATE_DECOMPRESS_SELECT_VALID = 4.U
  val STATE_DECOMPRESS_SHIFT = 5.U
  val STATE_STREAM_DONE = 6.U
  val STATE_PROCESS_RAW = 7.U
  val STATE_PROCESS_RLE = 8.U
  val STATE_DONE = 9.U

  val state = RegInit(0.U(4.W))

  val max_bits_width = log2Ceil(11+1) + 1
  val max_bits_zero_extend_signed = 0.U((max_bits_width+1).W)

  val max_bits = RegInit(0.U(max_bits_width.W))
  val dic_idx_mask = RegInit(0.U(12.W))
  val bit_offset = RegInit(0.S(64.W))
  val decompressed_stream_cnt = RegInit(0.U(3.W))

  val decompressed_size_q = Module(new Queue(UInt(64.W), cmd_que_depth)).io
  decompressed_size_q.enq <> io.decompressed_bytes
  decompressed_size_q.deq.ready := (state === STATE_DONE)

  val literal_cmd_q = Module(new Queue(new HufDecompressLiteralExpanderCommand, cmd_que_depth)).io
  literal_cmd_q.enq <> io.literal_cmd

  val literal_src_info_in_q = Module(new Queue(new StreamInfo, cmd_que_depth)).io
  literal_src_info_in_q.enq <> io.literal_src_info_in

  val literal_src_info_out_q = Module(new Queue(new StreamInfo, cmd_que_depth)).io
  io.literal_src_info_out <> literal_src_info_out_q.deq
  literal_src_info_out_q.enq <> literal_src_info_in_q.deq
  // src_info for RLE or RAW blocks goes to rawrle_src_info_out_q below,
  // so it should not go into literal_src_info_out_q. 
  val block_type = RegInit(0.U(2.W))
  block_type := io.literal_cmd.bits.block_type
  literal_src_info_out_q.enq.valid := literal_src_info_in_q.deq.valid && 
    block_type(1)===1.U //not RAW or RLE

  val rawrle_src_info_out_q = Module(new Queue(new StreamInfo, cmd_que_depth)).io
  io.rawrle_src_info_out <> rawrle_src_info_out_q.deq
  rawrle_src_info_out_q.enq.valid := false.B
  rawrle_src_info_out_q.enq.bits.ip := 0.U
  rawrle_src_info_out_q.enq.bits.isize := 0.U

  literal_cmd_q.deq.ready := (state === STATE_DONE || state === STATE_STREAM_DONE)

  io.src_stream.output_ready := false.B
  io.src_stream.user_consumed_bytes := 0.U

  io.rawrle_stream.output_ready := false.B
  io.rawrle_stream.user_consumed_bytes := 0.U


  val written_bytes = RegInit(0.U(64.W))
  val memwrites_out_q = Module(new Queue(new WriterBundleWithRAWFlag, cmd_que_depth)).io
  io.memwrites_out.bits <> memwrites_out_q.deq.bits.writerbundle
  io.memwrites_out.valid := memwrites_out_q.deq.valid
  memwrites_out_q.deq.ready := io.memwrites_out.ready
  memwrites_out_q.enq.bits.raw_flag := false.B
  

  val rle_byte = RegInit(0.U(8.W))
  val rle_byte_set = RegInit(false.B)
  val rle_sent_bytes = RegInit(0.U(64.W))

  val literal_expander_done_q = Module(new Queue(Bool(), cmd_que_depth)).io
  io.literal_expander_done <> literal_expander_done_q.deq
  literal_expander_done_q.enq.valid := false.B
  literal_expander_done_q.enq.bits := false.B

  val max_bits_mask = Wire(UInt(HUF_MAX_SYMBOL_BITS.W))
  max_bits_mask := (1.U << max_bits) - 1.U

  val avail_bytes = io.src_stream.available_output_bytes
  val avail_bits = avail_bytes << 3.U
  val src_output = io.src_stream.output_data
  val last_chunk = io.src_stream.output_last_chunk
  val src_stream_consumed_bits = RegInit(0.U(SBUS_BITS_LOG2.W))

  val max_bits_per_lookup = (HUF_MAX_SYMBOL_BITS+decomp_at_once-1).U(8.W)
  val max_bytes_per_lookup = (max_bits_per_lookup >> 3.U) + 1.U
  val stop_speculate = last_chunk && (avail_bytes <= max_bytes_per_lookup)

  val msb = WireInit(0.U(8.W))

  val spec_symbols = WireInit(VecInit(Seq.fill(decomp_at_once)(0.U(16.W))))
  val top_offset_bits = WireInit(VecInit(Seq.fill(decomp_at_once)(0.U(SBUS_BITS_LOG2.W))))
  for (i <- 0 until decomp_at_once) {
    top_offset_bits(i) := src_stream_consumed_bits + max_bits + i.U
    spec_symbols(i) := (src_output >> (SBUS_BITS.U - top_offset_bits(i))) & max_bits_mask
  }

  dontTouch(spec_symbols)
  dontTouch(top_offset_bits)

  val top_max_bits = WireInit(0.U(16.W))
  val fill_zero_bits = WireInit(0.U(4.W))
  val bits_to_read = WireInit(0.U(4.W))

  val debug_bit_offset_asUInt = bit_offset.asUInt
  dontTouch(debug_bit_offset_asUInt)

  val max_bits_signed = (max_bits | max_bits_zero_extend_signed).asSInt
  val overflow_condition = max_bits + src_stream_consumed_bits > avail_bits
  val termination_condition = bit_offset + max_bits_signed <= 0.S
  dontTouch(max_bits_signed)
  dontTouch(overflow_condition)
  dontTouch(termination_condition)

  when (overflow_condition) {
    bits_to_read := avail_bits - src_stream_consumed_bits
    fill_zero_bits := max_bits - bits_to_read

    val mask_up = (1.U << bits_to_read) - 1.U
    top_max_bits := ((src_output >> (SBUS_BITS.U - avail_bits)) & mask_up) << fill_zero_bits
  } .otherwise {
    top_max_bits := spec_symbols(0)
  }

  val lookup_idx_q = Seq.fill(decomp_at_once)(Module(new Queue(new HufDecompressorLookupIdx, cmd_que_depth)).io)

  val insert_end_idx_q = Module(new Queue(UInt(decomp_at_once_log2.W), cmd_que_depth)).io

  for (i <- 0 until decomp_at_once) {
    io.lookup_idx(i) <> lookup_idx_q(i).deq
  }


  val lookup_idx_enq_fire = (0 until decomp_at_once).map{ i =>
    DecoupledHelper(lookup_idx_q(i).enq.ready,
                    state === STATE_DECOMPRESS_LOOKUP,
                    io.src_stream.output_valid,
                    insert_end_idx_q.enq.ready)
  }

  val lookup_idx_enq_valid = lookup_idx_enq_fire.zipWithIndex.map { case(f, idx) =>
    val x = WireInit(false.B)
    when (top_offset_bits(idx) <= avail_bits) {
      x := f.fire(lookup_idx_q(idx).enq.ready)
    } .otherwise {
      x := (state === STATE_DECOMPRESS_LOOKUP)
    }
    x
  }.reduce(_ && _)

  val insert_idx_enq_valid = lookup_idx_enq_fire.zipWithIndex.map { case(f, idx) =>
    val x = WireInit(false.B)
    when (top_offset_bits(idx) <= avail_bits) {
      x := f.fire(insert_end_idx_q.enq.ready)
    } .otherwise {
      x := (state === STATE_DECOMPRESS_LOOKUP)
    }
    x
  }.reduce(_ && _)

  val lookup_idx_enq_fire_success = lookup_idx_enq_fire.zipWithIndex.map { case(f, idx) =>
    val x = WireInit(false.B)
    when (top_offset_bits(idx) <= avail_bits) {
      x := f.fire
    } .otherwise {
      x := (state === STATE_DECOMPRESS_LOOKUP)
    }
    x
  }.reduce(_ && _)

  val lookup_fired_cnt = lookup_idx_enq_fire.map(_.fire).map(_.asUInt).reduce(_ +& _)

  val last_chunk_lookup_idx_q_fire = DecoupledHelper(lookup_idx_q(0).enq.ready,
                                                     state === STATE_DECOMPRESS_LOOKUP,
                                                     io.src_stream.output_valid,
                                                     insert_end_idx_q.enq.ready)

  // stop speculation when last_chunk is set
  when (stop_speculate) {
    lookup_idx_q(0).enq.valid := last_chunk_lookup_idx_q_fire.fire(lookup_idx_q(0).enq.ready)
    lookup_idx_q(0).enq.bits.idx := top_max_bits

    insert_end_idx_q.enq.valid := last_chunk_lookup_idx_q_fire.fire(insert_end_idx_q.enq.ready)
    insert_end_idx_q.enq.bits := 1.U

    for (i <- 1 until decomp_at_once) {
      lookup_idx_q(i).enq.valid := false.B
      lookup_idx_q(i).enq.bits.idx := 0.U
    }
  } .otherwise {
    for (i <- 0 until decomp_at_once) {
      val use_this_q = top_offset_bits(i) <= avail_bits
      lookup_idx_q(i).enq.valid := use_this_q && lookup_idx_enq_valid
      lookup_idx_q(i).enq.bits.idx := spec_symbols(i)

      insert_end_idx_q.enq.valid := insert_idx_enq_valid
      insert_end_idx_q.enq.bits := lookup_fired_cnt
    }
  }

  /////////////////////////////////////////////////////////////////////////////
  require(SBUS_BYTES >= decomp_at_once)
  val decomp_symbol_q = Seq.fill(SBUS_BYTES)(Module(new Queue(UInt(8.W), cmd_que_depth)).io)
  val all_decomp_symbol_stage_q_ready = decomp_symbol_q.map(_.enq.ready).reduce(_ && _)
  


  val dic_entry_q = Seq.fill(decomp_at_once)(Module(new Queue(new HufDecompressorDicEntry, cmd_que_depth)).io)
  val dic_entry_deq_fire = (0 until decomp_at_once).map { i =>
    DecoupledHelper(dic_entry_q(i).deq.valid,
                    state === STATE_DECOMPRESS_SELECT_VALID,
                    insert_end_idx_q.deq.valid,
                    all_decomp_symbol_stage_q_ready)
  }

  val dic_entry_all_fire = dic_entry_deq_fire.zipWithIndex.map { case(f, idx) =>
    val x = WireInit(false.B)
    when (idx.U < insert_end_idx_q.deq.bits) {
      x := f.fire
    } .otherwise {
      x := (state === STATE_DECOMPRESS_SELECT_VALID)
    }
    x
  }.reduce(_ && _)

  val dic_entry_deq_ready = dic_entry_deq_fire.zipWithIndex.map { case(f, idx) =>
    val x = WireInit(false.B)
    when (idx.U < insert_end_idx_q.deq.bits) {
      x := f.fire(dic_entry_q(idx).deq.valid)
    } .otherwise {
      x := (state === STATE_DECOMPRESS_SELECT_VALID)
    }
    x
  }.reduce(_ && _)

  val insert_end_idx_q_deq_ready = dic_entry_deq_fire.zipWithIndex.map { case(f, idx) =>
    val x = WireInit(false.B)
    when (idx.U < insert_end_idx_q.deq.bits) {
      x := f.fire(insert_end_idx_q.deq.valid)
    } .otherwise {
      x := (state === STATE_DECOMPRESS_SELECT_VALID)
    }
    x
  }.reduce(_ && _)

  for (i <- 0 until decomp_at_once) {
    dic_entry_q(i).enq <> io.dic_entry(i)
  }

  val last_chunk_dic_entry_deq_fire = DecoupledHelper(dic_entry_q(0).deq.valid,
                                                      state === STATE_DECOMPRESS_SELECT_VALID,
                                                      insert_end_idx_q.deq.valid,
                                                      stop_speculate)

  for (i <- 0 until decomp_at_once) {
    val use_this_dic = i.U < insert_end_idx_q.deq.bits
    dic_entry_q(i).deq.ready :=  use_this_dic && dic_entry_deq_ready
    insert_end_idx_q.deq.ready := insert_end_idx_q_deq_ready
  }

  val numbit_cumul_bits =  8 + HUF_MAX_SYMBOL_BITS + decomp_at_once - 1
  val numbit_cumul_bits_log2 = log2Ceil(numbit_cumul_bits+1)
  val byte_mask = Wire(UInt(8.W))
  byte_mask := ((1 << 8) - 1).U

  val numbit_cat = Wire(UInt((8*decomp_at_once).W))
  numbit_cat := Cat(dic_entry_q.map(_.deq.bits.numbit).reverse)

  val numbit_cumul = WireInit(VecInit(Seq.fill(decomp_at_once+1)(0.U(numbit_cumul_bits_log2.W))))
  numbit_cumul(0) := dic_entry_q(0).deq.bits.numbit
  for (i <- 1 until decomp_at_once + 1) {
    when (numbit_cumul(i-1) < decomp_at_once.U && (i.U === numbit_cumul(i-1))) {
      val cur_numbit = (numbit_cat >> (numbit_cumul(i-1) * 8.U)) & byte_mask
      numbit_cumul(i) := numbit_cumul(i-1) + cur_numbit
    } .otherwise {
      numbit_cumul(i) := numbit_cumul(i-1)
    }
  }

  val valid_cat = Wire(UInt(decomp_at_once.W))
  valid_cat := Cat(dic_entry_q.map(_.deq.valid.asUInt).reverse)

  val speculate_success = WireInit(VecInit(Seq.fill(decomp_at_once)(0.U(1.W))))
  speculate_success(0) := dic_entry_q(0).deq.valid.asUInt
  for (i <- 0 until decomp_at_once) {
    when (numbit_cumul(i) < insert_end_idx_q.deq.bits) { 
      speculate_success(numbit_cumul(i)) := (valid_cat >> numbit_cumul(i)) & 1.U
    }
  }
  dontTouch(speculate_success)

  val speculate_success_cnt = speculate_success.reduce(_ +& _)
  val speculate_success_insert_idx = WireInit(VecInit(Seq.fill(decomp_at_once)(0.U(decomp_at_once_log2.W))))
  speculate_success_insert_idx(0) := speculate_success(0)
  for (i <- 1 until decomp_at_once) {
    speculate_success_insert_idx(i) := speculate_success_insert_idx(i-1) + speculate_success(i)
  }
  dontTouch(speculate_success_cnt)
  dontTouch(speculate_success_insert_idx)

  val valid_numbits_consumed = Wire(UInt(MAX_NUMBITS_AT_ONCE_LOG2.W))
  valid_numbits_consumed := dic_entry_q.zip(speculate_success).map { case(entry, success) =>
    Mux(entry.deq.valid && (success > 0.U), entry.deq.bits.numbit, 0.U)
  }.reduce (_ +& _)

  val MAX_NUMBITS_AT_ONCE_LOG2_P1 = MAX_NUMBITS_AT_ONCE_LOG2 + 1
  val zero_extend_unsigned = 0.U(MAX_NUMBITS_AT_ONCE_LOG2_P1.W)

  val debug_last_chunk_dic_entry_deq_fire = last_chunk_dic_entry_deq_fire.fire
  dontTouch(debug_last_chunk_dic_entry_deq_fire)

  val debug_dic_entry_all_fire = dic_entry_all_fire
  dontTouch(debug_dic_entry_all_fire)

  when (last_chunk_dic_entry_deq_fire.fire) {
    val q0_numbit = dic_entry_q(0).deq.bits.numbit
    src_stream_consumed_bits := src_stream_consumed_bits + q0_numbit
    bit_offset := bit_offset - (zero_extend_unsigned | q0_numbit).asSInt
  } .elsewhen (dic_entry_all_fire) {
    src_stream_consumed_bits := src_stream_consumed_bits + valid_numbits_consumed
    bit_offset := bit_offset - (zero_extend_unsigned | valid_numbits_consumed).asSInt
  }

  dontTouch(bit_offset)

  // From decomp_symbol_q
  val write_start_idx = RegInit(0.U(SBUS_BYTES_LOG2.W))
  val write_len = speculate_success_cnt
  val write_end_idx_wide = write_start_idx +& speculate_success_cnt
  val write_end_idx_end = write_end_idx_wide % SBUS_BYTES.U
  val wrapped = write_end_idx_wide >= SBUS_BYTES.U

  dontTouch(write_start_idx)
  dontTouch(write_len)
  dontTouch(write_end_idx_wide)
  dontTouch(write_end_idx_end)
  dontTouch(wrapped)

  when (dic_entry_all_fire) {
    write_start_idx := write_end_idx_end
  }


  val dic_to_deq = WireInit(VecInit(Seq.fill(SBUS_BYTES)(0.U(decomp_at_once_log2.W))))
  val dic_to_deq_valid = WireInit(VecInit(Seq.fill(SBUS_BYTES)(false.B)))
  for (i <- 0 until SBUS_BYTES) {
    dic_to_deq(i) := 0.U
    dic_to_deq_valid(i) := false.B
  }

  for (i <- 0 until decomp_at_once) {
    when (speculate_success(i) > 0.U) {
      val idx_wide = (speculate_success_insert_idx(i) -& 1.U) +& write_start_idx
      val idx_wrap = idx_wide % SBUS_BYTES.U
      dic_to_deq(idx_wrap) := i.U
      dic_to_deq_valid(idx_wrap) := true.B
    }
  }

  val decomp_symbol = WireInit(VecInit(Seq.fill(decomp_at_once)(0.U(8.W))))
  for (i <- 0 until decomp_at_once) {
    decomp_symbol(i) := dic_entry_q(i).deq.bits.symbol
  }

  for (i <- 0 until SBUS_BYTES) {
    decomp_symbol_q(i).enq.valid := false.B
    decomp_symbol_q(i).enq.bits := 0.U
  }

  for (i <- 0 until SBUS_BYTES) {
    when (dic_to_deq_valid(i)) {
      decomp_symbol_q(i).enq.valid := dic_to_deq_valid(i)
      decomp_symbol_q(i).enq.bits := decomp_symbol(dic_to_deq(i))
    }
  }

  val valid_decomp_symbol_cnt = decomp_symbol_q.map(_.deq.valid.asUInt).reduce(_ +& _)

  val read_start_idx = RegInit(0.U(SBUS_BYTES_LOG2.W))

  val write_symbol_fire = (0 until SBUS_BYTES).map { idx =>
    DecoupledHelper(decomp_symbol_q(idx).deq.valid,
                    memwrites_out_q.enq.ready,
                    valid_decomp_symbol_cnt === SBUS_BYTES.U)
  }

  val decomp_symbol_deq_all_fire = write_symbol_fire.zipWithIndex.map { case(f, idx) =>
    f.fire(decomp_symbol_q(idx).deq.valid)
  }.reduce(_ && _)

  val memwrites_enq_all_fire = write_symbol_fire.zipWithIndex.map { case(f, idx) =>
    f.fire(memwrites_out_q.enq.ready)
  }.reduce(_ && _)

  val last_chunk_write_symbol_fire = (0 until SBUS_BYTES).map { idx =>
    DecoupledHelper(decomp_symbol_q(idx).deq.valid,
                    memwrites_out_q.enq.ready,
                    stop_speculate)
  }

  val last_chunk_memwrite_enq_fire = last_chunk_write_symbol_fire.zipWithIndex.map { case(f, idx) => 
    f.fire(memwrites_out_q.enq.ready)
  }.reduce(_ || _)


  when (last_chunk_memwrite_enq_fire) {
    read_start_idx := (read_start_idx +& valid_decomp_symbol_cnt) % SBUS_BYTES.U
  }

  when (!stop_speculate) {
    memwrites_out_q.enq.valid := memwrites_enq_all_fire
    for (i <- 0 until SBUS_BYTES) {
      decomp_symbol_q(i).deq.ready := decomp_symbol_deq_all_fire
    }
  } .otherwise {
    memwrites_out_q.enq.valid := last_chunk_memwrite_enq_fire
    for (i <- 0 until SBUS_BYTES) {
      decomp_symbol_q(i).deq.ready := last_chunk_write_symbol_fire(i).fire(decomp_symbol_q(i).deq.valid)
    }
  }


  val remapped_symbols = WireInit(VecInit(Seq.fill(SBUS_BYTES)(0.U(8.W))))
  for (i <- 0 until SBUS_BYTES) {
    val insert_idx = (SBUS_BYTES.U - read_start_idx + i.U) % SBUS_BYTES.U
    remapped_symbols(insert_idx) := decomp_symbol_q(i).deq.bits
  }

  val write_data = Wire(UInt(SBUS_BITS.W))
  write_data := Cat(remapped_symbols.reverse)

  val nxt_written_bytes = written_bytes + valid_decomp_symbol_cnt

  memwrites_out_q.enq.bits.writerbundle.data := write_data
  memwrites_out_q.enq.bits.writerbundle.validbytes := valid_decomp_symbol_cnt
  memwrites_out_q.enq.bits.writerbundle.end_of_message := (nxt_written_bytes >= decompressed_size_q.deq.bits)
  //TODO: &&state===STATE_DONE

  when (memwrites_out_q.deq.fire && !memwrites_out_q.deq.bits.raw_flag) {
    written_bytes := written_bytes + memwrites_out_q.deq.bits.writerbundle.validbytes
    CompressAccelLogger.logInfo("Bytes sent to be written: %d\n", written_bytes + memwrites_out_q.deq.bits.writerbundle.validbytes)
  }

  when (io.memwrites_out.fire) {
    CompressAccelLogger.logInfo("write vaild bytes: %d\n", io.memwrites_out.bits.validbytes)
    for (i <- 0 until SBUS_BYTES) {
      CompressAccelLogger.logInfo("memwrites_out: %d\n", io.memwrites_out.bits.data)
    }
  }

  val debug_write_symbols = WireInit(VecInit(Seq.fill(SBUS_BYTES)(0.U(8.W))))
  for (i <- 0 until SBUS_BYTES) {
    debug_write_symbols(i) := io.memwrites_out.bits.data(8*(i+1)-1, 8*i)
  }
  dontTouch(debug_write_symbols)

// for (i <- 0 until SBUS_BYTES) {
// when (decomp_symbol_q(i).deq.fire) {
// CompressAccelLogger.logInfo("decompressed_symbol: %d\n", decomp_symbol_q(i).deq.bits)
// }
// }

  switch (state) {
  is(STATE_IDLE) {
    when (literal_cmd_q.deq.valid && decompressed_size_q.deq.valid) {
      val block_type_wire = literal_cmd_q.deq.bits.block_type

      val dic_max_bits = literal_cmd_q.deq.bits.max_bits
      val dic_max_bit_mask = (1.U << dic_max_bits) - 1.U
      max_bits := dic_max_bits
      dic_idx_mask := dic_max_bit_mask

      when (block_type_wire >= 2.U) {
// state := STATE_READ_SRC
        state := STATE_REMOVE_PADDING
        CompressAccelLogger.logInfo("LiteralExpander moving to STATE_REMOVE_PADDING\n")
      } .otherwise {
        val rawrle_fire = DecoupledHelper(literal_src_info_in_q.deq.valid,
                                          rawrle_src_info_out_q.enq.ready)

        literal_src_info_in_q.deq.ready := rawrle_fire.fire(literal_src_info_in_q.deq.valid)
        rawrle_src_info_out_q.enq.valid := rawrle_fire.fire(rawrle_src_info_out_q.enq.ready)
        rawrle_src_info_out_q.enq.bits.ip := literal_src_info_in_q.deq.bits.ip
        rawrle_src_info_out_q.enq.bits.isize := Mux(block_type_wire === 0.U, literal_src_info_in_q.deq.bits.isize, 1.U)

        when (rawrle_fire.fire) {
          when (block_type_wire === 0.U) {
            state := STATE_PROCESS_RAW

            CompressAccelLogger.logInfo("LiteralExpander moving to STATE_PROCESS_RAW\n")
          } .otherwise {
            state := STATE_PROCESS_RLE

            CompressAccelLogger.logInfo("LiteralExpander moving to STATE_PROCESS_RLE\n")
          }
        }
      }

      CompressAccelLogger.logInfo("LiteralExpander STATE_IDLE\n")
      CompressAccelLogger.logInfo("LiteralExpander dic info, max_bits: %d\n", dic_max_bits)
    }
  }

  is (STATE_REMOVE_PADDING) {
    when (io.src_stream.output_valid) {
      msb := io.src_stream.output_data >> (SBUS_BITS - 8).U

      val highest_set_bit = 7.U - PriorityEncoder(Reverse(msb))
      val padding = 8.U - highest_set_bit
      bit_offset := ((literal_cmd_q.deq.bits.comp_size << 3.U) - padding - max_bits).asSInt
      src_stream_consumed_bits := padding

      state := STATE_DECOMPRESS_LOOKUP

      CompressAccelLogger.logInfo("LiteralExpander padding: %d\n", padding)
    }
  }

  is (STATE_DECOMPRESS_LOOKUP) {
    when (lookup_idx_enq_fire_success || last_chunk_lookup_idx_q_fire.fire) {
      state := STATE_DECOMPRESS_SELECT_VALID

      CompressAccelLogger.logInfo("LiteralExpander DECOMPRESS_LOOKUP -> DECOMPRESS_SELECT_VALID\n")
    }
  }

  is (STATE_DECOMPRESS_SELECT_VALID) {
    when (dic_entry_all_fire || last_chunk_dic_entry_deq_fire.fire) {
      state := STATE_DECOMPRESS_SHIFT
      CompressAccelLogger.logInfo("LiteralExpander DECOMPRESS_SELECT_VALID -> DECOMPRESS_SHIFT\n")
    }
  }

  is (STATE_DECOMPRESS_SHIFT) {
    io.src_stream.output_ready := true.B
    when (io.src_stream.output_valid) {
      val consumed_bytes = src_stream_consumed_bits >> 3.U
      val actual_consumed_bytes = Mux(consumed_bytes >= avail_bytes, avail_bytes, consumed_bytes)

      io.src_stream.user_consumed_bytes := actual_consumed_bytes
      src_stream_consumed_bits := src_stream_consumed_bits - (actual_consumed_bytes << 3.U)

      when (termination_condition) {
        val all_stream_done = Mux(literal_cmd_q.deq.bits.single_stream, true.B, 
          decompressed_stream_cnt === 3.U)

        when (all_stream_done) {
          state := STATE_DONE
        } .otherwise {
          state := STATE_STREAM_DONE
        }
      } .otherwise {
        state := STATE_DECOMPRESS_LOOKUP
      }
    }
  }

  is (STATE_STREAM_DONE) {
    bit_offset := 0.S
    src_stream_consumed_bits := 0.U
    decompressed_stream_cnt := decompressed_stream_cnt + 1.U
    state := STATE_IDLE

    CompressAccelLogger.logInfo("LiteralExpander STREAM_DONE: %d finished\n", decompressed_stream_cnt)
  }

  is (STATE_PROCESS_RAW) {
    val consume_bytes_fire = DecoupledHelper(io.rawrle_stream.output_valid,
                                             memwrites_out_q.enq.ready)

    memwrites_out_q.enq.valid := consume_bytes_fire.fire(memwrites_out_q.enq.ready)
    io.rawrle_stream.output_ready := consume_bytes_fire.fire(io.rawrle_stream.output_valid)

    when (consume_bytes_fire.fire) {
      val avail = io.rawrle_stream.available_output_bytes
      val data = io.rawrle_stream.output_data

      io.rawrle_stream.user_consumed_bytes := avail

      val raw_data = WireInit(VecInit(Seq.fill(SBUS_BYTES)(0.U(8.W))))
      for (i <- 0 until SBUS_BYTES) {
        raw_data(i) := Mux(i.U < avail, (data >> (8*i).U)&(0xFF).U, 0.U)
      }

      memwrites_out_q.enq.bits.writerbundle.validbytes := avail 
      memwrites_out_q.enq.bits.writerbundle.data := Cat(raw_data.reverse)
      memwrites_out_q.enq.bits.writerbundle.end_of_message := io.rawrle_stream.output_last_chunk
      memwrites_out_q.enq.bits.raw_flag := true.B

      when (io.rawrle_stream.output_last_chunk) {
        state := STATE_DONE
      }
    }
  }

  is (STATE_PROCESS_RLE) {
    when (io.rawrle_stream.output_valid) {
      io.rawrle_stream.output_ready := true.B
      io.rawrle_stream.user_consumed_bytes := io.rawrle_stream.available_output_bytes
      rle_byte := io.rawrle_stream.output_data & (0xFF).U
      rle_byte_set := true.B
    }

    when (rle_byte_set) {
      memwrites_out_q.enq.valid := true.B

      when (memwrites_out_q.enq.fire) {
        val remain_bytes = literal_cmd_q.deq.bits.decomp_size - rle_sent_bytes
        val valid_byte = Mux(remain_bytes < SBUS_BYTES.U, remain_bytes, SBUS_BYTES.U)
        val rle_data = Cat(Seq.fill(SBUS_BYTES)(rle_byte))
        val end_of_rle = rle_sent_bytes + valid_byte === literal_cmd_q.deq.bits.decomp_size

        rle_sent_bytes := rle_sent_bytes + valid_byte
        memwrites_out_q.enq.bits.writerbundle.validbytes := valid_byte
        memwrites_out_q.enq.bits.writerbundle.data := rle_data
        memwrites_out_q.enq.bits.writerbundle.end_of_message := end_of_rle
        // memwrites_out_q.enq.bits.raw_flag := true.B


        when (end_of_rle) {
          state := STATE_DONE
        }
      }
    }
  }

  is (STATE_DONE) {
    max_bits := 0.U
    dic_idx_mask := 0.U
    bit_offset := 0.S
    decompressed_stream_cnt := 0.U
    written_bytes := 0.U
    write_start_idx := 0.U
    read_start_idx := 0.U
    rle_byte := 0.U
    rle_byte_set := false.B
    rle_sent_bytes := 0.U
    literal_expander_done_q.enq.valid := true.B
    literal_expander_done_q.enq.bits := true.B
    block_type := 0.U
    when (literal_expander_done_q.enq.fire) {
      state := STATE_IDLE
    }

    CompressAccelLogger.logInfo("LiteralExpander STREAM_DONE: %d finished\n", decompressed_stream_cnt)
    CompressAccelLogger.logInfo("LiteralExpander STATE_DONE\n")
  }
  }
}
