package compressacc

import chisel3._
import chisel3.util._

object ZstdConsts {
  val ZSTD_WINDOWLOG_ABSOLUTEMIN = 10
  val ZSTD_MAX_COMPRESSION_LEVEL = 22

  val ZSTD_BLOCKSIZELOG_MAX = 17
  val ZSTD_BLOCKSIZE_MAX = 1 << ZSTD_BLOCKSIZELOG_MAX

  val ZSTD_WINDOWSIZELOG_MAX = 27
  val ZSTD_BLOCKHEADER_BYTES = 3

  val ZSTD_SEQUENCE_COMMAND_BYTES = 12

  val ZSTD_SEQUENCE_PREDEFINED_MODE_LIMIT = 20



  // Stuff for debugging
  val FHDR_MEMWRITER_CMPVAL = 2
  val BHDR_MEMWRITER_CMPVAL = 3
}

object CompressorConsts {
  val ZSTD = 0
  val Snappy = 1
}
