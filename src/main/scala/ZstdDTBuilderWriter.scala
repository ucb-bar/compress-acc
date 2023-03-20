package compressacc

import Chisel._
import chisel3.{Printable, VecInit}
import freechips.rocketchip.tile._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tilelink._
/* 
class ZstdSeqInfo: {is_match, ll, ml, offset}
class LiteralChunk extends Bundle {
  val chunk_data = UInt(OUTPUT, 256.W)
  // could be 7.W but make it easy for now
  val chunk_size_bytes = UInt(OUTPUT, 9.W)
}
*/
class DTEntryChunk extends Bundle{
	val chunk_data = UInt(OUTPUT, 128.W)
	val chunk_size_bytes = UInt(OUTPUT, 8.W)
	val tableType = UInt(2.W) //0: LL, 1: Off, 2: ML
	val is_final_entry = Bool() //Indicates the final entry of the last DT(probably ML DT)
}
// Basically, ZstdDTBuilderWriter will be a ZstdSeqExecWriter with only literal writes
class ZstdDTBuilderWriter32()(implicit p: Parameters) extends Module with MemoryOpConstants {
	val io = IO(new Bundle {
		// Inputs from ZstdDTBuilder
		val dt_entry = (Decoupled(new DTEntryChunk32)).flip
		val decompress_dest_info = (Decoupled(new SnappyDecompressDestInfo)).flip

		// Output to outer mem
		val l2helperUser = new L2MemHelperBundle
		// Feedback outputs to ZstdDTBuilder
		val bufs_completed = Output(UInt(64.W))
		val no_writes_inflight = Output(Bool())
	})
	val memwriter = Module(new ZstdSeqExecMemwriter32)
	io.l2helperUser <> memwriter.io.l2io
	memwriter.io.decompress_dest_info <> io.decompress_dest_info

	// write into the recent hist + memwriter
	val fire_write = DecoupledHelper(
		io.dt_entry.valid,
		memwriter.io.memwrites_in.ready
	)
	io.dt_entry.ready := fire_write.fire(io.dt_entry.valid)

	val literal_data = io.dt_entry.bits.chunk_data
	val write_num_bytes = io.dt_entry.bits.chunk_size_bytes

	val recent_history_vec = Array.fill(32) {Mem(2, UInt(8.W))}
	val read_indexing_vec = Wire(Vec(32, UInt(16.W)))
	val read_ports_vec = Wire(Vec(32, UInt(8.W)))
	val write_indexing_vec = Wire(Vec(32, UInt(16.W)))
	val write_ports_vec = Wire(Vec(32, UInt(8.W)))
	val write_ports_write_enable = Wire(Vec(32, Bool()))

	for (elemno <- 0 until 32) {
		write_ports_write_enable(elemno) := false.B
	}

	for (elemno <- 0 until 32) {
		read_ports_vec(elemno) := recent_history_vec(elemno)(read_indexing_vec(elemno))
		when (write_ports_write_enable(elemno)) {
			recent_history_vec(elemno)(write_indexing_vec(elemno)) := write_ports_vec(elemno)
		}
	}
	val recent_history_vec_next = Wire(Vec(32, UInt(8.W)))
	val addr_base_ptr = RegInit(0.U(16.W))

	// for literal writes, advance pointer and write to the mems
	for (elemno <- 0 until 32) {
		recent_history_vec_next(write_num_bytes - UInt(elemno) - 1.U) := literal_data(((elemno+1) << 3) - 1, elemno << 3)
	}

	for (elemno <- 0 until 32) {
		when (fire_write.fire && (elemno.U(5.W) < write_num_bytes)) {
			val full_address = addr_base_ptr + write_num_bytes - elemno.U - 1.U
			val memno = full_address & (0x1F).U
			val memaddr = full_address >> 5
			write_indexing_vec(memno) := memaddr
			write_ports_vec(memno) := recent_history_vec_next(elemno)
			write_ports_write_enable(memno) := true.B
		}
	}

	when (fire_write.fire) {
		addr_base_ptr := addr_base_ptr + write_num_bytes
	}

	memwriter.io.memwrites_in.bits.data := Cat(
		recent_history_vec_next(31),
        recent_history_vec_next(30),
        recent_history_vec_next(29),
        recent_history_vec_next(28),
        recent_history_vec_next(27),
        recent_history_vec_next(26),
        recent_history_vec_next(25),
        recent_history_vec_next(24),
        recent_history_vec_next(23),
        recent_history_vec_next(22),
        recent_history_vec_next(21),
        recent_history_vec_next(20),
        recent_history_vec_next(19),
        recent_history_vec_next(18),
        recent_history_vec_next(17),
        recent_history_vec_next(16),
		recent_history_vec_next(15),
		recent_history_vec_next(14),
		recent_history_vec_next(13),
		recent_history_vec_next(12),
		recent_history_vec_next(11),
		recent_history_vec_next(10),
		recent_history_vec_next(9),
		recent_history_vec_next(8),
		recent_history_vec_next(7),
		recent_history_vec_next(6),
		recent_history_vec_next(5),
		recent_history_vec_next(4),
		recent_history_vec_next(3),
		recent_history_vec_next(2),
		recent_history_vec_next(1),
		recent_history_vec_next(0)
	)

