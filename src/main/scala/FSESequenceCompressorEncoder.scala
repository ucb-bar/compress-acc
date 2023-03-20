package compressacc

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import ZstdConsts._



class FSESeqCodePair extends Bundle {
  val seq = UInt(32.W)
  val code = UInt(8.W)
}

class FSESequenceCompressorEncoderIO()(implicit p: Parameters) extends Bundle {
  val src_stream = Flipped(new MemLoaderConsumerBundle)

  val nbseq = Flipped(Decoupled(UInt(64.W)))

  val ll_table_log = Flipped(Decoupled(UInt(4.W)))
  val ll_header_writes = Flipped(Decoupled(new WriterBundle))

  val of_table_log = Flipped(Decoupled(UInt(4.W)))
  val of_header_writes = Flipped(Decoupled(new WriterBundle))

  val ml_table_log = Flipped(Decoupled(UInt(4.W)))
  val ml_header_writes = Flipped(Decoupled(new WriterBundle))

  val ll_symbol_info = Decoupled(new FSESymbolInfo)
  val ll_state_table_idx = Output(UInt(16.W))
  val ll_comp_trans_table = Flipped(Decoupled(new FSECompTransformationTable))
  val ll_new_state = Flipped(Valid(UInt(16.W)))

  val of_symbol_info = Decoupled(new FSESymbolInfo)
  val of_state_table_idx = Output(UInt(16.W))
  val of_comp_trans_table = Flipped(Decoupled(new FSECompTransformationTable))
  val of_new_state = Flipped(Valid(UInt(16.W)))

  val ml_symbol_info = Decoupled(new FSESymbolInfo)
  val ml_state_table_idx = Output(UInt(16.W))
  val ml_comp_trans_table = Flipped(Decoupled(new FSECompTransformationTable))
  val ml_new_state = Flipped(Valid(UInt(16.W)))

  val ll_predefined_mode = Flipped(Decoupled(Bool()))
  val of_predefined_mode = Flipped(Decoupled(Bool()))
  val ml_predefined_mode = Flipped(Decoupled(Bool()))

  val ll_lookup_done = Decoupled(Bool())
  val of_lookup_done = Decoupled(Bool())
  val ml_lookup_done = Decoupled(Bool())

  val memwrites_out = Decoupled(new WriterBundle)
}

class FSESequenceCompressorEncoder()(implicit p: Parameters) extends Module {
  val io = IO(new FSESequenceCompressorEncoderIO())

  val SBUS_WIDTH = 32


  val LL_bits = RegInit(VecInit(Seq.fill(36)(0.U(8.W))))
  val ML_bits = RegInit(VecInit(Seq.fill(53)(0.U(8.W))))
  val table_initialized = RegInit(false.B)
  when (!table_initialized) {
    table_initialized := true.B

    for (i <- 0 until 16) {
      LL_bits(i) := 0.U
    }
    for (i <- 16 until 20) {
      LL_bits(i) := 1.U
    }
    LL_bits(20) := 2.U
    LL_bits(21) := 2.U
    LL_bits(22) := 3.U
    LL_bits(23) := 3.U
    LL_bits(24) := 4.U
    for (i <- 25 until 36) {
      LL_bits(i) := (i - 19).U
    }

    for (i <- 0 until 32) {
      ML_bits(i) := 0.U
    }
    for (i <- 32 until 36) {
      ML_bits(i) := 1.U
    }
    ML_bits(36) := 2.U
    ML_bits(37) := 2.U
    ML_bits(38) := 3.U
    ML_bits(39) := 3.U
    ML_bits(40) := 4.U
    ML_bits(41) := 4.U
    ML_bits(42) := 5.U
    for (i <- 43 until 53) {
      ML_bits(i) := (i - 36).U
    }
  }

  /////////////////////////////////////////////////////////////////////////////

  val ml_symbol = Wire(UInt(32.W))
  val of_symbol = Wire(UInt(32.W))
  val ll_symbol = Wire(UInt(32.W))
  of_symbol := io.src_stream.output_data >> ((SBUS_WIDTH-4)*8).U
  ml_symbol := io.src_stream.output_data >> ((SBUS_WIDTH-8)*8).U
  ll_symbol := io.src_stream.output_data >> ((SBUS_WIDTH-12)*8).U

  val ml_seq_to_code = Module(new MLSeqToCode())
  ml_seq_to_code.io.mlbase := ml_symbol

