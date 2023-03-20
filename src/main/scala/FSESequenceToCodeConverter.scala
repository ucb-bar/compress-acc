package compressacc

import chisel3._
import chisel3.util._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheArbiter}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._


class FSESequenceToCodeConverterIO()(implicit p: Parameters) extends Bundle {
  val src_stream = Flipped(new MemLoaderConsumerBundle)

  val ll_consumer = new MemLoaderConsumerBundle
  val ml_consumer = new MemLoaderConsumerBundle
  val of_consumer = new MemLoaderConsumerBundle
}

class FSESequenceToCodeConverter()(implicit p: Parameters) extends Module {
  val io = IO(new FSESequenceToCodeConverterIO())

  val ll32 = io.src_stream.output_data(31, 0)
  val ml32 = io.src_stream.output_data(63, 32)
  val of32 = io.src_stream.output_data(95, 64)

  val ll_seq_to_code = Module(new LLSeqToCode())
  ll_seq_to_code.io.litlen := ll32

  val ml_seq_to_code = Module(new MLSeqToCode())
  ml_seq_to_code.io.mlbase := ml32

  val of_seq_to_code = Module(new OFSeqToCode())
  of_seq_to_code.io.ofbase := of32
 
  val ll_buff = Module(new ZstdCompressorLitRotBuf)
  val ml_buff = Module(new ZstdCompressorLitRotBuf)
  val of_buff = Module(new ZstdCompressorLitRotBuf)

  val split_input_fire = DecoupledHelper(
    io.src_stream.output_valid,
    io.src_stream.available_output_bytes >= 12.U,
    ll_buff.io.memwrites_in.ready,
    ml_buff.io.memwrites_in.ready,
    of_buff.io.memwrites_in.ready)

  io.src_stream.output_ready := split_input_fire.fire(io.src_stream.output_valid)
  io.src_stream.user_consumed_bytes := 12.U

  val end_of_message = io.src_stream.output_last_chunk && (io.src_stream.user_consumed_bytes === io.src_stream.available_output_bytes)

  when (split_input_fire.fire && io.src_stream.output_last_chunk && io.src_stream.available_output_bytes <= 12.U) {
    assert(io.src_stream.available_output_bytes === 12.U, "Available sequence bytes is not a multiple of 12")
  }

  ll_buff.io.memwrites_in.valid := split_input_fire.fire(ll_buff.io.memwrites_in.ready)
  ll_buff.io.memwrites_in.bits.data := ll_seq_to_code.io.llcode
  ll_buff.io.memwrites_in.bits.validbytes := 1.U
  ll_buff.io.memwrites_in.bits.end_of_message := end_of_message

  ml_buff.io.memwrites_in.valid := split_input_fire.fire(ml_buff.io.memwrites_in.ready)
  ml_buff.io.memwrites_in.bits.data := ml_seq_to_code.io.mlcode
  ml_buff.io.memwrites_in.bits.validbytes := 1.U
  ml_buff.io.memwrites_in.bits.end_of_message := end_of_message

  of_buff.io.memwrites_in.valid := split_input_fire.fire(of_buff.io.memwrites_in.ready)
  of_buff.io.memwrites_in.bits.data := of_seq_to_code.io.ofcode
  of_buff.io.memwrites_in.bits.validbytes := 1.U
  of_buff.io.memwrites_in.bits.end_of_message := end_of_message

  when (ll_buff.io.memwrites_in.fire) {
    CompressAccelLogger.logInfo("ll32: %d ll8: %d\n", ll32, ll_seq_to_code.io.llcode)
  }

  when (ml_buff.io.memwrites_in.fire) {
    CompressAccelLogger.logInfo("ml32: %d ml8: %d\n", ml32, ml_seq_to_code.io.mlcode)
  }

  when (of_buff.io.memwrites_in.fire) {
    CompressAccelLogger.logInfo("of32: %d of8: %d\n", of32, of_seq_to_code.io.ofcode)
  }

  val nbseq = RegInit(0.U(64.W))
  when (split_input_fire.fire) {
    nbseq := nbseq + 1.U
  }

  val end_of_message_fired = RegInit(false.B)
  when (split_input_fire.fire && end_of_message) {
    end_of_message_fired := true.B
  }

  val SBUS_WIDTH = 32

  val ll_done = RegInit(false.B)
  val ml_done = RegInit(false.B)
  val of_done = RegInit(false.B)

  val ll_consumed_bytes = RegInit(0.U(64.W))
  val ll_remaining_bytes = nbseq - ll_consumed_bytes
  when (io.ll_consumer.output_valid && io.ll_consumer.output_ready) {
    ll_consumed_bytes := ll_consumed_bytes + io.ll_consumer.user_consumed_bytes
    when (io.ll_consumer.output_last_chunk && (io.ll_consumer.user_consumed_bytes === io.ll_consumer.available_output_bytes)) {
      ll_done := true.B
    }
  }
  io.ll_consumer <> ll_buff.io.consumer
  io.ll_consumer.output_last_chunk := end_of_message_fired && (ll_remaining_bytes <= io.ll_consumer.user_consumed_bytes)


