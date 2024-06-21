package compressacc

import chisel3._
import chisel3.util._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants


class SnappyDecompressorCopyExpander()(implicit p: Parameters) extends Module with MemoryOpConstants {

  val io = IO(new Bundle {
    val internal_commands_in = Flipped(Decoupled(new SnappyInternalCommandRep))
    val literal_chunks_in = Flipped(Decoupled(new LiteralChunk))

    val internal_commands_out = (Decoupled(new SnappyInternalCommandRep))
    val literal_chunks_out = (Decoupled(new LiteralChunk))
  })


  io.literal_chunks_out <> io.literal_chunks_in

  io.internal_commands_out.bits.is_copy := io.internal_commands_in.bits.is_copy
  io.internal_commands_out.bits.copy_offset := io.internal_commands_in.bits.copy_offset
  io.internal_commands_out.bits.final_command := io.internal_commands_in.bits.final_command && io.internal_commands_in.fire


  val emitted_bytes = RegInit(0.U(7.W))

  val leftover_bytes = io.internal_commands_in.bits.copy_length - emitted_bytes
  val copy_offset = io.internal_commands_in.bits.copy_offset

  val emittable_copy_bytes = Mux(leftover_bytes < 32.U,
    Mux(leftover_bytes < copy_offset,
      leftover_bytes,
      copy_offset),
    Mux(32.U < copy_offset,
      32.U,
      copy_offset)
  )
  io.internal_commands_out.bits.copy_length := emittable_copy_bytes

  val fire_output_command = DecoupledHelper(
    io.internal_commands_in.valid,
    io.internal_commands_out.ready,
  )

  when (io.internal_commands_in.fire) {
    emitted_bytes := 0.U
  } .elsewhen (io.internal_commands_out.fire) {
    emitted_bytes := emitted_bytes + emittable_copy_bytes
  }

  io.internal_commands_in.ready := ((!io.internal_commands_in.bits.is_copy) || (leftover_bytes === emittable_copy_bytes)) && io.internal_commands_out.ready
  io.internal_commands_out.valid := io.internal_commands_in.valid

}

