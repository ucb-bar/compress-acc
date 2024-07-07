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

class HufCompressorControllerIO(implicit p: Parameters) extends Bundle {
  val src_info_in = Flipped(Decoupled(new StreamInfo))
  val dst_info_in = Flipped(Decoupled(new DstWithValInfo))

  val lit_src_info = Decoupled(new StreamInfo)
  val lit_dst_info = Decoupled(new DstInfo)
  val compressed_bytes = Flipped(Decoupled(UInt(64.W)))

  val hdr_dst_info = Decoupled(new DstInfo)
  val hdr_writes = Decoupled(new WriterBundle)

  val jt_dst_info = Decoupled(new DstInfo)
  val jt_writes = Decoupled(new WriterBundle)

  val weight_dst_info = Decoupled(new DstInfo)
  val weight_bytes = Flipped(Decoupled(UInt(64.W)))
  val header_size_info = Flipped(Decoupled(UInt(8.W)))

  val total_write_bytes = Decoupled(UInt(64.W))
  val total_write_bytes2 = Decoupled(UInt(64.W))
  val init_dictionary = Decoupled(Bool())
  val bufs_completed = Output(UInt(64.W))
}

// write header when # of io.compressed_bytes.fire === stream_cnt - 1.U & set end_of_message
class HufCompressorController(val cmd_que_depth: Int)(implicit p: Parameters) extends Module {
  val io = IO(new HufCompressorControllerIO)

// size_t const lhSize = 3 + (srcSize >= 1 KB) + (srcSize >= 16 KB);
// U32 singleStream = srcSize < 256;

// case 3: /* 2 - 2 - 10 - 10 */
// {   U32 const lhc = hType + ((!singleStream) << 2) + ((U32)srcSize<<4) + ((U32)cLitSize<<14);
// MEM_writeLE24(ostart, lhc);
// break;
// }
// case 4: /* 2 - 2 - 14 - 14 */
// {   U32 const lhc = hType + (2 << 2) + ((U32)srcSize<<4) + ((U32)cLitSize<<18);
// MEM_writeLE32(ostart, lhc);
// break;
// }
// case 5: /* 2 - 2 - 18 - 18 */
// {   U32 const lhc = hType + (3 << 2) + ((U32)srcSize<<4) + ((U32)cLitSize<<22);
// MEM_writeLE32(ostart, lhc);
// ostart[4] = (BYTE)(cLitSize >> 10);
// break;
// }

  val sSendWeightDst = 0.U
  val sReceiveWrittenWeightBytes = 1.U
  val sKickoffEncoder = 2.U
  val sWriteJumpTable = 3.U
  val sWriteHeader = 4.U
  val hufCompressState = RegInit(0.U(3.W))

  val KB = 1024
  val srcSize = io.src_info_in.bits.isize
  val singleStream = srcSize < 256.U
  val written_weight_bytes = RegInit(0.U(64.W))
  val clitSize = RegInit(0.U(64.W))
  val hType = 2.U(2.W) // FIXME : Currently ignore rle & raw for now

  val totCompBytes = written_weight_bytes + Mux(singleStream, 0.U, 6.U) + clitSize

  // There are cases when (weights + jump table + compressed literals) > (src file size) happens.
  // In software, this is considered as a raw block by some heuristics, but it would be inefficient
  // to do something like compress -> check -> rollback. Hence, we just use the compressed results
  // with some modifications from the software.
  val lhSizeSrc = 3.U +& Mux(srcSize >= (1*KB).U, 1.U, 0.U) +& Mux(srcSize >= (16*KB).U, 1.U, 0.U)
  val lhSizeComp = 3.U +& Mux(totCompBytes >= (1*KB).U, 1.U, 0.U) +& Mux(totCompBytes >= (16*KB).U, 1.U, 0.U)
  val lhSize = Mux(totCompBytes < srcSize, lhSizeSrc, lhSizeComp)

