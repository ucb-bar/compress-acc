package compressacc

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
// import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheArbiter}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._

class HufDecompressorLookupIdx extends Bundle {
  val idx = UInt(16.W)
}

class HufDecompressorDicEntry extends Bundle { 
  val symbol = UInt(8.W)
  val numbit = UInt(8.W)
}

class HufDecompressLiteralExpanderCommand extends Bundle {
  val block_type = UInt(2.W)
  val decomp_size = UInt(20.W)
  val comp_size = UInt(20.W)
  val single_stream = Bool()
  val max_bits = UInt(log2Ceil(11 + 1).W)
}

class HufDecompressorHeaderExpanderIO(val decomp_at_once: Int)(implicit p: Parameters) 
extends Bundle {
  val src_all = Flipped(Decoupled(new StreamInfo))
// val dic_src_info = Flipped(Decoupled(new StreamInfo))

  // For literal memloader router
  val literal_router_sel = Output(UInt(1.W))

  // For header memloader
  val src_header = Decoupled(new StreamInfo)
  val header_stream = Flipped(new MemLoaderConsumerBundle)
  val header_router_sel = Output(UInt(2.W))

  // For literal expander
  val literal_src_info = Decoupled(new StreamInfo)
  val literal_cmd = Decoupled(new HufDecompressLiteralExpanderCommand)
  val lookup_idx = Vec(decomp_at_once-3, Flipped(Decoupled(new HufDecompressorLookupIdx)))
  val dic_entry = Vec(decomp_at_once-3, Decoupled(new HufDecompressorDicEntry))
  val literal_expander_done = Flipped(Decoupled(Bool()))

  // For writer
  val decompressed_bytes = Decoupled(UInt(20.W))

  // For FSE decoder
  val huf_header_info = Decoupled(new StreamInfo)
  val huf_weight = Flipped(Decoupled(UInt(8.W)))
  val num_symbols = Flipped(Decoupled(UInt(64.W)))

  val decomp_literal_info = Decoupled(new HufInfo)
}

