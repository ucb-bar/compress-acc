package compressacc

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheArbiter}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._


class ZstdFrameDecompressor(zstdTop: ZstdDecompressor, cmd_que_depth: Int)
  (implicit p: Parameters) extends LazyModule {

  override lazy val module = new ZstdFrameDecompressorImp(this, zstdTop, cmd_que_depth)
}

class FSEL2MemHelpers extends Bundle {
  val read_dtbuild_userif = new L2MemHelperBundle
//  val write_dtbuild_userif = new L2MemHelperBundle
  val read_dtread_userif = new L2MemHelperBundle
  val read_seqexec_userif = new L2MemHelperBundle
  val read_histlookup_userif = new L2MemHelperBundle
  val write_seqexec_userif = new L2MemHelperBundle
}
class RawRLEL2MemHelpers extends Bundle {
  val read_rawrle_userif = new L2MemHelperBundle
}

class ZstdFrameDecompressorIO(implicit val p: Parameters) extends Bundle {
  val frame_content = Flipped(Decoupled(new FrameContentInfo))

  val decompressed_frame = Decoupled(new DecompressedFrameInfo)

  val l2_bhdr_userif = new L2MemHelperBundle
  val l2_huf_memhelpers = new HufL2MemHelpers
  val l2_fse_memhelpers = new FSEL2MemHelpers
  val l2_rawrle_memhelpers = new RawRLEL2MemHelpers

  val MAX_OFFSET_ALLOWED = Input(UInt(64.W))
  val ALGORITHM = Input(UInt(1.W))

  val snappy_decompress_src_info = Flipped(Decoupled(new StreamInfo))
  val snappy_decompress_dest_info = Flipped(Decoupled(new SnappyDecompressDestInfo))
  val snappy_decompress_dest_info_offchip = Flipped(Decoupled(new SnappyDecompressDestInfo))
  val snappy_bufs_completed = Output(UInt(64.W))
  val snappy_no_writes_inflight = Output(Bool())
}


/* The frame decompressor is responsible for decompressing the Zstd frame contents (the frame 
 * headers are already stripped off in the CommandExpander).
 * It receives commands through the "frame_content" port and returns the decompressed frame
 * data using the "decompressed_frame" port. It pushes commands to the BlockDecompressor
 * until the "last_block" signal has been received.
 */