  val of_seq_to_code = Module(new OFSeqToCode())
  of_seq_to_code.io.ofbase := of_symbol

  val ll_seq_to_code = Module(new LLSeqToCode())
  ll_seq_to_code.io.litlen := ll_symbol

  val initCStateDone = RegInit(false.B)

  val ml_statePtr_value = RegInit(0.U(16.W))
  val of_statePtr_value = RegInit(0.U(16.W))
  val ll_statePtr_value = RegInit(0.U(16.W))

  val ml_code_q = Module(new Queue(new FSESymbolInfo, 4))
  val of_code_q = Module(new Queue(new FSESymbolInfo, 4))
  val ll_code_q = Module(new Queue(new FSESymbolInfo, 4))

  val ml_seqcode_q = Module(new Queue(new FSESeqCodePair, 4))
  val of_seqcode_q = Module(new Queue(new FSESeqCodePair, 4))
  val ll_seqcode_q = Module(new Queue(new FSESeqCodePair, 4))

  io.ml_symbol_info <> ml_code_q.io.deq
  io.of_symbol_info <> of_code_q.io.deq
  io.ll_symbol_info <> ll_code_q.io.deq

  val FSE_CODE_MAX_BITS = 16
  val comp_bits_buff = Module(new CompressedBitsBuff(unroll_cnt=3, max_bits_per_code=FSE_CODE_MAX_BITS))

  val sequence_dic_lookup_fire = DecoupledHelper(
    ml_code_q.io.enq.ready,
    of_code_q.io.enq.ready,
    ll_code_q.io.enq.ready,
    ml_seqcode_q.io.enq.ready,
    of_seqcode_q.io.enq.ready,
    ll_seqcode_q.io.enq.ready,
    io.src_stream.output_valid,
    io.src_stream.available_output_bytes >= 12.U,
    comp_bits_buff.io.writes_in.ready)

  io.src_stream.output_ready := sequence_dic_lookup_fire.fire(io.src_stream.output_valid)
  io.src_stream.user_consumed_bytes := 12.U

  val last_symbols = io.src_stream.output_last_chunk && (io.src_stream.available_output_bytes === 12.U)

  ml_code_q.io.enq.bits.symbol := ml_seq_to_code.io.mlcode
  ml_code_q.io.enq.bits.last_symbol := last_symbols
  ml_code_q.io.enq.valid := sequence_dic_lookup_fire.fire(ml_code_q.io.enq.ready)

  of_code_q.io.enq.bits.symbol := of_seq_to_code.io.ofcode
  of_code_q.io.enq.bits.last_symbol := last_symbols
  of_code_q.io.enq.valid := sequence_dic_lookup_fire.fire(of_code_q.io.enq.ready)

  ll_code_q.io.enq.bits.symbol := ll_seq_to_code.io.llcode
  ll_code_q.io.enq.bits.last_symbol := last_symbols
  ll_code_q.io.enq.valid := sequence_dic_lookup_fire.fire(ll_code_q.io.enq.ready)

  ml_seqcode_q.io.enq.bits.code := ml_seq_to_code.io.mlcode
  ml_seqcode_q.io.enq.bits.seq := ml_symbol
  ml_seqcode_q.io.enq.valid := sequence_dic_lookup_fire.fire(ml_seqcode_q.io.enq.ready)

  of_seqcode_q.io.enq.bits.code := of_seq_to_code.io.ofcode
  of_seqcode_q.io.enq.bits.seq := of_symbol
  of_seqcode_q.io.enq.valid := sequence_dic_lookup_fire.fire(of_seqcode_q.io.enq.ready)

  ll_seqcode_q.io.enq.bits.code := ll_seq_to_code.io.llcode
  ll_seqcode_q.io.enq.bits.seq := ll_symbol
  ll_seqcode_q.io.enq.valid := sequence_dic_lookup_fire.fire(ll_seqcode_q.io.enq.ready)

  when (sequence_dic_lookup_fire.fire) {
    CompressAccelLogger.logInfo("FSE_SEQUENCE_ENCODER sequence_dic_lookup_fire\n")
    CompressAccelLogger.logInfo("last_symbols: %d\n", last_symbols)
    CompressAccelLogger.logInfo("ll_symbol32: 0x%x\n", ll_symbol)
    CompressAccelLogger.logInfo("ll_code8: %d\n", ll_seq_to_code.io.llcode)
    CompressAccelLogger.logInfo("ml_symbol32: 0x%x\n", ml_symbol)
    CompressAccelLogger.logInfo("ml_code8: %d\n", ml_seq_to_code.io.mlcode)
    CompressAccelLogger.logInfo("of_symbol32: 0x%x\n", of_symbol)
    CompressAccelLogger.logInfo("of_code8: %d\n", of_seq_to_code.io.ofcode)
  }

