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

//ZSTD_decodeSequence()
/* In SW:
for(num_sequences){
    sequence = ZSTD_decodeSequence()
    oneSeqSize = ZSTD_execSequence(sequence)
}
In HW:
    for(num_sequences): sequence = decodeSequence().
    Decoupled interface via queue
    for(num_sequences): execSequence()
    increment input and output address internally in SeqExecutor
*/

// Input: 3 DT start addresses, num_sequence, bitstream start address, first_block, start trigger
// Output: sequence
// Internal: 3 DT states
/*
class TableSrcInfo extends Bundle{
    val addr = UInt(64.W)
    //val size = UInt(64.W)
    val isPredefined = Bool()
    val parity = UInt(1.W)
}
*/
class ZstdSequence extends Bundle{
    val ll = UInt(64.W)
    val ml = UInt(64.W)
    val offset = UInt(64.W)
}
/*
class SnappyDecompressSrcInfo extends Bundle{
	val ip = UInt(64.W)
	val isize = UInt(64.W) //Bytes!! Not bits!!
}
*/
class DTReaderRequestInfo extends Bundle{
    val dt_or_bitstream = UInt(1.W) //0: DT entry, 1: bitstream
    val dt_type = UInt(2.W) //0: ll, 1: ml, 2: off
    val dt_index = UInt(64.W)
    val requested_bytes = UInt(16.W)
}
class ZstdDTEntryReadSRAM extends Bundle{
    val dt_parity = Output(UInt(1.W)) //0: select table 0, 1: select 1
    val dt_parity_delayed = Output(UInt(1.W)) // Delayed parity for read
    val dt_entry_num = Output(UInt(10.W)) // SRAM address
    val dt_entry_content = Input(UInt(64.W)) // SRAM entry
    val dt_read = Output(Bool()) // Write or don't write
}
class ZstdDTReader(l2bw: Int)(implicit p: Parameters) extends Module{
    val io = IO(new Bundle{
        val ll_dt_info = Input(new TableSrcInfo) //from DTBuilder
        val off_dt_info = Input(new TableSrcInfo)
        val ml_dt_info = Input(new TableSrcInfo)
        val dt_write_done = Input(Bool()) //from DTBuilder - make it dt_write_done
        val num_sequences = Input(UInt(64.W)) //from DTBuilder
        val bitstream_start_addr = Input(UInt(64.W)) //from DTBuilder
        val bitstream_end_addr = Input(UInt(64.W)) // from somewhere
        val literal_start_addr = Input(UInt(64.W)) //from DTBuilder or somewhere
        val output_start_addr = Input(UInt(64.W)) //from DTBuilder or somewhere
        val mem_stream = (new MemLoaderConsumerBundle).flip //from memloader. can be a DT entry or a input bitstream
        val new_frame = Input(Bool()) // from frame decoder -> to set first_block true. High for 1 cycle.
        val seqexec_ready = Input(Bool())

        val sequence = Decoupled(new ZstdSequence) //to ZstdSeqExecutor
        val literal_start_addr_out = Output(UInt(64.W)) //to ZstdSeqExecutor
        val literal_start_addr_valid = Output(Bool()) //to ZstdSeqExecutor
        val output_start_addr_out = Output(UInt(64.W)) //to ZstdSeqExecutor
        val num_sequences_out = Output(UInt(64.W)) //to ZstdSeqExecutor
        val table_in_use = Output(Bool()) //to DTBuilder. Stop DTBuilder to write in the DT address that is in use.
        val read_request = Decoupled(new SnappyDecompressSrcInfo) //to memloader
        val completion_flag = Output(Bool())

        val ll_dt_entry_sram = new ZstdDTEntryReadSRAM //to, from DT SRAMs
        val ml_dt_entry_sram = new ZstdDTEntryReadSRAM //to, from DT SRAMs
        val off_dt_entry_sram = new ZstdDTEntryReadSRAM //to, from DT SRAMs

        val is_last_block = Input(Bool())
    })
    dontTouch(io.sequence)
    // Registers for metadata (received from the input)
    val num_sequences = RegInit(0.U(32.W))
    val bitstream_start_addr = RegInit(0.U(64.W))
    dontTouch(bitstream_start_addr)
    val bitstream_end_addr = RegInit(0.U(64.W))
    val literal_start_addr = RegInit(0.U(64.W))
    io.literal_start_addr_out := literal_start_addr
    val literal_start_addr_fireOnce = RegInit(false.B)
    io.literal_start_addr_valid := literal_start_addr_fireOnce
    when(literal_start_addr_fireOnce && io.seqexec_ready){literal_start_addr_fireOnce := false.B}
    val output_start_addr = RegInit(0.U(64.W))
    io.output_start_addr_out := output_start_addr
    io.num_sequences_out := num_sequences
    val first_block = RegInit(true.B)
    first_block := Mux(io.new_frame, true.B, first_block)
    val dt_mem_address = RegInit(VecInit(Seq.fill(3)(0.U(64.W)))) // memory addresses to 3 DTs' header
    val dt_parity = RegInit(VecInit(Seq.fill(3)(0.U(1.W)))) // parity of 3 DTs
    val dt_parity_delayed = RegInit(VecInit(Seq.fill(3)(0.U(1.W)))) // delayed parity: used for SRAM read
    for(i <- 0 to 2){
        dt_parity_delayed(i) := dt_parity(i)
    }
    //val dt_size = RegInit(VecInit(Seq.fill(3)(0.U(64.W)))) // size of 3 DTs
    val dt_state = RegInit(VecInit(Seq.fill(3)(0.U(64.W)))) // states for 3 DTs
    val dt_isPredefined = RegInit(VecInit(Seq.fill(3)(false.B))) // whether each DT is predefined or not. 1 if predefined.
    val prevOffset = RegInit(VecInit(Seq.fill(3)(0.U(64.W)))) // corresponds to repeated_offset in zstd format doc
    val seq_processed_so_far = RegInit(0.U(64.W)) // For loop variable
    val total_bitstream_consumed = RegInit(0.U(64.W)) //bits
    val isDTentry = 0.U
    val isBitstream = 1.U
    val LL = 0.U
    val ML = 1.U
    val OFF = 2.U
    val bitstream_requests_so_far = RegInit(0.U(32.W))
    val is_last_block = RegInit(false.B)

