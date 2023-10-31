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
case object ZstdDecompressorCmdQueDepth extends Field[Int](4)
case object HufDecompressDecompAtOnce extends Field[Int](32)
case object NoSnappy extends Field[Boolean](true)

class ZstdDecompressor(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(
    opcodes = opcodes, nPTWPorts = 12) {
  override lazy val module = new ZstdDecompressorImp(this)

  val cmd_que_depth = p(ZstdDecompressorCmdQueDepth)

  val frame_decompressor = LazyModule(new ZstdFrameDecompressor(this, cmd_que_depth))



  val tapeout = p(HyperscaleSoCTapeOut)
  val roccTLNode = if (tapeout) atlNode else tlNode

  val l2_cmpflag_writer =     LazyModule(new L2MemHelper("[cmpflag_writer]", numOutstandingReqs=2))
  roccTLNode := TLBuffer.chainNode(1) := l2_cmpflag_writer.masterNode

  // For frame header
  val l2_fhdr_reader =     LazyModule(new L2MemHelper("[fhdr_reader]", numOutstandingReqs=4))
  roccTLNode := TLBuffer.chainNode(1) := l2_fhdr_reader.masterNode

  // For block header
  val l2_bhdr_reader =     LazyModule(new L2MemHelper("[bhdr_reader]", numOutstandingReqs=4))
  roccTLNode := TLBuffer.chainNode(1) := l2_bhdr_reader.masterNode

  // For Huffman
  val l2_huf_literal_reader =     LazyModule(new L2MemHelper("[huf_lit_reader]", numOutstandingReqs=32))
  roccTLNode := TLBuffer.chainNode(1) := l2_huf_literal_reader.masterNode

  val l2_huf_header_reader =     LazyModule(new L2MemHelper("[huf_hdr_reader]", numOutstandingReqs=4))
  roccTLNode := TLBuffer.chainNode(1) := l2_huf_header_reader.masterNode

  val l2_huf_literal_writer =     LazyModule(new L2MemHelper("[huf_lit_writer]", numOutstandingReqs=32))
  roccTLNode := TLBuffer.chainNode(1) := l2_huf_literal_writer.masterNode

  // For FSE
  //memloader of dt builder
  val mem_decomp_ireader_dtbuilder =     LazyModule(new L2MemHelper("[mem_decomp_ireader_dtbuilder]", numOutstandingReqs=32))
  roccTLNode := TLBuffer.chainNode(1) := mem_decomp_ireader_dtbuilder.masterNode

	//memloader of dt reader
  val mem_decomp_ireader_dtreader =     LazyModule(new L2MemHelper("[mem_decomp_ireader_dtreader]", numOutstandingReqs=32))
  roccTLNode := TLBuffer.chainNode(1) := mem_decomp_ireader_dtreader.masterNode

  // For LZ77 
  //memloader of seq executor-history lookup
  val mem_decomp_ireader_histlookup =     LazyModule(new L2MemHelper("[m_decomp_readbackref]", numOutstandingReqs=32))
  roccTLNode := TLBuffer.chainNode(1) := mem_decomp_ireader_histlookup.masterNode

	//memloader of seq executor
  val mem_decomp_ireader_seqexec =     LazyModule(new L2MemHelper("[mem_decomp_ireader_seqexec]", numOutstandingReqs=32))
  roccTLNode := TLBuffer.chainNode(1) := mem_decomp_ireader_seqexec.masterNode

	//memwriter of seq executor
  val mem_decomp_writer_seqexec =     LazyModule(new L2MemHelper(printInfo="[m_decomp_writer_seqexec]", numOutstandingReqs=32, queueRequests=true, queueResponses=true))
  roccTLNode := TLBuffer.chainNode(1) := mem_decomp_writer_seqexec.masterNode

  // For Raw and RLE blocks
  // memloader
  val mem_decomp_ireader_rawrle =    LazyModule(new L2MemHelper("[mem_decomp_ireader_rawrle]", numOutstandingReqs=32))
  roccTLNode := TLBuffer.chainNode(1) := mem_decomp_ireader_rawrle.masterNode
}

class ZstdDecompressorImp(outer: ZstdDecompressor)(implicit p: Parameters) 
  extends LazyRoCCModuleImp(outer) with MemoryOpConstants {

  io.mem.req.valid := false.B
  io.mem.s1_kill := false.B
  io.mem.s2_kill := false.B
  io.mem.keep_clock_enabled := true.B
  io.interrupt := false.B
  io.busy := false.B
  ////////////////////////////////////////////////////////////////////////////
  // Don't touch above this line
  ////////////////////////////////////////////////////////////////////////////

  val cmd_que_depth = p(ZstdDecompressorCmdQueDepth)
  val nosnappy = p(NoSnappy)

  val cmd_router = Module(new ZstdDecompressorCommandRouter(cmd_que_depth))
  cmd_router.io.rocc_in <> io.cmd
  io.resp <> cmd_router.io.rocc_out
  outer.frame_decompressor.module.io.MAX_OFFSET_ALLOWED := cmd_router.io.MAX_OFFSET_ALLOWED
  outer.frame_decompressor.module.io.ALGORITHM := cmd_router.io.ALGORITHM
  outer.frame_decompressor.module.io.snappy_decompress_src_info <> cmd_router.io.snappy_decompress_src_info
  outer.frame_decompressor.module.io.snappy_decompress_dest_info <> cmd_router.io.snappy_decompress_dest_info
  outer.frame_decompressor.module.io.snappy_decompress_dest_info_offchip <> cmd_router.io.snappy_decompress_dest_info_offchip
  cmd_router.io.snappy_bufs_completed := outer.frame_decompressor.module.io.snappy_bufs_completed
  cmd_router.io.snappy_no_writes_inflight := outer.frame_decompressor.module.io.snappy_no_writes_inflight

  

  val memloader = Module(new MemLoader(memLoaderQueDepth=2))
  outer.l2_fhdr_reader.module.io.userif <> memloader.io.l2helperUser

  outer.l2_bhdr_reader.module.io.userif <> outer.frame_decompressor.module.io.l2_bhdr_userif
  outer.l2_huf_literal_reader.module.io.userif <> outer.frame_decompressor.module.io.l2_huf_memhelpers.lit_userif
  outer.l2_huf_header_reader.module.io.userif <> outer.frame_decompressor.module.io.l2_huf_memhelpers.hdr_userif
  outer.l2_huf_literal_writer.module.io.userif <> outer.frame_decompressor.module.io.l2_huf_memhelpers.write_userif

  outer.mem_decomp_ireader_dtbuilder.module.io.userif <> outer.frame_decompressor.module.io.l2_fse_memhelpers.read_dtbuild_userif
  outer.mem_decomp_ireader_dtreader.module.io.userif <> outer.frame_decompressor.module.io.l2_fse_memhelpers.read_dtread_userif
  outer.mem_decomp_ireader_seqexec.module.io.userif <> outer.frame_decompressor.module.io.l2_fse_memhelpers.read_seqexec_userif
  outer.mem_decomp_ireader_histlookup.module.io.userif <> outer.frame_decompressor.module.io.l2_fse_memhelpers.read_histlookup_userif
//  outer.mem_decomp_writer_dtbuilder.module.io.userif <> outer.frame_decompressor.module.io.l2_fse_memhelpers.write_dtbuild_userif
  outer.mem_decomp_writer_seqexec.module.io.userif <> outer.frame_decompressor.module.io.l2_fse_memhelpers.write_seqexec_userif

  outer.mem_decomp_ireader_rawrle.module.io.userif <> outer.frame_decompressor.module.io.l2_rawrle_memhelpers.read_rawrle_userif

  val cmd_expander = Module(new ZstdDecompressorCommandExpander(cmd_que_depth))
  outer.l2_cmpflag_writer.module.io.userif <> cmd_expander.io.l2helperUser
  cmd_expander.io.src_info <> cmd_router.io.src_info
  cmd_expander.io.wksp_info <> cmd_router.io.wksp_info
  cmd_expander.io.dst_info <> cmd_router.io.dst_info
  cmd_expander.io.fhdr_stream <> memloader.io.consumer
  memloader.io.src_info <> cmd_expander.io.fhdr_info
  cmd_router.io.finished_cnt <> cmd_expander.io.decompressed_cnt

  outer.frame_decompressor.module.io.frame_content <> cmd_expander.io.frame_content
  cmd_expander.io.decompressed_frame <> outer.frame_decompressor.module.io.decompressed_frame

  ////////////////////////////////////////////////////////////////////////////
  // Latency Injection
  ////////////////////////////////////////////////////////////////////////////

  val tapeout = p(HyperscaleSoCTapeOut)


  // Boilerplate code for l2 mem helper
  outer.l2_cmpflag_writer.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_cmpflag_writer.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_cmpflag_writer.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(0) <> outer.l2_cmpflag_writer.module.io.ptw

  outer.l2_fhdr_reader.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_fhdr_reader.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_fhdr_reader.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(1) <> outer.l2_fhdr_reader.module.io.ptw

  outer.l2_bhdr_reader.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_bhdr_reader.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_bhdr_reader.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(2) <> outer.l2_bhdr_reader.module.io.ptw

  outer.l2_huf_literal_reader.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_huf_literal_reader.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_huf_literal_reader.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(3) <> outer.l2_huf_literal_reader.module.io.ptw

  outer.l2_huf_header_reader.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_huf_header_reader.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_huf_header_reader.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(4) <> outer.l2_huf_header_reader.module.io.ptw

  outer.l2_huf_literal_writer.module.io.sfence <> cmd_router.io.sfence_out
  outer.l2_huf_literal_writer.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.l2_huf_literal_writer.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(5) <> outer.l2_huf_literal_writer.module.io.ptw

  outer.mem_decomp_ireader_dtbuilder.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_decomp_ireader_dtbuilder.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_decomp_ireader_dtbuilder.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(6) <> outer.mem_decomp_ireader_dtbuilder.module.io.ptw

  outer.mem_decomp_ireader_dtreader.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_decomp_ireader_dtreader.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_decomp_ireader_dtreader.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(7) <> outer.mem_decomp_ireader_dtreader.module.io.ptw

  outer.mem_decomp_ireader_histlookup.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_decomp_ireader_histlookup.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_decomp_ireader_histlookup.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(8) <> outer.mem_decomp_ireader_histlookup.module.io.ptw

  outer.mem_decomp_ireader_seqexec.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_decomp_ireader_seqexec.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_decomp_ireader_seqexec.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(9) <> outer.mem_decomp_ireader_seqexec.module.io.ptw

//  outer.mem_decomp_writer_dtbuilder.module.io.sfence <> cmd_router.io.sfence_out
//  outer.mem_decomp_writer_dtbuilder.module.io.status.valid := cmd_router.io.dmem_status_out.valid
//  outer.mem_decomp_writer_dtbuilder.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
//  io.ptw(10) <> outer.mem_decomp_writer_dtbuilder.module.io.ptw

  outer.mem_decomp_writer_seqexec.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_decomp_writer_seqexec.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_decomp_writer_seqexec.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(10) <> outer.mem_decomp_writer_seqexec.module.io.ptw

  outer.mem_decomp_ireader_rawrle.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_decomp_ireader_rawrle.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_decomp_ireader_rawrle.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(11) <> outer.mem_decomp_ireader_rawrle.module.io.ptw
}


class WithZstdDecompressorBase extends Config ((site, here, up) => {
  case CompressAccelTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case ZstdDecompressorCmdQueDepth => 4
  case HufDecompressDecompAtOnce => 4
  case NoSnappy => true
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val zstd_decompressor = LazyModule.apply(new ZstdDecompressor(OpcodeSet.custom0)(p))
      zstd_decompressor
    }
  )
  case CompressAccelPrintfEnable => true
})


