package compressacc

import chisel3._
import chisel3.util._
import chisel3.{Printable}
import org.chipsalliance.cde.config._
import freechips.rocketchip.util.DecoupledHelper
import ZstdConsts._
import CompressorConsts._

class ZstdMatchFinderBufTrackInfo extends Bundle {
  val lit_addr = UInt(64.W)
  val lit_idx = UInt(64.W)
  val seq_addr = UInt(64.W)
  val seq_idx = UInt(64.W)
  val last_block = Bool()
}

class ZstdHufCompressorBufTrackInfo extends Bundle {
  val lit_idx = UInt(64.W)
  val block_hdr_addr = UInt(64.W)
  val lit_dst_start_addr = UInt(64.W)
  val seq_consumed_bytes = UInt(64.W)
  val raw_literal = Bool()
  val seq_addr = UInt(64.W)
  val seq_idx = UInt(64.W)
  val last_block = Bool()
}

class ZstdFSECompressorBufTrackInfo extends Bundle {
  val lit_idx = UInt(64.W)
  val block_hdr_addr = UInt(64.W)
  val lit_written_bytes = UInt(64.W)
  val no_sequence_block = Bool()
  val seq_dst_start_addr = UInt(64.W)
  val seq_idx = UInt(64.W)
  val last_block = Bool()
}


class ZstdCompressorFrameControllerL2IO extends Bundle {
  val fhdr_l2userif = new L2MemHelperBundle
  val bhdr_l2userif = new L2MemHelperBundle
}

class ZstdControlIO extends Bundle {
  val l2io = new ZstdCompressorFrameControllerL2IO

  val litcpy_src = Decoupled(new StreamInfo)
  val litcpy_src2 = Decoupled(new StreamInfo)
  val litcpy_dst = Decoupled(new DstWithValInfo)
  val litbytes_written = Flipped(Decoupled(UInt(64.W)))

  val raw_lit_src = Decoupled(new StreamInfo)
  val raw_lit_dst = Decoupled(new DstWithValInfo)
  val raw_litbytes_written = Flipped(Decoupled(UInt(64.W)))

  val raw_block_src = Decoupled(new StreamInfo)
  val raw_block_dst = Decoupled(new DstWithValInfo)
  val raw_blockbytes_written = Flipped(Decoupled(UInt(64.W)))

  val seqcpy_src = Decoupled(new StreamInfo)
  val seqcpy_dst = Decoupled(new DstWithValInfo)
  val seqbytes_written = Flipped(Decoupled(UInt(64.W)))
}

class SharedControlIO extends Bundle {
  val mf_src = new ZstdMatchFinderSrcBundle
  val mf_dst = new ZstdMatchFinderDstBundle
  val mf_maxoffset = Output(UInt(64.W))
  val mf_runtime_ht_num_entries_log2 = Output(UInt(5.W))
  val mf_buff_consumed = Flipped(new ZstdMatchFinderConsumedBundle)
}


class ZstdCompressorFrameControllerIO extends Bundle {
  val src_info  = Flipped(Decoupled(new StreamInfo))
  val dst_info  = Flipped(Decoupled(new DstInfo))
  val buff_info = Flipped(new ZstdBuffInfo)
  val clevel_info = Flipped(Decoupled(UInt(5.W)))
  val finished_cnt = Decoupled(UInt(64.W))

  val zstd_control = new ZstdControlIO
  val shared_control = new SharedControlIO
}


class ZstdCompressorFrameController(implicit p: Parameters) extends ZstdCompressorModule {
  val io = IO(new ZstdCompressorFrameControllerIO)

  val sWriteFrameHeader = 0.U
  val sCompressBlocks = 1.U
  val frameControllerState = RegInit(0.U(4.W))

  val total_compressed_bytes = RegInit(0.U(64.W))

  val dst_base = io.dst_info.bits.op
  val cmpflag = io.dst_info.bits.cmpflag

  val mf_dst_addr_q = Module(new Queue(UInt(64.W), queDepth))

  val src_offset = RegInit(0.U(64.W))

  val track_buf_cnt = RegInit(0.U(64.W))

  ////////////////////////////////////////////////////////////////////////////
  // frameControllerState === sWriteFrameHeader
  ////////////////////////////////////////////////////////////////////////////
  val fhdr_builder = Module(new ZstdCompressorFrameHeaderBuilder)
  fhdr_builder.io.input_info.bits.src_size := io.src_info.bits.isize
  fhdr_builder.io.input_info.bits.clevel := io.clevel_info.bits
  fhdr_builder.io.input_info.valid := io.src_info.valid && io.clevel_info.valid

  val cctx = Reg(Valid(new ZstdCompressionContextParamsBundle))
  cctx <> fhdr_builder.io.cctx

  val prev_cctx_valid = RegNext(cctx.valid)
  when (cctx.valid && !prev_cctx_valid) {
    CompressAccelLogger.logInfo("FRAMECONTROL_CCTX: WSZ: 0x%x, SS: %d, FH: 0x%x, FHSZ: %d, BSZ: 0x%x, minML: %d\n",
      cctx.bits.window_size,
      cctx.bits.single_segment,
      cctx.bits.frame_header,
      cctx.bits.frame_header_bytes,
      cctx.bits.block_size,
      cctx.bits.min_match_length)
  }

  val fhdr_memwriter = Module(new ZstdCompressorMemWriter(circularQueDepth=2, writeCmpFlag=false))
  io.zstd_control.l2io.fhdr_l2userif <> fhdr_memwriter.io.l2io

  val write_frame_header_fire = DecoupledHelper(fhdr_memwriter.io.memwrites_in.ready,
                                                fhdr_memwriter.io.dest_info.ready,
                                                mf_dst_addr_q.io.enq.ready,
                                                cctx.valid,
                                                io.src_info.valid,
                                                io.buff_info.lit.valid,
                                                io.buff_info.seq.valid,
                                                io.dst_info.valid,
                                                io.clevel_info.valid,
                                                frameControllerState === sWriteFrameHeader)
  fhdr_memwriter.io.memwrites_in.valid := write_frame_header_fire.fire(fhdr_memwriter.io.memwrites_in.ready)
  fhdr_memwriter.io.memwrites_in.bits.data := cctx.bits.frame_header
  fhdr_memwriter.io.memwrites_in.bits.validbytes := cctx.bits.frame_header_bytes
  fhdr_memwriter.io.memwrites_in.bits.end_of_message := true.B

