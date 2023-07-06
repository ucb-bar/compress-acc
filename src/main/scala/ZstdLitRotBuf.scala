package compressacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants

class ZstdCompressorReverseLitRotBuf(implicit val p: Parameters) extends Module {
  val io = IO(new Bundle {
    val memwrites_in = Decoupled(new WriterBundle).flip
    val consumer = new MemLoaderConsumerBundle
  })

  /*
   * memwrites_in.bits.data
   * 0 1 2 3 4 5 6 7
   * o o o o o o x x
   * 5 4 3 2 1 0 x x
   *
   * consumer
   * 0 1 2 3 4 5 6 7
   * x x 0 1 2 3 4 5
   */

  val incoming_writes_Q = Module(new Queue(new WriterBundle, 4))

  incoming_writes_Q.io.enq <> io.memwrites_in

  when (incoming_writes_Q.io.deq.fire) {
    CompressAccelLogger.logInfo("[lit-rot-buf] dat: 0x%x, bytes: 0x%x, EOM: %d\n",
      incoming_writes_Q.io.deq.bits.data,
      incoming_writes_Q.io.deq.bits.validbytes,
      incoming_writes_Q.io.deq.bits.end_of_message
      )
  }

  val NUM_QUEUES = 32
  val QUEUE_DEPTHS = 10
  val write_start_index = RegInit(UInt(0, log2Up(NUM_QUEUES+1).W))
  val mem_resp_queues = Vec.fill(NUM_QUEUES)(Module(new Queue(UInt(8.W), QUEUE_DEPTHS)).io)

  val len_to_write = incoming_writes_Q.io.deq.bits.validbytes

  for ( queueno <- 0 until NUM_QUEUES ) {
    mem_resp_queues((write_start_index +& UInt(queueno)) % UInt(NUM_QUEUES)).enq.bits := incoming_writes_Q.io.deq.bits.data >> ((queueno.U) << 3)
  }

  val wrap_len_index_wide = write_start_index +& len_to_write
  val wrap_len_index_end = wrap_len_index_wide % UInt(NUM_QUEUES)
  val wrapped = wrap_len_index_wide >= UInt(NUM_QUEUES)

  val all_queues_ready = mem_resp_queues.map(_.enq.ready).reduce(_ && _)

  val input_fire_allqueues = DecoupledHelper(
    incoming_writes_Q.io.deq.valid,
    all_queues_ready,
  )

  incoming_writes_Q.io.deq.ready := input_fire_allqueues.fire(incoming_writes_Q.io.deq.valid)

  when (input_fire_allqueues.fire) {
    write_start_index := wrap_len_index_end
  }


  for ( queueno <- 0 until NUM_QUEUES ) {
    val use_this_queue = Mux(wrapped,
                             (UInt(queueno) >= write_start_index) || (UInt(queueno) < wrap_len_index_end),
                             (UInt(queueno) >= write_start_index) && (UInt(queueno) < wrap_len_index_end)
                            )
    mem_resp_queues(queueno).enq.valid := input_fire_allqueues.fire && use_this_queue
  }

  for ( queueno <- 0 until NUM_QUEUES ) {
    when (mem_resp_queues(queueno).deq.valid) {
      CompressAccelLogger.logInfo("lrb: qi%d,0x%x\n", UInt(queueno), mem_resp_queues(queueno).deq.bits)
    }
  }

  val read_start_index = RegInit(UInt(0, log2Up(NUM_QUEUES+1).W))


  val remapVecData = Wire(Vec(NUM_QUEUES, UInt(8.W)))
  val remapVecValids = Wire(Vec(NUM_QUEUES, Bool()))
  val remapVecReadys = Wire(Vec(NUM_QUEUES, Bool()))


  for (queueno <- 0 until NUM_QUEUES) {
    val remapindex = (UInt(queueno) +& read_start_index) % UInt(NUM_QUEUES)
    remapVecData(queueno) := mem_resp_queues(remapindex).deq.bits
    remapVecValids(queueno) := mem_resp_queues(remapindex).deq.valid
    mem_resp_queues(remapindex).deq.ready := remapVecReadys(queueno)
  }
// io.consumer.output_data := Cat(remapVecData.reverse)
  io.consumer.output_data := Cat(remapVecData)


  val count_valids = remapVecValids.map(_.asUInt).reduce(_ +& _)

  val enough_data = count_valids =/= 0.U

  io.consumer.available_output_bytes := count_valids

  io.consumer.output_last_chunk := false.B // in this module, we don't actualy care about driving this.

  val read_fire = DecoupledHelper(
    io.consumer.output_ready,
    enough_data
  )

  when (read_fire.fire) {
    CompressAccelLogger.logInfo("lrb READ: bytesread %d\n", io.consumer.user_consumed_bytes)
    CompressAccelLogger.logInfo("lrb read data: 0x%x\n", io.consumer.output_data)
  }

  io.consumer.output_valid := read_fire.fire(io.consumer.output_ready)

  for (queueno <- 0 until NUM_QUEUES) {
    remapVecReadys(queueno) := (UInt(queueno) < io.consumer.user_consumed_bytes) && read_fire.fire
  }

