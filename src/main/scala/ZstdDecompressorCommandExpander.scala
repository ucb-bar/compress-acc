package compressacc

import chisel3._
import chisel3.util._
import chisel3.util._
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheArbiter}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._


class FrameContentInfo extends Bundle {
  val ip = UInt(64.W)
  val wp = UInt(64.W)
  val dst = new DecompressDstInfo
  val fcs = UInt(64.W) // frame content size
  val fcs_valid = Bool()
}

class FrameHeaderInfo extends Bundle {
  val checksum = Bool()
}

class FrameInfo extends Bundle {
  val header = new FrameHeaderInfo
// val content = new FrameContentInfo
}

class DecompressedFrameInfo extends Bundle {
  val src_consumed_bytes = UInt(64.W)
  val dst_written_bytes = UInt(64.W)
}

class ZstdDecompressorCommandExpanderIO()(implicit val p: Parameters) extends Bundle {
  val src_info = Flipped(Decoupled(new StreamInfo))
  val wksp_info = Flipped(Decoupled(new DecompressPtrInfo))
  val dst_info = Flipped(Decoupled(new DecompressDstInfo))

  val fhdr_info = Decoupled(new StreamInfo)
  val fhdr_stream = Flipped(new MemLoaderConsumerBundle)

  val frame_content = Decoupled(new FrameContentInfo)
  val decompressed_frame = Flipped(Decoupled(new DecompressedFrameInfo))
  val decompressed_cnt = Decoupled(UInt(64.W))

  val l2helperUser = new L2MemHelperBundle

}

