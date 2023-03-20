package compressacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.config._


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
  val user_consumed_bytes = UInt(INPUT, log2Up(32+1).W)
  val available_output_bytes = UInt(OUTPUT, log2Up(32+1).W)
  val output_valid = Bool(OUTPUT)
  val output_ready = Bool(INPUT)
  val output_data = UInt(OUTPUT, (32*8).W)
  val output_last_chunk = Bool(OUTPUT)
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