  fhdr_memwriter.io.dest_info.valid := write_frame_header_fire.fire(fhdr_memwriter.io.dest_info.ready)
  fhdr_memwriter.io.dest_info.bits.op := dst_base
  fhdr_memwriter.io.dest_info.bits.cmpflag := 0.U
  fhdr_memwriter.io.dest_info.bits.cmpval := 0.U

  fhdr_builder.io.print_info := write_frame_header_fire.fire

  when (write_frame_header_fire.fire) {
    frameControllerState := sCompressBlocks
    track_buf_cnt := track_buf_cnt + 1.U
    total_compressed_bytes := total_compressed_bytes + fhdr_memwriter.io.memwrites_in.bits.validbytes

    CompressAccelLogger.logInfo("FRAMECONTROL_BUILD_FHDR : sWriteFrameHeader -> sCompressBlocks\n")
    CompressAccelLogger.logInfo("srcFileSize: 0x%x, clevel: %d\n", io.src_info.bits.isize, io.clevel_info.bits)
  }

  ////////////////////////////////////////////////////////////////////////////
  // state === sCompressBlocks
  // Pipeline stages
  // 1. kick off matchfinder if there is a empty buffer (M)
  // 2. when the matchfinder has finished (mf_done_q filled) kick off lit comp (L)
  // 3. when lit comp has finished (litbytes_written_q filled) kick off seq comp (S)
  // 4. when seq comp finished (seqbytes_written_q filled), free corresponding 
  //    buffer & write blockheader (H)
  //
  // This is the maximum parallelism that we can get since L & S are dependent
  // because they both write to the output file (dst)
  //
  //   mf_dst_addr_q
  //   |
  // M | L   S   H
  // o 1
  // o 1 o
  // o 0 x   o
  // o 1 o   x   o
  // o 0 x   o   x
  // o 1 o   x   o
  // o 0 x   o   x
  ////////////////////////////////////////////////////////////////////////////

  ////////////////////////////////////////////////////////////////////////////
  // 1. kick off matchfinder
  ////////////////////////////////////////////////////////////////////////////
  val block_bytes = cctx.bits.block_size
  val sent_block_count = RegInit(0.U(64.W))
  val consumed_src_bytes = block_bytes * sent_block_count
  val remaining_src_bytes = io.src_info.bits.isize - consumed_src_bytes
  val next_block_start_ptr = io.src_info.bits.ip + consumed_src_bytes
  val last_block = remaining_src_bytes <= block_bytes
  val next_block_size = Mux(!last_block, block_bytes, remaining_src_bytes)
  val blocks_left_to_send = consumed_src_bytes < io.src_info.bits.isize

  val min_match_length = cctx.bits.min_match_length
// val max_sequences = block_bytes / min_match_length
  val max_sequences = block_bytes >> 2

  val seq_buff_free_vec = RegInit(VecInit(Seq.fill(256)(true.B)))
  val seq_buff_free_vec_cat = Cat(seq_buff_free_vec.reverse)
  val seq_buff_idx = RegInit(0.U(64.W))
  val seq_buff_base_addr = io.buff_info.seq.bits.ip
  val seq_buff_chunk_bytes = Wire(UInt(32.W))
  seq_buff_chunk_bytes := max_sequences * ZSTD_SEQUENCE_COMMAND_BYTES.U + 8.U // add extra 8B padding for safety
  val seq_buff_chunk_bytes_max_bitpos = 32.U - PriorityEncoder(Reverse(seq_buff_chunk_bytes))
// val seq_buff_chunk_cnt = io.buff_info.seq.bits.isize / seq_buff_chunk_bytes
  val seq_buff_chunk_cnt = io.buff_info.seq.bits.isize >> seq_buff_chunk_bytes_max_bitpos
  val seq_buff_offset = seq_buff_chunk_bytes * seq_buff_idx
  val seq_buff_start_addr = seq_buff_base_addr + seq_buff_offset

  val lit_buff_free_vec = RegInit(VecInit(Seq.fill(256)(true.B)))
  val lit_buff_free_vec_cat = Cat(lit_buff_free_vec.reverse)
  val lit_buff_idx = RegInit(0.U(64.W))
  val lit_buff_base_addr = io.buff_info.lit.bits.ip
  val lit_buff_chunk_bytes = Wire(UInt(32.W))
  lit_buff_chunk_bytes := block_bytes + 8.U
  val lit_buff_chunk_bytes_max_bitpos = 32.U - PriorityEncoder(Reverse(lit_buff_chunk_bytes))
// val lit_buff_chunk_cnt = io.buff_info.lit.bits.isize / lit_buff_chunk_bytes
  val lit_buff_chunk_cnt = io.buff_info.lit.bits.isize >> lit_buff_chunk_bytes_max_bitpos
  val lit_buff_offset = lit_buff_chunk_bytes * lit_buff_idx
  val lit_buff_start_addr = lit_buff_base_addr + lit_buff_offset

  val mf_src_info_q = Module(new Queue(new StreamInfo, queDepth))
  val mf_src_info2_q = Module(new Queue(new StreamInfo, queDepth))
  val mf_lit_dst_info_q = Module(new Queue(new DstInfo, queDepth))
  val mf_seq_dst_info_q = Module(new Queue(new DstInfo, queDepth))

  val mf_buf_track_q = Module(new Queue(new ZstdMatchFinderBufTrackInfo, queDepth))

  io.shared_control.mf_src.compress_src_info <> mf_src_info_q.io.deq
  io.shared_control.mf_src.compress_src_info2 <> mf_src_info2_q.io.deq
  io.shared_control.mf_dst.lit_dst_info <> mf_lit_dst_info_q.io.deq
  io.shared_control.mf_dst.seq_dst_info <> mf_seq_dst_info_q.io.deq
  io.shared_control.mf_maxoffset := cctx.bits.window_size
  io.shared_control.mf_runtime_ht_num_entries_log2 := cctx.bits.rt_hashtable_num_entries_log2

