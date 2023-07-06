package compressacc

import Chisel._
import chisel3.{Printable, SyncReadMem}
import chisel3.util.{PriorityEncoder}
import freechips.rocketchip.tile._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, TLBPTWIO, TLB, MStatus, PRV}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.rocket.{RAS}
import freechips.rocketchip.tilelink._


class HashTableReadRequest extends Bundle {
  val unhashed_input_key = UInt((4*8).W)
  val current_absolute_addr = UInt(64.W)
}

class HashTableReadResult extends Bundle {
  // these are absolute addresses w.r.t. where the start of the buffer,
  // then, you can easily figure out whether the match is too far away
  // without having to check the buf
  val absolute_addr_output_val = UInt(64.W)
  // result addr is in range and table value matches
  val has_match = Bool()
}

class HashTableWriteRequest extends Bundle {
  val unhashed_input_key = UInt((4*8).W)
  val absolute_addr_input_val = UInt(64.W)
}

class HashTableBasic(numEntriesLog2HW: Int = 14)(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val read_req = (new HashTableReadRequest).flip
    val read_resp = (new HashTableReadResult)
    val write_req = Valid(new HashTableWriteRequest).flip
    val MAX_OFFSET_ALLOWED = UInt(INPUT, 64.W)
    val RUNTIME_HT_NUM_ENTRIES_LOG2 = UInt(INPUT, 5.W)
  })

  val numEntriesHW = 1 << numEntriesLog2HW

  // store the absolute address, plus the actual 32B to quickly check for a match
  val hash_mem = SyncReadMem(numEntriesHW, UInt((64+32).W))

  val hash_magic = BigInt("1e35a7bd", 16).U(32.W)


  val numEntriesRUNTIME = 1.U(32.W) << io.RUNTIME_HT_NUM_ENTRIES_LOG2
  val hash_mask = numEntriesRUNTIME - 1.U
  val hash_shift = (32 - numEntriesLog2HW)

  val hashed_input_key_read = ((hash_magic * io.read_req.unhashed_input_key) >> hash_shift.U) & hash_mask

  val last_cycle_read_unhashed_input_key = RegNext(io.read_req.unhashed_input_key)
  val last_cycle_read_current_absolute_addr = RegNext(io.read_req.current_absolute_addr)

  val read_data = hash_mem(hashed_input_key_read)
  io.read_resp.absolute_addr_output_val := read_data >> 32

  val result_identical = (read_data >> 32) === last_cycle_read_current_absolute_addr
  val MAX_OFFSET_ALLOWED = io.MAX_OFFSET_ALLOWED
  val result_diff = (last_cycle_read_current_absolute_addr - (read_data >> 32))
  val result_too_large =  result_diff > MAX_OFFSET_ALLOWED
  io.read_resp.has_match := ((read_data & BigInt("FFFFFFFF", 16).U((64+32).W)) === last_cycle_read_unhashed_input_key) && ((read_data >> 80) === (last_cycle_read_current_absolute_addr >> 48)) && (!result_identical) && (!result_too_large)

  when (((read_data & BigInt("FFFFFFFF", 16).U((64+32).W)) === last_cycle_read_unhashed_input_key) && ((read_data >> 80) === (last_cycle_read_current_absolute_addr >> 48))) {
    when (result_identical) {
      CompressAccelLogger.logInfo("HT: not reporting match because offset would be 0.\n")
    }
    when (result_too_large) {
      CompressAccelLogger.logInfo("HT: not reporting match because offset would be too large: diff=%d.\n", result_diff)
    }
  }

  val read_print_helper = RegInit(false.B)

  // NOTE: When using CY to simulate the Snappy & Zstd compressors, we need to use RegNext for writes.
  // This is due to the discrepency between how CIRCT and SFC generates Verilog SRAMs. Technically,
  // read & writes happening in the same cycle is a undefined behavior, but SFC allows writes to happen under reads.
  // In CIRCT, the writes are ignored.

