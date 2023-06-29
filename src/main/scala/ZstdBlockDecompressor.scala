package compressacc

import Chisel._
import chisel3.SyncReadMem
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheArbiter}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._
import chisel3.dontTouch
/*
class FSEL2MemHelpers extends Bundle {
  val read_dtbuild_userif = new L2MemHelperBundle
  val write_dtbuild_userif = new L2MemHelperBundle
  val read_dtread_userif = new L2MemHelperBundle
  val read_seqexec_userif = new L2MemHelperBundle
  val read_histlookup_userif = new L2MemHelperBundle
  val write_seqexec_userif = new L2MemHelperBundle
}
*/
class DTAddressBundle extends Bundle{
      val ll_dt_addr_0 = UInt(64.W)
      val off_dt_addr_0 = UInt(64.W)
      val ml_dt_addr_0 = UInt(64.W)
      val ll_dt_addr_1 = UInt(64.W)
      val off_dt_addr_1 = UInt(64.W)
      val ml_dt_addr_1 = UInt(64.W)
}

///////////////////////////////////////////////////////////////////////////
// - Src stream layout
// | BLOCK_HEADER_BYTES | literal_comp_size | 
// ^                    ^                   ^
// |                    |                   |
// block                block               seq
// start                lit_src_start       src_start
// addr                 addr                addr
///////////////////////////////////////////////////////////////////////////

///////////////////////////////////////////////////////////////////////////
// - wksp stream layout
// | ll table (4kB) | ml table (4kB) | of table (2kB) | block lit/seq |
///////////////////////////////////////////////////////////////////////////

///////////////////////////////////////////////////////////////////////////
// - block lit/seq (last part of wksp stream layout)
// | literal_decomp_size | < put fse decompressed ml, ll, of here> |
// ^                     ^
// |                     |
// wksp                  seq
// start                 dst_start
// addr                  addr
///////////////////////////////////////////////////////////////////////////

/////////////////////////////////////////////////////////////////////////
// - final results of decompression (output of seqExecutor)
// | written_bytes |   ~~~~~~~~~~~~~~~~~~~~~~~~~  | cmpflag |
// ^
// |
// op
///////////////////////////////////////////////////////////////////////////


class CompressedBlockInfo extends Bundle {
  val ip = new DecompressPtrInfo             // compressed src starting position
  val ll_dic = new DecompressPtrInfo         // ll dic workspace
  val ml_dic = new DecompressPtrInfo         // ml dic workspace
  val of_dic = new DecompressPtrInfo         // of dic workspace
  val wp = new DecompressPtrInfo             // literal / sequence workspace
  val dst = new DecompressDstInfo  // decompressed stream destination
}

class DecompressedBlockInfo extends Bundle {
  val last_block = Bool()
  val block_type = UInt(2.W)
  val block_size = UInt(21.W)
  val written_bytes = UInt(64.W)
}

class ZstdBlockDecompressorIO(implicit val p: Parameters) extends Bundle {
  val compressed_block_info = Flipped(Decoupled(new CompressedBlockInfo))
  val decompressed_block_info = Decoupled(new DecompressedBlockInfo)

  val l2_bhdr_userif = new L2MemHelperBundle
  val l2_huf_memhelpers = new HufL2MemHelpers
  val l2_fse_memhelpers = new FSEL2MemHelpers
  val l2_rawrle_memhelpers = new RawRLEL2MemHelpers

  val MAX_OFFSET_ALLOWED = Input(UInt(64.W))
  val ALGORITHM = Input(UInt(1.W))

  val snappy_decompress_src_info = Flipped(Decoupled(new StreamInfo))
  val snappy_decompress_dest_info = Flipped(Decoupled(new SnappyDecompressDestInfo))
  val snappy_decompress_dest_info_offchip = Flipped(Decoupled(new SnappyDecompressDestInfo))
  val snappy_bufs_completed = Output(UInt(64.W))
  val snappy_no_writes_inflight = Output(Bool())
}

/* The ZstdBlockDecompressor decompresses each Zstd block within the current frame.
 * It first decodes the block header to get information such as the decompressed 
 * size of the block, whether if the current block is the last byte of the frame, and 
 * the compression format. Currently, the RLE & Raw blocks are not supported. 
 * It receives commands using the "compressed_block_info" port & returns the 
 * block information using the "decompressed_block_info" port. Within this block,
 * the Huffman decoder, the FSE decoder and the Sequence Executor resides. */
