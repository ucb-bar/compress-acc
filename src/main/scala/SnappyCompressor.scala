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

class SnappyCompressor(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(
    opcodes = opcodes, nPTWPorts = 2) {
  override lazy val module = new SnappyCompressorImp(this)

  val mem_comp_ireader = LazyModule(new L2MemHelper("[mem_comp_ireader]", numOutstandingReqs=32))

  if (p(CompressAccelLatencyInjectEnable)) {
    println(s"latency injection ON for [mem_comp_ireader]: adding ${p(CompressAccelLatencyInjectCycles)} cycles")
    tlNode := TLWidthWidget(32) := TLBuffer.chainNode(p(CompressAccelLatencyInjectCycles)) := mem_comp_ireader.masterNode
  } else {
    println(s"latency injection OFF for [mem_comp_ireader] due to CompressAccelLatencyInjectEnable: ${p(CompressAccelLatencyInjectEnable)}")
    tlNode := TLWidthWidget(32) := mem_comp_ireader.masterNode
  }

  val mem_comp_writer = LazyModule(new L2MemHelper(printInfo="[m_comp_writer]", numOutstandingReqs=32, queueRequests=true, queueResponses=true, printWriteBytes=true))

  if (p(CompressAccelLatencyInjectEnable)) {
    println(s"latency injection ON for [mem_comp_writer]: adding ${p(CompressAccelLatencyInjectCycles)} cycles")
    tlNode := TLWidthWidget(32) := TLBuffer.chainNode(p(CompressAccelLatencyInjectCycles)) := mem_comp_writer.masterNode
  } else {
    println(s"latency injection OFF for [mem_comp_writer] due to CompressAccelLatencyInjectEnable: ${p(CompressAccelLatencyInjectEnable)}")
    tlNode := TLWidthWidget(32) := mem_comp_writer.masterNode
  }
}

class SnappyCompressorImp(outer: SnappyCompressor)(implicit p: Parameters) extends LazyRoCCModuleImp(outer)
with MemoryOpConstants {

  io.interrupt := false.B

  val cmd_router = Module(new SnappyCompressorCommandRouter)
  cmd_router.io.rocc_in <> io.cmd
  io.resp <> cmd_router.io.rocc_out

  io.mem.req.valid := false.B
  io.mem.s1_kill := false.B
  io.mem.s2_kill := false.B
  io.mem.keep_clock_enabled := true.B


  val memloader = Module(new LZ77HashMatcherMemLoader)
  outer.mem_comp_ireader.module.io.userif <> memloader.io.l2helperUser
  memloader.io.src_info <> cmd_router.io.compress_src_info

  val lz77hashmatcher = Module(new LZ77HashMatcher)
  lz77hashmatcher.io.MAX_OFFSET_ALLOWED := cmd_router.io.MAX_OFFSET_ALLOWED
  lz77hashmatcher.io.RUNTIME_HT_NUM_ENTRIES_LOG2 := cmd_router.io.RUNTIME_HT_NUM_ENTRIES_LOG2
  lz77hashmatcher.io.write_snappy_header := true.B

  lz77hashmatcher.io.memloader_in <> memloader.io.consumer
  lz77hashmatcher.io.memloader_optional_hbsram_in <> memloader.io.optional_hbsram_write
  lz77hashmatcher.io.src_info <> cmd_router.io.compress_src_info2

  when (lz77hashmatcher.io.memwrites_out.fire) {
    CompressAccelLogger.logInfo("LZ77-MEMWRITEFIRE: data: 0x%x, validbytes: %d, EOM: %d, is_copy: %d, length_header: %d\n",
      lz77hashmatcher.io.memwrites_out.bits.data,
      lz77hashmatcher.io.memwrites_out.bits.validbytes,
      lz77hashmatcher.io.memwrites_out.bits.end_of_message,
      lz77hashmatcher.io.memwrites_out.bits.is_copy,
      lz77hashmatcher.io.memwrites_out.bits.length_header
    )
  }

  val compress_copy_expander = Module(new SnappyCompressCopyExpander)
  compress_copy_expander.io.memwrites_in <> lz77hashmatcher.io.memwrites_out

  when (compress_copy_expander.io.memwrites_out.fire) {
    CompressAccelLogger.logInfo("CEXP-MEMWRITEFIRE: data: 0x%x, validbytes: %d, EOM: %d, is_copy: %d, length_header: %d\n",
      compress_copy_expander.io.memwrites_out.bits.data,
      compress_copy_expander.io.memwrites_out.bits.validbytes,
      compress_copy_expander.io.memwrites_out.bits.end_of_message,
      compress_copy_expander.io.memwrites_out.bits.is_copy,
      compress_copy_expander.io.memwrites_out.bits.length_header
    )
  }

  val compress_litlen_injector = Module(new SnappyCompressLitLenInjector)
  compress_litlen_injector.io.memwrites_in <> compress_copy_expander.io.memwrites_out

  when (compress_litlen_injector.io.memwrites_out.fire) {
    CompressAccelLogger.logInfo("CLLI-MEMWRITEFIRE: data: 0x%x, validbytes: %d, EOM: %d, is_copy: %d, length_header: %d\n",
      compress_litlen_injector.io.memwrites_out.bits.data,
      compress_litlen_injector.io.memwrites_out.bits.validbytes,
      compress_litlen_injector.io.memwrites_out.bits.end_of_message,
      compress_litlen_injector.io.memwrites_out.bits.is_copy,
      compress_litlen_injector.io.memwrites_out.bits.length_header
    )
  }

  val memwriter = Module(new SnappyCompressorMemwriter)
  memwriter.io.memwrites_in <> compress_litlen_injector.io.memwrites_out
  outer.mem_comp_writer.module.io.userif <> memwriter.io.l2io
  memwriter.io.compress_dest_info <> cmd_router.io.compress_dest_info
  cmd_router.io.bufs_completed := memwriter.io.bufs_completed
  cmd_router.io.no_writes_inflight := memwriter.io.no_writes_inflight

  outer.mem_comp_ireader.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_comp_ireader.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_comp_ireader.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(0) <> outer.mem_comp_ireader.module.io.ptw

  outer.mem_comp_writer.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_comp_writer.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_comp_writer.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(1) <> outer.mem_comp_writer.module.io.ptw

  io.busy := false.B
}

