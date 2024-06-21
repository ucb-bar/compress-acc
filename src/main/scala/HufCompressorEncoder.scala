package compressacc

import chisel3._
import chisel3.util._
import chisel3.util._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheArbiter}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._



class HufSymbolInfo extends Bundle {
  val symbol = UInt(8.W)
  val last_symbol = Bool() }

class HufCompDicInfo extends Bundle {
  // FIXME : fix entry related stuff to 32 bits
  val entry = UInt(32.W)
  val from_last_symbol = Bool()
}

class HufCompressorEncoderIO(val unroll_cnt: Int)(implicit val p: Parameters) extends Bundle {
  val lit_stream = Flipped(new MemLoaderConsumerBundle)

  val symbol_info = Vec(unroll_cnt, Decoupled(new HufSymbolInfo))
  val dic_info = Vec(unroll_cnt, Flipped(Decoupled(new HufCompDicInfo)))

  val memwrites_out = Decoupled(new WriterBundle)
  val compressed_bytes = Decoupled(UInt(64.W))
}

class HufCompressorEncoder(val cmd_que_depth: Int, val unroll_cnt: Int)(implicit p: Parameters) extends Module {
  val io = IO(new HufCompressorEncoderIO(unroll_cnt))

  val SBUS_WIDTH = 32

  val symbol_info_q = Seq.fill(unroll_cnt)(Module(new Queue(new HufSymbolInfo, cmd_que_depth)).io)
  val dic_info_q = Seq.fill(unroll_cnt)(Module(new Queue(new HufCompDicInfo, cmd_que_depth)).io)
  for (i <- 0 until unroll_cnt) {
    io.symbol_info(i) <> symbol_info_q(i).deq
    dic_info_q(i).enq <> io.dic_info(i)
  }

  val avail_bytes = io.lit_stream.available_output_bytes
  val last_chunk = io.lit_stream.output_last_chunk
  val src_data = io.lit_stream.output_data

  val write_start_idx = RegInit(0.U(log2Ceil(unroll_cnt+1).W))
  val write_len = Mux(avail_bytes >= unroll_cnt.U, unroll_cnt.U, avail_bytes)
  val write_end_idx_wide = write_start_idx +& write_len
  val write_end_idx_end = write_end_idx_wide % unroll_cnt.U
  val wrapped = write_end_idx_wide >= unroll_cnt.U

  val all_symbol_queues_enq_ready = symbol_info_q.map(_.enq.ready).reduce(_ && _)

  val symbol_remapped = WireInit(VecInit(Seq.fill(unroll_cnt)(0.U(8.W))))
  val last_symbol_remapped = WireInit(VecInit(Seq.fill(unroll_cnt)(false.B)))

  for (i <- 0 until unroll_cnt) {
    val use_this_queue = Mux(wrapped,
                             (i.U >= write_start_idx) || (i.U < write_end_idx_end),
                             (i.U >= write_start_idx) && (i.U < write_end_idx_end)
                            )
    symbol_info_q(i).enq.valid := use_this_queue && all_symbol_queues_enq_ready && io.lit_stream.output_valid

    val cur_write_idx = (write_start_idx +& i.U) % unroll_cnt.U
    symbol_remapped(cur_write_idx) := src_data >> ((SBUS_WIDTH-1-i)*8).U
    last_symbol_remapped(cur_write_idx) := (i.U === (avail_bytes-1.U)) && last_chunk && use_this_queue
  }

  for (i <- 0 until unroll_cnt) {
    symbol_info_q(i).enq.bits.symbol := symbol_remapped(i)
    symbol_info_q(i).enq.bits.last_symbol := last_symbol_remapped(i)
  }

  when (all_symbol_queues_enq_ready && io.lit_stream.output_valid) {
    write_start_idx := write_end_idx_end
  }

  io.lit_stream.output_ready := false.B
  io.lit_stream.user_consumed_bytes := 0.U

  when (all_symbol_queues_enq_ready) {
    io.lit_stream.output_ready := true.B
    io.lit_stream.user_consumed_bytes := write_len
  }

  // HUF_addBits() takes a HUF_CElt (size_t) which is
  // the pair (nbBits, value) in the format:
  // format:
  //   - Bits [0, 4)            = nbBits
  //   - Bits [4, 32 - nbBits)  = 0
  //   - Bits [32 - nbBits, 32) = value
  val nbbits = WireInit(VecInit(Seq.fill(unroll_cnt)(0.U(4.W))))
  val comp_vals = WireInit(VecInit(Seq.fill(unroll_cnt)(0.U(32.W))))

  val print_symbol_info_fire_cnt = RegInit(0.U(64.W))
  val print_dic_info_fire_cnt = RegInit(0.U(64.W))
  for (i <- 0 until unroll_cnt) {
    nbbits(i) := Mux(dic_info_q(i).deq.valid, dic_info_q(i).deq.bits.entry(3, 0), 0.U)
    comp_vals(i) := Mux(dic_info_q(i).deq.valid, (dic_info_q(i).deq.bits.entry >> 4.U) << 4.U, 0.U)
  }

