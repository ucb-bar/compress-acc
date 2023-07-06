package compressacc

import chisel3._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheArbiter}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._




case class ZstdCompressorConfig (
  queDepth: Int = 4,
)

case object ZstdCompressorKey extends Field[Option[ZstdCompressorConfig]](None)

trait HasZstdCompressorParams {
  implicit val p: Parameters
  val zstdCompParams = p(ZstdCompressorKey).get
  val queDepth = zstdCompParams.queDepth
}

abstract class ZstdCompressorModule(implicit val p: Parameters)
  extends Module with HasZstdCompressorParams


class ZstdCompressor(opcodes: OpcodeSet)(implicit p: Parameters) 
  extends LazyRoCC(opcodes = opcodes, nPTWPorts = 18) with HasZstdCompressorParams {

  override lazy val module = new ZstdCompressorImp(this)

  val tapeout = p(HyperscaleSoCTapeout)

  val l2_fhdr_writer = if (!tapeout) {
    LazyModule(new L2MemHelperLatencyInjection("[fhdr_writer]", numOutstandingReqs=4, printWriteBytes=true))
  } else {
    LazyModule(new L2MemHelper("[fhdr_writer]", numOutstandingReqs=4, printWriteBytes=true))
  }
  tlNode := l2_fhdr_writer.masterNode

  val l2_bhdr_writer = if (!tapeout) {
    LazyModule(new L2MemHelperLatencyInjection("[bhdr_writer]", numOutstandingReqs=4, printWriteBytes=true))
  } else {
    LazyModule(new L2MemHelper("[bhdr_writer]", numOutstandingReqs=4, printWriteBytes=true))
  }
  tlNode := l2_bhdr_writer.masterNode

  val l2_mf_reader = if (!tapeout) {
    LazyModule(new L2MemHelperLatencyInjection("[mf_reader]", numOutstandingReqs=32))
  } else {
    LazyModule(new L2MemHelper("[mf_reader]", numOutstandingReqs=32))
  }
  tlNode := l2_mf_reader.masterNode

  val l2_mf_seqwriter = if (!tapeout) {
    LazyModule(new L2MemHelperLatencyInjection(printInfo="[mf_seqwriter]", numOutstandingReqs=32, queueRequests=true, queueResponses=true, printWriteBytes=true))
  } else {
    LazyModule(new L2MemHelper(printInfo="[mf_seqwriter]", numOutstandingReqs=32, queueRequests=true, queueResponses=true, printWriteBytes=true))
  }
  tlNode := l2_mf_seqwriter.masterNode

  val l2_mf_litwriter = if (!tapeout) {
    LazyModule(new L2MemHelperLatencyInjection(printInfo="[mf_litwriter]", numOutstandingReqs=32, queueRequests=true, queueResponses=true))
  } else {
    LazyModule(new L2MemHelper(printInfo="[mf_litwriter]", numOutstandingReqs=32, queueRequests=true, queueResponses=true))
  }
  tlNode := l2_mf_litwriter.masterNode

  val l2_huf_lit_reader = if (!tapeout) {
    LazyModule(new L2MemHelperLatencyInjection(printInfo="[huf_lit_reader]", numOutstandingReqs=32))
  } else {
    LazyModule(new L2MemHelper(printInfo="[huf_lit_reader]", numOutstandingReqs=32))
  }
  tlNode := l2_huf_lit_reader.masterNode

  val l2_huf_dic_reader = if (!tapeout) {
    LazyModule(new L2MemHelperLatencyInjection(printInfo="[huf_dic_reader]", numOutstandingReqs=32))
  } else {
    LazyModule(new L2MemHelper(printInfo="[huf_dic_reader]", numOutstandingReqs=32))
  }
  tlNode := l2_huf_dic_reader.masterNode

  val l2_huf_dic_writer = if (!tapeout) {
    LazyModule(new L2MemHelperLatencyInjection(printInfo="[huf_dic_writer]", numOutstandingReqs=32, printWriteBytes=true))
  } else {
    LazyModule(new L2MemHelper(printInfo="[huf_dic_writer]", numOutstandingReqs=32, printWriteBytes=true))
  }
  tlNode := l2_huf_dic_writer.masterNode

  val l2_huf_hdr_writer = if (!tapeout) {
    LazyModule(new L2MemHelperLatencyInjection(printInfo="[huf_hdr_writer]", numOutstandingReqs=4, printWriteBytes=true))
  } else {
    LazyModule(new L2MemHelper(printInfo="[huf_hdr_writer]", numOutstandingReqs=4, printWriteBytes=true))
  }
  tlNode := l2_huf_hdr_writer.masterNode

  val l2_huf_jt_writer = if (!tapeout) {
    LazyModule(new L2MemHelperLatencyInjection(printInfo="[huf_jt_writer]", numOutstandingReqs=4, printWriteBytes=true))
  } else {
    LazyModule(new L2MemHelper(printInfo="[huf_jt_writer]", numOutstandingReqs=4, printWriteBytes=true))
  }
  tlNode := l2_huf_jt_writer.masterNode

  val l2_huf_lit_writer = if (!tapeout) {
    LazyModule(new L2MemHelperLatencyInjection(printInfo="[huf_lit_writer]", numOutstandingReqs=32, printWriteBytes=true))
  } else {
    LazyModule(new L2MemHelper(printInfo="[huf_lit_writer]", numOutstandingReqs=32, printWriteBytes=true))
  }
  tlNode := l2_huf_lit_writer.masterNode

  val l2_seq_reader = if (!tapeout) {
    LazyModule(new L2MemHelperLatencyInjection(printInfo="[seq_reader]", numOutstandingReqs=32))
  } else {
    LazyModule(new L2MemHelper(printInfo="[seq_reader]", numOutstandingReqs=32))
  }
  tlNode := l2_seq_reader.masterNode

  val l2_seq_reader2 = if (!tapeout) {
    LazyModule(new L2MemHelperLatencyInjection(printInfo="[seq_reader2]", numOutstandingReqs=32))
  } else {
    LazyModule(new L2MemHelper(printInfo="[seq_reader2]", numOutstandingReqs=32))
  }
  tlNode := l2_seq_reader2.masterNode

  val l2_seq_writer = if (!tapeout) {
    LazyModule(new L2MemHelperLatencyInjection(printInfo="[seq_writer]", numOutstandingReqs=32, printWriteBytes=true))
  } else {
    LazyModule(new L2MemHelper(printInfo="[seq_writer]", numOutstandingReqs=32, printWriteBytes=true))
  }
  tlNode := l2_seq_writer.masterNode

  val l2_raw_block_reader = if (!tapeout) {
    LazyModule(new L2MemHelperLatencyInjection(printInfo="[raw_block_reader]", numOutstandingReqs=32))
  } else {
    LazyModule(new L2MemHelper(printInfo="[raw_block_reader]", numOutstandingReqs=32))
  }
  tlNode := l2_raw_block_reader.masterNode

  val l2_raw_block_writer = if (!tapeout) {
    LazyModule(new L2MemHelperLatencyInjection(printInfo="[raw_block_writer]", numOutstandingReqs=32, printWriteBytes=true))
  } else {
    LazyModule(new L2MemHelper(printInfo="[raw_block_writer]", numOutstandingReqs=32, printWriteBytes=true))
  }
  tlNode := l2_raw_block_writer.masterNode

  val l2_raw_lit_reader = if (!tapeout) {
    LazyModule(new L2MemHelperLatencyInjection(printInfo="[raw_lit_reader]", numOutstandingReqs=32))
  } else {
    LazyModule(new L2MemHelper(printInfo="[raw_lit_reader]", numOutstandingReqs=32))
  }
  tlNode := l2_raw_lit_reader.masterNode

  val l2_raw_lit_writer = if (!tapeout) {
    LazyModule(new L2MemHelperLatencyInjection(printInfo="[raw_lit_writer]", numOutstandingReqs=32, printWriteBytes=true))
  } else {
    LazyModule(new L2MemHelper(printInfo="[raw_lit_writer]", numOutstandingReqs=32, printWriteBytes=true))
  }
  tlNode := l2_raw_lit_writer.masterNode
}

