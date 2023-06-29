package compressacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants

class SnappyInternalCommandRep extends Bundle {
  // if not is_copy, then it's a literal CHUNK command
  val is_copy = Bool(OUTPUT)
  val copy_offset = UInt(OUTPUT, 32.W)
  val copy_length = UInt(OUTPUT, 7.W)
  val final_command = Bool(OUTPUT)
}

class LiteralChunk extends Bundle {
  val chunk_data = UInt(OUTPUT, 256.W)
  // could be 7.W but make it easy for now
  val chunk_size_bytes = UInt(OUTPUT, 9.W)
}

class SnappyDecompressorCommandConverter()(implicit p: Parameters) extends Module {

  val io = IO(new Bundle {
    val mem_stream = (new MemLoaderConsumerBundle).flip
    val internal_commands = Decoupled(new SnappyInternalCommandRep)
    val literal_chunks = Decoupled(new LiteralChunk)
  })

  val internal_command_out_queue = Module(new Queue(new SnappyInternalCommandRep, 10))
  io.internal_commands <> internal_command_out_queue.io.deq
  val literal_chunk_out_queue = Module(new Queue(new LiteralChunk, 5))
  io.literal_chunks <> literal_chunk_out_queue.io.deq

  val varint_decode = Module(new CombinationalVarint)
  varint_decode.io.inputRawData := io.mem_stream.output_data

  // tie off consumer IF by default
  io.mem_stream.output_ready := false.B
  io.mem_stream.user_consumed_bytes := 0.U

  // tie off queue enqueues by default
  internal_command_out_queue.io.enq.valid := false.B
  literal_chunk_out_queue.io.enq.valid := false.B

  val NUM_BITS_FOR_STATES = 4
  val sHandleLength = 0.U(NUM_BITS_FOR_STATES.W)
  val sCommandDispatch = 1.U(NUM_BITS_FOR_STATES.W)
  val sLongLiteralEmit = 2.U(NUM_BITS_FOR_STATES.W)

  val converterState = RegInit(sHandleLength)

  val long_literal_length = RegInit(0.U(32.W))

  val fire_single_cycle_literal = DecoupledHelper(
    io.mem_stream.output_valid,
    internal_command_out_queue.io.enq.ready,
    literal_chunk_out_queue.io.enq.ready
  )

