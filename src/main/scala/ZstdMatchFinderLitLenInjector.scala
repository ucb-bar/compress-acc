package compressacc

import Chisel._
import chisel3.{Printable, VecInit}
import freechips.rocketchip.tile._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._




class ZstdMatchFinderLitLenInjector()(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val memwrites_in = Decoupled(new CompressWriterBundle).flip

    val seq_memwrites_out = Decoupled(new CompressWriterBundle)
    val lit_memwrites_out = Decoupled(new CompressWriterBundle)
  })

  val track_blocks = RegInit(0.U(64.W))

  val incoming_writes_Q = Module(new Queue(new CompressWriterBundle, 4))
  incoming_writes_Q.io.enq <> io.memwrites_in

  when (io.memwrites_in.fire) {
    CompressAccelLogger.logInfo("LLInjector-IO_MEMWRITE_FIRE\n")
    CompressAccelLogger.logInfo("data: 0x%x, validbytes: %d, is_copy: %d, EOM: %d\n",
      io.memwrites_in.bits.data,
      io.memwrites_in.bits.validbytes,
      io.memwrites_in.bits.is_copy,
      io.memwrites_in.bits.end_of_message)
  }


  val lit_len_so_far = RegInit(0.U(64.W))


  val copy_length = incoming_writes_Q.io.deq.bits.data(127, 64) - 3.U
  val copy_offset = incoming_writes_Q.io.deq.bits.data(63, 0) + 3.U
// val copy_offset = incoming_writes_Q.io.deq.bits.data(63, 0) // TODO : Check this
  val sequence = Cat(copy_offset(31, 0), copy_length(31, 0), lit_len_so_far(31, 0))

  val sDefault = 0.U(2.W)
  val sWriteDummyLiteral = 1.U(2.W)
  val sWriteDummyCopy = 2.U(2.W)
  val llInjectorState = RegInit(sDefault)

  val write_lit = DecoupledHelper(
    io.lit_memwrites_out.ready,
    incoming_writes_Q.io.deq.valid,
    !incoming_writes_Q.io.deq.bits.is_copy,
    llInjectorState === sDefault)

  val write_dummy_lit = DecoupledHelper(
    io.lit_memwrites_out.ready,
    llInjectorState === sWriteDummyLiteral)


  val is_dummy_lit = llInjectorState === sWriteDummyLiteral
  val is_length_header = incoming_writes_Q.io.deq.bits.length_header

  io.lit_memwrites_out.valid := write_lit.fire(io.lit_memwrites_out.ready, !is_length_header) ||
                                write_dummy_lit.fire(io.lit_memwrites_out.ready)
  io.lit_memwrites_out.bits := incoming_writes_Q.io.deq.bits
  io.lit_memwrites_out.bits.data := Mux(is_dummy_lit, 0.U, incoming_writes_Q.io.deq.bits.data)
  io.lit_memwrites_out.bits.validbytes := Mux(is_dummy_lit, 1.U, incoming_writes_Q.io.deq.bits.validbytes)
  io.lit_memwrites_out.bits.end_of_message := incoming_writes_Q.io.deq.bits.end_of_message || is_dummy_lit
  io.lit_memwrites_out.bits.is_dummy := is_dummy_lit

  when (write_lit.fire) {
    lit_len_so_far := lit_len_so_far + Mux(!is_length_header, incoming_writes_Q.io.deq.bits.validbytes, 0.U)

    when (incoming_writes_Q.io.deq.bits.end_of_message) {
      llInjectorState := sWriteDummyCopy
    }
    CompressAccelLogger.logInfo("LLInjector-LIT_FIRE\n")
    CompressAccelLogger.logInfo("data: 0x%x\n validbytes: %d, EOM: %d\n",
      io.lit_memwrites_out.bits.data,
      io.lit_memwrites_out.bits.validbytes,
      io.lit_memwrites_out.bits.end_of_message)
  }

  when (write_dummy_lit.fire) {
    CompressAccelLogger.logInfo("LLInjector-DUMMY_LIT_FIRE\n")
  }

  val write_copy = DecoupledHelper(
    io.seq_memwrites_out.ready,
    incoming_writes_Q.io.deq.valid,
    incoming_writes_Q.io.deq.bits.is_copy,
    llInjectorState === sDefault)

  val write_dummy_copy = DecoupledHelper(
    io.seq_memwrites_out.ready,
    llInjectorState === sWriteDummyCopy)

  val is_dummy_copy = llInjectorState === sWriteDummyCopy

  io.seq_memwrites_out.valid := write_copy.fire(io.seq_memwrites_out.ready) || write_dummy_copy.fire(io.seq_memwrites_out.ready)
  io.seq_memwrites_out.bits := io.lit_memwrites_out.bits
  io.seq_memwrites_out.bits.data := Mux(is_dummy_copy, 0.U, sequence)
  io.seq_memwrites_out.bits.validbytes := Mux(is_dummy_copy, 1.U, 12.U)
  io.seq_memwrites_out.bits.end_of_message := incoming_writes_Q.io.deq.bits.end_of_message || is_dummy_copy
  io.seq_memwrites_out.bits.is_dummy := is_dummy_copy

  when (write_copy.fire) {
    lit_len_so_far := 0.U

    when (incoming_writes_Q.io.deq.bits.end_of_message) {
      llInjectorState := sWriteDummyLiteral
    }
    CompressAccelLogger.logInfo("LLInjector-COPY_FIRE\n")
    CompressAccelLogger.logInfo("ll: %d, ml: %d, of: %d EOM: %d bid: %d\n",
      sequence(31, 0), sequence(63, 32), sequence(95, 64), io.seq_memwrites_out.bits.end_of_message, track_blocks)
  }

  when (write_dummy_copy.fire) {
    CompressAccelLogger.logInfo("LLInjector-DUMMY_COPY_FIRE\n")
  }

  when (write_dummy_copy.fire || write_dummy_lit.fire) {
    llInjectorState := sDefault
    lit_len_so_far := 0.U
    track_blocks := track_blocks + 1.U
  }

  incoming_writes_Q.io.deq.ready := write_lit.fire(incoming_writes_Q.io.deq.valid) ||
                                    write_copy.fire(incoming_writes_Q.io.deq.valid)
}