  ////////////////////////////////////////////////////////////////////////////

  val ml_comp_trans_table_q = Module(new Queue(new FSECompTransformationTable, 10))
  val of_comp_trans_table_q = Module(new Queue(new FSECompTransformationTable, 10))
  val ll_comp_trans_table_q = Module(new Queue(new FSECompTransformationTable, 10))

  ml_comp_trans_table_q.io.enq <> io.ml_comp_trans_table
  of_comp_trans_table_q.io.enq <> io.of_comp_trans_table
  ll_comp_trans_table_q.io.enq <> io.ll_comp_trans_table

  val flush = RegInit(false.B)


  val ml_nbBitsOut = Wire(UInt(32.W))
  val ml_deltaNbBits = ml_comp_trans_table_q.io.deq.bits.nbbit
  val ml_deltaFindState = ml_comp_trans_table_q.io.deq.bits.findstate
  val ml_statePtr_value_to_shift = Wire(UInt(16.W))
  ml_nbBitsOut := Mux(flush, io.ml_table_log.bits,
                    Mux(!initCStateDone,
                      (ml_deltaNbBits + (1<<15).U) >> 16.U,
                      (ml_statePtr_value + ml_deltaNbBits) >> 16.U))
  ml_statePtr_value_to_shift := Mux(!initCStateDone,
                                    (ml_nbBitsOut << 16.U) - ml_deltaNbBits,
                                    ml_statePtr_value)
  io.ml_state_table_idx := ((ml_statePtr_value_to_shift >> ml_nbBitsOut).asSInt + ml_deltaFindState.asSInt).asUInt

  val of_nbBitsOut = Wire(UInt(32.W))
  val of_deltaNbBits = of_comp_trans_table_q.io.deq.bits.nbbit
  val of_deltaFindState = of_comp_trans_table_q.io.deq.bits.findstate
  val of_statePtr_value_to_shift = Wire(UInt(16.W))
  of_nbBitsOut := Mux(flush, io.of_table_log.bits,
                    Mux(!initCStateDone,
                      (of_deltaNbBits + (1<<15).U) >> 16.U,
                      (of_statePtr_value + of_deltaNbBits) >> 16.U))
  of_statePtr_value_to_shift := Mux(!initCStateDone,
                                    (of_nbBitsOut << 16.U) - of_deltaNbBits,
                                    of_statePtr_value)
  io.of_state_table_idx := ((of_statePtr_value_to_shift >> of_nbBitsOut).asSInt + of_deltaFindState.asSInt).asUInt

  val ll_nbBitsOut = Wire(UInt(32.W))
  val ll_deltaNbBits = ll_comp_trans_table_q.io.deq.bits.nbbit
  val ll_deltaFindState = ll_comp_trans_table_q.io.deq.bits.findstate
  val ll_statePtr_value_to_shift = Wire(UInt(16.W))
  ll_nbBitsOut := Mux(flush, io.ll_table_log.bits,
                    Mux(!initCStateDone,
                      (ll_deltaNbBits + (1<<15).U) >> 16.U,
                      (ll_statePtr_value + ll_deltaNbBits) >> 16.U))
  ll_statePtr_value_to_shift := Mux(!initCStateDone,
                                    (ll_nbBitsOut << 16.U) - ll_deltaNbBits,
                                    ll_statePtr_value)
  io.ll_state_table_idx := ((ll_statePtr_value_to_shift >> ll_nbBitsOut).asSInt + ll_deltaFindState.asSInt).asUInt

  val of_encoded_symbol_masked = of_statePtr_value & ((1.U << of_nbBitsOut(4, 0)) - 1.U)

  val ml_encoded_symbol_masked = ml_statePtr_value & ((1.U << ml_nbBitsOut(4, 0)) - 1.U)
  val ml_encoded_symbol_masked_shifted = ml_encoded_symbol_masked << of_nbBitsOut(4, 0)

