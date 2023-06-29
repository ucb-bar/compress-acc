package compressacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheArbiter}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._


class HufCompressor(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(
    opcodes = opcodes, nPTWPorts = 6) {
  override lazy val module = new HufCompressorImp(this)

  val l2_lit_reader = LazyModule(new L2MemHelper("[l2_lit_reader]", numOutstandingReqs=32))
  tlNode := l2_lit_reader.masterNode

  val l2_dic_reader = LazyModule(new L2MemHelper("[l2_dic_reader]", numOutstandingReqs=32))
  tlNode := l2_dic_reader.masterNode

  val l2_dic_writer = LazyModule(new L2MemHelper("[l2_dic_writer]", numOutstandingReqs=32))
  tlNode := l2_dic_writer.masterNode

  val l2_hdr_writer = LazyModule(new L2MemHelper("[l2_hdr_writer]", numOutstandingReqs=2))
  tlNode := l2_hdr_writer.masterNode

  val l2_jt_writer = LazyModule(new L2MemHelper("[l2_jt_writer]", numOutstandingReqs=2))
  tlNode := l2_jt_writer.masterNode

  val l2_lit_writer = LazyModule(new L2MemHelper("[l2_lit_writer]", numOutstandingReqs=32))
  tlNode := l2_lit_writer.masterNode
}

class HufCompressorImp(outer: HufCompressor)(implicit p: Parameters) 
  extends LazyRoCCModuleImp(outer) with MemoryOpConstants {

  // tie up some wires
  io.mem.req.valid := false.B
  io.mem.s1_kill := false.B
  io.mem.s2_kill := false.B
  io.mem.keep_clock_enabled := true.B
  io.interrupt := false.B
  io.busy := false.B

  //////////////////////////////////////////////////////////////////////////

  val cmd_que_depth = p(HufCompressCmdQueDepth)
  val unroll_cnt = p(HufCompressUnrollCnt)

  val cmd_router = Module(new HufCompressorCommandRouter(cmd_que_depth))
  cmd_router.io.rocc_in <> io.cmd
  io.resp <> cmd_router.io.rocc_out

  val controller = Module(new HufCompressorController(cmd_que_depth))
  controller.io.src_info_in <> cmd_router.io.src_info
  controller.io.dst_info_in <> cmd_router.io.dst_info

  val hdr_writer = Module(new HufCompressorMemwriter(writeCmpFlag=true))
  outer.l2_hdr_writer.module.io.userif <> hdr_writer.io.l2io
  hdr_writer.io.decompress_dest_info <> controller.io.hdr_dst_info
  hdr_writer.io.memwrites_in <> controller.io.hdr_writes
  hdr_writer.io.custom_write_value <> controller.io.total_write_bytes
  controller.io.total_write_bytes2.ready := true.B

  val jt_writer = Module(new EntropyCompressorMemwriter(writeCmpFlag=false))
  outer.l2_jt_writer.module.io.userif <> jt_writer.io.l2io
  jt_writer.io.decompress_dest_info <> controller.io.jt_dst_info
  jt_writer.io.memwrites_in <> controller.io.jt_writes

  val dic_memloader = Module(new MemLoader)
  outer.l2_dic_reader.module.io.userif <> dic_memloader.io.l2helperUser
  dic_memloader.io.src_info <> cmd_router.io.cnt_info

  val dic_builder = Module(new HufCompressorDicBuilder(cmd_que_depth, unroll_cnt))
  dic_builder.io.cnt_stream <> dic_memloader.io.consumer
  controller.io.weight_bytes <> dic_builder.io.header_written_bytes
  controller.io.header_size_info <> dic_builder.io.header_size_info
  dic_builder.io.init_dictionary <> controller.io.init_dictionary

  val dic_writer = Module(new EntropyCompressorMemwriter(writeCmpFlag=false))
  outer.l2_dic_writer.module.io.userif <> dic_writer.io.l2io
  dic_writer.io.decompress_dest_info <> controller.io.weight_dst_info
  dic_writer.io.memwrites_in <> dic_builder.io.header_writes

  val lit_memloader = Module(new ReverseMemLoader)
  outer.l2_lit_reader.module.io.userif <> lit_memloader.io.l2helperUser
  lit_memloader.io.src_info <> controller.io.lit_src_info

  val encoder = Module(new HufCompressorEncoder(cmd_que_depth, unroll_cnt))
  encoder.io.lit_stream <> lit_memloader.io.consumer
  encoder.io.dic_info <> dic_builder.io.dic_info
  dic_builder.io.symbol_info <> encoder.io.symbol_info
  controller.io.compressed_bytes <> encoder.io.compressed_bytes

  val lit_memwriter = Module(new EntropyCompressorMemwriter(writeCmpFlag=false))
  outer.l2_lit_writer.module.io.userif <> lit_memwriter.io.l2io
  lit_memwriter.io.decompress_dest_info <> controller.io.lit_dst_info
  lit_memwriter.io.memwrites_in <> encoder.io.memwrites_out

  cmd_router.io.no_memops_inflight := lit_memwriter.io.no_writes_inflight
  cmd_router.io.write_complete_stream_cnt := controller.io.bufs_completed


  ///////////////////////////////////////////////////////////////////////////

  // Boilerplate code for l2 mem helper
  outer.l2_lit_reader.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_lit_reader.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_lit_reader.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(0) <> outer.l2_lit_reader.module.io.ptw

  outer.l2_dic_reader.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_dic_reader.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_dic_reader.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(1) <> outer.l2_dic_reader.module.io.ptw

  outer.l2_dic_writer.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_dic_writer.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_dic_writer.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(2) <> outer.l2_dic_writer.module.io.ptw

  outer.l2_hdr_writer.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_hdr_writer.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_hdr_writer.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(3) <> outer.l2_hdr_writer.module.io.ptw

  outer.l2_jt_writer.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_jt_writer.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_jt_writer.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(4) <> outer.l2_jt_writer.module.io.ptw

  outer.l2_lit_writer.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_lit_writer.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_lit_writer.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(5) <> outer.l2_lit_writer.module.io.ptw
}

class WithHufCompressor extends Config ((site, here, up) => {
  case CompressAccelTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case HufCompressCmdQueDepth => 4
  case HufCompressUnrollCnt => 2
  case BuildRoCC => Seq(
    (p: Parameters) => {
      val huf_compressor = LazyModule.apply(new HufCompressor(OpcodeSet.custom2)(p))
      huf_compressor
    }
  )
})