  when (read_fire.fire) {
    read_start_index := (read_start_index +& io.consumer.user_consumed_bytes) % UInt(NUM_QUEUES)
  }
}

class ZstdCompressorLitRotBuf(implicit val p: Parameters) extends Module {
  val io = IO(new Bundle {
    val memwrites_in = Decoupled(new WriterBundle).flip
    val consumer = new MemLoaderConsumerBundle
  })

  val incoming_writes_Q = Module(new Queue(new WriterBundle, 4))

  incoming_writes_Q.io.enq <> io.memwrites_in

  when (incoming_writes_Q.io.deq.fire) {
    CompressAccelLogger.logInfo("[lit-rot-buf] dat: 0x%x, bytes: 0x%x, EOM: %d\n",
      incoming_writes_Q.io.deq.bits.data,
      incoming_writes_Q.io.deq.bits.validbytes,
      incoming_writes_Q.io.deq.bits.end_of_message
      )
  }

  val NUM_QUEUES = 32
  val QUEUE_DEPTHS = 10
  val write_start_index = RegInit(UInt(0, log2Up(NUM_QUEUES+1).W))
  val mem_resp_queues = Vec.fill(NUM_QUEUES)(Module(new Queue(UInt(8.W), QUEUE_DEPTHS)).io)

  val len_to_write = incoming_writes_Q.io.deq.bits.validbytes

  for ( queueno <- 0 until NUM_QUEUES ) {
    mem_resp_queues((write_start_index +& UInt(queueno)) % UInt(NUM_QUEUES)).enq.bits := incoming_writes_Q.io.deq.bits.data >> ((queueno.U) << 3)
  }

  val wrap_len_index_wide = write_start_index +& len_to_write
  val wrap_len_index_end = wrap_len_index_wide % UInt(NUM_QUEUES)
  val wrapped = wrap_len_index_wide >= UInt(NUM_QUEUES)

  val all_queues_ready = mem_resp_queues.map(_.enq.ready).reduce(_ && _)

  val input_fire_allqueues = DecoupledHelper(
    incoming_writes_Q.io.deq.valid,
    all_queues_ready,
  )

  incoming_writes_Q.io.deq.ready := input_fire_allqueues.fire(incoming_writes_Q.io.deq.valid)

  when (input_fire_allqueues.fire) {
    write_start_index := wrap_len_index_end
  }


  for ( queueno <- 0 until NUM_QUEUES ) {
    val use_this_queue = Mux(wrapped,
                             (UInt(queueno) >= write_start_index) || (UInt(queueno) < wrap_len_index_end),
                             (UInt(queueno) >= write_start_index) && (UInt(queueno) < wrap_len_index_end)
                            )
    mem_resp_queues(queueno).enq.valid := input_fire_allqueues.fire && use_this_queue
  }

  for ( queueno <- 0 until NUM_QUEUES ) {
    when (mem_resp_queues(queueno).deq.valid) {
      CompressAccelLogger.logInfo("lrb: qi%d,0x%x\n", UInt(queueno), mem_resp_queues(queueno).deq.bits)
    }
  }

  val read_start_index = RegInit(UInt(0, log2Up(NUM_QUEUES+1).W))


  val remapVecData = Wire(Vec(NUM_QUEUES, UInt(8.W)))
  val remapVecValids = Wire(Vec(NUM_QUEUES, Bool()))
  val remapVecReadys = Wire(Vec(NUM_QUEUES, Bool()))


  for (queueno <- 0 until NUM_QUEUES) {
    val remapindex = (UInt(queueno) +& read_start_index) % UInt(NUM_QUEUES)
    remapVecData(queueno) := mem_resp_queues(remapindex).deq.bits
    remapVecValids(queueno) := mem_resp_queues(remapindex).deq.valid
    mem_resp_queues(remapindex).deq.ready := remapVecReadys(queueno)
  }
  io.consumer.output_data := Cat(remapVecData.reverse)


  val count_valids = remapVecValids.map(_.asUInt).reduce(_ +& _)

  val enough_data = count_valids =/= 0.U

  io.consumer.available_output_bytes := count_valids

  io.consumer.output_last_chunk := false.B // in this module, we don't actualy care about driving this.

  val read_fire = DecoupledHelper(
    io.consumer.output_ready,
    enough_data
  )

  when (read_fire.fire) {
    CompressAccelLogger.logInfo("lrb READ: bytesread %d\n", io.consumer.user_consumed_bytes)
    CompressAccelLogger.logInfo("lrb read data: 0x%x\n", io.consumer.output_data)
  }

  io.consumer.output_valid := read_fire.fire(io.consumer.output_ready)

  for (queueno <- 0 until NUM_QUEUES) {
    remapVecReadys(queueno) := (UInt(queueno) < io.consumer.user_consumed_bytes) && read_fire.fire
  }

  when (read_fire.fire) {
    read_start_index := (read_start_index +& io.consumer.user_consumed_bytes) % UInt(NUM_QUEUES)
  }
}
