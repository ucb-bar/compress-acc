package compressacc

import Chisel._
import chisel3.{Printable, VecInit}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._



class SnappyCompressCopyExpander()(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val memwrites_in = Decoupled(new CompressWriterBundle).flip
    val memwrites_out = Decoupled(new CompressWriterBundle)
  })

  val incoming_writes_Q = Module(new Queue(new CompressWriterBundle, 4))
  incoming_writes_Q.io.enq <> io.memwrites_in


  val intermediate_writes_Q = Module(new Queue(new CompressWriterBundle, 4))

  // stage 1: expand copies with lengths larger than 64 into individual
  // copies, read from incoming_writes_Q, output to intermediate_writes_Q

  // defaults:
  intermediate_writes_Q.io.enq.bits <> incoming_writes_Q.io.deq.bits
  intermediate_writes_Q.io.enq.valid := false.B
  incoming_writes_Q.io.deq.ready := false.B

  val copy_len = incoming_writes_Q.io.deq.bits.data(127, 64)
  val expansion_counter = RegInit(0.U(64.W))
  val len_diff = copy_len - expansion_counter

  when ((!incoming_writes_Q.io.deq.bits.is_copy) || incoming_writes_Q.io.deq.bits.length_header || (len_diff <= 64.U)) {
    intermediate_writes_Q.io.enq.valid := incoming_writes_Q.io.deq.valid
    incoming_writes_Q.io.deq.ready := intermediate_writes_Q.io.enq.ready
    when (!((!incoming_writes_Q.io.deq.bits.is_copy) || incoming_writes_Q.io.deq.bits.length_header)) {
        intermediate_writes_Q.io.enq.bits.data := Cat(len_diff, incoming_writes_Q.io.deq.bits.data(63, 0))
    }
    when (incoming_writes_Q.io.deq.valid && intermediate_writes_Q.io.enq.ready) {
        expansion_counter := 0.U
    }
  } .otherwise {
    // a copy that needs to be split into multiple
    // we only handle the first steps here, so this can NEVER have end_of_message set
    intermediate_writes_Q.io.enq.bits.end_of_message := false.B
    intermediate_writes_Q.io.enq.bits.data := Cat(64.U(64.W), incoming_writes_Q.io.deq.bits.data(63, 0))
    intermediate_writes_Q.io.enq.valid := incoming_writes_Q.io.deq.valid

    when (incoming_writes_Q.io.deq.valid && intermediate_writes_Q.io.enq.ready) {
      expansion_counter := expansion_counter + 64.U
    }
  }


  // stage 2: encode copies properly

  // defaults
  io.memwrites_out <> intermediate_writes_Q.io.deq

  val copy_length = intermediate_writes_Q.io.deq.bits.data(127, 64)
  val copy_offset = intermediate_writes_Q.io.deq.bits.data(63, 0)

  when (intermediate_writes_Q.io.enq.fire) {
    CompressAccelLogger.logInfo("CopyExpander is_copy: %d offset: %d copy_len: %d\n", 
      intermediate_writes_Q.io.enq.bits.is_copy, 
      intermediate_writes_Q.io.enq.bits.data(63, 0),
      intermediate_writes_Q.io.enq.bits.data(127, 64))
  }



  when (io.memwrites_out.fire && (copy_offset === 0.U)) {
    CompressAccelLogger.logInfo("ERROR: INVALID OFFSET 0!\n")
  }


  when (intermediate_writes_Q.io.deq.bits.is_copy) {
    // do encoding
    when (copy_length >= 4.U && copy_length <= 11.U && copy_offset <= 2047.U) {
      val byte1_low_bits = 1.U(2.W)
      val byte1_len_enc = (copy_length - 4.U)(2, 0)
      val byte1_offset = (copy_offset >> 8)(2, 0)

      val byte2_offset = copy_offset(7, 0)
      io.memwrites_out.bits.data := Cat(byte2_offset, byte1_offset, byte1_len_enc, byte1_low_bits)
      io.memwrites_out.bits.validbytes := 2.U
    } .elsewhen (copy_length >= 1.U && copy_length <= 64.U && copy_offset <= 65535.U) {
      val byte1_low_bits = 2.U(2.W)
      val byte1_len_enc = (copy_length - 1.U)(5, 0)
      val byte32_offset = copy_offset(15, 0)
      io.memwrites_out.bits.data := Cat(byte32_offset, byte1_len_enc, byte1_low_bits)
      io.memwrites_out.bits.validbytes := 3.U
    } .elsewhen (copy_length >= 1.U && copy_length <= 64.U && copy_offset <= ((BigInt("FFFFFFFF", 16)).U(64.W))) {
      val byte1_low_bits = 3.U(2.W)
      val byte1_len_enc = (copy_length - 1.U)(5, 0)
      val byte5432_offset = copy_offset(31, 0)
      io.memwrites_out.bits.data := Cat(byte5432_offset, byte1_len_enc, byte1_low_bits)
      io.memwrites_out.bits.validbytes := 5.U
    } .otherwise {
      when (io.memwrites_out.fire) {
        CompressAccelLogger.logInfo("ERROR: INVALID COMBINATION OF LENGTH/OFFSET!\n")
      }
    }
  }
}