class ZstdFrameDecompressorImp(outer: ZstdFrameDecompressor, zstdTop: ZstdDecompressor, cmd_que_depth: Int) 
  (implicit p: Parameters) extends LazyModuleImp(outer) {
  val io = IO(new ZstdFrameDecompressorIO)
  dontTouch(io)

  val STATE_IDLE = 0.U
  val STATE_BUSY = 1.U
  val state = RegInit(0.U(4.W))

  val frame_content_q = Module(new Queue(new FrameContentInfo, cmd_que_depth)).io
  frame_content_q.enq <> io.frame_content

  val decompressed_frame_q = Module(new Queue(new DecompressedFrameInfo, cmd_que_depth)).io
  io.decompressed_frame <> decompressed_frame_q.deq

  val block_decompressor = Module(new ZstdBlockDecompressor(zstdTop, cmd_que_depth))
  block_decompressor.io.MAX_OFFSET_ALLOWED := io.MAX_OFFSET_ALLOWED
  block_decompressor.io.ALGORITHM := io.ALGORITHM
  block_decompressor.io.snappy_decompress_src_info <> io.snappy_decompress_src_info
  block_decompressor.io.snappy_decompress_dest_info <> io.snappy_decompress_dest_info
  block_decompressor.io.snappy_decompress_dest_info_offchip <> io.snappy_decompress_dest_info_offchip
  io.snappy_bufs_completed := block_decompressor.io.snappy_bufs_completed
  io.snappy_no_writes_inflight := block_decompressor.io.snappy_no_writes_inflight

  io.l2_bhdr_userif <> block_decompressor.io.l2_bhdr_userif
  io.l2_huf_memhelpers <> block_decompressor.io.l2_huf_memhelpers
  io.l2_fse_memhelpers <> block_decompressor.io.l2_fse_memhelpers
  io.l2_rawrle_memhelpers <> block_decompressor.io.l2_rawrle_memhelpers

  val compressed_block_info_q = Module(new Queue(new CompressedBlockInfo, cmd_que_depth)).io
  val decompressed_block_info_q = Module(new Queue(new DecompressedBlockInfo, cmd_que_depth)).io
  block_decompressor.io.compressed_block_info <> compressed_block_info_q.deq
  decompressed_block_info_q.enq <> block_decompressor.io.decompressed_block_info

// val block_src_info_q = Module(new Queue(new DecompressPtrInfo, cmd_que_depth)).io
// val block_dst_info_q = Module(new Queue(new DecompressDstInfo, cmd_que_depth)).io
// val block_wksp_info_q = Module(new Queue(new DecompressPtrInfo, cmd_que_depth)).io

  val frame_consumed_bytes = RegInit(0.U(64.W))
  val frame_written_bytes = RegInit(0.U(64.W))

  val block_decompressor_fire = DecoupledHelper(
                                  compressed_block_info_q.enq.ready,
                                  frame_content_q.deq.valid,
                                  state === STATE_IDLE)

  val MAX_LL_TABLE_BYTES = 4096
  val MAX_ML_TABLE_BYTES = 4096
  val MAX_OF_TABLE_BYTES = 2048

  // Workspace layout
  // | ll table (4kB) | ml table (4kB) | of table (2kB) | block lit/seq
  // - the ll table, ml table, of table can be reused for different blocks
  val wksp_base_addr = frame_content_q.deq.bits.wp
  val ll_table_start_addr = wksp_base_addr
  val ml_table_start_addr = ll_table_start_addr + MAX_LL_TABLE_BYTES.U
  val of_table_start_addr = ml_table_start_addr + MAX_ML_TABLE_BYTES.U
  val lit_seq_start_addr  = of_table_start_addr + MAX_OF_TABLE_BYTES.U

  val block_src_start_addr = frame_content_q.deq.bits.ip + frame_consumed_bytes
  val block_dst_start_addr = frame_content_q.deq.bits.dst.op + frame_written_bytes

  compressed_block_info_q.enq.valid := block_decompressor_fire.fire(compressed_block_info_q.enq.ready)
  compressed_block_info_q.enq.bits.ip.ip := block_src_start_addr
  compressed_block_info_q.enq.bits.ll_dic.ip := ll_table_start_addr
  compressed_block_info_q.enq.bits.ml_dic.ip := ml_table_start_addr
  compressed_block_info_q.enq.bits.of_dic.ip := of_table_start_addr
  compressed_block_info_q.enq.bits.wp.ip := lit_seq_start_addr
  compressed_block_info_q.enq.bits.dst.op := block_dst_start_addr
  compressed_block_info_q.enq.bits.dst.cmpflag := frame_content_q.deq.bits.dst.cmpflag

  when (block_decompressor_fire.fire) {
    CompressAccelLogger.logInfo("Frame Decompressor initiated Block Decompressor!\n")
    CompressAccelLogger.logInfo("src_start_addr: 0x%x\n", block_src_start_addr)
    CompressAccelLogger.logInfo("dst_start_addr: 0x%x\n", block_dst_start_addr)
    CompressAccelLogger.logInfo("ll_table_start_addr: 0x%x\n", ll_table_start_addr)
    CompressAccelLogger.logInfo("ml_table_start_addr: 0x%x\n", ml_table_start_addr)
    CompressAccelLogger.logInfo("of_table_start_addr: 0x%x\n", of_table_start_addr)
    CompressAccelLogger.logInfo("lit_seq_start_addr: 0x%x\n", lit_seq_start_addr)
  }

  val block_decompressor_done_fire = DecoupledHelper(
                                      frame_content_q.deq.valid,
                                      decompressed_frame_q.enq.ready,
                                      decompressed_block_info_q.deq.valid,
                                      state === STATE_BUSY)

  decompressed_block_info_q.deq.ready := block_decompressor_done_fire.fire(decompressed_block_info_q.deq.valid)

  val last_block = decompressed_block_info_q.deq.bits.last_block
  val consumed_src_bytes = decompressed_block_info_q.deq.bits.block_size
  val written_bytes = decompressed_block_info_q.deq.bits.written_bytes
  val nxt_frame_consumed_bytes = frame_consumed_bytes + consumed_src_bytes
  val nxt_frame_written_bytes = frame_written_bytes + written_bytes


  decompressed_block_info_q.deq.ready := block_decompressor_done_fire.fire(decompressed_block_info_q.deq.valid)
  decompressed_frame_q.enq.valid := block_decompressor_done_fire.fire(
                                      decompressed_frame_q.enq.ready,
                                      last_block)
  decompressed_frame_q.enq.bits.src_consumed_bytes := nxt_frame_consumed_bytes // Total bytes consumed for this frame
  decompressed_frame_q.enq.bits.dst_written_bytes := nxt_frame_written_bytes   // Total bytes written for this frame

  frame_content_q.deq.ready := block_decompressor_done_fire.fire(frame_content_q.deq.valid,
                                                                 last_block)


  when (block_decompressor_done_fire.fire && !last_block) {
    frame_consumed_bytes := nxt_frame_consumed_bytes
    frame_written_bytes := nxt_frame_written_bytes
  }

  when (block_decompressor_done_fire.fire && last_block) {
    frame_consumed_bytes := 0.U
    frame_written_bytes := 0.U
  }

  when (block_decompressor_done_fire.fire) {
    CompressAccelLogger.logInfo("Block Decompressor returned to Frame Decompressor!\n")
    CompressAccelLogger.logInfo("last_block: %d\n", last_block)
    CompressAccelLogger.logInfo("consumed_src_bytes: %d\n", consumed_src_bytes)
    CompressAccelLogger.logInfo("written_bytes: %d\n", written_bytes)
    CompressAccelLogger.logInfo("nxt_frame_consumed_bytes: %d\n", nxt_frame_consumed_bytes)
    CompressAccelLogger.logInfo("nxt_frame_written_bytes: %d\n", nxt_frame_written_bytes)
  }

  switch (state) {
    is (STATE_IDLE) {
      when (block_decompressor_fire.fire) {
        state := STATE_BUSY
      }
    }

    is (STATE_BUSY) {
      when (block_decompressor_done_fire.fire) {
        state := STATE_IDLE
      }
    }
  }
}