  val mf_kickoff = DecoupledHelper(
    mf_src_info_q.io.enq.ready,
    mf_src_info2_q.io.enq.ready,
    mf_lit_dst_info_q.io.enq.ready,
    mf_seq_dst_info_q.io.enq.ready,
    mf_buf_track_q.io.enq.ready,
    lit_buff_free_vec(lit_buff_idx),
    seq_buff_free_vec(seq_buff_idx),
    blocks_left_to_send,
    frameControllerState === sCompressBlocks)

  mf_src_info_q.io.enq.bits.ip := next_block_start_ptr
  mf_src_info_q.io.enq.bits.isize := next_block_size
  mf_src_info2_q.io.enq.bits.ip := next_block_start_ptr
  mf_src_info2_q.io.enq.bits.isize := next_block_size
  mf_src_info_q.io.enq.valid := mf_kickoff.fire(mf_src_info_q.io.enq.ready)
  mf_src_info2_q.io.enq.valid := mf_kickoff.fire(mf_src_info2_q.io.enq.ready)

  mf_lit_dst_info_q.io.enq.bits.op := lit_buff_start_addr
  mf_lit_dst_info_q.io.enq.bits.cmpflag := 0.U
  mf_lit_dst_info_q.io.enq.valid := mf_kickoff.fire(mf_lit_dst_info_q.io.enq.ready)

  mf_seq_dst_info_q.io.enq.bits.op := seq_buff_start_addr
  mf_seq_dst_info_q.io.enq.bits.cmpflag := 0.U
  mf_seq_dst_info_q.io.enq.valid := mf_kickoff.fire(mf_seq_dst_info_q.io.enq.ready)

  mf_buf_track_q.io.enq.bits.lit_addr := lit_buff_start_addr
  mf_buf_track_q.io.enq.bits.lit_idx := lit_buff_idx
  mf_buf_track_q.io.enq.bits.seq_addr := seq_buff_start_addr
  mf_buf_track_q.io.enq.bits.seq_idx := seq_buff_idx
  mf_buf_track_q.io.enq.bits.last_block := last_block
  mf_buf_track_q.io.enq.valid := mf_kickoff.fire(mf_buf_track_q.io.enq.ready)

  when (mf_kickoff.fire) {
    sent_block_count := sent_block_count + 1.U

    seq_buff_idx := Mux(seq_buff_idx === seq_buff_chunk_cnt - 1.U, 0.U, seq_buff_idx + 1.U)
    seq_buff_free_vec(seq_buff_idx) := false.B

    lit_buff_idx := Mux(lit_buff_idx === lit_buff_chunk_cnt - 1.U, 0.U, lit_buff_idx + 1.U)
    lit_buff_free_vec(lit_buff_idx) := false.B
  }

  when (mf_kickoff.fire) {
    CompressAccelLogger.logInfo("FRAMECONTROL_MF_FIRE\n")
    CompressAccelLogger.logInfo("sent_block_count: %d\n", sent_block_count)
    CompressAccelLogger.logInfo("consumed_src_bytes: %d\n", consumed_src_bytes)
    CompressAccelLogger.logInfo("remaining_src_bytes: %d\n", remaining_src_bytes)
    CompressAccelLogger.logInfo("next_block_start_ptr: 0x%x\n", next_block_start_ptr)
    CompressAccelLogger.logInfo("last_block: %d\n", last_block)
    CompressAccelLogger.logInfo("next_block_size: %d\n", next_block_size)
    CompressAccelLogger.logInfo("max_sequences: %d\n", max_sequences)
    CompressAccelLogger.logInfo("seq_buff_idx: %d\n", seq_buff_idx)
    CompressAccelLogger.logInfo("seq_buff_free_vec_cat: 0x%x\n", seq_buff_free_vec_cat)
    CompressAccelLogger.logInfo("seq_buff_base_addr: 0x%x\n", seq_buff_base_addr)
    CompressAccelLogger.logInfo("seq_buff_chunk_bytes: %d\n", seq_buff_chunk_bytes)
    CompressAccelLogger.logInfo("seq_buff_chunk_cnt: %d\n", seq_buff_chunk_cnt)
    CompressAccelLogger.logInfo("seq_buff_offset: %d, 0x%x\n", seq_buff_offset, seq_buff_offset)
    CompressAccelLogger.logInfo("seq_buff_start_addr: 0x%x\n", seq_buff_start_addr)
    CompressAccelLogger.logInfo("lit_buff_idx: %d\n", lit_buff_idx)
    CompressAccelLogger.logInfo("lit_buff_free_vec_cat: 0x%x\n", lit_buff_free_vec_cat)
    CompressAccelLogger.logInfo("lit_buff_base_addr: 0x%x\n", lit_buff_base_addr)
    CompressAccelLogger.logInfo("lit_buff_chunk_bytes: %d\n", lit_buff_chunk_bytes)
    CompressAccelLogger.logInfo("lit_buff_chunk_cnt: %d\n", lit_buff_chunk_cnt)
    CompressAccelLogger.logInfo("lit_buff_offset: %d, 0x%x\n", lit_buff_offset, lit_buff_offset)
    CompressAccelLogger.logInfo("lit_buff_start_addr: 0x%x\n", lit_buff_start_addr)
  }



  val mf_lit_consumed_q = Module(new Queue(UInt(64.W), queDepth))
  val mf_seq_consumed_q = Module(new Queue(UInt(64.W), queDepth))
  mf_lit_consumed_q.io.enq <> io.shared_control.mf_buff_consumed.lit_consumed_bytes
  mf_seq_consumed_q.io.enq <> io.shared_control.mf_buff_consumed.seq_consumed_bytes

  ////////////////////////////////////////////////////////////////////////////
  // 2. kick off literal compressor
  ////////////////////////////////////////////////////////////////////////////
  val litcpy_src_q = Module(new Queue(new StreamInfo, queDepth))
  val litcpy_src2_q = Module(new Queue(new StreamInfo, queDepth))
  val litcpy_dst_q = Module(new Queue(new DstWithValInfo, queDepth))
  io.zstd_control.litcpy_src <> litcpy_src_q.io.deq
  io.zstd_control.litcpy_src2 <> litcpy_src2_q.io.deq
  io.zstd_control.litcpy_dst <> litcpy_dst_q.io.deq

