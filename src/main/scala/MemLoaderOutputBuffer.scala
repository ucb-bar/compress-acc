package compressacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants


// class MemLoaderConsumerBundle extends Bundle {
//   val user_consumed_bytes = UInt(INPUT, log2Up(32+1).W)
//   val output_ready = Bool(INPUT)
//
//   val output_data = UInt(OUTPUT, (32*8).W)
//   val output_last_chunk = Bool(OUTPUT)
//   val output_valid = Bool(OUTPUT)
//   val available_output_bytes = UInt(OUTPUT, log2Up(32+1).W)

// }

class MemLoaderOutputBuffer()(implicit p: Parameters) extends Module
     with MemoryOpConstants {

  val io = IO(new Bundle {
    val in_from_ml = (new MemLoaderConsumerBundle).flip
    val out_to_consumer = new MemLoaderConsumerBundle
  })

  val data_output_reg = Reg(Vec(32, UInt(8.W)))
  val output_last_chunk_reg = RegInit(false.B)
  val output_valid_reg = RegInit(false.B)
  val available_output_bytes_reg = RegInit(0.U(log2Up(32+1).W))

  io.out_to_consumer.output_data := Cat(data_output_reg)
  io.out_to_consumer.output_last_chunk := output_last_chunk_reg
  io.out_to_consumer.output_valid := output_valid_reg
  io.out_to_consumer.available_output_bytes := available_output_bytes_reg


  // tie-off outputs on I/F to memloader (consumer I/Fs already tied to reg above):
  io.in_from_ml.output_ready := false.B
  io.in_from_ml.user_consumed_bytes := 0.U



  // for transaction to fire, must have io.out_to_consumer.output_ready
  // AND
  // output_valid_reg
  // true
  //
  // then, some optional stuff happens depending on if
  // io.in_from_ml.output_valid is true



  // TODO: cleaner way to do this?
  val data_input_vec = Wire(Vec(32, UInt(8.W)))
  for (chunkno <- 0 until 32) {
    data_input_vec(chunkno) := io.in_from_ml.output_data(8*(chunkno+1)-1, 8*chunkno)
  }


  when (!output_valid_reg) {
    // the pipeline reg is empty, just accept from the memloader into pipe reg

    for (chunkno <- 0 until 32) {
      data_output_reg(chunkno) := data_input_vec(chunkno)
    }
    output_last_chunk_reg := io.in_from_ml.output_last_chunk
    output_valid_reg := io.in_from_ml.output_valid
    available_output_bytes_reg := io.in_from_ml.available_output_bytes

    io.in_from_ml.output_ready := true.B
    io.in_from_ml.user_consumed_bytes := io.in_from_ml.available_output_bytes

  } .elsewhen (output_valid_reg && io.out_to_consumer.output_ready) {
    // consumer is willing to accept and pipeline reg has data

    when (output_last_chunk_reg) {
      when (io.out_to_consumer.user_consumed_bytes === available_output_bytes_reg) {
        // user has consumed the last of a buffer, so clock in fresh input if
        // available

        for (chunkno <- 0 until 32) {
          data_output_reg(chunkno) := data_input_vec(chunkno)
        }
        output_last_chunk_reg := io.in_from_ml.output_last_chunk
        output_valid_reg := io.in_from_ml.output_valid
        available_output_bytes_reg := io.in_from_ml.available_output_bytes

        io.in_from_ml.output_ready := true.B
        io.in_from_ml.user_consumed_bytes := io.in_from_ml.available_output_bytes
      } .otherwise {
        // we're close to the end of a buffer, but there will still be
        // data left from the current buffer. shift over, don't accept
        // new from memloader

        for (chunkno <- 0 until 32) {
          val input_index_calc = chunkno.U + io.out_to_consumer.user_consumed_bytes
          when (input_index_calc < 32.U) {
            data_output_reg(chunkno) := data_output_reg(input_index_calc)
          }
        }

        output_last_chunk_reg := true.B
        output_valid_reg := true.B
        available_output_bytes_reg := available_output_bytes_reg - io.out_to_consumer.user_consumed_bytes

        io.in_from_ml.output_ready := false.B
        io.in_from_ml.user_consumed_bytes := 0.U

      }
    } .otherwise {
      // this isn't part of the end of a buffer, so just:
      // 1) shift over depending on user consumed size
      // 2) accept new based on available space
      // 3) clock in existing avail bytes reg - user consumed + (min(user_consumed, the real memloader's avialble)
      // 4) clock in memloader's last if user consumed === memloaders avail output

      // nc = next cycle
      val nc_bytes_from_reg = available_output_bytes_reg - io.out_to_consumer.user_consumed_bytes

      for (chunkno <- 0 until 32) {
        when (chunkno.U < nc_bytes_from_reg) {
          data_output_reg(chunkno) := data_output_reg(chunkno.U + io.out_to_consumer.user_consumed_bytes)
        } .otherwise {
          // capture from memloader output. it's okay if it's not real data, that
          // will be controlled by valid and avail bytes
          data_output_reg(chunkno) := data_input_vec(chunkno.U - nc_bytes_from_reg)
        }
      }

      output_valid_reg := (nc_bytes_from_reg =/= 0.U) || io.in_from_ml.output_valid

      val nc_reg_free_space = 32.U - nc_bytes_from_reg
      val nc_bytes_from_ml = Mux(io.in_from_ml.available_output_bytes < nc_reg_free_space,
        io.in_from_ml.available_output_bytes,
        nc_reg_free_space)

      io.in_from_ml.user_consumed_bytes := nc_bytes_from_ml
      io.in_from_ml.output_ready := nc_bytes_from_ml =/= 0.U

      available_output_bytes_reg := nc_bytes_from_reg +& nc_bytes_from_ml

      output_last_chunk_reg := io.in_from_ml.output_last_chunk && (io.in_from_ml.available_output_bytes === nc_bytes_from_ml)

    }

  }


}