  val lhc = WireInit(0.U(42.W))
  when (lhSize === 3.U) {
    lhc := hType + Mux(singleStream, 0.U, (1 << 2).U) + (srcSize << 4.U) + (totCompBytes << 14.U)
  } .elsewhen (lhSize === 4.U) {
    lhc := hType + (2 << 2).U + (srcSize << 4.U) + (totCompBytes << 18.U)
  } .elsewhen (lhSize === 5.U) {
    lhc := hType + (3 << 2).U + (srcSize << 4.U) + (totCompBytes << 22.U)
  }

  val completed_streams = RegInit(0.U(3.W))
  val num_streams = Mux(singleStream, 1.U, 4.U)

  val jump_table = RegInit(VecInit(Seq.fill(3)(0.U(16.W))))
  val jump_table_concat = Cat(jump_table(2), jump_table(1), jump_table(0))

  val segment_size = (srcSize + 3.U) >> 2.U
  val last_segment_size = srcSize - (segment_size * 3.U)

  val compressed_bytes_q = Module(new Queue(UInt(64.W), 4))
  compressed_bytes_q.io.enq <> io.compressed_bytes
  when (io.compressed_bytes.fire) {
    completed_streams := completed_streams + 1.U
    clitSize := clitSize + io.compressed_bytes.bits
    jump_table(completed_streams) := io.compressed_bytes.bits

    CompressAccelLogger.logInfo("HUF_CONTROL_COMPRESSEDBYTES_FIRE\n")
    CompressAccelLogger.logInfo("completed_streams: %d, cBytes: %d\n", completed_streams, io.compressed_bytes.bits)
  }


  val weight_dst_fire = DecoupledHelper(
    io.src_info_in.valid,
    io.dst_info_in.valid,
    hufCompressState === sSendWeightDst)

  io.weight_dst_info.valid := weight_dst_fire.fire
  io.weight_dst_info.bits.op := io.dst_info_in.bits.op + lhSize + 1.U // weight compressor does not write the first byte(size of huf tree info)
  io.weight_dst_info.bits.cmpflag := 0.U

  when (weight_dst_fire.fire) {
    hufCompressState := sReceiveWrittenWeightBytes
  }


  io.weight_bytes.ready := (hufCompressState === sReceiveWrittenWeightBytes)

  when (io.weight_bytes.fire) {
    written_weight_bytes := io.weight_bytes.bits
    hufCompressState := sKickoffEncoder
    CompressAccelLogger.logInfo("huf_written_weight_bytes: %d\n", io.weight_bytes.bits)
  }

  val lit_src_info_q = Module(new Queue(new StreamInfo, 4))
  val lit_dst_info_q = Module(new Queue(new DstInfo, 4))
  io.lit_src_info <> lit_src_info_q.io.deq
  io.lit_dst_info <> lit_dst_info_q.io.deq

  val first_stream = RegInit(true.B)
  val kickoff_encoder = first_stream || compressed_bytes_q.io.deq.valid
  val kickoff_encoder_fire = DecoupledHelper(
    io.src_info_in.valid,
    io.dst_info_in.valid,
    lit_src_info_q.io.enq.ready,
    lit_dst_info_q.io.enq.ready,
    kickoff_encoder,
    hufCompressState === sKickoffEncoder)

  compressed_bytes_q.io.deq.ready := kickoff_encoder_fire.fire(kickoff_encoder)

  val h_w_j_bytes = lhSize + written_weight_bytes + Mux(singleStream, 0.U, 6.U)

  lit_dst_info_q.io.enq.bits.op := io.dst_info_in.bits.op + h_w_j_bytes + clitSize
  lit_dst_info_q.io.enq.bits.cmpflag := 0.U
  lit_dst_info_q.io.enq.valid := kickoff_encoder_fire.fire(lit_dst_info_q.io.enq.ready, completed_streams < num_streams)

  lit_src_info_q.io.enq.bits.ip := io.src_info_in.bits.ip + completed_streams * segment_size
  lit_src_info_q.io.enq.bits.isize := Mux(!singleStream, Mux(completed_streams === 3.U, last_segment_size, segment_size),
                                    srcSize)
  lit_src_info_q.io.enq.valid := kickoff_encoder_fire.fire(lit_src_info_q.io.enq.ready, completed_streams < num_streams)