	memwriter.io.memwrites_in.bits.validbytes := write_num_bytes
	memwriter.io.memwrites_in.bits.end_of_message := true.B //io.dt_entry.bits.is_final_entry
	memwriter.io.memwrites_in.valid := fire_write.fire(memwriter.io.memwrites_in.ready)
	io.bufs_completed := memwriter.io.bufs_completed
	io.no_writes_inflight := memwriter.io.no_writes_inflight
}
class ZstdDTBuilderWriter()(implicit p: Parameters) extends Module with MemoryOpConstants {
	val io = IO(new Bundle {
		// Inputs from ZstdDTBuilder
		val dt_entry = (Decoupled(new DTEntryChunk)).flip
		val decompress_dest_info = (Decoupled(new SnappyDecompressDestInfo)).flip

		// Output to outer mem
		val l2helperUser = new L2MemHelperBundle
		// Feedback outputs to ZstdDTBuilder
		val bufs_completed = Output(UInt(64.W))
		val no_writes_inflight = Output(Bool())
	})
	val memwriter = Module(new ZstdSeqExecMemwriter)
	io.l2helperUser <> memwriter.io.l2io
	memwriter.io.decompress_dest_info <> io.decompress_dest_info

	// write into the recent hist + memwriter
	val fire_write = DecoupledHelper(
		io.dt_entry.valid,
		memwriter.io.memwrites_in.ready
	)
	io.dt_entry.ready := fire_write.fire(io.dt_entry.valid)

	val literal_data = io.dt_entry.bits.chunk_data
	val write_num_bytes = io.dt_entry.bits.chunk_size_bytes

	val recent_history_vec = Array.fill(16) {Mem(2, UInt(8.W))}
	val read_indexing_vec = Wire(Vec(16, UInt(16.W)))
	val read_ports_vec = Wire(Vec(16, UInt(8.W)))
	val write_indexing_vec = Wire(Vec(16, UInt(16.W)))
	val write_ports_vec = Wire(Vec(16, UInt(8.W)))
	val write_ports_write_enable = Wire(Vec(16, Bool()))

	for (elemno <- 0 until 16) {
		write_ports_write_enable(elemno) := false.B
	}

	for (elemno <- 0 until 16) {
		read_ports_vec(elemno) := recent_history_vec(elemno)(read_indexing_vec(elemno))
		when (write_ports_write_enable(elemno)) {
			recent_history_vec(elemno)(write_indexing_vec(elemno)) := write_ports_vec(elemno)
		}
	}
	val recent_history_vec_next = Wire(Vec(16, UInt(8.W)))
	val addr_base_ptr = RegInit(0.U(16.W))

	// for literal writes, advance pointer and write to the mems
	for (elemno <- 0 until 16) {
		recent_history_vec_next(write_num_bytes - UInt(elemno) - 1.U) := literal_data(((elemno+1) << 3) - 1, elemno << 3)
	}

	for (elemno <- 0 until 16) {
		when (fire_write.fire && (elemno.U(5.W) < write_num_bytes)) {
			val full_address = addr_base_ptr + write_num_bytes - elemno.U - 1.U
			val memno = full_address & (0xF).U
			val memaddr = full_address >> 4
			write_indexing_vec(memno) := memaddr
			write_ports_vec(memno) := recent_history_vec_next(elemno)
			write_ports_write_enable(memno) := true.B
		}
	}

	when (fire_write.fire) {
		addr_base_ptr := addr_base_ptr + write_num_bytes
	}

	memwriter.io.memwrites_in.bits.data := Cat(
		recent_history_vec_next(15),
		recent_history_vec_next(14),
		recent_history_vec_next(13),
		recent_history_vec_next(12),
		recent_history_vec_next(11),
		recent_history_vec_next(10),
		recent_history_vec_next(9),
		recent_history_vec_next(8),
		recent_history_vec_next(7),
		recent_history_vec_next(6),
		recent_history_vec_next(5),
		recent_history_vec_next(4),
		recent_history_vec_next(3),
		recent_history_vec_next(2),
		recent_history_vec_next(1),
		recent_history_vec_next(0)
	)

	memwriter.io.memwrites_in.bits.validbytes := write_num_bytes
	memwriter.io.memwrites_in.bits.end_of_message := true.B //io.dt_entry.bits.is_final_entry
	memwriter.io.memwrites_in.valid := fire_write.fire(memwriter.io.memwrites_in.ready)
	io.bufs_completed := memwriter.io.bufs_completed
	io.no_writes_inflight := memwriter.io.no_writes_inflight
}