  val ll_encoded_symbol_masked = ll_statePtr_value & ((1.U << ll_nbBitsOut(4, 0)) - 1.U)
  val ll_encoded_symbol_to_shift = of_nbBitsOut(4, 0) + ml_nbBitsOut(4, 0)
  val ll_encoded_symbol_masked_shifted = ll_encoded_symbol_masked << ll_encoded_symbol_to_shift(4, 0)

  val update_state_data_to_write = of_encoded_symbol_masked | ml_encoded_symbol_masked_shifted | ll_encoded_symbol_masked_shifted
  val update_state_data_bits = of_nbBitsOut + ml_nbBitsOut + ll_nbBitsOut

  val ll_encoded_symbol_flush_to_shift = ml_nbBitsOut(4, 0) + of_nbBitsOut(4, 0)
  val of_encoded_symbol_masked_flush_shifted = of_encoded_symbol_masked << ml_nbBitsOut(4, 0)
  val ll_encoded_symbol_masked_flush_shifted = ll_encoded_symbol_masked << ll_encoded_symbol_flush_to_shift(4, 0)
  val flush_state_data_to_write = ml_encoded_symbol_masked | of_encoded_symbol_masked_flush_shifted | ll_encoded_symbol_masked_flush_shifted
  val flush_state_data_bits = of_nbBitsOut + ml_nbBitsOut + ll_nbBitsOut


  val all_new_states_valid = io.ll_new_state.valid && io.ml_new_state.valid && io.of_new_state.valid

  val block_add_bits_enable_q = Module(new Queue(Bool(), 8))
  val update_state_fired = RegInit(false.B)

  val update_state_fire = DecoupledHelper(
    ll_comp_trans_table_q.io.deq.valid,
    ml_comp_trans_table_q.io.deq.valid,
    of_comp_trans_table_q.io.deq.valid,
    comp_bits_buff.io.writes_in.ready,
    block_add_bits_enable_q.io.enq.ready,
    !update_state_fired,
    all_new_states_valid)

  block_add_bits_enable_q.io.enq.valid := update_state_fire.fire(block_add_bits_enable_q.io.enq.ready)
  block_add_bits_enable_q.io.enq.bits := true.B

// val corresponding_update_state_fired = RegInit(false.B)

  val block_add_bits_fire = DecoupledHelper(
    ll_seqcode_q.io.deq.valid,
    ml_seqcode_q.io.deq.valid,
    of_seqcode_q.io.deq.valid,
    update_state_fired,
    comp_bits_buff.io.writes_in.ready,
    block_add_bits_enable_q.io.deq.valid)

  block_add_bits_enable_q.io.deq.ready := block_add_bits_fire.fire(block_add_bits_enable_q.io.deq.valid)

  when (update_state_fire.fire && !initCStateDone) {
    initCStateDone := true.B
  }

  when (update_state_fire.fire) {
    update_state_fired := true.B
    ll_statePtr_value := io.ll_new_state.bits
    ml_statePtr_value := io.ml_new_state.bits
    of_statePtr_value := io.of_new_state.bits
  }

  when (block_add_bits_fire.fire) {
    update_state_fired := false.B
  }


  val last_update_state_fired = RegInit(false.B)
  when (update_state_fire.fire && ll_comp_trans_table_q.io.deq.bits.from_last_symbol) {
    last_update_state_fired := true.B
  }

  when (block_add_bits_fire.fire && last_update_state_fired) {
    flush := true.B
    last_update_state_fired := false.B
  }

  val flush_state_fire = DecoupledHelper(
    io.ll_table_log.valid,
    io.ml_table_log.valid,
    io.of_table_log.valid,
    comp_bits_buff.io.writes_in.ready,
    flush)

  io.ll_table_log.ready := flush_state_fire.fire(io.ll_table_log.valid)
  io.ml_table_log.ready := flush_state_fire.fire(io.ml_table_log.valid)
  io.of_table_log.ready := flush_state_fire.fire(io.of_table_log.valid)