  when (kickoff_encoder_fire.fire && first_stream) {
    first_stream := false.B
  }

  val last_kickoff_done = (completed_streams === num_streams)
  when (kickoff_encoder_fire.fire(lit_src_info_q.io.enq.ready) && last_kickoff_done) {
    hufCompressState := sWriteJumpTable
    CompressAccelLogger.logInfo("HUF_CONTROL_LAST_COMPRESS_BYTES_RECEIVED\n")
  }

  when (kickoff_encoder_fire.fire && !last_kickoff_done) {
    CompressAccelLogger.logInfo("HUF_CONTROL_KICKOFF_ENCODER\n")
    CompressAccelLogger.logInfo("h_w_j_bytes: %d\n", h_w_j_bytes)
    CompressAccelLogger.logInfo("lit_dst_info.op: 0x%x\n", io.lit_dst_info.bits.op)
    CompressAccelLogger.logInfo("lit_src_info.ip: 0x%x\n", io.lit_src_info.bits.ip)
    CompressAccelLogger.logInfo("lit_src_info.isize: %d\n", io.lit_src_info.bits.isize)
    CompressAccelLogger.logInfo("completed_streams: %d\n", completed_streams)
    CompressAccelLogger.logInfo("first_stream: %d\n", first_stream)
  }

  val write_jump_table_fire = DecoupledHelper(
    io.src_info_in.valid,
    io.dst_info_in.valid,
    io.jt_dst_info.ready,
    io.jt_writes.ready,
    hufCompressState === sWriteJumpTable)

  io.jt_dst_info.valid := write_jump_table_fire.fire(io.jt_dst_info.ready, !singleStream)
  io.jt_dst_info.bits.op := io.dst_info_in.bits.op + lhSize + written_weight_bytes
  io.jt_dst_info.bits.cmpflag := 0.U

  io.jt_writes.valid := write_jump_table_fire.fire(io.jt_writes.ready, !singleStream)
  io.jt_writes.bits.data := jump_table_concat
  io.jt_writes.bits.validbytes := 6.U
  io.jt_writes.bits.end_of_message := true.B

  when (write_jump_table_fire.fire) {
    hufCompressState := sWriteHeader

    when (!singleStream) {
      CompressAccelLogger.logInfo("jump_table: 0x%x\n", jump_table_concat)
      CompressAccelLogger.logInfo("jump_table0: %d\n", jump_table(0))
      CompressAccelLogger.logInfo("jump_table1: %d\n", jump_table(1))
      CompressAccelLogger.logInfo("jump_table2: %d\n", jump_table(2))
    }
  }

  val header_size_info_q = Module(new Queue(UInt(8.W), 4))
  header_size_info_q.io.enq <> io.header_size_info

  val write_header_fire = DecoupledHelper(
    io.src_info_in.valid,
    io.dst_info_in.valid,
    io.hdr_dst_info.ready,
    io.hdr_writes.ready,
    header_size_info_q.io.deq.valid,
    hufCompressState === sWriteHeader)

  when (hufCompressState === sWriteHeader) {
    CompressAccelLogger.logInfo("HUF_CONTROL_sWriteHeader\n")
    CompressAccelLogger.logInfo("io.src_info_in.valid: %d\n", io.src_info_in.valid)
    CompressAccelLogger.logInfo("io.dst_info_in.valid: %d\n", io.dst_info_in.valid)
    CompressAccelLogger.logInfo("io.hdr_dst_info.ready: %d\n", io.hdr_dst_info.ready)
    CompressAccelLogger.logInfo("io.hdr_writes.ready: %d\n", io.hdr_writes.ready)
    CompressAccelLogger.logInfo("header_size_info_q.io.deq.valid: %d\n", header_size_info_q.io.deq.valid)
    CompressAccelLogger.logInfo("io.total_write_bytes.ready: %d\n", io.total_write_bytes.ready)
  }

