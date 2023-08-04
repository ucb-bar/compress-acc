package compressacc

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants

class HufCompressorDicBuilderIO(val unroll_cnt: Int)(implicit val p: Parameters) extends Bundle {
  val cnt_stream = Flipped(new MemLoaderConsumerBundle)

  val symbol_info = Vec(unroll_cnt, Flipped(Decoupled(new HufSymbolInfo)))
  val dic_info = Vec(unroll_cnt, Decoupled(new HufCompDicInfo))

  val header_writes = Decoupled(new WriterBundle)
  val header_written_bytes = Decoupled(UInt(64.W))
  val header_size_info = Decoupled(UInt(8.W))

  val init_dictionary = Flipped(Decoupled(Bool()))
}

class HufCompressorDicBuilder(val cmd_que_depth: Int, val unroll_cnt: Int)
  (implicit val p: Parameters) extends Module {
  val io = IO(new HufCompressorDicBuilderIO(unroll_cnt))
  dontTouch(io)

  val HUF_MAX_CODE_MAX_BITS = 11
  val HUF_TABLELOG_MAX = 12
  val HUF_MAX_SYMBOLS = 256
  val HUF_MAX_SYMBOLS1 = 257
  val HUF_MAX_SYMBOLS1_LOG2 = log2Ceil(HUF_MAX_SYMBOLS1) + 1

  val STATE_COLLECT_STATS = 0.U
  val STATE_PROCESS_STATS = 1.U
  val STATE_PMIN_RECIP = 2.U
  val STATE_NORM_CNT = 3.U
  val STATE_SORT_CNT = 4.U
  val STATE_BUILD_TREE = 5.U
  val STATE_UPDATE_NBBITS = 6.U
  val STATE_UPDATE_NBBIT_PER_RANK = 7.U
  val STATE_UPDATE_VAL_PER_RANK = 8.U
  val STATE_DIC_SET_VALUE = 9.U
  val STATE_WRITE_DIC = 10.U
  val STATE_LOOKUP = 11.U
  val STATE_NORMALIZE_FAILED = 12.U
  val state = RegInit(0.U(4.W))

  val symbol_stats = RegInit(VecInit(Seq.fill(HUF_MAX_SYMBOLS1)(0.U(32.W))))
  val dic = RegInit(VecInit(Seq.fill(HUF_MAX_SYMBOLS1)(0.U(32.W))))
  val tmp_idx = RegInit(0.U(10.W))

  ///////////////////////////////////////////////////////////////////////////
  // STATE_COLLECT_STATS
  ///////////////////////////////////////////////////////////////////////////
  val avail_bytes = io.cnt_stream.available_output_bytes

  val INF = (1 << 31) - 1
  val cnt_min = RegInit(INF.U(32.W))
  val cnt_tot = WireInit(0.U(32.W))
  val max_symbol_value = RegInit(0.U(HUF_MAX_SYMBOLS1_LOG2.W))

  io.cnt_stream.output_ready := true.B
  io.cnt_stream.user_consumed_bytes := 0.U

  val COLLECT_STAT_PROCESS_BYTES = p(HufCompressDicBuilderProcessedStatBytesPerCycle)
  val COLLECT_STAT_PROCESS_BYTES_LOG2 = log2Ceil(COLLECT_STAT_PROCESS_BYTES+1)
  val input_symbol = WireInit(VecInit(Seq.fill(COLLECT_STAT_PROCESS_BYTES)(0.U(8.W))))
  for (i <- 0 until COLLECT_STAT_PROCESS_BYTES) {
    input_symbol(i) :=  io.cnt_stream.output_data >> (i*8).U
  }

  val table = Seq.fill(HUF_MAX_SYMBOLS1)(WireInit(VecInit(Seq.fill(COLLECT_STAT_PROCESS_BYTES)(0.U(1.W)))))
  for (i <- 0 until HUF_MAX_SYMBOLS1) {
    for (j <- 0 until COLLECT_STAT_PROCESS_BYTES) {
      table(i)(j) := Mux(j.U < avail_bytes && (input_symbol(j) === i.U), 1.U, 0.U)
// table(i)(j) := Mux((i.U === input_symbol(j)) && j.U < avail_bytes, 1.U, 0.U)
    }
  }

  val stat_sum = WireInit(VecInit(Seq.fill(HUF_MAX_SYMBOLS1)(0.U(COLLECT_STAT_PROCESS_BYTES_LOG2.W))))
  for (i <- 0 until HUF_MAX_SYMBOLS1) {
    stat_sum(i) := table(i).reduce(_ +& _)
  }

  val has_value = WireInit(VecInit(Seq.fill(HUF_MAX_SYMBOLS1)(0.U(1.W))))
  for (i <- 0 until HUF_MAX_SYMBOLS1) {
    has_value(i) := Mux(stat_sum(i) > 0.U, 1.U, 0.U)
  }
  val has_value_cat = Cat(has_value)

  val cur_max_value = (HUF_MAX_SYMBOLS1-1).U - PriorityEncoder(has_value_cat)

  val consumed_bytes = Mux(avail_bytes < COLLECT_STAT_PROCESS_BYTES.U,
                           avail_bytes, COLLECT_STAT_PROCESS_BYTES.U)

  when (state === STATE_COLLECT_STATS) {
    when (io.cnt_stream.output_valid) {
      io.cnt_stream.user_consumed_bytes := consumed_bytes

      for (i <- 0 until HUF_MAX_SYMBOLS1) {
        symbol_stats(i) := symbol_stats(i) + stat_sum(i)
      }

      max_symbol_value := Mux(max_symbol_value > cur_max_value, max_symbol_value, cur_max_value)

      CompressAccelLogger.logInfo("HUF_DICBUILDER STATE_COLLECT_STATS\n")
      for (i <- 0 until HUF_MAX_SYMBOLS1) {
        CompressAccelLogger.logInfo("stat_sum(%d): %d\n", i.U, stat_sum(i))
      }
      for (i <- 0 until COLLECT_STAT_PROCESS_BYTES) {
        CompressAccelLogger.logInfo("input_symbol(%d): %d\n", i.U, input_symbol(i))
      }
      CompressAccelLogger.logInfo("avail_bytes: %d, cur_max_value: %d\n", avail_bytes, cur_max_value)
    }
  }

  cnt_tot := symbol_stats.reduce(_ + _)

  /////////////////////////////////////////////////////////////////////////////
  // STATE_PROCESS_STATS && STATE_NORM_CNT
  // takes 256 cycles (we can just change this part to process multiple bytes per cycle)
  /////////////////////////////////////////////////////////////////////////////
  val processed_idx = RegInit(0.U(HUF_MAX_SYMBOLS1_LOG2.W))
  when (state === STATE_PROCESS_STATS) {
    processed_idx := processed_idx + 1.U

    val cur_stat_val = symbol_stats(processed_idx)
    cnt_min := Mux((cnt_min > cur_stat_val) && (cur_stat_val > 0.U), cur_stat_val, cnt_min)
  }


  val divider = Module(new PipelinedDivider(32))
  divider.io.start := false.B
  divider.io.A := cnt_tot
  divider.io.B := cnt_min

  when (state === STATE_PMIN_RECIP) {
    divider.io.start := true.B
  }

  val pmin_reciprocol = Reg(UInt(32.W))
  val pmin_reciprocol_log2 = Wire(UInt(32.W))
  pmin_reciprocol := Mux(divider.io.done, divider.io.Q, pmin_reciprocol)
// pmin_reciprocol := cnt_tot / cnt_min
  pmin_reciprocol_log2 := (31.U - PriorityEncoder(Reverse(pmin_reciprocol))) + 1.U

  val renormalize = RegInit(false.B)
  when (state === STATE_NORM_CNT && ((pmin_reciprocol_log2 >= (HUF_TABLELOG_MAX-1).U) || renormalize)) {
    symbol_stats.foreach(x => x := (x + 1.U) >> 1.U)
    cnt_min := (cnt_min + 1.U) >> 1.U

    renormalize := false.B
  }


  ////////////////////////////////////////////////////////////////////////////
  // STATE_SORT_CNT && STATE_BUILD_TREE
  ////////////////////////////////////////////////////////////////////////////
  val CMD_DEQ = 0.U
  val CMD_ENQ = 1.U

  val root_node_enqued = RegInit(false.B)

  val priority_queue = Module(new PriorityQueue(HUF_MAX_SYMBOLS1, 30, new ValueInfo)).io
  val pq_insert_idx = RegInit(0.U(HUF_MAX_SYMBOLS1_LOG2.W))

  val min_cnt_nodes = Seq.fill(2)(Module(new Queue(KeyValue(10, new ValueInfo), 2)).io)
  val min_cnt_insert_idx = RegInit(0.U(1.W))

  val is_symbol_stat_nonzero = symbol_stats(pq_insert_idx) > 0.U
  val pq_state_sort_enq_fire = DecoupledHelper(priority_queue.enq.ready,
                                               state === STATE_SORT_CNT,
                                               is_symbol_stat_nonzero)

  val pq_enque_enable = min_cnt_nodes(0).deq.valid && min_cnt_nodes(1).deq.valid
  val pq_state_build_tree_deq_fire = DecoupledHelper(priority_queue.deq.valid,
                                                     min_cnt_nodes(0).enq.ready,
                                                     min_cnt_nodes(1).enq.ready,
                                                     state === STATE_BUILD_TREE,
                                                     !pq_enque_enable)
  val pq_state_build_tree_enq_fire = DecoupledHelper(priority_queue.enq.ready,
                                                     min_cnt_nodes(0).deq.valid,
                                                     min_cnt_nodes(1).deq.valid,
                                                     state === STATE_BUILD_TREE)

  min_cnt_nodes(0).deq.ready := pq_state_build_tree_enq_fire.fire(min_cnt_nodes(0).deq.valid) || (state =/= STATE_BUILD_TREE) // Hack : root_node_enqued to clear the queues once the tree build is done
  min_cnt_nodes(1).deq.ready := pq_state_build_tree_enq_fire.fire(min_cnt_nodes(1).deq.valid) || (state =/= STATE_BUILD_TREE)

  min_cnt_nodes(0).enq.valid := pq_state_build_tree_deq_fire.fire(min_cnt_nodes(0).enq.ready, min_cnt_insert_idx === 0.U)
  min_cnt_nodes(0).enq.bits.key := priority_queue.deq.bits.key
  min_cnt_nodes(0).enq.bits.value.symbol := priority_queue.deq.bits.value.symbol

  min_cnt_nodes(1).enq.valid := pq_state_build_tree_deq_fire.fire(min_cnt_nodes(1).enq.ready, min_cnt_insert_idx === 1.U)
  min_cnt_nodes(1).enq.bits.key := priority_queue.deq.bits.key
  min_cnt_nodes(1).enq.bits.value.symbol := priority_queue.deq.bits.value.symbol

  val nxt_hufftree_node = RegInit(0.U(10.W))
  val nxt_key = min_cnt_nodes(0).deq.bits.key + min_cnt_nodes(1).deq.bits.key

  val pq_state_build_tree_deq_root_fire = DecoupledHelper(priority_queue.deq.valid,
                                                          root_node_enqued)

  priority_queue.enq.valid := pq_state_sort_enq_fire.fire || pq_state_build_tree_enq_fire.fire
  priority_queue.enq.bits.key := Mux(state === STATE_SORT_CNT, symbol_stats(pq_insert_idx), nxt_key)
  priority_queue.enq.bits.value.symbol := Mux(state === STATE_SORT_CNT, pq_insert_idx, 
                                              nxt_hufftree_node + HUF_MAX_SYMBOLS1.U)

  priority_queue.deq.ready := pq_state_build_tree_deq_fire.fire || pq_state_build_tree_deq_root_fire.fire

  when (pq_state_sort_enq_fire.fire(is_symbol_stat_nonzero)) {
    pq_insert_idx := pq_insert_idx + 1.U
  }
 
  when (pq_state_build_tree_deq_fire.fire) {
    min_cnt_insert_idx := min_cnt_insert_idx + 1.U
  }

  // 1.8 kB
  val huff_tree_node_idx = RegInit(VecInit(Seq.fill(HUF_MAX_SYMBOLS1)(0.U(9.W))))
  val huff_tree_node_cnt = RegInit(VecInit(Seq.fill(HUF_MAX_SYMBOLS1)(0.U(32.W))))
  val huff_tree_parent = RegInit(VecInit(Seq.fill(HUF_MAX_SYMBOLS1)(0.U(9.W))))
  val huff_tree_leaf = RegInit(VecInit(Seq.fill(HUF_MAX_SYMBOLS1)(0.U(10.W)))) // FIXME : minimal bit width?

  when (pq_state_build_tree_enq_fire.fire) {
    // TODO : update tree
    nxt_hufftree_node := nxt_hufftree_node + 1.U

    for (i <- 0 until 2) {
      val child_idx = min_cnt_nodes(i).deq.bits.value.symbol

      when (child_idx < HUF_MAX_SYMBOLS1.U) { // leaf node of the tree
        huff_tree_leaf(child_idx) := nxt_hufftree_node + HUF_MAX_SYMBOLS1.U
      } .otherwise { // inner node of the tree
        huff_tree_parent(child_idx - HUF_MAX_SYMBOLS1.U) := nxt_hufftree_node + HUF_MAX_SYMBOLS1.U
      }
    }
  }

  val root_node_idx = RegInit(0.U(10.W))
  when (pq_state_build_tree_deq_root_fire.fire) {
    // subtract 1 since we added a extra 1
    root_node_idx := nxt_hufftree_node - 1.U
    nxt_hufftree_node := nxt_hufftree_node - 1.U
    root_node_enqued := false.B
  }

  when (pq_state_build_tree_enq_fire.fire && priority_queue.counter === 0.U) {
    root_node_enqued := true.B
  }

  ////////////////////////////////////////////////////////////////////////////
  // STATE_UPDATE_NBBITS
  ////////////////////////////////////////////////////////////////////////////
  val max_nbbits = RegInit(0.U(5.W))
  val set_leaf_node_nbbits  = RegInit(false.B)
  val cur_leaf_node_parent_idx = WireInit(0.U(10.W))

  val renormalize_required = WireInit(false.B)

  when (state === STATE_UPDATE_NBBITS) {
    val inner_node_parent_idx = huff_tree_parent(nxt_hufftree_node) - HUF_MAX_SYMBOLS1.U
    nxt_hufftree_node := nxt_hufftree_node - 1.U

    when (nxt_hufftree_node >= 0.U) {
      val nxt_hufftree_height = Mux(nxt_hufftree_node === root_node_idx, 0.U,
                                    huff_tree_node_cnt(inner_node_parent_idx) + 1.U)
      huff_tree_node_cnt(nxt_hufftree_node) := nxt_hufftree_height
      max_nbbits := Mux(max_nbbits < nxt_hufftree_height, nxt_hufftree_height, max_nbbits)
    }

    when (nxt_hufftree_node === 0.U) {
      set_leaf_node_nbbits := true.B
    }

    when (set_leaf_node_nbbits) {
      tmp_idx := tmp_idx + 1.U

      when (tmp_idx === (HUF_MAX_SYMBOLS1.U - 1.U)) {
        max_nbbits := max_nbbits + 1.U
        tmp_idx := 0.U
      }
      cur_leaf_node_parent_idx := huff_tree_leaf(tmp_idx) - HUF_MAX_SYMBOLS1.U
      val parent_node_height = huff_tree_node_cnt(cur_leaf_node_parent_idx)
      val nbbits =  Mux(parent_node_height > 0.U && huff_tree_leaf(tmp_idx) > 0.U, parent_node_height + 1.U,
                      Mux(cur_leaf_node_parent_idx === root_node_idx, 1.U,
                        0.U))
      dic(tmp_idx) := nbbits
      renormalize_required := nbbits > HUF_MAX_CODE_MAX_BITS.U
      renormalize := renormalize_required
    }
  }

  ////////////////////////////////////////////////////////////////////////////
  // STATE_UPDATE_NBBITS_PER_RANK && STATE_UPDATE_VAL_PER_RANK && STATE_DIC_SET_VALUE
  ////////////////////////////////////////////////////////////////////////////
  val nb_per_rank = RegInit(VecInit(Seq.fill(HUF_TABLELOG_MAX+1)(0.U(16.W))))
  val val_per_rank = RegInit(VecInit(Seq.fill(HUF_TABLELOG_MAX+1)(0.U(16.W))))

  // - assume cur_cnt is always smaller or equal to HUF_TABLELOG_MAX
  // - we can do this step in parallel, but it would result in a huge amount of 
  // wires & comparators for little gain
  // - this takes up to HUF_MAX_SYMBOLS1 cycles
  when (state === STATE_UPDATE_NBBIT_PER_RANK) {
    tmp_idx := Mux(tmp_idx === max_symbol_value, max_nbbits, tmp_idx + 1.U)

    val cur_cnt = dic(tmp_idx)
    nb_per_rank(cur_cnt) := nb_per_rank(cur_cnt) + 1.U
  }

  // - this takes only HUF_TABLELOG_MAX cycles so it is fine to use a FSM
  val val_per_rank_start = RegInit(0.U(16.W))
  when (state === STATE_UPDATE_VAL_PER_RANK) {
    tmp_idx := tmp_idx - 1.U
    val_per_rank(tmp_idx) := val_per_rank_start

    val nxt_val_per_rank_start = (val_per_rank_start + nb_per_rank(tmp_idx)) >> 1.U
    val_per_rank_start := nxt_val_per_rank_start
  }

  val huf_encoding = WireInit(0.U(32.W))

  when (state === STATE_DIC_SET_VALUE) {
    tmp_idx := tmp_idx + 1.U

    val nbbits = dic(tmp_idx)

// assert(nbbits <= HUF_MAX_CODE_MAX_BITS.U, "HUF_COMPRESSOR tree height for symbol is too high")

    val shift_up = 32.U - nbbits(3, 0)
    huf_encoding := val_per_rank(nbbits) << shift_up
    val_per_rank(nbbits) := val_per_rank(nbbits) + 1.U

    dic(tmp_idx) := dic(tmp_idx) | huf_encoding

    when (tmp_idx === max_symbol_value) {
      tmp_idx := 0.U
    }
  }

  ////////////////////////////////////////////////////////////////////////////
  // STATE_WRITE_DIC
  ////////////////////////////////////////////////////////////////////////////
  val print_before_writing_dic = RegInit(true.B)
  when (print_before_writing_dic && state === STATE_WRITE_DIC) {
    CompressAccelLogger.logInfo("DICBUILDER_STATE_WRITE_DIC\n")
    for (i <- 0 until HUF_MAX_SYMBOLS1) {
      val idx = HUF_MAX_SYMBOLS1-1-i
      CompressAccelLogger.logInfo("dic(%d): %d\n", idx.U, dic(idx))
    }
    CompressAccelLogger.logInfo("max_symbol_value: %d\n", max_symbol_value)
    print_before_writing_dic := false.B
  }

  val fse_compressor = Module(new FSECompressorHufWeights)
  val nb_seq_received = RegInit(false.B)

  // assumes that the sizes of the buffers are large enough to accomodate all the weights
  val header_buf1 = Module(new ZstdCompressorLitRotBuf)
  val header_buf2 = Module(new ZstdCompressorReverseLitRotBuf)

  fse_compressor.io.nb_seq.bits := max_symbol_value
  fse_compressor.io.nb_seq.valid := !nb_seq_received && (state === STATE_WRITE_DIC)
  when (fse_compressor.io.nb_seq.fire) {
    nb_seq_received := true.B
  }

  val HEADER_BYTES_PER_CYCLE = p(HufCompressDicBuilderProcessedHeaderBytesPerCycle)
  val weight_sent_cnt = RegInit(0.U(HUF_MAX_SYMBOLS1_LOG2.W))

  val header_buf_insert_fire = DecoupledHelper(
    header_buf1.io.memwrites_in.ready,
    header_buf2.io.memwrites_in.ready,
    state === STATE_WRITE_DIC,
    (weight_sent_cnt < max_symbol_value)
  )

  val huf_weights_forward = WireInit(VecInit(Seq.fill(HEADER_BYTES_PER_CYCLE)(0.U(8.W))))
  val huf_weights_reverse = WireInit(VecInit(Seq.fill(HEADER_BYTES_PER_CYCLE)(0.U(8.W))))
  val entry_valid_vec = WireInit(VecInit(Seq.fill(HEADER_BYTES_PER_CYCLE)(false.B)))

  for (i <- 0 until HEADER_BYTES_PER_CYCLE) {
    entry_valid_vec(i) := (max_symbol_value - 1.U) >= (weight_sent_cnt + i.U)

    val dic_idx_forward = weight_sent_cnt + i.U
    val nbbits_forward = dic(dic_idx_forward)(3, 0)
    huf_weights_forward(i) := Mux(!entry_valid_vec(i), 0.U,
                                Mux(nbbits_forward > 0.U, max_nbbits + 1.U - nbbits_forward,
                                  0.U))

    val dic_idx_reverse = (max_symbol_value - 1.U) - (weight_sent_cnt + i.U)
    val nbbits_reverse = dic(dic_idx_reverse)(3, 0)
    huf_weights_reverse(i) := Mux(!entry_valid_vec(i), 0.U,
                                Mux(nbbits_reverse > 0.U, max_nbbits + 1.U - nbbits_reverse,
                                  0.U))
  }


  val header_valid_bytes = entry_valid_vec.map(_.asUInt).reduce(_ +& _)
  val header_end = (weight_sent_cnt + header_valid_bytes) >= (max_symbol_value - 1.U)

  val huf_weights_forward_cat = Cat(huf_weights_forward.reverse)
  val huf_weights_reverse_cat = Cat(huf_weights_reverse.reverse)


  when (header_buf_insert_fire.fire) {
    weight_sent_cnt := weight_sent_cnt + header_valid_bytes
  }

  header_buf1.io.memwrites_in.valid := header_buf_insert_fire.fire(header_buf1.io.memwrites_in.ready)
  header_buf1.io.memwrites_in.bits.data := huf_weights_forward_cat
  header_buf1.io.memwrites_in.bits.validbytes := header_valid_bytes
  header_buf1.io.memwrites_in.bits.end_of_message := header_end

  header_buf2.io.memwrites_in.valid := header_buf_insert_fire.fire(header_buf2.io.memwrites_in.ready)
  header_buf2.io.memwrites_in.bits.data := huf_weights_reverse_cat
  header_buf2.io.memwrites_in.bits.validbytes := header_valid_bytes
  header_buf2.io.memwrites_in.bits.end_of_message := header_end


  val fse_input_stream_consumed_bytes = RegInit(0.U(64.W))
  val remaining_bytes = max_symbol_value - fse_input_stream_consumed_bytes
  when (fse_compressor.io.input_stream.output_valid && fse_compressor.io.input_stream.output_ready) {
    fse_input_stream_consumed_bytes := fse_input_stream_consumed_bytes + fse_compressor.io.input_stream.user_consumed_bytes

    CompressAccelLogger.logInfo("fse_compressor.io.input_stream.user_consumed_bytes: %d\n", fse_compressor.io.input_stream.user_consumed_bytes)
    CompressAccelLogger.logInfo("fse_input_stream_consumed_bytes: %d\n", fse_input_stream_consumed_bytes)
    CompressAccelLogger.logInfo("remaining_bytes: %d\n", remaining_bytes)
    CompressAccelLogger.logInfo("fse_compressor.io.input_stream.output_last_chunk: %d\n", fse_compressor.io.input_stream.output_last_chunk)
  }
  fse_compressor.io.input_stream <> header_buf1.io.consumer // forward
  fse_compressor.io.input_stream.output_last_chunk := (remaining_bytes <= HEADER_BYTES_PER_CYCLE.U)

  val fse_input_stream_consumed_bytes2 = RegInit(0.U(64.W))
  val remaining_bytes2 = max_symbol_value - fse_input_stream_consumed_bytes2
  when (fse_compressor.io.input_stream2.output_valid && fse_compressor.io.input_stream2.output_ready) {
    fse_input_stream_consumed_bytes2 := fse_input_stream_consumed_bytes2 + fse_compressor.io.input_stream2.user_consumed_bytes

    CompressAccelLogger.logInfo("fse_compressor.io.input_stream2.user_consumed_bytes: %d\n", fse_compressor.io.input_stream2.user_consumed_bytes)
    CompressAccelLogger.logInfo("fse_input_stream_consumed_bytes2: %d\n", fse_input_stream_consumed_bytes2)
    CompressAccelLogger.logInfo("remaining_bytes2: %d\n", remaining_bytes2)
    CompressAccelLogger.logInfo("fse_compressor.io.input_stream2.output_last_chunk: %d\n", fse_compressor.io.input_stream2.output_last_chunk)
  }
  fse_compressor.io.input_stream2 <> header_buf2.io.consumer // reverse
  fse_compressor.io.input_stream2.output_last_chunk := (remaining_bytes2 <= HEADER_BYTES_PER_CYCLE.U)

  val compressed_header_fire = DecoupledHelper(
    io.header_writes.ready,
    fse_compressor.io.memwrites_out.valid,
    io.header_written_bytes.ready)

  io.header_writes.bits := fse_compressor.io.memwrites_out.bits
  io.header_writes.valid := compressed_header_fire.fire(io.header_writes.ready)
  fse_compressor.io.memwrites_out.ready := compressed_header_fire.fire(fse_compressor.io.memwrites_out.valid)

  val track_header_written_bytes = RegInit(0.U(64.W))
  when (compressed_header_fire.fire) {
    when (fse_compressor.io.memwrites_out.bits.end_of_message) {
      track_header_written_bytes := 0.U
    } .otherwise {
      track_header_written_bytes := track_header_written_bytes + fse_compressor.io.memwrites_out.bits.validbytes
    }
    CompressAccelLogger.logInfo("track_header_written_bytes: %d\n", track_header_written_bytes + fse_compressor.io.memwrites_out.bits.validbytes)
  }

  io.header_written_bytes.valid := compressed_header_fire.fire(io.header_written_bytes.ready, fse_compressor.io.memwrites_out.bits.end_of_message)
  io.header_written_bytes.bits := track_header_written_bytes + fse_compressor.io.memwrites_out.bits.validbytes + 1.U

  io.header_size_info <> fse_compressor.io.header_size_info // op[0] in huf_compress.c HUF_writeCTable_wksp
  when (io.header_size_info.fire) {
    CompressAccelLogger.logInfo("header_size_info: %d\n", io.header_size_info.bits)
    CompressAccelLogger.logInfo("max_nbbits: %d\n", max_nbbits)
  }

  ///////////////////////////////////////////////////////////////////////////
  // STATE_LOOKUP
  ///////////////////////////////////////////////////////////////////////////
  val symbol_info_q = Seq.fill(unroll_cnt)(Module(new Queue(new HufSymbolInfo, cmd_que_depth)).io)
  val dic_info_q = Seq.fill(unroll_cnt)(Module(new Queue(new HufCompDicInfo, cmd_que_depth)).io)
  val last_symbol_lookup_fired_vec = WireInit(VecInit(Seq.fill(unroll_cnt)(false.B)))
  for (i <- 0 until unroll_cnt) {
    symbol_info_q(i).enq <> io.symbol_info(i)
    io.dic_info(i) <> dic_info_q(i).deq

    val lookup_fire = DecoupledHelper(state === STATE_LOOKUP,
                                      symbol_info_q(i).deq.valid,
                                      dic_info_q(i).enq.ready)
    symbol_info_q(i).deq.ready := lookup_fire.fire(symbol_info_q(i).deq.valid)
    dic_info_q(i).enq.valid := lookup_fire.fire(dic_info_q(i).enq.ready)

    dic_info_q(i).enq.bits.entry := dic(symbol_info_q(i).deq.bits.symbol)
    dic_info_q(i).enq.bits.from_last_symbol := symbol_info_q(i).deq.bits.last_symbol

    last_symbol_lookup_fired_vec(i) := lookup_fire.fire && symbol_info_q(i).deq.bits.last_symbol

    when (lookup_fire.fire && symbol_info_q(i).deq.bits.last_symbol) {
      CompressAccelLogger.logInfo("HUF_DICBUILDER last_symbol lookup fired\n")
    }
  }


  val print_dic_value = RegInit(false.B)

  when (!print_dic_value && state === STATE_LOOKUP) {
    print_dic_value := true.B

    for (i <- 0 until HUF_MAX_SYMBOLS1) {
      when (i.U <= max_symbol_value) {
        val nbbits = dic(i)(3, 0)
        val symbol = dic(i) >> (32.U - nbbits)
        CompressAccelLogger.logInfo("dic(0x%x) : 0x%x, nbbits: %d, symbol: %d\n", i.U, dic(i), nbbits, symbol)
      }
    }
  }
  ///////////////////////////////////////////////////////////////////////////
  // STATE_NORMALIZE_FAILED
  ///////////////////////////////////////////////////////////////////////////
  when (state === STATE_NORMALIZE_FAILED) {
    tmp_idx := 0.U
    root_node_enqued := false.B
    pq_insert_idx := 0.U
    min_cnt_insert_idx := 0.U
    nxt_hufftree_node := 0.U
    root_node_idx := 0.U
    for (i <- 0 until HUF_MAX_SYMBOLS1) {
      huff_tree_node_idx(i) := 0.U
      huff_tree_node_cnt(i) := 0.U
      huff_tree_parent(i) := 0.U
      huff_tree_leaf(i) := 0.U
    }
    max_nbbits := 0.U
    set_leaf_node_nbbits := false.B
  }

  // init
  io.init_dictionary.ready := true.B
  when (io.init_dictionary.valid) {
    CompressAccelLogger.logInfo("HUF_DICBUILDER Last sybmol lookup fired\n")

    for (i <- 0 until HUF_MAX_SYMBOLS1) {
      symbol_stats(i) := 0.U
    }

    for (i <- 0 until HUF_MAX_SYMBOLS1) {
      dic(i) := 0.U
    }
    tmp_idx := 0.U
    cnt_min := INF.U
    max_symbol_value := 0.U
    processed_idx := 0.U
    pq_insert_idx := 0.U
    min_cnt_insert_idx := 0.U
    nxt_hufftree_node := 0.U
    root_node_enqued := false.B
    for (i <- 0 until HUF_MAX_SYMBOLS1) {
      huff_tree_node_idx(i) := 0.U
      huff_tree_node_cnt(i) := 0.U
      huff_tree_parent(i) := 0.U
      huff_tree_leaf(i) := 0.U
    }
    root_node_idx := 0.U
    max_nbbits := 0.U
    set_leaf_node_nbbits := false.B

    for (i <- 0 until HUF_TABLELOG_MAX+1) {
      nb_per_rank(i) := 0.U
      val_per_rank(i) := 0.U
    }

    val_per_rank_start := 0.U
    print_before_writing_dic := true.B
    nb_seq_received := false.B
    weight_sent_cnt := 0.U
    fse_input_stream_consumed_bytes := 0.U
    fse_input_stream_consumed_bytes2 := 0.U
    track_header_written_bytes := 0.U
    print_dic_value := false.B
  }

  val prev_state = RegNext(state)
  when (prev_state =/= state) {
    CompressAccelLogger.logCritical("HufCompressorDicBuilder state transition from %d to %d\n", prev_state, state)
  }


  /////////////////////////////////////////////////////////////////////////////
  // state machine transitions
  /////////////////////////////////////////////////////////////////////////////
  switch (state) {
    is (STATE_COLLECT_STATS) {
      when (io.cnt_stream.output_valid && io.cnt_stream.output_last_chunk && (consumed_bytes === avail_bytes)) {
        state := STATE_PROCESS_STATS
      }
    }

    is (STATE_PROCESS_STATS) {
      when (processed_idx === HUF_MAX_SYMBOLS1.U - 1.U) {
        state := STATE_PMIN_RECIP
      }
    }

    is (STATE_PMIN_RECIP) {
      when (divider.io.done) {
        state := STATE_NORM_CNT
      }
    }

    is (STATE_NORM_CNT) {
      when (pmin_reciprocol_log2 <= HUF_TABLELOG_MAX.U) {
        state := STATE_SORT_CNT
      }
    }

    is (STATE_SORT_CNT) {
      when (pq_insert_idx + 1.U === HUF_MAX_SYMBOLS1.U) {
        state := STATE_BUILD_TREE
      }
    }

    is (STATE_BUILD_TREE) {
      when (root_node_enqued) {
        state := STATE_UPDATE_NBBITS
      }
    }

    is (STATE_UPDATE_NBBITS) {
      when (renormalize_required) {
        state := STATE_NORMALIZE_FAILED
      } .elsewhen (set_leaf_node_nbbits && (tmp_idx === HUF_MAX_SYMBOLS1.U - 1.U)) {
        state := STATE_UPDATE_NBBIT_PER_RANK
      }
    }

    is (STATE_UPDATE_NBBIT_PER_RANK) {
      when (tmp_idx === max_symbol_value) {
        state := STATE_UPDATE_VAL_PER_RANK
      }
    }

    is (STATE_UPDATE_VAL_PER_RANK) {
      when (tmp_idx === 1.U) {
        state := STATE_DIC_SET_VALUE
      }
    }

    is (STATE_DIC_SET_VALUE) {
      when (tmp_idx === max_symbol_value) {
        state := STATE_WRITE_DIC
      }
    }

    is (STATE_WRITE_DIC) {
      when (io.header_written_bytes.fire) {
        state := STATE_LOOKUP
      }
    }

    is (STATE_LOOKUP) {
      when (io.init_dictionary.valid) {
        state := STATE_COLLECT_STATS
      }
    }

    is (STATE_NORMALIZE_FAILED) {
      state := STATE_NORM_CNT
    }
  }
}
