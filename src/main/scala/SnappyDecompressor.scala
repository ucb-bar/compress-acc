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

class SnappyDecompressor(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(
    opcodes = opcodes, nPTWPorts = 3) {
  override lazy val module = new SnappyDecompressorImp(this)

  val mem_decomp_ireader = LazyModule(new L2MemHelper("[mem_decomp_ireader]", numOutstandingReqs=32))
  if (p(CompressAccelLatencyInjectEnable)) {
    println(s"latency injection ON for [mem_decomp_ireader]: adding ${p(CompressAccelLatencyInjectCycles)} cycles")
    tlNode := TLBuffer.chainNode(p(CompressAccelLatencyInjectCycles)) := mem_decomp_ireader.masterNode
  } else {
    println(s"latency injection OFF for [mem_decomp_ireader] due to CompressAccelLatencyInjectEnable: ${p(CompressAccelLatencyInjectEnable)}")
    tlNode := mem_decomp_ireader.masterNode
  }

  val mem_decomp_writer = LazyModule(new L2MemHelper(printInfo="[m_decomp_writer]", numOutstandingReqs=32, queueRequests=true, queueResponses=true))
  if (p(CompressAccelLatencyInjectEnable)) {
    println(s"latency injection ON for [mem_decomp_writer]: adding ${p(CompressAccelLatencyInjectCycles)} cycles")
    tlNode := TLBuffer.chainNode(p(CompressAccelLatencyInjectCycles)) := mem_decomp_writer.masterNode
  } else {
    println(s"latency injection OFF for [mem_decomp_writer] due to CompressAccelLatencyInjectEnable: ${p(CompressAccelLatencyInjectEnable)}")
    tlNode := mem_decomp_writer.masterNode
  }

  val mem_decomp_readbackref = LazyModule(new L2MemHelper("[m_decomp_readbackref]", numOutstandingReqs=32))
  if (p(CompressAccelLatencyInjectEnable)) {
    if (p(CompressAccelFarAccelLocalCache)) {
      println(s"latency injection OFF for [mem_decomp_readbackref] due to CompressAccelFarAccelLocalCache: ${p(CompressAccelFarAccelLocalCache)}")
      tlNode := mem_decomp_readbackref.masterNode
    } else {
      println(s"latency injection ON for [mem_decomp_readbackref]: adding ${p(CompressAccelLatencyInjectCycles)} cycles")
      tlNode := TLBuffer.chainNode(p(CompressAccelLatencyInjectCycles)) := mem_decomp_readbackref.masterNode
    }
  } else {
    println(s"latency injection OFF for [mem_decomp_readbackref] due to CompressAccelLatencyInjectEnable: ${p(CompressAccelLatencyInjectEnable)}")
    tlNode := mem_decomp_readbackref.masterNode
  }
}

class SnappyDecompressorImp(outer: SnappyDecompressor)(implicit p: Parameters) extends LazyRoCCModuleImp(outer)
with MemoryOpConstants {

  io.interrupt := Bool(false)

  val cmd_router = Module(new SnappyDecompressorCommandRouter)
  cmd_router.io.rocc_in <> io.cmd
  io.resp <> cmd_router.io.rocc_out

  io.mem.req.valid := false.B
  io.mem.s1_kill := false.B
  io.mem.s2_kill := false.B
  io.mem.keep_clock_enabled := true.B

  val memloader = Module(new MemLoader)
  outer.mem_decomp_ireader.module.io.userif <> memloader.io.l2helperUser
  memloader.io.src_info <> cmd_router.io.decompress_src_info

  val command_converter = Module(new SnappyDecompressorCommandConverter)
  command_converter.io.mem_stream <> memloader.io.consumer


  val copy_expander = Module(new SnappyDecompressorCopyExpander)
  copy_expander.io.internal_commands_in <> command_converter.io.internal_commands
  copy_expander.io.literal_chunks_in <> command_converter.io.literal_chunks

  val offchip_history_lookup = Module(new SnappyDecompressorOffchipHistoryLookup)
  offchip_history_lookup.io.internal_commands <> copy_expander.io.internal_commands_out
  offchip_history_lookup.io.literal_chunks <> copy_expander.io.literal_chunks_out
  offchip_history_lookup.io.decompress_dest_info <> cmd_router.io.decompress_dest_info_offchip
  outer.mem_decomp_readbackref.module.io.userif <> offchip_history_lookup.io.l2helperUser
  offchip_history_lookup.io.onChipHistLenConfig := cmd_router.io.onChipHistLenConfig

  val command_expander = Module(new SnappyDecompressorCommandExpanderSRAM)
  command_expander.io.internal_commands <> offchip_history_lookup.io.internal_commands_out
  command_expander.io.literal_chunks <> offchip_history_lookup.io.literal_chunks_out
  outer.mem_decomp_writer.module.io.userif <> command_expander.io.l2helperUser
  command_expander.io.decompress_dest_info <> cmd_router.io.decompress_dest_info

  cmd_router.io.bufs_completed := command_expander.io.bufs_completed
  cmd_router.io.no_writes_inflight := command_expander.io.no_writes_inflight

  outer.mem_decomp_ireader.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_decomp_ireader.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_decomp_ireader.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(0) <> outer.mem_decomp_ireader.module.io.ptw

  outer.mem_decomp_writer.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_decomp_writer.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_decomp_writer.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(1) <> outer.mem_decomp_writer.module.io.ptw

  outer.mem_decomp_readbackref.module.io.sfence <> cmd_router.io.sfence_out
  outer.mem_decomp_readbackref.module.io.status.valid := cmd_router.io.dmem_status_out.valid
  outer.mem_decomp_readbackref.module.io.status.bits := cmd_router.io.dmem_status_out.bits.status
  io.ptw(2) <> outer.mem_decomp_readbackref.module.io.ptw

  io.busy := Bool(false)
}

