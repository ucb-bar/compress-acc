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
import chisel3.dontTouch
/*
class MemLoaderConsumerBundle extends Bundle {
  val user_consumed_bytes = UInt(INPUT, log2Up(16+1).W)
  val available_output_bytes = UInt(OUTPUT, log2Up(16+1).W)
  val output_valid = Bool(OUTPUT)
  val output_ready = Bool(INPUT)
  val output_data = UInt(OUTPUT, (16*8).W)
  val output_last_chunk = Bool(OUTPUT)
}
class DTEntryChunk extends Bundle{
  val chunk_data = UInt(OUTPUT, 128.W)
  val chunk_size_bytes = UInt(OUTPUT, 8,W)
  val tableType = UInt(2.W) //0: LL, 1: Off, 2: ML
  val is_final_entry = Bool() //Indicates the final entry of the last DT(probably ML DT)
}
class DTAddressBundle extends Bundle{
    val ll_dt_addr_0 = UInt(64.W)
    val off_dt_addr_0 = UInt(64.W)
    val ml_dt_addr_0 = UInt(64.W)
    val ll_dt_addr_1 = UInt(64.W)
    val off_dt_addr_1 = UInt(64.W)
    val ml_dt_addr_1 = UInt(64.W)
}
*/
class DTEntryChunk32 extends Bundle{
  val chunk_data = UInt(OUTPUT, 256.W)
  val chunk_size_bytes = UInt(OUTPUT, 8.W)
  val tableType = UInt(2.W) //0: LL, 1: Off, 2: ML
  val is_final_entry = Bool() //Indicates the final entry of the last DT(probably ML DT)
}

class SnappyDecompressSrcInfo extends Bundle{
  val ip = UInt(64.W)
  val isize = UInt(64.W) //bytes, not bits!
}

class TableSrcInfo extends Bundle{
    val addr = UInt(64.W)
    //val size = UInt(64.W)
    val isPredefined = Bool()
    val parity = UInt(1.W)
}
class ZstdDTEntryWriteSRAM extends Bundle{
    val dt_type = UInt(2.W) //0: LL, 1: OFF, 2: ML
    val dt_parity = UInt(1.W) //0: select table 0, 1: select 1
    val dt_entry_num = UInt(10.W) //SRAM address
    val dt_entry_content = UInt(64.W) //SRAM entry
    val dt_write = Bool() // Write or don't write
}

class ZstdDTBuilder(l2bw: Int)(implicit p: Parameters) extends Module{
    val io = IO(new Bundle{
        // From block decoder or Huffman unit
        val trigger = Input(Bool())
        val dt_addr = Input(new DTAddressBundle)
        val bitstream_start = Input(UInt(64.W))
        val bitstream_end = Input(UInt(64.W))
        val literal_start = Input(UInt(64.W))
        val output_file_pointer = Input(UInt(64.W))
        // To block decoder or Huffman unit
        val trigger_ready = Output(Bool())

        val input_stream = (new MemLoaderConsumerBundle).flip //from memloader
        // val bufs_completed = Input(UInt(64.W)) //from ZstdDTBuilderWriter
        // val no_writes_inflight = Input(Bool()) //from ZstdDTBuilderWriter
        val table_in_use = Input(Bool()) //from DTReader
        val completion_dtreader = Input(Bool()) //from DTReader
        val completion_seqexec = Input(Bool()) //from SeqExecControl

        val input_src_info = Decoupled(new SnappyDecompressSrcInfo) //to memloader
        val ll_dt_info = Output(new TableSrcInfo) //to DTReader
        val ml_dt_info = Output(new TableSrcInfo) //to DTReader
        val off_dt_info = Output(new TableSrcInfo) //to DTReader
        val dt_info_valid = Output(Bool()) //to DTReader
        val next_bitstream_start = Output(UInt(64.W)) //to DTReader
        val next_bitstream_end = Output(UInt(64.W)) //to DTReader.
        val num_sequences = Output(UInt(64.W)) //to DTReader
        val literal_start_addr_out = Output(UInt(64.W)) //to DTReader-->to SeqExecutor
        val output_start_addr_out = Output(UInt(64.W)) //to DTReader-->to SeqExecutor
        // val dt_entry = Decoupled(new DTEntryChunk32) //to ZstdDTBuilderWriter
        // val dt_dest_info = Decoupled(new SnappyDecompressDestInfo) //to ZstdDTBuilderWriter
        val zero_num_sequences = Output(Bool()) // to BlockDecoder or FrameDecoder.
        val completion = Output(Bool())

        val dt_entry_sram = Output(new ZstdDTEntryWriteSRAM) //to DT SRAMs
    })

