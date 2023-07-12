package compressacc

import Chisel._
import chisel3.{Printable, VecInit}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import chisel3.dontTouch

/*
class SnappyDecompressDestInfo extends Bundle{
  val op = UInt(64.W)
  val boolptr = UInt(64.W)
}
*/
class ZstdSeqInfo extends Bundle{
  val is_match = Bool()
  val ll = UInt(6.W) // instead of 64.W, partitioned to 16 (or 32 for 256b l2 bandwidth)
  val ml = UInt(7.W) // 7bits for Snappy support
  val offset = UInt(64.W)
  val is_final_command = Bool()
}

class ZstdSeqExecControl(l2bw: Int)(implicit p: Parameters) extends Module{
  val io = IO(new Bundle{
    val sequence_in = Decoupled(new ZstdSequence).flip
    val literal_pointer = Input(UInt(64.W)) //receive only once per block
    val literal_pointer_valid = Input(Bool())
    val literal_pointer_dtbuilder = Input(UInt(64.W))
    val num_literals = Input(UInt(64.W))
    val file_pointer = Input(UInt(64.W)) //receive only once per file
    val file_pointer_dtbuilder = Input(UInt(64.W))
    val num_sequences = Input(UInt(64.W))
    val bufs_completed = Input(UInt(64.W)) // from writer unit
    val no_writes_inflight = Input(Bool()) // from writer unit
    
    val lit_src_info = Decoupled(new StreamInfo) //to memloader
    val decompress_dest_info = Decoupled(new SnappyDecompressDestInfo) //to writer unit
    val decompress_dest_info_histlookup = Decoupled(new SnappyDecompressDestInfo) //to OffchipHistoryLookup
    val seq_info = Decoupled(new ZstdSeqInfo) // Sequence info a.k.a. command
    val seqexec_ready = Output(Bool()) //to DTReader
    val completion = Output(Bool())
    val seqCount = Output(UInt(64.W))
    val num_literals_seqexec = Output(UInt(64.W))

    val rawrle_block_size = Input(UInt(21.W))
    val rawrle_completion = Input(Bool())

    val is_last_block = Input(Bool())

    val dt_builder_completion = Input(Bool())
    val zero_num_sequences = Input(Bool())
  })
  dontTouch(io.bufs_completed)
  dontTouch(io.file_pointer_dtbuilder)

  val first_block = RegInit(true.B)
  val ip_reg_valid = RegInit(false.B)
  val seq_received = RegInit(0.U(64.W))
  val num_sequences = RegInit(0.U(64.W))
  val ip_reg = RegInit(0.U(64.W)) // Register to save the input pointer
  val op_reg = RegInit(0.U(64.W)) // Register to save the output pointer // Constant per file
  val num_literals = RegInit(0.U(64.W)) // Number of literals to write
  val litCount = RegInit(0.U(64.W)) //literals spent so far (per block)
  val seqCount = RegInit(0.U(64.W)) //literal length+match length so far (per compressed file)
  val seqCount_total = RegInit(0.U(64.W)) //literal length+match length so far (per compressed file)
  val zero_num_sequences = RegInit(false.B)

  io.seqCount := seqCount

  val repeatedOffset = RegInit(VecInit(1.U(64.W), 4.U(64.W), 8.U(64.W)))
  val offset = Wire(UInt(64.W))

  when (io.sequence_in.bits.offset > 3.U) {
    offset := io.sequence_in.bits.offset
  } .elsewhen (io.sequence_in.bits.ll =/= 0.U) {
    offset := repeatedOffset(io.sequence_in.bits.offset-1.U)
  } .otherwise{
    when (io.sequence_in.bits.offset === 1.U) {
      offset := repeatedOffset(1.U)
    } .elsewhen (io.sequence_in.bits.offset === 2.U) {
      offset := repeatedOffset(2.U)
    } .otherwise{
      offset := repeatedOffset(0.U) - 1.U
    }
  }