class ZstdCompressorImp(outer: ZstdCompressor)(implicit p: Parameters) 
  extends LazyRoCCModuleImp(outer) 
  with MemoryOpConstants 
  with HasZstdCompressorParams {

  io.mem.req.valid := false.B
  io.mem.s1_kill := false.B
  io.mem.s2_kill := false.B
  io.mem.keep_clock_enabled := true.B
  io.interrupt := false.B
  io.busy := false.B
  ////////////////////////////////////////////////////////////////////////////
  // Don't touch above this line
  ////////////////////////////////////////////////////////////////////////////

  val removeSnappy = p(RemoveSnappyFromMergedAccelerator)

  val cmd_router = Module(new ZstdCompressorCommandRouter)
  cmd_router.io.rocc_in <> io.cmd
  io.resp <> cmd_router.io.rocc_out

  val controller = Module(new CompressorController)
  controller.io.ALGORITHM := cmd_router.io.ALGORITHM
  controller.io.SNAPPY_MAX_OFFSET_ALLOWED := cmd_router.io.SNAPPY_MAX_OFFSET_ALLOWED
  controller.io.SNAPPY_RUNTIME_HT_NUM_ENTRIES_LOG2 := cmd_router.io.SNAPPY_RUNTIME_HT_NUM_ENTRIES_LOG2
  controller.io.src_info <> cmd_router.io.src_info
  controller.io.dst_info <> cmd_router.io.dst_info
  controller.io.buff_info <> cmd_router.io.buff_info
  controller.io.clevel_info <> cmd_router.io.clevel_info
  cmd_router.io.zstd_finished_cnt <> controller.io.zstd_finished_cnt
  cmd_router.io.snappy_finished_cnt <> controller.io.snappy_finished_cnt
  outer.l2_fhdr_writer.module.io.userif <> controller.io.zstd_control.l2io.fhdr_l2userif
  outer.l2_bhdr_writer.module.io.userif <> controller.io.zstd_control.l2io.bhdr_l2userif

  val matchfinder = Module(new ZstdMatchFinder(removeSnappy=removeSnappy))
  outer.l2_mf_reader.module.io.userif <> matchfinder.io.l2io.memloader_userif
  outer.l2_mf_seqwriter.module.io.userif <> matchfinder.io.l2io.seq_memwriter_userif
  outer.l2_mf_litwriter.module.io.userif <> matchfinder.io.l2io.lit_memwriter_userif
  matchfinder.io.src <> controller.io.shared_control.mf_src
  matchfinder.io.dst <> controller.io.shared_control.mf_dst
  matchfinder.io.MAX_OFFSET_ALLOWED := cmd_router.io.SNAPPY_MAX_OFFSET_ALLOWED
  matchfinder.io.RUNTIME_HT_NUM_ENTRIES_LOG2 := cmd_router.io.SNAPPY_RUNTIME_HT_NUM_ENTRIES_LOG2
  matchfinder.io.ALGORITHM := cmd_router.io.ALGORITHM
  controller.io.shared_control.mf_buff_consumed <> matchfinder.io.buff_consumed

  val lit_compressor = Module(new ZstdLiteralEncoder)
  outer.l2_huf_lit_reader.module.io.userif <> lit_compressor.io.l2if.lit_reader
  outer.l2_huf_dic_reader.module.io.userif <> lit_compressor.io.l2if.dic_reader
  outer.l2_huf_dic_writer.module.io.userif <> lit_compressor.io.l2if.dic_writer
  outer.l2_huf_hdr_writer.module.io.userif <> lit_compressor.io.l2if.hdr_writer
  outer.l2_huf_jt_writer.module.io.userif <> lit_compressor.io.l2if.jt_writer
  outer.l2_huf_lit_writer.module.io.userif <> lit_compressor.io.l2if.lit_writer
  lit_compressor.io.src_info <> controller.io.zstd_control.litcpy_src
  lit_compressor.io.src_info2 <> controller.io.zstd_control.litcpy_src2
  lit_compressor.io.dst_info <> controller.io.zstd_control.litcpy_dst
  controller.io.zstd_control.litbytes_written <> lit_compressor.io.bytes_written
// ??? := lit_compressor.io.busy


  val raw_lit_encoder = Module(new ZstdRawLiteralEncoder)
  outer.l2_raw_lit_reader.module.io.userif <> raw_lit_encoder.io.l2io_read
  outer.l2_raw_lit_writer.module.io.userif <> raw_lit_encoder.io.l2io_write
  raw_lit_encoder.io.src_info <> controller.io.zstd_control.raw_lit_src
  raw_lit_encoder.io.dst_info <> controller.io.zstd_control.raw_lit_dst
  controller.io.zstd_control.raw_litbytes_written <> raw_lit_encoder.io.bytes_written

  val raw_block_encoder = Module(new ZstdRawBlockMemcopy)
  outer.l2_raw_block_reader.module.io.userif <> raw_block_encoder.io.l2if.reader
  outer.l2_raw_block_writer.module.io.userif <> raw_block_encoder.io.l2if.writer
  raw_block_encoder.io.src_info <> controller.io.zstd_control.raw_block_src
  raw_block_encoder.io.dst_info <> controller.io.zstd_control.raw_block_dst
  controller.io.zstd_control.raw_blockbytes_written <> raw_block_encoder.io.bytes_written

  val seq_compressor = Module(new ZstdSequenceEncoder)
  outer.l2_seq_reader.module.io.userif <> seq_compressor.io.l2if.seq_reader
  outer.l2_seq_reader2.module.io.userif <> seq_compressor.io.l2if.seq_reader2
  outer.l2_seq_writer.module.io.userif <> seq_compressor.io.l2if.seq_writer
  seq_compressor.io.src_info <> controller.io.zstd_control.seqcpy_src
  seq_compressor.io.dst_info <> controller.io.zstd_control.seqcpy_dst
  controller.io.zstd_control.seqbytes_written <> seq_compressor.io.bytes_written


  ////////////////////////////////////////////////////////////////////////////
  // Latency Injection
  ////////////////////////////////////////////////////////////////////////////


  val tapeout = p(HyperscaleSoCTapeOut)

  if (!tapeout) {
    outer.l2_fhdr_writer.module.io.latency_inject_cycles := cmd_router.io.LATENCY_INJECTION_CYCLES
    outer.l2_bhdr_writer.module.io.latency_inject_cycles := cmd_router.io.LATENCY_INJECTION_CYCLES

    outer.l2_mf_reader.module.io.latency_inject_cycles := cmd_router.io.LATENCY_INJECTION_CYCLES
    outer.l2_mf_seqwriter.module.io.latency_inject_cycles := Mux(cmd_router.io.HAS_INTERMEDIATE_CACHE, 0.U, cmd_router.io.LATENCY_INJECTION_CYCLES)
    outer.l2_mf_litwriter.module.io.latency_inject_cycles := Mux(cmd_router.io.HAS_INTERMEDIATE_CACHE, 0.U, cmd_router.io.LATENCY_INJECTION_CYCLES)

    outer.l2_huf_lit_reader.module.io.latency_inject_cycles := Mux(cmd_router.io.HAS_INTERMEDIATE_CACHE, 0.U, cmd_router.io.LATENCY_INJECTION_CYCLES)
    outer.l2_huf_dic_reader.module.io.latency_inject_cycles := Mux(cmd_router.io.HAS_INTERMEDIATE_CACHE, 0.U, cmd_router.io.LATENCY_INJECTION_CYCLES)
    outer.l2_huf_dic_writer.module.io.latency_inject_cycles := cmd_router.io.LATENCY_INJECTION_CYCLES
    outer.l2_huf_hdr_writer.module.io.latency_inject_cycles := cmd_router.io.LATENCY_INJECTION_CYCLES
    outer.l2_huf_jt_writer.module.io.latency_inject_cycles := cmd_router.io.LATENCY_INJECTION_CYCLES
    outer.l2_huf_lit_writer.module.io.latency_inject_cycles := cmd_router.io.LATENCY_INJECTION_CYCLES

    outer.l2_seq_reader.module.io.latency_inject_cycles := Mux(cmd_router.io.HAS_INTERMEDIATE_CACHE, 0.U, cmd_router.io.LATENCY_INJECTION_CYCLES)
    outer.l2_seq_reader2.module.io.latency_inject_cycles := Mux(cmd_router.io.HAS_INTERMEDIATE_CACHE, 0.U, cmd_router.io.LATENCY_INJECTION_CYCLES)
    outer.l2_seq_writer.module.io.latency_inject_cycles := cmd_router.io.LATENCY_INJECTION_CYCLES

    outer.l2_raw_block_reader.module.io.latency_inject_cycles := Mux(cmd_router.io.HAS_INTERMEDIATE_CACHE, 0.U, cmd_router.io.LATENCY_INJECTION_CYCLES)
    outer.l2_raw_block_writer.module.io.latency_inject_cycles := cmd_router.io.LATENCY_INJECTION_CYCLES

    outer.l2_raw_lit_reader.module.io.latency_inject_cycles := Mux(cmd_router.io.HAS_INTERMEDIATE_CACHE, 0.U, cmd_router.io.LATENCY_INJECTION_CYCLES)
    outer.l2_raw_lit_writer.module.io.latency_inject_cycles := cmd_router.io.LATENCY_INJECTION_CYCLES
  }



  ////////////////////////////////////////////////////////////////////////////
  // Boilerplate code for l2 mem helper
  ////////////////////////////////////////////////////////////////////////////
  outer.l2_fhdr_writer.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_fhdr_writer.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_fhdr_writer.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(0) <> outer.l2_fhdr_writer.module.io.ptw

  outer.l2_bhdr_writer.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_bhdr_writer.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_bhdr_writer.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(1) <> outer.l2_bhdr_writer.module.io.ptw

  outer.l2_mf_reader.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_mf_reader.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_mf_reader.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(2) <> outer.l2_mf_reader.module.io.ptw

  outer.l2_mf_seqwriter.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_mf_seqwriter.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_mf_seqwriter.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(3) <> outer.l2_mf_seqwriter.module.io.ptw

  outer.l2_mf_litwriter.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_mf_litwriter.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_mf_litwriter.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(4) <> outer.l2_mf_litwriter.module.io.ptw

  outer.l2_huf_lit_reader.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_huf_lit_reader.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_huf_lit_reader.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(5) <> outer.l2_huf_lit_reader.module.io.ptw

  outer.l2_huf_dic_reader.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_huf_dic_reader.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_huf_dic_reader.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(6) <> outer.l2_huf_dic_reader.module.io.ptw

  outer.l2_huf_dic_writer.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_huf_dic_writer.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_huf_dic_writer.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(7) <> outer.l2_huf_dic_writer.module.io.ptw

  outer.l2_huf_hdr_writer.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_huf_hdr_writer.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_huf_hdr_writer.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(8) <> outer.l2_huf_hdr_writer.module.io.ptw

  outer.l2_huf_jt_writer.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_huf_jt_writer.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_huf_jt_writer.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(9) <> outer.l2_huf_jt_writer.module.io.ptw

  outer.l2_huf_lit_writer.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_huf_lit_writer.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_huf_lit_writer.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(10) <> outer.l2_huf_lit_writer.module.io.ptw

  outer.l2_seq_reader.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_seq_reader.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_seq_reader.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(11) <> outer.l2_seq_reader.module.io.ptw

  outer.l2_seq_reader2.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_seq_reader2.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_seq_reader2.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(12) <> outer.l2_seq_reader2.module.io.ptw

  outer.l2_seq_writer.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_seq_writer.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_seq_writer.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(13) <> outer.l2_seq_writer.module.io.ptw

  outer.l2_raw_block_reader.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_raw_block_reader.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_raw_block_reader.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(14) <> outer.l2_raw_block_reader.module.io.ptw

  outer.l2_raw_block_writer.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_raw_block_writer.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_raw_block_writer.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(15) <> outer.l2_raw_block_writer.module.io.ptw

  outer.l2_raw_lit_reader.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_raw_lit_reader.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_raw_lit_reader.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(16) <> outer.l2_raw_lit_reader.module.io.ptw

  outer.l2_raw_lit_writer.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_raw_lit_writer.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_raw_lit_writer.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(17) <> outer.l2_raw_lit_writer.module.io.ptw
}
