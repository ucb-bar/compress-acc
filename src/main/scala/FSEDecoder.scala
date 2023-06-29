package compressacc

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheArbiter}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._


class FSEHeaderDecoderIO()(implicit p: Parameters) extends Bundle {
  val header_info = Flipped(Decoupled(new StreamInfo))

  val header_stream_info = Decoupled(new StreamInfo)
  val header_stream = Flipped(new MemLoaderConsumerBundle) // header memloader consumer bundle
 
  val literal_stream_info = Decoupled(new StreamInfo)
  val literal_stream = Flipped(new MemLoaderConsumerBundle) // literal memloader consumer bundle

  val weight = Decoupled(UInt(8.W))
  val num_symbols = Decoupled(UInt(64.W))
}

class FSEHeaderDecoder(val cmd_que_depth: Int)(implicit p: Parameters) extends Module {
  val io = IO(new FSEHeaderDecoderIO)

  // Overall state
  val STATE_IDLE = 0.U
  val STATE_FSE_DECODE_HEADER = 1.U
  val STATE_FSE_INIT_DTABLE = 2.U
  val STATE_FSE_DECOMPRESS_INTERLEAVE = 3.U
  val state = RegInit(0.U(3.W))

  // States for the header decoding
  val FSE_HEADER_IDLE = 0.U
  val FSE_HEADER_GET_ACCURACY_LOG = 1.U
  val FSE_HEADER_GET_INIT_VAL = 2.U
  val FSE_HEADER_READ_SRC = 3.U
  val FSE_HEADER_CHECK_SMALL_VAL = 4.U
  val FSE_HEADER_GET_PROB = 5.U
  val FSE_HEADER_GET_ZEROS = 6.U
  val FSE_HEADER_READ_SRC_ZEROS = 7.U
  val decode_header_state = RegInit(0.U(3.W))

  val decode_header_done = RegInit(false.B)
  val accuracy_log = RegInit(0.U(4.W)) // max accuracy log is 7 (?), readme & code different...
  val remaining = RegInit(0.S(16.W))

  val FSE_MAX_SYMBS = 256
  val FSE_MAX_ACCURACY = (1 << 6)

  val cur_val = RegInit(0.U(16.W))
  val cur_bits = RegInit(0.U(16.W))
  val cur_lower_mask = RegInit(0.U(16.W))
  val cur_threshold = RegInit(0.U(16.W))
  val cur_symb_cnt = RegInit(0.U(log2Ceil(FSE_MAX_SYMBS + 1).W))
  val frequencies = RegInit(VecInit(Seq.fill(FSE_MAX_SYMBS)(0.S(16.W))))

  val cur_header_byte = RegInit(0.U(8.W))
  val cur_header_used_bits = RegInit(0.U(4.W))

  // States for fse dtable init
  val FSE_INIT_DTABLE_IDLE = 0.U
  val FSE_INIT_DTABLE_SINGLE_CELL_SYMBOLS = 1.U
  val FSE_INIT_DTABLE_REMAINING_CELL_SYMBOLS = 2.U
  val FSE_INIT_DTABLE_SET_SYMBOL = 3.U
  val FSE_INIT_DTABLE_GET_NEXT_POS = 4.U
  val FSE_INIT_DTABLE_FILL_BASELINE_AND_NUMBITS = 5.U

  val init_dtable_state = RegInit(0.U(3.W))

  val init_dtable_done = RegInit(false.B)

  val cur_dtable_size = RegInit(0.U(16.W))
  val cur_high_threshold = RegInit(0.U(16.W))
  val cur_step = RegInit(0.U(16.W))
  val cur_size_mask = RegInit(0.U(16.W))
  val cur_pos = RegInit(0.U(16.W))

  val cur_symbol_idx = RegInit(0.U(16.W)) // s
  val cur_freq_idx = RegInit(0.U(16.W))   // i
  val cur_size_idx = RegInit(0.U(16.W))

  // FIXME : Is it okay to use this much on-chip memory?
  // 512B + 128B + 128B + 256B = 1kB
  val fse_state_desc = RegInit(VecInit(Seq.fill(FSE_MAX_SYMBS)(0.U(16.W))))
  val fse_dtable_symbols = RegInit(VecInit(Seq.fill(FSE_MAX_ACCURACY)(0.U(8.W))))
  val fse_dtable_numbits = RegInit(VecInit(Seq.fill(FSE_MAX_ACCURACY)(0.U(8.W))))
  val fse_dtable_new_state_base = RegInit(VecInit(Seq.fill(FSE_MAX_ACCURACY)(0.U(16.W))))