// val last_io_write_req_valid = RegNext(io.write_req.valid)
// val last_io_write_req_bits_absolute_addr_input_val = RegNext(io.write_req.bits.absolute_addr_input_val)
// val last_io_write_req_bits_unhashed_input_key = RegNext(io.write_req.bits.unhashed_input_key)
  val last_io_write_req_valid = io.write_req.valid
  val last_io_write_req_bits_absolute_addr_input_val = io.write_req.bits.absolute_addr_input_val
  val last_io_write_req_bits_unhashed_input_key = io.write_req.bits.unhashed_input_key
  val hashed_input_key_write = ((hash_magic * last_io_write_req_bits_unhashed_input_key) >> hash_shift.U) & hash_mask

  when (last_io_write_req_valid) {
    hash_mem(hashed_input_key_write) := Cat(last_io_write_req_bits_absolute_addr_input_val, last_io_write_req_bits_unhashed_input_key)
    when (hashed_input_key_write === hashed_input_key_read) {
      CompressAccelLogger.logInfo("HT: conflicting r/w: rkey: 0x%x, wkey: 0x%x, rhash: 0x%x, whash: 0x%x, raddr_in: 0x%x, waddr_in: 0x%x\n",
        io.read_req.unhashed_input_key, io.write_req.bits.unhashed_input_key, hashed_input_key_read, hashed_input_key_write, io.read_req.current_absolute_addr, io.write_req.bits.absolute_addr_input_val)
      read_print_helper := true.B
    }
  }

  when (read_print_helper) {
    when (!((hashed_input_key_write === hashed_input_key_read) && io.write_req.valid)) {
      read_print_helper := false.B
    }
    CompressAccelLogger.logInfo("HT: response for read during conflicting r/w: lc_input_rkey: 0x%x, lc_input_raddr_in: 0x%x, read_data: 0x%x\n",
        last_cycle_read_unhashed_input_key,
        last_cycle_read_current_absolute_addr,
        read_data
    )
  }

  val max_offset_next_printer = RegNext(io.MAX_OFFSET_ALLOWED)
  when (max_offset_next_printer =/= io.MAX_OFFSET_ALLOWED) {
    CompressAccelLogger.logInfo("HT: max_offset updated from prev:%d to new:%d\n",
        max_offset_next_printer,
        io.MAX_OFFSET_ALLOWED
    )
  }

}


class LZ77HashMatcher()(implicit p: Parameters) extends Module {
  val io = IO(new Bundle{
    val write_snappy_header = Input(Bool())

    val memloader_in = (new MemLoaderConsumerBundle).flip
    val memloader_optional_hbsram_in = Valid(new HBSRAMWrite).flip
    // for each request, we need to be provided with the total input len
    val src_info = Decoupled(new StreamInfo).flip

    val memwrites_out = Decoupled(new CompressWriterBundle)
    val MAX_OFFSET_ALLOWED = UInt(INPUT, 64.W)
    val RUNTIME_HT_NUM_ENTRIES_LOG2 = UInt(INPUT, 5.W)
  })

  val hash_table = Module(new HashTableBasic())
  hash_table.io.MAX_OFFSET_ALLOWED := io.MAX_OFFSET_ALLOWED
  hash_table.io.RUNTIME_HT_NUM_ENTRIES_LOG2 := io.RUNTIME_HT_NUM_ENTRIES_LOG2
  val history_buffer = Module(new HistoryBufferSRAM)
  history_buffer.io.writes_in <> io.memloader_optional_hbsram_in

  val skip_amt = RegInit(32.U(10.W))
  val skip_bytes = skip_amt >> 5

  when (skip_amt === UInt(16*32)) {
    CompressAccelLogger.logInfo("skip_amt @ max\n")
  } .elsewhen (skip_amt >= UInt(16*32)) {
    CompressAccelLogger.logInfo("WARN: skip_amt EXCEEDED max: %d\n", skip_amt)
  }