  val raw_block_src_q = Module(new Queue(new StreamInfo, queDepth))
  val raw_block_dst_q = Module(new Queue(new DstWithValInfo, queDepth))
  io.zstd_control.raw_block_src <> raw_block_src_q.io.deq
  io.zstd_control.raw_block_dst <> raw_block_dst_q.io.deq

  val raw_lit_src_q = Module(new Queue(new StreamInfo, queDepth))
  val raw_lit_dst_q = Module(new Queue(new DstWithValInfo, queDepth))
  io.zstd_control.raw_lit_src <> raw_lit_src_q.io.deq
  io.zstd_control.raw_lit_dst <> raw_lit_dst_q.io.deq

  val lit_buf_track_q = Module(new Queue(new ZstdHufCompressorBufTrackInfo, queDepth))

  val lit_kickoff = DecoupledHelper(
    litcpy_src_q.io.enq.ready,
    litcpy_src2_q.io.enq.ready,
    litcpy_dst_q.io.enq.ready,
    raw_block_src_q.io.enq.ready,
    raw_block_dst_q.io.enq.ready,
    raw_lit_src_q.io.enq.ready,
    raw_lit_dst_q.io.enq.ready,
    lit_buf_track_q.io.enq.ready,
    mf_lit_consumed_q.io.deq.valid,
    mf_seq_consumed_q.io.deq.valid,
    mf_buf_track_q.io.deq.valid,
    mf_dst_addr_q.io.deq.valid,
    frameControllerState === sCompressBlocks)

// when (lit_kickoff.fire) {
// when (raw_block && raw_lit) {
// assert(false.B, "Cannot be a raw block (no sequences) and a raw literal (bunch of sequences, small literal) at the same time")
// }
// }

  val raw_lit = (mf_lit_consumed_q.io.deq.bits <= 16.U) // TODO : expose this as a knob
  val raw_block = (mf_seq_consumed_q.io.deq.bits === 0.U)

  litcpy_src_q.io.enq.bits.ip := mf_buf_track_q.io.deq.bits.lit_addr
  litcpy_src_q.io.enq.bits.isize := mf_lit_consumed_q.io.deq.bits
  litcpy_src_q.io.enq.valid := lit_kickoff.fire(litcpy_src_q.io.enq.ready, !raw_block, !raw_lit)

  litcpy_src2_q.io.enq.bits.ip := mf_buf_track_q.io.deq.bits.lit_addr
  litcpy_src2_q.io.enq.bits.isize := mf_lit_consumed_q.io.deq.bits
  litcpy_src2_q.io.enq.valid := lit_kickoff.fire(litcpy_src2_q.io.enq.ready, !raw_block, !raw_lit)

  val lit_dst_start_addr = mf_dst_addr_q.io.deq.bits + ZSTD_BLOCKHEADER_BYTES.U
  litcpy_dst_q.io.enq.bits.op := lit_dst_start_addr
  litcpy_dst_q.io.enq.bits.cmpflag := 0.U
  litcpy_dst_q.io.enq.bits.cmpval := 0.U
  litcpy_dst_q.io.enq.valid := lit_kickoff.fire(litcpy_dst_q.io.enq.ready, !raw_block, !raw_lit)

  raw_block_src_q.io.enq.bits.ip := mf_buf_track_q.io.deq.bits.lit_addr
  raw_block_src_q.io.enq.bits.isize := mf_lit_consumed_q.io.deq.bits
  raw_block_src_q.io.enq.valid := lit_kickoff.fire(raw_block_src_q.io.enq.ready, raw_block)

  raw_block_dst_q.io.enq.bits.op := lit_dst_start_addr
  raw_block_dst_q.io.enq.bits.cmpflag := 0.U
  raw_block_dst_q.io.enq.bits.cmpval := 0.U
  raw_block_dst_q.io.enq.valid := lit_kickoff.fire(raw_block_dst_q.io.enq.ready, raw_block)

  raw_lit_src_q.io.enq.bits.ip := mf_buf_track_q.io.deq.bits.lit_addr
  raw_lit_src_q.io.enq.bits.isize := mf_lit_consumed_q.io.deq.bits
  raw_lit_src_q.io.enq.valid := lit_kickoff.fire(raw_lit_src_q.io.enq.ready, raw_lit)

  raw_lit_dst_q.io.enq.bits.op := lit_dst_start_addr
  raw_lit_dst_q.io.enq.bits.cmpflag := 0.U
  raw_lit_dst_q.io.enq.bits.cmpval := 0.U
  raw_lit_dst_q.io.enq.valid := lit_kickoff.fire(raw_lit_dst_q.io.enq.ready, raw_lit)

  lit_buf_track_q.io.enq.bits.lit_idx := mf_buf_track_q.io.deq.bits.lit_idx
  lit_buf_track_q.io.enq.bits.block_hdr_addr := mf_dst_addr_q.io.deq.bits
  lit_buf_track_q.io.enq.bits.lit_dst_start_addr := lit_dst_start_addr
  lit_buf_track_q.io.enq.bits.seq_consumed_bytes := mf_seq_consumed_q.io.deq.bits
  lit_buf_track_q.io.enq.bits.raw_literal := raw_lit
  lit_buf_track_q.io.enq.bits.seq_addr := mf_buf_track_q.io.deq.bits.seq_addr
  lit_buf_track_q.io.enq.bits.seq_idx := mf_buf_track_q.io.deq.bits.seq_idx
  lit_buf_track_q.io.enq.bits.last_block := mf_buf_track_q.io.deq.bits.last_block
  lit_buf_track_q.io.enq.valid := lit_kickoff.fire(lit_buf_track_q.io.enq.ready)

  mf_lit_consumed_q.io.deq.ready := lit_kickoff.fire(mf_lit_consumed_q.io.deq.valid)
  mf_seq_consumed_q.io.deq.ready := lit_kickoff.fire(mf_seq_consumed_q.io.deq.valid)
  mf_buf_track_q.io.deq.ready := lit_kickoff.fire(mf_buf_track_q.io.deq.valid)
  mf_dst_addr_q.io.deq.ready := lit_kickoff.fire(mf_dst_addr_q.io.deq.valid)