    // FSM states
    val fsm_state = RegInit(0.U(3.W))
    val RECEIVE_DT_ADDRESS = 0.U
    val REQUEST_DT_HEADER = 1.U
    val INITIALIZE_DT_STATE = 2.U
    val REQUEST_DT_ENTRY = 3.U
    val PRODUCE_SEQUENCE_PIPE0 = 4.U
    val PRODUCE_SEQUENCE_PIPE1 = 5.U
    val RESET_QUEUE = 6.U
    val dt_type_counter = RegInit(0.U(2.W)) // For loop variable in state REQUEST_DT_HEADER
    val dt_entries_receipt = RegInit(false.B) // For loop variable in state REQUEST_DT_HEADER
    val bitstream_receipt = RegInit(false.B) // For loop variable in state REQUEST_DT_HEADER

    // Detecting the first nonzero bit from the bitstream
    val first1 = Wire(UInt(4.W))
    when(fsm_state=/=INITIALIZE_DT_STATE){first1 := 0.U}
    dontTouch(first1)

    // Queues and buffers
    val request_info_queue_flush = false.B
    val request_info_queue = Module(new Queue(new DTReaderRequestInfo, 6, false, false, request_info_queue_flush))
    val request_info_enq_count = RegInit(0.U(3.W))
    val request_info_enq_fire = request_info_queue.io.enq.ready && request_info_queue.io.enq.valid
    val request_info_deq_fire = request_info_queue.io.deq.ready && request_info_queue.io.deq.valid
    when(request_info_enq_fire && !request_info_deq_fire){
        request_info_enq_count := request_info_enq_count + 1.U
    }.elsewhen(!request_info_enq_fire && request_info_deq_fire){
        request_info_enq_count := request_info_enq_count - 1.U
    }
    val request_queue_flush = Mux(fsm_state===RESET_QUEUE && request_info_enq_count===0.U, true.B, false.B)
    val bitstream_queue_flush = Mux(fsm_state===RESET_QUEUE && request_info_enq_count===0.U, true.B, false.B)    
    val request_queue = Module(new Queue(new SnappyDecompressSrcInfo, 6, false, false, request_queue_flush))
    val bitstream_queue = Module(new Queue(UInt(l2bw.W), 6, false, false, bitstream_queue_flush))
    
