package compressacc

import chisel3._
import chisel3.util._
import chisel3.util._
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants

// class WriterBundle extends Bundle {
// val data = UInt(128.W)
// val validbytes = UInt(6.W)
// val end_of_message = Bool()
// }

class FSESymbolInfo extends Bundle {
  val symbol = UInt(8.W)
  val last_symbol = Bool()
}

class FSECompressorEncoderIO(val interleave_cnt: Int)(implicit val p: Parameters) extends Bundle {
  val src_stream = Flipped(new MemLoaderConsumerBundle)

  val table_log = Flipped(Decoupled(UInt(4.W)))

  val symbol_info = Vec(interleave_cnt, Decoupled(new FSESymbolInfo))
  val comp_trans_table = Vec(interleave_cnt, Flipped(Decoupled(new FSECompTransformationTable)))
  val state_table_idx = Vec(interleave_cnt, Output(UInt(16.W)))
  val new_state = Vec(interleave_cnt, Flipped(Valid(UInt(16.W))))

  val memwrites_out = Decoupled(new WriterBundle)
  val header_writes = Flipped(Decoupled(new WriterBundle))

  val lookup_done = Decoupled(Bool())
}

// Encoder for FSE_compress_usingCTable_generic
class FSECompressorEncoder(val cmd_que_depth: Int, val interleave_cnt: Int)
  (implicit val p: Parameters) extends Module {
  val io = IO(new FSECompressorEncoderIO(interleave_cnt))

  val SBUS_WIDTH = 32
  require(interleave_cnt <= SBUS_WIDTH)

  val intereave_cnt_log2 = log2Ceil(interleave_cnt + 1)
  val lookup_symbol_cnt_q = Module(new Queue(UInt(intereave_cnt_log2.W), 10))

  val initCStateDone = RegInit(VecInit(Seq.fill(interleave_cnt)(false.B)))
  val statePtr_value = RegInit(VecInit(Seq.fill(interleave_cnt)(0.U(16.W))))
  val symbols = WireInit(VecInit(Seq.fill(interleave_cnt)(0.U(8.W))))
  for (i <- 0 until interleave_cnt) {
    symbols(i) := io.src_stream.output_data >> ((SBUS_WIDTH-1-i)*8).U
  }

  when (io.src_stream.output_valid && io.src_stream.output_ready) {
    CompressAccelLogger.logInfo("FSE_ENCODER consumed input stream\n")
    for (i <- 0 until interleave_cnt) {
      CompressAccelLogger.logInfo("fse_encoder_symbols(%d): %d\n", i.U, symbols(i))
    }
  }

  val symbol_info_q = Seq.fill(interleave_cnt)(Module(new Queue(new FSESymbolInfo, 10)))
  val comp_trans_table_q = Seq.fill(interleave_cnt)(Module(new Queue(new FSECompTransformationTable, 10)))
  for (i <- 0 until interleave_cnt) {
    io.symbol_info(i) <> symbol_info_q(i).io.deq
    comp_trans_table_q(i).io.enq <> io.comp_trans_table(i)
  }

  val all_io_symbol_info_ready = symbol_info_q.map(_.io.enq.ready).reduce(_ || _)
  val symbol_info_fire = DecoupledHelper(
    lookup_symbol_cnt_q.io.enq.ready,
    io.src_stream.output_valid,
    all_io_symbol_info_ready)

  val consumed_bytes = Mux(interleave_cnt.U > io.src_stream.available_output_bytes,
                           io.src_stream.available_output_bytes,
                           interleave_cnt.U)

  lookup_symbol_cnt_q.io.enq.valid := symbol_info_fire.fire(lookup_symbol_cnt_q.io.enq.ready)
  lookup_symbol_cnt_q.io.enq.bits := consumed_bytes

  io.src_stream.output_ready := symbol_info_fire.fire(io.src_stream.output_valid)
  io.src_stream.user_consumed_bytes := consumed_bytes

  val track_consumed_bytes = RegInit(0.U(64.W))
  when (io.src_stream.output_valid && io.src_stream.output_ready) {
    track_consumed_bytes := track_consumed_bytes + consumed_bytes
    CompressAccelLogger.logInfo("FSE_ENCODER track_consumed_bytes: %d\n", track_consumed_bytes)
  }
  val track_consumed_bytes_odd = (track_consumed_bytes(0) =/= 0.U)

  for (i <- 0 until interleave_cnt) {
    val use_this_queue = (i.U < consumed_bytes)
    symbol_info_q(i).io.enq.valid := symbol_info_fire.fire(all_io_symbol_info_ready) && use_this_queue
    symbol_info_q(i).io.enq.bits.symbol := symbols(i)
    symbol_info_q(i).io.enq.bits.last_symbol := (i.U === io.src_stream.available_output_bytes - 1.U) && io.src_stream.output_last_chunk
  }

  val input_symbol_cnt = RegInit(0.U(64.W))
  when (io.src_stream.output_valid && io.src_stream.output_ready) {
    input_symbol_cnt := input_symbol_cnt + consumed_bytes
    CompressAccelLogger.logInfo("FSE_ENCODER_INPUT_BYTES\n")
    for (i <- 0 until interleave_cnt) {
      when (i.U < consumed_bytes) {
        CompressAccelLogger.logInfo("fse_symbol(%d): %d\n", input_symbol_cnt + i.U, symbols(i))
      }
    }
  }

  val flush = RegInit(false.B)
  val nbBitsOut = WireInit(VecInit(Seq.fill(interleave_cnt)(0.U(32.W))))
  val deltaNbBits = WireInit(VecInit(Seq.fill(interleave_cnt)(0.U(32.W))))
  val deltaFindState = WireInit(VecInit(Seq.fill(interleave_cnt)(0.U(32.W))))
  val statePtr_value_to_shift = WireInit(VecInit(Seq.fill(interleave_cnt)(0.U(16.W))))
  for (i <- 0 until interleave_cnt) {
    deltaNbBits(i) := comp_trans_table_q(i).io.deq.bits.nbbit
    deltaFindState(i) := comp_trans_table_q(i).io.deq.bits.findstate
    nbBitsOut(i) := Mux(flush, io.table_log.bits,
                      Mux(!initCStateDone(i),
                        (deltaNbBits(i) + (1<<15).U) >> 16.U,
                        (statePtr_value(i) + deltaNbBits(i)) >> 16.U))
    statePtr_value_to_shift(i) := Mux(!initCStateDone(i),
                                      (nbBitsOut(i) << 16.U) - deltaNbBits(i),
                                      statePtr_value(i))
    io.state_table_idx(i) := ((statePtr_value_to_shift(i) >> nbBitsOut(i)).asSInt + deltaFindState(i).asSInt).asUInt
  }

  val comp_trans_table_last_symbols = comp_trans_table_q.map(x => x.io.deq.bits.from_last_symbol && x.io.deq.valid).reduce(_ || _)
  val comp_trans_table_all_valid = comp_trans_table_q.map(_.io.deq.valid).reduce(_ && _)
  val comp_trans_table_valid = comp_trans_table_last_symbols || comp_trans_table_all_valid
  val all_new_state_valid = io.new_state.map(_.valid).zipWithIndex.map { case(v, i) =>
    val can_be_invalid = WireInit(false.B)
    when (i.U < lookup_symbol_cnt_q.io.deq.bits) {
      can_be_invalid := v
    } .otherwise {
      can_be_invalid := true.B
    }
    can_be_invalid
  }.reduce(_ && _)

  val FSE_CODE_MAX_BITS = 16
  val comp_bits_buff = Module(new CompressedBitsBuff(interleave_cnt, FSE_CODE_MAX_BITS))

  val update_state_fire = DecoupledHelper(
    lookup_symbol_cnt_q.io.deq.valid,
    comp_trans_table_valid,
    all_new_state_valid,
    comp_bits_buff.io.writes_in.ready)

  lookup_symbol_cnt_q.io.deq.ready := update_state_fire.fire(lookup_symbol_cnt_q.io.deq.valid)
  for (i <- 0 until interleave_cnt) {
    comp_trans_table_q(i).io.deq.ready := update_state_fire.fire(comp_trans_table_valid)
  }

  for (i <- 0 until interleave_cnt) {
    val valid_new_state = (i.U < lookup_symbol_cnt_q.io.deq.bits)
    statePtr_value(i) := Mux(valid_new_state && update_state_fire.fire, io.new_state(i).bits, statePtr_value(i))
  }

  for (i <- 0 until interleave_cnt) {
    when (update_state_fire.fire && i.U < lookup_symbol_cnt_q.io.deq.bits && !initCStateDone(i)) {
      initCStateDone(i) := true.B
    }
  }

  val flush_state_fire = DecoupledHelper(
    io.table_log.valid,
    flush,
    io.lookup_done.ready,
    comp_bits_buff.io.writes_in.ready)

  io.lookup_done.valid := RegNext(flush_state_fire.fire)
  io.lookup_done.bits := true.B


  io.table_log.ready := flush_state_fire.fire(io.table_log.valid)

  when (update_state_fire.fire && comp_trans_table_last_symbols) {
    flush := true.B
  }

  when (flush_state_fire.fire) {
    flush := false.B
    for (i <- 0 until interleave_cnt) {
      statePtr_value(i) := 0.U
      initCStateDone(i) := false.B
    }
    track_consumed_bytes := 0.U
  }

  when (update_state_fire.fire || flush_state_fire.fire) {
    for (i <- 0 until interleave_cnt) {
      CompressAccelLogger.logInfo("new_state_value: %d\n", statePtr_value(i))
    }
  }

  val add_padding = RegInit(false.B)
  when (flush_state_fire.fire) {
    add_padding := true.B
  }

  val add_padding_fire = DecoupledHelper(
    comp_bits_buff.io.writes_in.ready,
    add_padding)

  when (add_padding_fire.fire) {
    add_padding := false.B
  }

  val cumul_nbBitsOut = WireInit(VecInit(Seq.fill(interleave_cnt)(0.U(32.W))))
  cumul_nbBitsOut(0) := nbBitsOut(0)
  for (i <- 1 until interleave_cnt) {
    val valid_bits = Mux(flush, nbBitsOut(i),
                        Mux(i.U < lookup_symbol_cnt_q.io.deq.bits, nbBitsOut(i), 
                          0.U))
    cumul_nbBitsOut(i) := cumul_nbBitsOut(i-1) + valid_bits
  }

  val states_masked = WireInit(VecInit(Seq.fill(interleave_cnt)(0.U(16.W))))
  for (i <- 0 until interleave_cnt) {
    val mask = (1.U << nbBitsOut(i)(4, 0)) - 1.U
    states_masked(i) := statePtr_value(i) & mask
  }

  val MAX_VALID_BITS = interleave_cnt * FSE_CODE_MAX_BITS
  val MAX_VALID_BITS_LOG2 = log2Ceil(MAX_VALID_BITS + 1)
  val states_shifted = WireInit(VecInit(Seq.fill(interleave_cnt)(0.U(MAX_VALID_BITS.W))))
  states_shifted(0) := states_masked(0)
  for (i <- 1 until interleave_cnt) {
    states_shifted(i) := states_masked(i) << cumul_nbBitsOut(i-1)(MAX_VALID_BITS_LOG2-1, 0)
  }

  val states_concat = states_shifted.reduce(_ | _)
  val init_done = initCStateDone.reduce(_ && _)


  // NOTE: assume interleave_cnt is 2 for this code because I got lazy lol
  val states_concat_reverse = states_masked(1) | (states_masked(0) << nbBitsOut(1)(MAX_VALID_BITS_LOG2-1, 0))

  // when the number of compressed bytes is odd,
  //     symbol_idx    0  1    0  1    0  1    0
  // FSE_encodeSymbol (0, 1), (2, 3), (4, 5), (6, x)
  // FSE_flushCState  (6, 5)
  // So we have to flip the order states when flushing
  val data_to_write = Mux(flush_state_fire.fire && track_consumed_bytes_odd, states_concat_reverse,
                          states_concat)

  val sent_bits = RegInit(0.U(64.W))

  when (comp_bits_buff.io.writes_in.fire) {
    sent_bits := Mux(add_padding, 0.U, sent_bits + comp_bits_buff.io.writes_in.bits.validbits)
  }

  val extra_bits = sent_bits & 7.U
  val padding_bits = 8.U - extra_bits

  comp_bits_buff.io.writes_in.valid := update_state_fire.fire(comp_bits_buff.io.writes_in.ready, init_done) ||
                                       flush_state_fire.fire(comp_bits_buff.io.writes_in.ready) ||
                                       add_padding_fire.fire(comp_bits_buff.io.writes_in.ready)
  comp_bits_buff.io.writes_in.bits.data := Mux(add_padding, 1.U, data_to_write)
  comp_bits_buff.io.writes_in.bits.validbits := Mux(add_padding, padding_bits, cumul_nbBitsOut(interleave_cnt - 1))
  comp_bits_buff.io.writes_in.bits.end_of_message := add_padding

  when (update_state_fire.fire && init_done) {
    CompressAccelLogger.logInfo("update_state_fire.fire\n")
    for (i <- 0 until interleave_cnt) {
      CompressAccelLogger.logInfo("%d, state: 0x%x, states_masked: 0x%x, states_shifted: 0x%x, cumul_nbBitsOut: %d, nbBitsOut: %d\n",
        i.U, statePtr_value(i), states_masked(i), states_shifted(i), cumul_nbBitsOut(i), nbBitsOut(i))
    }
    CompressAccelLogger.logInfo("bitbuf data: 0x%x, validbits: %d\n",
      comp_bits_buff.io.writes_in.bits.data, comp_bits_buff.io.writes_in.bits.validbits)
    CompressAccelLogger.logInfo("comp_trans_table_last_symbols: %d\n", comp_trans_table_last_symbols)
  }

  when (flush_state_fire.fire) {
    CompressAccelLogger.logInfo("flush_state_fire.fire\n")
    for (i <- 0 until interleave_cnt) {
      CompressAccelLogger.logInfo("%d, state: 0x%x, states_masked: 0x%x, states_shifted: 0x%x, cumul_nbBitsOut: %d, nbBitsOut: %d\n",
        i.U, statePtr_value(i), states_masked(i), states_shifted(i), cumul_nbBitsOut(i), nbBitsOut(i))
    }
    CompressAccelLogger.logInfo("bitbuf data: 0x%x, validbits: %d\n",
      comp_bits_buff.io.writes_in.bits.data, comp_bits_buff.io.writes_in.bits.validbits)
  }

  when (add_padding_fire.fire) {
    CompressAccelLogger.logInfo("padding_bits: %d\n", padding_bits)
  }
 

  val memwrite_fire = DecoupledHelper(
    io.memwrites_out.ready,
    comp_bits_buff.io.consumer.valid)

  val header_fire = DecoupledHelper(
    io.header_writes.valid,
    io.memwrites_out.ready)

  val track_fse_total_written_bytes = RegInit(0.U(64.W))

  when (io.memwrites_out.fire) {
    when (io.memwrites_out.bits.end_of_message) {
      CompressAccelLogger.logInfo("FSE_ENCODER track_fse_total_written_bytes: %d\n", track_fse_total_written_bytes + io.memwrites_out.bits.validbytes)
      track_fse_total_written_bytes := 0.U
    } .otherwise {
      track_fse_total_written_bytes := track_fse_total_written_bytes + io.memwrites_out.bits.validbytes
    }
  }

  // Currently, header_fire & memwrite_fire cannot overlap because
  // the dictionary builder will write out all the header before enabling
  // the encoder from performing lookups
  io.memwrites_out.valid := memwrite_fire.fire(io.memwrites_out.ready) ||
                            header_fire.fire(io.memwrites_out.ready)
  io.memwrites_out.bits.data := Mux(header_fire.fire,
                                    io.header_writes.bits.data,
                                    comp_bits_buff.io.consumer.data)
  io.memwrites_out.bits.validbytes := Mux(header_fire.fire,
                                          io.header_writes.bits.validbytes,
                                          comp_bits_buff.io.consumer.avail_bytes)
  io.memwrites_out.bits.end_of_message := Mux(header_fire.fire,
                                              io.header_writes.bits.end_of_message,
                                              comp_bits_buff.io.consumer.last_chunk)

  io.header_writes.ready := header_fire.fire(io.header_writes.valid)

  comp_bits_buff.io.consumer.consumed_bytes := comp_bits_buff.io.consumer.avail_bytes
  comp_bits_buff.io.consumer.ready := memwrite_fire.fire(comp_bits_buff.io.consumer.valid)

  when (io.memwrites_out.fire && io.memwrites_out.bits.end_of_message) {
    for (i <- 0 until interleave_cnt) {
      initCStateDone(i) := false.B
      statePtr_value(i) := 0.U
      track_consumed_bytes := 0.U
      input_symbol_cnt := 0.U
      flush := false.B
      add_padding := false.B
      sent_bits := 0.U
      track_fse_total_written_bytes := 0.U
    }
  }
}