  when(io.sequence_in.ready && io.sequence_in.valid){
    when(io.sequence_in.bits.offset > 3.U){
      repeatedOffset(0.U) := offset - 3.U
      repeatedOffset(1.U) := repeatedOffset(0.U)
      repeatedOffset(2.U) := repeatedOffset(1.U)
    } .elsewhen (io.sequence_in.bits.ll =/= 0.U) {
      when (io.sequence_in.bits.offset === 1.U){
        /*No change*/
      } .elsewhen (io.sequence_in.bits.offset === 2.U) {
        repeatedOffset(0.U) := repeatedOffset(1.U)
        repeatedOffset(1.U) := repeatedOffset(0.U)
      }.otherwise{
        repeatedOffset(0.U) := repeatedOffset(2.U)
        repeatedOffset(1.U) := repeatedOffset(0.U)
        repeatedOffset(2.U) := repeatedOffset(1.U)
      }
    } .otherwise {
      when(io.sequence_in.bits.offset===1.U){
        repeatedOffset(0.U) := repeatedOffset(1.U)
        repeatedOffset(1.U) := repeatedOffset(0.U)
      }.elsewhen(io.sequence_in.bits.offset===2.U){
        repeatedOffset(0.U) := repeatedOffset(2.U)
        repeatedOffset(1.U) := repeatedOffset(0.U)
        repeatedOffset(2.U) := repeatedOffset(1.U)
      }.otherwise{
        repeatedOffset(0.U) := repeatedOffset(0.U) - 1.U
        repeatedOffset(1.U) := repeatedOffset(0.U)
        repeatedOffset(2.U) := repeatedOffset(1.U)
      }
    }
  }

  io.seqexec_ready := first_block || seq_received === num_sequences

  when((io.literal_pointer_valid && (first_block || seq_received === num_sequences)) ||
    (io.dt_builder_completion && io.zero_num_sequences)){
    ip_reg := Mux(io.dt_builder_completion && io.zero_num_sequences,
      io.literal_pointer_dtbuilder,
      io.literal_pointer)    
    ip_reg_valid := true.B
    litCount := 0.U
    seq_received := 0.U
    num_sequences := io.num_sequences
    num_literals := io.num_literals
    zero_num_sequences := io.dt_builder_completion && io.zero_num_sequences
    when(first_block){
      op_reg := Mux(io.dt_builder_completion && io.zero_num_sequences,
        io.file_pointer_dtbuilder,
        io.file_pointer)
      first_block := false.B
    }
  } .elsewhen (io.sequence_in.valid && io.sequence_in.ready) {
    seq_received:=seq_received + 1.U
  }

  val ll_is_zero = Wire(Bool())
  ll_is_zero := io.sequence_in.bits.ll === 0.U

  val ml_reg = RegInit(0.U(64.W))
  val ml_is_zero = Wire(Bool())
  ml_is_zero := io.sequence_in.bits.ml === 0.U

  val is_first_literal = RegInit(true.B)
  val current_ll = Wire(UInt(64.W))
  val ll_consumed_bytes = RegInit(0.U(64.W))
  val ll_left_to_consume = io.sequence_in.bits.ll - ll_consumed_bytes
  val current_ml = Wire(UInt(8.W))
  val ml_consumed_bytes = RegInit(0.U(64.W))
  val ml_left_to_consume = io.sequence_in.bits.ml - ml_consumed_bytes
  val done_dealing_literals = RegInit(false.B) // If dealing with literals are done
  val is_first_command = RegInit(true.B) // If it's the first command of the sequence
  val is_first_match = RegInit(true.B) //If it's the first match command of the sequence

  // Queues
  // (1) Queue for literal read information - moved to here because of variable order
  val lit_src_info_queue_flush = Wire(Bool())
  val lit_src_info_queue = Module(new Queue(new SnappyDecompressSrcInfo,
    4, false, false, lit_src_info_queue_flush || reset))

  // (2) Queue for write
  val dest_info_queue = Module(new Queue(new SnappyDecompressDestInfo, 4))

