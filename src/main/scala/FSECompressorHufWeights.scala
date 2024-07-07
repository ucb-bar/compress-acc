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

class FSECompressorHufWeights(implicit val p: Parameters) extends Module {
  val io = IO(new Bundle{
    val nb_seq = Flipped(Decoupled(UInt(64.W)))
    val input_stream = Flipped(new MemLoaderConsumerBundle) // forward
    val input_stream2 = Flipped(new MemLoaderConsumerBundle) // reverse
    val memwrites_out = Decoupled(new WriterBundle)
    val header_size_info = Decoupled(UInt(8.W))
  })

  val select_set = RegInit(false.B)
  val select_raw = RegInit(false.B)
  val nbseq = RegInit(0.U(64.W))

  when (io.nb_seq.valid && !select_set) {
    select_raw := (io.nb_seq.bits <= 128.U)
    select_set := true.B
    nbseq := io.nb_seq.bits

    CompressAccelLogger.logInfo("HUF_HEADER_COMPRESSOR io.nb_seq.valid\n")
    CompressAccelLogger.logInfo("io.nb_seq.bits: %d\n", io.nb_seq.bits)
  }

  val fse_compressor = Module(new FSECompressorCompHufWeights)
  val raw_compressor = Module(new FSECompressorRawHufWeights)

  val nb_seq_fire = DecoupledHelper(
    io.nb_seq.valid,
    fse_compressor.io.nb_seq.ready
  )

  val select_valid_raw = select_set && select_raw
  val select_valid_fse = select_set && !select_raw

  fse_compressor.io.nb_seq.bits := io.nb_seq.bits
  fse_compressor.io.nb_seq.valid := io.nb_seq.valid && select_valid_fse

  fse_compressor.io.input_stream.output_data := io.input_stream.output_data
  fse_compressor.io.input_stream.output_last_chunk := io.input_stream.output_last_chunk
  fse_compressor.io.input_stream.available_output_bytes := io.input_stream.available_output_bytes
  fse_compressor.io.input_stream.output_valid := io.input_stream.output_valid && select_valid_fse

  fse_compressor.io.input_stream2.output_data := io.input_stream2.output_data
  fse_compressor.io.input_stream2.output_last_chunk := io.input_stream2.output_last_chunk
  fse_compressor.io.input_stream2.available_output_bytes := io.input_stream2.available_output_bytes
  fse_compressor.io.input_stream2.output_valid := io.input_stream2.output_valid && select_valid_fse


  raw_compressor.io.input_stream.output_data := io.input_stream.output_data
  raw_compressor.io.input_stream.output_last_chunk := io.input_stream.output_last_chunk
  raw_compressor.io.input_stream.available_output_bytes := io.input_stream.available_output_bytes
  raw_compressor.io.input_stream.output_valid := io.input_stream.output_valid && select_valid_raw

  io.nb_seq.ready := (fse_compressor.io.nb_seq.ready && select_valid_fse) || select_valid_raw
  io.input_stream.user_consumed_bytes := Mux(select_valid_raw,
                                             raw_compressor.io.input_stream.user_consumed_bytes,
                                             fse_compressor.io.input_stream.user_consumed_bytes)
  io.input_stream.output_ready := Mux(select_valid_raw,
                                      raw_compressor.io.input_stream.output_ready,
                                      fse_compressor.io.input_stream.output_ready)

  io.input_stream2.user_consumed_bytes := Mux(select_valid_raw,
                                             io.input_stream2.available_output_bytes,
                                             fse_compressor.io.input_stream2.user_consumed_bytes)
  io.input_stream2.output_ready := Mux(select_valid_raw,
                                      true.B,
                                      fse_compressor.io.input_stream2.output_ready)

  io.memwrites_out.bits := Mux(select_valid_raw, raw_compressor.io.memwrites_out.bits, fse_compressor.io.memwrites_out.bits)
  io.memwrites_out.valid := Mux(select_valid_raw, raw_compressor.io.memwrites_out.valid, fse_compressor.io.memwrites_out.valid)
  raw_compressor.io.memwrites_out.ready := Mux(select_valid_raw, io.memwrites_out.ready, false.B)
  fse_compressor.io.memwrites_out.ready := Mux(select_valid_raw, false.B, io.memwrites_out.ready)

  val track_written_bytes = RegInit(0.U(64.W))
  val end_of_message_fired = RegInit(false.B)
  when (io.memwrites_out.fire) {
    track_written_bytes := track_written_bytes + io.memwrites_out.bits.validbytes

    when (io.memwrites_out.bits.end_of_message) {
      end_of_message_fired := true.B
    }

    CompressAccelLogger.logInfo("HUF_WEIGHT_COMP_MEMWRITES_FIRE\n")
    CompressAccelLogger.logInfo("io.memwrites_out.bits.validbytes: %d\n", io.memwrites_out.bits.validbytes)
    CompressAccelLogger.logInfo("io.memwrites_out.bits.end_of_message: %d\n", io.memwrites_out.bits.end_of_message)
  }

  io.header_size_info.bits := Mux(select_valid_raw, (128.U + nbseq - 1.U), track_written_bytes)
  io.header_size_info.valid := end_of_message_fired

