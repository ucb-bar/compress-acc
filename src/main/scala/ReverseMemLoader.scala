package compressacc

import chisel3._
import chisel3.util._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants

class ReverseMemLoader(val printInfo: String = "")(implicit p: Parameters) extends Module
     with MemoryOpConstants {

  val io = IO(new Bundle {
    val l2helperUser = new L2MemHelperBundle
    val src_info = Flipped(Decoupled(new StreamInfo))

    val consumer = new MemLoaderConsumerBundle
  })

  val buf_info_queue = Module(new Queue(new BufInfoBundle, 32))

  val load_info_queue = Module(new Queue(new LoadInfoBundle, 256))

  val base_addr_bytes = io.src_info.bits.ip
  val base_len = io.src_info.bits.isize
  val base_addr_start_index = io.src_info.bits.ip & 0x1F.U
  val aligned_loadlen =  base_len + base_addr_start_index
  val base_addr_end_index = (base_len + base_addr_start_index) & 0x1F.U
  val base_addr_end_index_inclusive = (base_len + base_addr_start_index - 1.U) & 0x1F.U
  val extra_word = ((aligned_loadlen & 0x1F.U) =/= 0.U).asUInt

  val base_addr_bytes_aligned = (base_addr_bytes >> 5.U) << 5.U
  val words_to_load = (aligned_loadlen >> 5.U) + extra_word
  val words_to_load_minus_one = words_to_load - 1.U

  val end_addr_bytes_aligned = ((base_addr_bytes + base_len - 1.U) >> 5.U) << 5.U

  val print_not_done = RegInit(true.B)

  when (io.src_info.valid && print_not_done) {
    CompressAccelLogger.logInfo(printInfo + " base_addr_bytes: %x\n", base_addr_bytes)
    CompressAccelLogger.logInfo(printInfo + " base_len: %x\n", base_len)
    CompressAccelLogger.logInfo(printInfo + " base_addr_start_index: %x\n", base_addr_start_index)
    CompressAccelLogger.logInfo(printInfo + " aligned_loadlen: %x\n", aligned_loadlen)
    CompressAccelLogger.logInfo(printInfo + " base_addr_end_index: %x\n", base_addr_end_index)
    CompressAccelLogger.logInfo(printInfo + " base_addr_end_index_inclusive: %x\n", base_addr_end_index_inclusive)
    CompressAccelLogger.logInfo(printInfo + " base_addr_bytes: %x\n", base_addr_bytes)
    CompressAccelLogger.logInfo(printInfo + " extra_word: %x\n", extra_word)
    CompressAccelLogger.logInfo(printInfo + " base_addr_bytes_aligned: %x\n", base_addr_bytes_aligned)
    CompressAccelLogger.logInfo(printInfo + " words_to_load: %x\n", words_to_load)
    CompressAccelLogger.logInfo(printInfo + " words_to_load_minus_one: %x\n", words_to_load_minus_one)
    CompressAccelLogger.logInfo(printInfo + " end_addr_bytes_aligned: %x\n", end_addr_bytes_aligned)
    when (io.src_info.ready) {
      print_not_done := true.B
    } .otherwise {
      print_not_done := false.B
    }
  }

  val request_fire = DecoupledHelper(
    io.l2helperUser.req.ready,
    io.src_info.valid,
    buf_info_queue.io.enq.ready,
    load_info_queue.io.enq.ready
  )

  io.l2helperUser.req.bits.cmd := M_XRD
  io.l2helperUser.req.bits.size := log2Ceil(32).U
  io.l2helperUser.req.bits.data := 0.U

  val addrinc = RegInit(0.U(64.W))

  load_info_queue.io.enq.bits.start_byte := Mux(addrinc === words_to_load_minus_one, base_addr_start_index, 0.U)
  load_info_queue.io.enq.bits.end_byte := Mux(addrinc === 0.U, base_addr_end_index_inclusive, 31.U)

  when (request_fire.fire && (addrinc === words_to_load_minus_one)) {
    addrinc := 0.U
  } .elsewhen (request_fire.fire) {
    addrinc := addrinc + 1.U
  }

  when (io.src_info.fire) {
    CompressAccelLogger.logInfo(printInfo + " COMPLETED_LITERAL_LOAD_FOR_DECOMPRESSION\n")
  }

  io.src_info.ready := request_fire.fire(io.src_info.valid,
                                            addrinc === words_to_load_minus_one)

  buf_info_queue.io.enq.valid := request_fire.fire(buf_info_queue.io.enq.ready,
                                            addrinc === 0.U)
  load_info_queue.io.enq.valid := request_fire.fire(load_info_queue.io.enq.ready)

  buf_info_queue.io.enq.bits.len_bytes := base_len

  io.l2helperUser.req.bits.addr := (end_addr_bytes_aligned) - (addrinc << 5)
  io.l2helperUser.req.valid := request_fire.fire(io.l2helperUser.req.ready)

  val NUM_QUEUES = 32
  val QUEUE_DEPTHS = 16 * 4
  val write_start_index = RegInit(0.U(log2Up(NUM_QUEUES+1).W))
  val mem_resp_queues = Seq.fill(NUM_QUEUES)(Module(new Queue(UInt(8.W), QUEUE_DEPTHS)).io)
  val align_shamt = ((31.U - load_info_queue.io.deq.bits.end_byte) << 3)
  val memresp_bits_shifted = io.l2helperUser.resp.bits.data << align_shamt

  val MAX_QUEUE_IDX = NUM_QUEUES.U - 1.U

  for ( queueno <- 0 until NUM_QUEUES ) {
    val idx = (NUM_QUEUES.U +& MAX_QUEUE_IDX -& write_start_index -& queueno.U) % NUM_QUEUES.U
    mem_resp_queues(queueno).enq.bits := 0.U
    for (j <- 0 until NUM_QUEUES) {
      when (j.U === idx) {
        mem_resp_queues(j).enq.bits := memresp_bits_shifted >> ((NUM_QUEUES-queueno-1) * 8)
      }
    }
  }

  val len_to_write = (load_info_queue.io.deq.bits.end_byte - load_info_queue.io.deq.bits.start_byte) +& 1.U

  val wrap_len_index_wide = NUM_QUEUES.U +& MAX_QUEUE_IDX -& write_start_index -& len_to_write
  val wrap_len_index_end = wrap_len_index_wide % NUM_QUEUES.U
  val wrapped = wrap_len_index_wide < MAX_QUEUE_IDX

  when (load_info_queue.io.deq.valid) {
    CompressAccelLogger.logInfo(printInfo + " start %x, end %x\n", load_info_queue.io.deq.bits.start_byte,
      load_info_queue.io.deq.bits.end_byte)
  }

  val resp_fire_noqueues = DecoupledHelper(
    io.l2helperUser.resp.valid,
    load_info_queue.io.deq.valid
  )
  val all_queues_ready = mem_resp_queues.map(_.enq.ready).reduce(_ && _)

  load_info_queue.io.deq.ready := resp_fire_noqueues.fire(load_info_queue.io.deq.valid, all_queues_ready)
  io.l2helperUser.resp.ready := resp_fire_noqueues.fire(io.l2helperUser.resp.valid, all_queues_ready)

  val resp_fire_allqueues = resp_fire_noqueues.fire && all_queues_ready
  when (resp_fire_allqueues) {
    write_start_index := MAX_QUEUE_IDX - wrap_len_index_end
  }

  for ( queueno <- 0 until NUM_QUEUES ) {
    val use_this_queue = Mux(wrapped,
                             (queueno.U <= MAX_QUEUE_IDX-write_start_index) || (queueno.U > wrap_len_index_end),
                             (queueno.U <= MAX_QUEUE_IDX-write_start_index) && (queueno.U >= NUM_QUEUES.U-write_start_index-len_to_write)
                            )
 
    val cur_queue_valid = resp_fire_noqueues.fire && use_this_queue && all_queues_ready
    mem_resp_queues(queueno).enq.valid := cur_queue_valid
  }

  for ( queueno <- 0 until NUM_QUEUES ) {
    when (mem_resp_queues(queueno).enq.valid) {
    }
  }

  val read_start_index = RegInit(0.U(log2Up(NUM_QUEUES+1).W))
  val len_already_consumed = RegInit(0.U(64.W))

  val remapVecData = Wire(Vec(NUM_QUEUES, UInt(8.W)))
  val remapVecValids = Wire(Vec(NUM_QUEUES, Bool()))
  val remapVecReadys = Wire(Vec(NUM_QUEUES, Bool()))

  for (queueno <- 0 until NUM_QUEUES) {
    remapVecData(queueno) := 0.U
    remapVecValids(queueno) := false.B
    mem_resp_queues(queueno).deq.ready := false.B
  }

  for (queueno <- 0 until NUM_QUEUES) {
    val remapindex = (NUM_QUEUES.U +& MAX_QUEUE_IDX -& queueno.U -& read_start_index) % NUM_QUEUES.U
    for (j <- 0 until NUM_QUEUES) {
      when (j.U === remapindex) {
        remapVecData(queueno) := mem_resp_queues(j).deq.bits
        remapVecValids(queueno) := mem_resp_queues(j).deq.valid
        mem_resp_queues(j).deq.ready := remapVecReadys(queueno)
      }
    }
  }
  io.consumer.output_data := Cat(remapVecData)

  val buf_last = (len_already_consumed + io.consumer.user_consumed_bytes) === buf_info_queue.io.deq.bits.len_bytes
  val count_valids = remapVecValids.map(_.asUInt).reduce(_ +& _)
  val unconsumed_bytes_so_far = buf_info_queue.io.deq.bits.len_bytes - len_already_consumed
  val enough_data = Mux(unconsumed_bytes_so_far >= NUM_QUEUES.U,
                        count_valids === NUM_QUEUES.U,
                        count_valids >= unconsumed_bytes_so_far)

  io.consumer.available_output_bytes := Mux(unconsumed_bytes_so_far >= NUM_QUEUES.U,
                                    NUM_QUEUES.U,
                                    unconsumed_bytes_so_far)

  io.consumer.output_last_chunk := (unconsumed_bytes_so_far <= NUM_QUEUES.U)

  val read_fire = DecoupledHelper(
    io.consumer.output_ready,
    buf_info_queue.io.deq.valid,
    enough_data
  )

  when (read_fire.fire) {
    CompressAccelLogger.logInfo(printInfo + " read: bytesread %d\n", 
      io.consumer.user_consumed_bytes)
  }

  io.consumer.output_valid := read_fire.fire(io.consumer.output_ready)

  for (queueno <- 0 until NUM_QUEUES) {
    remapVecReadys(queueno) := (queueno.U < io.consumer.user_consumed_bytes) && read_fire.fire
  }

  when (read_fire.fire) {
    read_start_index := (read_start_index +& io.consumer.user_consumed_bytes) % NUM_QUEUES.U
  }

  buf_info_queue.io.deq.ready := read_fire.fire(buf_info_queue.io.deq.valid) && buf_last

  when (read_fire.fire) {
    when (buf_last) {
      len_already_consumed := 0.U
    } .otherwise {
      len_already_consumed := len_already_consumed + io.consumer.user_consumed_bytes
    }
  }
}
