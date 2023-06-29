package compressacc

import Chisel._
import chisel3.{Printable, VecInit, dontTouch}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._
// Instead of right-shifting after consuming the stream, have to left-stream.
class BufferManagerReverse(l2bw: Int)(implicit p: Parameters) extends Module{
    val io = IO(new Bundle{
        val input_stream_deq = Decoupled(UInt(l2bw.W)).flip
        val bits_consumed = Input(UInt(log2Up(l2bw+1).W))
        val flush = Bool(INPUT)

        val most_recent_stream = Output(UInt(l2bw.W))
        val first_stream_received = Output(Bool())
        val available_bits = Output(UInt((log2Up(l2bw+1)+1).W))
    })
    // Reverse the order of bits
    val stream_before_reversed = Wire(UInt(l2bw.W))
    io.most_recent_stream := stream_before_reversed //Handle reverse reading in DTReader, not here.
                                
    val most_recent_buffer = RegInit(0.U(l2bw.W))
    val reservoir_buffer = RegInit(0.U(l2bw.W))
    val buffer_ptr = RegInit(0.U(log2Up(l2bw+1).W)) //log(l2bw)+1 //pointer for most_recent_buffer
    val bitmask = Wire(UInt(l2bw.W)) //used for bit selection
    val bitmask_reservoir = Wire(UInt(l2bw.W)) //used for bit selection
    /* If it is the first buffer received in DTBuilder,
    ** store the input stream from memloader to most_recent_buffer.
    ** Otherwise, store the input stream fro memloader to reservoir_buffer.
    */
    val buffer_ready = Wire(Bool())
    val before_first_stream = RegInit(true.B)
    io.first_stream_received := !before_first_stream
    val before_second_stream = RegInit(true.B)
    val reservoir_empty = Wire(Bool())
    val reservoir_empty_cond = !before_second_stream &&
        (buffer_ptr +& io.bits_consumed > l2bw.U)
    val prev_valid = RegInit(false.B)
    val prev_reservoir_empty = RegInit(false.B)
    when(!io.flush){
        prev_valid := io.input_stream_deq.valid
        prev_reservoir_empty := reservoir_empty
    }    
    reservoir_empty := reservoir_empty_cond || (!prev_valid && prev_reservoir_empty)
    buffer_ready := before_first_stream || before_second_stream ||
        ((!before_first_stream)&&(!before_second_stream)&&reservoir_empty)
    io.input_stream_deq.ready := buffer_ready
    
    when(io.flush){
        most_recent_buffer := 0.U
        reservoir_buffer := 0.U
        buffer_ptr := 0.U
        before_first_stream := true.B
        before_second_stream := true.B
    }.elsewhen(before_first_stream){
        when(io.input_stream_deq.valid && buffer_ready){
            most_recent_buffer := io.input_stream_deq.bits
            before_first_stream := false.B
        }
    }.otherwise{
        when(buffer_ptr === 0.U){
            stream_before_reversed := most_recent_buffer
        }.otherwise{
            val bits_recent = l2bw.U-buffer_ptr
            val bits_reservoir = buffer_ptr
            bitmask := ((1.U << bits_recent) - 1.U)<<bits_reservoir
            bitmask_reservoir := (1.U << bits_reservoir) - 1.U
            val data_from_recent = most_recent_buffer & bitmask
            val data_from_reservoir = (reservoir_buffer>>bits_recent) & bitmask_reservoir
            stream_before_reversed := data_from_recent + data_from_reservoir
        }
        /* Update reservoir_buffer and most_recent buffer
        ** Update buffer_ptr.
        */
        when(buffer_ptr +& io.bits_consumed <= l2bw.U){
            buffer_ptr := buffer_ptr +& io.bits_consumed
            most_recent_buffer := most_recent_buffer << io.bits_consumed    
        }.otherwise{
            val new_buffer_ptr = buffer_ptr +& io.bits_consumed - l2bw.U
            most_recent_buffer := reservoir_buffer << new_buffer_ptr
            // reservoir_buffer will fetch io.input_stream_deq.bits
            buffer_ptr := new_buffer_ptr
        }
        // get reservoir_buffer
        when(io.input_stream_deq.valid && buffer_ready){
            reservoir_buffer := io.input_stream_deq.bits
            before_second_stream := false.B
        }
    }

    //Count the bits received & consumed to inform data availability
    val bits_count = RegInit(0.U((log2Up(l2bw+1)+1).W))
    when(io.flush){
        bits_count := 0.U
    }.elsewhen(!io.flush && io.input_stream_deq.valid && buffer_ready){
        bits_count := bits_count + l2bw.U - io.bits_consumed
    }.otherwise{
        bits_count := bits_count - io.bits_consumed
    }
    io.available_bits := bits_count
    dontTouch(bits_count)

    // Prevent floating wire
    when(buffer_ptr === 0.U){
        bitmask := 0.U
        bitmask_reservoir := 0.U
    }
}