  when (io.header_size_info.fire) {
    select_set := false.B
    track_written_bytes := 0.U
    end_of_message_fired := false.B
    nbseq := 0.U
  }
}

class FSECompressorCompHufWeights(implicit val p: Parameters) extends Module {
  val io = IO(new Bundle{
    val nb_seq = Flipped(Decoupled(UInt(64.W)))
    val input_stream = Flipped(new MemLoaderConsumerBundle)
    val input_stream2 = Flipped(new MemLoaderConsumerBundle)
    val memwrites_out = Decoupled(new WriterBundle)
  })

  val interleave_cnt = 2
  val cmd_que_depth = 4

  val dic_builder = Module(new FSECompressorDicBuilder(printInfo="HufWeights",
                                                       interleave_cnt=interleave_cnt,
                                                       as_zstd_submodule=false,
                                                       max_symbol_value=12,
                                                       max_table_log=6,
                                                       predefined_table_log=6))
  dic_builder.io.nb_seq <> io.nb_seq
  dic_builder.io.ll_stream <> io.input_stream
  dic_builder.io.predefined_mode.ready := true.B

  val encoder = Module(new FSECompressorEncoder(cmd_que_depth, interleave_cnt))
  encoder.io.src_stream <> io.input_stream2
  encoder.io.table_log <> dic_builder.io.ll_table_log
  encoder.io.header_writes <> dic_builder.io.header_writes
  dic_builder.io.lookup_done <> encoder.io.lookup_done

  for (i <- 0 until interleave_cnt) {
    dic_builder.io.symbol_info(i) <> encoder.io.symbol_info(i)
    encoder.io.comp_trans_table(i) <> dic_builder.io.symbolTT_info(i)

    dic_builder.io.state_table_idx(i) := encoder.io.state_table_idx(i)
    encoder.io.new_state(i) <> dic_builder.io.new_state(i)
  }

  io.memwrites_out <> encoder.io.memwrites_out

  if (p(AnnotateEvents)) {
    dic_builder.io.i_event.get := DontCare
  }
}

class FSECompressorRawHufWeights(implicit val p: Parameters) extends Module {
  val io = IO(new Bundle {
    val input_stream = Flipped(new MemLoaderConsumerBundle)
    val memwrites_out = Decoupled(new WriterBundle)
  })

  val avail_bytes = io.input_stream.available_output_bytes
  val SBUS_BYTES = 32
  val input_data_bytes_vec = WireInit(VecInit(Seq.fill(SBUS_BYTES)(0.U(8.W))))
  for (i <- 0 until SBUS_BYTES) {
    input_data_bytes_vec(i) := Mux(i.U < avail_bytes, io.input_stream.output_data(8*(i+1)-1, 8*i), 0.U)
  }
  val input_data_half_bytes = WireInit(VecInit(Seq.fill(SBUS_BYTES)(0.U(4.W))))
  for (i <- 0 until SBUS_BYTES) {
    input_data_half_bytes(i) := input_data_bytes_vec(i)(3, 0)
  }

  val input_data_cat_half_bytes = WireInit(VecInit(Seq.fill(SBUS_BYTES/2)(0.U(8.W))))
  for (i <- 0 until SBUS_BYTES/2) {
    input_data_cat_half_bytes(i) := Cat(input_data_half_bytes(2*i), input_data_half_bytes(2*(i+1)-1))
  }

  val data = Cat(input_data_cat_half_bytes.reverse)
  val avail_bytes_2 = avail_bytes >> 1.U
  val avail_bytes_odd = (avail_bytes - (avail_bytes_2 << 1.U)) > 0.U
  val write_bytes = Mux(avail_bytes_odd, avail_bytes_2 + 1.U, avail_bytes_2)

  when (io.memwrites_out.fire) {
    CompressAccelLogger.logInfo("RAW_HEADER_COMPRESSOR WRITEFIRE\n")
    for (i <- 0 until SBUS_BYTES) {
      CompressAccelLogger.logInfo("input_data_bytes_vec(%d): %d\n", i.U, input_data_bytes_vec(i))
    }
    CompressAccelLogger.logInfo("avail_bytes: %d\n", avail_bytes)
    CompressAccelLogger.logInfo("avail_bytes_2: %d\n", avail_bytes_2)
    CompressAccelLogger.logInfo("avail_bytes_odd: %d\n", avail_bytes_odd)
    CompressAccelLogger.logInfo("write_bytes: %d\n", write_bytes)
    CompressAccelLogger.logInfo("end_of_message: %d\n", io.memwrites_out.bits.end_of_message)
    CompressAccelLogger.logInfo("data: 0x%x\n", io.memwrites_out.bits.data)
    CompressAccelLogger.logInfo("validbytes: %d\n", io.memwrites_out.bits.validbytes)
  }

  io.memwrites_out.valid := io.input_stream.output_valid
  io.memwrites_out.bits.data := data
  io.memwrites_out.bits.validbytes := write_bytes
  io.memwrites_out.bits.end_of_message := io.input_stream.output_last_chunk

  io.input_stream.output_ready := io.memwrites_out.ready
  io.input_stream.user_consumed_bytes := avail_bytes
}