  // (2-2) Duplication of dest_info_queue for history lookup
  val dest_info_queue_histlookup = Module(new Queue(new SnappyDecompressDestInfo, 4))

  // (3) Queue for partitioned sequences, with LL and ML handling logic.
  // Divide LL and ML into 16*q+r. 
  val seq_info_queue = Module(new Queue(new ZstdSeqInfo, 4))

  // Queue Interface
  val lit_info_fire = DecoupledHelper(io.sequence_in.valid,
    !done_dealing_literals, !ll_is_zero,
    //memloader can't handle 0B load, so don't put 0B to lit_src_info_queue.
    dest_info_queue.io.enq.ready, seq_info_queue.io.enq.ready,
    dest_info_queue_histlookup.io.enq.ready)

  val match_info_fire = DecoupledHelper(io.sequence_in.valid,
    dest_info_queue.io.enq.ready, 
    seq_info_queue.io.enq.ready,
    (!ml_is_zero), 
    (ll_is_zero || done_dealing_literals),
    dest_info_queue_histlookup.io.enq.ready)

  val bitstream_requested = RegInit(false.B)
  when(!io.completion && lit_src_info_queue.io.enq.valid && lit_src_info_queue.io.enq.ready){
    bitstream_requested := true.B
  }
  // Send only one literal request. The size is num_literals.
  lit_src_info_queue.io.enq.valid := ip_reg_valid && !bitstream_requested
  lit_src_info_queue.io.enq.bits.ip := ip_reg
  lit_src_info_queue.io.enq.bits.isize := num_literals
  
  when (!ll_is_zero && !done_dealing_literals) {
    dest_info_queue_histlookup.io.enq.valid := lit_info_fire.fire(dest_info_queue_histlookup.io.enq.ready)
    seq_info_queue.io.enq.valid := lit_info_fire.fire(seq_info_queue.io.enq.ready)
  }.elsewhen ((!ml_is_zero) && (ll_is_zero || done_dealing_literals)) {
    dest_info_queue_histlookup.io.enq.valid := match_info_fire.fire(dest_info_queue_histlookup.io.enq.ready)
    seq_info_queue.io.enq.valid := match_info_fire.fire(seq_info_queue.io.enq.ready)
  }.otherwise {
    dest_info_queue_histlookup.io.enq.valid := false.B
    seq_info_queue.io.enq.valid := false.B
  }

  // Only insert to dest_info_queue once for the 1st to last sequence,
  // optionally once more for the last literal burst.
  when(io.sequence_in.valid){
    when(seq_received===0.U){
      dest_info_queue.io.enq.valid := (!ll_is_zero && is_first_literal && !done_dealing_literals) ||
        (ll_is_zero && !ml_is_zero && ml_consumed_bytes===0.U)
    }.otherwise{
      dest_info_queue.io.enq.valid := false.B
    }
  }.otherwise{
    when( ((seq_received===num_sequences && num_sequences =/= 0.U && litCount < num_literals)||
      zero_num_sequences) &&
      lit_src_info_queue.io.enq.ready && dest_info_queue.io.enq.ready && 
      dest_info_queue_histlookup.io.enq.ready && seq_info_queue.io.enq.ready &&
      !done_dealing_literals && is_first_literal){
      dest_info_queue.io.enq.valid := true.B
    }.otherwise{
      dest_info_queue.io.enq.valid := false.B
    }
  }

  // track dispatched information for completion check
  val dispatched_info_counter = RegInit(0.U(64.W))
  when(dest_info_queue.io.enq.valid && dest_info_queue.io.enq.ready){
    dispatched_info_counter := dispatched_info_counter + 1.U
  }

  // Turn on ready signal at the last partition of the sequence.
  io.sequence_in.ready := (ll_is_zero && ml_is_zero) ||
  (!ll_is_zero && ml_is_zero && ll_left_to_consume <= (l2bw/8).U && lit_info_fire.fire) ||
  (!ml_is_zero && (ll_is_zero || done_dealing_literals) && ml_left_to_consume===current_ml && match_info_fire.fire)