  // this will be incremented by 1 every time we start a new input buffer to compress
  // to minimize false positives in the hash table
  val rotating_request_id = RegInit(1.U(16.W))

  // this will be reset to zero every time we start a new input buffer to compress
  val absolute_address_base = RegInit(0.U(48.W))

  val absolute_internal_address = Cat(rotating_request_id, absolute_address_base)

  history_buffer.io.read_req_in.bits.offset := absolute_internal_address - hash_table.io.read_resp.absolute_addr_output_val


  val NUM_BITS_FOR_STATES = 2
  val sClockInHTRead = 0.U(NUM_BITS_FOR_STATES.W)
      // |---- should always go to sHTResultAvailable
  val sHTResultAvailable = 1.U(NUM_BITS_FOR_STATES.W)
      // |---- if fast result valid, clock in hist read req
      // |---- if not valid, there should be an HT read clocked
      //       in during this cycle, so stay in this state. in
      //       this case, everything up to the start of the
      //       next hash being checked needs to be clocked
      //       into the literal output buf
  val sHistoryResultAvailable = 2.U(NUM_BITS_FOR_STATES.W)
      // |---- if history result matches any number of bytes,
      // |     a) consume that many bytes from memloader
      // |     i) if everything we have so far matched, clock in
      // |     a request for the next chunk from hist, and
      // |     setup-up an "in-progress" copy, stay in this
      // |     state
      // |     ii) if not everything matched, ship the copy,
      // |     consume the remaining from memloader,
      // |     clock in the hash read from match end -> end of
      // |     memloader if possible, and go to
      // |     sHTResultAvailable.
      // |     Otherwise, go to sClockInHTRead
      // |---- we can never get NO match, because of our
      //       fast-path HT storing data
  val sWriteUncompressedSizeVarint = 3.U(NUM_BITS_FOR_STATES.W)

  val compressorState = RegInit(sWriteUncompressedSizeVarint)

  val hist_as_bytevec = Wire(Vec(32, UInt(8.W)))
  val memloader_as_bytevec = Wire(Vec(32, UInt(8.W)))
  val comparison_as_boolvec = Wire(Vec(32, Bool()))

  for (elemno <- 0 until 32) {
    hist_as_bytevec(elemno) := history_buffer.io.read_resp_out.bits.data((elemno << 3) + 7, (elemno << 3))
    memloader_as_bytevec(elemno) := io.memloader_in.output_data((elemno << 3) + 7, (elemno << 3))
    comparison_as_boolvec(elemno) := (hist_as_bytevec(elemno) === memloader_as_bytevec(elemno))
  }

  // can be 0 to 32
  val num_match_bytes = PriorityEncoder(~(comparison_as_boolvec.asUInt | 0.U(33.W)))


  // tie-offs
  io.memloader_in.user_consumed_bytes := 0.U
  io.memloader_in.output_ready := false.B
  io.src_info.ready := false.B
  io.memwrites_out.valid := false.B
  io.memwrites_out.bits.data := 0.U
  io.memwrites_out.bits.validbytes := 0.U
  io.memwrites_out.bits.end_of_message := false.B
  io.memwrites_out.bits.is_copy := false.B
  io.memwrites_out.bits.length_header := false.B
  io.memwrites_out.bits.is_dummy := false.B

  hash_table.io.write_req.valid := false.B
  hash_table.io.write_req.bits.unhashed_input_key := 0.U
  hash_table.io.write_req.bits.absolute_addr_input_val := 0.U
  history_buffer.io.read_req_in.valid := false.B
  history_buffer.io.read_advance_ptr.valid := false.B

  // end of message addr reg management
  when (io.memloader_in.output_last_chunk && (io.memloader_in.available_output_bytes === io.memloader_in.user_consumed_bytes) && io.memloader_in.output_valid && io.memloader_in.output_ready) {
    // end of buffer condition
    rotating_request_id := rotating_request_id + 1.U
    absolute_address_base := 0.U
    io.src_info.ready := true.B
  }