  when ((update_state_fire.fire && initCStateDone) || flush_state_fire.fire) {
    CompressAccelLogger.logInfo("FSE_SEQUENCE_ENCODER update_state_fire.fire\n")

    CompressAccelLogger.logInfo("OF state: 0x%x, states_masked: 0x%x, states_shifted: 0x%x, nbBitsOut: %d, nxt_state: 0x%x\n",
      of_statePtr_value,
      of_encoded_symbol_masked,
      of_encoded_symbol_masked,
      of_nbBitsOut,
      io.of_new_state.bits)

    CompressAccelLogger.logInfo("ML state: 0x%x, states_masked: 0x%x, states_shifted: 0x%x, nbBitsOut: %d, nxt_state: 0x%x\n",
      ml_statePtr_value,
      ml_encoded_symbol_masked,
      ml_encoded_symbol_masked_shifted,
      ml_nbBitsOut,
      io.ml_new_state.bits)

    CompressAccelLogger.logInfo("LL state: 0x%x, states_masked: 0x%x, states_shifted: 0x%x, nbBitsOut: %d, nxt_state: 0x%x\n",
      ll_statePtr_value,
      ll_encoded_symbol_masked,
      ll_encoded_symbol_masked_shifted,
      ll_nbBitsOut,
      io.ll_new_state.bits)
  }


  val add_padding = RegInit(false.B)
  when (flush_state_fire.fire) {
    add_padding := true.B
    flush := false.B
  }

  val add_padding_fire = DecoupledHelper(
    comp_bits_buff.io.writes_in.ready,
    add_padding)

  when (add_padding_fire.fire) {
    add_padding := false.B
  }

  val total_sent_bits = RegInit(0.U(64.W))
  val extra_bits = total_sent_bits & 7.U
  val padding_bits = 8.U - extra_bits
  when (comp_bits_buff.io.writes_in.fire) {
    when (add_padding) {
      total_sent_bits := 0.U
    } .otherwise {
      total_sent_bits := total_sent_bits + comp_bits_buff.io.writes_in.bits.validbits
    }
  }

  when (add_padding_fire.fire) {
    CompressAccelLogger.logInfo("FSESequenceEncoder padding_bits: %d\n", padding_bits)
  }

  ll_comp_trans_table_q.io.deq.ready := update_state_fire.fire(ll_comp_trans_table_q.io.deq.valid)
  ml_comp_trans_table_q.io.deq.ready := update_state_fire.fire(ml_comp_trans_table_q.io.deq.valid)
  of_comp_trans_table_q.io.deq.ready := update_state_fire.fire(of_comp_trans_table_q.io.deq.valid)

  ll_seqcode_q.io.deq.ready := block_add_bits_fire.fire(ll_seqcode_q.io.deq.valid)
  ml_seqcode_q.io.deq.ready := block_add_bits_fire.fire(ml_seqcode_q.io.deq.valid)
  of_seqcode_q.io.deq.ready := block_add_bits_fire.fire(of_seqcode_q.io.deq.valid)

  val ll_BIT_addBits_value = ll_seqcode_q.io.deq.bits.seq
  val ll_BIT_addBits_nbBits = LL_bits(ll_seqcode_q.io.deq.bits.code)
  val ll_BIT_addBits_BIT_mask = (1.U << ll_BIT_addBits_nbBits(4, 0)) - 1.U
  val ll_BIT_addBits_value_masked = ll_BIT_addBits_value & ll_BIT_addBits_BIT_mask

  val ml_BIT_addBits_value = ml_seqcode_q.io.deq.bits.seq
  val ml_BIT_addBits_nbBits = ML_bits(ml_seqcode_q.io.deq.bits.code)
  val ml_BIT_addBits_BIT_mask = (1.U << ml_BIT_addBits_nbBits(4, 0)) - 1.U
  val ml_BIT_addBits_value_masked = ml_BIT_addBits_value & ml_BIT_addBits_BIT_mask
  val ml_BIT_addBits_value_masked_shifted = ml_BIT_addBits_value_masked << (ll_BIT_addBits_nbBits)

  val of_BIT_addBits_value = of_seqcode_q.io.deq.bits.seq
  val of_BIT_addBits_nbBits = of_seqcode_q.io.deq.bits.code
  val of_BIT_addBits_BIT_mask = (1.U << of_BIT_addBits_nbBits(4, 0)) - 1.U
  val of_BIT_addBits_value_masked = of_BIT_addBits_value & of_BIT_addBits_BIT_mask
  val of_BIT_addBits_value_masked_shifted = of_BIT_addBits_value_masked << (ll_BIT_addBits_nbBits + ml_BIT_addBits_nbBits)

  val BIT_addBits_concat = of_BIT_addBits_value_masked_shifted | ml_BIT_addBits_value_masked_shifted | ll_BIT_addBits_value_masked
  val BIT_addBits_nbBits = ll_BIT_addBits_nbBits + ml_BIT_addBits_nbBits + of_BIT_addBits_nbBits