  // States for fse decode interleave 2
  val FSE_DECOMP_INTERLEAVE_IDLE = 0.U
  val FSE_DECOMP_INTERLEAVE_READ_SRC = 1.U
  val FSE_DECOMP_INTERLEAVE_INIT_STATE = 2.U
  val FSE_DECOMP_INTERLEAVE_PEEK_SYMBOL = 3.U
  val FSE_DECOMP_INTERLEAVE_UPDATE_STATE = 4.U

  val decomp_interleave_state = RegInit(0.U(3.W))
  val decomp_interleave_prev_state = RegInit(0.U(3.W))
  val decomp_interleave_done = RegInit(false.B)

  val cur_symbol_state = RegInit(VecInit(Seq.fill(2)(0.U(16.W))))
  val interleave_idx = RegInit(0.U(1.W))
  val fse_literal_first_byte = RegInit(true.B)
  val fse_literal = RegInit(0.U(16.W))
  val fse_literal_valid_bits = RegInit(0.U(5.W))
  val fse_literal_used_bits = RegInit(0.U(5.W)) // used bits for current fse_literal
  val fse_literal_offset = RegInit(0.U(64.W))
  val fse_literal_tot_used_bits = RegInit(0.U(64.W)) // total used bits so far
  val fse_final_peek = RegInit(false.B)

  val huf_symbol_cnt = RegInit(0.U(64.W))

  val fse_literal_size = RegInit(0.U(64.W))
  val fse_header_consumed_bytes = RegInit(0.U(64.W))

  io.literal_stream.output_ready := false.B
  io.literal_stream.user_consumed_bytes := false.B

  io.header_stream.output_ready := false.B
  io.header_stream.user_consumed_bytes := 0.U

  val header_info_q = Module(new Queue(new StreamInfo, cmd_que_depth)).io
  header_info_q.enq <> io.header_info
  header_info_q.deq.ready := false.B

  val weight_q = Module(new Queue(UInt(8.W), cmd_que_depth)).io
  io.weight <> weight_q.deq
  weight_q.enq.valid := false.B
  weight_q.enq.bits := 0.U

  val num_symbols_q = Module(new Queue(UInt(64.W), cmd_que_depth)).io
  io.num_symbols <> num_symbols_q.deq
  num_symbols_q.enq.valid := false.B
  num_symbols_q.enq.bits := 0.U

  // For header stream info
  val header_stream_info_q = Module(new Queue(new StreamInfo, cmd_que_depth)).io
  io.header_stream_info <> header_stream_info_q.deq
  header_stream_info_q.enq.valid := false.B
  header_stream_info_q.enq.bits.ip := header_info_q.deq.bits.ip
  header_stream_info_q.enq.bits.isize := header_info_q.deq.bits.isize

  // For literal stream info
  val literal_stream_info_q = Module(new Queue(new StreamInfo, cmd_que_depth)).io
  io.literal_stream_info <> literal_stream_info_q.deq
  literal_stream_info_q.enq.valid := false.B
  literal_stream_info_q.enq.bits.ip := header_info_q.deq.bits.ip + fse_header_consumed_bytes
  literal_stream_info_q.enq.bits.isize := header_info_q.deq.bits.isize - fse_header_consumed_bytes

  val byte_mask = (1.U << 8.U) - 1.U
  val word_mask = (1.U << 16.U) - 1.U