  io.hdr_dst_info.valid := write_header_fire.fire(io.hdr_dst_info.ready)
  io.hdr_dst_info.bits.cmpflag := io.dst_info_in.bits.cmpflag
  io.hdr_dst_info.bits.op := io.dst_info_in.bits.op

  header_size_info_q.io.deq.ready := write_header_fire.fire(header_size_info_q.io.deq.valid)

  val lhSize_4 = WireInit(0.U(4.W))
  lhSize_4 := lhSize

  val header_with_size = lhc | (header_size_info_q.io.deq.bits << (lhSize_4 << 3.U))
  io.hdr_writes.valid := write_header_fire.fire(io.hdr_writes.ready)
  io.hdr_writes.bits.data := header_with_size
  io.hdr_writes.bits.validbytes := lhSize +& 1.U
  io.hdr_writes.bits.end_of_message := true.B

  val bufs_completed = RegInit(0.U(64.W))
  io.bufs_completed := bufs_completed

  val write_header_fired = RegInit(false.B)
  when (write_header_fire.fire) {
    write_header_fired := true.B

    CompressAccelLogger.logInfo("HUF_CONTROL_WRITE_HEADER\n")
    CompressAccelLogger.logInfo("header: 0x%x\n", lhc)
    CompressAccelLogger.logInfo("singleStream: %d\n", singleStream)
    CompressAccelLogger.logInfo("headerBytes: %d\n", lhSize)
    CompressAccelLogger.logInfo("header_size_info_q.io.deq.bits: %d\n", header_size_info_q.io.deq.bits)
    CompressAccelLogger.logInfo("io.hdr_writes.bits.data: 0x%x\n", io.hdr_writes.bits.data)
    CompressAccelLogger.logInfo("io.hdr_writes.bits.validbytes: 0x%x\n", io.hdr_writes.bits.validbytes)
  }

  val total_write_bytes_fire = DecoupledHelper(
    io.total_write_bytes.ready,
    io.total_write_bytes2.ready,
    io.init_dictionary.ready,
    io.src_info_in.valid,
    io.dst_info_in.valid,
    write_header_fired)

  io.total_write_bytes.valid := total_write_bytes_fire.fire(io.total_write_bytes.ready)
  io.total_write_bytes.bits := h_w_j_bytes + clitSize

  io.total_write_bytes2.valid := total_write_bytes_fire.fire(io.total_write_bytes2.ready)
  io.total_write_bytes2.bits := h_w_j_bytes + clitSize

  io.src_info_in.ready := total_write_bytes_fire.fire(io.src_info_in.valid)
  io.dst_info_in.ready := total_write_bytes_fire.fire(io.dst_info_in.valid)

  io.init_dictionary.valid := total_write_bytes_fire.fire(io.init_dictionary.ready)
  io.init_dictionary.bits := true.B

  when (total_write_bytes_fire.fire) {
    bufs_completed := bufs_completed + 1.U

    hufCompressState := sSendWeightDst
    written_weight_bytes := 0.U
    completed_streams := 0.U
    clitSize := 0.U
    jump_table(0) := 0.U
    jump_table(1) := 0.U
    jump_table(2) := 0.U
    first_stream := true.B
    write_header_fired := false.B

    CompressAccelLogger.logInfo("HUF_CONTROL_TOTAL_WRITE_BYTES\n")
    CompressAccelLogger.logInfo("total_write_bytes: %d\n", io.total_write_bytes.bits)
    CompressAccelLogger.logInfo("HEADER_SECTION: %d\n", lhSize)
    CompressAccelLogger.logInfo("WEIGHT_SECTION: %d\n", written_weight_bytes)
    CompressAccelLogger.logInfo("JUMP_TABLE: %d\n", Mux(singleStream, 0.U, 6.U))
    CompressAccelLogger.logInfo("COMPRESSED_LITERALS: %d\n", clitSize)
  }
}
