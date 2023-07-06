package compressacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheArbiter}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._
import CompressorConsts._



class ZstdMatchFinderL2IO extends Bundle {
  val memloader_userif = new L2MemHelperBundle
  val lit_memwriter_userif = new L2MemHelperBundle
  val seq_memwriter_userif = new L2MemHelperBundle
}

class ZstdMatchFinderSrcBundle extends Bundle {
  val compress_src_info = Decoupled(new StreamInfo)
  val compress_src_info2 = Decoupled(new StreamInfo)
}

class ZstdMatchFinderDstBundle extends Bundle {
  val lit_dst_info = Decoupled(new DstInfo)
  val seq_dst_info = Decoupled(new DstInfo)
}

class ZstdMatchFinderConsumedBundle extends Bundle {
  val lit_consumed_bytes = Decoupled(UInt(64.W))
  val seq_consumed_bytes = Decoupled(UInt(64.W))
}

class ZstdMatchFinderIO()(implicit p: Parameters) extends Bundle {
  val l2io = new ZstdMatchFinderL2IO
  val src = Flipped(new ZstdMatchFinderSrcBundle)
  val dst = Flipped(new ZstdMatchFinderDstBundle)
  val buff_consumed = new ZstdMatchFinderConsumedBundle
  val MAX_OFFSET_ALLOWED = Input(UInt(64.W))
  val RUNTIME_HT_NUM_ENTRIES_LOG2 = Input(UInt(5.W))
  val ALGORITHM = Input(UInt(1.W))
}

class ZstdMatchFinder(removeSnappy: Boolean)(implicit p: Parameters) extends Module {
  val io = IO(new ZstdMatchFinderIO)

  when (io.src.compress_src_info.fire) {
    CompressAccelLogger.logInfo("MATCHFINDER_SRC_INFO_FIRE\n")
    CompressAccelLogger.logInfo("addr: 0x%x, size: %d\n", io.src.compress_src_info.bits.ip, io.src.compress_src_info.bits.isize)
  }

  when (io.src.compress_src_info2.fire) {
    CompressAccelLogger.logInfo("MATCHFINDER_SRC_INFO2_FIRE\n")
    CompressAccelLogger.logInfo("addr: 0x%x, size: %d\n", io.src.compress_src_info2.bits.ip, io.src.compress_src_info2.bits.isize)
  }

  when (io.dst.lit_dst_info.fire) {
    CompressAccelLogger.logInfo("MATCHFINDER_LIT_DST_INFO_FIRE\n")
    CompressAccelLogger.logInfo("op: 0x%x, cmpflag: 0x%x\n", io.dst.lit_dst_info.bits.op, io.dst.lit_dst_info.bits.cmpflag)
  }

  when (io.dst.seq_dst_info.fire) {
    CompressAccelLogger.logInfo("MATCHFINDER_SEQ_DST_INFO_FIRE\n")
    CompressAccelLogger.logInfo("op: 0x%x, cmpflag: 0x%x\n", io.dst.seq_dst_info.bits.op, io.dst.seq_dst_info.bits.cmpflag)
  }

  when (io.buff_consumed.lit_consumed_bytes.fire) {
    CompressAccelLogger.logInfo("MATCHFINDER_LIT_CONSUMED_BYTES_FIRE\n")
    CompressAccelLogger.logInfo("consumed_bytes: %d\n", io.buff_consumed.lit_consumed_bytes.bits)
  }

  when (io.buff_consumed.seq_consumed_bytes.fire) {
    CompressAccelLogger.logInfo("MATCHFINDER_SEQ_CONSUMED_BYTES_FIRE\n")
    CompressAccelLogger.logInfo("consumed_bytes: %d\n", io.buff_consumed.seq_consumed_bytes.bits)
  }

  val memloader = Module(new LZ77HashMatcherMemLoader)
  io.l2io.memloader_userif <> memloader.io.l2helperUser
  memloader.io.src_info <> io.src.compress_src_info

  val use_zstd = io.ALGORITHM === ZSTD.U

  val lz77hashmatcher = Module(new LZ77HashMatcher)
  lz77hashmatcher.io.write_snappy_header := !use_zstd
  lz77hashmatcher.io.MAX_OFFSET_ALLOWED := io.MAX_OFFSET_ALLOWED
  lz77hashmatcher.io.RUNTIME_HT_NUM_ENTRIES_LOG2 := io.RUNTIME_HT_NUM_ENTRIES_LOG2
  lz77hashmatcher.io.memloader_in <> memloader.io.consumer
  lz77hashmatcher.io.memloader_optional_hbsram_in <> memloader.io.optional_hbsram_write
  lz77hashmatcher.io.src_info <> io.src.compress_src_info2

