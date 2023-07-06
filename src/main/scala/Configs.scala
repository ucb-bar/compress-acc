package compressacc

import chisel3._
import chisel3.util._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheArbiter}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._


case object HufCompressCmdQueDepth extends Field[Int](4)
case object HufCompressUnrollCnt extends Field[Int](2) // NOTE : Don't touch this
case object HufCompressDicBuilderProcessedStatBytesPerCycle extends Field[Int](2)
case object HufCompressDicBuilderProcessedHeaderBytesPerCycle extends Field[Int](2)

case object FSECompressCmdQueDepth extends Field[Int](4)
case object FSECompressDicBuilderProcessedStatBytesPerCycle extends Field[Int](4)
case object FSECompressInterleaveCnt extends Field[Int](2) // NOTE : Don't touch this

case object ZstdLiteralLengthMaxAccuracy extends Field[Int](9)
case object ZstdMatchLengthMaxAccuracy extends Field[Int](9)
case object ZstdOffsetMaxAccuracy extends Field[Int](8)

case object RemoveSnappyFromMergedAccelerator extends Field[Boolean](false)

// Overprovision history buf SRAM size to enable fpga-sim runtime
// reconfigurable sram size sweeping
case object LZ77HistBufOverProvisionFactor extends Field[Integer](2)


case object CompressAccelPrintfEnable extends Field[Boolean](false)
case object CompressAccelLatencyInjectEnable extends Field[Boolean](false)
case object CompressAccelLatencyInjectCycles extends Field[Integer](400)
case object CompressAccelFarAccelLocalCache extends Field[Boolean](false)




case object HyperscaleSoCTapeOut extends Field[Boolean](false)


//////////////////////////////////////////////////////////////////////////////
// ZSTD stuff
//////////////////////////////////////////////////////////////////////////////
class WithZstdCompressor extends Config ((site, here, up) => {
  case CompressAccelTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case ZstdCompressorKey => Some(ZstdCompressorConfig(
    queDepth = 4
    ))
  case HufCompressUnrollCnt => 2
  case HufCompressDicBuilderProcessedStatBytesPerCycle => 2
  case HufCompressDicBuilderProcessedHeaderBytesPerCycle => 4
  case FSECompressDicBuilderProcessedStatBytesPerCycle => 4
  case RemoveSnappyFromMergedAccelerator => true
  case CompressAccelPrintfEnable => true
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val zstd_compressor = LazyModule(new ZstdCompressor(OpcodeSet.custom1)(p))
      zstd_compressor
    }
  )
})

class WithZstdCompressorReducedAccuracy extends Config ((site, here, up) => {
  case CompressAccelTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case ZstdCompressorKey => Some(ZstdCompressorConfig(
    queDepth = 4
    ))
  case HufCompressUnrollCnt => 2
  case HufCompressDicBuilderProcessedStatBytesPerCycle => 2
  case HufCompressDicBuilderProcessedHeaderBytesPerCycle => 4
  case FSECompressDicBuilderProcessedStatBytesPerCycle => 4
  case ZstdLiteralLengthMaxAccuracy => 7
  case ZstdMatchLengthMaxAccuracy => 7
  case ZstdOffsetMaxAccuracy => 6
  case RemoveSnappyFromMergedAccelerator => true
  case CompressAccelPrintfEnable => true
  case BuildRoCC => Seq(
    (p: Parameters) => {
      val zstd_compressor = LazyModule(new ZstdCompressor(OpcodeSet.custom2)(p))
      zstd_compressor
    }
  )
})

class WithZstdCompressorHufUnroll2HufStat8FSEStat4ReducedAccuracy extends Config ((site, here, up) => {
  case CompressAccelTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case ZstdCompressorKey => Some(ZstdCompressorConfig(
    queDepth = 4
    ))
  case HufCompressUnrollCnt => 2
  case HufCompressDicBuilderProcessedStatBytesPerCycle => 8
  case FSECompressDicBuilderProcessedStatBytesPerCycle => 4
  case ZstdLiteralLengthMaxAccuracy => 7
  case ZstdMatchLengthMaxAccuracy => 7
  case ZstdOffsetMaxAccuracy => 6
  case RemoveSnappyFromMergedAccelerator => true
  case CompressAccelPrintfEnable => true
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val zstd_compressor = LazyModule(new ZstdCompressor(OpcodeSet.custom1)(p))
      zstd_compressor
    }
  )
})

class WithZstdCompressorHufUnroll2HufStat16FSEStat4ReducedAccuracy extends Config ((site, here, up) => {
  case CompressAccelTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case ZstdCompressorKey => Some(ZstdCompressorConfig(
    queDepth = 4
    ))
  case HufCompressUnrollCnt => 2
  case HufCompressDicBuilderProcessedStatBytesPerCycle => 16
  case FSECompressDicBuilderProcessedStatBytesPerCycle => 4
  case ZstdLiteralLengthMaxAccuracy => 7
  case ZstdMatchLengthMaxAccuracy => 7
  case ZstdOffsetMaxAccuracy => 6
  case RemoveSnappyFromMergedAccelerator => true
  case CompressAccelPrintfEnable => true
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val zstd_compressor = LazyModule(new ZstdCompressor(OpcodeSet.custom1)(p))
      zstd_compressor
    }
  )
})