  when (block_add_bits_fire.fire) {
    CompressAccelLogger.logInfo("FSESequenceEncoder BIT_addBits_fire\n")
    CompressAccelLogger.logInfo("ll value: %d nbbits: %d\n", ll_BIT_addBits_value, ll_BIT_addBits_nbBits)
    CompressAccelLogger.logInfo("ml value: %d nbbits: %d\n", ml_BIT_addBits_value, ml_BIT_addBits_nbBits)
    CompressAccelLogger.logInfo("of value: %d nbbits: %d\n", of_BIT_addBits_value, of_BIT_addBits_nbBits)
  }
 
  comp_bits_buff.io.writes_in.valid := block_add_bits_fire.fire(comp_bits_buff.io.writes_in.ready) ||
                                       update_state_fire.fire(comp_bits_buff.io.writes_in.ready, initCStateDone) ||
                                       flush_state_fire.fire(comp_bits_buff.io.writes_in.ready) ||
                                       add_padding_fire.fire(comp_bits_buff.io.writes_in.ready)

  comp_bits_buff.io.writes_in.bits.data := Mux(block_add_bits_fire.fire, BIT_addBits_concat,
                                             Mux(update_state_fire.fire, update_state_data_to_write,
                                               Mux(flush_state_fire.fire, flush_state_data_to_write,
                                                 Mux(add_padding_fire.fire, 1.U,
                                                   0.U))))
  comp_bits_buff.io.writes_in.bits.validbits := Mux(block_add_bits_fire.fire, BIT_addBits_nbBits,
                                                  Mux(update_state_fire.fire, update_state_data_bits,
                                                    Mux(flush_state_fire.fire, flush_state_data_bits,
                                                      Mux(add_padding_fire.fire, padding_bits,
                                                        0.U))))
  comp_bits_buff.io.writes_in.bits.end_of_message := add_padding

  when (comp_bits_buff.io.writes_in.fire) {
    CompressAccelLogger.logInfo("FSESequenceEncoder_COMPRESSED_BITS\n")
    CompressAccelLogger.logInfo("data: 0x%x bits: %d\n", comp_bits_buff.io.writes_in.bits.data, comp_bits_buff.io.writes_in.bits.validbits)
  }
  ////////////////////////////////////////////////////////////////////////////

  val fseSeqCompressorState = RegInit(0.U(3.W))
  val sWriteSequenceHeader = 0.U
  val sWriteLiteralLengthHeader = 1.U
  val sWriteOffsetHeader = 2.U
  val sWriteMatchlengthHeader = 3.U
  val sWriteCompressedSequences = 4.U

  val LONGNBSEQ = BigInt("7F00", 16)

  val nbseq_low8 = io.nbseq.bits(7, 0)
  val nbseq_high8 = io.nbseq.bits(15, 8)
  val nbseq_minus_longnbseq = io.nbseq.bits - LONGNBSEQ.U

  val nbseq_high8_plus80 = Wire(UInt(8.W))
  nbseq_high8_plus80 := nbseq_high8 + BigInt("80", 16).U

  val wire_ff = Wire(UInt(8.W))
  wire_ff := BigInt("FF", 16).U


  val ll_fse_mode = Wire(UInt(2.W))
  val of_fse_mode = Wire(UInt(2.W))
  val ml_fse_mode = Wire(UInt(2.W))

  ll_fse_mode := Mux(io.ll_predefined_mode.bits, 0.U, 2.U)
  of_fse_mode := Mux(io.of_predefined_mode.bits, 0.U, 2.U)
  ml_fse_mode := Mux(io.ml_predefined_mode.bits, 0.U, 2.U)

  // TODO : add other modes like rle & raw???????
  val compression_modes = Wire(UInt(8.W))
  compression_modes := Cat(ll_fse_mode, of_fse_mode, ml_fse_mode, 0.U(2.W))

  val sequence_header = Mux(io.nbseq.bits < 128.U, io.nbseq.bits,
                          Mux(io.nbseq.bits < LONGNBSEQ.U, Cat(nbseq_low8, nbseq_high8_plus80),
                            Cat(nbseq_minus_longnbseq(15, 0), wire_ff)))
  val sequence_header_bytes = Mux(io.nbseq.bits < 128.U, 1.U,
                                Mux(io.nbseq.bits < LONGNBSEQ.U, 2.U,
                                  3.U))

