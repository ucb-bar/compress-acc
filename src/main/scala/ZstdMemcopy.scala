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


class ZstdMemcopyIO extends Bundle {
  val l2io_read = new L2MemHelperBundle
  val src_info = Flipped(Decoupled(new StreamInfo))
  val dst_info = Flipped(Decoupled(new DstWithValInfo))
  val l2io_write = new L2MemHelperBundle
  val bytes_written = Decoupled(UInt(64.W))
}

class ZstdMemcopy()(implicit p: Parameters) extends Module {
  val io = IO(new ZstdMemcopyIO)

  val memloader = Module(new MemLoader)
  io.l2io_read <> memloader.io.l2helperUser

  val send_done_q = Module(new Queue(Bool(), 10))

  val src_fire = DecoupledHelper(
    send_done_q.io.enq.ready,
    memloader.io.src_info.ready,
    io.src_info.valid)

  memloader.io.src_info.bits := io.src_info.bits
  memloader.io.src_info.valid := src_fire.fire(memloader.io.src_info.ready)

  send_done_q.io.enq.valid := src_fire.fire(send_done_q.io.enq.ready)
  send_done_q.io.enq.bits := true.B

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

  val memwriter = Module(new ZstdCompressorMemWriter(writeCmpFlag=false, printinfo="zstdmemcpy-memwriter"))
  io.l2io_write <> memwriter.io.l2io
  memwriter.io.dest_info <> io.dst_info


  memwriter.io.memwrites_in.valid := memloader.io.consumer.output_valid
  memwriter.io.memwrites_in.bits.data := memloader.io.consumer.output_data
  memwriter.io.memwrites_in.bits.validbytes := memloader.io.consumer.available_output_bytes
  memwriter.io.memwrites_in.bits.end_of_message := memloader.io.consumer.output_last_chunk

  memloader.io.consumer.output_ready := memwriter.io.memwrites_in.ready
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
    bytes_written := 0.U
    CompressAccelLogger.logInfo("MEMCPY_DONE_FIRE, bytes_written: %d\n", bytes_written)
  }
}