  when (io.sequence_in.valid) {
    when (!ll_is_zero && !done_dealing_literals && lit_info_fire.fire) { // Dealing with literals
      when (is_first_literal) {
        current_ll := Mux(io.sequence_in.bits.ll<=(l2bw/8).U, io.sequence_in.bits.ll, (l2bw/8).U)
        is_first_literal := Mux(io.sequence_in.bits.ll<=(l2bw/8).U, true.B, false.B)
        ll_consumed_bytes := Mux(io.sequence_in.bits.ll<=(l2bw/8).U, 0.U, (l2bw/8).U)
        done_dealing_literals := Mux(io.sequence_in.bits.ll<=(l2bw/8).U, true.B, false.B)
      } .otherwise {
        current_ll := Mux(ll_left_to_consume<=(l2bw/8).U, ll_left_to_consume, (l2bw/8).U)
        is_first_literal := Mux(ll_left_to_consume<=(l2bw/8).U, true.B, false.B)
        ll_consumed_bytes := Mux(ll_left_to_consume<=(l2bw/8).U, 0.U, ll_consumed_bytes + current_ll)
        done_dealing_literals := Mux(ll_left_to_consume<=(l2bw/8).U, true.B, false.B)
      }

      dest_info_queue.io.enq.bits.op := op_reg + seqCount_total
      dest_info_queue.io.enq.bits.boolptr := 0.U

      seq_info_queue.io.enq.bits.is_match := false.B
      seq_info_queue.io.enq.bits.ll := current_ll
      seq_info_queue.io.enq.bits.ml := io.sequence_in.bits.ml // doesn't matter
      seq_info_queue.io.enq.bits.offset := io.sequence_in.bits.offset // doesn't matter
      seq_info_queue.io.enq.bits.is_final_command := (seq_received === num_sequences - 1.U) &&
        ml_is_zero && (ll_left_to_consume<=(l2bw/8).U)

      dest_info_queue_histlookup.io.enq.bits.op := op_reg + seqCount_total
      dest_info_queue_histlookup.io.enq.bits.boolptr := 0.U

      litCount := litCount + current_ll
      seqCount := seqCount + current_ll
      seqCount_total := seqCount_total + current_ll
    }

    when ((!ml_is_zero) && (ll_is_zero || done_dealing_literals) && match_info_fire.fire) { // done dealing with literals, deal with matches
      seq_info_queue.io.enq.bits.is_match := true.B
      // offset is selected when doing RLE decoding. (ex) (0, 5, 1)-->(0, 1, 1)x5
      current_ml := Mux(ml_left_to_consume <= (l2bw/8).U,
        Mux(ml_left_to_consume <= io.sequence_in.bits.offset, ml_left_to_consume, io.sequence_in.bits.offset),
        Mux((l2bw/8).U <= io.sequence_in.bits.offset, (l2bw/8).U, io.sequence_in.bits.offset)
      )

      seq_info_queue.io.enq.bits.ll := 0.U //doesn't matter 
      seq_info_queue.io.enq.bits.ml := current_ml
      seq_info_queue.io.enq.bits.offset := io.sequence_in.bits.offset
      seq_info_queue.io.enq.bits.is_final_command := (seq_received === num_sequences - 1.U) &&
        (ll_is_zero || done_dealing_literals) && (ml_left_to_consume-current_ml === 0.U)

      ml_consumed_bytes := ml_consumed_bytes + current_ml
      seqCount := seqCount + current_ml
      seqCount_total := seqCount_total + current_ml

      when (ml_left_to_consume-current_ml === 0.U) {
        is_first_command := true.B
        ml_reg := 0.U
        ml_consumed_bytes := 0.U
        is_first_match := true.B
        done_dealing_literals := false.B
      } .otherwise {
        is_first_command := false.B
        is_first_match := false.B
      }
      dest_info_queue.io.enq.bits.op := op_reg + seqCount_total
      dest_info_queue.io.enq.bits.boolptr := 0.U // Don't care

      dest_info_queue_histlookup.io.enq.bits.op := op_reg + seqCount_total
      dest_info_queue_histlookup.io.enq.bits.boolptr := 0.U // Don't care 
      //op_reg := op_reg +& current_ml
    }
  }

