package compressacc

import chisel3._
import chisel3.util._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheArbiter}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._


class WriteBitsBundle(val dataWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
  val validbits = UInt(log2Ceil(dataWidth + 1).W)
  val end_of_message = Bool()
}

class BitsConsumerBundle(val dataWidth: Int) extends Bundle {
  val valid = Output(Bool())
  val ready = Input(Bool())
  val consumed_bytes = Input(UInt(log2Ceil(dataWidth + 1).W))
  val avail_bytes = Output(UInt(log2Ceil(dataWidth + 1).W))
  val data = Output(UInt(dataWidth.W))
  val last_chunk = Output(Bool())
}

class CompressedBitsBuffIO(val dataWidth: Int) extends Bundle {
  val writes_in = Flipped(Decoupled(new WriteBitsBundle(dataWidth)))
  val consumer = new BitsConsumerBundle(dataWidth)
}

// input interface is just like memwriter except that it is in bit granularity instead of byte granularity
// output interface is just like memloader
class CompressedBitsBuff(val unroll_cnt: Int, val max_bits_per_code: Int)(implicit p: Parameters) extends Module {
// val HUF_CODE_MAX_BITS = 11
  val dataWidth = max_bits_per_code * unroll_cnt
  val dataWidthByteAligned = if (dataWidth % 8 == 0) dataWidth else (dataWidth/8 + 1) * 8

  // 8 byte interface
  val NUM_QUEUES = if (dataWidthByteAligned <= 64) {
    64
  } else if (dataWidthByteAligned <= 128) {
    128
  } else if (dataWidthByteAligned <= 256) {
    256
  } else {
    0 // doens't make sense
  }
  val QUEUE_DEPTH = 16
  assert(NUM_QUEUES >= dataWidthByteAligned)

  val io = IO(new CompressedBitsBuffIO(NUM_QUEUES))

  val incoming_writes = Module(new Queue(new WriteBitsBundle(NUM_QUEUES), 8))
  incoming_writes.io.enq <> io.writes_in

// val data = UInt(dataWidth.W)
// val validbits = UInt(log2Ceil(dataWidth + 1).W)
// val end_of_message = Bool()

  val buf_lens_q = Module(new Queue(UInt(64.W), 10))
  val buf_lens_tracker = RegInit(0.U(64.W))

  when (incoming_writes.io.deq.fire) {
    when (incoming_writes.io.deq.bits.end_of_message) {
      buf_lens_tracker := 0.U
    } .otherwise {
      buf_lens_tracker := buf_lens_tracker + incoming_writes.io.deq.bits.validbits
    }
  }

  val buffers = Seq.fill(NUM_QUEUES)(Module(new Queue(UInt(1.W), QUEUE_DEPTH)).io)
  val write_start_idx = RegInit(0.U(log2Up(NUM_QUEUES + 1).W))

  val bits_to_write = incoming_writes.io.deq.bits.validbits
  val wrap_len_idx_wide = write_start_idx + bits_to_write
  val wrap_len_idx_end = wrap_len_idx_wide % NUM_QUEUES.U
  val wrapped = wrap_len_idx_wide >= NUM_QUEUES.U

  val all_queues_ready = buffers.map(_.enq.ready).reduce(_ && _)

  val end_of_buf = incoming_writes.io.deq.bits.end_of_message
  val account_for_buf_length = (!end_of_buf) || (end_of_buf && buf_lens_q.io.enq.ready)

  val input_fire_all_queues = DecoupledHelper(
    all_queues_ready,
    account_for_buf_length,
    incoming_writes.io.deq.valid)

  incoming_writes.io.deq.ready := input_fire_all_queues.fire(incoming_writes.io.deq.valid)