  val in_progress_offset = RegInit(0.U(64.W))
  val in_progress_copy_len = RegInit(0.U(64.W))


  val varint_encoder = Module(new CombinationalVarintEncode)
  varint_encoder.io.inputData := io.src_info.bits.isize

  val size_written = RegInit(false.B)

  switch (compressorState) {
    is (sWriteUncompressedSizeVarint) {
      io.memwrites_out.valid := io.src_info.valid

      io.memwrites_out.bits.data := Mux(io.write_snappy_header, varint_encoder.io.outputData, BigInt("DEADBEAF", 16).U(32.W))
      io.memwrites_out.bits.validbytes := Mux(io.write_snappy_header, varint_encoder.io.outputBytes, 4.U)

      io.memwrites_out.bits.end_of_message := false.B
      io.memwrites_out.bits.is_copy := false.B
      io.memwrites_out.bits.length_header := true.B

      when (io.memwrites_out.ready && io.src_info.valid) {
        CompressAccelLogger.logCritical("Write header for rqid: %d\n", rotating_request_id)
        compressorState := sClockInHTRead
      }
    }
    is (sClockInHTRead) {
      when (io.memloader_in.output_last_chunk && (io.memloader_in.available_output_bytes < 4.U)) {
        // fewer than 3 bytes are remaining, end of buffer
        io.memloader_in.output_ready := io.memwrites_out.ready
        io.memloader_in.user_consumed_bytes := io.memloader_in.available_output_bytes

        io.memwrites_out.valid := io.memloader_in.output_valid
        io.memwrites_out.bits.data := io.memloader_in.output_data
        io.memwrites_out.bits.validbytes := io.memloader_in.available_output_bytes
        io.memwrites_out.bits.end_of_message := true.B
        io.memwrites_out.bits.is_copy := false.B

        history_buffer.io.read_advance_ptr.bits.advance_bytes := io.memloader_in.available_output_bytes

        when (io.memloader_in.output_valid && io.memwrites_out.ready) {
          compressorState := sWriteUncompressedSizeVarint
          CompressAccelLogger.logInfo("EOB: fewer than 4 bytes left.\n")
          history_buffer.io.read_advance_ptr.valid := true.B
        }

      } .elsewhen (io.memloader_in.available_output_bytes >= 4.U) {

        hash_table.io.read_req.unhashed_input_key := io.memloader_in.output_data(31, 0)
        hash_table.io.read_req.current_absolute_addr := absolute_internal_address

        hash_table.io.write_req.bits.unhashed_input_key := io.memloader_in.output_data(31, 0)
        hash_table.io.write_req.bits.absolute_addr_input_val := absolute_internal_address

        // regular case
        // by default HT input is clocked to low 4 bytes of memloader
        when (io.memloader_in.output_valid) {
          compressorState := sHTResultAvailable
          hash_table.io.write_req.valid := true.B
          CompressAccelLogger.logInfo("HT Read Issued: key 0x%x, addr 0x%x\n",
            hash_table.io.read_req.unhashed_input_key,
            hash_table.io.read_req.current_absolute_addr)

        }
      } .otherwise {
        // not end of buffer, but not enough data yet. just wait.
      }
    }
    is (sHTResultAvailable) {
      when (hash_table.io.read_resp.has_match) {
        // at least 4 bytes match, so we're definitely starting a copy
        // TODO(perf improvement): make the next stage start reading hist buf
        // at +4 instead of +0
        skip_amt := 32.U
        in_progress_offset := absolute_internal_address - hash_table.io.read_resp.absolute_addr_output_val
        when ((absolute_internal_address - hash_table.io.read_resp.absolute_addr_output_val) === 0.U) {
          CompressAccelLogger.logInfo("ERROR: got zero offset. absolute_internal_addres: 0x%x, hash_table_result: 0x%x\n",
            absolute_internal_address,
            hash_table.io.read_resp.absolute_addr_output_val
          )
        }
        history_buffer.io.read_req_in.valid := true.B
        compressorState := sHistoryResultAvailable
      } .otherwise {

        hash_table.io.read_req.unhashed_input_key := io.memloader_in.output_data(31, 0)
        hash_table.io.read_req.current_absolute_addr := absolute_internal_address

        when ((skip_bytes + 4.U) > io.memloader_in.available_output_bytes) {
          when (io.memloader_in.output_last_chunk) {
            // there isn't enough stuff left in this buffer.
            // just write to literals, end it, and return to start state
            io.memloader_in.output_ready := io.memwrites_out.ready
            io.memloader_in.user_consumed_bytes := io.memloader_in.available_output_bytes

            io.memwrites_out.valid := io.memloader_in.output_valid
            io.memwrites_out.bits.data := io.memloader_in.output_data
            io.memwrites_out.bits.validbytes := io.memloader_in.available_output_bytes
            io.memwrites_out.bits.end_of_message := true.B
            io.memwrites_out.bits.is_copy := false.B

            history_buffer.io.read_advance_ptr.bits.advance_bytes := io.memloader_in.available_output_bytes

            when (io.memwrites_out.ready && io.memloader_in.output_valid) {
              CompressAccelLogger.logInfo("EOB: fewer than skip_bytes(=%d) bytes left.\n", skip_bytes)
              compressorState := sWriteUncompressedSizeVarint
              history_buffer.io.read_advance_ptr.valid := true.B
            }
          } .otherwise {
            // 2: we just don't have data yet, so wait in this state
          }
        } .otherwise {
          // we have enough bytes to proceed with the next HT lookup + clocking
          // in everything so far to literals buf

          io.memloader_in.output_ready := io.memwrites_out.ready
          io.memloader_in.user_consumed_bytes := skip_bytes

          io.memwrites_out.valid := io.memloader_in.output_valid
          io.memwrites_out.bits.data := io.memloader_in.output_data
          io.memwrites_out.bits.validbytes := skip_bytes
          io.memwrites_out.bits.end_of_message := false.B
          io.memwrites_out.bits.is_copy := false.B

          history_buffer.io.read_advance_ptr.bits.advance_bytes := skip_bytes


          hash_table.io.write_req.bits.unhashed_input_key := (io.memloader_in.output_data >> (skip_bytes << 3)) & (BigInt("FFFFFFFF", 16).U(32.W))
          hash_table.io.write_req.bits.absolute_addr_input_val := absolute_internal_address + skip_bytes

          when (io.memwrites_out.ready && io.memloader_in.output_valid) {
            CompressAccelLogger.logInfo("Advance by skip_bytes(=%d).\n", skip_bytes)
            // stay in this state, we'll have an HT result on the next cycle
            history_buffer.io.read_advance_ptr.valid := true.B
            when ((skip_amt + skip_bytes) <= ((16*32).U)) {
              skip_amt := skip_amt + skip_bytes
            }
            absolute_address_base := absolute_address_base + skip_bytes

            hash_table.io.read_req.unhashed_input_key := (io.memloader_in.output_data >> (skip_bytes << 3)) & (BigInt("FFFFFFFF", 16).U(32.W))
              //io.memloader_in.output_data((skip_bytes << 3) + 31.U, (skip_bytes << 3))
            hash_table.io.read_req.current_absolute_addr := absolute_internal_address + skip_bytes
            hash_table.io.write_req.valid := true.B
          }
        }
      }
    }
    is (sHistoryResultAvailable) {
      // by default, i.e. if stalled out, need to keep feeding history
      // buffer the same input:
      history_buffer.io.read_req_in.bits.offset := in_progress_offset
      history_buffer.io.read_req_in.valid := true.B



      for (elemno <- 0 until 32) {
        val hist_val = hist_as_bytevec(elemno)
        val curr_val = memloader_as_bytevec(elemno)
        CompressAccelLogger.logInfo("elemno: %d, hist 0x%x, current 0x%x\n",
            UInt(elemno),
            hist_val,
            curr_val
        )
      }
      CompressAccelLogger.logInfo("Overall comparison num_match_bytes: %d\n", num_match_bytes)

      // NOTE: this will emit copies with lengths greater than 64.
      // we will rely on the memwriter to break them into chunks and
      // encode as snappy expects. the format we emit is simply:
      // Cat(64b length, 64b offset)

      when (num_match_bytes >= io.memloader_in.available_output_bytes) {
        // match that will consume entire available memloader input and
        // potentially continue


        when (!io.memloader_in.output_last_chunk) {
          // this is not necessarily the end of the copy and it isn't the end
          // of the buffer overall. just advance stuff and move on
          io.memloader_in.output_ready := true.B
          io.memloader_in.user_consumed_bytes := io.memloader_in.available_output_bytes

          when (io.memloader_in.output_valid) {
              history_buffer.io.read_advance_ptr.bits.advance_bytes := io.memloader_in.available_output_bytes
              history_buffer.io.read_advance_ptr.valid := true.B

              history_buffer.io.read_req_in.bits.offset := in_progress_offset - io.memloader_in.available_output_bytes
              history_buffer.io.read_req_in.valid := true.B

              in_progress_copy_len := in_progress_copy_len + io.memloader_in.available_output_bytes
              absolute_address_base := absolute_address_base + io.memloader_in.available_output_bytes
          }
        } .otherwise {

          // this is the end of a copy and the end of a buffer overall.
          io.memloader_in.output_ready := io.memwrites_out.ready
          io.memloader_in.user_consumed_bytes := io.memloader_in.available_output_bytes

          io.memwrites_out.valid := io.memloader_in.output_valid
          io.memwrites_out.bits.data := Cat(in_progress_copy_len + io.memloader_in.available_output_bytes, in_progress_offset)
          io.memwrites_out.bits.validbytes := 16.U
          io.memwrites_out.bits.end_of_message := true.B
          io.memwrites_out.bits.is_copy := true.B

          when (io.memloader_in.output_valid && io.memwrites_out.ready) {
            CompressAccelLogger.logInfo("Emitting copy and EOB: offset: %d, length: %d.\n", in_progress_offset, in_progress_copy_len + io.memloader_in.available_output_bytes)
            history_buffer.io.read_advance_ptr.bits.advance_bytes := io.memloader_in.available_output_bytes
            history_buffer.io.read_advance_ptr.valid := true.B
            in_progress_copy_len := 0.U
            in_progress_offset := 0.U
            absolute_address_base := absolute_address_base + io.memloader_in.available_output_bytes
            compressorState := sWriteUncompressedSizeVarint
          }

        }

      } .otherwise {
        // in this case, we have reached the end of the copy and there's more
        // stuff at the end of the buffer to keep compressing. ship it
        // to the memwriter and jump back to the start state
        // TODO: future optimization can again overlap the HT lookup here.

        io.memloader_in.output_ready := io.memwrites_out.ready
        io.memloader_in.user_consumed_bytes := num_match_bytes

        io.memwrites_out.valid := io.memloader_in.output_valid
        io.memwrites_out.bits.data := Cat(in_progress_copy_len + num_match_bytes, in_progress_offset)
        io.memwrites_out.bits.validbytes := 16.U
        io.memwrites_out.bits.end_of_message := false.B
        io.memwrites_out.bits.is_copy := true.B

        when (io.memloader_in.output_valid && io.memwrites_out.ready) {
          CompressAccelLogger.logInfo("Emitting copy and continuing: offset: %d, length: %d.\n", in_progress_offset, in_progress_copy_len + num_match_bytes)
          history_buffer.io.read_advance_ptr.bits.advance_bytes := num_match_bytes
          history_buffer.io.read_advance_ptr.valid := true.B
          in_progress_copy_len := 0.U
          in_progress_offset := 0.U
          absolute_address_base := absolute_address_base + num_match_bytes
          compressorState := sClockInHTRead
          // TODO(perf improvement): update hash table
        }
      }

    }
  }
}