    dontTouch(io)

    val trigger = Wire(Bool())
    trigger := io.trigger
    val boolptr = RegInit(0.U(64.W))//for rocc_in test

    io.dt_info_valid := false.B // Will be turned on to true.B in state 2, after building DT.
    // Global variables
        // Address of the FSE distribution table: should know this
    val predefined_addr = RegInit(VecInit(Seq.fill(3)(0.U(64.W))))
    val dt_parity = RegInit(VecInit(Seq.fill(3)(0.U(1.W)))) //Real Parity
        // These four variables are not needed. They are from design that uses L2 cache for DT storage.
    val dt_addr0 = RegInit(VecInit(Seq.fill(3)(0.U(64.W))))
    val dt_addr1 = RegInit(VecInit(Seq.fill(3)(0.U(64.W))))
    val block_count = RegInit(0.U(64.W)) //use block_count(0) to pick dt_addr.
    val block_count_DTReader = RegInit(1.U(1.W)) // parity of the block number being executed in DTReader
        // Metadata
    val sequence_section_size = RegInit(0.U(64.W))
    val ip_start = RegInit(0.U(64.W))
    val bitstream_end_addr = RegInit(0.U(64.W))
    io.next_bitstream_end := bitstream_end_addr;

    val bits_consumed_from_bitstream = RegInit(0.U(64.W))
    dontTouch(bits_consumed_from_bitstream)
    io.next_bitstream_start := ip_start + (bits_consumed_from_bitstream >> 3.U)

    // Total number of sequences to generate
    val num_sequences = RegInit(0.U(64.W))
    io.num_sequences := num_sequences
    dontTouch(num_sequences)

    val literal_start_addr = RegInit(0.U(64.W))
    io.literal_start_addr_out := literal_start_addr

    val output_start_addr = RegInit(0.U(64.W))
    io.output_start_addr_out := output_start_addr

    val llMode = RegInit(0.U(2.W))
    val offMode = RegInit(0.U(2.W))
    val mlMode = RegInit(0.U(2.W))

    val state = RegInit(0.U(2.W))
    val building_order = RegInit(0.U(2.W)) //currently building DT for 0: LL, 1: Offset, 2: ML
    val mode = Wire(UInt(2.W))

    when (building_order === 0.U) {
      mode := llMode
    } .elsewhen(building_order === 1.U) {
      mode := offMode
    } .elsewhen (building_order === 2.U) {
      mode := mlMode
    } .otherwise {
      mode := 0.U/*dummy*/
    }
    
    // Memories (ROM)
    // BaseTable: used for DT.baseValue. 32b * (35/28/52) = 460B
    val llBaseTable = VecInit(0.U(32.W), 1.U(32.W), 2.U(32.W), 3.U(32.W), 4.U(32.W), 
      5.U(32.W), 6.U(32.W), 7.U(32.W), 8.U(32.W), 9.U(32.W), 10.U(32.W), 11.U(32.W), 
      12.U(32.W), 13.U(32.W), 14.U(32.W), 15.U(32.W), 16.U(32.W), 18.U(32.W), 20.U(32.W), 
      22.U(32.W), 24.U(32.W), 28.U(32.W), 32.U(32.W), 40.U(32.W), 48.U(32.W), 64.U(32.W), 
      "h80".U(32.W), "h100".U(32.W), "h200".U(32.W), "h400".U(32.W), "h800".U(32.W), 
      "h1000".U(32.W), "h2000".U(32.W), "h4000".U(32.W), "h8000".U(32.W), "h10000".U(32.W))

