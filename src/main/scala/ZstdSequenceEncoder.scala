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


class ZstdSequenceEncoderL2IO()(implicit p: Parameters) extends Bundle {
  val seq_reader = new L2MemHelperBundle
  val seq_reader2 = new L2MemHelperBundle
  val seq_writer = new L2MemHelperBundle
}


class ZstdSequenceEncoderIO()(implicit p: Parameters) extends Bundle {
  val src_info = Flipped(Decoupled(new StreamInfo))
  val dst_info = Flipped(Decoupled(new DstWithValInfo))
  val bytes_written = Decoupled(UInt(64.W))
  val l2if = new ZstdSequenceEncoderL2IO

  val busy = Output(Bool())
}


class ZstdSequenceEncoder()(implicit p: Parameters) extends Module {

  val io = IO(new ZstdSequenceEncoderIO)

  dontTouch(io)

  val src_info_q = Module(new Queue(new StreamInfo, 4))
  val src_info_q2 = Module(new Queue(new StreamInfo, 4))

  val nbseq_q = Module(new Queue(UInt(64.W), 4))
  val nbseq_q1 = Module(new Queue(UInt(64.W), 4))
  val nbseq_q2 = Module(new Queue(UInt(64.W), 4))
  val nbseq_q3 = Module(new Queue(UInt(64.W), 4))

  val insert_src_fire = DecoupledHelper(
    src_info_q.io.enq.ready,
    src_info_q2.io.enq.ready,
    nbseq_q.io.enq.ready,
    nbseq_q1.io.enq.ready,
    nbseq_q2.io.enq.ready,
    nbseq_q3.io.enq.ready,
    io.src_info.valid)

  io.src_info.ready := insert_src_fire.fire(io.src_info.valid)

  src_info_q.io.enq.valid := insert_src_fire.fire(src_info_q.io.enq.ready)
  src_info_q2.io.enq.valid := insert_src_fire.fire(src_info_q2.io.enq.ready)
  src_info_q.io.enq.bits := io.src_info.bits
  src_info_q2.io.enq.bits := io.src_info.bits

  val nbseq = io.src_info.bits.isize / 12.U
  nbseq_q.io.enq.valid := insert_src_fire.fire(nbseq_q.io.enq.ready)
  nbseq_q1.io.enq.valid := insert_src_fire.fire(nbseq_q1.io.enq.ready)
  nbseq_q2.io.enq.valid := insert_src_fire.fire(nbseq_q2.io.enq.ready)
  nbseq_q3.io.enq.valid := insert_src_fire.fire(nbseq_q3.io.enq.ready)

  nbseq_q.io.enq.bits := nbseq
  nbseq_q1.io.enq.bits := nbseq
  nbseq_q2.io.enq.bits := nbseq
  nbseq_q3.io.enq.bits := nbseq


  val src_memloader = Module(new MemLoader)
  io.l2if.seq_reader <> src_memloader.io.l2helperUser
  src_memloader.io.src_info <> src_info_q.io.deq

  val seq_to_code_converter = Module(new FSESequenceToCodeConverter())
  seq_to_code_converter.io.src_stream <> src_memloader.io.consumer

  val ll_max_accuracy = p(ZstdLiteralLengthMaxAccuracy)
  val ll_dic_builder = Module(new FSECompressorDicBuilder(printInfo="LL",
                                                          interleave_cnt=1,
                                                          as_zstd_submodule=true,
                                                          max_symbol_value=35,
                                                          max_table_log=ll_max_accuracy,
                                                          predefined_table_log=6,
                                                          mark_end_of_header=true))
  ll_dic_builder.io.nb_seq <> nbseq_q.io.deq
  ll_dic_builder.io.ll_stream <> seq_to_code_converter.io.ll_consumer

  val of_max_accuracy = p(ZstdOffsetMaxAccuracy)
  val of_dic_builder = Module(new FSECompressorDicBuilder(printInfo="OF",
                                                          interleave_cnt=1,
                                                          as_zstd_submodule=true,
                                                          max_symbol_value=31,
                                                          max_table_log=of_max_accuracy,
                                                          predefined_table_log=5,
                                                          mark_end_of_header=true))
  of_dic_builder.io.nb_seq <> nbseq_q1.io.deq
  of_dic_builder.io.ll_stream <> seq_to_code_converter.io.of_consumer

  val ml_max_accuracy = p(ZstdMatchLengthMaxAccuracy)
  val ml_dic_builder = Module(new FSECompressorDicBuilder(printInfo="ML",
                                                          interleave_cnt=1,
                                                          as_zstd_submodule=true,
                                                          max_symbol_value=52,
                                                          max_table_log=ml_max_accuracy,
                                                          predefined_table_log=6,
                                                          mark_end_of_header=true))
  ml_dic_builder.io.nb_seq <> nbseq_q2.io.deq
  ml_dic_builder.io.ll_stream <> seq_to_code_converter.io.ml_consumer