  when (lit_kickoff.fire) {
    CompressAccelLogger.logInfo("FRAMECONTROL_LIT_FIRE\n")
    CompressAccelLogger.logInfo("litcpy_src_q.io.enq.bits.ip: 0x%x\n", litcpy_src_q.io.enq.bits.ip)
    CompressAccelLogger.logInfo("litcpy_src_q.io.enq.bits.isize: %d\n", litcpy_src_q.io.enq.bits.isize)
    CompressAccelLogger.logInfo("litcpy_dst_q.io.enq.bits.op: 0x%x\n", litcpy_dst_q.io.enq.bits.op)
    CompressAccelLogger.logInfo("lit_idx: %d\n", mf_buf_track_q.io.deq.bits.lit_idx)
    CompressAccelLogger.logInfo("block_hdr_addr: 0x%x\n", mf_dst_addr_q.io.deq.bits)
    CompressAccelLogger.logInfo("lit_dst_start_addr: 0x%x\n", lit_dst_start_addr)
    CompressAccelLogger.logInfo("seq_consumed_bytes: %d\n", mf_seq_consumed_q.io.deq.bits)
    CompressAccelLogger.logInfo("seq_addr: 0x%x\n", mf_buf_track_q.io.deq.bits.seq_addr)
    CompressAccelLogger.logInfo("seq_idx: %d\n", mf_buf_track_q.io.deq.bits.seq_idx)
    CompressAccelLogger.logInfo("last_block: %d\n", mf_buf_track_q.io.deq.bits.last_block)
    CompressAccelLogger.logInfo("raw_block: %d\n", raw_block)
    CompressAccelLogger.logInfo("raw_lit: %d\n", raw_lit)
  }

  val litbytes_written_q = Module(new Queue(UInt(64.W), queDepth))
  litbytes_written_q.io.enq <> io.zstd_control.litbytes_written

  val raw_blockbytes_written_q = Module(new Queue(UInt(64.W), queDepth))
  raw_blockbytes_written_q.io.enq <> io.zstd_control.raw_blockbytes_written

  val raw_litbytes_written_q = Module(new Queue(UInt(64.W), queDepth))
  raw_litbytes_written_q.io.enq <> io.zstd_control.raw_litbytes_written

  ////////////////////////////////////////////////////////////////////////////
  // 3. kick off sequence compressor
  ////////////////////////////////////////////////////////////////////////////
  val seqcpy_src_q = Module(new Queue(new StreamInfo, queDepth))
  val seqcpy_dst_q = Module(new Queue(new DstWithValInfo, queDepth))
  io.zstd_control.seqcpy_src <> seqcpy_src_q.io.deq
  io.zstd_control.seqcpy_dst <> seqcpy_dst_q.io.deq

  val seq_buf_track_q = Module(new Queue(new ZstdFSECompressorBufTrackInfo, queDepth))

  val litbytes_deq_valid = litbytes_written_q.io.deq.valid || raw_blockbytes_written_q.io.deq.valid || raw_litbytes_written_q.io.deq.valid

  val seq_kickoff = DecoupledHelper(
    seqcpy_src_q.io.enq.ready,
    seqcpy_dst_q.io.enq.ready,
    seq_buf_track_q.io.enq.ready,
    litbytes_deq_valid,
    lit_buf_track_q.io.deq.valid,
    frameControllerState === sCompressBlocks)

  val no_sequence_block = (lit_buf_track_q.io.deq.bits.seq_consumed_bytes === 0.U)
  val raw_literal_block = lit_buf_track_q.io.deq.bits.raw_literal

  seqcpy_src_q.io.enq.bits.ip := lit_buf_track_q.io.deq.bits.seq_addr
  seqcpy_src_q.io.enq.bits.isize := lit_buf_track_q.io.deq.bits.seq_consumed_bytes
  seqcpy_src_q.io.enq.valid := seq_kickoff.fire(seqcpy_src_q.io.enq.ready, !no_sequence_block)

  val literals_written = Mux(no_sequence_block, raw_blockbytes_written_q.io.deq.bits,
                          Mux(raw_literal_block, raw_litbytes_written_q.io.deq.bits,
                            litbytes_written_q.io.deq.bits))

  val seq_dst_start_addr = lit_buf_track_q.io.deq.bits.lit_dst_start_addr + literals_written
  seqcpy_dst_q.io.enq.bits.op := seq_dst_start_addr
  seqcpy_dst_q.io.enq.bits.cmpflag := 0.U
  seqcpy_dst_q.io.enq.bits.cmpval := 0.U
  seqcpy_dst_q.io.enq.valid := seq_kickoff.fire(seqcpy_dst_q.io.enq.ready, !no_sequence_block)

  seq_buf_track_q.io.enq.bits.lit_idx := lit_buf_track_q.io.deq.bits.lit_idx
  seq_buf_track_q.io.enq.bits.block_hdr_addr := lit_buf_track_q.io.deq.bits.block_hdr_addr
  seq_buf_track_q.io.enq.bits.lit_written_bytes := literals_written
  seq_buf_track_q.io.enq.bits.no_sequence_block := no_sequence_block
  seq_buf_track_q.io.enq.bits.seq_dst_start_addr := seq_dst_start_addr
  seq_buf_track_q.io.enq.bits.seq_idx := lit_buf_track_q.io.deq.bits.seq_idx
  seq_buf_track_q.io.enq.bits.last_block := lit_buf_track_q.io.deq.bits.last_block
  seq_buf_track_q.io.enq.valid := seq_kickoff.fire(seq_buf_track_q.io.enq.ready)

  litbytes_written_q.io.deq.ready := seq_kickoff.fire(litbytes_deq_valid, !no_sequence_block, !raw_literal_block)
  raw_blockbytes_written_q.io.deq.ready := seq_kickoff.fire(litbytes_deq_valid, no_sequence_block)
  raw_litbytes_written_q.io.deq.ready := seq_kickoff.fire(litbytes_deq_valid, raw_literal_block)
  lit_buf_track_q.io.deq.ready := seq_kickoff.fire(lit_buf_track_q.io.deq.valid)