    val ofBaseTable = VecInit(0.U(32.W), 1.U(32.W), 1.U(32.W), 5.U(32.W), 
      "hd".U(32.W), "h1d".U(32.W), "h3d".U(32.W), "h7d".U(32.W), "hfd".U(32.W), 
      "h1fd".U(32.W), "h3fd".U(32.W), "h7fd".U(32.W), "hffd".U(32.W), "h1ffd".U(32.W), 
      "h3ffd".U(32.W), "h7ffd".U(32.W), "hfffd".U(32.W), "h1fffd".U(32.W), "h3fffd".U(32.W), 
      "h7fffd".U(32.W), "hffffd".U(32.W), "h1ffffd".U(32.W), "h3ffffd".U(32.W), 
      "h7ffffd".U(32.W), "hfffffd".U(32.W), "h1fffffd".U(32.W), "h3fffffd".U(32.W), 
      "h7fffffd".U(32.W), "hffffffd".U(32.W), "h1ffffffd".U(32.W), "h3ffffffd".U(32.W), 
      "h7ffffffd".U(32.W))

    val mlBaseTable = VecInit(3.U(32.W), 4.U(32.W), 5.U(32.W), 6.U(32.W), 
      7.U(32.W), 8.U(32.W), 9.U(32.W), 10.U(32.W), 11.U(32.W), 12.U(32.W), 
      13.U(32.W), 14.U(32.W), 15.U(32.W), 16.U(32.W), 17.U(32.W), 18.U(32.W), 
      19.U(32.W), 20.U(32.W), 21.U(32.W), 22.U(32.W), 23.U(32.W), 24.U(32.W), 
      25.U(32.W), 26.U(32.W), 27.U(32.W), 28.U(32.W), 29.U(32.W), 30.U(32.W), 
      31.U(32.W), 32.U(32.W), 33.U(32.W), 34.U(32.W), 35.U(32.W), 37.U(32.W), 
      39.U(32.W), 41.U(32.W), 43.U(32.W), 47.U(32.W), 51.U(32.W), 59.U(32.W), 
      67.U(32.W), 83.U(32.W), 99.U(32.W), "h83".U(32.W), "h103".U(32.W), "h203".U(32.W), 
      "h403".U(32.W), "h803".U(32.W), "h1003".U(32.W), "h2003".U(32.W), "h4003".U(32.W), 
      "h8003".U(32.W), "h10003".U(32.W))

    // BitsTable: used for DT.nbAdditionalBits. 5b * (35/52) = 54.375B
    val llBitsTable = VecInit(0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 
      0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 
      0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 1.U(5.W), 1.U(5.W), 1.U(5.W), 
      1.U(5.W), 2.U(5.W), 2.U(5.W), 3.U(5.W), 3.U(5.W), 4.U(5.W), 6.U(5.W), 
      7.U(5.W), 8.U(5.W), 9.U(5.W), 10.U(5.W), 11.U(5.W), 12.U(5.W), 13.U(5.W), 
      14.U(5.W), 15.U(5.W), 16.U(5.W))

    // val ofBitsTable: omitted b/c ofBitsTable(i)=i
    val mlBitsTable = VecInit(0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 
      0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 
      0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 
      0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 
      0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 0.U(5.W), 1.U(5.W), 
      1.U(5.W), 1.U(5.W), 1.U(5.W), 2.U(5.W), 2.U(5.W), 3.U(5.W), 3.U(5.W), 
      4.U(5.W), 4.U(5.W), 5.U(5.W), 7.U(5.W), 8.U(5.W), 9.U(5.W), 10.U(5.W), 
      11.U(5.W), 12.U(5.W), 13.U(5.W), 14.U(5.W), 15.U(5.W), 16.U(5.W))