  switch (state) {
  is (STATE_IDLE) {
    when (header_info_q.deq.valid) {
      header_stream_info_q.enq.valid := true.B

      when (!decode_header_done && header_stream_info_q.enq.fire) {
        state := STATE_FSE_DECODE_HEADER
        CompressAccelLogger.logInfo("FSEHeaderDecoder start obtaining freq values\n")
      }
    } .otherwise {
      decode_header_state := FSE_HEADER_IDLE
      decode_header_done := false.B
      accuracy_log := 0.U
      remaining := 0.S
      cur_val := 0.U
      cur_bits := 0.U
      cur_lower_mask := 0.U
      cur_threshold := 0.U
      cur_symb_cnt := 0.U
      cur_header_byte := 0.U
      cur_header_used_bits := 0.U
      init_dtable_state := FSE_INIT_DTABLE_IDLE
      init_dtable_done := false.B
      cur_dtable_size := 0.U
      cur_high_threshold := 0.U
      cur_step := 0.U
      cur_size_mask := 0.U
      cur_pos := 0.U
      cur_symbol_idx := 0.U
      cur_freq_idx := 0.U
      cur_size_idx := 0.U

      for (i <- 0 until FSE_MAX_SYMBS) {
        frequencies(i) := 0.S
        fse_state_desc(i) := 0.U
      }

      for (i <- 0 until FSE_MAX_ACCURACY) {
        fse_dtable_symbols(i) := 0.U
        fse_dtable_numbits(i) := 0.U
        fse_dtable_new_state_base(i) := 0.U
      }

      decomp_interleave_state := FSE_DECOMP_INTERLEAVE_IDLE
      decomp_interleave_prev_state := FSE_DECOMP_INTERLEAVE_IDLE
      decomp_interleave_done := false.B
      cur_symbol_state(0) := 0.U
      cur_symbol_state(1) := 0.U
      interleave_idx := 0.U
      fse_literal_first_byte := true.B
      fse_literal := 0.U
      fse_literal_valid_bits := 0.U
      fse_literal_used_bits := 0.U
      fse_literal_offset := 0.U
      fse_literal_tot_used_bits := 0.U
      fse_final_peek := false.B
      huf_symbol_cnt := 0.U
      fse_literal_size := 0.U
      fse_header_consumed_bytes := 0.U
    }
  }

  is (STATE_FSE_DECODE_HEADER) {
    switch (decode_header_state) {
    is (FSE_HEADER_IDLE) {
      when (!decode_header_done) {
        decode_header_state := FSE_HEADER_GET_ACCURACY_LOG
      } .otherwise {
        fse_literal_size := header_info_q.deq.bits.isize - fse_header_consumed_bytes

        literal_stream_info_q.enq.valid := true.B
        when (literal_stream_info_q.enq.fire) {
          state := STATE_FSE_INIT_DTABLE
          CompressAccelLogger.logInfo("FSEHeaderDecoder start init dtable\n")
        }
      }
    }

    is (FSE_HEADER_GET_ACCURACY_LOG) {
      io.header_stream.output_ready := true.B
      when (io.header_stream.output_valid) {
        io.header_stream.user_consumed_bytes := 1.U

        val cur_byte = io.header_stream.output_data & byte_mask
        val cur_accuracy_log = cur_byte(3, 0) + 5.U
        cur_header_byte := cur_byte
        cur_header_used_bits := 4.U
        accuracy_log := cur_accuracy_log
        remaining := (1.S << cur_accuracy_log)

        fse_header_consumed_bytes := fse_header_consumed_bytes + 1.U

        decode_header_state := FSE_HEADER_GET_INIT_VAL

        CompressAccelLogger.logInfo("FSEHeaderDecoder, GET_ACCURACY_LOG\n")
        CompressAccelLogger.logInfo("FSEHeaderDecoder, remaining: %d\n", (1.S << cur_accuracy_log))
        CompressAccelLogger.logInfo("FSEHeaderDecoder, cur_accuracy_log: %d\n", cur_accuracy_log)
        CompressAccelLogger.logInfo("FSEHeaderDecoder, cur_header_byte: 0x%x\n", cur_byte)
      }
    }

    is (FSE_HEADER_GET_INIT_VAL) {
      // maximum value of bits is 8, so it is okay to read the stream byte-by-byte
      val bits = 16.U - PriorityEncoder(Reverse(remaining.asUInt + 1.U))
      val bits_mask = (1.U << bits) - 1.U
      val lower_mask = (1.U << (bits - 1.U)) - 1.U
      val threshold = (1.U << bits) - 1.U - (remaining.asUInt + 1.U)

      cur_lower_mask := lower_mask
      cur_threshold := threshold
      cur_bits := bits

      when ((cur_symb_cnt === FSE_MAX_SYMBS.U) || remaining <= 0.S) {
        decode_header_state := FSE_HEADER_IDLE
        decode_header_done := true.B
        CompressAccelLogger.logInfo("FSEHeaderDecoder, Obtained all the frequency values\n")
        CompressAccelLogger.logInfo("FSEHeaderDecoder, cur_symb_cnt: %d\n", cur_symb_cnt)
      } .elsewhen (cur_header_used_bits + bits > 8.U) {
        decode_header_state := FSE_HEADER_READ_SRC
      } .otherwise {
        cur_val := (cur_header_byte >> cur_header_used_bits) & bits_mask
        cur_header_used_bits := cur_header_used_bits + bits
        decode_header_state := FSE_HEADER_CHECK_SMALL_VAL
      }

      CompressAccelLogger.logInfo("FSEHeaderDecoder, GET_INIT_VAL\n")
      CompressAccelLogger.logInfo("FSEHeaderDecoder, bits: %d\n", bits)
      CompressAccelLogger.logInfo("FSEHeaderDecoder, lower_mask: 0x%x\n", lower_mask)
      CompressAccelLogger.logInfo("FSEHeaderDecoder, threshold: %d\n", threshold)
      CompressAccelLogger.logInfo("FSEHeaderDecoder, cur_header_used_bits: %d\n", cur_header_used_bits)
      CompressAccelLogger.logInfo("FSEHeaderDecoder, cur_val: %d\n", 
        (cur_header_byte >> cur_header_used_bits) & bits_mask)
    }

    is (FSE_HEADER_READ_SRC) {
      io.header_stream.output_ready := true.B

      // bits - (8 - cur_used_bytes)
      when (io.header_stream.output_valid) {
        io.header_stream.user_consumed_bytes := 1.U

        val cur_byte = io.header_stream.output_data & byte_mask
        val top_bits = cur_bits - (8.U - cur_header_used_bits)
        val top_mask = (1.U << top_bits(4, 0)) - 1.U
        cur_val := Cat(cur_byte & top_mask, cur_header_byte) >> cur_header_used_bits
        cur_header_used_bits := top_bits
        cur_header_byte := cur_byte

        fse_header_consumed_bytes := fse_header_consumed_bytes + 1.U

        decode_header_state := FSE_HEADER_CHECK_SMALL_VAL

        CompressAccelLogger.logInfo("FSEHeaderDecoder, READ_SRC\n")
        CompressAccelLogger.logInfo("FSEHeaderDecoder, cur_byte: 0x%x\n", cur_byte)
        CompressAccelLogger.logInfo("FSEHeaderDecoder, top_bits: %d\n", top_bits)
        CompressAccelLogger.logInfo("FSEHeaderDecoder, cur_header_byte: 0x%x\n", cur_header_byte)
        CompressAccelLogger.logInfo("FSEHeaderDecoder, cur_val: 0x%x\n", 
          (Cat(cur_byte & top_mask, cur_header_byte) >> cur_header_used_bits) & word_mask)
      }
    }

    is (FSE_HEADER_CHECK_SMALL_VAL) {
      when ((cur_val & cur_lower_mask) < cur_threshold) {
        cur_header_used_bits := cur_header_used_bits - 1.U
        cur_val := (cur_val & cur_lower_mask)
      } .elsewhen (cur_val > cur_lower_mask) {
        cur_val := cur_val - cur_threshold
      }

      decode_header_state := FSE_HEADER_GET_PROB
    }

    is (FSE_HEADER_GET_PROB) {
      val prob = cur_val.asSInt - 1.S
      when (cur_val === 0.U) {
        remaining := remaining - 1.S
      } .otherwise {
        remaining := remaining - prob
      }

      val nxt_symb_cnt = cur_symb_cnt + 1.U
      frequencies(cur_symb_cnt) := prob
      cur_symb_cnt := nxt_symb_cnt

      when (prob === 0.S) {
        decode_header_state := FSE_HEADER_GET_ZEROS
      } .otherwise {
        decode_header_state := FSE_HEADER_GET_INIT_VAL
      }

      CompressAccelLogger.logInfo("FSEHeaderDecoder, GET_PROB\n")
      CompressAccelLogger.logInfo("FSEHeaderDecoder, cur_val: 0x%x\n", cur_val)
      CompressAccelLogger.logInfo("FSEHeaderDecoder, cur_header_byte: 0x%x\n", cur_header_byte)
      CompressAccelLogger.logInfo("FSEHeaderDecoder, frequencies[%d] : %d\n", cur_symb_cnt, prob)
    }

    is (FSE_HEADER_GET_ZEROS) {
      /* 
      * "When a symbol has a probability of zero, it is followed by a 2-bits
      * repeat flag. This repeat flag tells how many probabilities of zeroes
      * follow the current one. It provides a number ranging from 0 to 3. If
      * it is a 3, another 2-bits repeat flag follows, and so on."
      */
     when (8.U - cur_header_used_bits >= 2.U) {
       val repeat = (cur_header_byte >> cur_header_used_bits) & (3.U)
       cur_symb_cnt := cur_symb_cnt + repeat
       cur_header_used_bits := cur_header_used_bits + 2.U

       when (repeat === 3.U) {
         decode_header_state := FSE_HEADER_GET_ZEROS
       } .otherwise {
         decode_header_state := FSE_HEADER_GET_INIT_VAL
       }

       CompressAccelLogger.logInfo("FSEHeaderDecoder GET_ZEROS\n")
       CompressAccelLogger.logInfo("FSEHeaderDecoder repeat: %d\n", repeat)
     } .otherwise {
       decode_header_state := FSE_HEADER_READ_SRC_ZEROS
     }
    }

    is (FSE_HEADER_READ_SRC_ZEROS) {
      io.header_stream.output_ready := true.B

      when (io.header_stream.output_valid) {
        io.header_stream.user_consumed_bytes := 1.U

        val cur_byte = io.header_stream.output_data(7, 0)
        val top_bits = 2.U - (8.U - cur_header_used_bits)
        val top_mask = (1.U << top_bits(3, 0)) - 1.U
        val repeat = (Cat(cur_byte & top_mask, cur_header_byte) >> cur_header_used_bits) & 3.U

        cur_header_byte := cur_byte
        cur_header_used_bits := top_bits
        fse_header_consumed_bytes := fse_header_consumed_bytes + 1.U
        cur_symb_cnt := cur_symb_cnt + repeat

        when (repeat === 3.U) {
          decode_header_state := FSE_HEADER_GET_ZEROS
        } .otherwise {
          decode_header_state := FSE_HEADER_GET_INIT_VAL
        }
      }
    }
    }
  }

  is (STATE_FSE_INIT_DTABLE) {
    switch (init_dtable_state) {

    is (FSE_INIT_DTABLE_IDLE) {
      when (!init_dtable_done) {
        init_dtable_state := FSE_INIT_DTABLE_SINGLE_CELL_SYMBOLS

        val size = (1.U << accuracy_log)
        cur_dtable_size := size
        cur_high_threshold := size
        cur_step := (size >> 1.U) + (size >> 3.U) + 3.U
        cur_size_mask := size - 1.U

        CompressAccelLogger.logInfo("FSEHeaderDecoder, INIT_DTABLE_STARTED!!!\n")
        CompressAccelLogger.logInfo("size: %d\n", size)
      } .otherwise {
        state := STATE_FSE_DECOMPRESS_INTERLEAVE
        CompressAccelLogger.logInfo("FSEHeaderDecoder, INIT_DTABLE_FINISHED!!!\n")
      }
    }

    is (FSE_INIT_DTABLE_SINGLE_CELL_SYMBOLS) {
      CompressAccelLogger.logInfo("FSEHeaderDecoder, INIT_DTABLE_SINGLE_CELL_SYMBOLS\n")
      CompressAccelLogger.logInfo("FSEHeaderDecoder, cur_symbol_idx: %d\n", cur_symbol_idx)

      cur_symbol_idx := cur_symbol_idx + 1.U

      when (cur_symbol_idx === cur_symb_cnt - 1.U) {
        init_dtable_state := FSE_INIT_DTABLE_REMAINING_CELL_SYMBOLS
        cur_symbol_idx := 0.U
        cur_pos := 0.U
      }

      // TODO : This block of code is not tested yet, find a test case & test it
      when (frequencies(cur_symbol_idx) < 0.S) {
        val nxt_high_threshold = cur_high_threshold - 1.U
        cur_high_threshold := nxt_high_threshold
        fse_dtable_symbols(nxt_high_threshold) := cur_symbol_idx
        fse_state_desc(cur_symbol_idx) := 1.U

        CompressAccelLogger.logInfo("FSEHeaderDecoder, Found negative frequency\n")
      }
    }

    is (FSE_INIT_DTABLE_REMAINING_CELL_SYMBOLS) {
      when (frequencies(cur_symbol_idx) > 0.S) {
        fse_state_desc(cur_symbol_idx) := frequencies(cur_symbol_idx).asUInt

        init_dtable_state := FSE_INIT_DTABLE_SET_SYMBOL
        cur_freq_idx := 0.U

        CompressAccelLogger.logInfo("FSEHeaderDecoder, INIT_DTABLE_REMAINING_CELL_SYMBOLS\n")
        CompressAccelLogger.logInfo("FSEHeaderDecoder, state_desc[%d]: %d\n", 
          cur_symbol_idx, frequencies(cur_symbol_idx).asUInt)
      } .otherwise {
        cur_symbol_idx := cur_symbol_idx + 1.U

        when (cur_symbol_idx === cur_symb_cnt - 1.U) {
          init_dtable_state := FSE_INIT_DTABLE_FILL_BASELINE_AND_NUMBITS
        }
      }
    }

    is (FSE_INIT_DTABLE_SET_SYMBOL) {
      cur_freq_idx := cur_freq_idx + 1.U

      when (cur_freq_idx === frequencies(cur_symbol_idx).asUInt) {
        cur_symbol_idx := cur_symbol_idx + 1.U

        when (cur_symbol_idx === cur_symb_cnt - 1.U) {
          init_dtable_state := FSE_INIT_DTABLE_FILL_BASELINE_AND_NUMBITS
        } .otherwise {
          init_dtable_state := FSE_INIT_DTABLE_REMAINING_CELL_SYMBOLS
        }
      } .otherwise {
        fse_dtable_symbols(cur_pos) := cur_symbol_idx
        init_dtable_state := FSE_INIT_DTABLE_GET_NEXT_POS

        CompressAccelLogger.logInfo("FSEHeaderDecoder, INIT_DTABLE_SET_SYMBOL\n")
        CompressAccelLogger.logInfo("FSEHeaderDecoder, dtable_symbols[%d]: %d\n",
          cur_pos, cur_symbol_idx)
      }
    }

    is (FSE_INIT_DTABLE_GET_NEXT_POS) {
      val next_pos = (cur_pos + cur_step) & cur_size_mask
      cur_pos := next_pos

      when (next_pos < cur_high_threshold) {
        init_dtable_state := FSE_INIT_DTABLE_SET_SYMBOL
        CompressAccelLogger.logInfo("FSEHeaderDecoder, INIT_DTABLE_GET_NEXT_POS\n")
        CompressAccelLogger.logInfo("FSEHeaderDecoder, next_pos: %d\n", next_pos)
      }
    }

    is (FSE_INIT_DTABLE_FILL_BASELINE_AND_NUMBITS) {
      cur_size_idx := cur_size_idx + 1.U

      val cur_symbol = fse_dtable_symbols(cur_size_idx)
      val cur_state_desc = fse_state_desc(cur_symbol)
      val next_state_desc = cur_state_desc + 1.U
      fse_state_desc(cur_symbol) := next_state_desc

      val highest_set_bit = 15.U - PriorityEncoder(Reverse(cur_state_desc))
      val dtable_numbits = (accuracy_log - highest_set_bit) & byte_mask
      fse_dtable_numbits(cur_size_idx) := dtable_numbits
      fse_dtable_new_state_base(cur_size_idx) := (cur_state_desc << dtable_numbits(4, 0)) - cur_dtable_size

      CompressAccelLogger.logInfo("FSEHeaderDecoder FSE_INIT_DTABLE_FILL_BASELINE_AND_NUMBITS\n")
      CompressAccelLogger.logInfo("FSEHeaderDecoder nxt_state_desc: %d\n", cur_state_desc)
      CompressAccelLogger.logInfo("FSEHeaderDecoder cur_size_idx: %d\n", cur_size_idx)
      CompressAccelLogger.logInfo("FSEHeaderDecoder symbols: %d, numbits: %d, new_state_base: %d\n", 
        fse_dtable_symbols(cur_size_idx), dtable_numbits, (cur_state_desc << dtable_numbits(4, 0)) - cur_dtable_size)

      when (cur_size_idx === cur_dtable_size - 1.U) {
        init_dtable_done := true.B
        init_dtable_state := FSE_INIT_DTABLE_IDLE
      }
    }
    }
  }

  is (STATE_FSE_DECOMPRESS_INTERLEAVE) {
    switch (decomp_interleave_state) {
    is (FSE_DECOMP_INTERLEAVE_IDLE) {
      when (!decomp_interleave_done) {
        decomp_interleave_state := FSE_DECOMP_INTERLEAVE_READ_SRC
        decomp_interleave_prev_state := FSE_DECOMP_INTERLEAVE_IDLE
        CompressAccelLogger.logInfo("FSEHeaderDecoder decompress_interleave start\n")
      } .otherwise {
        state := STATE_IDLE
        num_symbols_q.enq.valid := true.B
        num_symbols_q.enq.bits := huf_symbol_cnt

        when (num_symbols_q.enq.fire) {
          header_info_q.deq.ready := true.B
          CompressAccelLogger.logInfo("FSEHeaderDecoder decompress_interleave finished\n")
        }
      }
    }

    is (FSE_DECOMP_INTERLEAVE_READ_SRC) {
      io.literal_stream.output_ready := true.B
      when (io.literal_stream.output_valid) {
        io.literal_stream.user_consumed_bytes := 1.U

        val cur_byte = io.literal_stream.output_data(255, 248)
        val remain_bits = fse_literal_valid_bits - fse_literal_used_bits
        val remain_mask = (1.U << remain_bits) - 1.U
        val top_bits = (fse_literal >> (16.U - fse_literal_valid_bits)) & remain_mask

        when (fse_literal_first_byte) {
          val highest_set_bit = 7.U - PriorityEncoder(Reverse(cur_byte))
          val padding = 8.U - highest_set_bit

          fse_literal := cur_byte << (padding + 8.U)
          fse_literal_used_bits := 0.U
          fse_literal_valid_bits := 8.U - padding
          fse_literal_offset  := (fse_literal_size << 3.U) - padding
          fse_literal_first_byte := false.B
        } .otherwise {
          fse_literal := Cat(top_bits, cur_byte) << (16.U - 8.U - remain_bits)
          fse_literal_used_bits := 0.U
          fse_literal_valid_bits := remain_bits + 8.U
        }

        when (decomp_interleave_prev_state === FSE_DECOMP_INTERLEAVE_IDLE) {
          decomp_interleave_state := FSE_DECOMP_INTERLEAVE_INIT_STATE
          decomp_interleave_prev_state := FSE_DECOMP_INTERLEAVE_READ_SRC
        } .otherwise {
          decomp_interleave_state := decomp_interleave_prev_state
          decomp_interleave_prev_state := decomp_interleave_state
        }
        CompressAccelLogger.logInfo("FSEHeaderDecoder FSE_DECOMP_INTERLEAVE_READ_SRC\n")
        CompressAccelLogger.logInfo("FSEHeaderDecoder fse_literal: 0x%x, used_bits: %d, valid_bits: %d\n", 
          fse_literal, fse_literal_used_bits, remain_bits + 8.U)
      }
    }

    is (FSE_DECOMP_INTERLEAVE_INIT_STATE) {
      when (fse_literal_valid_bits - fse_literal_used_bits < accuracy_log) {
        decomp_interleave_state := FSE_DECOMP_INTERLEAVE_READ_SRC
        decomp_interleave_prev_state := decomp_interleave_state
      } .otherwise {
        val accuracy_mask = (1.U << accuracy_log) - 1.U
        val nxt_fse_literal_used_bits = fse_literal_used_bits + accuracy_log
        val nxt_cur_symbol_state = (fse_literal >> (16.U - nxt_fse_literal_used_bits)) & accuracy_mask
        cur_symbol_state(interleave_idx) := nxt_cur_symbol_state
        interleave_idx := interleave_idx + 1.U

        fse_literal_used_bits := nxt_fse_literal_used_bits
        fse_literal_tot_used_bits := fse_literal_tot_used_bits + accuracy_log

        when (interleave_idx === 1.U) {
          decomp_interleave_state := FSE_DECOMP_INTERLEAVE_PEEK_SYMBOL
          decomp_interleave_prev_state := decomp_interleave_state
        }

        CompressAccelLogger.logInfo("FSEHeaderDecoder, DECOMP_INTERLEAVE_INIT_STATE\n")
        CompressAccelLogger.logInfo("FSEHeaderDecoder, state1: %d\n", cur_symbol_state(0.U))
        CompressAccelLogger.logInfo("FSEHeaderDecoder, state2: %d\n", cur_symbol_state(1.U))
        CompressAccelLogger.logInfo("FSEHeaderDecoder, nxt_state: %d\n", nxt_cur_symbol_state)
      }
    }

    is (FSE_DECOMP_INTERLEAVE_PEEK_SYMBOL) {
      val cur_huf_weight = fse_dtable_symbols(cur_symbol_state(interleave_idx)) // FIXME : Possible long wire

      weight_q.enq.valid := true.B
      weight_q.enq.bits := cur_huf_weight

      when (weight_q.enq.fire) {
        huf_symbol_cnt := huf_symbol_cnt + 1.U

        when (!fse_final_peek) {
          decomp_interleave_state := FSE_DECOMP_INTERLEAVE_UPDATE_STATE
          decomp_interleave_prev_state := decomp_interleave_state
        } .otherwise {
          decomp_interleave_done := true.B
          decomp_interleave_state := FSE_DECOMP_INTERLEAVE_IDLE
          decomp_interleave_prev_state := decomp_interleave_state
        }
        CompressAccelLogger.logInfo("FSEHeaderDecoder FSE_DECOMP_INTERLEAVE_PEEK_SYMBOL\n")
        CompressAccelLogger.logInfo("FSEHeaderDecoder byte[%d]: 0x%x\n", interleave_idx, cur_huf_weight)
      }
    }

    is (FSE_DECOMP_INTERLEAVE_UPDATE_STATE) {
      val bits = fse_dtable_numbits(cur_symbol_state(interleave_idx)) // FIXME : Possible long wire
      val nxt_fse_literal_tot_used_bits = fse_literal_tot_used_bits + bits
      val nxt_fse_literal_used_bits = fse_literal_used_bits + bits
      val cur_symbol_state_idx = cur_symbol_state(interleave_idx)
      val cur_new_state_base = fse_dtable_new_state_base(cur_symbol_state_idx)

      when (nxt_fse_literal_tot_used_bits > fse_literal_offset) {
        val missing_bits = nxt_fse_literal_tot_used_bits - fse_literal_offset
        val rest = (fse_literal >> (16.U - fse_literal_used_bits)) << missing_bits(3, 0)
        fse_final_peek := true.B

        fse_literal_used_bits := nxt_fse_literal_used_bits
        fse_literal_tot_used_bits := nxt_fse_literal_used_bits

        cur_symbol_state(interleave_idx) := cur_new_state_base + rest
        interleave_idx := interleave_idx + 1.U

        decomp_interleave_state := FSE_DECOMP_INTERLEAVE_PEEK_SYMBOL
        decomp_interleave_prev_state := decomp_interleave_state

      } .elsewhen (fse_literal_valid_bits - fse_literal_used_bits < bits) {
        decomp_interleave_state := FSE_DECOMP_INTERLEAVE_READ_SRC
        decomp_interleave_prev_state := decomp_interleave_state
      } .otherwise {
        val rest_mask = (1.U << bits(3, 0)) - 1.U
        val rest = (fse_literal >> (16.U - nxt_fse_literal_used_bits)) & rest_mask

        fse_literal_used_bits := nxt_fse_literal_used_bits
        fse_literal_tot_used_bits := nxt_fse_literal_tot_used_bits

        cur_symbol_state(interleave_idx) := cur_new_state_base + rest
        interleave_idx := interleave_idx + 1.U

        decomp_interleave_state := FSE_DECOMP_INTERLEAVE_PEEK_SYMBOL
        decomp_interleave_prev_state := decomp_interleave_state

        CompressAccelLogger.logInfo("FSEHeaderDecoder, DECOMP_INTERLEAVE_UPDATE_STATE\n")
        CompressAccelLogger.logInfo("FSEHeaderDecoder, state1: %d\n", cur_symbol_state(0.U))
        CompressAccelLogger.logInfo("FSEHeaderDecoder, state2: %d\n", cur_symbol_state(1.U))
        CompressAccelLogger.logInfo("FSEHeaderDecoder, cur_new_state_base: %d, rest: %d\n",
          cur_new_state_base, rest)
        CompressAccelLogger.logInfo("FSEHeaderDecoder, nxt_state: %d\n",
          fse_dtable_new_state_base(cur_symbol_state_idx) + rest)
      }
    }
    }
  }
  }
}