  for (i <- 0 until unroll_cnt) {
    when (io.symbol_info(i).fire) {
      CompressAccelLogger.logInfo("ENCODER_SYMBOL_FIRE tag: %d, io.symbol(%d): 0x%x\n", print_symbol_info_fire_cnt, i.U, io.symbol_info(i).bits.symbol)
    }

    when (io.dic_info(i).fire) {
      val entry = io.dic_info(i).bits.entry
      val nbbits = entry(3, 0)
      val code = entry >> (32.U - nbbits)
      CompressAccelLogger.logInfo("ENCODER_DIC_FIRE tag: %d, io.dic(%d) nbbits: %d, code: 0x%x\n", print_dic_info_fire_cnt, i.U, nbbits, code)
    }

    when (io.symbol_info.map(_.fire).reduce(_ || _)) {
      print_symbol_info_fire_cnt := print_symbol_info_fire_cnt + 1.U
    }

    when (io.dic_info.map(_.fire).reduce(_ || _)) {
      print_dic_info_fire_cnt := print_dic_info_fire_cnt + 1.U
    }
  }

  val nbbits_cumul = WireInit(VecInit(Seq.fill(unroll_cnt+1)(0.U((log2Ceil(4*unroll_cnt+1)+1).W))))
  nbbits_cumul(0) := 0.U
  for (i <- 1 until unroll_cnt+1) {
    nbbits_cumul(i) := nbbits_cumul(i-1) + nbbits(unroll_cnt-i)
  }

  /////////////////////////////////////////////////////////////////////////////

  val HUF_CODE_MAX_BITS = 11
  val comp_bits_buf = Module(new CompressedBitsBuff(unroll_cnt, HUF_CODE_MAX_BITS))


  val COMP_VALUE_MAX_BITS_PER_CYCLE = HUF_CODE_MAX_BITS * unroll_cnt
  val COMP_VALUE_BITWIDTH_BYTALIGNED = if (COMP_VALUE_MAX_BITS_PER_CYCLE % 8 == 0) COMP_VALUE_MAX_BITS_PER_CYCLE else (COMP_VALUE_MAX_BITS_PER_CYCLE/8 + 1) * 8
  val COMP_VALUE_BITWIDTH = if (COMP_VALUE_BITWIDTH_BYTALIGNED <= 32) 32 else COMP_VALUE_BITWIDTH_BYTALIGNED
  val COMP_VALUE_SHIFT_LEFT = COMP_VALUE_BITWIDTH - 32

  val comp_vals_left_shifted = WireInit(VecInit(Seq.fill(unroll_cnt)(0.U(COMP_VALUE_BITWIDTH.W))))
  for (i <- 0 until unroll_cnt) {
    comp_vals_left_shifted(i) := comp_vals(i) << COMP_VALUE_SHIFT_LEFT
  }

  val comp_value_high_bits_shifted = WireInit(VecInit(Seq.fill(unroll_cnt)(0.U(COMP_VALUE_BITWIDTH.W))))
  for (i <- 0 until unroll_cnt) {
    comp_value_high_bits_shifted(i) := comp_vals_left_shifted(i) >> nbbits_cumul(unroll_cnt-1-i)
  }

  val comp_value_concat = Wire(UInt(COMP_VALUE_BITWIDTH.W))
  comp_value_concat := comp_value_high_bits_shifted.reduce(_ | _)

  val all_dic_valid = dic_info_q.map(_.deq.valid).reduce(_ && _)
  val last_dic_valid = dic_info_q.map(q => q.deq.valid && q.deq.bits.from_last_symbol).reduce(_ || _)
  val dic_valid = all_dic_valid || last_dic_valid
  val dic_valid_cnt = dic_info_q.map(_.deq.valid.asUInt).reduce(_ +& _)


  val write_bitbuf = DecoupledHelper(
    comp_bits_buf.io.writes_in.ready,
    dic_valid)

  val track_compressed_bits = RegInit(0.U(64.W))
  val track_compressed_bytes = track_compressed_bits >> 3.U
  val extra_compressed_bits = track_compressed_bits - (track_compressed_bytes << 3.U)
  val padding_bits = 8.U - extra_compressed_bits
  when (write_bitbuf.fire) {
    track_compressed_bits := track_compressed_bits + nbbits_cumul(unroll_cnt)
  }

  val last_dic_processed = RegInit(false.B)
  when (write_bitbuf.fire && last_dic_valid) {
    last_dic_processed := true.B
  }

  val write_padding = DecoupledHelper(
    comp_bits_buf.io.writes_in.ready,
    last_dic_processed)

  when (write_padding.fire) {
    last_dic_processed := false.B
    track_compressed_bits := 0.U
  }