    val buffermanager = Module(new BufferManagerReverse(l2bw))
    val bitstream = Wire(UInt(l2bw.W))
    bitstream := buffermanager.io.most_recent_stream
    buffermanager.io.bits_consumed := 0.U //by default. connect to another value in the FSM.
    val dt_entry = Wire(Vec(3, UInt(64.W)))
    val dt_entry_reg = RegInit(VecInit(Seq.fill(3)(0.U(64.W))))
    val dt_entry_wire = Wire(Vec(3, UInt(64.W)))
    val sequence_queue = Module(new Queue(new ZstdSequence, 4))

    // Predefined (Default) decode table
    val predefinedDT = Module(new ZstdPredefinedDT)

    // DT entry wires
    dt_entry_wire(LL) := Mux(
                    !dt_isPredefined(LL),
                    io.ll_dt_entry_sram.dt_entry_content,
                    predefinedDT.io.ll_entry)
    dt_entry_wire(ML) := Mux(
        !dt_isPredefined(ML),
        io.ml_dt_entry_sram.dt_entry_content,
        predefinedDT.io.ml_entry)
    dt_entry_wire(OFF) := Mux(
        !dt_isPredefined(OFF),
        io.off_dt_entry_sram.dt_entry_content,
        predefinedDT.io.off_entry)
    val sequence_produce_cond = Wire(Bool())
    val sequence_produce_cond_delayed = RegInit(false.B)
    sequence_produce_cond_delayed := sequence_produce_cond
    dt_entry(LL) := dt_entry_reg(LL)
    dt_entry(ML) := dt_entry_reg(ML)
    dt_entry(OFF) := dt_entry_reg(OFF)
    val tableLog_ll = Wire(UInt(4.W)) //max 9
    tableLog_ll := dt_entry(LL)(63,32)
    val tableLog_ml = Wire(UInt(4.W)) //max 9
    tableLog_ml := dt_entry(ML)(63,32) 
    val tableLog_off = Wire(UInt(4.W)) //max 8
    tableLog_off := dt_entry(OFF)(63,32)
    val total_tableLog = Wire(UInt(5.W)) //max 26
    total_tableLog := tableLog_ll +& tableLog_ml +& tableLog_off
    val ll_nextState = Wire(UInt(16.W))
    val ll_nbAdd = Wire(UInt(8.W))
    val ll_nb = Wire(UInt(8.W))
    val ll_baseValue = Wire(UInt(32.W))
    ll_nextState := dt_entry(LL)(15, 0)
    ll_nbAdd := dt_entry(LL)(23, 16)
    ll_nb := dt_entry(LL)(31, 24)
    ll_baseValue := dt_entry(LL)(63, 32)
    val ml_nextState = Wire(UInt(16.W))
    val ml_nbAdd = Wire(UInt(8.W))
    val ml_nb = Wire(UInt(8.W))
    val ml_baseValue = Wire(UInt(32.W))
    ml_nextState := dt_entry(ML)(15, 0)
    ml_nbAdd := dt_entry(ML)(23, 16)
    ml_nb := dt_entry(ML)(31, 24)
    ml_baseValue := dt_entry(ML)(63, 32)
    val off_nextState = Wire(UInt(16.W))
    val off_nbAdd = Wire(UInt(8.W))
    val off_nb = Wire(UInt(8.W))
    val off_baseValue = Wire(UInt(32.W))
    off_nextState := dt_entry(OFF)(15, 0)
    off_nbAdd := dt_entry(OFF)(23, 16)
    off_nb := dt_entry(OFF)(31, 24)
    off_baseValue := dt_entry(OFF)(63, 32)

    sequence_produce_cond := fsm_state===PRODUCE_SEQUENCE_PIPE1 && 
        seq_processed_so_far < num_sequences &&
        sequence_queue.io.enq.ready &&
        (off_nbAdd +& ml_nbAdd +& ll_nbAdd +& ll_nb +& ml_nb +& off_nb) <= buffermanager.io.available_bits