  val src_memloader2 = Module(new ReverseMemLoader("seq-revmemloader"))
  io.l2if.seq_reader2 <> src_memloader2.io.l2helperUser
  src_memloader2.io.src_info <> src_info_q2.io.deq

  val encoder = Module(new FSESequenceCompressorEncoder)
  encoder.io.src_stream <> src_memloader2.io.consumer
  encoder.io.nbseq <> nbseq_q3.io.deq

  encoder.io.ll_table_log <> ll_dic_builder.io.ll_table_log
  encoder.io.ll_header_writes <> ll_dic_builder.io.header_writes
  encoder.io.ll_predefined_mode <> ll_dic_builder.io.predefined_mode

  encoder.io.of_table_log <> of_dic_builder.io.ll_table_log
  encoder.io.of_header_writes <> of_dic_builder.io.header_writes
  encoder.io.of_predefined_mode <> of_dic_builder.io.predefined_mode

  encoder.io.ml_table_log <> ml_dic_builder.io.ll_table_log
  encoder.io.ml_header_writes <> ml_dic_builder.io.header_writes
  encoder.io.ml_predefined_mode <> ml_dic_builder.io.predefined_mode

  ll_dic_builder.io.lookup_done <> encoder.io.ll_lookup_done
  of_dic_builder.io.lookup_done <> encoder.io.of_lookup_done
  ml_dic_builder.io.lookup_done <> encoder.io.ml_lookup_done

  // ll lookup
  ll_dic_builder.io.symbol_info(0) <> encoder.io.ll_symbol_info
  ll_dic_builder.io.state_table_idx(0) := encoder.io.ll_state_table_idx
  encoder.io.ll_comp_trans_table <> ll_dic_builder.io.symbolTT_info(0)
  encoder.io.ll_new_state <> ll_dic_builder.io.new_state(0)

  // of lookup
  of_dic_builder.io.symbol_info(0) <> encoder.io.of_symbol_info
  of_dic_builder.io.state_table_idx(0) := encoder.io.of_state_table_idx
  encoder.io.of_comp_trans_table <> of_dic_builder.io.symbolTT_info(0)
  encoder.io.of_new_state <> of_dic_builder.io.new_state(0)

  // ml lookup
  ml_dic_builder.io.symbol_info(0) <> encoder.io.ml_symbol_info
  ml_dic_builder.io.state_table_idx(0) := encoder.io.ml_state_table_idx
  encoder.io.ml_comp_trans_table <> ml_dic_builder.io.symbolTT_info(0)
  encoder.io.ml_new_state <> ml_dic_builder.io.new_state(0)

  val writer = Module(new EntropyCompressorMemwriter(writeCmpFlag=false))
  io.l2if.seq_writer <> writer.io.l2io
  writer.io.memwrites_in <> encoder.io.memwrites_out
  writer.io.decompress_dest_info.valid := io.dst_info.valid
  io.dst_info.ready := writer.io.decompress_dest_info.ready
  writer.io.decompress_dest_info.bits.op := io.dst_info.bits.op
  writer.io.decompress_dest_info.bits.cmpflag := io.dst_info.bits.cmpflag


  val dispatched_src_cnt = RegInit(0.U(64.W))
  when (insert_src_fire.fire) {
    dispatched_src_cnt := dispatched_src_cnt + 1.U
  }

  val done = (dispatched_src_cnt === writer.io.bufs_completed) && writer.io.no_writes_inflight
  io.busy := !done

  val track_bytes_written = RegInit(0.U(64.W))
  when (encoder.io.memwrites_out.fire) {
    track_bytes_written := track_bytes_written + encoder.io.memwrites_out.bits.validbytes
  }

  val prev_done = RegNext(done)
  val should_fire_bytes_written = RegInit(false.B)

  when (done && !prev_done) {
    should_fire_bytes_written := true.B
  }

  when (io.bytes_written.fire) {
    should_fire_bytes_written := false.B
  }

  io.bytes_written.bits := track_bytes_written
  io.bytes_written.valid := should_fire_bytes_written

  when (io.bytes_written.fire) {
    track_bytes_written := 0.U
  }

// cmd_router.io.no_memops_inflight := writer.io.no_writes_inflight
// cmd_router.io.write_complete_stream_cnt := writer.io.bufs_completed

// val done = (dispatched_src_cnt === controller.io.bufs_completed) && hdr_writer.io.no_writes_inflight
// io.busy := !done
}
