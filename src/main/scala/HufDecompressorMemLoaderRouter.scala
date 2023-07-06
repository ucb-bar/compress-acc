package compressacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheArbiter}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._

class HufDecompressorMemLoaderRouterIO(val connect_count: Int)(implicit val p: Parameters) extends Bundle {
  val sel_bits = log2Ceil(connect_count)

  val sel = Input(UInt(sel_bits.W))

  val memloader_info_in = Vec(connect_count, Flipped(Decoupled(new StreamInfo)))
  val memloader_info_out = Decoupled(new StreamInfo)

  val consumer_in = Flipped(new MemLoaderConsumerBundle)
  val consumer_out = Vec(connect_count, new MemLoaderConsumerBundle)
}

class HufDecompressorMemLoaderRouter(val connect_count: Int)(implicit val p: Parameters) extends Module {
  val io = IO(new HufDecompressorMemLoaderRouterIO(connect_count))

  for (i <- 0 until connect_count) {
    when (i.U === io.sel) {
      io.memloader_info_out <> io.memloader_info_in(i)
      io.consumer_out(i) <> io.consumer_in
    } .otherwise {
      io.memloader_info_in(i).ready := false.B

      io.consumer_out(i).available_output_bytes := 0.U
      io.consumer_out(i).output_valid := false.B
      io.consumer_out(i).output_data := 0.U
      io.consumer_out(i).output_last_chunk := false.B
    }
  }
}
