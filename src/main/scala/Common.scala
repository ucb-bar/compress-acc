package compressacc

import chisel3._
import chisel3.util._
import chisel3.util._
import chisel3.{Printable}
import org.chipsalliance.cde.config._


class PtrInfo extends Bundle {
  val ptr = UInt(64.W)
}
class DecompressPtrInfo extends Bundle {
  val ip = UInt(64.W)
}

class StreamInfo extends Bundle {
  val ip = UInt(64.W)
  val isize = UInt(64.W)
}

class DstInfo extends Bundle {
  val op = UInt(64.W)
  val cmpflag = UInt(64.W)
}

class DecompressDstInfo extends Bundle {
  val op = UInt(64.W)
  val cmpflag = UInt(64.W)
}

class DstWithValInfo extends Bundle {
  val op = UInt(64.W)
  val cmpflag = UInt(64.W)
  val cmpval = UInt(64.W)
}

class WriterBundle extends Bundle {
  val data = UInt(256.W)
  val validbytes = UInt(6.W)
  val end_of_message = Bool()
}

class LoadInfoBundle extends Bundle {
  val start_byte = UInt(5.W)
  val end_byte = UInt(5.W)
}

class MemLoaderConsumerBundle extends Bundle {
  val user_consumed_bytes = Input(UInt(log2Up(32+1).W))
  val available_output_bytes = Output(UInt(log2Up(32+1).W))
  val output_valid = Output(Bool())
  val output_ready = Input(Bool())
  val output_data = Output(UInt((32*8).W))
  val output_last_chunk = Output(Bool())
}


class BufInfoBundle extends Bundle {
  val len_bytes = UInt(64.W)
}

object BitOperations {
  def BIT_highbit32(x: UInt): UInt = {
    // add assertion about x width?
    val highBit = 31.U - PriorityEncoder(Reverse(x))
    highBit
  }
}