class HufDecompressorHeaderExpander(val decomp_at_once: Int, val cmd_que_depth: Int)
(implicit p: Parameters) extends Module with MemoryOpConstants {
  val io = IO(new HufDecompressorHeaderExpanderIO(decomp_at_once))

 /*
  * The last symbol's Weight is deduced from previously decoded ones, by 
  * completing to the nearest power of 2. This power of 2 gives Max_Number_of_Bits, 
  * the depth of the current tree. 
  * Max_Number_of_Bits must be <= 11, otherwise the representation is considered 
  * corrupted. 
  * - 2^7 * 16 = 2^11
  */
  // The default HUF_TABLELOG is 11
  val weight_table = RegInit(VecInit(Seq.fill(1 << 11)(0.U(8.W))))
  val symbol_table = RegInit(VecInit(Seq.fill(1 << 11)(0.U(8.W))))
  val numbit_table = RegInit(VecInit(Seq.fill(1 << 11)(0.U(8.W))))

  val STATE_IDLE = 0.U
  val STATE_GET_BLOCKTYPE_SIZEFORMAT = 1.U
  val STATE_RLE_RAW_DECOMP_SIZE = 2.U
  val STATE_HUF_DECOMP_COMP_SIZE = 3.U
  val STATE_HUF_LOAD_HEADER_SIZE = 4.U
  val STATE_HUF_DECODE_JUMP_TABLE = 5.U
  val STATE_HUF_DECODE_HEADER = 6.U
  val STATE_BUILD_HUF_DIC = 7.U
  val STATE_SET_LITERAL_EXPANDER = 8.U
  val STATE_LOOKUPS = 9.U
  val STATE_RLE_RAW_EXPAND = 10.U
  val STATE_DONE = 11.U

  val state = RegInit(0.U(4.W))

  // States & variables for BUILD_HUF_DIC
  val BUILD_HUF_DIC_IDLE = 0.U
  val BUILD_HUF_DIC_GET_PER_SYMBOL_NUMBITS = 1.U
  val BUILD_HUF_DIC_INIT_RANK_COUNT = 2.U
  val BUILD_HUF_DIC_INIT_RANK_IDX = 3.U
  val BUILD_HUF_DIC_INIT_NUMBIT_TABLE = 4.U
  val BUILD_HUF_DIC_GET_CODE_AND_LEN = 5.U
  val BUILD_HUF_DIC_INIT_SYMBOL_TABLE = 6.U

  val build_dic_state = RegInit(0.U(3.W))
  val build_dic_done = RegInit(false.B)

// val last_weight = RegInit(0.U(64.W))

  val huf_max_bits = RegInit(0.U(4.W))
  val huf_num_symbols = RegInit(0.U(12.W))
  val huf_table_size = RegInit(0.U(12.W))

  val bidx = RegInit(0.U(11.W))
  val bits = RegInit(VecInit(Seq.fill(256 + 1)(0.U(4.W))))

  val weight_table_idx = RegInit(0.U(11.W))
  val weight_sum = RegInit(0.U(64.W))

  val tidx = RegInit(0.U(16.W))
  val rank_count = RegInit(VecInit(Seq.fill(18)(0.U(16.W))))
  val rank_idx = RegInit(VecInit(Seq.fill(18)(0.U(32.W))))
  val cur_rank_idx_reg = RegInit(0.U(32.W))
  //val bits_bidx_reg = RegInit(0.U(4.W))

  val cur_code = RegInit(0.U(32.W))
  val cur_len  = RegInit(0.U(32.W))

  // States & variables for HUF_DECODE_HEADER
  val HUF_DECODE_HEADER_FSE_START = 0.U
  val HUF_DECODE_HEADER_FSE_REC_WEIGHTS = 1.U

  val huf_decode_header_state = RegInit(0.U(3.W))


  val src_lit_start_addr = RegInit(0.U(64.W))
  val src_all_q = Module(new Queue(new StreamInfo, cmd_que_depth)).io
  val src_all_fire = DecoupledHelper(io.src_all.valid, src_all_q.enq.ready)

  io.src_all.ready := src_all_fire.fire(io.src_all.valid)
  src_all_q.enq.valid := src_all_fire.fire(src_all_q.enq.ready)
  src_all_q.enq.bits <> io.src_all.bits
  src_all_q.deq.ready := false.B

  // for src memloader router
  val LITERAL_ROUTER_SEL_FSE_DECODER = 0.U
  val LITERAL_ROUTER_SEL_LITERAL_EXPANDER = 1.U

  val literal_router_sel = RegInit(0.U(1.W))
  io.literal_router_sel := literal_router_sel

  val src_lit_valid = RegInit(false.B)
  val src_lit_valid_set = RegInit(false.B)

  // For header memloader
  val huf_header_size = RegInit(0.U(8.W))
  val huf_header_size_for_jump = Wire(UInt(8.W))
  huf_header_size_for_jump := Mux(huf_header_size >= 128.U,
    ((huf_header_size-126.U)>>1.U),
    huf_header_size)

  val src_header_valid_set = RegInit(false.B)
  val src_header_q = Module(new Queue(new StreamInfo, cmd_que_depth)).io
  io.src_header <> src_header_q.deq
  src_header_q.enq.valid := false.B
  src_header_q.enq.bits.ip := 0.U
  src_header_q.enq.bits.isize := 0.U

  val HEADER_ROUTER_SEL_HEADER_EXPANDER = 0.U
  val HEADER_ROUTER_SEL_FSE_DECODER = 1.U
  val HEADER_ROUTER_SEL_LITERAL_EXPANDER = 2.U

  val header_router_sel = RegInit(0.U(2.W))
  header_router_sel := HEADER_ROUTER_SEL_HEADER_EXPANDER
  io.header_router_sel := header_router_sel

  val lit_header_first_byte = RegInit(0.U(8.W))
  val block_type = RegInit(0.U(2.W))
  val size_format = RegInit(0.U(2.W))
  val single_stream = RegInit(false.B)
  val treeless_decoding = RegInit(false.B)

  val comp_size = RegInit(0.U(20.W))
  val comp4_size = RegInit(VecInit(Seq.fill(4)(0.U(20.W))))

  val decomp_size = RegInit(0.U(20.W))

  val lit_header_size = RegInit(0.U(6.W))
  val decomp_streams = RegInit(0.U(3.W))

  val literal_src_info_q = Module(new Queue(new StreamInfo, cmd_que_depth)).io
  io.literal_src_info <> literal_src_info_q.deq
  literal_src_info_q.enq.valid := false.B
  literal_src_info_q.enq.bits.ip := 0.U
  literal_src_info_q.enq.bits.isize := 0.U

  val literal_cmd_q = Module(new Queue(new HufDecompressLiteralExpanderCommand, cmd_que_depth)).io
  io.literal_cmd <> literal_cmd_q.deq
  literal_cmd_q.enq.valid := false.B
  literal_cmd_q.enq.bits.block_type := 0.U
  literal_cmd_q.enq.bits.comp_size := 0.U
  literal_cmd_q.enq.bits.decomp_size := 0.U
  literal_cmd_q.enq.bits.max_bits := 0.U
  literal_cmd_q.enq.bits.single_stream := false.B

  val literal_expander_done_q = Module(new Queue(Bool(), cmd_que_depth)).io
  literal_expander_done_q.enq <> io.literal_expander_done
  literal_expander_done_q.deq.ready := false.B

  val decompressed_bytes_q = Module(new Queue(UInt(20.W), cmd_que_depth)).io
  io.decompressed_bytes <> decompressed_bytes_q.deq
  decompressed_bytes_q.enq.valid := false.B
  decompressed_bytes_q.enq.bits := 0.U

  val huf_header_info_q = Module(new Queue(new StreamInfo, cmd_que_depth)).io
  io.huf_header_info <> huf_header_info_q.deq
  huf_header_info_q.enq.valid := false.B
  huf_header_info_q.enq.bits.ip := 0.U
  huf_header_info_q.enq.bits.isize := 0.U

  val huf_weight_q = Module(new Queue(UInt(8.W), cmd_que_depth)).io
  huf_weight_q.enq <> io.huf_weight
  huf_weight_q.deq.ready := false.B

  val num_symbols_q = Module(new Queue(UInt(64.W), cmd_que_depth)).io
  num_symbols_q.enq <> io.num_symbols
  num_symbols_q.deq.ready := false.B

  val lookup_idx_q = Seq.fill(decomp_at_once-3)(Module(new Queue(new HufDecompressorLookupIdx, cmd_que_depth)).io)
  val dic_entry_q = Seq.fill(decomp_at_once-3)(Module(new Queue(new HufDecompressorDicEntry, cmd_que_depth)).io)

  for (i <- 0 until decomp_at_once-3) {
    lookup_idx_q(i).enq <> io.lookup_idx(i)
    io.dic_entry(i) <> dic_entry_q(i).deq

    dic_entry_q(i).enq.valid := false.B
    dic_entry_q(i).enq.bits.symbol := 0.U
    dic_entry_q(i).enq.bits.numbit := 0.U

    lookup_idx_q(i).deq.ready := false.B
  }

  io.header_stream.output_ready := false.B
  io.header_stream.user_consumed_bytes := 0.U

  val decomp_literal_info_q = Module(new Queue(new HufInfo, cmd_que_depth)).io
  io.decomp_literal_info <> decomp_literal_info_q.deq

  decomp_literal_info_q.enq.valid := false.B
  decomp_literal_info_q.enq.bits.src_consumed_bytes := 0.U
  decomp_literal_info_q.enq.bits.dst_written_bytes := 0.U

  switch (state) {
    is (STATE_IDLE) {
      when (src_all_q.deq.valid) {
        state := STATE_GET_BLOCKTYPE_SIZEFORMAT

        src_lit_start_addr := src_all_q.deq.bits.ip
        src_all_q.deq.ready := true.B
      }
    }

    is (STATE_GET_BLOCKTYPE_SIZEFORMAT) {
      header_router_sel := HEADER_ROUTER_SEL_HEADER_EXPANDER

      // Load 1 byte from memloader
      src_header_q.enq.valid := !src_header_valid_set
      src_header_q.enq.bits.ip := src_lit_start_addr
      src_header_q.enq.bits.isize := 1.U

      when (src_header_q.enq.fire) {
        src_header_valid_set := true.B
      }

      io.header_stream.output_ready := true.B
      when (io.header_stream.output_valid) {
        io.header_stream.user_consumed_bytes := 1.U

        val block_type_w = io.header_stream.output_data & 3.U
        val size_format_w = (io.header_stream.output_data & 15.U) >> 2.U
        block_type := block_type_w
        size_format := size_format_w
        lit_header_first_byte := io.header_stream.output_data & 255.U

        src_header_valid_set := false.B
        state := Mux(block_type_w <= 1.U, STATE_RLE_RAW_DECOMP_SIZE, STATE_HUF_DECOMP_COMP_SIZE)

        CompressAccelLogger.logInfo("HeaderExpander block_type: %d, size_format: %d\n", 
          block_type_w, size_format_w)
      }
    }

    is (STATE_RLE_RAW_DECOMP_SIZE) {
      when (size_format === 0.U || size_format === 2.U) {
        val decomp_size_w = lit_header_first_byte >> 3.U

        decompressed_bytes_q.enq.valid := true.B
        decompressed_bytes_q.enq.bits := decomp_size_w
        decomp_size := decomp_size_w

        decomp_literal_info_q.enq.valid := true.B
        decomp_literal_info_q.enq.bits.dst_written_bytes := decomp_size_w
        when(block_type===0.U){ //Raw          
          decomp_literal_info_q.enq.bits.src_consumed_bytes := decomp_size_w +& 1.U
        }.elsewhen(block_type===1.U){ //RLE
          decomp_literal_info_q.enq.bits.src_consumed_bytes := 2.U
        }        

        when (decompressed_bytes_q.enq.fire) {
          state := STATE_RLE_RAW_EXPAND
          lit_header_size := 1.U

          CompressAccelLogger.logInfo("HeaderExpander decomp_size: %d\n", 
                                      lit_header_first_byte >> 3.U)
        }
      } .otherwise {
        val src_header_ilen_w = Mux(size_format === 1.U, 1.U, 2.U)

        src_header_q.enq.valid := !src_header_valid_set
        src_header_q.enq.bits.ip := src_lit_start_addr + 1.U
        src_header_q.enq.bits.isize := src_header_ilen_w
        when (src_header_q.enq.fire) {
          src_header_valid_set := true.B
        }

        io.header_stream.output_ready := true.B
        when (io.header_stream.output_valid &&
             (src_header_ilen_w === io.header_stream.available_output_bytes) &&
             decompressed_bytes_q.enq.ready) {
          io.header_stream.user_consumed_bytes := src_header_ilen_w

          val cat_size = Cat(io.header_stream.output_data(15, 0), lit_header_first_byte) >> 4.U
          val size_bits = src_header_ilen_w * 8.U + 4.U
          val size_mask = (1.U << size_bits) - 1.U
          val decomp_size_w = cat_size & size_mask

          decompressed_bytes_q.enq.valid := true.B
          decompressed_bytes_q.enq.bits := decomp_size_w
          decomp_size := decomp_size_w

          decomp_literal_info_q.enq.valid := true.B
          decomp_literal_info_q.enq.bits.dst_written_bytes := decomp_size_w
          when(block_type===0.U){ //Raw          
            decomp_literal_info_q.enq.bits.src_consumed_bytes := decomp_size_w +& 1.U +& src_header_ilen_w
          }.elsewhen(block_type===1.U){ //RLE
            decomp_literal_info_q.enq.bits.src_consumed_bytes := src_header_ilen_w +& 2.U
          }

          lit_header_size := 1.U + src_header_ilen_w

          state := STATE_RLE_RAW_EXPAND
          CompressAccelLogger.logInfo("HeaderExpander decomp_size: %d\n", decomp_size_w)
        }
      }
    }

    is (STATE_HUF_DECOMP_COMP_SIZE) {
      when (size_format === 0.U) {
        single_stream := true.B
      }

      val src_header_ilen_w = Mux(size_format <= 1.U, 2.U, Mux(size_format === 2.U, 3.U, 4.U))

      src_header_q.enq.valid := !src_header_valid_set
      src_header_q.enq.bits.ip := src_lit_start_addr + 1.U
      src_header_q.enq.bits.isize := src_header_ilen_w

      when (src_header_q.enq.fire) {
        src_header_valid_set := true.B
      }

      io.header_stream.output_ready := true.B
      when (io.header_stream.output_valid &&
           (src_header_ilen_w === io.header_stream.available_output_bytes) &&
           decompressed_bytes_q.enq.ready &&
           decomp_literal_info_q.enq.ready) {
        io.header_stream.user_consumed_bytes := src_header_ilen_w

        val size_bits = (src_header_ilen_w(2, 0)*8.U + 4.U) >> 1.U
        val size_mask = (1.U << size_bits) - 1.U
        val cat_size = Cat(io.header_stream.output_data, lit_header_first_byte) >> 4.U
        val decomp_size_w = cat_size & size_mask
        val comp_size_w = (cat_size >> size_bits) & size_mask

        src_header_valid_set := false.B
        decompressed_bytes_q.enq.valid := true.B
        decompressed_bytes_q.enq.bits := decomp_size_w

        val nxt_lit_header_size = 1.U + src_header_ilen_w
        decomp_literal_info_q.enq.valid := true.B
        decomp_literal_info_q.enq.bits.src_consumed_bytes := comp_size_w + nxt_lit_header_size
        decomp_literal_info_q.enq.bits.dst_written_bytes := decomp_size_w

        comp_size := comp_size_w
        decomp_size := decomp_size_w
        lit_header_size := nxt_lit_header_size

        when (block_type === 2.U) {
          state := STATE_HUF_LOAD_HEADER_SIZE
        } .otherwise {
          treeless_decoding := true.B
          when (size_format === 0.U) {
            state := STATE_SET_LITERAL_EXPANDER
          } .otherwise {
            state := STATE_HUF_DECODE_JUMP_TABLE
          }
        }

        CompressAccelLogger.logInfo("HeaderExpander decomp size: %d, comp size: %d\n", 
          decomp_size_w, comp_size_w)
        CompressAccelLogger.logInfo("Huffman block type: %d\n", 
          block_type)
      }
    }

    is (STATE_HUF_LOAD_HEADER_SIZE) {
      src_header_q.enq.valid := !src_header_valid_set
      src_header_q.enq.bits.ip := src_lit_start_addr + lit_header_size
      src_header_q.enq.bits.isize := 1.U

      when (src_header_q.enq.fire) {
        src_header_valid_set := true.B
      }

      io.header_stream.output_ready := true.B
      when (io.header_stream.output_valid) {
        io.header_stream.user_consumed_bytes := 1.U

        val huf_header_size_w = io.header_stream.output_data & 255.U
        huf_header_size := huf_header_size_w

        src_header_valid_set := false.B

        when (single_stream) {
          state := STATE_HUF_DECODE_HEADER
        } .otherwise {
          state := STATE_HUF_DECODE_JUMP_TABLE
        }

        CompressAccelLogger.logInfo("HeaderExpander, STATE_LOAD_HEADER_SIZE\n")
        CompressAccelLogger.logInfo("HeaderExpander, huf_header_size: 0x%x\n", huf_header_size_w)
      }
    }

    is (STATE_HUF_DECODE_JUMP_TABLE) {
      src_header_q.enq.valid := !src_header_valid_set
      src_header_q.enq.bits.ip := src_lit_start_addr + lit_header_size + Mux(block_type === 2.U, 1.U + huf_header_size_for_jump, 0.U)
      src_header_q.enq.bits.isize := 6.U

      when (src_header_q.enq.fire) {
        src_header_valid_set := true.B
      }

      io.header_stream.output_ready := true.B
      when (io.header_stream.output_valid && io.header_stream.available_output_bytes === 6.U) {
        io.header_stream.user_consumed_bytes := 6.U

        val jump_bits = 16.U
        val jump_mask = (1.U << 16.U) - 1.U

        val comp_size0_w = io.header_stream.output_data & jump_mask
        val comp_size1_w = (io.header_stream.output_data >> jump_bits) & jump_mask
        val comp_size2_w = (io.header_stream.output_data >> (2.U*jump_bits)) & jump_mask
        val comp_size3_w = comp_size - comp_size0_w - comp_size1_w - comp_size2_w - 6.U - Mux(block_type === 2.U, 1.U + huf_header_size_for_jump, 0.U)

        comp4_size(0) := comp_size0_w
        comp4_size(1) := comp_size1_w
        comp4_size(2) := comp_size2_w
        comp4_size(3) := comp_size3_w

        src_header_valid_set := false.B
        when (treeless_decoding) {
          state := STATE_SET_LITERAL_EXPANDER
        } .otherwise {
          state := STATE_HUF_DECODE_HEADER
        }

        CompressAccelLogger.logInfo("HeaderExpander stream length: %d, %d, %d, %d\n",
          comp_size0_w, comp_size1_w, comp_size2_w, comp_size3_w)
      }
    }

    is (STATE_HUF_DECODE_HEADER) {
      when (huf_header_size >= 128.U) {
        header_router_sel := HEADER_ROUTER_SEL_HEADER_EXPANDER
// l2readresp_q.deq.ready := true.B

        val num_symbs = huf_header_size - 127.U
        val bytes = (num_symbs + 1.U) >> 1.U

        huf_num_symbols := num_symbs

        src_header_q.enq.valid := !src_header_valid_set
        src_header_q.enq.bits.ip := src_lit_start_addr + lit_header_size + 1.U
        src_header_q.enq.bits.isize := bytes

        when (src_header_q.enq.fire) {
          src_header_valid_set := true.B
        }

        io.header_stream.output_ready := true.B
        when (io.header_stream.output_valid) {
          io.header_stream.user_consumed_bytes := 1.U
          val cur_data = io.header_stream.output_data(7, 0)
          val weight_even = cur_data >> 4.U
          val weight_odd = cur_data & (0xF).U

          val weight_table_even = Mux(weight_table_idx < num_symbs, weight_even(3, 0), 0.U)
          val weight_table_odd = Mux(weight_table_idx + 1.U < num_symbs, weight_odd(3, 0), 0.U)
          val weight_sum_even = Mux(weight_table_even > 0.U, 1.U << (weight_table_even - 1.U), 0.U)
          val weight_sum_odd = Mux(weight_table_odd > 0.U, 1.U << (weight_table_odd - 1.U), 0.U)

          weight_table(weight_table_idx) := weight_table_even
          weight_table(weight_table_idx + 1.U) := weight_table_odd
          weight_table_idx := weight_table_idx + 2.U
          weight_sum := weight_sum + weight_sum_even + weight_sum_odd

          CompressAccelLogger.logInfo("HeaderExpander weight_table(%d): %d, weight_table(%d): %d\n", 
                        weight_table_idx, weight_table_even, weight_table_idx + 1.U, weight_table_odd)

          when (io.header_stream.output_last_chunk && io.header_stream.available_output_bytes === 1.U) {
            src_header_valid_set := false.B
            huf_header_size := bytes
            state := STATE_BUILD_HUF_DIC
          }
        }
      } .otherwise {
        literal_router_sel := LITERAL_ROUTER_SEL_FSE_DECODER
        header_router_sel := HEADER_ROUTER_SEL_FSE_DECODER

        switch (huf_decode_header_state) {
          is (HUF_DECODE_HEADER_FSE_START) {
            huf_header_info_q.enq.valid := true.B
            huf_header_info_q.enq.bits.ip := src_lit_start_addr + lit_header_size + 1.U
            huf_header_info_q.enq.bits.isize := huf_header_size

            when (huf_header_info_q.enq.fire) {
              huf_decode_header_state := HUF_DECODE_HEADER_FSE_REC_WEIGHTS
            }
          }

          is (HUF_DECODE_HEADER_FSE_REC_WEIGHTS) {
            huf_weight_q.deq.ready := true.B
            when (huf_weight_q.deq.fire) {
              val cur_weight_w = huf_weight_q.deq.bits

              weight_table_idx := weight_table_idx + 1.U
              weight_table(weight_table_idx) := cur_weight_w
              weight_sum := weight_sum + Mux(cur_weight_w > 0.U, 1.U << (cur_weight_w - 1.U), 0.U)

              CompressAccelLogger.logInfo("HeaderExpander huftable weight[%d]: %d\n", 
                                          weight_table_idx, cur_weight_w)
            }

            num_symbols_q.deq.ready := !huf_weight_q.deq.valid
            when (num_symbols_q.deq.fire) {
              huf_num_symbols := num_symbols_q.deq.bits
              state := STATE_BUILD_HUF_DIC

              CompressAccelLogger.logInfo("HeaderExpander fse decoding finished\n")
            }
          }
        }
      }
    }

    is (STATE_BUILD_HUF_DIC) {
      switch (build_dic_state) {
      is (BUILD_HUF_DIC_IDLE) {
        when (!build_dic_done) {
          val max_bits = 63.U - PriorityEncoder(Reverse(weight_sum)) + 1.U
          val left_over = (1.U << max_bits(3, 0)) - weight_sum
          val last_weight = 63.U - PriorityEncoder(Reverse(left_over)) + 1.U

          huf_max_bits := max_bits
          bits(huf_num_symbols) := max_bits + 1.U - last_weight
          weight_table(huf_num_symbols) := last_weight
          build_dic_state := BUILD_HUF_DIC_GET_PER_SYMBOL_NUMBITS

          CompressAccelLogger.logInfo("HeaderExpander start building huf dict\n")
          CompressAccelLogger.logInfo("HeaderExpander max_bits: %d\n", max_bits)
          CompressAccelLogger.logInfo("HeaderExpander left_over: %d\n", left_over)
          CompressAccelLogger.logInfo("HeaderExpander last_weight: %d\n", last_weight)
        } .otherwise {
          state := STATE_SET_LITERAL_EXPANDER
          CompressAccelLogger.logInfo("HeaderExpander huffman dic table build finished!!\n")
        }
      }

      is (BUILD_HUF_DIC_GET_PER_SYMBOL_NUMBITS) {
        val nxt_bidx = bidx + 1.U
        val cur_weight = weight_table(bidx)
        val cur_numbits = Mux(cur_weight > 0.U, huf_max_bits + 1.U - cur_weight, 0.U)

        bidx := nxt_bidx
        bits(bidx) := cur_numbits

        when (bidx === huf_num_symbols - 1.U) {
          build_dic_state := BUILD_HUF_DIC_INIT_RANK_COUNT
          bidx := 0.U
        }

        CompressAccelLogger.logInfo("HeaderExpander numbit_table[%d]: %d\n", 
          bidx, cur_numbits)
      }

      is (BUILD_HUF_DIC_INIT_RANK_COUNT) {
        val cur_bits = bits(bidx)
        val cur_rank_count = rank_count(cur_bits)
        rank_count(cur_bits) := cur_rank_count + 1.U
        bidx := bidx + 1.U

        when (bidx === huf_num_symbols) {
          huf_table_size := (1.U << huf_max_bits)
          bidx := huf_max_bits
          build_dic_state := BUILD_HUF_DIC_INIT_RANK_IDX
        }
      }

      is (BUILD_HUF_DIC_INIT_RANK_IDX) {
        val nxt_rank_idx = rank_idx(bidx) + rank_count(bidx) * (1.U << (huf_max_bits - bidx(3, 0)))
        rank_idx(bidx - 1.U) := nxt_rank_idx
        tidx := 0.U
        build_dic_state := BUILD_HUF_DIC_INIT_NUMBIT_TABLE
      }

      is (BUILD_HUF_DIC_INIT_NUMBIT_TABLE) {
        when (rank_idx(bidx) =/= rank_idx(bidx-1.U)) {
          numbit_table(tidx + rank_idx(bidx)) := bidx
          tidx := tidx + 1.U
        }

        when ((tidx === rank_idx(bidx - 1.U) - rank_idx(bidx) - 1.U) || 
              (rank_idx(bidx - 1.U) === rank_idx(bidx))) {
          bidx := bidx - 1.U

          when (bidx === 1.U) {
            bidx := 0.U
            tidx := 0.U
            build_dic_state := BUILD_HUF_DIC_GET_CODE_AND_LEN
          } .otherwise {
            build_dic_state := BUILD_HUF_DIC_INIT_RANK_IDX
          }
        }
      }

      is (BUILD_HUF_DIC_GET_CODE_AND_LEN) {
        val cur_bits = bits(bidx)
        when (cur_bits === 0.U) {
          bidx := bidx + 1.U

          when (bidx === huf_num_symbols) {
            build_dic_state := BUILD_HUF_DIC_IDLE
            build_dic_done := true.B
          }
        } .otherwise {
          cur_code := rank_idx(cur_bits)
          cur_len := 1.U << (huf_max_bits - cur_bits)

          tidx := 0.U
          build_dic_state := BUILD_HUF_DIC_INIT_SYMBOL_TABLE

          //bits_bidx_reg := bits(bidx)
          cur_rank_idx_reg := rank_idx(bits(bidx))
        }
      }

      is (BUILD_HUF_DIC_INIT_SYMBOL_TABLE) {
        tidx := tidx + 1.U
        symbol_table(cur_code + tidx) := bidx

        when (tidx === cur_len - 1.U) {
          //rank_idx(bits_bidx_reg) := cur_rank_idx_reg + cur_len
          rank_idx(bits(bidx)) := cur_rank_idx_reg + cur_len
          bidx := bidx + 1.U

          when (bidx === huf_num_symbols) {
            build_dic_state := BUILD_HUF_DIC_IDLE
            build_dic_done := true.B
          } .otherwise {
            build_dic_state := BUILD_HUF_DIC_GET_CODE_AND_LEN
          }
        }
      }
      }
    }

    is (STATE_SET_LITERAL_EXPANDER) {
      literal_router_sel := LITERAL_ROUTER_SEL_LITERAL_EXPANDER

      val huf_header_len_w = Mux(block_type === 2.U, 1.U + huf_header_size, 0.U)
      val huf_header_end_addr_w = src_lit_start_addr + lit_header_size + huf_header_len_w
      val stream_size = comp_size - huf_header_len_w
      val stream_offset_w = Mux(decomp_streams === 0.U, 0.U,
                                Mux(decomp_streams === 1.U, comp4_size(0),
                                  Mux(decomp_streams === 2.U, comp4_size(0) + comp4_size(1), 
                                    comp4_size(0) + comp4_size(1) + comp4_size(2))))

      val all_stream_sent = (single_stream && decomp_streams === 1.U) || 
                            (!single_stream && decomp_streams === 4.U)

      val lit_expander_fire = DecoupledHelper(literal_src_info_q.enq.ready,
                                              literal_cmd_q.enq.ready,
                                              !all_stream_sent)

      literal_src_info_q.enq.valid := lit_expander_fire.fire(literal_src_info_q.enq.ready)
      literal_cmd_q.enq.valid := lit_expander_fire.fire(literal_cmd_q.enq.ready)

      when (lit_expander_fire.fire) {
        literal_cmd_q.enq.bits.block_type := block_type
        literal_cmd_q.enq.bits.max_bits := huf_max_bits
        literal_cmd_q.enq.bits.decomp_size := decomp_size
        literal_cmd_q.enq.bits.single_stream := single_stream

        when (single_stream) {
          literal_src_info_q.enq.bits.ip := huf_header_end_addr_w
          literal_src_info_q.enq.bits.isize := stream_size
          literal_cmd_q.enq.bits.comp_size := stream_size
        } .otherwise {
          literal_src_info_q.enq.bits.ip := huf_header_end_addr_w + 6.U + stream_offset_w
          literal_src_info_q.enq.bits.isize := comp4_size(decomp_streams)
          literal_cmd_q.enq.bits.comp_size := comp4_size(decomp_streams)
        }

        decomp_streams := decomp_streams + 1.U
        CompressAccelLogger.logInfo("HeaderExpander STATE_SET_LITERAL_EXPANDER\n")
        CompressAccelLogger.logInfo("HeaderExpander processing stream: %d\n", decomp_streams)
        CompressAccelLogger.logInfo("HeaderExpander addr: 0x%x size: %d\n", literal_src_info_q.enq.bits.ip, literal_src_info_q.enq.bits.isize)
      }

      when (all_stream_sent) {
        state := STATE_LOOKUPS
      }
    }

    is (STATE_LOOKUPS) {
      literal_router_sel := LITERAL_ROUTER_SEL_LITERAL_EXPANDER

      literal_expander_done_q.deq.ready := !lookup_idx_q.map(_.deq.valid).reduce(_ || _)

      when (literal_expander_done_q.deq.fire) {
        state := STATE_DONE
        CompressAccelLogger.logInfo("HeaderExpander Execution Finished\n")
      }

      // FIXME : Too much resource utilization here!!!!!
      for (i <- 0 until decomp_at_once-3) {
        val lookup_idx = lookup_idx_q(i).deq.bits.idx
        val lookup_fire = DecoupledHelper(lookup_idx_q(i).deq.valid, dic_entry_q(i).enq.ready)

        lookup_idx_q(i).deq.ready := lookup_fire.fire(lookup_idx_q(i).deq.valid)
        dic_entry_q(i).enq.valid := lookup_fire.fire(dic_entry_q(i).enq.ready)
        when (lookup_fire.fire) {
          dic_entry_q(i).enq.bits.symbol := symbol_table(lookup_idx)
          dic_entry_q(i).enq.bits.numbit := numbit_table(lookup_idx)
        }
        // TODO: AREA FIX
        // In LiteralExpander, if i is in {1,2,3} and lookup_idx_q(i) is valid,
        // invalidate everything from i (only 0 is valid) and adjust the bits_consumed to i.
        // Remember that to actually reduce the circuit, i should be until (decomp_at_once-3).
        // In other words: i is actually i+3 after i==0.
      }
    }

    is (STATE_RLE_RAW_EXPAND) {
      header_router_sel := HEADER_ROUTER_SEL_LITERAL_EXPANDER

      val all_stream_sent = (decomp_streams === 1.U)
      val rle_raw_expander_fire = DecoupledHelper(literal_src_info_q.enq.ready,
                                              literal_cmd_q.enq.ready,
                                              !all_stream_sent)

      literal_src_info_q.enq.valid := rle_raw_expander_fire.fire(literal_src_info_q.enq.ready)
      literal_cmd_q.enq.valid := rle_raw_expander_fire.fire(literal_cmd_q.enq.ready)

      when (rle_raw_expander_fire.fire) {
        literal_src_info_q.enq.bits.ip := src_lit_start_addr + lit_header_size
        literal_src_info_q.enq.bits.isize := Mux(block_type === 0.U, decomp_size, 1.U)

        literal_cmd_q.enq.bits.block_type := block_type
        literal_cmd_q.enq.bits.decomp_size := decomp_size
        literal_cmd_q.enq.bits.max_bits := 0.U

        decomp_streams := decomp_streams + 1.U
      }

      literal_expander_done_q.deq.ready := true.B
      when (literal_expander_done_q.deq.fire) {
        state := STATE_DONE
        CompressAccelLogger.logInfo("HeaderExpander RLE/RAW Execution Finished\n")
      }
    }

    is (STATE_DONE) {
      build_dic_state := BUILD_HUF_DIC_IDLE
      build_dic_done := false.B
      bidx := 0.U
      for (i <- 0 until 257) {
        bits(i) := 0.U
      }
      weight_table_idx := 0.U
      weight_sum := 0.U
      for (i <- 0 until 18) {
        rank_count(i) := 0.U
        rank_idx(i) := 0.U
      }
      cur_code := 0.U
      cur_len := 0.U
      huf_decode_header_state := 0.U

      src_lit_start_addr := 0.U
      literal_router_sel := LITERAL_ROUTER_SEL_FSE_DECODER
      src_lit_valid := 0.U
      src_lit_valid_set := 0.U
      huf_header_size := 0.U
      src_header_valid_set := false.B
      header_router_sel := HEADER_ROUTER_SEL_HEADER_EXPANDER
      lit_header_first_byte := 0.U
      block_type := 0.U
      size_format := 0.U
      single_stream := false.B
      treeless_decoding := false.B
      comp_size := 0.U
      comp4_size(0) := 0.U
      comp4_size(1) := 0.U
      comp4_size(2) := 0.U
      comp4_size(3) := 0.U
      decomp_size := 0.U
      lit_header_size := 0.U
      decomp_streams := 0.U

      // Flush out all the header_stream.
      // This is due to the FSE decoder no knowing the amount of bytes that it
      // will consume, hence loading the maximum possible amount that it may
      // use.
      io.header_stream.output_ready := true.B
      when (io.header_stream.output_valid) {
        io.header_stream.user_consumed_bytes := io.header_stream.available_output_bytes
        when (io.header_stream.output_last_chunk) {
          state := STATE_IDLE
        }
      }.otherwise{
        state := STATE_IDLE
      }
    }
  }
}
