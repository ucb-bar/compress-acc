package compressacc

import chisel3._
import chisel3.util._

class ValueInfo extends Bundle {
  val symbol = UInt(10.W)
}

class KeyValue(keyWidth: Int, v: ValueInfo) extends Bundle {
  val key = Output(UInt(keyWidth.W))
  val value = Output(v)
}

object KeyValue {
  def apply(keyWidth: Int, v: ValueInfo): KeyValue = new KeyValue(keyWidth, v)
}
