package compressacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants



object CompressAccelLogger {

  def logInfo(format: String, args: Bits*)(implicit p: Parameters) {
    val loginfo_cycles = RegInit(0.U(64.W))
    loginfo_cycles := loginfo_cycles + 1.U

    printf("cy: %d, ", loginfo_cycles)
    printf(Printable.pack(format, args:_*))
  }

  def logCritical(format: String, args: Bits*)(implicit p: Parameters) {
    val loginfo_cycles = RegInit(0.U(64.W))
    loginfo_cycles := loginfo_cycles + 1.U

    if (p(CompressAccelPrintfEnable)) {
      printf(midas.targetutils.SynthesizePrintf("cy: %d, ", loginfo_cycles))
      printf(midas.targetutils.SynthesizePrintf(format, args:_*))
    } else {
      printf("cy: %d, ", loginfo_cycles)
      printf(Printable.pack(format, args:_*))
    }
  }





  def logWaveStyle(format: String, args: Bits*)(implicit p: Parameters) {

  }

}

object CompressAccelParams {

}