  when (seq_kickoff.fire) {
    CompressAccelLogger.logInfo("FRAMECONTROL_SEQ_FIRE\n")
    CompressAccelLogger.logInfo("seqcpy_src_q.io.enq.bits.ip: 0x%x\n", seqcpy_src_q.io.enq.bits.ip)
    CompressAccelLogger.logInfo("seqcpy_src_q.io.enq.bits.isize: %d\n", seqcpy_src_q.io.enq.bits.isize)
    CompressAccelLogger.logInfo("seqcpy_dst_q.io.enq.bits.op: 0x%x\n", seqcpy_dst_q.io.enq.bits.op)
    CompressAccelLogger.logInfo("lit_idx: %d\n", lit_buf_track_q.io.deq.bits.lit_idx)
    CompressAccelLogger.logInfo("block_hdr_addr: 0x%x\n", lit_buf_track_q.io.deq.bits.block_hdr_addr)
    CompressAccelLogger.logInfo("lit_written_bytes: %d\n", litbytes_written_q.io.deq.bits)
    CompressAccelLogger.logInfo("seq_dst_start_addr: 0x%x\n", seq_dst_start_addr)
    CompressAccelLogger.logInfo("seq_idx: %d\n", lit_buf_track_q.io.deq.bits.seq_idx)
    CompressAccelLogger.logInfo("last_block: %d\n", lit_buf_track_q.io.deq.bits.last_block)
    CompressAccelLogger.logInfo("seqcpy_src_q.io.enq.fire: %d\n", seqcpy_src_q.io.enq.fire)
    CompressAccelLogger.logInfo("seqcpy_dst_q.io.enq.fire: %d\n", seqcpy_dst_q.io.enq.fire)
  }

  val seqbytes_written_q = Module(new Queue(UInt(64.W), queDepth))
  seqbytes_written_q.io.enq <> io.zstd_control.seqbytes_written

  ////////////////////////////////////////////////////////////////////////////
  // 4. free corresponding buffers & write block header
  ////////////////////////////////////////////////////////////////////////////
  val bhdr_memwriter = Module(new ZstdCompressorMemWriter(circularQueDepth=2, writeCmpFlag=true))
  io.zstd_control.l2io.bhdr_l2userif <> bhdr_memwriter.io.l2io

  val sequence_execute_done = seqbytes_written_q.io.deq.valid || (seq_buf_track_q.io.deq.bits.no_sequence_block && seq_buf_track_q.io.deq.valid)

  val block_hdr_write = DecoupledHelper(
    bhdr_memwriter.io.memwrites_in.ready,
    bhdr_memwriter.io.dest_info.ready,
    sequence_execute_done,
    seq_buf_track_q.io.deq.valid,
    mf_dst_addr_q.io.enq.ready,
    io.src_info.valid,
    io.dst_info.valid,
    io.buff_info.lit.valid,
    io.buff_info.seq.valid,
    io.clevel_info.valid,
    io.finished_cnt.ready,
    frameControllerState === sCompressBlocks)

  val prev_seqbytes_written_q_deq_valid = RegNext(seqbytes_written_q.io.deq.valid)
  when (seqbytes_written_q.io.deq.valid && !prev_seqbytes_written_q_deq_valid) {
    CompressAccelLogger.logInfo("FRAMECONTROL_RECEIVED_SEQBYTES_WRITTEN_Q\n")
    CompressAccelLogger.logInfo("bhdr_memwriter.io.memwrites_in.ready %d\n", bhdr_memwriter.io.memwrites_in.ready)
    CompressAccelLogger.logInfo("bhdr_memwriter.io.dest_info.ready %d\n", bhdr_memwriter.io.dest_info.ready)
    CompressAccelLogger.logInfo("seqbytes_written_q.io.deq.valid %d\n", seqbytes_written_q.io.deq.valid)
    CompressAccelLogger.logInfo("seq_buf_track_q.io.deq.valid %d\n", seq_buf_track_q.io.deq.valid)
    CompressAccelLogger.logInfo("mf_dst_addr_q.io.enq.ready %d\n", mf_dst_addr_q.io.enq.ready)
    CompressAccelLogger.logInfo("io.src_info.valid %d\n", io.src_info.valid)
    CompressAccelLogger.logInfo("io.dst_info.valid %d\n", io.dst_info.valid)
    CompressAccelLogger.logInfo("io.buff_info.lit.valid %d\n", io.buff_info.lit.valid)
    CompressAccelLogger.logInfo("io.buff_info.seq.valid %d\n", io.buff_info.seq.valid)
    CompressAccelLogger.logInfo("io.clevel_info.valid %d\n", io.clevel_info.valid)
    CompressAccelLogger.logInfo("io.finished_cnt.ready %d\n", io.finished_cnt.ready)
  }

  val last_block_header = seq_buf_track_q.io.deq.bits.last_block
  val last_block_header_bit  = Wire(UInt(1.W))
  last_block_header_bit := last_block_header

  val block_type = Wire(UInt(2.W))
  block_type := Mux(seq_buf_track_q.io.deq.bits.no_sequence_block, 0.U, 2.U)

  val lit_written_bytes = seq_buf_track_q.io.deq.bits.lit_written_bytes
  val seq_written_bytes = Mux(seq_buf_track_q.io.deq.bits.no_sequence_block, 0.U, seqbytes_written_q.io.deq.bits)
  val block_written_bytes = lit_written_bytes + seq_written_bytes
  val nxt_total_compressed_bytes = total_compressed_bytes + block_written_bytes + ZSTD_BLOCKHEADER_BYTES.U

  bhdr_memwriter.io.memwrites_in.bits.data := Cat(block_written_bytes(20, 0),
                                                  block_type(1, 0),
                                                  last_block_header_bit(0, 0))
  bhdr_memwriter.io.memwrites_in.bits.validbytes := ZSTD_BLOCKHEADER_BYTES.U
  bhdr_memwriter.io.memwrites_in.bits.end_of_message := true.B
  bhdr_memwriter.io.memwrites_in.valid := block_hdr_write.fire(bhdr_memwriter.io.memwrites_in.ready)

