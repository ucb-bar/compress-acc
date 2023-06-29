package compressacc

import Chisel._
import chisel3.{Printable, VecInit}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._

// This block does the function of (1) FSE_readNCount (2) ZSTD_buildFSETable
class FSEDTBuilder(l2bw: Int)(implicit p: Parameters) extends Module{
    val io = IO(new Bundle{
        // All inputs are from ZstdDTBuilder
        val input_stream = Input(UInt(l2bw.W)) // from BufferManager.
        val maxSymbolValue = Input(UInt(10.W))
        val start_trigger = Input(Bool())
        val bitsTableValue = Input(UInt(32.W))
        val baseTableValue = Input(UInt(32.W))
        val output_queues_ready = Input(Bool())

        val bits_consumed = Output(UInt(log2Down(l2bw).W)) // to BufferManager.
        val tableIndex = Output(UInt(6.W))
        val dt_entry_num = Output(UInt(64.W))
        val dt_entry_data = Output(UInt(64.W))
        val dt_entry_valid = Output(Bool())
        val dt_isLastEntry = Output(Bool())
        val in_work = Output(Bool())
    })
    val maxSymbolValue = RegInit(0.U(9.W))
    val mSV1 = maxSymbolValue +& 1.U
    
    val LL = 0.U
    val OFF = 1.U
    val ML = 2.U    
    val HUFF = 3.U
    val in_work = RegInit(false.B)
    io.in_work := in_work
    when(io.start_trigger && !in_work){
        in_work := true.B
        maxSymbolValue := io.maxSymbolValue
    }

    // Memories
    val nCount = RegInit(VecInit(Seq.fill(53)(0.S(16.W)))) //reg to save NCount table. 106B.
    val nCount_isMinus1 = RegInit(VecInit(Seq.fill(53)(false.B))) //53b.
    val baseValue = RegInit(VecInit(Seq.fill(512)(0.U(6.W)))) // 384B

    // Variables for FSE_readNCount
    val first_readNCount = RegInit(true.B)
    val done_readNCount = RegInit(false.B)
    val tableLog = RegInit(0.U(4.W))
    val bitCount = RegInit(0.U(32.W)) //bits consumed from the input stream
    //val bitCount_wire = Wire(UInt(32.W))
    val nbBits = RegInit(0.U(32.W)) //is likely to be bitCount in the general case
    //val nbBits_wire = Wire(UInt(32.W))
    val remaining_counts = RegInit(0.S(32.W)) //counts to distribute to the NormalizedCount table
    //val remaining_counts_wire = Wire(SInt(32.W))
    val threshold = RegInit(0.U(32.W)) //standard for low count. Scale down with the decrease of remaining_counts
    //val threshold_wire = Wire(UInt(32.W))
    val max_sint = (2.S*threshold.asSInt - 1.S - remaining_counts) //compare bitstream -> calculate number of bits to read
    val max = max_sint.asUInt
    val previous0 = RegInit(false.B)
    //val count_wire = Wire(SInt(17.W))
    val highest3 = Wire(UInt(3.W)) //found 2^highest3 11s in the previous0 case
    val bits_consumed_previous0 = Wire(UInt(log2Down(l2bw).W))
    when(!previous0){bits_consumed_previous0 := 0.U}
    val symbols_consumed_previous0 = Wire(UInt(5.W))
    when(!previous0){symbols_consumed_previous0 := 0.U}

    // Variables for FSE_buildDTable
    val dt_fsm_state = RegInit(0.U(2.W))
    val tableSize = 1.U << tableLog
    val dtheader_tableLog = Wire(UInt(32.W))
    val dtheader_fastMode = Wire(UInt(32.W))
    val dtentry = Wire(UInt(64.W))
    val dtentry_nextState = Wire(UInt(16.W))
    val dtentry_nbAdditionalBits = Wire(UInt(8.W))
    val dtentry_nbBits = Wire(UInt(8.W))
    val dtentry_baseValue = Wire(UInt(32.W))
    dtentry := Cat(dtentry_baseValue, dtentry_nbBits, dtentry_nbAdditionalBits, dtentry_nextState)
    val s = RegInit(0.U(8.W)) //Loop variable for dt_fsm_state 1
    val highThreshold = RegInit(0.U(10.W))
        // Loop variables for FSE_buildDTable state 2
    val i = RegInit(0.U(10.W))
    val nCount_accumulate = RegInit(0.S(11.W))