  val sequence_header_compression_modes = sequence_header | (compression_modes << (sequence_header_bytes << 3.U))
  val sequence_header_compression_modes_bytes = sequence_header_bytes + 1.U

  val sequence_header_fire = DecoupledHelper(
    fseSeqCompressorState === sWriteSequenceHeader,
    io.nbseq.valid,
    io.ll_predefined_mode.valid,
    io.ml_predefined_mode.valid,
    io.of_predefined_mode.valid,
    io.memwrites_out.ready)

  io.nbseq.ready := sequence_header_fire.fire(io.nbseq.valid)
  when (sequence_header_fire.fire) {
    when (!io.ll_predefined_mode.bits) {
      fseSeqCompressorState := sWriteLiteralLengthHeader
    } .elsewhen (io.ll_predefined_mode.bits && !io.of_predefined_mode.bits) {
      fseSeqCompressorState := sWriteOffsetHeader
    } .elsewhen (io.ll_predefined_mode.bits && io.of_predefined_mode.bits && !io.ml_predefined_mode.bits) {
      fseSeqCompressorState := sWriteMatchlengthHeader
    } .otherwise {
      fseSeqCompressorState := sWriteCompressedSequences
    }

    CompressAccelLogger.logInfo("sequence_header: %d sequence_header_bytes: %d\n", sequence_header, sequence_header_bytes)
    CompressAccelLogger.logInfo("ll_predefined_mode: %d\n", io.ll_predefined_mode.bits)
    CompressAccelLogger.logInfo("ml_predefined_mode: %d\n", io.ml_predefined_mode.bits)
    CompressAccelLogger.logInfo("of_predefined_mode: %d\n", io.of_predefined_mode.bits)
    CompressAccelLogger.logInfo("compression_modes: %b\n", compression_modes)
  }

  val literallength_header_fire = DecoupledHelper(
    fseSeqCompressorState === sWriteLiteralLengthHeader,
    io.ll_header_writes.valid,
    io.memwrites_out.ready)

  io.ll_header_writes.ready := literallength_header_fire.fire(io.ll_header_writes.valid)

  when (literallength_header_fire.fire && io.ll_header_writes.bits.end_of_message) {
    when (!io.of_predefined_mode.bits) {
      fseSeqCompressorState := sWriteOffsetHeader
    } .elsewhen (io.of_predefined_mode.bits && !io.ml_predefined_mode.bits) {
      fseSeqCompressorState := sWriteMatchlengthHeader
    } .otherwise {
      fseSeqCompressorState := sWriteCompressedSequences
    }
  }

  val offset_header_fire = DecoupledHelper(
    fseSeqCompressorState === sWriteOffsetHeader,
    io.of_header_writes.valid,
    io.memwrites_out.ready)

  io.of_header_writes.ready := offset_header_fire.fire(io.of_header_writes.valid)

  when (offset_header_fire.fire && io.of_header_writes.bits.end_of_message) {
    when (!io.ml_predefined_mode.bits) {
      fseSeqCompressorState := sWriteMatchlengthHeader
    } .otherwise {
      fseSeqCompressorState := sWriteCompressedSequences
    }
  }

  val matchlength_header_fire = DecoupledHelper(
    fseSeqCompressorState === sWriteMatchlengthHeader,
    io.ml_header_writes.valid,
    io.memwrites_out.ready)

  io.ml_header_writes.ready := matchlength_header_fire.fire(io.ml_header_writes.valid)

  when (matchlength_header_fire.fire && io.ml_header_writes.bits.end_of_message) {
    fseSeqCompressorState := sWriteCompressedSequences
  }

  val compressed_sequences_fire = DecoupledHelper(
    fseSeqCompressorState === sWriteCompressedSequences,
    io.memwrites_out.ready,
    comp_bits_buff.io.consumer.valid,
    io.ll_predefined_mode.valid,
    io.ml_predefined_mode.valid,
    io.of_predefined_mode.valid,
    io.ll_lookup_done.ready,
    io.of_lookup_done.ready,
    io.ml_lookup_done.ready)

  comp_bits_buff.io.consumer.ready := compressed_sequences_fire.fire(comp_bits_buff.io.consumer.valid)
  comp_bits_buff.io.consumer.consumed_bytes := comp_bits_buff.io.consumer.avail_bytes

