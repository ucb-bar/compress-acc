/*

//*From SW, get sequence(LL, ML, offset), *litPtr, op, dictEnd, prefixStart 
//via ROCCCommands*/

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

/* Commented the definition below as it is already defined in Snappy Decompressor.
case object CompressAccelPrintfEnable extends Field[Boolean](false)
*/

class ZstdSeqExec(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(opcodes = opcodes, nPTWPorts = 3) {
  override lazy val module = new ZstdSeqExecImp(this)

  val mem_decomp_ireader = LazyModule(new L2MemHelper("[mem_decomp_ireader]", numOutstandingReqs=32))
  tlNode := TLWidthWidget(32) := mem_decomp_ireader.masterNode

  val mem_decomp_writer = LazyModule(new L2MemHelper(printInfo="[m_decomp_writer]", numOutstandingReqs=32, queueRequests=true, queueResponses=true))
  tlNode := TLWidthWidget(32) := mem_decomp_writer.masterNode

  val mem_decomp_readbackref = LazyModule(new L2MemHelper("[m_decomp_readbackref]", numOutstandingReqs=32))
  tlNode := TLWidthWidget(32) := mem_decomp_readbackref.masterNode 
}

class ZstdSeqExecImp(outer: ZstdSeqExec)(implicit p: Parameters) extends LazyRoCCModuleImp(outer) with MemoryOpConstants {
  io.interrupt := Bool(false.B)
  
  val control_unit = Module(new ZstdSeqExecControl(128))
  control_unit.io.rocc_in <> io.cmd
  io.resp <> control_unit.io.rocc_out

  io.mem.req.valid := false.B
  io.mem.s1_kill := false.B
  io.mem.s2_kill := false.B
  io.mem.keep_clock_enabled := true.B
  /*
  outer.mem_decomp_readbackref.module.io.userif.req.valid := false.B
  outer.mem_decomp_readbackref.module.io.userif.req.bits.addr := 0.U(32.W)
  outer.mem_decomp_readbackref.module.io.userif.req.bits.data := 0.U(128.W)
  outer.mem_decomp_readbackref.module.io.userif.req.bits.size := 0.U(6.W)
  outer.mem_decomp_readbackref.module.io.userif.req.bits.cmd := 0.U(2.W)
  outer.mem_decomp_readbackref.module.io.userif.resp.ready := false.B
  */

  val memloader = Module(new MemLoaderFSE)
  outer.mem_decomp_ireader.module.io.userif <> memloader.io.l2helperUser
  memloader.io.decompress_src_info <> control_unit.io.lit_src_info
  // Above line depends on my implementation of Decoder/Router

  val seqExecLoader = Module(new ZstdSeqExecLoader)
  seqExecLoader.io.mem_stream <> memloader.io.consumer
  seqExecLoader.io.command_in <> control_unit.io.seq_info

  val seqExecHistoryLookup = Module(new ZstdOffchipHistoryLookup(16)) //Size of On-chip SRAM in bytes. 65536 if 4096
  seqExecHistoryLookup.io.internal_commands <> seqExecLoader.io.command_out
  seqExecHistoryLookup.io.literal_chunks <> seqExecLoader.io.literal_chunk
  seqExecHistoryLookup.io.final_command <> seqExecLoader.io.final_command
  seqExecHistoryLookup.io.decompress_dest_info <> control_unit.io.decompress_dest_info_histlookup
  outer.mem_decomp_readbackref.module.io.userif <> seqExecHistoryLookup.io.l2helperUser

  val seqExecWriter = Module(new ZstdSeqExecWriter(16))
  seqExecWriter.io.internal_commands <> seqExecHistoryLookup.io.internal_commands_out
  seqExecWriter.io.literal_chunks <> seqExecHistoryLookup.io.literal_chunks_out
  seqExecWriter.io.final_command <> seqExecHistoryLookup.io.final_command_out
  //seqExecWriter.io.internal_commands <> seqExecLoader.io.command_out
  //seqExecWriter.io.literal_chunks <> seqExecLoader.io.literal_chunk
  //seqExecWriter.io.final_command <> seqExecLoader.io.final_command
  seqExecWriter.io.decompress_dest_info <> control_unit.io.decompress_dest_info
  control_unit.io.bufs_completed := seqExecWriter.io.bufs_completed
  control_unit.io.no_writes_inflight := seqExecWriter.io.no_writes_inflight
  outer.mem_decomp_writer.module.io.userif <> seqExecWriter.io.l2helperUser
  
  // L2 I/F 0: boilerplate, do not touch
  outer.mem_decomp_ireader.module.io.sfence <> control_unit.io.sfence_out
  outer.mem_decomp_ireader.module.io.status.valid := control_unit.io.dmem_status_out.valid
  outer.mem_decomp_ireader.module.io.status.bits := control_unit.io.dmem_status_out.bits.status
  io.ptw(0) <> outer.mem_decomp_ireader.module.io.ptw

  // L2 I/F 1: boilerplate, do not touch
  outer.mem_decomp_writer.module.io.sfence <> control_unit.io.sfence_out
  outer.mem_decomp_writer.module.io.status.valid := control_unit.io.dmem_status_out.valid
  outer.mem_decomp_writer.module.io.status.bits := control_unit.io.dmem_status_out.bits.status
  io.ptw(1) <> outer.mem_decomp_writer.module.io.ptw

  // L2 I/F 2: boilerplate, do not touch
  outer.mem_decomp_readbackref.module.io.sfence <> control_unit.io.sfence_out
  outer.mem_decomp_readbackref.module.io.status.valid := control_unit.io.dmem_status_out.valid
  outer.mem_decomp_readbackref.module.io.status.bits := control_unit.io.dmem_status_out.bits.status
  io.ptw(2) <> outer.mem_decomp_readbackref.module.io.ptw

  io.busy := Bool(false.B)
}
class WithZstdSeqExec extends Config ((site, here, up) => {
  case CompressAccelTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case BuildRoCC => Seq(
    (p: Parameters) => {
      val compress_accel_decompressor = LazyModule.apply(new ZstdSeqExec(OpcodeSet.custom3)(p))
      compress_accel_decompressor
    }
  )
})

/* Commented the definition below as it is already defined in the Snappy Decompressor.
class WithCompressAccelPrintf extends Config((site, here, up) => {
  case CompressAccelPrintfEnable => true
})
*/
/*Comment out for Chipyard Target Sim
class ZstdSeqExecConfig extends Config(
  new WithZstdSeqExec ++
  new RocketConfig)
*/

*/