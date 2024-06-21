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


class ZstdRawLiteralEncoderIO extends Bundle {
  val l2io_read = new L2MemHelperBundle
  val src_info = Flipped(Decoupled(new StreamInfo))
  val dst_info = Flipped(Decoupled(new DstWithValInfo))
  val l2io_write = new L2MemHelperBundle
  val bytes_written = Decoupled(UInt(64.W))
}

class ZstdRawLiteralEncoder()(implicit p: Parameters) extends Module {
  val io = IO(new ZstdRawLiteralEncoderIO)

  val memloader = Module(new MemLoader)
  io.l2io_read <> memloader.io.l2helperUser

  val src_size_q = Module(new Queue(UInt(64.W), 4))
  val send_done_q = Module(new Queue(Bool(), 4))

  val src_fire = DecoupledHelper(
    send_done_q.io.enq.ready,
    src_size_q.io.enq.ready,
    memloader.io.src_info.ready,
    io.src_info.valid)

  memloader.io.src_info.bits := io.src_info.bits
  memloader.io.src_info.valid := src_fire.fire(memloader.io.src_info.ready)

  src_size_q.io.enq.bits := io.src_info.bits.isize
  src_size_q.io.enq.valid := src_fire.fire(src_size_q.io.enq.ready)

  send_done_q.io.enq.bits := true.B
  send_done_q.io.enq.valid := src_fire.fire(send_done_q.io.enq.ready)

  io.src_info.ready := src_fire.fire(io.src_info.valid)

  val input_copy_req_count = RegInit(0.U(64.W))
  when (src_fire.fire) {
    val nxt_input_copy_req_cnt = input_copy_req_count + 1.U
    input_copy_req_count := nxt_input_copy_req_cnt
    CompressAccelLogger.logInfo("MEMCPY_SRC_FIRE, input_copy_req_count: %d, src_addr: 0x%x, src_size: %d\n", 
      nxt_input_copy_req_cnt,
      io.src_info.bits.ip,
      io.src_info.bits.isize)
  }

  // zstd_compress_literals.c:16 ZSTD_noCompressLiterals
  val src_size_bytes = src_size_q.io.deq.bits
  val set_basic = 0.U
  val flSize = 1.U + Mux(src_size_bytes > 31.U, 1.U, 0.U) + Mux(src_size_bytes > 4095.U, 1.U, 0.U)
  val header = Wire(UInt(24.W))
  header := Mux(flSize === 1.U, set_basic + (src_size_bytes << 3.U),
              Mux(flSize === 2.U, set_basic + (1 << 2).U + (src_size_bytes << 4.U),
                set_basic + (3 << 2).U + (src_size_bytes << 4.U)))

  val memwriter = Module(new ZstdCompressorMemWriter(writeCmpFlag=false, printinfo="zstd-rawlit-memwriter"))
  io.l2io_write <> memwriter.io.l2io
  memwriter.io.dest_info <> io.dst_info

  val header_written = RegInit(false.B)
  val header_write_fire = DecoupledHelper(
    src_size_q.io.deq.valid,
    !header_written,
    memwriter.io.memwrites_in.ready)

  when (header_write_fire.fire) {
    header_written := true.B
  }

  src_size_q.io.deq.ready := header_write_fire.fire(src_size_q.io.deq.valid)

  val copy_src_fire = DecoupledHelper(
    memloader.io.consumer.output_valid,
    memwriter.io.memwrites_in.ready,
    header_written)

  val header_fire = header_write_fire.fire

  memwriter.io.memwrites_in.valid := header_write_fire.fire(memwriter.io.memwrites_in.ready) ||
                                     copy_src_fire.fire(memwriter.io.memwrites_in.ready)
  memwriter.io.memwrites_in.bits.data := Mux(header_fire,
                                             header,
                                             memloader.io.consumer.output_data)
  memwriter.io.memwrites_in.bits.validbytes := Mux(header_fire,
                                                   flSize,
                                                   memloader.io.consumer.available_output_bytes)
  memwriter.io.memwrites_in.bits.end_of_message := Mux(header_fire, false.B, memloader.io.consumer.output_last_chunk)

  memloader.io.consumer.output_ready := copy_src_fire.fire(memloader.io.consumer.output_valid)
  memloader.io.consumer.user_consumed_bytes := memloader.io.consumer.available_output_bytes

  val bytes_written = RegInit(0.U(64.W))
  when (memwriter.io.memwrites_in.fire) {
    bytes_written := bytes_written + memwriter.io.memwrites_in.bits.validbytes
  }

  val done_fire = DecoupledHelper(
    memwriter.io.bufs_completed === input_copy_req_count,
    memwriter.io.no_writes_inflight,
    io.bytes_written.ready,
    send_done_q.io.deq.valid)

  io.bytes_written.valid := done_fire.fire(io.bytes_written.ready)
  io.bytes_written.bits := bytes_written
  send_done_q.io.deq.ready := done_fire.fire(send_done_q.io.deq.valid)

  when (done_fire.fire) {
    header_written := false.B
    bytes_written := 0.U
    CompressAccelLogger.logInfo("MEMCPY_DONE_FIRE, bytes_written: %d\n", bytes_written)
  }
}
