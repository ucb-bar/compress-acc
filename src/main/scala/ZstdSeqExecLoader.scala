package compressacc

import Chisel._
import chisel3.{Printable, dontTouch}
import freechips.rocketchip.tile._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
/*
class LiteralChunk extends Bundle{
  val chunk_data = UInt(OUTPUT, 256.W)
  val chunk_size_bytes = UInt(OUTPUT, 8.W)
}
*/
class ZstdSeqExecLoader(l2bw: Int)(implicit p: Parameters) extends Module{
  val io = IO(new Bundle{
    val algorithm = Input(UInt(1.W))

    val mem_stream = (new MemLoaderConsumerBundle).flip
    val command_in = (Decoupled(new ZstdSeqInfo)).flip
    val num_literals_seqexec = Input(UInt(64.W))
    val completion_seqexec = Input(Bool())

    val command_out = Decoupled(new ZstdSeqInfo)
    val literal_chunk = Decoupled(new LiteralChunk)
  })
  // val l2bwdiv8_uint = Wire(UInt(64.W)) //avoid verilator error
  // l2bwdiv8_uint := (l2bw/8).U
  // val eight_10bits = Wire(UInt(10.W)) //avoid verilator error
  // eight_10bits := 8.U

  val nosnappy = p(NoSnappy)

  // Zstd modules
  // Queue (1) Literal data queue: receives literals from the memloader
  val litdata_queue_flush = false.B //io.lit_completion
  val litdata_queue = Module(new Queue(new LiteralChunk, 5, false, false, litdata_queue_flush || reset))
  // Queue (2) Command queue: receives sequences (LL and ML sliced into (L2 bandwidth)/8 bytes)
  val command_queue = Module(new Queue(new ZstdSeqInfo, 5))

  val num_literals = io.num_literals_seqexec
  val litdata_received = RegInit(0.U(16.W))

  val buffermanager = Module(new BufferManager(l2bw))
  val recent_stream = Wire(UInt(l2bw.W))

  val bytes_consumed = RegInit(0.U(32.W))

  ///////// Zstd Logic //////////
  when(io.algorithm===0.U){
    when(io.completion_seqexec){
      litdata_received := 0.U
    }.elsewhen(litdata_queue.io.enq.ready && litdata_queue.io.enq.valid){
      litdata_received := litdata_received + 1.U
    }
    dontTouch(litdata_received)
    // Stage 1: inputs <-> litdata_queue, command_queue
    // (1) litdata_queue receiving literals from Memloader
    // val litdata_size = Mux(litdata_received < num_literals/l2bwdiv8_uint, //avoid verilator error
    val litdata_size = Mux(litdata_received < num_literals/(l2bw/8).U,
      (l2bw/8).U,
      num_literals % (l2bw/8).U)
    litdata_queue.io.enq.bits.chunk_data := io.mem_stream.output_data
    litdata_queue.io.enq.bits.chunk_size_bytes := litdata_size
    litdata_queue.io.enq.valid := io.mem_stream.output_valid &&
    (litdata_size <= io.mem_stream.available_output_bytes)
    io.mem_stream.output_ready := litdata_queue.io.enq.ready &&
      (litdata_size <= io.mem_stream.available_output_bytes)
    io.mem_stream.user_consumed_bytes := litdata_size

    // (2) command_queue receiving sequences from SeqExecControl
    command_queue.io.enq <> io.command_in

    // Stage 1.5: litdata_queue <-> buffermanager
    // Buffer manager: provides the most recent literal, deals with literal consumption.
    buffermanager.io.input_stream_deq.bits := litdata_queue.io.deq.bits.chunk_data
    buffermanager.io.input_stream_deq.valid := litdata_queue.io.deq.valid
    litdata_queue.io.deq.ready := buffermanager.io.input_stream_deq.ready
    buffermanager.io.flush := io.completion_seqexec
    recent_stream := buffermanager.io.most_recent_stream

    // Stage 2: buffermanager, command_queue <-> outputs
    // litCond works as the buffermanager.io.most_recent_stream.valid
    val litCond = (!command_queue.io.deq.bits.is_match && 
      // command_queue.io.deq.bits.ll <= (buffermanager.io.available_bits/eight_10bits)) // avoid verilator error
      command_queue.io.deq.bits.ll <= (buffermanager.io.available_bits/8.U))
    val matchCond = command_queue.io.deq.bits.is_match
    val seqCond = litCond || matchCond
    // Consume literals in the buffer when io.literal_chunk is fired.
    when(io.literal_chunk.valid && io.literal_chunk.ready){
      buffermanager.io.bits_consumed := command_queue.io.deq.bits.ll * 8.U
      bytes_consumed := bytes_consumed + command_queue.io.deq.bits.ll
    }.otherwise{
      buffermanager.io.bits_consumed := 0.U
    }
    when(io.completion_seqexec){bytes_consumed := 0.U}
    dontTouch(bytes_consumed)
    // If there was a ready signal on recent_stream,
    // buffermanager.io.most_recent_stream.ready := io.literal_chunk.ready
    command_queue.io.deq.ready := io.command_out.ready
    io.command_out.valid := command_queue.io.deq.valid && seqCond
    io.literal_chunk.valid := litCond

    // Output
    io.literal_chunk.bits.chunk_data := (recent_stream & ((1.U<<(8.U*command_queue.io.deq.bits.ll))-1.U))
    io.literal_chunk.bits.chunk_size_bytes := command_queue.io.deq.bits.ll
    io.command_out.bits := command_queue.io.deq.bits
  }

