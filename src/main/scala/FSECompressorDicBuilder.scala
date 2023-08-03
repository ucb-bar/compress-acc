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
import ZstdConsts._


class FSECompTransformationTable extends Bundle {
  val nbbit = UInt(32.W)
  val findstate = UInt(32.W)
  val from_last_symbol = Bool()
}

class FSECompressorDicBuilderIO(val interleave_cnt: Int)(implicit p: Parameters) extends Bundle {
  val nb_seq = Flipped(Decoupled(UInt(64.W)))
  val ll_stream = Flipped(new MemLoaderConsumerBundle)

  val ll_table_log = Decoupled(UInt(4.W))
  val symbol_info = Vec(interleave_cnt, Flipped(Decoupled(new FSESymbolInfo)))
  val symbolTT_info = Vec(interleave_cnt, Decoupled(new FSECompTransformationTable))
  val state_table_idx = Vec(interleave_cnt, Input(UInt(16.W)))
  val new_state = Vec(interleave_cnt, Valid(UInt(16.W)))

  val header_writes = Decoupled(new WriterBundle)
  val predefined_mode = Decoupled(Bool())

  val lookup_done = Flipped(Decoupled(Bool()))
}

// NOTE : about 4 * 2^tableLog Bytes of on-chip regs
class FSECompressorDicBuilder(
  val printInfo: String,
  val interleave_cnt: Int, 
  val as_zstd_submodule: Boolean,
  val max_symbol_value: Int, 
  val max_table_log: Int,
  val predefined_table_log: Int,
  val mark_end_of_header: Boolean = false) (implicit p: Parameters) extends Module {

  val io = IO(new FSECompressorDicBuilderIO(interleave_cnt))

  def BIT_highbit32(x: UInt): UInt = {
    // add assertion about x width?
    val highBit = 31.U - PriorityEncoder(Reverse(x))
    highBit
  }

  val rtbTable_initialized = RegInit(false.B)
  val rtbTable = RegInit(VecInit(Seq.fill(8)(0.U(32.W))))
  when (!rtbTable_initialized) {
    rtbTable(0) := 0.U
    rtbTable(1) := 473195.U
    rtbTable(2) := 504333.U
    rtbTable(3) := 520860.U
    rtbTable(4) := 550000.U
    rtbTable(5) := 700000.U
    rtbTable(6) := 750000.U
    rtbTable(7) := 830000.U
    rtbTable_initialized := true.B
  }

  val LL_symbolTTDeltaNbBitsDefaultValues = List(
    327552, 327584, 393088, 393088, 393088, 393088, 393088, 393088, 393088, 393088, 393088, 393088, 
    393088, 393152, 393152, 393152, 393088, 393088, 393088, 393088, 393088, 393088, 393088, 393088, 
    393088, 327584, 393088, 393152, 393152, 393152, 393152, 393152, 393152, 393152, 393152, 393152
  )

  val LL_symbolTTDeltaFindStateDefaultValues = List(
    -4, 1, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23, 
    25, 28, 29, 30, 30, 32, 34, 36, 38, 40, 42, 44, 
    46, 47, 51, 54, 55, 56, 57, 58, 59, 60, 61, 62
  )

  val LL_DefaultTableU16 = List(
    64, 65, 86, 107, 66, 87, 108, 88, 109, 67, 110, 68,
    89, 90, 111, 69, 112, 70, 91, 92, 113, 71, 114, 72,
    93, 94, 115, 73, 116, 95, 74, 117, 75, 96, 97, 118,
    76, 119, 77, 98, 99, 120, 78, 121, 79, 100, 101, 122,
    80, 123, 81, 102, 103, 82, 104, 83, 105, 84, 106, 85,
    127, 126, 125, 124
  )

  val OF_symbolTTDeltaNbBitsDefaultValues = List(
    327648, 327648, 327648, 327648, 327648, 327648, 327616, 327616, 327616, 327648, 327648, 327648, 
    327648, 327648, 327648, 327648, 327648, 327648, 327648, 327648, 327648, 327648, 327648, 327648, 
    327648, 327648, 327648, 327648, 327648
  )

  val OF_symbolTTDeltaFindStateDefaultValues = List(
    -1, 0, 1, 2, 3, 4, 4, 6, 8, 11, 12, 13, 
    14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 
    26, 27, 28, 29, 30
  )

  val OF_DefaultTableU16 = List(
    32, 55, 46, 37, 51, 42, 33, 56, 38, 47, 43, 52, 
    34, 57, 48, 39, 53, 44, 35, 58, 49, 40, 54, 45, 
    36, 50, 41, 63, 62, 61, 60, 59
  )

  val ML_symbolTTDeltaNbBitsDefaultValues = List(
    393152, 327552, 327584, 393088, 393088, 393088, 393088, 393088, 393088, 393152, 393152, 393152, 
    393152, 393152, 393152, 393152, 393152, 393152, 393152, 393152, 393152, 393152, 393152, 393152, 
    393152, 393152, 393152, 393152, 393152, 393152, 393152, 393152, 393152, 393152, 393152, 393152, 
    393152, 393152, 393152, 393152, 393152, 393152, 393152, 393152, 393152, 393152, 393152, 393152, 
    393152, 393152, 393152, 393152, 393152
  )

  val ML_symbolTTDeltaFindStateDefaultValues = List(
    -1, -3, 2, 6, 8, 10, 12, 14, 16, 19, 20, 21, 
    22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 
    34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 
    46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 
    58, 59, 60, 61, 62
  )

  val ML_DefaultTableU16 = List(
    64, 65, 86, 107, 108, 66, 87, 109, 67, 88, 89, 110, 
    68, 111, 69, 90, 91, 112, 70, 113, 92, 71, 114, 93, 
    72, 115, 94, 73, 116, 95, 74, 117, 96, 75, 118, 97, 
    76, 119, 98, 77, 120, 99, 78, 100, 79, 101, 80, 102, 
    81, 103, 82, 104, 83, 105, 84, 106, 85, 127, 126, 125, 
    124, 123, 122, 121
  )



  // Tie up wires
  io.ll_stream.output_ready := false.B
  io.ll_stream.user_consumed_bytes := 0.U
  io.nb_seq.ready := false.B

  val predefined_mode_q = Module(new Queue(Bool(), 4))
  predefined_mode_q.io.enq.valid := false.B
  predefined_mode_q.io.enq.bits := false.B
  io.predefined_mode <> predefined_mode_q.io.deq


  val sIdle = 0.U
  val sCount = 1.U
  val sDivider = 2.U
  val sSetllStep = 3.U
  val sSetProbaBase = 4.U
  val sSetNormalizeCountReg = 5.U
  val sSetNormalizedCouterMaxIdx = 6.U
  val sNormalizeCount = 7.U
  val sBuildCTableSymbolStartPositions = 8.U
  val sBuildCTableSpreadSymbols = 9.U
  val sBuildCTableBuildTable = 10.U
  val sBuildCTableSymbolTT = 11.U
  val sWriteCTable = 12.U
  val sLookup = 13.U
  val dicBuilderState = RegInit(0.U(4.W))


  val COLLECT_STAT_PROCESS_BYTES = p(FSECompressDicBuilderProcessedStatBytesPerCycle)
  val COLLECT_STAT_PROCESS_BYTES_LOG2 = log2Ceil(COLLECT_STAT_PROCESS_BYTES + 1)

  val tableLogLL = max_table_log // Always use maximum possible tableLog since dictionary lookups are basically free???????
  val maxSymbolLL = max_symbol_value
  val maxSymbolLLPlus1 = max_symbol_value + 1

  ///////////////////////////////////////////////////////////////////////////
  // sCount
  ///////////////////////////////////////////////////////////////////////////
  val ll_count = RegInit(VecInit(Seq.fill(maxSymbolLLPlus1)(0.U(32.W))))
  val ll_max_symbol_value = RegInit(0.U(32.W))
  val ll_nbseq_1 = RegInit(0.U(64.W))
  val input_ll_symbols = WireInit(VecInit(Seq.fill(COLLECT_STAT_PROCESS_BYTES)(0.U(8.W))))
  for (i <- 0 until COLLECT_STAT_PROCESS_BYTES) {
    input_ll_symbols(i) := io.ll_stream.output_data >> (i*8).U
  }
  dontTouch(input_ll_symbols)

  val avail_bytes = io.ll_stream.available_output_bytes
  val table = Seq.fill(maxSymbolLLPlus1)(WireInit(VecInit(Seq.fill(COLLECT_STAT_PROCESS_BYTES)(0.U(1.W)))))
  for (i <- 0 until maxSymbolLLPlus1) {
    for (j <- 0 until COLLECT_STAT_PROCESS_BYTES) {
      table(i)(j) := Mux(j.U < avail_bytes && (input_ll_symbols(j) === i.U), 1.U, 0.U)
    }
  }

  val stat_sum = WireInit(VecInit(Seq.fill(maxSymbolLLPlus1)(0.U(COLLECT_STAT_PROCESS_BYTES_LOG2.W))))
  for (i <- 0 until maxSymbolLLPlus1) {
    stat_sum(i) := table(i).reduce(_ +& _)
  }

  val has_value = WireInit(VecInit(Seq.fill(maxSymbolLLPlus1)(0.U(1.W))))
  for (i <- 0 until maxSymbolLLPlus1) {
    has_value(i) := Mux(stat_sum(i) > 0.U, 1.U, 0.U)
  }
  val has_value_cat = Cat(has_value)
  val cur_max_value = (maxSymbolLLPlus1 - 1).U - PriorityEncoder(has_value_cat)

  when (dicBuilderState === sCount) {
    when (io.ll_stream.output_valid) {
      for (i <- 0 until maxSymbolLLPlus1) {
        ll_count(i) := ll_count(i) + stat_sum(i)
      }

      ll_max_symbol_value := Mux(ll_max_symbol_value > cur_max_value, ll_max_symbol_value, cur_max_value)
    }
  }

  ///////////////////////////////////////////////////////////////////////////
  // sNormalizeCount (FSE_normalizeCount)
  ///////////////////////////////////////////////////////////////////////////
  /*
   *    {   static U32 const rtbTable[] = {     0, 473195, 504333, 520860, 550000, 700000, 750000, 830000 };
   *    short const lowProbCount = useLowProbCount ? -1 : 1;
   *    U64 const scale = 62 - tableLog;
   *    U64 const step = ZSTD_div64((U64)1<<62, (U32)total); 
   *    U64 const vStep = 1ULL<<(scale-20);
   *    int stillToDistribute = 1<<tableLog;
   *    unsigned s;
   *    unsigned largest=0;
   *    short largestP=0;
   *    U32 lowThreshold = (U32)(total >> tableLog);
   *    for (s=0; s<=maxSymbolValue; s++) {
   *        if (count[s] == total) return 0;   // rle special case
   *        if (count[s] == 0) { normalizedCounter[s]=0; continue; }
   *        if (count[s] <= lowThreshold) {
   *            normalizedCounter[s] = lowProbCount;
   *            stillToDistribute--;
   *        } else {
   *            short proba = (short)((count[s]*step) >> scale);
   *            if (proba<8) {
   *                U64 restToBeat = vStep * rtbTable[proba];
   *                proba += (count[s]*step) - ((U64)proba<<scale) > restToBeat;
   *            }
   *            if (proba > largestP) { largestP=proba; largest=s; }
   *            normalizedCounter[s] = proba;
   *            stillToDistribute -= proba;
   *    }   }
   *    if (-stillToDistribute >= (normalizedCounter[largest] >> 1)) {
   *        // corner case, need another normalization method
   *        size_t const errorCode = FSE_normalizeM2(normalizedCounter, tableLog, count, total, maxSymbolValue, lowProbCount);
   *        if (FSE_isError(errorCode)) return errorCode;
   *    }
   *    else normalizedCounter[largest] += (short)stillToDistribute;
   */
  val neg_one_uint16 = (1 << 16) - 1
  // val ll_useLowProbCount = ll_nbseq_1 >= 2048.U
  // don't use lowProb stuff for now, (small comp ratio gains for thousands of cycle tradeoff)
  val ll_useLowProbCount = ll_nbseq_1 >= BigInt("FFFFFFFF", 16).U
  val ll_lowProbCount = Wire(UInt(16.W))
  ll_lowProbCount := Mux(ll_useLowProbCount, neg_one_uint16.U, 1.U)
  val ll_scale = Wire(UInt(7.W))
  ll_scale := 62.U - tableLogLL.U
  val ll_scale_20 = Wire(UInt(7.W))
  ll_scale_20 := ll_scale - 20.U
  val ll_vStep = Wire(UInt(64.W))
  ll_vStep := 1.U << ll_scale_20
  val ll_still_to_distribute = (1 << tableLogLL).U
  val ll_lowThreshold = Wire(UInt(32.W))
  ll_lowThreshold := ll_nbseq_1 >> tableLogLL.U


  val divider = Module(new PipelinedDivider(64))
  divider.io.A := BigInt("4000000000000000", 16).U(64.W)
  divider.io.B := ll_nbseq_1
  divider.io.start := false.B

  val ll_step = Reg(UInt(64.W))
  ll_step := Mux(divider.io.done, divider.io.Q, ll_step)


  val ll_proba_base = RegInit(VecInit(Seq.fill(maxSymbolLL + 1)(0.U(16.W))))
  val ll_proba = WireInit(VecInit(Seq.fill(maxSymbolLL + 1)(0.U(16.W))))
  val ll_count_times_step = WireInit(VecInit(Seq.fill(maxSymbolLL + 1)(0.U(64.W))))
  for (i <- 0 until maxSymbolLL + 1) {
    ll_count_times_step(i) := ll_count(i) * ll_step
    ll_proba_base(i) := ll_count_times_step(i) >> ll_scale

    val restToBeat = ll_vStep * rtbTable(ll_proba_base(i))
    val ll_add_to_proba_base = Mux(ll_count(i) * ll_step - (ll_proba_base(i) << ll_scale) > restToBeat, 1.U, 0.U)
    ll_proba(i) := Mux(ll_proba_base(i) < 8.U, ll_proba_base(i) + ll_add_to_proba_base, ll_proba_base(i))
  }


  val ll_normalizedCounter = RegInit(VecInit(Seq.fill(maxSymbolLL + 1)(0.U(16.W))))
  val ll_normalizedCounterMaxAdjusted = WireInit(VecInit(Seq.fill(maxSymbolLL + 1)(0.U(16.W))))
  val ll_count_eq_nbseq_1 = WireInit(VecInit(Seq.fill(maxSymbolLLPlus1)(false.B)))
  for (i <- 0 until maxSymbolLLPlus1) {
    ll_count_eq_nbseq_1(i) := (ll_count(i) === ll_nbseq_1)
  }
  val ll_count_has_nbseq_1_as_value = ll_count_eq_nbseq_1.reduceTree(_ || _)
  val ll_rle = ll_count_has_nbseq_1_as_value

  for (i <- 0 until maxSymbolLL + 1) {
    ll_normalizedCounter(i) := Mux(ll_count(i) === 0.U, 0.U,
                                Mux(ll_count(i) <= ll_lowThreshold, ll_lowProbCount,
                                  ll_proba(i)))
  }

  val ll_countSmallOrEqToLowThld = WireInit(VecInit(Seq.fill(maxSymbolLLPlus1)(0.U(16.W))))
  for (i <- 0 until maxSymbolLLPlus1) {
    val count = ll_count(i)
    ll_countSmallOrEqToLowThld(i) := ((count <= ll_lowThreshold) && (count > 0.U)).asUInt
  }
  val ll_smallOrEqToLowThresholdCount = ll_countSmallOrEqToLowThld.reduceTree(_ +& _)

  val ll_largerThanLowThreshold = WireInit(VecInit(Seq.fill(maxSymbolLLPlus1)(0.U(16.W))))
  for (i <- 0 until maxSymbolLLPlus1) {
    val count = ll_count(i)
    ll_largerThanLowThreshold(i) := Mux((count === ll_nbseq_1) || (count === 0.U) || (count <= ll_lowThreshold),
      0.U,
      ll_proba(i))
  }
  val ll_largerThanLowThresholdProbaSum = ll_largerThanLowThreshold.reduceTree(_ +& _)

  val ll_normalizedCounterMax = ll_normalizedCounter.reduceTree((a, b) => Mux(a > b, a, b))
// val ll_normalizedCounterIdx = WireInit(VecInit(Seq.fill(maxSymbolLL + 1)(0.U(16.W))))
// for (i <- 0 until maxSymbolLL + 1) {
// ll_normalizedCounterIdx(i) := i.U
// }

// val ll_normalizedCounterMaxIdx = ll_normalizedCounter.zip(ll_normalizedCounterIdx).reduce{ (x, y) =>
// (Mux(x._1 < y._1, y._1, x._1), Mux(x._1 < y._1, y._2, x._2))
// }._2

  val ll_maxIdxIter = RegInit(0.U(16.W))
  val ll_normalizedCounterMaxIdx = RegInit(0.U(16.W))
// val ll_normalizedCounterMaxIdx = ll_normalizedCounter.indexWhere ( x => x === ll_normalizedCounterMax )

  val ll_nxtStillToDistribute = (ll_still_to_distribute - ll_largerThanLowThresholdProbaSum - ll_smallOrEqToLowThresholdCount).asSInt
  val ll_negNxtStillToDistribute = (-1).S * ll_nxtStillToDistribute

  val fse_normalize_corner_case = ll_negNxtStillToDistribute >= (ll_normalizedCounterMax >> 1.U).asSInt
  val fse_normalize_corner_case_reg = RegInit(false.B)

  when (dicBuilderState === sNormalizeCount && predefined_mode_q.io.enq.ready) {
    predefined_mode_q.io.enq.valid := true.B

    for (i <- 0 until maxSymbolLL + 1) {
      val ll_ncountSumStill2Dist = (ll_normalizedCounter(i).asSInt + ll_nxtStillToDistribute).asUInt

      ll_normalizedCounterMaxAdjusted(i) := Mux(i.U === ll_normalizedCounterMaxIdx,
        ll_ncountSumStill2Dist,
        ll_normalizedCounter(i))
    }

    // Force predefined mode when we encounter normalization corner case
    fse_normalize_corner_case_reg := fse_normalize_corner_case
    predefined_mode_q.io.enq.bits := fse_normalize_corner_case

    when (fse_normalize_corner_case) {
      CompressAccelLogger.logInfo(printInfo + " DICBUILDER ForcePredefinedMode\n")
    }
  }

  // insert registers to avoid critical path issues
  val ll_normalizedCounterReg = RegInit(VecInit(Seq.fill(maxSymbolLL + 1)(0.U(16.W))))

  when (dicBuilderState === sNormalizeCount) {
    for (i <- 0 until maxSymbolLL + 1) {
      CompressAccelLogger.logInfo(printInfo + " ll_count(%d): %d\n", i.U, ll_count(i))
    }
    CompressAccelLogger.logInfo(printInfo + " ll_lowProbCount: %d\n", ll_lowProbCount)
    CompressAccelLogger.logInfo(printInfo + " ll_scale: %d\n", ll_scale)
    CompressAccelLogger.logInfo(printInfo + " ll_scale_20: %d\n", ll_scale_20)
    CompressAccelLogger.logInfo(printInfo + " ll_step: %d\n", ll_step)
    CompressAccelLogger.logInfo(printInfo + " ll_vStep: %d\n", ll_vStep)
    CompressAccelLogger.logInfo(printInfo + " ll_still_to_distribute: %d\n", ll_still_to_distribute)
    CompressAccelLogger.logInfo(printInfo + " ll_lowThreshold: %d\n", ll_lowThreshold)

    for (i <- 0 until maxSymbolLL + 1) {
      CompressAccelLogger.logInfo(printInfo + " ll_count_times_step(%d): %d\n", i.U, ll_count_times_step(i))
    }
    for (i <- 0 until maxSymbolLL + 1) {
      CompressAccelLogger.logInfo(printInfo + " ll_proba_base(%d): %d\n", i.U, ll_proba_base(i))
    }
    for (i <- 0 until maxSymbolLL + 1) {
      CompressAccelLogger.logInfo(printInfo + " ll_proba(%d): %d\n", i.U, ll_proba(i))
    }
    for (i <- 0 until maxSymbolLL + 1) {
      CompressAccelLogger.logInfo(printInfo + " ll_normalizedCounter(%d): %d\n", i.U, ll_normalizedCounter(i))
    }
    for (i <- 0 until maxSymbolLL + 1) {
      CompressAccelLogger.logInfo(printInfo + " ll_normalizedCounterMaxAdjusted(%d): %d\n", i.U, ll_normalizedCounterMaxAdjusted(i))
    }
    CompressAccelLogger.logInfo(printInfo + " ll_smallOrEqToLowThresholdCount: %d\n", ll_smallOrEqToLowThresholdCount)
    CompressAccelLogger.logInfo(printInfo + " ll_largerThanLowThresholdProbaSum: %d\n", ll_largerThanLowThresholdProbaSum)
    CompressAccelLogger.logInfo(printInfo + " ll_normalizedCounterMax: %d\n", ll_normalizedCounterMax)
    CompressAccelLogger.logInfo(printInfo + " ll_normalizedCounterMaxIdx: %d\n", ll_normalizedCounterMaxIdx)
    CompressAccelLogger.logInfo(printInfo + " ll_nxtStillToDistribute: %d\n", ll_nxtStillToDistribute)
    CompressAccelLogger.logInfo(printInfo + " ll_negNxtStillToDistribute: %d\n", ll_negNxtStillToDistribute)
    CompressAccelLogger.logInfo(printInfo + " (ll_normalizedCounterMax >> 1.U).asSInt: %d\n", (ll_normalizedCounterMax >> 1.U).asSInt)
  }

  ///////////////////////////////////////////////////////////////////////////
  // sBuildCTableSymbolStartPositions
  ///////////////////////////////////////////////////////////////////////////
  val LL_TABLESIZE = 1 << tableLogLL
  val ll_maxSV1 = ll_max_symbol_value + 1.U
  val ll_tableSize = LL_TABLESIZE.U
  val ll_tableMask = ll_tableSize - 1.U
  val ll_cumul = WireInit(VecInit(Seq.fill(maxSymbolLL + 1)(0.U(16.W))))
  val ll_tableSymbol = RegInit(VecInit(Seq.fill(LL_TABLESIZE)(0.U(8.W))))

  val ll_highThresholdBeforeCumul = WireInit(0.U(32.W))
  ll_highThresholdBeforeCumul := ll_tableSize - 1.U

  val ll_normCountEqsNegOne = WireInit(VecInit(Seq.fill(maxSymbolLL + 1)(0.U(8.W))))
  val ll_normCountEqsNegOneCumul = WireInit(VecInit(Seq.fill(maxSymbolLL + 1)(0.U(8.W))))
  val ll_normCountEqsNegOneSum = ll_normCountEqsNegOne.reduce(_ +& _)

  val ll_highThresholdAfterCumul = RegInit(0.U(32.W))

  val ll_cumulReg = RegInit(VecInit(Seq.fill(maxSymbolLL + 1)(0.U(16.W))))

  ///////////////////////////////////////////////////////////////////////////
  // sBuildCTableSpreadSymbols
  ///////////////////////////////////////////////////////////////////////////
  val LL_SPREAD_TABLE_SIZE = LL_TABLESIZE + 8
  val ll_spread = RegInit(VecInit(Seq.fill(LL_SPREAD_TABLE_SIZE)(0.U(8.W))))
  val add = BigInt("0101010101010101", 16)
  val ll_pos = RegInit(0.U(64.W))
  val ll_s = RegInit(0.U(64.W))
  val ll_sv = RegInit(0.U(64.W))
  val ll_fse_tablestep = (ll_tableSize >> 1.U) + (ll_tableSize >> 3.U) + 3.U

  ///////////////////////////////////////////////////////////////////////////
  // sBuildCTableBuildTable / sBuildCTableSymbolTT
  ///////////////////////////////////////////////////////////////////////////

  val ll_tableU16 = RegInit(VecInit(Seq.fill(LL_TABLESIZE)(0.U(16.W))))
  val ll_symbolTTDeltaNbBits = RegInit(VecInit(Seq.fill(maxSymbolLL + 1)(0.U(32.W))))
  val ll_symbolTTDeltaFindState = RegInit(VecInit(Seq.fill(maxSymbolLL + 1)(0.S(32.W))))
  val ll_total = RegInit(0.U(32.W))
  val normCount = Wire(UInt(32.W))
  normCount := ll_normalizedCounterReg(ll_s)

  ///////////////////////////////////////////////////////////////////////////
  // sLookup
  ///////////////////////////////////////////////////////////////////////////

  val symbolTT_lookup_fire_and_last_vec = WireInit(VecInit(Seq.fill(interleave_cnt)(false.B)))
  for (i <- 0 until interleave_cnt) {
    val cur_symbol = io.symbol_info(i).bits.symbol
    val last_symbol = io.symbol_info(i).bits.last_symbol
    val symbolTT_lookup_fire = DecoupledHelper(
      io.symbol_info(i).valid,
      io.symbolTT_info(i).ready,
      dicBuilderState === sLookup)

    io.symbol_info(i).ready := symbolTT_lookup_fire.fire(io.symbol_info(i).valid)

    io.symbolTT_info(i).valid := symbolTT_lookup_fire.fire(io.symbolTT_info(i).ready)
    io.symbolTT_info(i).bits.nbbit := ll_symbolTTDeltaNbBits(cur_symbol)
    io.symbolTT_info(i).bits.findstate := ll_symbolTTDeltaFindState(cur_symbol).asUInt
    io.symbolTT_info(i).bits.from_last_symbol := last_symbol

    io.new_state(i).valid := (dicBuilderState === sLookup)
    io.new_state(i).bits := ll_tableU16(io.state_table_idx(i))

    symbolTT_lookup_fire_and_last_vec(i) := symbolTT_lookup_fire.fire && last_symbol
  }

  val dictionary_lookup_done = symbolTT_lookup_fire_and_last_vec.reduce(_ || _)


  // this is okay since we know for sure that in huffman, the fse compressor will
  // not be kicked-off unless there are enough symobls to compress
  val use_predefined_mode = (io.nb_seq.bits <= ZSTD_SEQUENCE_PREDEFINED_MODE_LIMIT.U) || fse_normalize_corner_case_reg

  val ll_table_log_fired = RegInit(false.B)
  io.ll_table_log.bits := Mux(use_predefined_mode, predefined_table_log.U, tableLogLL.U)
  io.ll_table_log.valid := !ll_table_log_fired && (dicBuilderState === sLookup)
  when (io.ll_table_log.fire) {
    ll_table_log_fired := true.B
  }

  val print_table = RegInit(true.B)


  val write_header_started = RegInit(false.B)
  val nbBits = RegInit(0.U(32.W))
  val remaining = RegInit(0.U(32.W))
  val threshold = RegInit(0.U(32.W))
  val symbol = RegInit(0.U(32.W))
  val alphabetSize = ll_max_symbol_value + 1.U
  val previousIs0 = RegInit(false.B)
  val bitStream = RegInit(0.U(64.W))
  val bitCount = RegInit(0.U(7.W))
  val writeBitStream = RegInit(false.B) // TODO : optimize to write & update bitStream at the same time
  val start = RegInit(0.U(32.W))
  val start_initialized = RegInit(false.B)
  val skip_zeros_done = RegInit(false.B)
  val skip_24_done = RegInit(false.B)
  val skip_3_done = RegInit(false.B)
  val writeBitStreamPrev0 = RegInit(false.B)
  val FSE_MIN_TABLELOG = 5

  io.header_writes.valid := false.B
  io.header_writes.bits.data := 0.U
  io.header_writes.bits.validbytes := 0.U
  io.header_writes.bits.end_of_message := false.B

  val shifted_thresholds = WireInit(VecInit(Seq.fill(tableLogLL+1)(0.U(32.W))))
  shifted_thresholds(0) := threshold
  for (i <- 1 until tableLogLL + 1) {
    shifted_thresholds(i) := shifted_thresholds(i-1) >> 1.U
  }

  val shifted_threshold_small_or_eq_remaining = WireInit(VecInit(Seq.fill(tableLogLL+1)(0.U(32.W))))
  val nxt_shifted_threshold_idx = shifted_threshold_small_or_eq_remaining.reduce(_ + _)

  io.lookup_done.ready := true.B
  when (io.lookup_done.valid) {

    for (i <- 0 until maxSymbolLL+1) {
      ll_count(i) := 0.U
    }

    ll_max_symbol_value := 0.U
    ll_nbseq_1 := 0.U

    ll_maxIdxIter := 0.U
    ll_normalizedCounterMaxIdx := 0.U

    for (i <- 0 until maxSymbolLL + 1) {
      ll_normalizedCounter(i) := 0.U
      ll_proba_base(i) := 0.U
      ll_normalizedCounterReg(i) := 0.U
    }

    for (i <- 0 until LL_TABLESIZE) {
      ll_tableSymbol(i) := 0.U
    }

    ll_highThresholdAfterCumul := 0.U
    for (i <- 0 until maxSymbolLL + 1) {
      ll_cumulReg(i) := 0.U
    }

    for (i <- 0 until LL_SPREAD_TABLE_SIZE) {
      ll_spread(i) := 0.U
    }
    ll_pos := 0.U
    ll_s := 0.U
    ll_sv := 0.U
    for (i <- 0 until LL_TABLESIZE) {
      ll_tableU16(i) := 0.U
    }

    for (i <- 0 until maxSymbolLL+ 1) {
      ll_symbolTTDeltaNbBits(i) := 0.U
      ll_symbolTTDeltaFindState(i) := 0.S
    }
    ll_total := 0.U
    ll_table_log_fired := false.B
    fse_normalize_corner_case_reg := false.B
    print_table := true.B
    write_header_started := false.B
    nbBits := 0.U
    remaining := 0.U
    threshold := 0.U
    symbol := 0.U
    previousIs0 := false.B
    bitStream := 0.U
    bitCount := 0.U
    writeBitStream := false.B
    start := 0.U
    start_initialized := false.B
    skip_zeros_done := false.B
    skip_24_done := false.B
    skip_3_done := false.B
    writeBitStreamPrev0 := false.B
  }

  switch (dicBuilderState) {
    is (sIdle) {
      when (io.ll_stream.output_valid && io.nb_seq.valid) {
        dicBuilderState := sCount
      }
    }

    is (sCount) {
      io.ll_stream.output_ready := predefined_mode_q.io.enq.ready
      io.ll_stream.user_consumed_bytes := Mux(io.ll_stream.available_output_bytes < COLLECT_STAT_PROCESS_BYTES.U,
                                              io.ll_stream.available_output_bytes,
                                              COLLECT_STAT_PROCESS_BYTES.U)

      when (io.ll_stream.output_valid) {
        for (i <- 0 until maxSymbolLL + 1) {
          ll_count(i) := ll_count(i) + stat_sum(i)
        }

        ll_max_symbol_value := Mux(ll_max_symbol_value > cur_max_value, ll_max_symbol_value, cur_max_value)
      }

      when (predefined_mode_q.io.enq.ready && io.ll_stream.output_valid && io.ll_stream.output_last_chunk && (io.ll_stream.user_consumed_bytes === io.ll_stream.available_output_bytes)) {
        // zstd_compress_sequences.c 271
        // if (count[codeTable[nbSeq-1]] > 1) {
        //   count[codeTable[nbSeq-1]]--;
        //   nbSeq_1--;
        // }
        if (as_zstd_submodule) {
          val ll_last_codetable = input_ll_symbols(io.ll_stream.user_consumed_bytes - 1.U)
          val ll_count_last_codetable = ll_count(ll_last_codetable)
          val ll_last_statcount = stat_sum(ll_last_codetable)

          val ll_last_count = ll_count_last_codetable + ll_last_statcount
          val do_subtract = ll_last_count > 1.U
          ll_nbseq_1 := Mux(do_subtract, io.nb_seq.bits - 1.U, io.nb_seq.bits)
          ll_count(ll_last_codetable) := Mux(do_subtract, ll_last_count - 1.U, ll_last_count)
        } else {
          ll_nbseq_1 := io.nb_seq.bits
        }

        when (!use_predefined_mode) {
          dicBuilderState := sDivider
        } .otherwise {

          // TODO : set io.ll_stream.output_ready to true & move on to sLookup without going through sCount
          dicBuilderState := sLookup

          predefined_mode_q.io.enq.valid := true.B
          predefined_mode_q.io.enq.bits := true.B


          if (printInfo == "LL") {
            for (i <- 0 until 36) {
              ll_symbolTTDeltaNbBits(i) := LL_symbolTTDeltaNbBitsDefaultValues(i).U
              ll_symbolTTDeltaFindState(i) := LL_symbolTTDeltaFindStateDefaultValues(i).S
            }
            for (i <- 0 until 64) {
              ll_tableU16(i) := LL_DefaultTableU16(i).U
            }
          } else if (printInfo == "ML") {
            for (i <- 0 until 53) {
              ll_symbolTTDeltaNbBits(i) := ML_symbolTTDeltaNbBitsDefaultValues(i).U
              ll_symbolTTDeltaFindState(i) := ML_symbolTTDeltaFindStateDefaultValues(i).S
            }
            for (i <- 0 until 64) {
              ll_tableU16(i) := ML_DefaultTableU16(i).U
            }
          } else if (printInfo == "OF") {
            for (i <- 0 until 29) {
              ll_symbolTTDeltaNbBits(i) := OF_symbolTTDeltaNbBitsDefaultValues(i).U
              ll_symbolTTDeltaFindState(i) := OF_symbolTTDeltaFindStateDefaultValues(i).S
            }
            for (i <- 0 until 32) {
              ll_tableU16(i) := OF_DefaultTableU16(i).U
            }
          }
        }
      }
    }

    is (sDivider) {
      divider.io.start := true.B
      when (divider.io.done) {
        dicBuilderState := sSetllStep
      }
    }

    is (sSetllStep) {
      dicBuilderState := sSetProbaBase
    }

    is (sSetProbaBase) {
      dicBuilderState := sSetNormalizeCountReg
    }

    is (sSetNormalizeCountReg) {
      dicBuilderState := sSetNormalizedCouterMaxIdx
    }

    is (sSetNormalizedCouterMaxIdx) {
      val cur = ll_normalizedCounter(ll_maxIdxIter)
      when (cur === ll_normalizedCounterMax) {
        ll_normalizedCounterMaxIdx := ll_maxIdxIter
        dicBuilderState := sNormalizeCount
      } .otherwise {
        ll_maxIdxIter := ll_maxIdxIter + 1.U
      }
    }

    is (sNormalizeCount) {
      CompressAccelLogger.logInfo(printInfo + "ll_nbseq_1: %d\n", ll_nbseq_1)

      for (i <- 0 until maxSymbolLL + 1) {
        ll_normalizedCounterReg(i) := ll_normalizedCounterMaxAdjusted(i)
      }

      for (i <- 0 until maxSymbolLL + 1) {
        CompressAccelLogger.logInfo(printInfo + " ll_proba_base(%d): %d\n", i.U, ll_proba_base(i))
      }
      for (i <- 0 until maxSymbolLL + 1) {
        CompressAccelLogger.logInfo(printInfo + " ll_proba(%d): %d\n", i.U, ll_proba(i))
      }
      for (i <- 0 until maxSymbolLL + 1) {
        CompressAccelLogger.logInfo(printInfo + " ll_proba(%d): %d\n", i.U, ll_proba(i))
      }
      for (i <- 0 until maxSymbolLL + 1) {
        CompressAccelLogger.logInfo(printInfo + " ll_normalizedCounter(%d): %d\n", i.U, ll_normalizedCounter(i))
      }
      CompressAccelLogger.logInfo(printInfo + " ll_lowThreshold: %d\n", ll_lowThreshold)
      CompressAccelLogger.logInfo(printInfo + " ll_lowProbCount: %d\n", ll_lowProbCount)

      // Takes only one cycle
      when (predefined_mode_q.io.enq.ready) {
        when (fse_normalize_corner_case) {
          dicBuilderState := sLookup

          if (printInfo == "LL") {
            for (i <- 0 until 36) {
              ll_symbolTTDeltaNbBits(i) := LL_symbolTTDeltaNbBitsDefaultValues(i).U
              ll_symbolTTDeltaFindState(i) := LL_symbolTTDeltaFindStateDefaultValues(i).S
            }
            for (i <- 0 until 64) {
              ll_tableU16(i) := LL_DefaultTableU16(i).U
            }
          } else if (printInfo == "ML") {
            for (i <- 0 until 53) {
              ll_symbolTTDeltaNbBits(i) := ML_symbolTTDeltaNbBitsDefaultValues(i).U
              ll_symbolTTDeltaFindState(i) := ML_symbolTTDeltaFindStateDefaultValues(i).S
            }
            for (i <- 0 until 64) {
              ll_tableU16(i) := ML_DefaultTableU16(i).U
            }
          } else if (printInfo == "OF") {
            for (i <- 0 until 29) {
              ll_symbolTTDeltaNbBits(i) := OF_symbolTTDeltaNbBitsDefaultValues(i).U
              ll_symbolTTDeltaFindState(i) := OF_symbolTTDeltaFindStateDefaultValues(i).S
            }
            for (i <- 0 until 32) {
              ll_tableU16(i) := OF_DefaultTableU16(i).U
            }
          }
        } .otherwise {
          dicBuilderState := sBuildCTableSymbolStartPositions
        }
      }
    }

    is (sBuildCTableSymbolStartPositions) {
      ll_normCountEqsNegOneCumul(0) := ll_normCountEqsNegOne(0)
      for (i <- 1 until maxSymbolLL + 1) {
        when (ll_normalizedCounterReg(i-1) === neg_one_uint16.U) {
          ll_normCountEqsNegOne(i-1) := 1.U
          ll_cumul(i) := ll_cumul(i-1) + 1.U
          ll_tableSymbol(ll_highThresholdBeforeCumul - ll_normCountEqsNegOneCumul(i-1) + 1.U) := (i-1).U
        } .otherwise {
          ll_cumul(i) := ll_cumul(i-1) + ll_normalizedCounterReg(i-1)
        }

        ll_normCountEqsNegOneCumul(i) := ll_normCountEqsNegOneCumul(i-1) + ll_normCountEqsNegOne(i)
      }

      ll_cumul(ll_maxSV1) := ll_tableSize + 1.U
      ll_highThresholdAfterCumul := ll_highThresholdBeforeCumul - ll_normCountEqsNegOneSum

      for (i <- 0 until maxSymbolLL + 1) {
        ll_cumulReg(i) := ll_cumul(i)
      }

      // Takes only one cycle
      dicBuilderState := sBuildCTableSpreadSymbols
    }

    is (sBuildCTableSpreadSymbols) {
      when (ll_highThresholdAfterCumul === ll_tableSize - 1.U) {
        ll_s := ll_s + 1.U
        ll_sv := ll_sv + add.U
        val n = ll_normalizedCounterReg(ll_s)
        val write_spread_cnt = n >> 3.U
        val write_extra = (n & 7.U) =/= 0.U
        val write_spread_cnt_wrapped = write_spread_cnt +& write_extra.asUInt
        val write_spread_bytes = write_spread_cnt_wrapped << 3.U
        ll_pos := ll_pos + n

        // pos + n
        for (i <- 0 until LL_SPREAD_TABLE_SIZE) {
          when ((i.U >= ll_pos) && (i.U < ll_pos + write_spread_bytes)) {
            val shift_bytes = (i.U - ll_pos)(2, 0)
            val shift_bits = shift_bytes << 3.U
            ll_spread(i) := ll_sv >> shift_bits
          }
        }

        when (ll_s === ll_maxSV1) {
          for (i <- 0 until LL_TABLESIZE) {
            val uPosition = (i.U * ll_fse_tablestep) & ll_tableMask

            when ((i.U >= ll_pos) && (i.U < ll_pos + write_spread_bytes)) {
              val shift_bytes = (i.U - ll_pos)(2, 0)
              val shift_bits = shift_bytes << 3.U
              ll_tableSymbol(uPosition) := ll_sv >> shift_bits
            } .otherwise {
              ll_tableSymbol(uPosition) := ll_spread(i)
            }
          }

          dicBuilderState := sBuildCTableBuildTable
          ll_s := 0.U
        }
      } .otherwise {
        CompressAccelLogger.logInfo(printInfo + " Doesn't support low probability cases")
        assert(false.B, "Doesn't support low probability cases")
      }
    }

    is (sBuildCTableBuildTable) { // 2^tableLogLL cycles
      ll_s := ll_s + 1.U
      val s = ll_tableSymbol(ll_s)
      ll_cumulReg(s) := ll_cumulReg(s) + 1.U
      ll_tableU16(ll_cumulReg(s)) := ll_tableSize + ll_s
      when (ll_s === ll_tableSize - 1.U) {
        ll_s := 0.U
        dicBuilderState := sBuildCTableSymbolTT
      }
    }

    is (sBuildCTableSymbolTT) {
      ll_s := ll_s + 1.U
      when (normCount === 0.U) {
        ll_symbolTTDeltaNbBits(ll_s) := (((tableLogLL + 1) << 16) - (1 << tableLogLL)).U
        ll_symbolTTDeltaFindState(ll_s) := 0.S
      } .elsewhen (normCount === 1.U) { // ignore low-prob (normCount === -1.S) for now
        ll_symbolTTDeltaNbBits(ll_s) := (((tableLogLL) << 16) - (1 << tableLogLL)).U
        ll_symbolTTDeltaFindState(ll_s) := (ll_total - 1.U).asSInt
        ll_total := ll_total + 1.U
      } .otherwise {
        val maxBitsOut = tableLogLL.U - BIT_highbit32(normCount - 1.U)
        val minStatePlus = normCount << maxBitsOut(3, 0)
        ll_symbolTTDeltaNbBits(ll_s) := (maxBitsOut << 16.U) - minStatePlus
        ll_symbolTTDeltaFindState(ll_s) := (ll_total - normCount).asSInt
        ll_total := ll_total + normCount
      }

      when (ll_s === ll_max_symbol_value) {
        ll_s := 0.U
        dicBuilderState := sWriteCTable
      }
    }

    is (sWriteCTable) {
      when (!write_header_started) {
        write_header_started := true.B
        remaining := ll_tableSize + 1.U
        threshold := ll_tableSize
        nbBits := (tableLogLL + 1).U
        bitStream := (tableLogLL - FSE_MIN_TABLELOG).U
        bitCount := 4.U
      } .otherwise {
        when ((symbol < alphabetSize) && (remaining > 1.U)) {
          when(writeBitStream) {
            when (io.header_writes.ready) {
              writeBitStream := false.B
              bitStream := bitStream >> 16.U
              bitCount := bitCount - 16.U

              CompressAccelLogger.logInfo(printInfo + " bitStream(7, 0): %d\n", bitStream(7, 0))
              CompressAccelLogger.logInfo(printInfo + " bitStream(15, 8): %d\n", bitStream(15, 8))
            }

            io.header_writes.valid := true.B
            io.header_writes.bits.data := bitStream
            io.header_writes.bits.validbytes := 2.U
          } .elsewhen (writeBitStreamPrev0) {
            when (io.header_writes.ready) {
              writeBitStreamPrev0 := false.B
              bitStream := bitStream >> 16.U

              CompressAccelLogger.logInfo(printInfo + "writeBitStreamPrev0")
            }

            io.header_writes.valid := true.B
            io.header_writes.bits.data := bitStream
            io.header_writes.bits.validbytes := 2.U
          } .elsewhen (previousIs0) {
            when (!start_initialized) {
              start := symbol
              start_initialized := true.B
              CompressAccelLogger.logInfo(printInfo + " start: %d\n", symbol)
            } .elsewhen (!skip_zeros_done) {
              val cur_norm_count = ll_normalizedCounterReg(symbol)
              when (cur_norm_count =/= 0.U) {
                skip_zeros_done := true.B
                CompressAccelLogger.logInfo(printInfo + " symbol: %d\n", symbol)
              } .otherwise {
                symbol := symbol + 1.U

                when ((symbol + 1.U) === alphabetSize) {
                  assert(false.B, printInfo + " Wrong distribution for FSE compression\n");
                }
              }
            } .elsewhen (!skip_24_done) {
              when (symbol >= start + 24.U) {
                start := start + 24.U
                bitStream := bitStream + (BigInt("FFFF", 16).U << bitCount)
                writeBitStreamPrev0 := true.B
              } .otherwise {
                skip_24_done := true.B
                CompressAccelLogger.logInfo(printInfo + " skip_24_done\n")
              }
              CompressAccelLogger.logInfo(printInfo + " skip_24\n")
            } .elsewhen (!skip_3_done) {
              when (symbol >= start + 3.U) {
                start := start + 3.U
                bitStream := bitStream + (3.U << bitCount)
                bitCount := bitCount + 2.U
              } .otherwise {
                skip_3_done := true.B
                CompressAccelLogger.logInfo(printInfo + " skip_3_done\n")
              }
              CompressAccelLogger.logInfo(printInfo + " skip_3\n")
            } .otherwise {
              bitStream := bitStream + ((symbol - start) << bitCount)
              bitCount := bitCount + 2.U
              previousIs0 := false.B
              start := 0.U
              start_initialized := false.B
              skip_zeros_done := false.B
              skip_24_done := false.B
              skip_3_done := false.B

              when (bitCount > 16.U) {
                writeBitStream := true.B
              }
              CompressAccelLogger.logInfo(printInfo + " previousIs0_done\n")
            }
          } .otherwise {
            val count = ll_normalizedCounterReg(symbol)
            symbol := symbol + 1.U
            val max = ((threshold << 1.U) - 1.U) - remaining
            // fse_compress.c 327 ?????
// remaining := remaining - Mux(count < 0.S, (-1.S)*count, count)
            val nxt_remaining = remaining - count
            remaining := nxt_remaining

            val count1 = count + 1.U
            val count1_max = Mux(count1 >= threshold, count1 + max, count1)
            val nxt_bitCount = bitCount + nbBits - Mux(count1_max < max, 1.U, 0.U)
            bitStream := bitStream + (count1_max << bitCount)
            bitCount := nxt_bitCount
            writeBitStream := nxt_bitCount > 16.U

            previousIs0 := (count1_max === 1.U)
            assert(remaining >= 1.U, "Not enough remaining for FSE header writes\n")
            CompressAccelLogger.logInfo(printInfo + " previousIs0: %d\n", (count1_max === 1.U))
            CompressAccelLogger.logInfo(printInfo + " alphabetSize: %d\n", alphabetSize)
            CompressAccelLogger.logInfo(printInfo + " symbol: %d\n", symbol)
            CompressAccelLogger.logInfo(printInfo + " threshold: %d\n", threshold)
            CompressAccelLogger.logInfo(printInfo + " max: %d\n", max)
            CompressAccelLogger.logInfo(printInfo + " remaining: %d\n", remaining)
            CompressAccelLogger.logInfo(printInfo + " nxt_remaining: %d\n", nxt_remaining)
            CompressAccelLogger.logInfo(printInfo + " count: %d\n", count)
            CompressAccelLogger.logInfo(printInfo + " count1_max: %d\n", count1_max)
            CompressAccelLogger.logInfo(printInfo + " nxt_bitCount: %d\n", nxt_bitCount)
            CompressAccelLogger.logInfo(printInfo + " writeBitStream: %d\n", writeBitStream)
            CompressAccelLogger.logInfo(printInfo + " BitStream: 0x%x\n", (bitStream + (count1_max << bitCount)))

            for (i <- 0 until tableLogLL + 1) {
              shifted_threshold_small_or_eq_remaining(i) := Mux(nxt_remaining < shifted_thresholds(i), 1.U, 0.U)
            }
            threshold := shifted_thresholds(nxt_shifted_threshold_idx)
            nbBits := nbBits - nxt_shifted_threshold_idx
          }
        } .otherwise {
          io.header_writes.valid := true.B
          io.header_writes.bits.data := bitStream
          io.header_writes.bits.validbytes := ((bitCount + 7.U) >> 3.U)

          if (mark_end_of_header) {
            io.header_writes.bits.end_of_message := true.B
          }

          when (io.header_writes.ready) {
            dicBuilderState := sLookup
            bitStream := 0.U
            bitCount := 0.U
          }
        }
      }
    }
 
    is (sLookup) {
      when (print_table) {
        print_table := false.B

// for (i <- 0 until maxSymbolLL + 1) {
// CompressAccelLogger.logInfo(printInfo + " ll_count(%d): %d\n", i.U, ll_count(i))
// }

        CompressAccelLogger.logInfo(printInfo + " ll_max_symbol_value: %d\n", ll_max_symbol_value)

        for (i <- 0 until maxSymbolLL + 1) {
          CompressAccelLogger.logInfo(printInfo + " ll_normalizedCounterReg(%d): %d\n", i.U, ll_normalizedCounterReg(i))
        }

        for (i <- 0 until LL_TABLESIZE) {
          CompressAccelLogger.logInfo(printInfo + " ll_tableSymbol(%d): %d\n", i.U, ll_tableSymbol(i))
        }

        CompressAccelLogger.logInfo(printInfo + " ll_highThresholdAfterCumul: %d\n", ll_highThresholdAfterCumul)

        for (i <- 0 until LL_SPREAD_TABLE_SIZE) {
          CompressAccelLogger.logInfo(printInfo + " ll_spread(%d): %d\n", i.U, ll_spread(i))
        }

        CompressAccelLogger.logInfo(printInfo + " ll_fse_tablestep: %d\n", ll_fse_tablestep)

        for (i <- 0 until maxSymbolLL + 1) {
          CompressAccelLogger.logInfo(printInfo + " symbolTTDeltaNbBits(%d): %d, ll_symbolTTDeltaFindState(%d): %d\n",
            i.U, ll_symbolTTDeltaNbBits(i), i.U, ll_symbolTTDeltaFindState(i).asSInt)
        }

        for (i <- 0 until (1 << tableLogLL)) {
          CompressAccelLogger.logInfo(printInfo + " ll_tableU16(%d): %d\n", i.U, ll_tableU16(i))
        }
      }

      when (io.lookup_done.valid) {
        io.nb_seq.ready := true.B
        dicBuilderState := sIdle
      }
    }
  }
}