    val symbol = RegInit(0.U(9.W))
    val j = RegInit(0.U(10.W))
    val position = RegInit(0.U(10.W))
    val step = (tableSize >> 3.U) + (tableSize >> 1.U) + 3.U
    val tableMask = tableSize - 1.U
    
    io.dt_isLastEntry := (!first_readNCount) && done_readNCount && dt_fsm_state===3.U && i===(highThreshold)

    // FSE_readNCount //
    // Goal: get the NCount table from the bitstream - stored in variable"nCount".
    when(in_work){
    when(first_readNCount){
        // Only for the start of NCount: handle the first 4 bits
        val first4bits = io.input_stream(3,0)
        val tableLog_wire = first4bits +& 5.U
        val remaining_counts_wire_temp = (1.U<<tableLog_wire) + 1.U
        val threshold_wire_temp = 1.U<<tableLog_wire
        val max_wire = (1.U<<tableLog_wire) - 2.U //Use max instead of this for "otherwise"
        val nbBits_wire_temp = tableLog_wire + 1.U
        // Common Logic - unconditionally executed
        val bitmask_default = (1.U<<tableLog_wire) - 1.U
        val bitmask_2 = (1.U<<(tableLog_wire+1.U)) - 1.U
        val current_bits_first = (io.input_stream>>4.U) & bitmask_default
        val current_bits_2_first = (io.input_stream>>4.U) & bitmask_2
        val count_wire = Mux(current_bits_first < max_wire, current_bits_first, current_bits_2_first).asSInt - 1.S //SInt
        nCount(symbol) := count_wire
        symbol := symbol + 1.U
        val bitCount_wire = Mux(current_bits_first < max_wire, nbBits_wire_temp - 1.U, nbBits_wire_temp)
        previous0 := (count_wire === 0.S)
        val remaining_counts_wire = Mux(count_wire > 0.S, remaining_counts_wire_temp.asSInt - count_wire, remaining_counts_wire_temp.asSInt + count_wire) //SInt
        when(remaining_counts_wire > 1.S && remaining_counts_wire.asUInt < threshold_wire_temp){
            nbBits := Log2(remaining_counts_wire.asUInt)+1.U
            threshold := 1.U<<Log2(remaining_counts_wire.asUInt)
        }.otherwise{
            nbBits := nbBits_wire_temp
            threshold := threshold_wire_temp	
        }
        
        // Common Logic - data stream and bitcount handling
        /* Just keep fetching new input stream from the memloader or a buffer
        ** Decouple reading input stream and writing NCount output
        */
        bitCount := 4.U + bitCount_wire
        io.bits_consumed := 4.U + bitCount_wire
        
        // Common Logic - End
        // Finish readNCount when remaining <= 1 or symbol > maxSymbolValue
        when(remaining_counts_wire <= 1.S || symbol > maxSymbolValue){
            done_readNCount := true.B
            maxSymbolValue := symbol - 1.U
        }

        // first_readNCount only: initialize register values
        first_readNCount := false.B
        tableLog := tableLog_wire
        remaining_counts := remaining_counts_wire
    }.elsewhen(!done_readNCount){ //!first_readNCount && !done_readNCount
        // Common Logic - End
        // Finish readNCount when remaining <= 1 or symbol > maxSymbolValue
        when(remaining_counts <= 1.S || symbol > maxSymbolValue){
            done_readNCount := true.B
            maxSymbolValue := symbol - 1.U
            io.bits_consumed := 0.U
            symbol := 0.U
        }.otherwise{ //Loop: remaining_counts_wire > 1.S && symbol <= maxSymbolValue
            when(previous0){
                /* Count the number of 11(2)s =: 2^(highest3-1)
                ** bits consumed from the input stream = 2^highest3 bits
                ** symbols to skip = 3*2^(highest3-1)
                ** If nCount is stored in L2 and L2 bandwidth is 16B or 32B,
                ** so we can skip 8 or 16 symbols at maximum.
                ** Therefore, for i<-1 to 2 is enough if l2bw=128bits
                ** and 1 to 3 is enough if l2bw=256bits.
                */
                highest3 := 0.U
                for(regIndex <- 1 to 5){ //
                    val powerof_regIndex = 1.U << (1.U<<(regIndex.U))
                    val mask_regIndex = powerof_regIndex - 1.U
                    when((io.input_stream & mask_regIndex) === mask_regIndex){highest3 := regIndex.U}
                }
                bits_consumed_previous0 := Mux(highest3 =/= 0.U, 1.U << (highest3), 2.U)
                symbols_consumed_previous0 := Mux(highest3 =/= 0.U, 3.U*(1.U << (highest3-1.U)), io.input_stream(1,0))
                //write "3*2^(highest3-1)" number of zeros to nCount: nCount is already initialized to 0.S
                when(highest3 =/= 0.U){
                    symbol := symbol + symbols_consumed_previous0
                    bitCount := bitCount + bits_consumed_previous0
	                io.bits_consumed := bits_consumed_previous0
                    previous0 := true.B
                }
            }
            when(!previous0 || (previous0 && highest3===0.U)){
                // Common Logic
                val current_bits = (io.input_stream>>bits_consumed_previous0) & (threshold-1.U)
                val current_bits_2 = (io.input_stream>>bits_consumed_previous0) & (2.U*threshold-1.U)
                
                val count = Mux(current_bits < max, current_bits, 
                            Mux(current_bits_2 >= threshold, current_bits_2-max, current_bits_2)).asSInt - 1.S //SInt
                nCount(symbol+symbols_consumed_previous0) := count
                symbol := symbol + symbols_consumed_previous0 + 1.U
                val bitCount_notPrevious0 = Mux(current_bits < max, nbBits - 1.U, nbBits)
                previous0 := (count === 0.S)
                val remaining_counts_nowire = Mux(count > 0.S, remaining_counts.asSInt - count, remaining_counts.asSInt + count)
                when(remaining_counts_nowire > 1.S && remaining_counts_nowire.asUInt < threshold){
                    nbBits := Log2(remaining_counts_nowire.asUInt)+1.U
                    threshold := 1.U<<Log2(remaining_counts_nowire.asUInt)
                }
                remaining_counts := remaining_counts_nowire
                // Common Logic - data stream and bitcount handling
                /* Just keep fetching new input stream from the memloader or a buffer
                ** Decouple reading input stream and writing NCount output to some address
                */
                bitCount := bitCount + bits_consumed_previous0 + bitCount_notPrevious0
                io.bits_consumed := bits_consumed_previous0 + bitCount_notPrevious0
            }
        }
    }.otherwise{ //done_readNCount
    // FSE_buildDTable //
    // Don't need any input stream. NCount, tableLog, mSV is enough.
    // Have to access BitsTable and BaseTable in ZstdDTBuilder.
        /* DT FSM State 0: Write DT header. (1 cycles)
        ** DT FSM State 1: Write a DT entry where NCount[symbol]==-1. (lookup NCount: mSV+1 cycles)
        ** DT FSM State 2: Put values into baseValue, which is an intermediate data structure needed for DT FSM State 3.
        **                  (tableSize cycles)
        ** DT FSM State 3: Write a DT entry. (loop of tableSize: tableSize cycles)
        */
        switch(dt_fsm_state){
            is(0.U){
                // Write DT Header
                val largeLimit = 1.U << (tableLog-1.U)
                dtheader_fastMode := 1.U
                for(i <- 0 to 52){
                    when(nCount(i.U) > largeLimit.asSInt){dtheader_fastMode := 0.U}
                    when(nCount(i.U) === -1.S){nCount_isMinus1(i.U) := true.B}
                }
                dtheader_tableLog := tableLog
                io.dt_entry_data := Cat(dtheader_tableLog, dtheader_fastMode)
                io.dt_entry_num := 0.U
                when(io.output_queues_ready){
                    highThreshold := tableSize - 1.U
                    dt_fsm_state := 1.U
                }                
            }
            is(1.U){ // Write DT entries of highThreshold region
                //loop: symbol=0~mSV
                when(s <= maxSymbolValue){
                    when(nCount_isMinus1(s)){
                        io.tableIndex := s
                        dtentry_baseValue := io.baseTableValue
                        dtentry_nbAdditionalBits := io.bitsTableValue
                        dtentry_nbBits := tableLog
                        dtentry_nextState := 0.U
                        io.dt_entry_data := dtentry
                        io.dt_entry_num := highThreshold + 1.U//+1 because of the header
                        when(io.output_queues_ready){
                            highThreshold := highThreshold - 1.U
                            s := s + 1.U
                        }
                    }.otherwise{
                        s := s+1.U
                    }
                }.otherwise{
                    s := 0.U
                    dt_fsm_state := 2.U
                    nCount_accumulate := nCount(0)
                    symbol := 0.U
                    position := 0.U
                }
            }
            is(2.U){
                // build basevalue table : will take tableSize cycles
                // can do faster with O(N) highThreshold region numstep sorter
                // +512*10b numsteps(inverse fn of position), +53*10b NCount_acc, +53*10b NCount_highT
                // -512*6b baseValue --> 388.5B
                // |highThreshold region| cycles for sorting
                // |highThreshold region| cycles for filling NCount_highT by comparing numsteps and NCount_acc+NCount_highT
                when(symbol <= maxSymbolValue){
                    when(nCount(symbol)<1.S){
                        symbol := symbol + 1.U
                    }.otherwise{
                        when(position > highThreshold){
                            position := (position+step) & tableMask
                        }.otherwise{
                            baseValue(position) := symbol
                            when(j===(nCount(symbol).asUInt-1.U)){
                                j := 0.U
                                symbol := symbol + 1.U
                            }.otherwise{
                                j := j+1.U
                                // symbol := symbol + 0.U
                            }
                            position := (position+step) & tableMask
                        }
                        i := i+1.U
                    }
                }.otherwise{
                    symbol := 0.U
                    i := 0.U
                    j := 0.U
                    position := 0.U
                    dt_fsm_state := 3.U
                }
            }
            is(3.U){
                when(i<=highThreshold){
                    io.tableIndex := baseValue(i)
                    dtentry_baseValue := io.baseTableValue
                    dtentry_nbAdditionalBits := io.bitsTableValue
                    val dt_nextState = nCount(baseValue(i)).asUInt
                    when(io.output_queues_ready){ //Share nCount as symbolNext
                        nCount(baseValue(i)) := nCount(baseValue(i)) + 1.S
                    }
                    val dt_nbBits = tableLog - Log2(dt_nextState)
                    dtentry_nbBits := dt_nbBits
                    dtentry_nextState := (dt_nextState << dt_nbBits) - tableSize
                    io.dt_entry_data := dtentry
                    io.dt_entry_num := i + 1.U //+1 because of the header
                    when(io.output_queues_ready){i := i+1.U}
                }
                when(i===highThreshold && io.output_queues_ready){//finished. re-initialize all reg values
                    in_work := false.B
                    i := 0.U
                    j := 0.U
                    symbol := 0.U
                    for(i<-0 to 52){
                        nCount(i.U) := 0.S
                        nCount_isMinus1(i.U) := false.B
                    }
                    first_readNCount := true.B
                    done_readNCount := false.B
                    tableLog := 0.U
                    bitCount := 0.U
                    nbBits := 0.U
                    remaining_counts := 0.S
                    threshold := 0.U
                    previous0 := false.B
                    dt_fsm_state := 0.U
                }
            }
        }
    }
    }
    // Prevent floating wires
    when(!in_work){io.bits_consumed := 0.U}
    when(!(!first_readNCount && !done_readNCount && !(remaining_counts <= 1.S || symbol > maxSymbolValue))){
        //bitmask_default := 0.U
        //bitmask_2 := 0.U
        //count_wire := 0.S
        //bitCount_wire := 0.U
    }
    when(!first_readNCount && !done_readNCount && !previous0){
        highest3 := 0.U
    }
    when(!(done_readNCount && dt_fsm_state===0.U)){
        dtheader_tableLog := 0.U
        dtheader_fastMode := 0.U
    }
    when(!(done_readNCount && dt_fsm_state>=1.U)){
        dtentry_baseValue := 0.U
        dtentry_nbAdditionalBits := 0.U
        dtentry_nbBits := 0.U
        dtentry_nextState := 0.U
    }
    when(in_work && !first_readNCount && done_readNCount){
        when(dt_fsm_state===0.U){io.dt_entry_valid := true.B}
        .elsewhen(dt_fsm_state===1.U && nCount_isMinus1(s)){io.dt_entry_valid := true.B}
        .elsewhen(dt_fsm_state===3.U && i<=highThreshold){io.dt_entry_valid := true.B}
        .otherwise{io.dt_entry_valid := false.B}
    }.otherwise{io.dt_entry_valid := false.B}
}