  val ml_consumed_bytes = RegInit(0.U(64.W))
  val ml_remaining_bytes = nbseq - ml_consumed_bytes
  when (io.ml_consumer.output_valid && io.ml_consumer.output_ready) {
    ml_consumed_bytes := ml_consumed_bytes + io.ml_consumer.user_consumed_bytes
    when (io.ml_consumer.output_last_chunk && (io.ml_consumer.user_consumed_bytes === io.ml_consumer.available_output_bytes)) {
      ml_done := true.B
    }
  }
  io.ml_consumer <> ml_buff.io.consumer
  io.ml_consumer.output_last_chunk := end_of_message_fired && (ml_remaining_bytes <= io.ml_consumer.user_consumed_bytes)

  val of_consumed_bytes = RegInit(0.U(64.W))
  val of_remaining_bytes = nbseq - of_consumed_bytes
  when (io.of_consumer.output_valid && io.of_consumer.output_ready) {
    of_consumed_bytes := of_consumed_bytes + io.of_consumer.user_consumed_bytes
    when (io.of_consumer.output_last_chunk && (io.of_consumer.user_consumed_bytes === io.of_consumer.available_output_bytes)) {
      of_done := true.B
    }
  }
  io.of_consumer <> of_buff.io.consumer
  io.of_consumer.output_last_chunk := end_of_message_fired && (of_remaining_bytes <= io.of_consumer.user_consumed_bytes)

  when (ll_done && ml_done && of_done) {
    nbseq := 0.U
    end_of_message_fired := false.B
    ll_done := false.B
    ml_done := false.B
    of_done := false.B
    ll_consumed_bytes := 0.U
    ml_consumed_bytes := 0.U
    of_consumed_bytes := 0.U
  }
}


class LLSeqToCode extends Module {
  val io = IO(new Bundle {
    val litlen = Input(UInt(32.W))
    val llcode = Output(UInt(8.W))
  })

  val ll_deltaCode = 19.U

  val ll_code = Wire(Vec(64, UInt(8.W)))

  for (i <- 0 until 16) {
    ll_code(i) := i.U
  }
  ll_code(16) := 16.U
  ll_code(17) := 16.U
  ll_code(18) := 17.U
  ll_code(19) := 17.U
  ll_code(20) := 18.U
  ll_code(21) := 18.U
  ll_code(22) := 19.U
  ll_code(23) := 19.U
  for (i <- 24 until 28) {
    ll_code(i) := 20.U
  }
  for (i <- 28 until 32) {
    ll_code(i) := 21.U
  }
  for (i <- 32 until 40) {
    ll_code(i) := 22.U
  }
  for (i <- 40 until 48) {
    ll_code(i) := 23.U
  }
  for (i <- 48 until 64)  {
    ll_code(i) := 24.U
  }

  io.llcode := Mux(io.litlen > 63.U,
    BitOperations.BIT_highbit32(io.litlen) +& ll_deltaCode,
    ll_code(io.litlen))
}

class OFSeqToCode extends Module {
  val io = IO(new Bundle {
    val ofbase = Input(UInt(32.W))
    val ofcode = Output(UInt(8.W))
  })

  io.ofcode := BitOperations.BIT_highbit32(io.ofbase)
}

class MLSeqToCode extends Module {
  val io = IO(new Bundle {
    val mlbase = Input(UInt(32.W))
    val mlcode = Output(UInt(8.W))
  })

  val ml_deltacode = 36.U
  val ml_code = Wire(Vec(128, UInt(8.W)))

  for (i <- 0 until 32) {
    ml_code(i) := i.U
  }
  ml_code(32) := 32.U
  ml_code(33) := 32.U
  ml_code(34) := 33.U
  ml_code(35) := 33.U
  ml_code(36) := 34.U
  ml_code(37) := 34.U
  ml_code(38) := 35.U
  ml_code(39) := 35.U
  for (i <- 40 until 44) {
    ml_code(i) := 36.U
  }
  for (i <- 44 until 48) {
    ml_code(i) := 37.U
  }
  for (i <- 48 until 56) {
    ml_code(i) := 38.U
  }
  for (i <- 56 until 64) {
    ml_code(i) := 39.U
  }
  for (i <- 64 until 80) {
    ml_code(i) := 40.U
  }
  for (i <- 80 until 96) {
    ml_code(i) := 41.U
  }
  for (i <- 96 until 128) {
    ml_code(i) := 42.U
  }

  io.mlcode := Mux(io.mlbase > 127.U,
    BitOperations.BIT_highbit32(io.mlbase) +& ml_deltacode,
    ml_code(io.mlbase))
}