class WithHufSpeculationAmount(n: Int = 4) extends Config ((site, here, up) => {
  case HufDecompressDecompAtOnce => n
})

class EnableSnappyInMergedDecompressor extends Config ((site, here, up) => {
  case NoSnappy => false
})

class WithZstdDecompressor4 extends Config (
  new WithHufSpeculationAmount(4) ++
  new WithZstdDecompressorBase
)

class WithZstdDecompressor8 extends Config (
  new WithHufSpeculationAmount(8) ++
  new WithZstdDecompressorBase
)

class WithZstdDecompressor16 extends Config (
  new WithHufSpeculationAmount(16) ++
  new WithZstdDecompressorBase
)

class WithMergedDecompressor16Spec extends Config (
  new WithHufSpeculationAmount(16) ++
  new EnableSnappyInMergedDecompressor ++
  new WithZstdDecompressorBase
)

class WithZstdDecompressor20 extends Config (
  new WithHufSpeculationAmount(20) ++
  new WithZstdDecompressorBase
)

class WithZstdDecompressor24 extends Config (
  new WithHufSpeculationAmount(24) ++
  new WithZstdDecompressorBase
)

class WithZstdDecompressor32 extends Config (
  new WithHufSpeculationAmount(32) ++
  new WithZstdDecompressorBase
)
class WithMergedDecompressor32 extends Config(
  new WithZstdDecompressor32 ++
  new EnableSnappyInMergedDecompressor)
