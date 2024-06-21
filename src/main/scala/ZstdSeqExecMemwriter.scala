package compressacc

import chisel3._
import chisel3.util._
import chisel3.{Printable, VecInit}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._

class WriterBundleFSE extends Bundle {
  val data = UInt(128.W)
  val validbytes = UInt(6.W)
  val end_of_message = Bool()
}

class ZstdSeqExecMemwriter32(val cmd_que_depth: Int = 4, val write_cmp_flag:Boolean = true)
  (implicit p: Parameters) extends Module with MemoryOpConstants {

  val io = IO(new Bundle {
    val memwrites_in = Flipped(Decoupled(new WriterBundle))
    val l2io = new L2MemHelperBundle
    val decompress_dest_info = Flipped(Decoupled(new SnappyDecompressDestInfo))

    val bufs_completed = Output(UInt(64.W))
    val no_writes_inflight = Output(Bool())
  })

  val incoming_writes_Q = Module(new Queue(new WriterBundle, cmd_que_depth))

  incoming_writes_Q.io.enq <> io.memwrites_in

  val decompress_dest_info_Q = Module(new Queue(new SnappyDecompressDestInfo, cmd_que_depth))
  decompress_dest_info_Q.io.enq <> io.decompress_dest_info

  val decompress_dest_last_fire = RegNext(decompress_dest_info_Q.io.deq.fire)
  val decompress_dest_last_valid = RegNext(decompress_dest_info_Q.io.deq.valid)
  val decompress_dest_printhelp = decompress_dest_info_Q.io.deq.valid && (decompress_dest_last_fire || (!decompress_dest_last_valid))

  when (decompress_dest_printhelp) {
    CompressAccelLogger.logInfo("[config-memwriter] got dest info op: 0x%x, boolptr 0x%x\n",
      decompress_dest_info_Q.io.deq.bits.op,
      decompress_dest_info_Q.io.deq.bits.boolptr)
  }

  val buf_lens_Q = Module(new Queue(UInt(64.W), 10))
  when (buf_lens_Q.io.enq.fire) {
    CompressAccelLogger.logInfo("[memwriter-serializer] enqueued buf len: %d\n", buf_lens_Q.io.enq.bits)
  }

  val buf_len_tracker = RegInit(0.U(64.W))
  when (incoming_writes_Q.io.deq.fire) {
    when (incoming_writes_Q.io.deq.bits.end_of_message) {
      buf_len_tracker := 0.U
    } .otherwise {
      buf_len_tracker := buf_len_tracker +& incoming_writes_Q.io.deq.bits.validbytes
    }
  }

  when (incoming_writes_Q.io.deq.fire) {
    CompressAccelLogger.logInfo("[memwriter-serializer] dat: 0x%x, bytes: 0x%x, EOM: %d\n",
      incoming_writes_Q.io.deq.bits.data,
      incoming_writes_Q.io.deq.bits.validbytes,
      incoming_writes_Q.io.deq.bits.end_of_message
      )
  }

  val NUM_QUEUES = 32
  val QUEUE_DEPTHS = 16
  val write_start_index = RegInit(0.U(log2Up(NUM_QUEUES+1).W))
  val mem_resp_queues = Seq.fill(NUM_QUEUES)(Module(new Queue(UInt(8.W), QUEUE_DEPTHS)).io)

  val len_to_write = incoming_writes_Q.io.deq.bits.validbytes

  for ( queueno <- 0 until NUM_QUEUES ) {
    mem_resp_queues(queueno).enq.bits := 0.U
  }

  for ( queueno <- 0 until NUM_QUEUES ) {
    val idx = (write_start_index +& queueno.U) % NUM_QUEUES.U
    for (j <- 0 until NUM_QUEUES) {
      when (j.U === idx) {
        mem_resp_queues(j).enq.bits := incoming_writes_Q.io.deq.bits.data >> ((len_to_write - (queueno+1).U) << 3)
      }
    }
  }


  val wrap_len_index_wide = write_start_index +& len_to_write
  val wrap_len_index_end = wrap_len_index_wide % NUM_QUEUES.U
  val wrapped = wrap_len_index_wide >= NUM_QUEUES.U

  val all_queues_ready = mem_resp_queues.map(_.enq.ready).reduce(_ && _)


  val end_of_buf = incoming_writes_Q.io.deq.bits.end_of_message
  val account_for_buf_lens_Q = (!end_of_buf) || (end_of_buf && buf_lens_Q.io.enq.ready)

  val input_fire_allqueues = DecoupledHelper(
    incoming_writes_Q.io.deq.valid,
    all_queues_ready,
    account_for_buf_lens_Q
  )

  buf_lens_Q.io.enq.valid := input_fire_allqueues.fire(account_for_buf_lens_Q) && end_of_buf
  buf_lens_Q.io.enq.bits := buf_len_tracker +& incoming_writes_Q.io.deq.bits.validbytes

  incoming_writes_Q.io.deq.ready := input_fire_allqueues.fire(incoming_writes_Q.io.deq.valid)


  when (input_fire_allqueues.fire) {
    write_start_index := wrap_len_index_end
  }


  for ( queueno <- 0 until NUM_QUEUES ) {
    val use_this_queue = Mux(wrapped,
                             (queueno.U >= write_start_index) || (queueno.U < wrap_len_index_end),
                             (queueno.U >= write_start_index) && (queueno.U < wrap_len_index_end)
                            )
    mem_resp_queues(queueno).enq.valid := input_fire_allqueues.fire && use_this_queue
  }

  for ( queueno <- 0 until NUM_QUEUES ) {
    when (mem_resp_queues(queueno).deq.valid) {
      CompressAccelLogger.logInfo("qi%d,0x%x\n", queueno.U, mem_resp_queues(queueno).deq.bits)
    }
  }




  val read_start_index = RegInit(0.U(log2Up(NUM_QUEUES+1).W))

  val remapVecData = Wire(Vec(NUM_QUEUES, UInt(8.W)))
  val remapVecValids = Wire(Vec(NUM_QUEUES, Bool()))
  val remapVecReadys = Wire(Vec(NUM_QUEUES, Bool()))


  for (queueno <- 0 until NUM_QUEUES) {
    remapVecData(queueno) := 0.U
    remapVecValids(queueno) := false.B
    mem_resp_queues(queueno).deq.ready := false.B
  }

  for (queueno <- 0 until NUM_QUEUES) {
    val remapindex = (queueno.U +& read_start_index) % NUM_QUEUES.U
    for (j <- 0 until NUM_QUEUES) {
      when (j.U === remapindex) {
        remapVecData(queueno) := mem_resp_queues(j).deq.bits
        remapVecValids(queueno) := mem_resp_queues(j).deq.valid
        mem_resp_queues(j).deq.ready := remapVecReadys(queueno)
      }
    }
  }

  val count_valids = remapVecValids.map(_.asUInt).reduce(_ +& _)



  val backend_bytes_written = RegInit(0.U(64.W))
  val backend_next_write_addr = decompress_dest_info_Q.io.deq.bits.op + backend_bytes_written

  val throttle_end = Mux(buf_lens_Q.io.deq.valid,
    buf_lens_Q.io.deq.bits - backend_bytes_written,
    32.U)

  val throttle_end_writeable = Mux(throttle_end >= 32.U, 32.U,
                                    Mux(throttle_end(4), 16.U,
                                      Mux(throttle_end(3), 8.U,
                                        Mux(throttle_end(2), 4.U,
                                          Mux(throttle_end(1), 2.U,
                                            Mux(throttle_end(0), 1.U,
                                                                  0.U))))))

  val throttle_end_writeable_log2 = Mux(throttle_end >= 32.U, 5.U,
                                      Mux(throttle_end(4), 4.U,
                                        Mux(throttle_end(3), 3.U,
                                          Mux(throttle_end(2), 2.U,
                                            Mux(throttle_end(1), 1.U,
                                              Mux(throttle_end(0), 0.U,
                                                                    0.U))))))


  val ptr_align_max_bytes_writeable = Mux(backend_next_write_addr(0), 1.U,
                                        Mux(backend_next_write_addr(1), 2.U,
                                          Mux(backend_next_write_addr(2), 4.U,
                                            Mux(backend_next_write_addr(3), 8.U,
                                              Mux(backend_next_write_addr(4), 16.U,
                                                                                 32.U)))))

  val ptr_align_max_bytes_writeable_log2 = Mux(backend_next_write_addr(0), 0.U,
                                            Mux(backend_next_write_addr(1), 1.U,
                                              Mux(backend_next_write_addr(2), 2.U,
                                                Mux(backend_next_write_addr(3), 3.U,
                                                  Mux(backend_next_write_addr(4), 4.U,
                                                                                     5.U)))))

  val count_valids_largest_aligned = Mux(count_valids(5), 32.U,
                                      Mux(count_valids(4), 16.U,
                                        Mux(count_valids(3), 8.U,
                                          Mux(count_valids(2), 4.U,
                                            Mux(count_valids(1), 2.U,
                                              Mux(count_valids(0), 1.U,
                                                                    0.U))))))

  val count_valids_largest_aligned_log2 = Mux(count_valids(5), 5.U,
                                            Mux(count_valids(4), 4.U,
                                              Mux(count_valids(3), 3.U,
                                                Mux(count_valids(2), 2.U,
                                                  Mux(count_valids(1), 1.U,
                                                    Mux(count_valids(0), 0.U,
                                                                           0.U))))))



  val bytes_to_write = Mux(
    ptr_align_max_bytes_writeable < count_valids_largest_aligned,
    Mux(ptr_align_max_bytes_writeable < throttle_end_writeable,
      ptr_align_max_bytes_writeable,
      throttle_end_writeable),
    Mux(count_valids_largest_aligned < throttle_end_writeable,
      count_valids_largest_aligned,
      throttle_end_writeable)
  )
  val remapped_write_data = Cat(remapVecData.reverse) // >> ((NUM_QUEUES.U - bytes_to_write) << 3)

  val enough_data = bytes_to_write =/= 0.U

  val bytes_to_write_log2 = Mux(
    ptr_align_max_bytes_writeable_log2 < count_valids_largest_aligned_log2,
    Mux(ptr_align_max_bytes_writeable_log2 < throttle_end_writeable_log2,
      ptr_align_max_bytes_writeable_log2,
      throttle_end_writeable_log2),
    Mux(count_valids_largest_aligned_log2 < throttle_end_writeable_log2,
      count_valids_largest_aligned_log2,
      throttle_end_writeable_log2)
  )

  val write_ptr_override = buf_lens_Q.io.deq.valid && (buf_lens_Q.io.deq.bits === backend_bytes_written)

  val mem_write_fire = DecoupledHelper(
    io.l2io.req.ready,
    enough_data,
    !write_ptr_override,
    decompress_dest_info_Q.io.deq.valid
  )

  val bool_ptr_write_fire = DecoupledHelper(
    io.l2io.req.ready,
    buf_lens_Q.io.deq.valid,
    buf_lens_Q.io.deq.bits === backend_bytes_written,
    decompress_dest_info_Q.io.deq.valid
  )

  for (queueno <- 0 until NUM_QUEUES) {
    remapVecReadys(queueno) := (queueno.U < bytes_to_write) && mem_write_fire.fire
  }

  when (mem_write_fire.fire) {
    read_start_index := (read_start_index +& bytes_to_write) % NUM_QUEUES.U
    backend_bytes_written := backend_bytes_written + bytes_to_write
    CompressAccelLogger.logInfo("[memwriter-serializer] writefire: addr: 0x%x, data 0x%x, size %d\n",
      io.l2io.req.bits.addr,
      io.l2io.req.bits.data,
      io.l2io.req.bits.size
    )
  }

  val bool_val = 1.U
  if (write_cmp_flag) {
    io.l2io.req.valid := mem_write_fire.fire(io.l2io.req.ready) || bool_ptr_write_fire.fire(io.l2io.req.ready)
  } else {
    io.l2io.req.valid := mem_write_fire.fire(io.l2io.req.ready)
  }
  io.l2io.req.bits.size := Mux(write_ptr_override, 0.U, bytes_to_write_log2)
  io.l2io.req.bits.addr := Mux(write_ptr_override, decompress_dest_info_Q.io.deq.bits.boolptr, backend_next_write_addr)
  io.l2io.req.bits.data := Mux(write_ptr_override, bool_val, remapped_write_data)
  io.l2io.req.bits.cmd := M_XWR

  buf_lens_Q.io.deq.ready := bool_ptr_write_fire.fire(buf_lens_Q.io.deq.valid)
  decompress_dest_info_Q.io.deq.ready := bool_ptr_write_fire.fire(decompress_dest_info_Q.io.deq.valid)

  val bufs_completed = RegInit(0.U(64.W))
  io.bufs_completed := bufs_completed

  io.l2io.resp.ready := true.B

  io.no_writes_inflight := io.l2io.no_memops_inflight

  when (bool_ptr_write_fire.fire) {
    bufs_completed := bufs_completed + 1.U
    backend_bytes_written := 0.U
    CompressAccelLogger.logInfo("[memwriter-serializer] write boolptr addr: 0x%x, write ptr val 0x%x\n", decompress_dest_info_Q.io.deq.bits.boolptr, bool_val)
  }

  when (count_valids =/= 0.U) {
    CompressAccelLogger.logInfo("[memwriter-serializer] write_start_index %d, backend_bytes_written %d, count_valids %d, ptr_align_max_bytes_writeable %d, bytes_to_write %d, bytes_to_write_log2 %d\n",
      read_start_index,
      backend_bytes_written,
      count_valids,
      ptr_align_max_bytes_writeable,
      bytes_to_write,
      bytes_to_write_log2
    )
  }
}