class WithZstdCompressorHufUnroll4HufStat4FSEStat4ReducedAccuracy extends Config ((site, here, up) => {
  case CompressAccelTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case ZstdCompressorKey => Some(ZstdCompressorConfig(
    queDepth = 4
    ))
  case HufCompressUnrollCnt => 4
  case HufCompressDicBuilderProcessedStatBytesPerCycle => 4
  case FSECompressDicBuilderProcessedStatBytesPerCycle => 4
  case ZstdLiteralLengthMaxAccuracy => 7
  case ZstdMatchLengthMaxAccuracy => 7
  case ZstdOffsetMaxAccuracy => 6
  case RemoveSnappyFromMergedAccelerator => true
  case CompressAccelPrintfEnable => true
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val zstd_compressor = LazyModule(new ZstdCompressor(OpcodeSet.custom1)(p))
      zstd_compressor
    }
  )
})

class WithZstdCompressorHufUnroll2HufStat4FSEStat4ReducedAccuracy extends Config ((site, here, up) => {
  case CompressAccelTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case ZstdCompressorKey => Some(ZstdCompressorConfig(
    queDepth = 4
    ))
  case HufCompressUnrollCnt => 2
  case HufCompressDicBuilderProcessedStatBytesPerCycle => 4
  case FSECompressDicBuilderProcessedStatBytesPerCycle => 4
  case ZstdLiteralLengthMaxAccuracy => 7
  case ZstdMatchLengthMaxAccuracy => 7
  case ZstdOffsetMaxAccuracy => 6
  case RemoveSnappyFromMergedAccelerator => true
  case CompressAccelPrintfEnable => true
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val zstd_compressor = LazyModule(new ZstdCompressor(OpcodeSet.custom1)(p))
      zstd_compressor
    }
  )
})

class WithZstdCompressorHist2MBHufUnroll2HufStat4FSEStat4ReducedAccuracy extends Config ((site, here, up) => {
  case CompressAccelTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case ZstdCompressorKey => Some(ZstdCompressorConfig(
    queDepth = 4
    ))
  case HufCompressUnrollCnt => 2
  case HufCompressDicBuilderProcessedStatBytesPerCycle => 4
  case FSECompressDicBuilderProcessedStatBytesPerCycle => 4
  case ZstdLiteralLengthMaxAccuracy => 7
  case ZstdMatchLengthMaxAccuracy => 7
  case ZstdOffsetMaxAccuracy => 6
  case RemoveSnappyFromMergedAccelerator => true
  case LZ77HistBufOverProvisionFactor => 64 // 2 -> 128kB, 64 ->  4MB
 
  case CompressAccelPrintfEnable => true
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val zstd_compressor = LazyModule(new ZstdCompressor(OpcodeSet.custom1)(p))
      zstd_compressor
    }
  )
})

class WithZstdCompressorSnappyDecompressor extends Config((site, here, up) => {
  case CompressAccelTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case ZstdCompressorKey => Some(ZstdCompressorConfig(
    queDepth = 4
    ))
  case HufCompressUnrollCnt => 2
  case HufCompressDicBuilderProcessedStatBytesPerCycle => 4
  case FSECompressDicBuilderProcessedStatBytesPerCycle => 4
  case ZstdLiteralLengthMaxAccuracy => 7
  case ZstdMatchLengthMaxAccuracy => 7
  case ZstdOffsetMaxAccuracy => 6
  case RemoveSnappyFromMergedAccelerator => true
  case LZ77HistBufOverProvisionFactor => 2 // 2 -> 128kB, 64 ->  4MB
 
  case CompressAccelPrintfEnable => true
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val snappy_decompressor = LazyModule.apply(new SnappyDecompressor(OpcodeSet.custom0)(p))
      snappy_decompressor
    },
    (p: Parameters) => {
      val zstd_compressor = LazyModule(new ZstdCompressor(OpcodeSet.custom1)(p))
      zstd_compressor
    }
  )
})

class WithMergedCompressorLatencyInjection extends Config((site, here, up) => {
  case CompressAccelTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case ZstdCompressorKey => Some(ZstdCompressorConfig(
    queDepth = 4
    ))
  case HufCompressUnrollCnt => 2
  case HufCompressDicBuilderProcessedStatBytesPerCycle => 4
  case FSECompressDicBuilderProcessedStatBytesPerCycle => 4
  case ZstdLiteralLengthMaxAccuracy => 7
  case ZstdMatchLengthMaxAccuracy => 7
  case ZstdOffsetMaxAccuracy => 6
  case RemoveSnappyFromMergedAccelerator => false
  case LZ77HistBufOverProvisionFactor => 2 // 2 -> 128kB, 64 ->  4MB
 
  case CompressAccelPrintfEnable => true
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val merged_compressor = LazyModule(new ZstdCompressor(OpcodeSet.custom1)(p))
      merged_compressor
    }
  )
})