  switch (converterState) {
    is (sHandleLength) {
      io.mem_stream.output_ready := true.B
      io.mem_stream.user_consumed_bytes := varint_decode.io.consumedLenBytes

      when (io.mem_stream.output_valid) {

        CompressAccelLogger.logInfo("expecting uncompressed size: %d\n",
          varint_decode.io.outputData
        )
        converterState := sCommandDispatch
      }
    }
    is (sCommandDispatch) {
      val SNAPPY_OP_LITERAL = 0.U(2.W)
      val SNAPPY_OP_COPY_1 = 1.U(2.W)
      val SNAPPY_OP_COPY_2 = 2.U(2.W)
      val SNAPPY_OP_COPY_4 = 3.U(2.W)


      val literal_len_6bit_raw = io.mem_stream.output_data(7, 2)
      val literal_len_6bit = literal_len_6bit_raw +& 1.U
      val is_literal_single_cycle = literal_len_6bit < 32.U // 31B or less
      val is_literal_len_6bit = literal_len_6bit_raw < 60.U

      val literal_len_1byte = io.mem_stream.output_data(15, 8) +& 1.U
      val literal_len_2byte = io.mem_stream.output_data(23, 8) +& 1.U
      val literal_len_3byte = io.mem_stream.output_data(31, 8) +& 1.U
      val literal_len_4byte = io.mem_stream.output_data(39, 8) +& 1.U

      val is_literal_len_1byte = literal_len_6bit_raw === 60.U
      val is_literal_len_2byte = literal_len_6bit_raw === 61.U
      val is_literal_len_3byte = literal_len_6bit_raw === 62.U
      val is_literal_len_4byte = literal_len_6bit_raw === 63.U

      when (io.mem_stream.output_data(1, 0) === SNAPPY_OP_LITERAL) {
        when (is_literal_single_cycle) {
          io.mem_stream.output_ready := fire_single_cycle_literal.fire(io.mem_stream.output_valid)
          internal_command_out_queue.io.enq.valid := fire_single_cycle_literal.fire(internal_command_out_queue.io.enq.ready)
          literal_chunk_out_queue.io.enq.valid := fire_single_cycle_literal.fire(literal_chunk_out_queue.io.enq.ready)

          internal_command_out_queue.io.enq.bits.is_copy := false.B
          internal_command_out_queue.io.enq.bits.final_command := io.mem_stream.output_last_chunk && (io.mem_stream.available_output_bytes === io.mem_stream.user_consumed_bytes)
          literal_chunk_out_queue.io.enq.bits.chunk_data := io.mem_stream.output_data(255, 8)
          literal_chunk_out_queue.io.enq.bits.chunk_size_bytes := literal_len_6bit

          io.mem_stream.user_consumed_bytes := literal_len_6bit +& 1.U

          when (fire_single_cycle_literal.fire) {
            when (internal_command_out_queue.io.enq.bits.final_command) {
              converterState := sHandleLength
            } .otherwise {
              converterState := sCommandDispatch
            }
          }
        } .otherwise {
          io.mem_stream.output_ready := true.B

          when (io.mem_stream.output_valid) {
            converterState := sLongLiteralEmit
          }

          when (is_literal_len_6bit) {
            long_literal_length := literal_len_6bit
            io.mem_stream.user_consumed_bytes := 1.U
          } .elsewhen (is_literal_len_1byte) {
            long_literal_length := literal_len_1byte
            io.mem_stream.user_consumed_bytes := 2.U
          } .elsewhen (is_literal_len_2byte) {
            long_literal_length := literal_len_2byte
            io.mem_stream.user_consumed_bytes := 3.U
          } .elsewhen (is_literal_len_3byte) {
            long_literal_length := literal_len_3byte
            io.mem_stream.user_consumed_bytes := 4.U
          } .elsewhen (is_literal_len_4byte) {
            long_literal_length := literal_len_4byte
            io.mem_stream.user_consumed_bytes := 5.U
          }
        }

      } .otherwise {

        io.mem_stream.output_ready  := internal_command_out_queue.io.enq.ready
        internal_command_out_queue.io.enq.valid := io.mem_stream.output_valid

        when (internal_command_out_queue.io.enq.fire) {
          when (internal_command_out_queue.io.enq.bits.final_command) {
            converterState := sHandleLength
          } .otherwise {
            converterState := sCommandDispatch
          }

        }

        internal_command_out_queue.io.enq.bits.is_copy := true.B
        internal_command_out_queue.io.enq.bits.final_command := io.mem_stream.output_last_chunk && (io.mem_stream.available_output_bytes === io.mem_stream.user_consumed_bytes)

        when (io.mem_stream.output_data(1, 0) === SNAPPY_OP_COPY_1) {
          internal_command_out_queue.io.enq.bits.copy_offset := Cat(io.mem_stream.output_data(7, 5), io.mem_stream.output_data(15, 8))
          internal_command_out_queue.io.enq.bits.copy_length := io.mem_stream.output_data(4, 2) +& 4.U
          io.mem_stream.user_consumed_bytes := 2.U
        } .elsewhen (io.mem_stream.output_data(1, 0) === SNAPPY_OP_COPY_2) {
          internal_command_out_queue.io.enq.bits.copy_offset := io.mem_stream.output_data(23, 8)
          internal_command_out_queue.io.enq.bits.copy_length := io.mem_stream.output_data(7, 2) +& 1.U
          io.mem_stream.user_consumed_bytes :=  3.U
        } .elsewhen (io.mem_stream.output_data(1, 0) === SNAPPY_OP_COPY_4) {
          internal_command_out_queue.io.enq.bits.copy_offset := io.mem_stream.output_data(39, 8)
          internal_command_out_queue.io.enq.bits.copy_length := io.mem_stream.output_data(7, 2) +& 1.U
          io.mem_stream.user_consumed_bytes := 5.U
        }
      }
    }
    is (sLongLiteralEmit) {
      val bytes_this_iter = Mux(long_literal_length > 32.U, 32.U, long_literal_length)
      val last_long_literal_chunk = bytes_this_iter === long_literal_length

      io.mem_stream.user_consumed_bytes := bytes_this_iter

      io.mem_stream.output_ready := fire_single_cycle_literal.fire(io.mem_stream.output_valid)
      internal_command_out_queue.io.enq.valid := fire_single_cycle_literal.fire(internal_command_out_queue.io.enq.ready)
      literal_chunk_out_queue.io.enq.valid := fire_single_cycle_literal.fire(literal_chunk_out_queue.io.enq.ready)

      internal_command_out_queue.io.enq.bits.is_copy := false.B
      internal_command_out_queue.io.enq.bits.final_command := io.mem_stream.output_last_chunk && (io.mem_stream.available_output_bytes === io.mem_stream.user_consumed_bytes)
      literal_chunk_out_queue.io.enq.bits.chunk_data := io.mem_stream.output_data
      literal_chunk_out_queue.io.enq.bits.chunk_size_bytes := bytes_this_iter

      io.mem_stream.user_consumed_bytes := bytes_this_iter

      when (fire_single_cycle_literal.fire) {
        when (internal_command_out_queue.io.enq.bits.final_command) {
          converterState := sHandleLength
        } .elsewhen (last_long_literal_chunk) {
          converterState := sCommandDispatch
        } .otherwise {
          long_literal_length := long_literal_length - bytes_this_iter
        }
      }

    }

  }

}