class ZstdSeqExecMemwriter()(implicit p: Parameters) extends Module
  with MemoryOpConstants {

  val io = IO(new Bundle {
    val memwrites_in = Flipped(Decoupled(new WriterBundleFSE))
    val l2io = new L2MemHelperBundle
    val decompress_dest_info = Flipped(Decoupled(new SnappyDecompressDestInfo))

    val bufs_completed = Output(UInt(64.W))
    val no_writes_inflight = Output(Bool())
  })

  val incoming_writes_Q = Module(new Queue(new WriterBundleFSE, 4))

  incoming_writes_Q.io.enq <> io.memwrites_in

  val decompress_dest_info_Q = Module(new Queue(new SnappyDecompressDestInfo, 4))
  decompress_dest_info_Q.io.enq <> io.decompress_dest_info

  val decompress_dest_last_fire = RegNext(decompress_dest_info_Q.io.deq.fire)
  val decompress_dest_last_valid = RegNext(decompress_dest_info_Q.io.deq.valid)
  val decompress_dest_printhelp = decompress_dest_info_Q.io.deq.valid && (decompress_dest_last_fire || (!decompress_dest_last_valid))

  when (decompress_dest_printhelp) {
    CompressAccelLogger.logInfo("[config-memwriter] got dest info op: 0x%x, boolptr 0x%x\n",
      decompress_dest_info_Q.io.deq.bits.op,
      decompress_dest_info_Q.io.deq.bits.boolptr)
  }

  val buf_lens_Q = Module(new Queue(UInt(64.W), 10))
  when (buf_lens_Q.io.enq.fire) {
    CompressAccelLogger.logInfo("[memwriter-serializer] enqueued buf len: %d\n", buf_lens_Q.io.enq.bits)
  }

  val buf_len_tracker = RegInit(0.U(64.W))
  when (incoming_writes_Q.io.deq.fire) {
    when (incoming_writes_Q.io.deq.bits.end_of_message) {
      buf_len_tracker := 0.U
    } .otherwise {
      buf_len_tracker := buf_len_tracker +& incoming_writes_Q.io.deq.bits.validbytes
    }
  }

  when (incoming_writes_Q.io.deq.fire) {
    CompressAccelLogger.logInfo("[memwriter-serializer] dat: 0x%x, bytes: 0x%x, EOM: %d\n",
      incoming_writes_Q.io.deq.bits.data,
      incoming_writes_Q.io.deq.bits.validbytes,
      incoming_writes_Q.io.deq.bits.end_of_message
      )
  }

  val NUM_QUEUES = 16
  val QUEUE_DEPTHS = 16
  val write_start_index = RegInit(0.U(log2Up(NUM_QUEUES+1).W))
  val mem_resp_queues = Seq.fill(NUM_QUEUES)(Module(new Queue(UInt(8.W), QUEUE_DEPTHS)).io)

  val len_to_write = incoming_writes_Q.io.deq.bits.validbytes

  for ( queueno <- 0 until NUM_QUEUES ) {
    val idx = (write_start_index +& queueno.U) % NUM_QUEUES.U
    mem_resp_queues(queueno).enq.bits := 0.U
    for (j <- 0 until NUM_QUEUES) {
      when (j.U === idx) {
        mem_resp_queues(j).enq.bits := incoming_writes_Q.io.deq.bits.data >> ((len_to_write - (queueno+1).U) << 3)
      }
    }
  }

  val wrap_len_index_wide = write_start_index +& len_to_write
  val wrap_len_index_end = wrap_len_index_wide % NUM_QUEUES.U
  val wrapped = wrap_len_index_wide >= NUM_QUEUES.U

  val all_queues_ready = mem_resp_queues.map(_.enq.ready).reduce(_ && _)


  val end_of_buf = incoming_writes_Q.io.deq.bits.end_of_message
  val account_for_buf_lens_Q = (!end_of_buf) || (end_of_buf && buf_lens_Q.io.enq.ready)

  val input_fire_allqueues = DecoupledHelper(
    incoming_writes_Q.io.deq.valid,
    all_queues_ready,
    account_for_buf_lens_Q
  )

  buf_lens_Q.io.enq.valid := input_fire_allqueues.fire(account_for_buf_lens_Q) && end_of_buf
  buf_lens_Q.io.enq.bits := buf_len_tracker +& incoming_writes_Q.io.deq.bits.validbytes

  incoming_writes_Q.io.deq.ready := input_fire_allqueues.fire(incoming_writes_Q.io.deq.valid)

  when (input_fire_allqueues.fire) {//erased && !end_of_buf as from the 2nd stream there was a write error
    write_start_index := wrap_len_index_end
  }


  for ( queueno <- 0 until NUM_QUEUES ) {
    val use_this_queue = Mux(wrapped,
                             (queueno.U >= write_start_index) || (queueno.U < wrap_len_index_end),
                             (queueno.U >= write_start_index) && (queueno.U < wrap_len_index_end)
                            )
    mem_resp_queues(queueno).enq.valid := input_fire_allqueues.fire && use_this_queue
  }

  for ( queueno <- 0 until NUM_QUEUES ) {
    when (mem_resp_queues(queueno).deq.valid) {
      CompressAccelLogger.logInfo("qi%d,0x%x\n", queueno.U, mem_resp_queues(queueno).deq.bits)
    }
  }


  val read_start_index = RegInit(0.U(log2Up(NUM_QUEUES+1).W))

  val remapVecData = Wire(Vec(NUM_QUEUES, UInt(8.W)))
  val remapVecValids = Wire(Vec(NUM_QUEUES, Bool()))
  val remapVecReadys = Wire(Vec(NUM_QUEUES, Bool()))


  for (queueno <- 0 until NUM_QUEUES) {
    remapVecData(queueno) := 0.U
    remapVecValids(queueno) := false.B
    mem_resp_queues(queueno).deq.ready := false.B
  }

  for (queueno <- 0 until NUM_QUEUES) {
    val remapindex = (queueno.U +& read_start_index) % NUM_QUEUES.U
    for (j <- 0 until NUM_QUEUES) {
      when (j.U === remapindex) {
        remapVecData(queueno) := mem_resp_queues(j).deq.bits
        remapVecValids(queueno) := mem_resp_queues(j).deq.valid
        mem_resp_queues(j).deq.ready := remapVecReadys(queueno)
      }
    }
  }

  val count_valids = remapVecValids.map(_.asUInt).reduce(_ +& _)



  val backend_bytes_written = RegInit(0.U(64.W))
  val backend_next_write_addr = decompress_dest_info_Q.io.deq.bits.op + backend_bytes_written

  val throttle_end = Mux(buf_lens_Q.io.deq.valid,
    buf_lens_Q.io.deq.bits - backend_bytes_written,
    16.U)

  val throttle_end_writeable = Mux(throttle_end >= 16.U, 16.U,
                                      Mux(throttle_end(3), 8.U,
                                        Mux(throttle_end(2), 4.U,
                                          Mux(throttle_end(1), 2.U,
                                            Mux(throttle_end(0), 1.U,
                                                                  0.U)))))

  val throttle_end_writeable_log2 = Mux(throttle_end >= 16.U, 4.U,
                                      Mux(throttle_end(3), 3.U,
                                        Mux(throttle_end(2), 2.U,
                                          Mux(throttle_end(1), 1.U,
                                            Mux(throttle_end(0), 0.U,
                                                                  0.U)))))


  val ptr_align_max_bytes_writeable = Mux(backend_next_write_addr(0), 1.U,
                                        Mux(backend_next_write_addr(1), 2.U,
                                          Mux(backend_next_write_addr(2), 4.U,
                                            Mux(backend_next_write_addr(3), 8.U,
                                                                                 16.U))))

  val ptr_align_max_bytes_writeable_log2 = Mux(backend_next_write_addr(0), 0.U,
                                            Mux(backend_next_write_addr(1), 1.U,
                                              Mux(backend_next_write_addr(2), 2.U,
                                                Mux(backend_next_write_addr(3), 3.U,
                                                                                     4.U))))

  val count_valids_largest_aligned = Mux(count_valids(4), 16.U,
                                      Mux(count_valids(3), 8.U,
                                        Mux(count_valids(2), 4.U,
                                          Mux(count_valids(1), 2.U,
                                            Mux(count_valids(0), 1.U,
                                                                  0.U)))))

  val count_valids_largest_aligned_log2 = Mux(count_valids(4), 4.U,
                                            Mux(count_valids(3), 3.U,
                                              Mux(count_valids(2), 2.U,
                                                Mux(count_valids(1), 1.U,
                                                  Mux(count_valids(0), 0.U,
                                                                         0.U)))))



  val bytes_to_write = Mux(
    ptr_align_max_bytes_writeable < count_valids_largest_aligned,
    Mux(ptr_align_max_bytes_writeable < throttle_end_writeable,
      ptr_align_max_bytes_writeable,
      throttle_end_writeable),
    Mux(count_valids_largest_aligned < throttle_end_writeable,
      count_valids_largest_aligned,
      throttle_end_writeable)
  )
  val remapped_write_data = Cat(remapVecData.reverse) // >> ((NUM_QUEUES.U - bytes_to_write) << 3)

  val enough_data = bytes_to_write =/= 0.U

  val bytes_to_write_log2 = Mux(
    ptr_align_max_bytes_writeable_log2 < count_valids_largest_aligned_log2,
    Mux(ptr_align_max_bytes_writeable_log2 < throttle_end_writeable_log2,
      ptr_align_max_bytes_writeable_log2,
      throttle_end_writeable_log2),
    Mux(count_valids_largest_aligned_log2 < throttle_end_writeable_log2,
      count_valids_largest_aligned_log2,
      throttle_end_writeable_log2)
  )

  val write_ptr_override = buf_lens_Q.io.deq.valid && (buf_lens_Q.io.deq.bits === backend_bytes_written)

  val mem_write_fire = DecoupledHelper(
    io.l2io.req.ready,
    enough_data,
    !write_ptr_override,
    decompress_dest_info_Q.io.deq.valid
  )

  val bool_ptr_write_fire = DecoupledHelper(
    io.l2io.req.ready,
    buf_lens_Q.io.deq.valid,
    buf_lens_Q.io.deq.bits === backend_bytes_written,
    decompress_dest_info_Q.io.deq.valid
  )

  for (queueno <- 0 until NUM_QUEUES) {
    remapVecReadys(queueno) := (queueno.U < bytes_to_write) && mem_write_fire.fire
  }

  when (mem_write_fire.fire) {
    read_start_index := (read_start_index +& bytes_to_write) % NUM_QUEUES.U
    backend_bytes_written := backend_bytes_written + bytes_to_write
    backend_bytes_written := backend_bytes_written + bytes_to_write
    CompressAccelLogger.logInfo("[memwriter-serializer] writefire: addr: 0x%x, data 0x%x, size %d\n",
      io.l2io.req.bits.addr,
      io.l2io.req.bits.data,
      io.l2io.req.bits.size
    )
  }

  val bool_val = 1.U
  io.l2io.req.valid := mem_write_fire.fire(io.l2io.req.ready) || bool_ptr_write_fire.fire(io.l2io.req.ready)
  io.l2io.req.bits.size := Mux(write_ptr_override, 0.U, bytes_to_write_log2)
  io.l2io.req.bits.addr := Mux(write_ptr_override, decompress_dest_info_Q.io.deq.bits.boolptr, backend_next_write_addr)
  io.l2io.req.bits.data := Mux(write_ptr_override, bool_val, remapped_write_data)
  io.l2io.req.bits.cmd := M_XWR

  buf_lens_Q.io.deq.ready := bool_ptr_write_fire.fire(buf_lens_Q.io.deq.valid)
  decompress_dest_info_Q.io.deq.ready := bool_ptr_write_fire.fire(decompress_dest_info_Q.io.deq.valid)

  val bufs_completed = RegInit(0.U(64.W))
  io.bufs_completed := bufs_completed

  io.l2io.resp.ready := true.B

  io.no_writes_inflight := io.l2io.no_memops_inflight

  when (bool_ptr_write_fire.fire) {
    bufs_completed := bufs_completed + 1.U
    backend_bytes_written := 0.U
    CompressAccelLogger.logInfo("[memwriter-serializer] write boolptr addr: 0x%x, write ptr val 0x%x\n", decompress_dest_info_Q.io.deq.bits.boolptr, bool_val)
  }

  when (count_valids =/= 0.U) {
    CompressAccelLogger.logInfo("[memwriter-serializer] write_start_index %d, backend_bytes_written %d, count_valids %d, ptr_align_max_bytes_writeable %d, bytes_to_write %d, bytes_to_write_log2 %d\n",
      read_start_index,
      backend_bytes_written,
      count_valids,
      ptr_align_max_bytes_writeable,
      bytes_to_write,
      bytes_to_write_log2
    )
  }



}
