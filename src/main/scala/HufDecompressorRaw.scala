package compressacc

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
// import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheArbiter}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._

class HufInfo extends Bundle {
  val src_consumed_bytes = UInt(64.W)
  val dst_written_bytes = UInt(64.W)
}

class HufL2MemHelpers extends Bundle {
  val lit_userif = new L2MemHelperBundle
  val hdr_userif = new L2MemHelperBundle
  val write_userif = new L2MemHelperBundle
}

class HufDecompressorRawIO()(implicit p: Parameters) extends Bundle {
  val src_info = Flipped(Decoupled(new DecompressPtrInfo))
  val dst_info = Flipped(Decoupled(new DecompressPtrInfo))

  val decomp_literal_info = Decoupled(new HufInfo)
  val huff_finished = Decoupled(Bool())

  val l2_memhelpers = new HufL2MemHelpers
}

class HufDecompressorRaw(zstdTop: ZstdDecompressor, cmd_que_depth: Int)(implicit p: Parameters) 
  extends Module with MemoryOpConstants {
  val io = IO(new HufDecompressorRawIO)

  dontTouch(io)

  val decomp_at_once = p(HufDecompressDecompAtOnce)

  val cmd_router = Module(new HufDecompressorRawCommandRouter(cmd_que_depth))
  cmd_router.io.src_info_in <> io.src_info
  cmd_router.io.dst_info_in <> io.dst_info
  io.huff_finished <> cmd_router.io.finished

  val header_memloader_router = Module(new HufDecompressorMemLoaderRouter(3)) // header expander, fse decoder, literal expander
  val literal_memloader_router = Module(new HufDecompressorMemLoaderRouter(2)) // fse decoder, literal expander

  val header_memloader = Module(new MemLoader)
  io.l2_memhelpers.hdr_userif <> header_memloader.io.l2helperUser
  header_memloader_router.io.consumer_in <> header_memloader.io.consumer
  header_memloader.io.src_info <> header_memloader_router.io.memloader_info_out

  val literal_memloader = Module(new ReverseMemLoader)
  io.l2_memhelpers.lit_userif <> literal_memloader.io.l2helperUser
  literal_memloader_router.io.consumer_in <> literal_memloader.io.consumer
  literal_memloader.io.src_info <> literal_memloader_router.io.memloader_info_out

  val header_expander = Module(new HufDecompressorHeaderExpander(decomp_at_once, cmd_que_depth))
  header_expander.io.src_all <> cmd_router.io.src_info
  header_memloader_router.io.memloader_info_in(0) <> header_expander.io.src_header
  header_expander.io.header_stream <> header_memloader_router.io.consumer_out(0)
  header_memloader_router.io.sel := header_expander.io.header_router_sel

  val fse_decoder = Module(new FSEHeaderDecoder(cmd_que_depth))
  fse_decoder.io.header_info <> header_expander.io.huf_header_info
  header_memloader_router.io.memloader_info_in(1) <> fse_decoder.io.header_stream_info
  fse_decoder.io.header_stream <> header_memloader_router.io.consumer_out(1)
  literal_memloader_router.io.memloader_info_in(0) <> fse_decoder.io.literal_stream_info
  fse_decoder.io.literal_stream <> literal_memloader_router.io.consumer_out(0)
  header_expander.io.huf_weight <> fse_decoder.io.weight
  header_expander.io.num_symbols <> fse_decoder.io.num_symbols

  val literal_expander = Module(new HufDecompressorLiteralExpander(decomp_at_once, cmd_que_depth))
  literal_expander.io.literal_cmd <> header_expander.io.literal_cmd
  literal_expander.io.literal_src_info_in <> header_expander.io.literal_src_info

  for (i <- 0 until decomp_at_once-3) {
    header_expander.io.lookup_idx(i) <> literal_expander.io.lookup_idx(i)
    literal_expander.io.dic_entry(i) <> header_expander.io.dic_entry(i)
  }
  literal_expander.io.decompressed_bytes <> header_expander.io.decompressed_bytes

  literal_memloader_router.io.sel := header_expander.io.literal_router_sel
  literal_memloader_router.io.memloader_info_in(1) <> literal_expander.io.literal_src_info_out
  literal_expander.io.src_stream <> literal_memloader_router.io.consumer_out(1)

  header_memloader_router.io.memloader_info_in(2) <> literal_expander.io.rawrle_src_info_out
  literal_expander.io.rawrle_stream <> header_memloader_router.io.consumer_out(2)
  header_expander.io.literal_expander_done <> literal_expander.io.literal_expander_done

  val mem_writer = Module(new HufDecompressorMemwriter(cmd_que_depth, write_cmp_flag=false))
  mem_writer.io.memwrites_in <> literal_expander.io.memwrites_out
  io.l2_memhelpers.write_userif <> mem_writer.io.l2io
  mem_writer.io.decompress_dest_info <> cmd_router.io.dst_info
  cmd_router.io.no_memops_inflight := mem_writer.io.no_writes_inflight
  cmd_router.io.write_complete_stream_cnt := mem_writer.io.bufs_completed

  mem_writer.io.decompress_dest_info.bits.cmpflag := 0.U

  val decomp_literal_info_q = Module(new Queue(new HufInfo, cmd_que_depth)).io
  io.decomp_literal_info <> decomp_literal_info_q.deq
  decomp_literal_info_q.enq <> header_expander.io.decomp_literal_info
}