  ///////// Snappy Logic //////////
  if(!nosnappy){
    //Snappy modules
    val internal_command_out_queue = Module(new Queue(new ZstdSeqInfo, 10))
    val literal_chunk_out_queue = Module(new Queue(new LiteralChunk, 5))

    val varint_decode = Module(new CombinationalVarint)
    
    val converterState = RegInit(0.U(4.W))

    val long_literal_length = RegInit(0.U(32.W))
    val emitted_bytes = RegInit(0.U(7.W))
    when(io.algorithm===1.U) {
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
            // TODO: validate output size is sufficient

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

              internal_command_out_queue.io.enq.bits.is_match := false.B
              internal_command_out_queue.io.enq.bits.is_final_command := io.mem_stream.output_last_chunk && (io.mem_stream.available_output_bytes === io.mem_stream.user_consumed_bytes)
              literal_chunk_out_queue.io.enq.bits.chunk_data := io.mem_stream.output_data(255, 8)
              literal_chunk_out_queue.io.enq.bits.chunk_size_bytes := literal_len_6bit

              io.mem_stream.user_consumed_bytes := literal_len_6bit +& 1.U

              when (fire_single_cycle_literal.fire) {
                when (internal_command_out_queue.io.enq.bits.is_final_command) {
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
              when (internal_command_out_queue.io.enq.bits.is_final_command) {
                converterState := sHandleLength
              } .otherwise {
                converterState := sCommandDispatch
              }

            }

            internal_command_out_queue.io.enq.bits.is_match := true.B
            internal_command_out_queue.io.enq.bits.is_final_command := io.mem_stream.output_last_chunk && (io.mem_stream.available_output_bytes === io.mem_stream.user_consumed_bytes)

            when (io.mem_stream.output_data(1, 0) === SNAPPY_OP_COPY_1) {
              internal_command_out_queue.io.enq.bits.offset := Cat(io.mem_stream.output_data(7, 5), io.mem_stream.output_data(15, 8))
              internal_command_out_queue.io.enq.bits.ml := io.mem_stream.output_data(4, 2) +& 4.U
              io.mem_stream.user_consumed_bytes := 2.U
            } .elsewhen (io.mem_stream.output_data(1, 0) === SNAPPY_OP_COPY_2) {
              internal_command_out_queue.io.enq.bits.offset := io.mem_stream.output_data(23, 8)
              internal_command_out_queue.io.enq.bits.ml := io.mem_stream.output_data(7, 2) +& 1.U
              io.mem_stream.user_consumed_bytes :=  3.U
            } .elsewhen (io.mem_stream.output_data(1, 0) === SNAPPY_OP_COPY_4) {
              internal_command_out_queue.io.enq.bits.offset := io.mem_stream.output_data(39, 8)
              internal_command_out_queue.io.enq.bits.ml := io.mem_stream.output_data(7, 2) +& 1.U
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

          internal_command_out_queue.io.enq.bits.is_match := false.B
          internal_command_out_queue.io.enq.bits.is_final_command := io.mem_stream.output_last_chunk && (io.mem_stream.available_output_bytes === io.mem_stream.user_consumed_bytes)
          literal_chunk_out_queue.io.enq.bits.chunk_data := io.mem_stream.output_data
          literal_chunk_out_queue.io.enq.bits.chunk_size_bytes := bytes_this_iter

          io.mem_stream.user_consumed_bytes := bytes_this_iter

          when (fire_single_cycle_literal.fire) {
            when (internal_command_out_queue.io.enq.bits.is_final_command) {
              converterState := sHandleLength
            } .elsewhen (last_long_literal_chunk) {
              converterState := sCommandDispatch
            } .otherwise {
              long_literal_length := long_literal_length - bytes_this_iter
            }
          }
        }
      }

      io.literal_chunk <> literal_chunk_out_queue.io.deq

      io.command_out.bits.is_match := internal_command_out_queue.io.deq.bits.is_match
      io.command_out.bits.offset := internal_command_out_queue.io.deq.bits.offset
      io.command_out.bits.is_final_command := internal_command_out_queue.io.deq.bits.is_final_command && 
        internal_command_out_queue.io.deq.fire

      
      val leftover_bytes = internal_command_out_queue.io.deq.bits.ml - emitted_bytes
      val copy_offset = internal_command_out_queue.io.deq.bits.offset

      val emittable_copy_bytes = Mux(leftover_bytes < 32.U,
        Mux(leftover_bytes < copy_offset,
          leftover_bytes,
          copy_offset),
        Mux(32.U < copy_offset,
          32.U,
          copy_offset)
      )
      io.command_out.bits.ml := emittable_copy_bytes

      val fire_output_command = DecoupledHelper(
        internal_command_out_queue.io.deq.valid,
        io.command_out.ready,
      )

      when (internal_command_out_queue.io.deq.fire) {
        emitted_bytes := 0.U
      } .elsewhen (io.command_out.fire) {
        emitted_bytes := emitted_bytes + emittable_copy_bytes
      }

      internal_command_out_queue.io.deq.ready := ((!internal_command_out_queue.io.deq.bits.is_match) ||
        (leftover_bytes === emittable_copy_bytes)) &&
        io.command_out.ready
      io.command_out.valid := internal_command_out_queue.io.deq.valid

    }
    // Prevent unpredictable behaviors in another algorithm.
    when(io.algorithm===1.U){
      litdata_received := 0.U
      bytes_consumed := 0.U
      buffermanager.io.input_stream_deq.valid := false.B
      buffermanager.io.bits_consumed := 0.U
      litdata_queue.io.enq.valid := false.B
      command_queue.io.enq.valid := false.B
    }
    when(io.algorithm===0.U){
      converterState := 0.U
      long_literal_length := 0.U
      internal_command_out_queue.io.enq.valid := false.B
      literal_chunk_out_queue.io.enq.valid := false.B
      varint_decode.io.inputRawData := 0.U
      emitted_bytes := 0.U
    }
  }
  
  // Prevent unpredictable behaviors in another algorithm.
  if(nosnappy){
    when(io.algorithm===1.U){
      litdata_received := 0.U
      bytes_consumed := 0.U
      buffermanager.io.input_stream_deq.valid := false.B
      buffermanager.io.bits_consumed := 0.U
      litdata_queue.io.enq.valid := false.B
      command_queue.io.enq.valid := false.B
    }
  }  
}