  if (!removeSnappy) {
    println("Snappy accelerator merged\n")

    val zstd_litlen_injector = Module(new ZstdMatchFinderLitLenInjector)
    zstd_litlen_injector.io.memwrites_in.bits := lz77hashmatcher.io.memwrites_out.bits
    zstd_litlen_injector.io.memwrites_in.valid := lz77hashmatcher.io.memwrites_out.valid && use_zstd

    val snappy_copy_expander = Module(new SnappyCompressCopyExpander)
    snappy_copy_expander.io.memwrites_in.bits := lz77hashmatcher.io.memwrites_out.bits
    snappy_copy_expander.io.memwrites_in.valid := lz77hashmatcher.io.memwrites_out.valid && !use_zstd


    lz77hashmatcher.io.memwrites_out.ready := (use_zstd && zstd_litlen_injector.io.memwrites_in.ready) ||
    (!use_zstd && snappy_copy_expander.io.memwrites_in.ready)

    val snappy_litlen_injector = Module(new SnappyCompressLitLenInjector)
    snappy_litlen_injector.io.memwrites_in <> snappy_copy_expander.io.memwrites_out

    assert(!(use_zstd && snappy_litlen_injector.io.memwrites_out.fire), "snappy_litlen_injection outputing memwrites when algo is zstd")
    assert(!(use_zstd && snappy_copy_expander.io.memwrites_in.fire), "snappy_copy_expander accepting memwrites when algo is zstd")

    val seq_memwriter = Module(new ZstdMatchFinderMemwriter("seq-writer", writeCmpFlag=true))
    seq_memwriter.io.memwrites_in.bits := Mux(use_zstd,
                                              zstd_litlen_injector.io.seq_memwrites_out.bits,
                                              snappy_litlen_injector.io.memwrites_out.bits)
    seq_memwriter.io.memwrites_in.valid := (use_zstd && zstd_litlen_injector.io.seq_memwrites_out.valid) ||
                                           (!use_zstd && snappy_litlen_injector.io.memwrites_out.valid)
    seq_memwriter.io.compress_dest_info <> io.dst.seq_dst_info
    seq_memwriter.io.force_write := !use_zstd // Write to cmpflag for snappy
    io.l2io.seq_memwriter_userif <> seq_memwriter.io.l2io

    zstd_litlen_injector.io.seq_memwrites_out.ready := (use_zstd && seq_memwriter.io.memwrites_in.ready)
    snappy_litlen_injector.io.memwrites_out.ready := (!use_zstd && seq_memwriter.io.memwrites_in.ready)



    when (seq_memwriter.io.memwrites_in.fire && use_zstd) {
      assert(zstd_litlen_injector.io.seq_memwrites_out.fire, "zstd_litlen_injector should fire here")
      assert(!snappy_litlen_injector.io.memwrites_out.fire, "snappy_litlen_injector should not fire here")
    }


    val lit_memwriter = Module(new ZstdMatchFinderMemwriter("lit-writer", writeCmpFlag=false))
    lit_memwriter.io.memwrites_in <> zstd_litlen_injector.io.lit_memwrites_out
    lit_memwriter.io.compress_dest_info <> io.dst.lit_dst_info
    lit_memwriter.io.force_write := false.B
    io.l2io.lit_memwriter_userif <> lit_memwriter.io.l2io

    io.buff_consumed.lit_consumed_bytes <> lit_memwriter.io.written_bytes
    io.buff_consumed.seq_consumed_bytes <> seq_memwriter.io.written_bytes
  } else {
    println("Snappy accelerator not merged\n")

    val zstd_litlen_injector = Module(new ZstdMatchFinderLitLenInjector)
    zstd_litlen_injector.io.memwrites_in <> lz77hashmatcher.io.memwrites_out

    val seq_memwriter = Module(new ZstdMatchFinderMemwriter("seq-writer", writeCmpFlag=true))
    seq_memwriter.io.memwrites_in <> zstd_litlen_injector.io.seq_memwrites_out
    seq_memwriter.io.compress_dest_info <> io.dst.seq_dst_info
    seq_memwriter.io.force_write := !use_zstd // Write to cmpflag for snappy
    io.l2io.seq_memwriter_userif <> seq_memwriter.io.l2io

    val lit_memwriter = Module(new ZstdMatchFinderMemwriter("lit-writer", writeCmpFlag=false))
    lit_memwriter.io.memwrites_in <> zstd_litlen_injector.io.lit_memwrites_out
    lit_memwriter.io.compress_dest_info <> io.dst.lit_dst_info
    lit_memwriter.io.force_write := false.B
    io.l2io.lit_memwriter_userif <> lit_memwriter.io.l2io

    io.buff_consumed.lit_consumed_bytes <> lit_memwriter.io.written_bytes
    io.buff_consumed.seq_consumed_bytes <> seq_memwriter.io.written_bytes
  }
}