    // Queues
    /* (1) input_src_info_queue:    address and size to request input stream to memloader
    **                              In DTBuilder, unconditionally request l2bw bits infinitely.
    ** (1-2) input_src_info_nmb_queue: queue for counting inflight requests
    ** (2) input_stream_queue:      receiving input stream from memloader
    */
    // input_src_info_queue
    val input_src_info_nmb_enq_count = RegInit(0.U(32.W))
    val input_src_info_inside_count = RegInit(0.U(3.W))
    val input_src_info_queue_flush = state===3.U
    val input_src_info_queue = Module(new Queue(new SnappyDecompressSrcInfo, 4, false, false, input_src_info_queue_flush)) //request data to memloader
    val input_src_info_nmb_queue = Module(new Queue(new SnappyDecompressSrcInfo, 4))
    io.input_src_info <> input_src_info_nmb_queue.io.deq
    input_src_info_nmb_queue.io.enq <> input_src_info_queue.io.deq
    // input_stream_queue
    val input_stream_requests_inflight = RegInit(0.U(3.W))
    val input_stream_enq_count = RegInit(0.U(32.W))
    val input_stream_queue_flush = state===3.U
    val input_stream_queue = Module(new Queue(UInt(l2bw.W), 4, false, false, input_stream_queue_flush)) //receive data from memloader
    // Queue-related logics
    val input_src_info_enq_fire = input_src_info_queue.io.enq.ready && input_src_info_queue.io.enq.valid
    val input_src_info_deq_fire = input_src_info_queue.io.deq.ready && input_src_info_queue.io.deq.valid
    val input_src_info_nmb_enq_fire = input_src_info_nmb_queue.io.enq.ready && input_src_info_nmb_queue.io.enq.valid
    when(input_src_info_enq_fire && !input_src_info_deq_fire){
        input_src_info_inside_count := input_src_info_inside_count + 1.U
    }.elsewhen(!input_src_info_enq_fire && input_src_info_deq_fire){
        input_src_info_inside_count := input_src_info_inside_count - 1.U
    }
    when(input_src_info_nmb_enq_fire){
        input_src_info_nmb_enq_count := input_src_info_nmb_enq_count + 1.U
    }
    val input_stream_queue_enq_fire = input_stream_queue.io.enq.ready && input_stream_queue.io.enq.valid
    val input_stream_queue_deq_fire = input_stream_queue.io.deq.ready && input_stream_queue.io.deq.valid
    when(input_src_info_enq_fire && !input_stream_queue_deq_fire){
        input_stream_requests_inflight := input_stream_requests_inflight + 1.U
    }.elsewhen(!input_src_info_enq_fire && input_stream_queue_deq_fire){
        input_stream_requests_inflight := input_stream_requests_inflight - 1.U
    }
    when(input_stream_queue_enq_fire ||
        state===3.U && input_stream_queue.io.enq.valid){
        input_stream_enq_count := input_stream_enq_count + 1.U
    }

    val bitstream_requests_so_far = RegInit(0.U(16.W))
    when(input_src_info_queue.io.enq.ready && input_src_info_queue.io.enq.valid){
        bitstream_requests_so_far := bitstream_requests_so_far + 1.U
    }
    
    input_src_info_queue.io.enq.valid := (input_stream_requests_inflight < 4.U) && 
        (state===1.U || state===2.U)
    input_src_info_queue.io.enq.bits.ip := ip_start + bitstream_requests_so_far*(l2bw/8).U
    input_src_info_queue.io.enq.bits.isize := (l2bw/8).U