  val write_data_bit_vec = Wire(Vec(NUM_QUEUES, UInt(1.W)))
  write_data_bit_vec.foreach(x => x := 0.U)
  for (i <- 0 until NUM_QUEUES) {
    val corresponding_buf_idx = (i.U +& write_start_idx) % NUM_QUEUES.U
    write_data_bit_vec(corresponding_buf_idx) := incoming_writes.io.deq.bits.data >> i.U
  }

  for (i <- 0 until NUM_QUEUES) {
    val use_this_queue = Mux(wrapped, 
                               (i.U < wrap_len_idx_end) || (i.U >= write_start_idx),
                               (i.U >= write_start_idx) && (i.U < wrap_len_idx_end)
                             )
    buffers(i).enq.valid := input_fire_all_queues.fire && use_this_queue
    buffers(i).enq.bits := write_data_bit_vec(i)
  }

  buf_lens_q.io.enq.valid := input_fire_all_queues.fire(account_for_buf_length) && end_of_buf
  buf_lens_q.io.enq.bits := buf_lens_tracker +& incoming_writes.io.deq.bits.validbits

  when (buf_lens_q.io.enq.fire) {
    CompressAccelLogger.logInfo("BITBUFF_BUFLEN_ENQ_FIRE, total_bits: %d\n", buf_lens_q.io.enq.bits)
  }

  when (input_fire_all_queues.fire) {
    write_start_idx := wrap_len_idx_end

    CompressAccelLogger.logInfo("BITBUF_WRITEFIRE\n")
    CompressAccelLogger.logInfo("write_start_idx: %d, written_bits: %d\n", write_start_idx, bits_to_write)
  }

  val read_start_idx = RegInit(0.U(log2Up(NUM_QUEUES + 1).W))

  val bufVecData = Wire(Vec(NUM_QUEUES, UInt(1.W)))
  val bufVecReadys = Wire(Vec(NUM_QUEUES, Bool()))
  val bufVecValids = Wire(Vec(NUM_QUEUES, Bool()))
  bufVecReadys.foreach(x => x := false.B)

  for (i <- 0 until NUM_QUEUES) {
    bufVecData(i) := buffers(i).deq.bits
    bufVecValids(i) := buffers(i).deq.valid
    buffers(i).deq.ready := bufVecReadys(i)
  }

  val remapVecData = Wire(Vec(NUM_QUEUES, UInt(1.W)))
  val remapVecReadys = Wire(Vec(NUM_QUEUES, Bool()))
  val remapVecValids = Wire(Vec(NUM_QUEUES, Bool()))

  for (i <- 0 until NUM_QUEUES) {
    val remap_idx = (i.U +& read_start_idx) % NUM_QUEUES.U
    remapVecData(i) := bufVecData(remap_idx)
    remapVecValids(i) := bufVecValids(remap_idx)
    bufVecReadys(remap_idx) := remapVecReadys(i)
  }

  val count_valid_bits = remapVecValids.map(_.asUInt).reduce(_ +& _)
  val count_valid_bytes = count_valid_bits >> 3.U
  val remain_valid_bits = count_valid_bits - (count_valid_bytes << 3.U)
  val byte_aligned = (remain_valid_bits === 0.U)

  val len_already_consumed = RegInit(0.U(64.W))
  val unconsumed_bits_so_far = buf_lens_q.io.deq.bits - len_already_consumed
  val last_chunk = buf_lens_q.io.deq.valid && (unconsumed_bits_so_far <= NUM_QUEUES.U)

  val avail_bytes = Mux((last_chunk && !byte_aligned),
                                count_valid_bytes + 1.U,
                                  count_valid_bytes)
  val enough_data = avail_bytes =/= 0.U

  val count_valid_bits_u8 = Wire(UInt(8.W))
  count_valid_bits_u8 := count_valid_bits
  val valid_bit_mask = (1.U << count_valid_bits_u8) - 1.U

  io.consumer.data := Cat(remapVecData.reverse) & valid_bit_mask
  io.consumer.avail_bytes := avail_bytes
  io.consumer.last_chunk := last_chunk