    // Queue interface
    io.read_request <> request_queue.io.deq
    request_info_queue.io.enq.valid := request_queue.io.enq.valid
    request_info_queue.io.deq.ready := io.mem_stream.output_valid && io.mem_stream.output_ready
    val request_enqueued = request_queue.io.enq.valid && request_queue.io.enq.ready
    val bitstream_request_enqueued = request_info_queue.io.enq.valid && request_info_queue.io.enq.ready &&
                                    request_info_queue.io.enq.bits.dt_or_bitstream===isBitstream
    val bitstream_requests_inflight = RegInit(0.U(3.W)) //(# of bitstream requested) - (# of bitstreams received by the buffer)
    val bitstream_queue_enq_count = RegInit(0.U(3.W))
    val bitstream_queue_enq_fire = bitstream_queue.io.enq.ready && bitstream_queue.io.enq.valid
    val bitstream_queue_deq_fire = bitstream_queue.io.deq.ready && bitstream_queue.io.deq.valid
    when(bitstream_request_enqueued && !bitstream_queue_deq_fire){bitstream_requests_inflight := bitstream_requests_inflight + 1.U}
    .elsewhen(!bitstream_request_enqueued && bitstream_queue_deq_fire){bitstream_requests_inflight := bitstream_requests_inflight - 1.U}
    .otherwise{/*do nothing*/}
    when(bitstream_queue_enq_fire && !bitstream_queue_deq_fire){
        bitstream_queue_enq_count := bitstream_queue_enq_count + 1.U
    }.elsewhen(!bitstream_queue_enq_fire && bitstream_queue_deq_fire){
        bitstream_queue_enq_count := bitstream_queue_enq_count - 1.U
    }
    val bitstream_queue_full = (bitstream_queue_enq_count === 6.U)
    val bitstream_request = (bitstream_requests_inflight < 6.U) && 
                            (fsm_state === REQUEST_DT_HEADER || fsm_state === INITIALIZE_DT_STATE ||
                            fsm_state === REQUEST_DT_ENTRY || fsm_state === PRODUCE_SEQUENCE_PIPE0 ||
                            fsm_state === PRODUCE_SEQUENCE_PIPE1)
    request_queue.io.enq.valid := bitstream_request
    when(fsm_state===RESET_QUEUE){
        io.mem_stream.output_ready := true.B
    }.otherwise{
        io.mem_stream.output_ready := (request_info_queue.io.deq.bits.dt_or_bitstream===isBitstream &&
            bitstream_queue.io.enq.ready) &&
            request_info_queue.io.deq.bits.requested_bytes =/= 0.U &&
            request_info_queue.io.deq.bits.requested_bytes <= io.mem_stream.available_output_bytes
    }
    val streamfire = DecoupledHelper(
        request_info_queue.io.deq.valid,
        io.mem_stream.output_valid,
        io.mem_stream.output_ready
    )
    bitstream_queue.io.enq.bits := io.mem_stream.output_data
    bitstream_queue.io.enq.valid := streamfire.fire && 
        request_info_queue.io.deq.bits.dt_or_bitstream===isBitstream
    buffermanager.io.input_stream_deq <> bitstream_queue.io.deq
    val bitstream_buffer_received = bitstream_queue.io.deq.valid && bitstream_queue.io.deq.ready
    when(request_info_queue.io.deq.valid){
        io.mem_stream.user_consumed_bytes := request_info_queue.io.deq.bits.requested_bytes
    }.otherwise{
        io.mem_stream.user_consumed_bytes := 0.U
    }
    
    sequence_queue.io.enq.valid := fsm_state===PRODUCE_SEQUENCE_PIPE1 && 
        seq_processed_so_far < num_sequences &&
        (off_nbAdd +& ml_nbAdd +& ll_nbAdd +& ll_nb +& ml_nb +& off_nb) <= buffermanager.io.available_bits

    
    //sequence values
    val ll = Wire(UInt(64.W))
    val ml = Wire(UInt(64.W))
    val off = Wire(UInt(64.W))
    val off_temp = Wire(UInt(64.W))
    val off_temp_temp = Wire(UInt(64.W))