// A / B
class PipelinedDivider(w: Int) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val A     = Input(UInt(w.W))
    val B     = Input(UInt(w.W))
    val Q     = Output(UInt(w.W))
    val done  = Output(Bool())
  })
  val w1 = w + 1

  val iter = RegInit(0.U(64.W))
  val P = RegInit(0.U(w1.W))

  val state = RegInit(0.U(2.W))
  val idle = 0.U
  val compute = 1.U
  val done = 2.U

  val R = RegInit(0.U((2*w).W))
  val D = RegInit(0.U(w.W))
  val done_reg = RegInit(false.B)

  val next_R  = Wire(UInt((2*w).W))
  val shift_R = Wire(UInt((2*w).W))
  val shift_R1 = Wire(UInt(w.W))
  val R_D     = Wire(UInt(w.W))

  shift_R := R << 1
  shift_R1 := shift_R(w-1, 0) + 1.U
  R_D := shift_R(2*w-1, w) - D
  when (shift_R(2*w-1, w) < D) {
    next_R := shift_R
  } .otherwise {
    next_R := Cat(R_D, shift_R1)
  }
  io.Q := R(w-1, 0)
  io.done := done_reg

  switch (state) {
    is (idle) {
      done_reg := false.B
      when (io.start) {
        R := io.A
        D := io.B
        iter := 0.U
        state := compute
      }
    }

    is (compute) {
      when (iter < w.U) {
        iter := iter + 1.U
        R := next_R
      } .otherwise {
        state := done
      }
    }

    is (done) {
      done_reg := true.B
      state := idle
    }
  }
}