/* Basically:
 * input interface is the same as the memwriter's input interface
 * output interface is the same as the memloader's input interface
 *
 * just an easy to use rotating buffer of data
 */
class SnappyCompressLitRotBuf()(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val memwrites_in = Decoupled(new CompressWriterBundle).flip
    val consumer = new MemLoaderConsumerBundle
  })

  val incoming_writes_Q = Module(new Queue(new CompressWriterBundle, 4))

  incoming_writes_Q.io.enq <> io.memwrites_in

  when (incoming_writes_Q.io.deq.fire) {
    CompressAccelLogger.logInfo("[lit-rot-buf] dat: 0x%x, bytes: 0x%x, EOM: %d\n",
      incoming_writes_Q.io.deq.bits.data,
      incoming_writes_Q.io.deq.bits.validbytes,
      incoming_writes_Q.io.deq.bits.end_of_message
      )
  }

  val NUM_QUEUES = 32
  val QUEUE_DEPTHS = 64
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

  }

  io.consumer.output_valid := read_fire.fire(io.consumer.output_ready)

  for (queueno <- 0 until NUM_QUEUES) {
    remapVecReadys(queueno) := (UInt(queueno) < io.consumer.user_consumed_bytes) && read_fire.fire
  }

  when (read_fire.fire) {
    read_start_index := (read_start_index +& io.consumer.user_consumed_bytes) % UInt(NUM_QUEUES)
  }

}

