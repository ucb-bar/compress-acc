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


class FSECompressor(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(
  opcodes=opcodes, nPTWPorts=3) {
    override lazy val module = new FSECompressorImp(this)

    val l2_src_reader = LazyModule(new L2MemHelper("[l2_src_reader]", numOutstandingReqs=32))
    tlNode := l2_src_reader.masterNode

    val l2_src_reader2 = LazyModule(new L2MemHelper("[l2_src_reader2]", numOutstandingReqs=32))
    tlNode := l2_src_reader2.masterNode

    val l2_dst_writer = LazyModule(new L2MemHelper("[l2_dst_writer]", numOutstandingReqs=32))
    tlNode := l2_dst_writer.masterNode
}

class FSECompressorImp(outer: FSECompressor)(implicit p: Parameters)
  extends LazyRoCCModuleImp(outer) with MemoryOpConstants {

  // tie up some wires
  io.mem.req.valid := false.B
  io.mem.s1_kill := false.B
  io.mem.s2_kill := false.B
  io.mem.keep_clock_enabled := true.B
  io.interrupt := false.B
  io.busy := false.B

  //////////////////////////////////////////////////////////////////////////

  val cmd_que_depth = p(FSECompressCmdQueDepth)
  val interleave_cnt = p(FSECompressInterleaveCnt)

  val cmd_router = Module(new FSECompressorCommandRouter(cmd_que_depth))
  cmd_router.io.rocc_in <> io.cmd
  io.resp <> cmd_router.io.rocc_out

  val src_memloader = Module(new MemLoader)
  outer.l2_src_reader.module.io.userif <> src_memloader.io.l2helperUser
  src_memloader.io.src_info <> cmd_router.io.src_info

  val src_memloader2 = Module(new ReverseMemLoader)
  outer.l2_src_reader2.module.io.userif <> src_memloader2.io.l2helperUser
  src_memloader2.io.src_info <> cmd_router.io.src_info2

  val dic_builder = Module(new FSECompressorDicBuilder(printInfo="StandAlone",
                                                       interleave_cnt=interleave_cnt,
                                                       as_zstd_submodule=false,
                                                       max_symbol_value=12,
                                                       max_table_log=6,
                                                       predefined_table_log=12))
  dic_builder.io.nb_seq <> cmd_router.io.nbseq_info
  dic_builder.io.ll_stream <> src_memloader.io.consumer
  dic_builder.io.predefined_mode.ready := true.B

  val encoder = Module(new FSECompressorEncoder(cmd_que_depth, interleave_cnt))
  encoder.io.src_stream <> src_memloader2.io.consumer
  encoder.io.table_log <> dic_builder.io.ll_table_log
  encoder.io.header_writes <> dic_builder.io.header_writes
  dic_builder.io.lookup_done <> encoder.io.lookup_done

  for (i <- 0 until interleave_cnt) {
    dic_builder.io.symbol_info(i) <> encoder.io.symbol_info(i)
    encoder.io.comp_trans_table(i) <> dic_builder.io.symbolTT_info(i)

    dic_builder.io.state_table_idx(i) := encoder.io.state_table_idx(i)
    encoder.io.new_state(i) <> dic_builder.io.new_state(i)
  }

  val dst_writer = Module(new EntropyCompressorMemwriter(writeCmpFlag=true))
  outer.l2_dst_writer.module.io.userif <> dst_writer.io.l2io
  dst_writer.io.decompress_dest_info <> cmd_router.io.dst_info
  dst_writer.io.memwrites_in <> encoder.io.memwrites_out

  cmd_router.io.no_memops_inflight := dst_writer.io.no_writes_inflight
  cmd_router.io.write_complete_stream_cnt := dst_writer.io.bufs_completed

  ///////////////////////////////////////////////////////////////////////////
  // Boilerplate code for l2 mem helper
  outer.l2_src_reader.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_src_reader.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_src_reader.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(0) <> outer.l2_src_reader.module.io.ptw

  outer.l2_src_reader2.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_src_reader2.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_src_reader2.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(1) <> outer.l2_src_reader2.module.io.ptw

  outer.l2_dst_writer.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_dst_writer.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_dst_writer.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(2) <> outer.l2_dst_writer.module.io.ptw
}

class WithFSECompressor extends Config ((site, here, up) => {
  case CompressAccelTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case FSECompressCmdQueDepth => 4
  case FSECompressInterleaveCnt => 2
  case BuildRoCC => Seq(
    (p: Parameters) => {
      val fse_compressor = LazyModule.apply(new FSECompressor(OpcodeSet.custom2)(p))
      fse_compressor
    }
  )
})