    // Output
    io.table_in_use := (fsm_state=/=0.U) //0.U=RECEIVE_DT_ADDRESS. Nonzero state means DTs are in use.
    io.sequence <> sequence_queue.io.deq   
    buffermanager.io.flush := Mux(fsm_state===RESET_QUEUE, true.B, false.B)
    io.ll_dt_entry_sram.dt_parity := dt_parity(LL)
    io.ml_dt_entry_sram.dt_parity := dt_parity(ML)
    io.off_dt_entry_sram.dt_parity := dt_parity(OFF)
    io.ll_dt_entry_sram.dt_parity_delayed := dt_parity_delayed(LL)
    io.ml_dt_entry_sram.dt_parity_delayed := dt_parity_delayed(ML)
    io.off_dt_entry_sram.dt_parity_delayed := dt_parity_delayed(OFF)
    val dt_read = (fsm_state===REQUEST_DT_HEADER && dt_type_counter === 0.U && !dt_entries_receipt) ||
                (fsm_state===REQUEST_DT_ENTRY && seq_processed_so_far < num_sequences) ||
                // Performance patch: read DT while producing sequences
                (fsm_state===PRODUCE_SEQUENCE_PIPE1 && seq_processed_so_far < num_sequences && 
                sequence_queue.io.enq.ready &&
                (off_nbAdd +& ml_nbAdd +& ll_nbAdd +& ll_nb +& ml_nb +& off_nb) <= buffermanager.io.available_bits)
    io.ll_dt_entry_sram.dt_read := dt_read
    io.ml_dt_entry_sram.dt_read := dt_read
    io.off_dt_entry_sram.dt_read := dt_read

    // Completion check
    val dispatched_sequences = RegInit(0.U(64.W))
    when(fsm_state===RECEIVE_DT_ADDRESS && io.dt_write_done){
        dispatched_sequences := 0.U
    }.elsewhen(sequence_queue.io.deq.ready && sequence_queue.io.deq.valid){
        dispatched_sequences := dispatched_sequences + 1.U
    }.otherwise{}
    val completion_flag = (dispatched_sequences === num_sequences && num_sequences =/= 0.U)
    io.completion_flag := completion_flag

