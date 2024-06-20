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





class LZ77HashMatcherMemLoader()(implicit p: Parameters) extends Module
     with MemoryOpConstants {

  val io = IO(new Bundle {
    val l2helperUser = new L2MemHelperBundle

    val src_info = Flipped(Decoupled(new StreamInfo))

    val consumer = new MemLoaderConsumerBundle

    val optional_hbsram_write = Valid(new HBSRAMWrite)

  })

  val buf_info_queue = Module(new Queue(new BufInfoBundle, 16))

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


  val print_not_done = RegInit(true.B)

  when (io.src_info.valid && print_not_done) {
    CompressAccelLogger.logInfo("base_addr_bytes: %x\n", base_addr_bytes)
    CompressAccelLogger.logInfo("base_len: %x\n", base_len)
    CompressAccelLogger.logInfo("base_addr_start_index: %x\n", base_addr_start_index)
    CompressAccelLogger.logInfo("aligned_loadlen: %x\n", aligned_loadlen)
    CompressAccelLogger.logInfo("base_addr_end_index: %x\n", base_addr_end_index)
    CompressAccelLogger.logInfo("base_addr_end_index_inclusive: %x\n", base_addr_end_index_inclusive)
    CompressAccelLogger.logInfo("extra_word: %x\n", extra_word)
    CompressAccelLogger.logInfo("base_addr_bytes_aligned: %x\n", base_addr_bytes_aligned)
    CompressAccelLogger.logInfo("words_to_load: %x\n", words_to_load)
    CompressAccelLogger.logInfo("words_to_load_minus_one: %x\n", words_to_load_minus_one)
    print_not_done := false.B
  }

  when (io.src_info.fire) {
    print_not_done := true.B
    CompressAccelLogger.logInfo("COMPLETED INPUT LOAD FOR DECOMPRESSION\n")
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

  load_info_queue.io.enq.bits.start_byte := Mux(addrinc === 0.U, base_addr_start_index, 0.U)
  load_info_queue.io.enq.bits.end_byte := Mux(addrinc === words_to_load_minus_one, base_addr_end_index_inclusive, 31.U)


  when (request_fire.fire && (addrinc === words_to_load_minus_one)) {
    addrinc := 0.U
  } .elsewhen (request_fire.fire) {
    addrinc := addrinc + 1.U
  }

  io.src_info.ready := request_fire.fire(io.src_info.valid,
                                            addrinc === words_to_load_minus_one)

  buf_info_queue.io.enq.valid := request_fire.fire(buf_info_queue.io.enq.ready,
                                            addrinc === 0.U)
  load_info_queue.io.enq.valid := request_fire.fire(load_info_queue.io.enq.ready)

  buf_info_queue.io.enq.bits.len_bytes := base_len

  io.l2helperUser.req.bits.addr := (base_addr_bytes_aligned) + (addrinc << 5)
  io.l2helperUser.req.valid := request_fire.fire(io.l2helperUser.req.ready)





  val NUM_QUEUES = 32
  val QUEUE_DEPTHS_HALF = 16 * 2
  val write_start_index = RegInit(0.U(log2Up(NUM_QUEUES+1).W))
  // split to deliberately insert another cycle delay
  val mem_resp_queues = Seq.fill(NUM_QUEUES)(Module(new Queue(UInt(8.W), QUEUE_DEPTHS_HALF)).io)
  val mem_resp_queues_pt2 = Seq.fill(NUM_QUEUES)(Module(new Queue(UInt(8.W), QUEUE_DEPTHS_HALF)).io)


  for (queueno <- 0 until NUM_QUEUES) {
    mem_resp_queues_pt2(queueno).enq <> mem_resp_queues(queueno).deq
    mem_resp_queues_pt2(queueno).deq.ready := DontCare
  }

  val align_shamt = (load_info_queue.io.deq.bits.start_byte << 3)
  val memresp_bits_shifted = io.l2helperUser.resp.bits.data >> align_shamt

  for ( queueno <- 0 until NUM_QUEUES ) {
    mem_resp_queues(queueno).enq.bits := 0.U
    val idx = (write_start_index +& queueno.U) % NUM_QUEUES.U
    for (j <- 0 until NUM_QUEUES) {
      when (j.U === idx) {
        mem_resp_queues(j).enq.bits := memresp_bits_shifted >> (queueno * 8)
      }
    }
  }

  val len_to_write = (load_info_queue.io.deq.bits.end_byte - load_info_queue.io.deq.bits.start_byte) +& 1.U

  val wrap_len_index_wide = write_start_index +& len_to_write
  val wrap_len_index_end = wrap_len_index_wide % NUM_QUEUES.U
  val wrapped = wrap_len_index_wide >= NUM_QUEUES.U

  when (load_info_queue.io.deq.valid) {
    CompressAccelLogger.logInfo("memloader start %x, end %x\n", load_info_queue.io.deq.bits.start_byte,
      load_info_queue.io.deq.bits.end_byte)
  }

  val resp_fire_noqueues = DecoupledHelper(
    io.l2helperUser.resp.valid,
    load_info_queue.io.deq.valid
  )
  val all_queues_ready = mem_resp_queues.map(_.enq.ready).reduce(_ && _)

  load_info_queue.io.deq.ready := resp_fire_noqueues.fire(load_info_queue.io.deq.valid, all_queues_ready)
  io.l2helperUser.resp.ready := resp_fire_noqueues.fire(io.l2helperUser.resp.valid, all_queues_ready)

  io.optional_hbsram_write.valid := resp_fire_noqueues.fire && all_queues_ready
  io.optional_hbsram_write.bits.data := memresp_bits_shifted
  io.optional_hbsram_write.bits.valid_bytes := len_to_write

  val resp_fire_allqueues = resp_fire_noqueues.fire && all_queues_ready
  when (resp_fire_allqueues) {
    write_start_index := wrap_len_index_end
  }

  for ( queueno <- 0 until NUM_QUEUES ) {
    val use_this_queue = Mux(wrapped,
                             (queueno.U >= write_start_index) || (queueno.U < wrap_len_index_end),
                             (queueno.U >= write_start_index) && (queueno.U < wrap_len_index_end)
                            )
    mem_resp_queues(queueno).enq.valid := resp_fire_noqueues.fire && use_this_queue && all_queues_ready
  }

  for ( queueno <- 0 until NUM_QUEUES ) {
    when (mem_resp_queues_pt2(queueno).deq.valid) {
      CompressAccelLogger.logInfo("queueind %d, val %x\n", queueno.U, mem_resp_queues_pt2(queueno).deq.bits)
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

    val remapindex = (queueno.U +& read_start_index) % NUM_QUEUES.U
    for (j <- 0 until NUM_QUEUES) {
      when (j.U === remapindex) {
        remapVecData(queueno) := mem_resp_queues_pt2(j).deq.bits
        remapVecValids(queueno) := mem_resp_queues_pt2(j).deq.valid
        mem_resp_queues_pt2(j).deq.ready := remapVecReadys(queueno)
      }
    }
  }
  io.consumer.output_data := Cat(remapVecData.reverse)


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
    CompressAccelLogger.logInfo("MEMLOADER READ: bytesread %d\n", io.consumer.user_consumed_bytes)

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