  bhdr_memwriter.io.dest_info.bits.op := seq_buf_track_q.io.deq.bits.block_hdr_addr
  bhdr_memwriter.io.dest_info.bits.cmpflag := cmpflag
  bhdr_memwriter.io.dest_info.bits.cmpval := Mux(last_block_header, nxt_total_compressed_bytes, 0.U)
  bhdr_memwriter.io.dest_info.valid := block_hdr_write.fire(bhdr_memwriter.io.dest_info.ready)

  mf_dst_addr_q.io.enq.valid := write_frame_header_fire.fire(mf_dst_addr_q.io.enq.ready) ||
                                block_hdr_write.fire(mf_dst_addr_q.io.enq.ready, !last_block_header)
  mf_dst_addr_q.io.enq.bits := Mux(write_frame_header_fire.fire, 
                                   dst_base + cctx.bits.frame_header_bytes,
                                   seq_buf_track_q.io.deq.bits.seq_dst_start_addr + seq_written_bytes)

  io.finished_cnt.valid := block_hdr_write.fire(io.finished_cnt.ready, last_block_header)
  io.finished_cnt.bits := track_buf_cnt

  seqbytes_written_q.io.deq.ready := block_hdr_write.fire(sequence_execute_done)
  seq_buf_track_q.io.deq.ready := block_hdr_write.fire(seq_buf_track_q.io.deq.valid)
  io.src_info.ready := block_hdr_write.fire(io.src_info.valid, last_block_header)
  io.dst_info.ready := block_hdr_write.fire(io.dst_info.valid, last_block_header)
  io.buff_info.lit.ready := block_hdr_write.fire(io.buff_info.lit.valid, last_block_header)
  io.buff_info.seq.ready := block_hdr_write.fire(io.buff_info.seq.valid, last_block_header)
  io.clevel_info.ready := block_hdr_write.fire(io.clevel_info.valid, last_block_header)

  when (block_hdr_write.fire) {
    total_compressed_bytes := nxt_total_compressed_bytes

    lit_buff_free_vec(seq_buf_track_q.io.deq.bits.lit_idx) := true.B
    seq_buff_free_vec(seq_buf_track_q.io.deq.bits.seq_idx) := true.B

    when (last_block_header) {
      frameControllerState := sWriteFrameHeader
      src_offset := 0.U
      sent_block_count := 0.U
      seq_buff_idx := 0.U
      lit_buff_idx := 0.U
      total_compressed_bytes := 0.U
    }
  }

  when (block_hdr_write.fire) {
    CompressAccelLogger.logInfo("FRAMECONTROL_BLOCKHDRWRITE_FIRE\n")
    CompressAccelLogger.logInfo("op 0x%x\n", seq_buf_track_q.io.deq.bits.block_hdr_addr)
    CompressAccelLogger.logInfo("cmpval: %d\n", bhdr_memwriter.io.dest_info.bits.cmpval)
    CompressAccelLogger.logInfo("data 0x%x\n", bhdr_memwriter.io.memwrites_in.bits.data)
    CompressAccelLogger.logInfo("validbytes %d\n", bhdr_memwriter.io.memwrites_in.bits.validbytes)
    CompressAccelLogger.logInfo("end_of_message %d\n", bhdr_memwriter.io.memwrites_in.bits.end_of_message)
    CompressAccelLogger.logInfo("seq_written_bytes: %d\n", seqbytes_written_q.io.deq.bits)
    CompressAccelLogger.logInfo("lit_idx: %d\n", seq_buf_track_q.io.deq.bits.lit_idx)
    CompressAccelLogger.logInfo("lit_buff_free_vec_cat: 0x%x\n", lit_buff_free_vec_cat)
    CompressAccelLogger.logInfo("seq_idx: %d\n", seq_buf_track_q.io.deq.bits.seq_idx)
    CompressAccelLogger.logInfo("seq_buff_free_vec_cat: 0x%x\n", seq_buff_free_vec_cat)
  }

  when (mf_dst_addr_q.io.enq.fire) {
    CompressAccelLogger.logInfo("FRAMECONTROL_DST_ADDRQ_ENQ_FIRE\n")
    CompressAccelLogger.logInfo("dst_addr: 0x%x\n", mf_dst_addr_q.io.enq.bits)
  }
}


class ZstdCompressionDefaultParamsBundle extends Bundle {
  val windowLog2 = UInt(5.W)
  val hashTableEntriesLog2 = UInt(5.W)
  val minMatchLength = UInt(5.W)
}

class ZstdCompressionContextParamsBundle extends Bundle {
  val window_size = UInt((ZSTD_WINDOWSIZELOG_MAX + 1).W)
  val single_segment = Bool()
  val frame_header = UInt(112.W)
  val frame_header_bytes = UInt(5.W)
  val block_size = UInt((ZSTD_BLOCKSIZELOG_MAX + 1).W)
  val min_match_length = UInt(5.W)
  val rt_hashtable_num_entries_log2 = UInt(5.W)
}

class ZstdFrameHeaderInputBundle extends Bundle {
  val src_size = UInt(64.W)
  val clevel = UInt(5.W)
}

class ZstdCompressorFrameHeaderBuilderIO extends Bundle {
  val input_info = Flipped(Valid(new ZstdFrameHeaderInputBundle))
  val cctx = Valid(new ZstdCompressionContextParamsBundle)

  val print_info = Input(Bool())
}

class ZstdCompressorFrameHeaderBuilder(implicit p: Parameters) extends ZstdCompressorModule {
  val io = IO(new ZstdCompressorFrameHeaderBuilderIO)

  val ZSTD_DEFAULT_CPARAMS = Reg(Vec(ZSTD_MAX_COMPRESSION_LEVEL, new ZstdCompressionDefaultParamsBundle))
  val init_zstd_default_params = RegInit(false.B)


  // NOTE : tune these params to correct values, currently set to test stuff
  val ZSTD_window_log2 = List(
    16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16,
    17, 18, 19, 20, 21, 21, 21, 21, 21, 21, 21, 21
  )