    //FSM
    switch(fsm_state){
        is(RECEIVE_DT_ADDRESS){
            // Step 1. Initialize prevOffset. Request 3 DT Headers and the first bitstream.
            // Wait for them to arrive.
            // Step 1-1. Initializing prevOffset
            /* Get prevOffset from prev compressed_block(mode==FSE. not RLE).
            ** For the first block, repeated_offset (1)=1, (2)=4, (3)=8
            */
            when(io.dt_write_done && first_block){
                prevOffset(0.U):=1.U
                prevOffset(1.U):=4.U
                prevOffset(2.U):=8.U
                first_block := false.B
            }/*otherwise get prevOffset of the previous compressed block
            which is just to hold the prevOffset values*/

            // Step 1-2. Receive DT start addresses
            when(io.dt_write_done){
                dt_mem_address(LL) := io.ll_dt_info.addr
                dt_parity(LL) := io.ll_dt_info.parity
                dt_isPredefined(LL) := io.ll_dt_info.isPredefined
                dt_mem_address(OFF) := io.off_dt_info.addr
                dt_parity(OFF) := io.off_dt_info.parity
                dt_isPredefined(OFF) := io.off_dt_info.isPredefined
                dt_mem_address(ML) := io.ml_dt_info.addr
                dt_parity(ML) := io.ml_dt_info.parity
                dt_isPredefined(ML) := io.ml_dt_info.isPredefined
                num_sequences := io.num_sequences
                bitstream_start_addr := io.bitstream_start_addr
                bitstream_end_addr := io.bitstream_end_addr
                literal_start_addr := io.literal_start_addr
                output_start_addr := io.output_start_addr
                literal_start_addr_fireOnce := true.B
                is_last_block := io.is_last_block
            }
            // If all DTs are written completely, jump to next state and request DT headers.
            when(io.dt_write_done && io.seqexec_ready){
                fsm_state := fsm_state + 1.U
            }
        }
        is(REQUEST_DT_HEADER){
            // Step 2. Request 3 DT headers and the first bitstream. Wait for them to arrive.
            
            // Request bitstream - outside of this FSM loop

            // Request 3 DT Headers at once
            // io.dt_entry_sram.dt_parity, dt_type, dt_read is dealt outside the loop
            io.ll_dt_entry_sram.dt_entry_num := 0.U
            io.ml_dt_entry_sram.dt_entry_num := 0.U
            io.off_dt_entry_sram.dt_entry_num := 0.U
            when(dt_type_counter === 0.U && !dt_entries_receipt){
                dt_type_counter := dt_type_counter + 1.U
            }

            // Receive 3 DT Headers at the same time
            when(dt_type_counter === 1.U && !dt_entries_receipt){
                predefinedDT.io.ll_state := 0.U
                dt_entry_reg(LL) := dt_entry_wire(LL)
                predefinedDT.io.ml_state := 0.U
                dt_entry_reg(ML) := dt_entry_wire(ML)
                predefinedDT.io.off_state := 0.U
                dt_entry_reg(OFF) := dt_entry_wire(OFF)                
                dt_entries_receipt := true.B
            }
            // Check receipt of bitstream and increase received_data_counter.
            // DT entries will come from 1 cycle latency SRAMs, so
            // bitstream from L2 will arrive later than DT entries,
            // but set bitstream_receipt flag too just in case .
            when(bitstream_buffer_received && !bitstream_receipt){
                // bitstream buffer will be handled by the bufferManager.
                bitstream_receipt := true.B
            }

            // After receiving 3 DT headers and 1 bitstream, change fsm state.
            when(dt_entries_receipt && bitstream_receipt){
                fsm_state := fsm_state + 1.U
                dt_entries_receipt := false.B
                bitstream_receipt := false.B
                dt_type_counter := 0.U
            }            
        }
        is(INITIALIZE_DT_STATE){
            when(bitstream(l2bw-1)===1.U){first1 := 1.U}
            .elsewhen(bitstream(l2bw-2)===1.U && bitstream(l2bw-2+1)===0.U){first1 := 2.U}
            .elsewhen(bitstream(l2bw-3)===1.U && bitstream(l2bw-3+1)===0.U){first1 := 3.U}
            .elsewhen(bitstream(l2bw-4)===1.U && bitstream(l2bw-4+1)===0.U){first1 := 4.U}
            .elsewhen(bitstream(l2bw-5)===1.U && bitstream(l2bw-5+1)===0.U){first1 := 5.U}
            .elsewhen(bitstream(l2bw-6)===1.U && bitstream(l2bw-6+1)===0.U){first1 := 6.U}
            .elsewhen(bitstream(l2bw-7)===1.U && bitstream(l2bw-7+1)===0.U){first1 := 7.U}
            .elsewhen(bitstream(l2bw-8)===1.U && bitstream(l2bw-8+1)===0.U){first1 := 8.U}
            dt_state(LL) := ( bitstream & ((1.U<<(l2bw.U-first1))-1.U) ) >> 
                (l2bw.U-first1-tableLog_ll)
            dt_state(OFF) := ( bitstream & ((1.U<<(l2bw.U-first1-tableLog_ll))-1.U) ) >> 
                (l2bw.U-first1-tableLog_ll-tableLog_off)
            dt_state(ML) := ( bitstream & ((1.U<<(l2bw.U-first1-tableLog_ll-tableLog_off))-1.U) ) >> 
                (l2bw.U-first1-tableLog_ll-tableLog_off-tableLog_ml)
            
            buffermanager.io.bits_consumed := total_tableLog +& first1 
            total_bitstream_consumed := total_bitstream_consumed +& total_tableLog +& first1 
            fsm_state := fsm_state + 1.U
        }
        is(REQUEST_DT_ENTRY){
            // Performance patch: only use for the first batch of request
            when(seq_processed_so_far < num_sequences){
                // Request 3 DT Headers at once
                // io.dt_entry_sram.dt_parity, dt_type, dt_read is dealt outside the loop
                io.ll_dt_entry_sram.dt_entry_num := 1.U+dt_state(LL)
                io.ml_dt_entry_sram.dt_entry_num := 1.U+dt_state(ML)
                io.off_dt_entry_sram.dt_entry_num := 1.U+dt_state(OFF)
                dt_entries_receipt := true.B
                fsm_state := PRODUCE_SEQUENCE_PIPE0
            }.otherwise{
                fsm_state := RESET_QUEUE
                seq_processed_so_far := 0.U

                CompressAccelLogger.logInfo("DTReader invalid dt_type_counter\n")
            }
        }
        is(PRODUCE_SEQUENCE_PIPE0){
            // This state only receives decode table entries from the SRAM.
            // The computation is done in fsm_state PRODUCE_SEQUENCE_PIPE1.

            // Use dt_entries_receipt signal to indicate the first cycle of this state.
            // The first cycle is where the DT entries from SRAM should be received.
            
            // Performance patch: Use the when(dt_entries_receipt) block below
            // only for the first batch of request.
            // This became meaningless for the tapeout version as to meet timing
            // constraints overlapping sequence production and decode table
            // read request became impossible.
            when(dt_entries_receipt){
                dt_entries_receipt := false.B
            }

            // Performance patch: Receiving
            when(dt_entries_receipt || sequence_produce_cond_delayed){
                predefinedDT.io.ll_state := 1.U+dt_state(LL)
                predefinedDT.io.ml_state := 1.U+dt_state(ML)
                predefinedDT.io.off_state := 1.U+dt_state(OFF)
                dt_entry_reg(LL) := dt_entry_wire(LL)
                dt_entry_reg(ML) := dt_entry_wire(ML)
                dt_entry_reg(OFF) := dt_entry_wire(OFF)
                fsm_state := PRODUCE_SEQUENCE_PIPE1
            }
        }
        is(PRODUCE_SEQUENCE_PIPE1){
            // This is the state where the sequence production
            // and request for decode table entries happen.
            
            // Do not jump into next sequence if sequence_queue.io.enq.ready === false.B
            // Do not jump into next sequence if buffermanager doesn't have enough bits
            when(seq_processed_so_far < num_sequences){
                when(sequence_queue.io.enq.ready &&
                (off_nbAdd +& ml_nbAdd +& ll_nbAdd +& ll_nb +& ml_nb +& off_nb) <= buffermanager.io.available_bits){
                    when(off_nbAdd > 1.U){
                        off := off_baseValue +& (bitstream >> (l2bw.U-off_nbAdd))
                        prevOffset(0.U) := off
                        prevOffset(1.U) := prevOffset(0.U)
                        prevOffset(2.U) := prevOffset(1.U)
                    }.elsewhen(off_nbAdd === 0.U){
                        off := prevOffset((ll_baseValue===0.U).asUInt)
                        prevOffset(1.U) := prevOffset((!(ll_baseValue===0.U)).asUInt)
                        prevOffset(0.U) := off
                    }.otherwise{//off_nbAdd === 1.U
                        off_temp_temp := off_baseValue +& (ll_baseValue===0.U).asUInt +& bitstream(l2bw-1)
                        off_temp := Mux(off_temp_temp===3.U, prevOffset(0.U)-1.U, prevOffset(off_temp_temp))
                        off := Mux(off_temp===0.U, 1.U, off_temp)
                        when(off_temp_temp=/=1.U){prevOffset(2.U) := prevOffset(1.U)}
                        prevOffset(1.U) := prevOffset(0.U)
                        prevOffset(0.U) := off
                    }
                    ml := ml_baseValue +& ((bitstream & ((1.U << (l2bw.U-off_nbAdd)) - 1.U)) >> (l2bw.U-off_nbAdd-ml_nbAdd))
                    ll := ll_baseValue +& ((bitstream & ((1.U << (l2bw.U-off_nbAdd-ml_nbAdd)) - 1.U)) >> (l2bw.U-off_nbAdd-ml_nbAdd-ll_nbAdd))
                    val dt_state_ll_wire = ll_nextState +& ((bitstream & ((1.U << (l2bw.U-off_nbAdd-ml_nbAdd-ll_nbAdd)) - 1.U)) >> (l2bw.U-off_nbAdd-ml_nbAdd-ll_nbAdd-ll_nb))
                    val dt_state_ml_wire = ml_nextState +& ((bitstream & ((1.U << (l2bw.U-off_nbAdd-ml_nbAdd-ll_nbAdd-ll_nb)) - 1.U)) >> (l2bw.U-off_nbAdd-ml_nbAdd-ll_nbAdd-ll_nb-ml_nb))
                    val dt_state_off_wire = off_nextState +& ((bitstream & ((1.U << (l2bw.U-off_nbAdd-ml_nbAdd-ll_nbAdd-ll_nb-ml_nb)) - 1.U)) >> (l2bw.U-off_nbAdd-ml_nbAdd-ll_nbAdd-ll_nb-ml_nb-off_nb))
                    dt_state(LL) := dt_state_ll_wire
                    dt_state(ML) := dt_state_ml_wire
                    dt_state(OFF) := dt_state_off_wire
                    buffermanager.io.bits_consumed := (off_nbAdd +& ml_nbAdd +& ll_nbAdd +& ll_nb +& ml_nb +& off_nb)
                    total_bitstream_consumed := total_bitstream_consumed +& (off_nbAdd +& ml_nbAdd +& ll_nbAdd +& ll_nb +& ml_nb +& off_nb)

                    sequence_queue.io.enq.bits.ll := ll
                    sequence_queue.io.enq.bits.ml := ml
                    sequence_queue.io.enq.bits.offset := off
                    // sequence_queue.io.enq.valid := true.B
                    seq_processed_so_far := seq_processed_so_far + 1.U

                    // Performance patch: Overlap requesting and consuming DT entries
                    fsm_state := PRODUCE_SEQUENCE_PIPE0
                    io.ll_dt_entry_sram.dt_entry_num := 1.U+dt_state_ll_wire
                    io.ml_dt_entry_sram.dt_entry_num := 1.U+dt_state_ml_wire
                    io.off_dt_entry_sram.dt_entry_num := 1.U+dt_state_off_wire

                    CompressAccelLogger.logInfo("DTReader state PRODUCE_SEQUENCE\n")
                    CompressAccelLogger.logInfo("sequence_queue.io.enq.fire!!!\n")
                    CompressAccelLogger.logInfo("num_sequences: %d\n", num_sequences)
                    CompressAccelLogger.logInfo("seq_processed_so_far: %d\n", seq_processed_so_far)
                }
            }.otherwise{
                fsm_state := RESET_QUEUE
                seq_processed_so_far := 0.U
            }
        }
        is(RESET_QUEUE){
            /* set queue flush signals to true, disconnect data from memloader from queues
            ** Make sure that there is no in-flight request being processed in the memloader.
            ** The easiest way is to wait for request_info_queue to be empty
            ** and then set the flush signal.
            ** Don't wait for sequence_queue to be empty: decouple sequence producing and consuming. */
            when(request_info_enq_count===0.U && completion_flag){
                //reset registers, except for first_block and prevOffset
                sequence_produce_cond_delayed := false.B
                num_sequences := 0.U
                bitstream_start_addr := 0.U
                bitstream_end_addr := 0.U
                literal_start_addr := 0.U
                seq_processed_so_far := 0.U
                total_bitstream_consumed := 0.U
                bitstream_requests_so_far := 0.U
                dt_type_counter := 0.U
                request_info_enq_count := 0.U
                bitstream_queue_enq_count := 0.U
                bitstream_requests_inflight := 0.U
                dispatched_sequences := 0.U
                for(i<-0 to 2){
                    dt_entry(i.U) := 0.U
                    dt_mem_address(i.U) := 0.U
                    //dt_size(i.U) := 0.U
                    dt_isPredefined(i.U) := false.B
                    dt_state(i.U) := 0.U
                }
                
                dt_entries_receipt := false.B
                bitstream_receipt := false.B

                when(is_last_block){
                    first_block := true.B
                    is_last_block := false.B
                }

                fsm_state := RECEIVE_DT_ADDRESS

                CompressAccelLogger.logInfo("DTReader finished Execution\n")
            }
        }
    }
    // Request bitstream, in states where DT entries are not requested
    // Make sure this request does not conflict with DT request -- now DT is in SRAM so it's okay
    when(fsm_state === INITIALIZE_DT_STATE || fsm_state === REQUEST_DT_HEADER ||
        fsm_state === REQUEST_DT_ENTRY || fsm_state === PRODUCE_SEQUENCE_PIPE0 ||
        fsm_state === PRODUCE_SEQUENCE_PIPE1){
        when(bitstream_requests_inflight < 6.U){
            request_queue.io.enq.bits.ip := bitstream_end_addr - (bitstream_requests_so_far+1.U) * (l2bw/8).U
            request_queue.io.enq.bits.isize := (l2bw/8).U
            when(request_enqueued){bitstream_requests_so_far := bitstream_requests_so_far + 1.U}
            request_info_queue.io.enq.bits.dt_or_bitstream := isBitstream
            request_info_queue.io.enq.bits.dt_type := 3.U
            request_info_queue.io.enq.bits.dt_index := 0.U/*dummy value*/
            request_info_queue.io.enq.bits.requested_bytes := (l2bw/8).U
        }
    }

    // Preventing floating wire
    when(!(fsm_state===PRODUCE_SEQUENCE_PIPE1 && seq_processed_so_far < num_sequences && sequence_queue.io.enq.ready)){
        ll := 0.U
        ml := 0.U
        off := 0.U
        off_temp := 0.U
        off_temp_temp := 0.U
        sequence_queue.io.enq.valid := false.B
    }
    when(fsm_state=/=PRODUCE_SEQUENCE_PIPE1){
        sequence_queue.io.enq.bits.ll := 0.U
        sequence_queue.io.enq.bits.ml := 0.U
        sequence_queue.io.enq.bits.offset := 0.U
    }
}
