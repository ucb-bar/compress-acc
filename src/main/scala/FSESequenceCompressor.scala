package compressacc

import chisel3._
import chisel3.util._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheArbiter}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._

class FSESequenceCompressor(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(
  opcodes=opcodes, nPTWPorts=3) {

  override lazy val module = new FSESequenceCompressorImp(this)

  val l2_src_reader = LazyModule(new L2MemHelper("[l2_src_reader]", numOutstandingReqs=32))
  tlNode := TLWidthWidget(32) := l2_src_reader.masterNode

  val l2_src2_reader = LazyModule(new L2MemHelper("[l2_src2_reader]", numOutstandingReqs=32))
  tlNode := TLWidthWidget(32) := l2_src2_reader.masterNode

  val l2_writer = LazyModule(new L2MemHelper("[l2_writer]", numOutstandingReqs=32))
  tlNode := TLWidthWidget(32) := l2_writer.masterNode
}

class FSESequenceCompressorImp(outer: FSESequenceCompressor)
  extends LazyRoCCModuleImp(outer) with MemoryOpConstants {

  // tie up some wires
  io.mem.req.valid := false.B
  io.mem.s1_kill := false.B
  io.mem.s2_kill := false.B
  io.mem.keep_clock_enabled := true.B
  io.interrupt := false.B
  io.busy := false.B

  //////////////////////////////////////////////////////////////////////////

  // input : nbseq, input stream
  // output : sequence header | ll table | of table | ml table | compressed sequences
  // - ZSTD_entropyCompressSeqStore_internal
  // - ZSTD_buildSequenceStatistics
  // - ZSTD_encodeSequences_body
  // - ZSTD_seqToCodes(seqStorePtr)


  val cmd_que_depth = 4
  val cmd_router = Module(new FSESequenceCompressorCommandRouter(cmd_que_depth))
  cmd_router.io.rocc_in <> io.cmd
  io.resp <> cmd_router.io.rocc_out

  val src_memloader = Module(new MemLoader)
  outer.l2_src_reader.module.io.userif <> src_memloader.io.l2helperUser
  src_memloader.io.src_info <> cmd_router.io.src_info

  val seq_to_code_converter = Module(new FSESequenceToCodeConverter())
  seq_to_code_converter.io.src_stream <> src_memloader.io.consumer

  val ll_dic_builder = Module(new FSECompressorDicBuilder(printInfo="LL",
                                                          interleave_cnt=1,
                                                          as_zstd_submodule=true,
                                                          max_symbol_value=35,
                                                          max_table_log=9,
                                                          predefined_table_log=6,
                                                          mark_end_of_header=true))
  ll_dic_builder.io.nb_seq <> cmd_router.io.ll_nbseq_info
  ll_dic_builder.io.ll_stream <> seq_to_code_converter.io.ll_consumer

  val of_dic_builder = Module(new FSECompressorDicBuilder(printInfo="OF",
                                                          interleave_cnt=1,
                                                          as_zstd_submodule=true,
                                                          max_symbol_value=31,
                                                          max_table_log=8,
                                                          predefined_table_log=5,
                                                          mark_end_of_header=true))
  of_dic_builder.io.nb_seq <> cmd_router.io.of_nbseq_info
  of_dic_builder.io.ll_stream <> seq_to_code_converter.io.of_consumer

  val ml_dic_builder = Module(new FSECompressorDicBuilder(printInfo="ML",
                                                          interleave_cnt=1,
                                                          as_zstd_submodule=true,
                                                          max_symbol_value=52,
                                                          max_table_log=9,
                                                          predefined_table_log=6,
                                                          mark_end_of_header=true))
  ml_dic_builder.io.nb_seq <> cmd_router.io.ml_nbseq_info
  ml_dic_builder.io.ll_stream <> seq_to_code_converter.io.ml_consumer

  val src_memloader2 = Module(new ReverseMemLoader)
  outer.l2_src2_reader.module.io.userif <> src_memloader2.io.l2helperUser
  src_memloader2.io.src_info <> cmd_router.io.src_info2

  val encoder = Module(new FSESequenceCompressorEncoder)
  encoder.io.src_stream <> src_memloader2.io.consumer
  encoder.io.nbseq <> cmd_router.io.enc_nbseq_info

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

  val writer = Module(new EntropyCompressorMemwriter(writeCmpFlag=true))
  outer.l2_writer.module.io.userif <> writer.io.l2io
  writer.io.decompress_dest_info <> cmd_router.io.dst_info
  writer.io.memwrites_in <> encoder.io.memwrites_out

  cmd_router.io.no_memops_inflight := writer.io.no_writes_inflight
  cmd_router.io.write_complete_stream_cnt := writer.io.bufs_completed

  ///////////////////////////////////////////////////////////////////////////

  // Boilerplate code for l2 mem helper
  outer.l2_src_reader.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_src_reader.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_src_reader.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(0) <> outer.l2_src_reader.module.io.ptw

  outer.l2_src2_reader.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_src2_reader.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_src2_reader.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(1) <> outer.l2_src2_reader.module.io.ptw

  outer.l2_writer.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_writer.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_writer.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(2) <> outer.l2_writer.module.io.ptw
}

class WithFSESequenceCompressor extends Config ((site, here, up) => {
  case CompressAccelTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case BuildRoCC => Seq(
    (p: Parameters) => {
      val fse_sequence_compressor = LazyModule.apply(new FSESequenceCompressor(OpcodeSet.custom2)(p))
      fse_sequence_compressor
    }
  )
})