    input_stream_queue.io.enq.bits := io.input_stream.output_data
    input_stream_queue.io.enq.valid := io.input_stream.output_valid && io.input_stream.output_ready &&
        ((l2bw/8).U <= io.input_stream.available_output_bytes)
    io.input_stream.output_ready := Mux(state===3.U, true.B,
        input_stream_queue.io.enq.ready &&
        (l2bw/8).U =/= 0.U && ((l2bw/8).U <= io.input_stream.available_output_bytes)
    )
    io.input_stream.user_consumed_bytes := (l2bw/8).U
    //Connect both queue's deq to ZstdDTBuilderWriter

    // Subblocks
    /* (1) BufferManager: provides the most recent (l2bw) bits of the input stream by handling buffers
    ** (2) FSEDTBuilder: builds the FSE decode table
    */
        //BufferManager
    val bufferManager = Module(new BufferManager(l2bw))
    bufferManager.io.input_stream_deq <> input_stream_queue.io.deq
    bufferManager.io.flush := state===3.U
    val recent_stream = Wire(UInt(l2bw.W))
    recent_stream := bufferManager.io.most_recent_stream
    val first_stream_received = Wire(Bool())
    first_stream_received := bufferManager.io.first_stream_received
        // FSEDTbuilder
    val fsedtbuilder = Module(new FSEDTBuilder(l2bw))

    // State-specific variables
        // "Sequence section header decoding"-related variables
    val requested_l2bw = RegInit(false.B)
        // "DT building"-related variables
    val PREDEFINED = UInt(0)
    val RLE = UInt(1)
    val FSE = UInt(2)
    val REPEAT = UInt(3)
    val dt_start_address = Wire(UInt(64.W))
    dt_start_address := Mux(block_count(0)===0.U, dt_addr0(building_order), dt_addr1(building_order))
    val dt_build_start_trigger = RegInit(true.B)
    when(state===2.U && mode===FSE && building_order <= 1.U && fsedtbuilder.io.dt_isLastEntry){
        dt_build_start_trigger := true.B
    }.elsewhen(state===2.U && mode===FSE && fsedtbuilder.io.in_work){
        dt_build_start_trigger := false.B
    }.otherwise{}

        // Assume building order=0,1,2
    val rle_entryCount = RegInit(0.U(1.W))
    val rle_tableLog = Wire(UInt(32.W))
    val rle_fastMode = Wire(UInt(32.W))
    val rle_baseValue = Wire(UInt(32.W))
    val rle_nbBits = Wire(UInt(8.W))
    val rle_nbAdditionalBits = Wire(UInt(8.W))
    val rle_nextState = Wire(UInt(16.W))

    io.dt_entry_sram.dt_parity := dt_parity(building_order)
    io.dt_entry_sram.dt_type := building_order
    io.dt_entry_sram.dt_write := state===2.U && building_order <= 2.U && 
        (mode===RLE || (mode===FSE && fsedtbuilder.io.dt_entry_valid))

