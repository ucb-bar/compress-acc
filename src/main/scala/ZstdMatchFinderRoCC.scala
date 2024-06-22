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




class ZstdMatchFinderRoCC(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(
    opcodes = opcodes, nPTWPorts = 3) {
  override lazy val module = new ZstdMatchFinderRoCCImp(this)

  // TODO: for all of these, tune the configuration params of L2MemHelper for perf and critical path
  val mem_comp_ireader = LazyModule(new L2MemHelper("[mem_comp_ireader]", numOutstandingReqs=32))
  tlNode := TLWidthWidget(32) := mem_comp_ireader.masterNode

  // TODO: this is NOT an L2MemHelperWriteFast. see if that is a reasonable optimization later.
  val mem_seq_writer = LazyModule(new L2MemHelper(printInfo="[m_seq_writer]", numOutstandingReqs=32, queueRequests=true, queueResponses=true))
  tlNode := TLWidthWidget(32) := mem_seq_writer.masterNode

  val mem_lit_writer = LazyModule(new L2MemHelper(printInfo="[m_lit_writer]", numOutstandingReqs=32, queueRequests=true, queueResponses=true))
  tlNode := TLWidthWidget(32) := mem_lit_writer.masterNode
}

class ZstdMatchFinderRoCCImp(outer: ZstdMatchFinderRoCC)(implicit p: Parameters) extends LazyRoCCModuleImp(outer)
with MemoryOpConstants {

  io.interrupt := false.B

  val cmd_router = Module(new ZstdMatchFinderCommandRouter)
  cmd_router.io.rocc_in <> io.cmd
  io.resp <> cmd_router.io.rocc_out

  io.mem.req.valid := false.B
  io.mem.s1_kill := false.B
  io.mem.s2_kill := false.B
  io.mem.keep_clock_enabled := true.B

  // DO NOT TOUCH ABOVE THIS LINE !!!!!!!!!!!!!!!!!!!!!!!!!
  // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!

  val memloader = Module(new LZ77HashMatcherMemLoader)
  outer.mem_comp_ireader.module.io.userif <> memloader.io.l2helperUser
  memloader.io.src_info <> cmd_router.io.compress_src_info

  val lz77hashmatcher = Module(new LZ77HashMatcher)
  lz77hashmatcher.io.MAX_OFFSET_ALLOWED := cmd_router.io.MAX_OFFSET_ALLOWED
  lz77hashmatcher.io.memloader_in <> memloader.io.consumer
  lz77hashmatcher.io.memloader_optional_hbsram_in <> memloader.io.optional_hbsram_write
  lz77hashmatcher.io.src_info <> cmd_router.io.compress_src_info2

  val compress_litlen_injector = Module(new ZstdMatchFinderLitLenInjector)
  compress_litlen_injector.io.memwrites_in <> lz77hashmatcher.io.memwrites_out

  val seq_memwriter = Module(new ZstdMatchFinderMemwriter("seq-writer"))
  seq_memwriter.io.memwrites_in <> compress_litlen_injector.io.seq_memwrites_out
  outer.mem_seq_writer.module.io.userif <> seq_memwriter.io.l2io
  seq_memwriter.io.compress_dest_info <> cmd_router.io.seq_dst_info

  val lit_memwriter = Module(new ZstdMatchFinderMemwriter("lit-writer"))
  lit_memwriter.io.memwrites_in <> compress_litlen_injector.io.lit_memwrites_out
  outer.mem_lit_writer.module.io.userif <> lit_memwriter.io.l2io
  lit_memwriter.io.compress_dest_info <> cmd_router.io.lit_dst_info

  lit_memwriter.io.written_bytes.ready := true.B
  seq_memwriter.io.written_bytes.ready := true.B

  val bufs_completed = Mux(seq_memwriter.io.bufs_completed === lit_memwriter.io.bufs_completed,
                           seq_memwriter.io.bufs_completed,
                           0.U)
  cmd_router.io.bufs_completed := bufs_completed
  cmd_router.io.no_writes_inflight := seq_memwriter.io.no_writes_inflight && lit_memwriter.io.no_writes_inflight

  // DO NOT TOUCH BELOW THIS LINE UNLESS YOU NEED MORE MEMORY
  // INTERFACES !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!

  // L2 I/F 0: boilerplate, do not touch
  outer.mem_comp_ireader.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_comp_ireader.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_comp_ireader.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(0) <> outer.mem_comp_ireader.module.io.ptw

  // L2 I/F 1: boilerplate, do not touch
  outer.mem_seq_writer.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_seq_writer.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_seq_writer.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(1) <> outer.mem_seq_writer.module.io.ptw

  // L2 I/F 2: boilerplate, do not touch
  outer.mem_lit_writer.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_lit_writer.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_lit_writer.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(2) <> outer.mem_lit_writer.module.io.ptw

  io.busy := false.B
}

class WithZstdMatchFinder extends Config ((site, here, up) => {
  case CompressAccelTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case BuildRoCC => Seq(
    (p: Parameters) => {
      val zstd_matchfinder = LazyModule.apply(new ZstdMatchFinderRoCC(OpcodeSet.custom3)(p))
      zstd_matchfinder
    }
  )
})
