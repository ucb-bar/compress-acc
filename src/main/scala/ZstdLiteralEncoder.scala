package compressacc

import chisel3._
import chisel3.util._
import chisel3.util._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheArbiter}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._


class ZstdRawBlockMemcopyL2IO()(implicit p: Parameters) extends Bundle {
  val reader = new L2MemHelperBundle
  val writer = new L2MemHelperBundle
}

class ZstdRawBlockMemcopyIO()(implicit p: Parameters) extends Bundle {
  val src_info = Flipped(Decoupled(new StreamInfo))
  val dst_info = Flipped(Decoupled(new DstWithValInfo))

  val bytes_written = Decoupled(UInt(64.W))
  val l2if = new ZstdRawBlockMemcopyL2IO
}

class ZstdRawBlockMemcopy()(implicit p: Parameters) extends ZstdCompressorModule {
  val io = IO(new ZstdRawBlockMemcopyIO())

  val memcpy = Module(new ZstdMemcopy())

  memcpy.io.src_info <> io.src_info
  memcpy.io.dst_info <> io.dst_info
  io.l2if.reader <> memcpy.io.l2io_read
  io.l2if.writer <> memcpy.io.l2io_write
  io.bytes_written <> memcpy.io.bytes_written
}


class ZstdLiteralEncoderL2IO()(implicit p: Parameters) extends Bundle {
  val lit_reader = new L2MemHelperBundle
  val dic_reader = new L2MemHelperBundle
  val dic_writer = new L2MemHelperBundle
  val hdr_writer = new L2MemHelperBundle
  val jt_writer = new L2MemHelperBundle
  val lit_writer = new L2MemHelperBundle
}


class ZstdLiteralEncoderIO()(implicit p: Parameters) extends Bundle {
  val src_info = Flipped(Decoupled(new StreamInfo))
  val src_info2 = Flipped(Decoupled(new StreamInfo))
  val dst_info = Flipped(Decoupled(new DstWithValInfo))
  val bytes_written = Decoupled(UInt(64.W))
  val l2if = new ZstdLiteralEncoderL2IO

  val busy = Output(Bool())
}

class ZstdLiteralEncoder()(implicit p: Parameters) extends ZstdCompressorModule {

  val io = IO(new ZstdLiteralEncoderIO())

  dontTouch(io)

  val unroll_cnt = p(HufCompressUnrollCnt)

  val dispatched_src_cnt = RegInit(0.U(64.W))

  when (io.src_info.fire) {
    val nxt_dispatched_src_cnt = dispatched_src_cnt + 1.U
    dispatched_src_cnt := nxt_dispatched_src_cnt
    CompressAccelLogger.logInfo("ZSTD_LITERAL_ENCODER src dispatched: %d\n", nxt_dispatched_src_cnt)
  }

  val controller = Module(new HufCompressorController(queDepth))
  controller.io.src_info_in <> io.src_info
  controller.io.dst_info_in <> io.dst_info
  io.bytes_written <> controller.io.total_write_bytes

  when (io.bytes_written.fire) {
    CompressAccelLogger.logInfo("ZSTD_LITERAL_ENCODER written bytes: %d\n", io.bytes_written.bits)
  }

  when (io.src_info.fire) {
    CompressAccelLogger.logInfo("ZSTD_LITERAL_ENCODER src size: %d\n", io.src_info.bits.isize)
  }

  val hdr_writer = Module(new HufCompressorMemwriter(writeCmpFlag=false))
  io.l2if.hdr_writer <> hdr_writer.io.l2io
  hdr_writer.io.decompress_dest_info <> controller.io.hdr_dst_info
  hdr_writer.io.memwrites_in <> controller.io.hdr_writes
  hdr_writer.io.custom_write_value <> controller.io.total_write_bytes2

  val jt_writer = Module(new EntropyCompressorMemwriter(writeCmpFlag=false))
  io.l2if.jt_writer <> jt_writer.io.l2io
  jt_writer.io.decompress_dest_info <> controller.io.jt_dst_info
  jt_writer.io.memwrites_in <> controller.io.jt_writes

  val dic_memloader = Module(new MemLoader)
  io.l2if.dic_reader <> dic_memloader.io.l2helperUser
  dic_memloader.io.src_info <> io.src_info2

  val dic_builder = Module(new HufCompressorDicBuilder(queDepth, unroll_cnt))
  dic_builder.io.cnt_stream <> dic_memloader.io.consumer
  controller.io.weight_bytes <> dic_builder.io.header_written_bytes
  controller.io.header_size_info <> dic_builder.io.header_size_info
  dic_builder.io.init_dictionary <> controller.io.init_dictionary

  val dic_writer = Module(new EntropyCompressorMemwriter(writeCmpFlag=false, "lit-dic-memwr"))
  io.l2if.dic_writer <> dic_writer.io.l2io
  dic_writer.io.decompress_dest_info <> controller.io.weight_dst_info
  dic_writer.io.memwrites_in <> dic_builder.io.header_writes

  val lit_memloader = Module(new ReverseMemLoader("lit-revmemloader"))
  io.l2if.lit_reader <> lit_memloader.io.l2helperUser
  lit_memloader.io.src_info <> controller.io.lit_src_info

  val encoder = Module(new HufCompressorEncoder(queDepth, unroll_cnt))
  encoder.io.lit_stream <> lit_memloader.io.consumer
  encoder.io.dic_info <> dic_builder.io.dic_info
  dic_builder.io.symbol_info <> encoder.io.symbol_info
  controller.io.compressed_bytes <> encoder.io.compressed_bytes

  val lit_memwriter = Module(new EntropyCompressorMemwriter(writeCmpFlag=false, "lit-comp-memwr"))
  io.l2if.lit_writer <> lit_memwriter.io.l2io
  lit_memwriter.io.decompress_dest_info <> controller.io.lit_dst_info
  lit_memwriter.io.memwrites_in <> encoder.io.memwrites_out

  val done = (dispatched_src_cnt === controller.io.bufs_completed) && hdr_writer.io.no_writes_inflight
  io.busy := !done
}