  val write_data = comp_value_concat >> (COMP_VALUE_BITWIDTH.U - nbbits_cumul(unroll_cnt))
  comp_bits_buf.io.writes_in.valid := write_bitbuf.fire(comp_bits_buf.io.writes_in.ready) ||
                                      write_padding.fire(comp_bits_buf.io.writes_in.ready)
  comp_bits_buf.io.writes_in.bits.data := Mux(last_dic_processed, 1.U, write_data)
  comp_bits_buf.io.writes_in.bits.validbits := Mux(last_dic_processed, padding_bits, nbbits_cumul(unroll_cnt))
  comp_bits_buf.io.writes_in.bits.end_of_message := last_dic_processed
// comp_bits_buf.io.writes_in.valid := write_bitbuf.fire(comp_bits_buf.io.writes_in.ready)
// comp_bits_buf.io.writes_in.bits.data := write_data
// comp_bits_buf.io.writes_in.bits.validbits := nbbits_cumul(dic_valid_cnt)
// comp_bits_buf.io.writes_in.bits.end_of_message := last_dic_valid

  dic_info_q.foreach(q => q.deq.ready := write_bitbuf.fire(dic_valid))

  when (write_bitbuf.fire) {
    CompressAccelLogger.logInfo("ENCODER_WRITEBITBUF\n")
    CompressAccelLogger.logInfo("data: 0x%x\n", comp_bits_buf.io.writes_in.bits.data)
    CompressAccelLogger.logInfo("validbits: 0x%x\n", comp_bits_buf.io.writes_in.bits.validbits)
    CompressAccelLogger.logInfo("end_of_message: 0x%x\n", comp_bits_buf.io.writes_in.bits.end_of_message)

    CompressAccelLogger.logInfo("dic_valid_cnt: %d\n", dic_valid_cnt)

    for (i <- 0 until unroll_cnt + 1) {
      CompressAccelLogger.logInfo("nbbits_cumul(%d): %d\n", i.U, nbbits_cumul(i))
    }
    for (i <- 0 until unroll_cnt) {
      CompressAccelLogger.logInfo("comp_val(%d): %d, nbbits(%d): %d\n",
        i.U, comp_vals(i) >> (32.U - nbbits(i)), i.U, nbbits(i))
    }
  }

  when (write_padding.fire) {
    CompressAccelLogger.logInfo("ENCODER_WRITEPADDING\n")
    CompressAccelLogger.logInfo("data: 0x%x\n", comp_bits_buf.io.writes_in.bits.data)
    CompressAccelLogger.logInfo("validbits: 0x%x\n", comp_bits_buf.io.writes_in.bits.validbits)
    CompressAccelLogger.logInfo("end_of_message: 0x%x\n", comp_bits_buf.io.writes_in.bits.end_of_message)
  }

  val memwrites_out_q = Module(new Queue(new WriterBundle, cmd_que_depth))
  io.memwrites_out <> memwrites_out_q.io.deq

  val track_written_compressed_bytes = RegInit(0.U(64.W))
  val read_bitbuf = DecoupledHelper(
    comp_bits_buf.io.consumer.valid,
    memwrites_out_q.io.enq.ready,
    io.compressed_bytes.ready)

  when (read_bitbuf.fire) {
    when (memwrites_out_q.io.enq.bits.end_of_message) {
      track_written_compressed_bytes := 0.U
    } .otherwise {
      track_written_compressed_bytes := track_written_compressed_bytes + memwrites_out_q.io.enq.bits.validbytes
    }
  }

  io.compressed_bytes.valid := read_bitbuf.fire(io.compressed_bytes.ready, memwrites_out_q.io.enq.bits.end_of_message)
  io.compressed_bytes.bits := track_written_compressed_bytes + memwrites_out_q.io.enq.bits.validbytes

  when (io.compressed_bytes.fire) {
    CompressAccelLogger.logInfo("ENCODER_COMPRESSED_BYTES_FIRE, compressed_byte: %d\n", io.compressed_bytes.bits)
  }

  comp_bits_buf.io.consumer.ready := read_bitbuf.fire(comp_bits_buf.io.consumer.valid)
  comp_bits_buf.io.consumer.consumed_bytes := comp_bits_buf.io.consumer.avail_bytes


  memwrites_out_q.io.enq.valid := read_bitbuf.fire(memwrites_out_q.io.enq.ready)
  memwrites_out_q.io.enq.bits.data := comp_bits_buf.io.consumer.data
  memwrites_out_q.io.enq.bits.validbytes := comp_bits_buf.io.consumer.avail_bytes
  memwrites_out_q.io.enq.bits.end_of_message := comp_bits_buf.io.consumer.last_chunk

  when (memwrites_out_q.io.enq.fire && memwrites_out_q.io.enq.bits.end_of_message) {
    print_symbol_info_fire_cnt := 0.U
    print_dic_info_fire_cnt := 0.U
    write_start_idx := 0.U
    track_written_compressed_bytes := 0.U
  }

  when (io.memwrites_out.fire) {
    CompressAccelLogger.logInfo("ENCODER_MEMWRITE\n")
    CompressAccelLogger.logInfo("data: 0x%x\n", io.memwrites_out.bits.data)
    CompressAccelLogger.logInfo("validbytes: %d\n", io.memwrites_out.bits.validbytes)
    CompressAccelLogger.logInfo("end_of_message: %d\n", io.memwrites_out.bits.end_of_message)
  }
}