  when (!init_zstd_default_params) {
    init_zstd_default_params := true.B
    for (i <- 0 until ZSTD_MAX_COMPRESSION_LEVEL) {
      val window_log2 = ZSTD_window_log2(i).U
      ZSTD_DEFAULT_CPARAMS(i).windowLog2 := window_log2
      ZSTD_DEFAULT_CPARAMS(i).minMatchLength := 4.U
      ZSTD_DEFAULT_CPARAMS(i).hashTableEntriesLog2 := 14.U

      CompressAccelLogger.logInfo("init_zstd_default_params, comp level %d: windowLog2: %d, minMatchLength: %d\n", 
        i.U, window_log2, 4.U)
    }
  }

  val ZSTD_MAGIC_NUMBER = BigInt("FD2FB528", 16) 
  val checksum_flag = 0.U
  val dict_id_size_code = 0.U
  val pledged_src_size = io.input_info.bits.src_size
  val windowLog2 = ZSTD_DEFAULT_CPARAMS(io.input_info.bits.clevel).windowLog2
  val rt_hashtable_num_entries_log2 = ZSTD_DEFAULT_CPARAMS(io.input_info.bits.clevel).hashTableEntriesLog2
  val minMatchLength = ZSTD_DEFAULT_CPARAMS(io.input_info.bits.clevel).minMatchLength
  val window_size = 1.U << windowLog2
  val single_segment = window_size >= pledged_src_size
  val window_log_byte = ((windowLog2 - ZSTD_WINDOWLOG_ABSOLUTEMIN.U) << 3.U)
  val fcs_code = Mux(pledged_src_size >= BigInt("FFFFFFFF", 16).U, 3.U,
                  Mux(pledged_src_size >= 65536.U + 256.U, 2.U,
                    Mux(pledged_src_size >= 256.U, 1.U,
                      0.U)))
  val frame_header_description_byte = WireInit(0.U(8.W))
  frame_header_description_byte := Cat(fcs_code(1, 0),
                                       single_segment.asUInt(0),
                                       0.U(1.W),
                                       0.U(1.W),
                                       checksum_flag(0),
                                       dict_id_size_code(1, 0))

  // ZSTD Frame Header Format
  // | MagicNumber (4B) | FrameHeaderDesc (1B) | [WindowLogBytes (1B)] | [DictId (ignore)] | [FCS (0-8B)] |
  //                                           |
  //                                           fhd_mn

  val fhd_mn = Wire(UInt(40.W))
  fhd_mn := Cat(frame_header_description_byte, ZSTD_MAGIC_NUMBER.U(32.W))

  val frame_content_size = WireInit(0.U(64.W))
  frame_content_size := Mux((fcs_code === 0.U) && single_segment, pledged_src_size,
                         Mux(fcs_code === 1.U, pledged_src_size - 256.U,
                           Mux(fcs_code === 2.U, pledged_src_size,
                             Mux(fcs_code === 3.U, pledged_src_size,
                               0.U /* unknown */))))

  val fcs_wl_fhd_mn = Wire(UInt(112.W))
  fcs_wl_fhd_mn := Cat(frame_content_size,
                       window_log_byte(7, 0),
                       fhd_mn)

  val fcs_fhd_mn = Wire(UInt(104.W))
  fcs_fhd_mn := Cat(frame_content_size,
                    fhd_mn)

  val fcs_bytes = Mux((fcs_code === 0.U) && single_segment, 1.U,
                    Mux(fcs_code === 1.U, 2.U,
                      Mux(fcs_code === 2.U, 4.U,
                        Mux(fcs_code === 3.U, 8.U,
                          0.U))))
  val wl_byte = Mux(!single_segment, 1.U, 0.U)
  val hdr_bytes = fcs_bytes + wl_byte + 1.U + 4.U

  val min_window_size = Mux(window_size < pledged_src_size, window_size, pledged_src_size)
  val window_size_used = Mux(1.U > min_window_size, 1.U, min_window_size)
  val block_size = Mux(ZSTD_BLOCKSIZE_MAX.U < window_size_used, ZSTD_BLOCKSIZE_MAX.U, window_size_used)


  io.cctx.valid := RegNext(io.input_info.valid)
  io.cctx.bits.window_size := window_size
  io.cctx.bits.single_segment := single_segment
  io.cctx.bits.frame_header := Mux(!single_segment, fcs_wl_fhd_mn, fcs_fhd_mn)
  io.cctx.bits.frame_header_bytes := hdr_bytes
  io.cctx.bits.block_size := block_size
  io.cctx.bits.min_match_length := minMatchLength
  io.cctx.bits.rt_hashtable_num_entries_log2 := rt_hashtable_num_entries_log2

  when (io.print_info) {
    CompressAccelLogger.logInfo("[*] Compression Context Params!!!\n")
    CompressAccelLogger.logInfo("checksum_flag: %d\n", checksum_flag)
    CompressAccelLogger.logInfo("pledged_src_size: 0x%x\n", pledged_src_size)
    CompressAccelLogger.logInfo("window_size: 0x%x\n", window_size)
    CompressAccelLogger.logInfo("single_segment: %d\n", single_segment)
    CompressAccelLogger.logInfo("window_log_byte: 0x%x\n", window_log_byte)
    CompressAccelLogger.logInfo("fcs_code: %d\n", fcs_code)
    CompressAccelLogger.logInfo("frame_header_description_byte: 0x%x\n", frame_header_description_byte)
    CompressAccelLogger.logInfo("frame_content_size: 0x%x\n", frame_content_size)
    CompressAccelLogger.logInfo("fhd_mn: 0x%x\n", fhd_mn)
    CompressAccelLogger.logInfo("fcs_wl_fhd_mn: 0x%x\n", fcs_wl_fhd_mn)
    CompressAccelLogger.logInfo("fcs_fhd_mn: 0x%x\n", fcs_fhd_mn)
    CompressAccelLogger.logInfo("fcs_bytes: %d\n", fcs_bytes)
    CompressAccelLogger.logInfo("wl_byte: %d\n", wl_byte)
    CompressAccelLogger.logInfo("hdr_bytes: %d\n", hdr_bytes)
    CompressAccelLogger.logInfo("min_window_size: 0x%x\n", min_window_size)
    CompressAccelLogger.logInfo("window_size_used: 0x%x\n", window_size_used)
    CompressAccelLogger.logInfo("block_size: 0x%x\n", block_size)
    CompressAccelLogger.logInfo("minMatchLength: %d\n", minMatchLength)
  }
}