  val seq_completion = io.no_writes_inflight && dispatched_info_counter =/= 0.U &&
  seq_received === num_sequences //&& io.bufs_completed === dispatched_info_counter

  val lit_completion = litCount === num_literals && litCount =/= 0.U
  lit_src_info_queue_flush := false.B //lit_completion

  // Dealing with the remaining literals to write
  val last_ll = num_literals - litCount
  when ((seq_received === num_sequences && num_sequences =/= 0.U && litCount < num_literals) ||
    zero_num_sequences) {
    when (dest_info_queue.io.enq.ready && 
      dest_info_queue_histlookup.io.enq.ready && seq_info_queue.io.enq.ready &&
      !done_dealing_literals) {

      current_ll := Mux(last_ll<=(l2bw/8).U, last_ll, (l2bw/8).U)
      is_first_literal := last_ll<=(l2bw/8).U
      done_dealing_literals := last_ll<=(l2bw/8).U

      dest_info_queue.io.enq.bits.op := op_reg + seqCount_total
      dest_info_queue.io.enq.bits.boolptr := 0.U

      seq_info_queue.io.enq.bits.is_match := false.B
      seq_info_queue.io.enq.bits.ll := current_ll
      seq_info_queue.io.enq.bits.ml := 0.U // doesn't matter
      seq_info_queue.io.enq.bits.offset := 0.U // doesn't matter
      seq_info_queue.io.enq.bits.is_final_command := last_ll <= (l2bw/8).U

      litCount := litCount + current_ll
      seqCount := seqCount + current_ll
      seqCount_total := seqCount_total + current_ll

      dest_info_queue_histlookup.io.enq.valid := true.B
      seq_info_queue.io.enq.valid := true.B
    }
  }

  //completion check
  io.completion := seq_completion && lit_completion
  io.num_literals_seqexec := num_literals

  //increment seqcount after raw/rle block completion
  when(io.rawrle_completion && !io.is_last_block){
    seqCount_total := seqCount_total + io.rawrle_block_size
  }

  //reset registers except repeatedOffset, first_block, op_reg after completion
  when(io.completion){
    seq_received := 0.U
    num_sequences := 0.U
    ip_reg := 0.U
    num_literals := 0.U
    litCount := 0.U
    seqCount := 0.U
    ml_reg := 0.U
    is_first_literal := true.B
    ll_consumed_bytes := 0.U
    ml_consumed_bytes := 0.U
    done_dealing_literals := false.B
    is_first_command := true.B
    is_first_match := true.B
    dispatched_info_counter := 0.U
    ip_reg_valid := false.B
    bitstream_requested := false.B
    when(io.is_last_block){
      seqCount_total := 0.U
      first_block := true.B
    }
    zero_num_sequences := false.B
  }

  //prevent floating wire
  when(!(io.sequence_in.valid && !ll_is_zero && !done_dealing_literals && lit_info_fire.fire) &&
    !(((seq_received === num_sequences && num_sequences =/= 0.U && litCount < num_literals) ||
    zero_num_sequences) &&
    dest_info_queue.io.enq.ready && dest_info_queue_histlookup.io.enq.ready && 
    seq_info_queue.io.enq.ready && !done_dealing_literals)){
    current_ll := 0.U
  }

  // Output
  io.lit_src_info <> lit_src_info_queue.io.deq
  io.decompress_dest_info <> dest_info_queue.io.deq
  io.decompress_dest_info_histlookup <> dest_info_queue_histlookup.io.deq
  io.seq_info <> seq_info_queue.io.deq
}