    // Logic & FSM
    /* State 0: Wait for a valid input stream address from the Huffman unit.
    ** State 1: If the first part of the input stream is received,
                do sequence section decoding with the first 4 bytes.
    ** State 2: Generate Decode Table(DT) depending on the compression mode, each for LL, Offset, ML. 
    ****    FSE State 0: Read normalized count(NCount)
    ****    FSE State 1: Build decode table
    ****    Reading decode table should be implemented in a separate block.
    */
    switch(state){
        // Start executing if this module receives a valid input address from the Huffman unit
        is(0.U){
            when(trigger && !(block_count(0)===block_count_DTReader && io.table_in_use)){
                state := 1.U
                dt_addr0(0) := io.dt_addr.ll_dt_addr_0
                dt_addr0(1) := io.dt_addr.off_dt_addr_0
                dt_addr0(2) := io.dt_addr.ml_dt_addr_0
                dt_addr1(0) := io.dt_addr.ll_dt_addr_1
                dt_addr1(1) := io.dt_addr.off_dt_addr_1
                dt_addr1(2) := io.dt_addr.ml_dt_addr_1
                ip_start := io.bitstream_start
                bitstream_end_addr := io.bitstream_end
                literal_start_addr := io.literal_start
                output_start_addr := io.output_file_pointer
            }
        }
        /* Decode the sequence section header.
        ** The first 1 to 3 bytes are num_sequences.
        ** The next 1 byte is reserved, ML mode, Offset mode, LL mode.
        */
        is(1.U){
            when(first_stream_received){
                val byte0 = recent_stream(7, 0)
                val byte1 = recent_stream(15, 8)
                val byte2 = recent_stream(23, 16)
                when(byte0 === 0.U){
                    num_sequences := 0.U
                    bufferManager.io.bits_consumed := 8.U
                    bits_consumed_from_bitstream := bits_consumed_from_bitstream + 8.U
                    // No sequence to be made. Don't make DT and end.
                    state := 3.U
                }.elsewhen(byte0 < 128.U){
                    num_sequences := byte0
                    llMode := recent_stream(8+7, 8+6)
                    offMode := recent_stream(8+5, 8+4)
                    mlMode := recent_stream(8+3, 8+2)
                    bufferManager.io.bits_consumed := 16.U
                    bits_consumed_from_bitstream := bits_consumed_from_bitstream + 16.U
                    state := 2.U
                }.elsewhen(byte0 < 255.U){
                    num_sequences := ((byte0-128.U)<<8.U) + byte1
                    llMode := recent_stream(16+7, 16+6)
                    offMode := recent_stream(16+5, 16+4)
                    mlMode := recent_stream(16+3, 16+2)
                    bufferManager.io.bits_consumed := 24.U
                    bits_consumed_from_bitstream := bits_consumed_from_bitstream + 24.U
                    state := 2.U
                }.otherwise{//byte0 === 255.U
                    num_sequences := recent_stream(23,8) + "h7f00".U
                    llMode := recent_stream(24+7, 24+6)
                    offMode := recent_stream(24+5, 24+4)
                    mlMode := recent_stream(24+3, 24+2)
                    bufferManager.io.bits_consumed := 32.U
                    bits_consumed_from_bitstream := bits_consumed_from_bitstream + 32.U
                    state := 2.U
                }             
            }
        }
        /* Generate DT depending on the compression mode.
        */
        is(2.U){
            switch(mode){
                is(PREDEFINED){
                    building_order := Mux(building_order===2.U, 0.U, building_order + 1.U)
                    state := Mux(building_order===2.U, 3.U, 2.U)
                }
                is(RLE){
                    when(building_order <= 2.U){
                        when(rle_entryCount===0.U){
                            // DT Header
                            rle_tableLog := 0.U
                            rle_fastMode := 0.U
                            io.dt_entry_sram.dt_entry_num := 0.U
                            io.dt_entry_sram.dt_entry_content := Cat(rle_tableLog, rle_fastMode)
                            bufferManager.io.bits_consumed := 0.U
                            rle_entryCount := 1.U
                        }.otherwise{ //rle_entryCount===1.U
                            // DT Entry (Only 1 entry)
                            val symbol = recent_stream(7, 0) // 32b in SW but 8b is enough
                            rle_baseValue := Mux(building_order===0.U, llBaseTable(symbol), 
                                          Mux(building_order===1.U, ofBaseTable(symbol), 
                                            Mux(building_order===2.U, mlBaseTable(symbol), 
                                              0.U/*dummy*/))) /*baseTable[symbol] 32b*/ 
                            rle_nbBits := 0.U
                            rle_nbAdditionalBits := Mux(building_order===0.U, llBitsTable(symbol), 
                                                    Mux(building_order===1.U, symbol, 
                                                        Mux(building_order===2.U, mlBitsTable(symbol), 
                                                        0.U/*dummy*/)))/*bitsTable[symbol] 8b*/
                            rle_nextState := 0.U
                            io.dt_entry_sram.dt_entry_num := 1.U
                            io.dt_entry_sram.dt_entry_content := Cat(rle_baseValue,
                                rle_nbBits, rle_nbAdditionalBits, rle_nextState)

                            bufferManager.io.bits_consumed := 8.U
                            bits_consumed_from_bitstream := bits_consumed_from_bitstream + 8.U

                            building_order := Mux(building_order===2.U, 0.U, building_order + 1.U)
                            state := Mux(building_order===2.U, 3.U, 2.U)
                            rle_entryCount := 0.U
                        }
                    }
                }
                is(FSE){
                    // Assign FSE DTBuilder's output to (ll/off/ml)_dt_info_addr
                    // Increment building_order and state
                    // maxSymbolValue: 35(LL) 52(ML) 31(OFF. recommendation: minimum 22, software: 31)
                    when(building_order <= 2.U){
                        fsedtbuilder.io.maxSymbolValue := Mux(building_order===0.U, 35.U, 
                                                            Mux(building_order===1.U, 31.U, 
                                                              Mux(building_order===2.U, 52.U, 
                                                                0.U/*dummy*/)))
                        fsedtbuilder.io.start_trigger := dt_build_start_trigger
                        fsedtbuilder.io.input_stream := recent_stream
                        fsedtbuilder.io.bitsTableValue := Mux(building_order===0.U, llBitsTable(fsedtbuilder.io.tableIndex), 
                                                            Mux(building_order===1.U, fsedtbuilder.io.tableIndex, 
                                                              Mux(building_order===2.U, mlBitsTable(fsedtbuilder.io.tableIndex), 
                                                                0.U/*dummy*/)))
                        fsedtbuilder.io.baseTableValue := Mux(building_order===0.U, llBaseTable(fsedtbuilder.io.tableIndex), 
                                                            Mux(building_order===1.U, ofBaseTable(fsedtbuilder.io.tableIndex), 
                                                              Mux(building_order===2.U, mlBaseTable(fsedtbuilder.io.tableIndex), 
                                                                0.U/*dummy*/)))
                        fsedtbuilder.io.output_queues_ready := true.B

                        io.dt_entry_sram.dt_entry_num := fsedtbuilder.io.dt_entry_num
                        io.dt_entry_sram.dt_entry_content := fsedtbuilder.io.dt_entry_data

                        when(fsedtbuilder.io.dt_isLastEntry){ // One DT Writing is done
                            when(bits_consumed_from_bitstream%8.U =/=0.U){ // Consume the rest of the byte
                                bufferManager.io.bits_consumed := 8.U - (bits_consumed_from_bitstream%8.U)
                                bits_consumed_from_bitstream := bits_consumed_from_bitstream + 8.U - (bits_consumed_from_bitstream%8.U)
                            }.otherwise{
                                bufferManager.io.bits_consumed := 0.U
                            }
                            building_order := Mux(building_order===2.U, 0.U, building_order + 1.U)
                            state := Mux(building_order===2.U, 3.U, 2.U)
                        }.otherwise{ //DT writing is in progress
                            bufferManager.io.bits_consumed := fsedtbuilder.io.bits_consumed
                            bits_consumed_from_bitstream := bits_consumed_from_bitstream + fsedtbuilder.io.bits_consumed
                        }
                    }
                }
                is(REPEAT){
                    // when(first_block){/*Use the table in the dictionary*/}
                    building_order := Mux(building_order===2.U, 0.U, building_order + 1.U)
                    state := Mux(building_order===2.U, 3.U, 2.U)
                }
            }
        }
        is(3.U){
            //Check if the 3rd DT writing is done, produce the outputs to DTReader
            when(!io.table_in_use && input_src_info_inside_count===0.U &&
                input_src_info_nmb_enq_count===input_stream_enq_count){
                //!table_in_use means DTReader is ready to get new table infos
                when(llMode===0.U){
                    io.ll_dt_info.isPredefined := true.B
                    io.ll_dt_info.parity := 0.U /*dummy value*/
                }.elsewhen(llMode===3.U){
                    io.ll_dt_info.isPredefined := false.B
                    io.ll_dt_info.parity := dt_parity(0) + 1.U
                    //Don't increase dt_parity
                }.otherwise{
                    io.ll_dt_info.isPredefined := false.B
                    io.ll_dt_info.parity := dt_parity(0)
                    dt_parity(0) := dt_parity(0) + 1.U
                }
                when(offMode===0.U){
                    io.off_dt_info.isPredefined := true.B
                    io.off_dt_info.parity := 0.U /*dummy value*/
                }.elsewhen(offMode===3.U){
                    io.off_dt_info.isPredefined := false.B
                    io.off_dt_info.parity := dt_parity(1) + 1.U
                    //Don't increase dt_parity
                }.otherwise{
                    io.off_dt_info.isPredefined := false.B
                    io.off_dt_info.parity := dt_parity(1)
                    dt_parity(1) := dt_parity(1) + 1.U
                }
                when(mlMode===0.U){
                    io.ml_dt_info.isPredefined := true.B
                    io.ml_dt_info.parity := 0.U /*dummy value*/
                }.elsewhen(mlMode===3.U){
                    io.ml_dt_info.isPredefined := false.B
                    io.ml_dt_info.parity := dt_parity(2) + 1.U
                    //Don't increase dt_parity
                }.otherwise{
                    io.ml_dt_info.isPredefined := false.B
                    io.ml_dt_info.parity := dt_parity(2)
                    dt_parity(2) := dt_parity(2) + 1.U
                }
                io.dt_info_valid := num_sequences =/= 0.U
                building_order := 0.U
                state := 0.U

                // Flush the input stream queue and the bitstream buffer in bufferManager.
                bits_consumed_from_bitstream := 0.U
                block_count := block_count + 1.U
                block_count_DTReader := block_count_DTReader + 1.U
                boolptr := 0.U /*dummy address*/
                sequence_section_size := 0.U
                ip_start := 0.U
                num_sequences := 0.U
                llMode := 0.U
                offMode := 0.U
                mlMode := 0.U
                dt_build_start_trigger := true.B
                bitstream_requests_so_far := 0.U
                requested_l2bw := false.B
                input_src_info_inside_count := 0.U
                input_src_info_nmb_enq_count := 0.U
                input_stream_requests_inflight := 0.U
                input_stream_enq_count := 0.U                

                CompressAccelLogger.logInfo(
                    "FSE block type: %d(LL), %d(OFF), %d(ML)\n", llMode, offMode, mlMode)
            }
        }
    }
    // prevent floating wires
    when(!(state===2.U && mode===RLE)){
        rle_tableLog := 0.U
        rle_fastMode := 0.U
        rle_baseValue := 0.U
        rle_nbAdditionalBits := 0.U
        rle_nbBits := 0.U
        rle_nextState := 0.U
    }
    io.zero_num_sequences := num_sequences===0.U
    when(!( (state===1.U && first_stream_received) || 
            (state===2.U && mode===RLE) || 
            (state===2.U && mode===FSE && building_order<=2.U) )){
                bufferManager.io.bits_consumed := 0.U
    }
    when(!(state===2.U && mode===FSE && building_order<=2.U)){
        fsedtbuilder.io.maxSymbolValue := 0.U
        fsedtbuilder.io.start_trigger := false.B
        fsedtbuilder.io.input_stream := 0.U
        fsedtbuilder.io.bitsTableValue := 0.U
        fsedtbuilder.io.baseTableValue := 0.U
        fsedtbuilder.io.output_queues_ready := false.B
    }
    val completion = state===0.U && 
                     io.completion_dtreader && 
                     io.completion_seqexec

    io.completion := (state === 3.U) && !io.table_in_use && input_src_info_inside_count===0.U &&
                input_src_info_nmb_enq_count===input_stream_enq_count

    io.trigger_ready := state===0.U && !(block_count(0)===block_count_DTReader && io.table_in_use)

}