/* 
* The command expander streams through the compressed file & searchs for the next 
* frame header positions. After it finds a vaild ZSTD frame, it offloads the 
* frame decoding to the FrameDecompressor.
*/
class ZstdDecompressorCommandExpander(val cmd_que_depth: Int)
  (implicit val p: Parameters) extends Module with MemoryOpConstants{
    val io = IO(new ZstdDecompressorCommandExpanderIO)
    dontTouch(io)

    val ZSTD_MAGIC_NUMBER = BigInt("FD2FB528", 16)
    val ZSTD_MAGIC_SKIPPABLE_MASK = BigInt("FFFFFFF0", 16)
    val ZSTD_MAGIC_SKIPPABLE_START = BigInt("184D2A50", 16)
    val ZSTD_MAX_FRAME_HEADER_BYTES = 18

    val STATE_IDLE = 0.U
    val STATE_LOAD_FHDR = 1.U
    val STATE_DECODE_FHDR = 2.U
    val STATE_DECOMPRESS_BLOCKS = 3.U
    val state = RegInit(0.U(4.W))

    val src_consumed_bytes = RegInit(0.U(64.W))
    val dst_written_bytes = RegInit(0.U(64.W))

    val src_info_q = Module(new Queue(new StreamInfo, cmd_que_depth)).io
    src_info_q.enq <> io.src_info

    val wksp_info_q = Module(new Queue(new DecompressPtrInfo, cmd_que_depth)).io
    wksp_info_q.enq <> io.wksp_info

    val dst_info_q = Module(new Queue(new DecompressDstInfo, cmd_que_depth)).io
    dst_info_q.enq <> io.dst_info

    val start_frame_decomp_fire = DecoupledHelper(
                                    src_info_q.deq.valid,
                                    wksp_info_q.deq.valid,
                                    dst_info_q.deq.valid)

    val fhdr_info_q = Module(new Queue(new StreamInfo, cmd_que_depth)).io
    io.fhdr_info <> fhdr_info_q.deq

    fhdr_info_q.enq.valid := (state === STATE_LOAD_FHDR)
    fhdr_info_q.enq.bits.ip := src_info_q.deq.bits.ip + src_consumed_bytes
    fhdr_info_q.enq.bits.isize := ZSTD_MAX_FRAME_HEADER_BYTES.U

    val header_data = Wire(UInt(64.W))
    val magic_number = Wire(UInt(32.W))
    val dic_id_flag = Wire(UInt(2.W))
    val content_checksum_flag = Wire(UInt(1.W))
    val single_segment_flag = Wire(UInt(1.W))
    val frame_content_size_flag = Wire(UInt(2.W))

    header_data := io.fhdr_stream.output_data
    magic_number := header_data(31, 0)
    dic_id_flag := header_data(33, 32)
    content_checksum_flag := header_data(34, 34)
    single_segment_flag := header_data(37, 37)
    frame_content_size_flag := header_data(39, 38)

    val magic_number_size = 4.U
    val header_desc_size = 1.U
    val did_field_size = Mux(dic_id_flag <= 2.U, dic_id_flag, dic_id_flag+1.U)
    val fcs_field_size = Mux(frame_content_size_flag === 3.U, 8.U,
                          Mux(frame_content_size_flag === 2.U, 4.U,
                            Mux(frame_content_size_flag === 1.U, 2.U,
                              Mux(single_segment_flag === 1.U, 1.U, 0.U))))

    val window_desc_size = Mux(single_segment_flag === 1.U, 0.U, 1.U)
    val field_size_sum = magic_number_size + header_desc_size + window_desc_size + did_field_size
    val frame_content_mask = (1.U << (fcs_field_size<<3.U)) - 1.U
    val frame_content_size = ((header_data >> (field_size_sum<<3.U)) & frame_content_mask) + Mux(fcs_field_size===2.U, 256.U, 0.U)

    val fhdr_size = field_size_sum + fcs_field_size

    val frame_content_q = Module(new Queue(new FrameContentInfo, cmd_que_depth)).io
    io.frame_content <> frame_content_q.deq

    val frame_info_q = Module(new Queue(new FrameInfo, cmd_que_depth)).io
    val decompressed_frame_q = Module(new Queue(new DecompressedFrameInfo, cmd_que_depth)).io
    decompressed_frame_q.enq <> io.decompressed_frame

    val avail_bytes = io.fhdr_stream.available_output_bytes

    val decode_frame_fire = DecoupledHelper(frame_content_q.enq.ready,
                                          frame_info_q.enq.ready,
                                          io.fhdr_stream.output_valid,
                                          avail_bytes >= ZSTD_MAX_FRAME_HEADER_BYTES.U,
                                          state === STATE_DECODE_FHDR)

    val frame_content_start_addr = src_info_q.deq.bits.ip + src_consumed_bytes + fhdr_size
    val wksp_start_addr = wksp_info_q.deq.bits.ip
    val dst_start_addr = dst_info_q.deq.bits.op + dst_written_bytes
    val cmpflag_addr = dst_info_q.deq.bits.cmpflag
    frame_content_q.enq.valid := decode_frame_fire.fire(frame_content_q.enq.ready)
    frame_content_q.enq.bits.ip := frame_content_start_addr
    frame_content_q.enq.bits.wp := wksp_start_addr
    frame_content_q.enq.bits.dst.op := dst_start_addr
    frame_content_q.enq.bits.dst.cmpflag := cmpflag_addr
    frame_content_q.enq.bits.fcs := frame_content_size
    frame_content_q.enq.bits.fcs_valid := Mux(fcs_field_size > 0.U, true.B, false.B)

    dontTouch(frame_content_start_addr)

    frame_info_q.enq.valid := decode_frame_fire.fire(frame_info_q.enq.ready)
    frame_info_q.enq.bits.header.checksum := content_checksum_flag.asBool

    io.fhdr_stream.output_ready := false.B
    io.fhdr_stream.user_consumed_bytes := 0.U
    when (decode_frame_fire.fire) {
      src_consumed_bytes := src_consumed_bytes + fhdr_size

      io.fhdr_stream.output_ready := true.B
      io.fhdr_stream.user_consumed_bytes := ZSTD_MAX_FRAME_HEADER_BYTES.U
    }


    val dispatched_files = RegInit(0.U(64.W))
    val nxt_dispatched_files = dispatched_files + 1.U

    val file_decompress_done_q = Module(new Queue(UInt(64.W), cmd_que_depth)).io
    io.decompressed_cnt <> file_decompress_done_q.deq

    val frame_done_fire = DecoupledHelper(
                            src_info_q.deq.valid,
                            wksp_info_q.deq.valid,
                            dst_info_q.deq.valid,
                            decompressed_frame_q.deq.valid,
                            frame_info_q.deq.valid,
                            file_decompress_done_q.enq.ready,
                            io.l2helperUser.req.ready,
                            state === STATE_DECOMPRESS_BLOCKS)

    decompressed_frame_q.deq.ready := frame_done_fire.fire(decompressed_frame_q.deq.valid)
    frame_info_q.deq.ready := frame_done_fire.fire(frame_info_q.deq.valid)

    val content_checksum_bytes = Mux(frame_info_q.deq.bits.header.checksum, 4.U, 0.U)
    val frame_src_consumed_bytes = decompressed_frame_q.deq.bits.src_consumed_bytes
    val nxt_src_consumed_bytes = src_consumed_bytes + frame_src_consumed_bytes + content_checksum_bytes

    val frame_dst_written_bytes = decompressed_frame_q.deq.bits.dst_written_bytes
    val nxt_dst_written_bytes = dst_written_bytes + frame_dst_written_bytes

    val remain_bytes_after_frame = src_info_q.deq.bits.isize - nxt_src_consumed_bytes
    val decompression_done = Mux(remain_bytes_after_frame < 5.U, true.B, false.B)

    src_info_q.deq.ready := frame_done_fire.fire(src_info_q.deq.valid,
                                                 decompression_done)
    wksp_info_q.deq.ready := frame_done_fire.fire(wksp_info_q.deq.valid,
                                                  decompression_done)
    dst_info_q.deq.ready := frame_done_fire.fire(dst_info_q.deq.valid,
                                                 decompression_done)

    file_decompress_done_q.enq.valid := frame_done_fire.fire(file_decompress_done_q.enq.ready,
                                                             decompression_done)
    file_decompress_done_q.enq.bits := nxt_dispatched_files

    when (file_decompress_done_q.enq.fire) {
      dispatched_files := nxt_dispatched_files
    }

    when (frame_done_fire.fire && !decompression_done) {
      src_consumed_bytes  := nxt_src_consumed_bytes
      dst_written_bytes := nxt_dst_written_bytes
    }

    when (frame_done_fire.fire && decompression_done) {
      src_consumed_bytes := 0.U
      dst_written_bytes := 0.U
    }

    io.l2helperUser.req.valid := frame_done_fire.fire(io.l2helperUser.req.ready, decompression_done)
    io.l2helperUser.req.bits.cmd := M_XWR
    io.l2helperUser.req.bits.size := 0.U
    io.l2helperUser.req.bits.data := 1.U
    io.l2helperUser.req.bits.addr := dst_info_q.deq.bits.cmpflag

    io.l2helperUser.resp.ready := true.B

    when (io.l2helperUser.req.fire) {
      CompressAccelLogger.logInfo("Zstd cmpflag write request fired!!\n")
      CompressAccelLogger.logInfo("Zstd cmpflag addr 0x%x\n", dst_info_q.deq.bits.cmpflag)
    }

    when (src_info_q.deq.fire) {
      CompressAccelLogger.logInfo("ZstdCommandExpander src_info_q.deq.fire!!\n")
      CompressAccelLogger.logInfo("Zstd Decompressed ALL FRAMES in the file\n")
      CompressAccelLogger.logInfo("consumed_bytes: %d\n", src_consumed_bytes)
      CompressAccelLogger.logInfo("nxt_frame_consumed_bytes: %d\n", nxt_src_consumed_bytes)
      CompressAccelLogger.logInfo("nxt_frame_written_bytes: %d\n", nxt_dst_written_bytes)
    }

    when (decode_frame_fire.fire) {
      CompressAccelLogger.logInfo("ZstdCommandExpander decode_frame_fire!!\n")
      CompressAccelLogger.logInfo("dic_id_flag: %d\n", dic_id_flag)
      CompressAccelLogger.logInfo("content_checksum_flag: %d\n", content_checksum_flag)
      CompressAccelLogger.logInfo("single_segment_flag: %d\n", single_segment_flag)
      CompressAccelLogger.logInfo("frame_content_size_flag: %d\n", frame_content_size_flag)
      CompressAccelLogger.logInfo("did_field_size: %d\n", did_field_size)
      CompressAccelLogger.logInfo("fcs_field_size: %d\n", fcs_field_size)
      CompressAccelLogger.logInfo("window_desc_size: %d\n", window_desc_size)
      CompressAccelLogger.logInfo("field_size_sum: %d\n", field_size_sum)
      CompressAccelLogger.logInfo("frame_content_size: %d\n", frame_content_size)
      CompressAccelLogger.logInfo("fhdr_size: %d\n", fhdr_size)
      CompressAccelLogger.logInfo("frame_content_start_addr: %d\n", frame_content_start_addr)
      CompressAccelLogger.logInfo("magic_number: 0x%x\n", magic_number)
    }

    when (fhdr_info_q.enq.fire) {
      CompressAccelLogger.logInfo("ZstdCommandExpander fdr_info.enq.fire!!\n")
      CompressAccelLogger.logInfo("Frame header start address: 0x%x\n", fhdr_info_q.enq.bits.ip)
    }

    when (frame_done_fire.fire) {
      CompressAccelLogger.logInfo("Zstd current frame decompress finished!\n")
      CompressAccelLogger.logInfo("Zstd frame src_consumed_bytes: %d\n", decompressed_frame_q.deq.bits.src_consumed_bytes)
      CompressAccelLogger.logInfo("Zstd frame dst_written_bytes: %d\n", decompressed_frame_q.deq.bits.dst_written_bytes)
      CompressAccelLogger.logInfo("Zstd frame checksum flag: %d\n", frame_info_q.deq.bits.header.checksum)
      CompressAccelLogger.logInfo("nxt_src_consumed_bytes: %d\n", nxt_src_consumed_bytes)
      CompressAccelLogger.logInfo("nxt_dst_written_bytes: %d\n", nxt_dst_written_bytes)
    }


    // NOTE : dic_id_flag, single_segment_flag, window desc is not needed
    // dic_id_flag : needed for loading user provided dictionaries
    // single_segment_flag & window_desc : if we are using a single cont memory to dump stuff
    switch (state) {
      is (STATE_IDLE) {
        when (start_frame_decomp_fire.fire) {
          state := STATE_LOAD_FHDR
        }
      }
      is (STATE_LOAD_FHDR) {
        when (fhdr_info_q.enq.fire) {
          state := STATE_DECODE_FHDR
        }
      }
      is (STATE_DECODE_FHDR) {
        when (decode_frame_fire.fire) {
          state := STATE_DECOMPRESS_BLOCKS
        }
      }
      is (STATE_DECOMPRESS_BLOCKS) {
        when (frame_done_fire.fire) {
          state := STATE_IDLE
        }
      }
    }
}