class WithMergedCompressorSnappyDecompressor extends Config((site, here, up) => {
  case CompressAccelTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case ZstdCompressorKey => Some(ZstdCompressorConfig(
    queDepth = 4
    ))
  case HufCompressUnrollCnt => 2
  case HufCompressDicBuilderProcessedStatBytesPerCycle => 4
  case FSECompressDicBuilderProcessedStatBytesPerCycle => 4
  case ZstdLiteralLengthMaxAccuracy => 7
  case ZstdMatchLengthMaxAccuracy => 7
  case ZstdOffsetMaxAccuracy => 6
  case RemoveSnappyFromMergedAccelerator => false
  case LZ77HistBufOverProvisionFactor => 2 // 2 -> 128kB, 64 ->  4MB
 
  case CompressAccelPrintfEnable => true
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val snappy_decompressor = LazyModule.apply(new SnappyDecompressor(OpcodeSet.custom0)(p))
      snappy_decompressor
    },
    (p: Parameters) => {
      val merged_compressor = LazyModule(new ZstdCompressor(OpcodeSet.custom1)(p))
      merged_compressor
    }
  )
})

//////////////////////////////////////////////////////////////////////////////
// Snappy stuff
//////////////////////////////////////////////////////////////////////////////

class WithSnappyCompressor extends Config ((site, here, up) => {
  case CompressAccelTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val compress_accel_compressor = LazyModule.apply(new SnappyCompressor(OpcodeSet.custom1)(p))
      compress_accel_compressor
    }
  )
})

class WithSnappyCompressorRuntimeOverprovision extends Config ((site, here, up) => {
  case LZ77HistBufOverProvisionFactor => 64
  case CompressAccelTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val compress_accel_compressor = LazyModule.apply(new SnappyCompressor(OpcodeSet.custom1)(p))
      compress_accel_compressor
    }
  )
})

class WithSnappyDecompressor extends Config ((site, here, up) => {
  case CompressAccelTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val compress_accel_decompressor = LazyModule.apply(new SnappyDecompressor(OpcodeSet.custom0)(p))
      compress_accel_decompressor
    }
  )
})

class WithSnappyComplete extends Config((site, here, up) => {
  case CompressAccelTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val compress_accel_decompressor = LazyModule.apply(new SnappyDecompressor(OpcodeSet.custom0)(p))
      compress_accel_decompressor
    },
    (p: Parameters) => {
      val compress_accel_compressor = LazyModule.apply(new SnappyCompressor(OpcodeSet.custom1)(p))
      compress_accel_compressor
    }
  )
})

class AcceleratorPlacementRoCC extends Config ((site, here, up) => {
  case CompressAccelLatencyInjectEnable => false
  case CompressAccelLatencyInjectCycles => 1000
  case CompressAccelFarAccelLocalCache => false
})

class AcceleratorPlacementPCIeNoCache extends Config ((site, here, up) => {
  case CompressAccelLatencyInjectEnable => true
  case CompressAccelLatencyInjectCycles => 400
  case CompressAccelFarAccelLocalCache => false
})

class AcceleratorPlacementPCIeLocalCache extends Config ((site, here, up) => {
  case CompressAccelLatencyInjectEnable => true
  case CompressAccelLatencyInjectCycles => 400
  case CompressAccelFarAccelLocalCache => true
})

class AcceleratorPlacementChiplet extends Config ((site, here, up) => {
  case CompressAccelLatencyInjectEnable => true
  case CompressAccelLatencyInjectCycles => 50
  case CompressAccelFarAccelLocalCache => false
})

class WithSnappyCompleteFireSim extends Config ((site, here, up) => {
  case LZ77HistBufOverProvisionFactor => 64
  case CompressAccelTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val compress_accel_decompressor = LazyModule.apply(new SnappyDecompressor(OpcodeSet.custom0)(p))
      compress_accel_decompressor
    },
    (p: Parameters) => {
      val compress_accel_compressor = LazyModule.apply(new SnappyCompressor(OpcodeSet.custom1)(p))
      compress_accel_compressor
    }
  )
})


class WithSnappyCompleteASIC extends Config ((site, here, up) => {
  case CompressAccelTLB => Some(TLBConfig(nSets = 4, nWays = 4, nSectors = 1, nSuperpageEntries = 1))
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val compress_accel_decompressor = LazyModule.apply(new SnappyDecompressor(OpcodeSet.custom0)(p))
      compress_accel_decompressor
    },
    (p: Parameters) => {
      val compress_accel_compressor = LazyModule.apply(new SnappyCompressor(OpcodeSet.custom1)(p))
      compress_accel_compressor
    }
  )
})


class WithCompressAccelPrintf extends Config((site, here, up) => {
  case CompressAccelPrintfEnable => true
})