  when (compressed_sequences_fire.fire && io.memwrites_out.bits.end_of_message) {
    fseSeqCompressorState := sWriteSequenceHeader
  }

  io.ll_lookup_done.bits := true.B
  io.of_lookup_done.bits := true.B
  io.ml_lookup_done.bits := true.B

  io.ll_lookup_done.valid := compressed_sequences_fire.fire(io.ll_lookup_done.ready, io.memwrites_out.bits.end_of_message)
  io.ml_lookup_done.valid := compressed_sequences_fire.fire(io.ml_lookup_done.ready, io.memwrites_out.bits.end_of_message)
  io.of_lookup_done.valid := compressed_sequences_fire.fire(io.of_lookup_done.ready, io.memwrites_out.bits.end_of_message)

  io.ll_predefined_mode.ready := compressed_sequences_fire.fire(io.ll_predefined_mode.valid, io.memwrites_out.bits.end_of_message)
  io.ml_predefined_mode.ready := compressed_sequences_fire.fire(io.ml_predefined_mode.valid, io.memwrites_out.bits.end_of_message)
  io.of_predefined_mode.ready := compressed_sequences_fire.fire(io.of_predefined_mode.valid, io.memwrites_out.bits.end_of_message)

  when (compressed_sequences_fire.fire && io.memwrites_out.bits.end_of_message) {
    // put stuff to initialize here
    initCStateDone := false.B
    ml_statePtr_value := 0.U
    of_statePtr_value := 0.U
    ll_statePtr_value := 0.U
    flush := false.B
    last_update_state_fired := false.B
    total_sent_bits := 0.U
  }


  io.memwrites_out.valid := sequence_header_fire.fire(io.memwrites_out.ready) ||
                            literallength_header_fire.fire(io.memwrites_out.ready) ||
                            offset_header_fire.fire(io.memwrites_out.ready) ||
                            matchlength_header_fire.fire(io.memwrites_out.ready) ||
                            compressed_sequences_fire.fire(io.memwrites_out.ready)

  io.memwrites_out.bits.data := Mux(fseSeqCompressorState === sWriteSequenceHeader, sequence_header_compression_modes,
                                  Mux(fseSeqCompressorState === sWriteLiteralLengthHeader, io.ll_header_writes.bits.data,
                                    Mux(fseSeqCompressorState === sWriteOffsetHeader, io.of_header_writes.bits.data,
                                      Mux(fseSeqCompressorState === sWriteMatchlengthHeader, io.ml_header_writes.bits.data,
                                        Mux(fseSeqCompressorState === sWriteCompressedSequences, comp_bits_buff.io.consumer.data,
                                          0.U)))))

  io.memwrites_out.bits.validbytes := Mux(fseSeqCompressorState === sWriteSequenceHeader, sequence_header_compression_modes_bytes,
                                        Mux(fseSeqCompressorState === sWriteLiteralLengthHeader, io.ll_header_writes.bits.validbytes,
                                          Mux(fseSeqCompressorState === sWriteOffsetHeader, io.of_header_writes.bits.validbytes,
                                            Mux(fseSeqCompressorState === sWriteMatchlengthHeader, io.ml_header_writes.bits.validbytes,
                                              Mux(fseSeqCompressorState === sWriteCompressedSequences, comp_bits_buff.io.consumer.avail_bytes,
                                                0.U)))))

  io.memwrites_out.bits.end_of_message := (fseSeqCompressorState === sWriteCompressedSequences) && comp_bits_buff.io.consumer.last_chunk

  val prevfseSeqCompressorState = RegNext(fseSeqCompressorState)
  when (prevfseSeqCompressorState =/= fseSeqCompressorState) {
    CompressAccelLogger.logCritical("FSE_SEQUENCE_ENCODER state transition %d to %d\n",
      prevfseSeqCompressorState, fseSeqCompressorState)
  }


  val memwrite_bytes_vec = WireInit(VecInit(Seq.fill(32)(0.U(8.W))))
  for (i <- 0 until 32) {
    memwrite_bytes_vec(i) := io.memwrites_out.bits.data >> ((8*i).U)
  }

  when (io.memwrites_out.fire) {
    for (i <- 0 until 32) {
      when (i.U < io.memwrites_out.bits.validbytes) {
        CompressAccelLogger.logInfo("FSESEQUENCE_COMPRESSED_BYTES: 0x%x\n", memwrite_bytes_vec(i))
      }
    }
  }
}