class ZstdBlockDecompressor(zstdTop: ZstdDecompressor, cmd_que_depth: Int)
  (implicit val p: Parameters) extends Module {
  val io = IO(new ZstdBlockDecompressorIO)

  val nosnappy = p(NoSnappy)

  val BLOCK_HEADER_BYTES = 3

  val STATE_IDLE = 0.U
  val STATE_DECODE_BLOCK_HEADER = 1.U
  val STATE_DECODE_LITERALS = 2.U
  val STATE_DECODE_SEQUENCE = 3.U
  val STATE_RAWRLE = 4.U
  val STATE_DONE = 5.U
  val state = RegInit(0.U(4.W))

  val compressed_info_q = Module(new Queue(new CompressedBlockInfo, cmd_que_depth)).io
  compressed_info_q.enq <> io.compressed_block_info

  when (compressed_info_q.enq.fire) {
    CompressAccelLogger.logInfo("BlockDecompressor compressed_info_q.enq.fire!!\n")
    CompressAccelLogger.logInfo("ip : 0x%x\n", compressed_info_q.enq.bits.ip.ip)
    CompressAccelLogger.logInfo("ll_dic : 0x%x\n", compressed_info_q.enq.bits.ll_dic.ip)
    CompressAccelLogger.logInfo("ml_dic : 0x%x\n", compressed_info_q.enq.bits.ml_dic.ip)
    CompressAccelLogger.logInfo("of_dic : 0x%x\n", compressed_info_q.enq.bits.of_dic.ip)
    CompressAccelLogger.logInfo("wp : 0x%x\n", compressed_info_q.enq.bits.wp.ip)
    CompressAccelLogger.logInfo("op : 0x%x\n", compressed_info_q.enq.bits.dst.op)
    CompressAccelLogger.logInfo("cmpflag : 0x%x\n", compressed_info_q.enq.bits.dst.cmpflag)
  }

  val memloader = Module(new MemLoader(memLoaderQueDepth=2))
  val decode_block_header_fire = DecoupledHelper(compressed_info_q.deq.valid,
                                                 memloader.io.src_info.ready,
                                                 state === STATE_IDLE)

  io.l2_bhdr_userif <> memloader.io.l2helperUser
  memloader.io.src_info.valid := decode_block_header_fire.fire(memloader.io.src_info.ready)
  memloader.io.src_info.bits.ip := compressed_info_q.deq.bits.ip.ip
  memloader.io.src_info.bits.isize := BLOCK_HEADER_BYTES.U
  val block_content_start_ip = compressed_info_q.deq.bits.ip.ip + BLOCK_HEADER_BYTES.U

  when (decode_block_header_fire.fire) {
    CompressAccelLogger.logInfo("Block header pointer: 0x%x\n", compressed_info_q.deq.bits.ip.ip)
  }

  val bhdr_stream = memloader.io.consumer
  val avail_bytes = bhdr_stream.available_output_bytes
  val last_block = Wire(Bool())
  val block_type = Wire(UInt(2.W))
  val block_size = Wire(UInt(21.W))
  last_block := bhdr_stream.output_data(0)
  block_type := bhdr_stream.output_data(2, 1)
  block_size := bhdr_stream.output_data(23, 3)

  // written_bytes represents the number of bytes written to "dst"
  // i.e., the total number of final decompressed bytes written
  val written_bytes = RegInit(0.U(64.W))

  val block_start_addr = compressed_info_q.deq.bits.ip.ip
  val block_lit_src_start_addr = block_start_addr + BLOCK_HEADER_BYTES.U

  val dst_start_addr = compressed_info_q.deq.bits.dst.op

  val wksp_start_addr = compressed_info_q.deq.bits.wp.ip
  val block_lit_dst_start_addr = wksp_start_addr
  val dst_start_addr_per_block = compressed_info_q.deq.bits.dst.op + written_bytes

  ////////////////////////////////////////////////////////////////////////////
  // Huffman decompressor
  ////////////////////////////////////////////////////////////////////////////
  val huff_decomp = Module(new HufDecompressorRaw(zstdTop, cmd_que_depth))
  io.l2_huf_memhelpers <> huff_decomp.io.l2_memhelpers

  val huff_src_q = Module(new Queue(new DecompressPtrInfo, cmd_que_depth)).io
  val huff_dst_q = Module(new Queue(new DecompressPtrInfo, cmd_que_depth)).io
  val huff_return_q = Module(new Queue(new HufInfo, cmd_que_depth)).io
  val huff_done_q = Module(new Queue(Bool(), cmd_que_depth)).io

  val huff_fired = RegInit(false.B)
  val huff_fire = DecoupledHelper(huff_src_q.enq.ready,
                                  huff_dst_q.enq.ready,
                                  state === STATE_DECODE_LITERALS,
                                  !huff_fired,
                                  block_type===2.U)

  huff_src_q.enq.valid := huff_fire.fire(huff_src_q.enq.ready)
  huff_src_q.enq.bits.ip := block_lit_src_start_addr

  huff_dst_q.enq.valid := huff_fire.fire(huff_dst_q.enq.ready)
  huff_dst_q.enq.bits.ip := block_lit_dst_start_addr


  when (huff_fire.fire) {
    huff_fired := true.B
    CompressAccelLogger.logInfo("Huffman Decoder Initiated By Block Decoder!\n")
    CompressAccelLogger.logInfo("literal_src_start_addr: 0x%x\n", block_lit_src_start_addr)
    CompressAccelLogger.logInfo("literal_dst_start_addr: 0x%x\n", block_lit_dst_start_addr)
  }


  huff_decomp.io.src_info <> huff_src_q.deq
  huff_decomp.io.dst_info <> huff_dst_q.deq
  huff_return_q.enq <> huff_decomp.io.decomp_literal_info
  huff_done_q.enq <> huff_decomp.io.huff_finished

  val huff_done_fire = DecoupledHelper(
                        huff_return_q.deq.valid,
                        huff_done_q.deq.valid,
                        state === STATE_DECODE_LITERALS)

  huff_done_q.deq.ready := huff_done_fire.fire(huff_done_q.deq.valid)
  huff_return_q.deq.ready := huff_done_fire.fire(huff_return_q.deq.valid)

  val literal_comp_size = RegInit(0.U(64.W))
  val literal_decomp_size = RegInit(0.U(64.W))

  val nxt_literal_comp_size = huff_return_q.deq.bits.src_consumed_bytes
  val nxt_literal_decomp_size = huff_return_q.deq.bits.dst_written_bytes

  when (huff_done_fire.fire) {
    literal_comp_size := nxt_literal_comp_size
    literal_decomp_size := nxt_literal_decomp_size
    huff_fired := false.B

    CompressAccelLogger.logInfo("Huffman Decoding Finished!!\n")
    CompressAccelLogger.logInfo("Huffman consumed src: %d B\n", nxt_literal_comp_size)
    CompressAccelLogger.logInfo("Huffman written: %d B\n", nxt_literal_decomp_size)
  }

  // FSE and LZ77 decompression blocks start here
  //- Don't forget to disable writes to the cmpflag as it will stop the accelerator 
  // midway through computation

  val seq_src_start_addr = block_lit_src_start_addr + literal_comp_size
  val seq_src_end_addr = compressed_info_q.deq.bits.ip.ip + block_size + BLOCK_HEADER_BYTES.U
  val seq_dst_start_addr = block_lit_dst_start_addr + literal_decomp_size

  val print_helper = RegInit(false.B)
  when (state === STATE_DECODE_SEQUENCE && !print_helper) {
    print_helper := true.B

    CompressAccelLogger.logInfo("ZstdBlockDecompressor start STATE_DECODE_SEQUENCE\n")
    CompressAccelLogger.logInfo("seq_src_start_addr: 0x%x\n", seq_src_start_addr)
    CompressAccelLogger.logInfo("seq_src_end_addr: 0x%x\n", seq_src_end_addr)
    CompressAccelLogger.logInfo("seq_dst_start_addr: 0x%x\n", seq_dst_start_addr)
    CompressAccelLogger.logInfo("literal_comp_size: %d\n", literal_comp_size)
    CompressAccelLogger.logInfo("literal_decomp_size: %d\n", literal_decomp_size)
    CompressAccelLogger.logInfo("block_size: %d\n", block_size)
    CompressAccelLogger.logInfo("block_header_bytes: %d\n", BLOCK_HEADER_BYTES.U)
    CompressAccelLogger.logInfo("block_start_addr: 0x%x\n", compressed_info_q.deq.bits.ip.ip)
  }


  ////////// ********** ADDING SEQUENCE PARTS ********** //////////
  val LL_DT_ADDR_0 = seq_dst_start_addr
  val OFF_DT_ADDR_0 = seq_dst_start_addr + 513.U*8.U
  val ML_DT_ADDR_0 = seq_dst_start_addr + 513.U*8.U + 257.U*8.U
  val LL_DT_ADDR_1 = seq_dst_start_addr + 513.U*8.U + 257.U*8.U + 513.U*8.U
  val OFF_DT_ADDR_1 = seq_dst_start_addr + 513.U*8.U + 257.U*8.U + 513.U*8.U + 513.U*8.U
  val ML_DT_ADDR_1 = seq_dst_start_addr + 513.U*8.U + 257.U*8.U + 513.U*8.U + 513.U*8.U + 257.U*8.U

// Decode Table Builder
  val dt_builder = Module(new ZstdDTBuilder(256))
  
  val fse_trigger = RegInit(false.B)
  when (huff_done_fire.fire) {
    fse_trigger := true.B
  }

  when (fse_trigger && dt_builder.io.trigger_ready) {
    fse_trigger := false.B
  }

  val dt_addr = Wire(new DTAddressBundle)
  dt_addr.ll_dt_addr_0 := LL_DT_ADDR_0
  dt_addr.off_dt_addr_0 := OFF_DT_ADDR_0
  dt_addr.ml_dt_addr_0 := ML_DT_ADDR_0
  dt_addr.ll_dt_addr_1 := LL_DT_ADDR_1
  dt_addr.off_dt_addr_1 := OFF_DT_ADDR_1
  dt_addr.ml_dt_addr_1 := ML_DT_ADDR_1

  dt_builder.io.dt_addr := dt_addr
  dt_builder.io.trigger := fse_trigger
  dt_builder.io.bitstream_start := seq_src_start_addr
  dt_builder.io.bitstream_end := seq_src_end_addr
  dt_builder.io.output_file_pointer := dst_start_addr
  dt_builder.io.literal_start := block_lit_dst_start_addr

  val memloader_dtbuilder = Module(new MemLoader)
  io.l2_fse_memhelpers.read_dtbuild_userif <> memloader_dtbuilder.io.l2helperUser
  memloader_dtbuilder.io.src_info <> dt_builder.io.input_src_info
  dt_builder.io.input_stream <> memloader_dtbuilder.io.consumer

/* Use SRAM instead of L2 for DTs: Don't need to write to L2
  val dtBuilderWriter = Module(new ZstdDTBuilderWriter32)
  dtBuilderWriter.io.dt_entry <> dt_builder.io.dt_entry
  dtBuilderWriter.io.decompress_dest_info <> dt_builder.io.dt_dest_info
  dt_builder.io.bufs_completed := dtBuilderWriter.io.bufs_completed
  dt_builder.io.no_writes_inflight := dtBuilderWriter.io.no_writes_inflight
  io.l2_fse_memhelpers.write_dtbuild_userif <> dtBuilderWriter.io.l2helperUser
*/
  val ll_dt0 = SyncReadMem(513, UInt(64.W))
  val off_dt0 = SyncReadMem(257, UInt(64.W))
  val ml_dt0 = SyncReadMem(513, UInt(64.W))
  val ll_dt1 = SyncReadMem(513, UInt(64.W))
  val off_dt1 = SyncReadMem(257, UInt(64.W))
  val ml_dt1 = SyncReadMem(513, UInt(64.W))

  when(dt_builder.io.dt_entry_sram.dt_write){
    when(dt_builder.io.dt_entry_sram.dt_type===0.U){//LL
      when(dt_builder.io.dt_entry_sram.dt_parity===0.U){
        ll_dt0.write(dt_builder.io.dt_entry_sram.dt_entry_num,
          dt_builder.io.dt_entry_sram.dt_entry_content)
      }.otherwise{
        ll_dt1.write(dt_builder.io.dt_entry_sram.dt_entry_num,
          dt_builder.io.dt_entry_sram.dt_entry_content)
      }
    }.elsewhen(dt_builder.io.dt_entry_sram.dt_type===1.U){//OFF
      when(dt_builder.io.dt_entry_sram.dt_parity===0.U){
        off_dt0.write(dt_builder.io.dt_entry_sram.dt_entry_num,
          dt_builder.io.dt_entry_sram.dt_entry_content)
      }.otherwise{
        off_dt1.write(dt_builder.io.dt_entry_sram.dt_entry_num,
          dt_builder.io.dt_entry_sram.dt_entry_content)
      }
    }.elsewhen(dt_builder.io.dt_entry_sram.dt_type===2.U){//ML
      when(dt_builder.io.dt_entry_sram.dt_parity===0.U){
        ml_dt0.write(dt_builder.io.dt_entry_sram.dt_entry_num,
          dt_builder.io.dt_entry_sram.dt_entry_content)
      }.otherwise{
        ml_dt1.write(dt_builder.io.dt_entry_sram.dt_entry_num,
          dt_builder.io.dt_entry_sram.dt_entry_content)
      }
    }.otherwise{
      //Do nothing
    }    
  }

  // Decode Table Reader
  val dt_reader = Module(new ZstdDTReader(256))
  dt_builder.io.completion_dtreader := dt_reader.io.completion_flag
  dt_builder.io.table_in_use := dt_reader.io.table_in_use
  dt_reader.io.ll_dt_info := dt_builder.io.ll_dt_info
  dt_reader.io.off_dt_info := dt_builder.io.off_dt_info
  dt_reader.io.ml_dt_info := dt_builder.io.ml_dt_info
  dt_reader.io.dt_write_done := dt_builder.io.dt_info_valid
  dt_reader.io.bitstream_start_addr := dt_builder.io.next_bitstream_start
  dt_reader.io.bitstream_end_addr := dt_builder.io.next_bitstream_end
  dt_reader.io.num_sequences := dt_builder.io.num_sequences
  dt_reader.io.literal_start_addr := dt_builder.io.literal_start_addr_out
  dt_reader.io.output_start_addr := dt_builder.io.output_start_addr_out
  dt_reader.io.is_last_block := last_block
  //dt_reader.io.new_frame := FROM FRAME HEADER DECODER

  // Read ports
  val dataOut_ll_dt0 = Wire(UInt(64.W))
  val dataOut_ll_dt1 = Wire(UInt(64.W))
  val dataOut_ml_dt0 = Wire(UInt(64.W))
  val dataOut_ml_dt1 = Wire(UInt(64.W))
  val dataOut_off_dt0 = Wire(UInt(64.W))
  val dataOut_off_dt1 = Wire(UInt(64.W))
  dataOut_ll_dt0 := ll_dt0.read(
    dt_reader.io.ll_dt_entry_sram.dt_entry_num,
    dt_reader.io.ll_dt_entry_sram.dt_parity===0.U &&
    dt_reader.io.ll_dt_entry_sram.dt_read)
  dataOut_ll_dt1 := ll_dt1.read(
    dt_reader.io.ll_dt_entry_sram.dt_entry_num,
    dt_reader.io.ll_dt_entry_sram.dt_parity===1.U &&
    dt_reader.io.ll_dt_entry_sram.dt_read)
  dataOut_ml_dt0 := ml_dt0.read(
    dt_reader.io.ml_dt_entry_sram.dt_entry_num,
    dt_reader.io.ml_dt_entry_sram.dt_parity===0.U &&
    dt_reader.io.ml_dt_entry_sram.dt_read)
  dataOut_ml_dt1 := ml_dt1.read(
    dt_reader.io.ml_dt_entry_sram.dt_entry_num,
    dt_reader.io.ml_dt_entry_sram.dt_parity===1.U &&
    dt_reader.io.ml_dt_entry_sram.dt_read)
  dataOut_off_dt0 := off_dt0.read(
    dt_reader.io.off_dt_entry_sram.dt_entry_num,
    dt_reader.io.off_dt_entry_sram.dt_parity===0.U &&
    dt_reader.io.off_dt_entry_sram.dt_read)
  dataOut_off_dt1 := off_dt1.read(
    dt_reader.io.off_dt_entry_sram.dt_entry_num,
    dt_reader.io.off_dt_entry_sram.dt_parity===1.U &&
    dt_reader.io.off_dt_entry_sram.dt_read)

  dt_reader.io.ll_dt_entry_sram.dt_entry_content := Mux(
    dt_reader.io.ll_dt_entry_sram.dt_parity_delayed===0.U,
    dataOut_ll_dt0, dataOut_ll_dt1)
  dt_reader.io.ml_dt_entry_sram.dt_entry_content := Mux(
    dt_reader.io.ml_dt_entry_sram.dt_parity_delayed===0.U,
    dataOut_ml_dt0, dataOut_ml_dt1)
  dt_reader.io.off_dt_entry_sram.dt_entry_content := Mux(
    dt_reader.io.off_dt_entry_sram.dt_parity_delayed===0.U,
    dataOut_off_dt0, dataOut_off_dt1)
    
  val memloader_dtreader = Module(new MemLoader)
  io.l2_fse_memhelpers.read_dtread_userif <> memloader_dtreader.io.l2helperUser
  memloader_dtreader.io.src_info <> dt_reader.io.read_request
  dt_reader.io.mem_stream <> memloader_dtreader.io.consumer

// Sequence Executor
  val seqExecControl = Module(new ZstdSeqExecControl(256))
  dt_reader.io.seqexec_ready := seqExecControl.io.seqexec_ready
  seqExecControl.io.sequence_in <> dt_reader.io.sequence
  seqExecControl.io.literal_pointer := dt_reader.io.literal_start_addr_out
  seqExecControl.io.literal_pointer_valid := dt_reader.io.literal_start_addr_valid
  seqExecControl.io.literal_pointer_dtbuilder := dt_builder.io.literal_start_addr_out
  seqExecControl.io.file_pointer := dt_reader.io.output_start_addr_out
  seqExecControl.io.file_pointer_dtbuilder := dt_builder.io.output_file_pointer
  seqExecControl.io.num_sequences := dt_reader.io.num_sequences_out
  seqExecControl.io.num_literals := literal_decomp_size
  seqExecControl.io.is_last_block := last_block
  seqExecControl.io.dt_builder_completion := dt_builder.io.completion
  seqExecControl.io.zero_num_sequences := dt_builder.io.zero_num_sequences
  dt_builder.io.completion_seqexec := seqExecControl.io.completion

  val memloader_seqexec = Module(new MemLoader)
  io.l2_fse_memhelpers.read_seqexec_userif <> memloader_seqexec.io.l2helperUser
  if(nosnappy){
    memloader_seqexec.io.src_info <> seqExecControl.io.lit_src_info
  }else{
    when(io.ALGORITHM===0.U){
      memloader_seqexec.io.src_info <> seqExecControl.io.lit_src_info
    }.otherwise{
      memloader_seqexec.io.src_info <> io.snappy_decompress_src_info
    }
  }

  val seqExecLoader = Module(new ZstdSeqExecLoader(256))
  seqExecLoader.io.algorithm := io.ALGORITHM
  seqExecLoader.io.mem_stream <> memloader_seqexec.io.consumer
  seqExecLoader.io.command_in <> seqExecControl.io.seq_info
  seqExecLoader.io.num_literals_seqexec := seqExecControl.io.num_literals_seqexec
  seqExecLoader.io.completion_seqexec := seqExecControl.io.completion

  val seqExecHistoryLookup = Module(new ZstdOffchipHistoryLookup(65536)) //Size of On-chip SRAM in bytes. 65536 if 4096
  seqExecHistoryLookup.io.algorithm := io.ALGORITHM
  seqExecHistoryLookup.io.MAX_OFFSET_ALLOWED := io.MAX_OFFSET_ALLOWED
  seqExecHistoryLookup.io.internal_commands <> seqExecLoader.io.command_out
  seqExecHistoryLookup.io.literal_chunks <> seqExecLoader.io.literal_chunk
  if(nosnappy){
    seqExecHistoryLookup.io.decompress_dest_info <> seqExecControl.io.decompress_dest_info_histlookup
  }else{
    when(io.ALGORITHM===0.U){
      seqExecHistoryLookup.io.decompress_dest_info <> seqExecControl.io.decompress_dest_info_histlookup
    }.otherwise{
      seqExecHistoryLookup.io.decompress_dest_info <> io.snappy_decompress_dest_info_offchip
    }
  }
  
  io.l2_fse_memhelpers.read_histlookup_userif <> seqExecHistoryLookup.io.l2helperUser

  val seqExecWriter = Module(new ZstdSeqExecWriterSRAM32(65536))
  io.l2_fse_memhelpers.write_seqexec_userif <> seqExecWriter.io.l2helperUser
  ////////////////////////////////////////////////////////////////////////////
  // Raw/RLE decompressor
  ////////////////////////////////////////////////////////////////////////////
  val rawrle_decompressor = Module(new ZstdRawRLEDecompressor(256))
  rawrle_decompressor.io.ip := block_content_start_ip
  rawrle_decompressor.io.op := dst_start_addr_per_block
  rawrle_decompressor.io.block_type := block_type(0) //0: Raw, 1: RLE
  rawrle_decompressor.io.block_size := block_size
  rawrle_decompressor.io.enable := state===STATE_RAWRLE
  seqExecControl.io.rawrle_block_size := block_size
  seqExecControl.io.rawrle_completion := rawrle_decompressor.io.completion

  val memloader_rawrle = Module(new MemLoader)
  io.l2_rawrle_memhelpers.read_rawrle_userif <> memloader_rawrle.io.l2helperUser
  memloader_rawrle.io.src_info <> rawrle_decompressor.io.src_info
  rawrle_decompressor.io.mem_stream <> memloader_rawrle.io.consumer

  // Connect SeqExecWriter to seqExec modules or the raw/rle decompressor
  if(nosnappy){
    when(state===STATE_RAWRLE){
      seqExecWriter.io.internal_commands <> rawrle_decompressor.io.commands
      seqExecWriter.io.literal_chunks <> rawrle_decompressor.io.rawrle_data
      seqExecWriter.io.decompress_dest_info <> rawrle_decompressor.io.dest_info

      seqExecControl.io.bufs_completed := 0.U
      seqExecControl.io.no_writes_inflight := true.B
      rawrle_decompressor.io.bufs_completed := seqExecWriter.io.bufs_completed
      rawrle_decompressor.io.no_writes_inflight := seqExecWriter.io.no_writes_inflight
    }.otherwise{
      seqExecWriter.io.internal_commands <> seqExecHistoryLookup.io.internal_commands_out
      seqExecWriter.io.literal_chunks <> seqExecHistoryLookup.io.literal_chunks_out
      seqExecWriter.io.decompress_dest_info <> seqExecControl.io.decompress_dest_info

      seqExecControl.io.bufs_completed := seqExecWriter.io.bufs_completed
      seqExecControl.io.no_writes_inflight := seqExecWriter.io.no_writes_inflight
      rawrle_decompressor.io.bufs_completed := 0.U
      rawrle_decompressor.io.no_writes_inflight := true.B
    }
  }else{
    when(io.ALGORITHM===0.U){
      when(state===STATE_RAWRLE){
        seqExecWriter.io.internal_commands <> rawrle_decompressor.io.commands
        seqExecWriter.io.literal_chunks <> rawrle_decompressor.io.rawrle_data
        seqExecWriter.io.decompress_dest_info <> rawrle_decompressor.io.dest_info

        seqExecControl.io.bufs_completed := 0.U
        seqExecControl.io.no_writes_inflight := true.B
        rawrle_decompressor.io.bufs_completed := seqExecWriter.io.bufs_completed
        rawrle_decompressor.io.no_writes_inflight := seqExecWriter.io.no_writes_inflight
      }.otherwise{
        seqExecWriter.io.internal_commands <> seqExecHistoryLookup.io.internal_commands_out
        seqExecWriter.io.literal_chunks <> seqExecHistoryLookup.io.literal_chunks_out
        seqExecWriter.io.decompress_dest_info <> seqExecControl.io.decompress_dest_info

        seqExecControl.io.bufs_completed := seqExecWriter.io.bufs_completed
        seqExecControl.io.no_writes_inflight := seqExecWriter.io.no_writes_inflight
        rawrle_decompressor.io.bufs_completed := 0.U
        rawrle_decompressor.io.no_writes_inflight := true.B
      }
      io.snappy_bufs_completed := 0.U
      io.snappy_no_writes_inflight := true.B
    }.otherwise{
      seqExecWriter.io.internal_commands <> seqExecHistoryLookup.io.internal_commands_out
      seqExecWriter.io.literal_chunks <> seqExecHistoryLookup.io.literal_chunks_out
      seqExecWriter.io.decompress_dest_info <> io.snappy_decompress_dest_info

      io.snappy_bufs_completed := seqExecWriter.io.bufs_completed
      io.snappy_no_writes_inflight := seqExecWriter.io.no_writes_inflight
      seqExecControl.io.bufs_completed := 0.U
      seqExecControl.io.no_writes_inflight := true.B
      rawrle_decompressor.io.bufs_completed := 0.U
      rawrle_decompressor.io.no_writes_inflight := true.B
    }
  }
    
  ///////////////////////////////////////////////////////////////////////////
  // Fires when the decompression of the current block is finished
  // The ZstdContentDecompressor gets this info to decide whether to continue
  // or not
  val decompressed_info_q = Module(new Queue(new DecompressedBlockInfo, cmd_que_depth)).io
  io.decompressed_block_info <> decompressed_info_q.deq
  decompressed_info_q.enq.bits.last_block := last_block
  decompressed_info_q.enq.bits.block_type := block_type
  decompressed_info_q.enq.bits.block_size := block_size + BLOCK_HEADER_BYTES.U


  val block_done_fire = DecoupledHelper(
                          decompressed_info_q.enq.ready,
                          bhdr_stream.output_valid,
                          compressed_info_q.deq.valid,
                          state === STATE_DONE)
  decompressed_info_q.enq.valid := block_done_fire.fire(decompressed_info_q.enq.ready)
  bhdr_stream.output_ready := block_done_fire.fire(bhdr_stream.output_valid)
  compressed_info_q.deq.ready := block_done_fire.fire(compressed_info_q.deq.valid)

  when (block_done_fire.fire) {
    bhdr_stream.user_consumed_bytes := BLOCK_HEADER_BYTES.U

    CompressAccelLogger.logInfo("BlockDecompressor finished current block\n")
    CompressAccelLogger.logInfo("BlockDecompressor last_block: %d\n", last_block)
    CompressAccelLogger.logInfo("BlockDecompressor block_type: %d\n", block_type)
    CompressAccelLogger.logInfo("BlockDecompressor block_size: %d\n", block_size)
    CompressAccelLogger.logInfo("BlockDecompressor written_bytes: %d\n", written_bytes)
  }

  when (dt_reader.io.completion_flag && (state === STATE_DECODE_SEQUENCE)) {
    print_helper := false.B
  }

  decompressed_info_q.enq.bits.written_bytes := seqExecControl.io.seqCount

  ////////////////////////////////////////////////////////////////////////////
  // - Deque src_info && dst_info when block compression is finished
  // - Enque decompressed_info_q when block compression is finished (set the correct written_bytes)
  ////////////////////////////////////////////////////////////////////////////
  // Cycle time variables for result analysis
  val cycles_huff = RegInit(0.U(64.W))
  val cycles_fse = RegInit(0.U(64.W))
  val cycles_rawrle = RegInit(0.U(64.W))
  dontTouch(cycles_huff)
  dontTouch(cycles_fse)
  dontTouch(cycles_rawrle)
  when(state===STATE_DECODE_LITERALS){
    cycles_huff := cycles_huff + 1.U
  }
  when(state===STATE_DECODE_SEQUENCE){
    cycles_fse := cycles_fse + 1.U
  }
  when(state===STATE_RAWRLE){
    cycles_rawrle := cycles_rawrle + 1.U
  }
  when(state===STATE_DONE){
    CompressAccelLogger.logInfo("Block Decompression Done\n")
    CompressAccelLogger.logInfo("Huffman cycles: %d\n", cycles_huff)
    CompressAccelLogger.logInfo("FSE cycles: %d\n", cycles_fse)
    CompressAccelLogger.logInfo("Raw/RLE cycles: %d\n", cycles_rawrle)
  }
  
  switch (state) {
    is (STATE_IDLE) {
      when (decode_block_header_fire.fire) {
        state := STATE_DECODE_BLOCK_HEADER
      }
    }
    is (STATE_DECODE_BLOCK_HEADER) {
      when (bhdr_stream.output_valid && (avail_bytes >= BLOCK_HEADER_BYTES.U)) {
        state := Mux(block_type===2.U, STATE_DECODE_LITERALS, STATE_RAWRLE)
      }
    }
    is (STATE_DECODE_LITERALS) {
      when (huff_done_fire.fire) {
        state := STATE_DECODE_SEQUENCE
      }
    }
    is (STATE_DECODE_SEQUENCE) {
      when (seqExecControl.io.completion) {
        written_bytes := written_bytes + seqExecControl.io.seqCount
        state := STATE_DONE
      }
    }
    is (STATE_RAWRLE) {
      when (rawrle_decompressor.io.completion) {
        written_bytes := written_bytes + block_size
        state := STATE_DONE
      }
    }
    is (STATE_DONE) {
      when(last_block){
        written_bytes := 0.U
      }
      state := STATE_IDLE
    }
  }
}