  val read_fire = DecoupledHelper(
    io.consumer.ready,
    enough_data)

  io.consumer.valid := read_fire.fire(io.consumer.ready)

  val consumed_bits_wide = io.consumer.consumed_bytes << 3.U
  val consumed_bits_overflow = consumed_bits_wide > count_valid_bits
  val consumed_bits = Mux(last_chunk && !byte_aligned && consumed_bits_overflow, 
                            count_valid_bits,
                            consumed_bits_wide)

  val buf_last = buf_lens_q.io.deq.valid && (buf_lens_q.io.deq.bits === (len_already_consumed + consumed_bits))
  val nxt_len_already_consumed = Mux(buf_last, 0.U, len_already_consumed + consumed_bits)
  when (read_fire.fire) {
    read_start_idx := (read_start_idx +& consumed_bits) % NUM_QUEUES.U

    when (buf_last) {
      len_already_consumed := 0.U
    } .otherwise {
      len_already_consumed := nxt_len_already_consumed
    }
  }

  for (i <- 0 until NUM_QUEUES) {
    remapVecReadys(i) := (i.U < consumed_bits) && read_fire.fire
  }

  buf_lens_q.io.deq.ready := read_fire.fire && buf_last // connecting ready & valid...?

  when (read_fire.fire) {
    CompressAccelLogger.logInfo("BITBUF_RDFIRE\n")
    CompressAccelLogger.logInfo("consumer.data: 0x%x\n", io.consumer.data)
    CompressAccelLogger.logInfo("consumer.last_chunk: %d\n", io.consumer.last_chunk)
    CompressAccelLogger.logInfo("consumer.avail_bytes: %d\n", io.consumer.avail_bytes)
    CompressAccelLogger.logInfo("consumer.consumed_bytes: %d\n", io.consumer.consumed_bytes)
    CompressAccelLogger.logInfo("consumed_bits_wide: %d\n", consumed_bits_wide)
    CompressAccelLogger.logInfo("consumed_bits_overflow: %d\n", consumed_bits_overflow)
    CompressAccelLogger.logInfo("consumed_bits: %d\n", consumed_bits)
    CompressAccelLogger.logInfo("buf_last: %d\n", buf_last)
    CompressAccelLogger.logInfo("read_start_idx: %d\n", read_start_idx)
    CompressAccelLogger.logInfo("count_valid_bits: %d\n", count_valid_bits)
    CompressAccelLogger.logInfo("count_valid_bytes: %d\n", count_valid_bytes)
    CompressAccelLogger.logInfo("remain_valid_bits: %d\n", remain_valid_bits)
    CompressAccelLogger.logInfo("byte_aligned: %d\n", byte_aligned)
    CompressAccelLogger.logInfo("len_already_consumed: %d\n", len_already_consumed)
    CompressAccelLogger.logInfo("nxt_len_already_consumed: %d\n", nxt_len_already_consumed)
    CompressAccelLogger.logInfo("unconsumed_bits_so_far: %d\n", unconsumed_bits_so_far)
    CompressAccelLogger.logInfo("last_chunk: %d\n", last_chunk)
    CompressAccelLogger.logInfo("valid_bit_mask: 0x%x\n", valid_bit_mask)

    for (i <- 0 until NUM_QUEUES) {
      CompressAccelLogger.logInfo("bufVecReadys(%d): %d\n", i.U, bufVecReadys(i))
    }
    for (i <- 0 until NUM_QUEUES) {
      CompressAccelLogger.logInfo("bufVecValids(%d): %d\n", i.U, bufVecValids(i))
    }
  }

  for (i <- 0 until NUM_QUEUES) {
    when (buffers(i).deq.fire) {
      CompressAccelLogger.logInfo("buffer(%d) fired\n", i.U)
    }
  }

  when (buf_lens_q.io.deq.fire) {
    CompressAccelLogger.logInfo("BITBUFF buf_lens_q dequeued\n")
  }
}