class SnappyCompressLitLenInjector()(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val memwrites_in = Decoupled(new CompressWriterBundle).flip
    val memwrites_out = Decoupled(new CompressWriterBundle)
  })

  val incoming_writes_Q = Module(new Queue(new CompressWriterBundle, 4))
  incoming_writes_Q.io.enq <> io.memwrites_in


  // track which commands are there. this will NOT keep literal data
  // literal commands will only enter this queue when chunked into 1024B or
  // less. furthermore, for literal commands, data = length in this queue
  val intermediate_writes_Q = Module(new Queue(new CompressWriterBundle, 10))
  val lit_rot_buf = Module(new SnappyCompressLitRotBuf)


  val lit_len_so_far = RegInit(0.U(64.W))


  // a literal can end because:
  // exceeded 1024: issue a cmd for lit_len_so_far + current
  //  it's okay to issue the whole thing because:
  //    worst case it is of length 1023 + 32 = 1055 which:
  //      a) fits in our buffer
  //      b) fits in 3B encoded len.
  //  this naturally also handles end-of-buffer case, just pass it through
  //  set lit_len_so_far to 0
  // copy command: issue the lit end cmd, then an extra cycle for the copy
  // EOB case while not exceeding 1024:
  //  issue whatever lit_len_so_far + current is
  //  set lit_len_so_far to 0


  // defaults, only tie BITS
  intermediate_writes_Q.io.enq.bits <> incoming_writes_Q.io.deq.bits
  lit_rot_buf.io.memwrites_in.bits <> incoming_writes_Q.io.deq.bits

  intermediate_writes_Q.io.enq.valid := false.B
  lit_rot_buf.io.memwrites_in.valid := false.B
  incoming_writes_Q.io.deq.ready := false.B

  when ((lit_len_so_far === 0.U) && (incoming_writes_Q.io.deq.bits.is_copy || incoming_writes_Q.io.deq.bits.length_header)) {
    // regular case, nothing special happens
    intermediate_writes_Q.io.enq.valid := incoming_writes_Q.io.deq.valid
    incoming_writes_Q.io.deq.ready := intermediate_writes_Q.io.enq.ready
  } .elsewhen (incoming_writes_Q.io.deq.bits.is_copy) {
    // copy with a non-zero lit_len_so_far. flush the lit this cycle and
    // set lit_len_so_far to zero and next cycle, the above case will happen
    // to issue the copy
    intermediate_writes_Q.io.enq.valid := incoming_writes_Q.io.deq.valid
    //incoming_writes_Q's io.deq.ready stays false
    intermediate_writes_Q.io.enq.bits.data := lit_len_so_far
    intermediate_writes_Q.io.enq.bits.validbytes := 0.U // don't really care
    intermediate_writes_Q.io.enq.bits.end_of_message := false.B
    intermediate_writes_Q.io.enq.bits.is_copy := false.B
    intermediate_writes_Q.io.enq.bits.length_header := false.B
    when (intermediate_writes_Q.io.enq.fire) {
      lit_len_so_far := 0.U
    }
  } .elsewhen (incoming_writes_Q.io.deq.bits.length_header) {
    // THIS SHOULD NOT BE POSSIBLE!!
    CompressAccelLogger.logInfo("FAIL: length_header with non-zero lit_len_so_far.\n")
  } .otherwise {
    // at this point, we have a literal
    val total_lit_bytes = incoming_writes_Q.io.deq.bits.validbytes + lit_len_so_far
    when ((total_lit_bytes >= 1024.U) || incoming_writes_Q.io.deq.bits.end_of_message) {
      // EOB or lit size >= 1024. flush
      incoming_writes_Q.io.deq.ready := intermediate_writes_Q.io.enq.ready && lit_rot_buf.io.memwrites_in.ready
      intermediate_writes_Q.io.enq.valid := incoming_writes_Q.io.deq.valid && lit_rot_buf.io.memwrites_in.ready
      lit_rot_buf.io.memwrites_in.valid := incoming_writes_Q.io.deq.valid && intermediate_writes_Q.io.enq.ready

      when (intermediate_writes_Q.io.enq.ready && lit_rot_buf.io.memwrites_in.ready && incoming_writes_Q.io.deq.valid) {
        lit_len_so_far := 0.U
      }

      intermediate_writes_Q.io.enq.bits.data := total_lit_bytes
      intermediate_writes_Q.io.enq.bits.validbytes := 0.U // don't really care
      // pass through end_of_message as it is
      //intermediate_writes_Q.io.enq.bits.end_of_message := false.B
      intermediate_writes_Q.io.enq.bits.is_copy := false.B
      intermediate_writes_Q.io.enq.bits.length_header := false.B

    } .otherwise {
      // total lit bytes still < 1024 and not EOB, so just push into lit_rot_buf
      lit_rot_buf.io.memwrites_in.valid := incoming_writes_Q.io.deq.valid
      incoming_writes_Q.io.deq.ready := lit_rot_buf.io.memwrites_in.ready
      when (incoming_writes_Q.io.deq.valid && lit_rot_buf.io.memwrites_in.ready) {
        lit_len_so_far := total_lit_bytes
      }
    }
  }

  // consumer side:

  // IFs that matter:
  // intermediate_writes_Q.io.deq
  // lit_rot_buf.io.consumer
  // io.memwrites_out


  // most of the time, memwrites_out is just intermediate writes, just BITS
  io.memwrites_out.bits <> intermediate_writes_Q.io.deq.bits
  io.memwrites_out.bits.is_dummy := false.B

  intermediate_writes_Q.io.deq.ready := false.B
  lit_rot_buf.io.consumer.output_ready := false.B
  io.memwrites_out.valid := false.B

  val literal_emit_length_done = RegInit(false.B)
  val literal_amt_emitted_so_far = RegInit(0.U(64.W))

  when (intermediate_writes_Q.io.deq.bits.is_copy || intermediate_writes_Q.io.deq.bits.length_header) {
    io.memwrites_out.valid := intermediate_writes_Q.io.deq.valid
    intermediate_writes_Q.io.deq.ready := io.memwrites_out.ready
  } .otherwise {
    // literal stuff
    val current_lit_len = intermediate_writes_Q.io.deq.bits.data

    when (!literal_emit_length_done) {
      // need to emit length

      io.memwrites_out.bits.end_of_message := false.B
      io.memwrites_out.bits.is_copy := false.B
      io.memwrites_out.bits.length_header := false.B

      io.memwrites_out.valid := intermediate_writes_Q.io.deq.valid
      // DO NOT DEQUEUE FROM intermediate_writes_Q

      when (io.memwrites_out.ready && intermediate_writes_Q.io.deq.valid) {
        literal_emit_length_done := true.B
      }

      when (current_lit_len <= 60.U) {
        val low_bits = 0.U(2.W)
        val high_bits = (current_lit_len - 1.U)(5, 0)
        io.memwrites_out.bits.data := Cat(high_bits, low_bits)
        io.memwrites_out.bits.validbytes := 1.U
      } .elsewhen (current_lit_len <= 256.U) {
        val low_bits = 0.U(2.W)
        val high_bits = (60.U)(5, 0)
        io.memwrites_out.bits.data := Cat(current_lit_len-1.U, high_bits, low_bits)
        io.memwrites_out.bits.validbytes := 2.U
      } .elsewhen (current_lit_len <= 65536.U) {
        val low_bits = 0.U(2.W)
        val high_bits = (61.U)(5, 0)
        io.memwrites_out.bits.data := Cat(current_lit_len-1.U, high_bits, low_bits)
        io.memwrites_out.bits.validbytes := 3.U
      } .elsewhen (current_lit_len <= 16777216.U) {
        val low_bits = 0.U(2.W)
        val high_bits = (62.U)(5, 0)
        io.memwrites_out.bits.data := Cat(current_lit_len-1.U, high_bits, low_bits)
        io.memwrites_out.bits.validbytes := 4.U
      } .otherwise {
        val low_bits = 0.U(2.W)
        val high_bits = (63.U)(5, 0)
        io.memwrites_out.bits.data := Cat(current_lit_len-1.U, high_bits, low_bits)
        io.memwrites_out.bits.validbytes := 5.U
      }
    } .otherwise {
      // ship out literal data
      val lit_rot_data_available = lit_rot_buf.io.consumer.available_output_bytes
      val data_left_to_write_current_lit = current_lit_len - literal_amt_emitted_so_far
      val data_shippable = Mux(lit_rot_data_available > data_left_to_write_current_lit,
        data_left_to_write_current_lit,
        lit_rot_data_available)
      val is_last_lit_data_chunk = data_shippable === data_left_to_write_current_lit

      val new_eob = intermediate_writes_Q.io.deq.bits.end_of_message && is_last_lit_data_chunk

      io.memwrites_out.bits.data := lit_rot_buf.io.consumer.output_data
      io.memwrites_out.bits.validbytes := data_shippable
      io.memwrites_out.bits.end_of_message := new_eob
      io.memwrites_out.bits.is_copy := false.B
      io.memwrites_out.bits.length_header := false.B

      io.memwrites_out.valid := lit_rot_buf.io.consumer.output_valid && intermediate_writes_Q.io.deq.valid
      lit_rot_buf.io.consumer.output_ready := io.memwrites_out.ready && intermediate_writes_Q.io.deq.valid
      intermediate_writes_Q.io.deq.ready := lit_rot_buf.io.consumer.output_valid && io.memwrites_out.ready && is_last_lit_data_chunk

      lit_rot_buf.io.consumer.user_consumed_bytes := data_shippable

      when (lit_rot_buf.io.consumer.output_valid && io.memwrites_out.ready && is_last_lit_data_chunk && intermediate_writes_Q.io.deq.valid) {
        literal_amt_emitted_so_far := 0.U
        literal_emit_length_done := false.B
      } .elsewhen (lit_rot_buf.io.consumer.output_valid && io.memwrites_out.ready && intermediate_writes_Q.io.deq.valid) {
        literal_amt_emitted_so_far := literal_amt_emitted_so_far + data_shippable
      }
    }
  }

}
