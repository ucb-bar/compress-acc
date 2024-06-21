package compressacc

import chisel3._
import chisel3.util._
import chisel3.{Printable, SyncReadMem}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants

// there is no offset here, because we always just add on at the current
// pointer
class HBSRAMWrite extends Bundle {
  val data = UInt(256.W)
  val valid_bytes = UInt(6.W)
}

class HBSRAMReadReq extends Bundle {
  val offset = UInt(48.W)
}

class HBSRAMReadResp extends Bundle {
  val data = UInt(256.W)
}

class HBSRAMAdvanceReadPtr extends Bundle {
  val advance_bytes = UInt(6.W)
}


/*
 * This block maintains the history of everything that has been
 * loaded from a buffer to compress. It runs at least one cycle
 * AHEAD of the state machine doing compression, so that handling
 * cases where the offset is less than 32B is easy.
 *
 * The write interface is "valid" only, it does not have the ability
 * to backpressure.
 */
class HistoryBufferSRAM()(implicit p: Parameters) extends Module with MemoryOpConstants {

  val io = IO(new Bundle {

    val writes_in = Flipped((Valid(new HBSRAMWrite)))

    // these valids are technically not necessary, but useful for debugging/
    // tracking purposes
    val read_req_in = Flipped((Valid(new HBSRAMReadReq)))
    val read_resp_out = (Valid(new HBSRAMReadResp))

    val read_advance_ptr = Flipped((Valid(new HBSRAMAdvanceReadPtr)))
  })

  println(s"HIST BUF OVERPROV FACTOR: ${p(LZ77HistBufOverProvisionFactor)}")

  val HIST_BUF_WIDTH = 32
  val HIST_BUF_ELEMS_PER_CHUNK = 4 * 512 * p(LZ77HistBufOverProvisionFactor)
  val HIST_SIZE_BYTES = HIST_BUF_WIDTH * HIST_BUF_ELEMS_PER_CHUNK
  val HIST_BUF_INDEX_WIDTH = log2Up(HIST_SIZE_BYTES)
  val BYTE_SIZE = 8
  println(s"HIST BUF WIDTH: ${HIST_BUF_WIDTH}")
  println(s"HIST BUF ELEMS PER CHUNK: ${HIST_BUF_ELEMS_PER_CHUNK}")
  println(s"TOTAL HIST BUF SIZE (B): ${HIST_SIZE_BYTES}")
  val recent_history_vec = Array.fill(HIST_BUF_WIDTH) {SyncReadMem(HIST_BUF_ELEMS_PER_CHUNK, UInt(BYTE_SIZE.W))}
  val read_indexing_vec = Wire(Vec(HIST_BUF_WIDTH, UInt(HIST_BUF_INDEX_WIDTH.W)))
  val read_ports_vec = Wire(Vec(HIST_BUF_WIDTH, UInt(BYTE_SIZE.W)))

  for (i <- 0 until HIST_BUF_WIDTH) {
    read_indexing_vec(i) := DontCare
  }

  // shift amount to remove memindex part of addr (low # of bits required to count HIST BUF WIDTH items)
  val MEMINDEX_BITS = log2Up(HIST_BUF_WIDTH)
  // mask to get only memindex part of addr
  val MEMINDEX_MASK = (1 << MEMINDEX_BITS) - 1


  // HANDLE READS:

  val read_addr_ptr = RegInit(0.U(HIST_BUF_INDEX_WIDTH.W))
  when (io.read_advance_ptr.valid) {
    // TODO: should an ongoing read account for advance_bytes?
    read_addr_ptr := read_addr_ptr + io.read_advance_ptr.bits.advance_bytes
  }

  val read_result_valid = RegNext(io.read_req_in.valid)
  val read_result_addr_ptr = RegNext(read_addr_ptr)
  val read_result_offset = RegNext(io.read_req_in.bits.offset)

  io.read_resp_out.valid := read_result_valid

  for (elemno <- 0 until HIST_BUF_WIDTH) {
    read_ports_vec(elemno) := recent_history_vec(elemno)(read_indexing_vec(elemno))
  }
  for (elemno <- 0 until HIST_BUF_WIDTH) {
    val read_memaddr = (read_addr_ptr + 32.U - io.read_req_in.bits.offset - elemno.U - 1.U) >> MEMINDEX_BITS
    val read_memno = (read_addr_ptr + 32.U - io.read_req_in.bits.offset - elemno.U - 1.U) & MEMINDEX_MASK.U
    read_indexing_vec(read_memno) := read_memaddr
    when (io.read_req_in.valid) {
      CompressAccelLogger.logInfo("issued hist_read(elemno:%d): from memno:%d,memaddr:%d\n", elemno.U, read_memno, read_memaddr)
    }
  }

  val read_output_vec = Wire(Vec(HIST_BUF_WIDTH, UInt(BYTE_SIZE.W)))

  for (elemno <- 0 until HIST_BUF_WIDTH) {
      // get read data
      val read_memaddr = (read_result_addr_ptr + 32.U - read_result_offset - elemno.U - 1.U) >> MEMINDEX_BITS
      val read_memno = (read_result_addr_ptr + 32.U - read_result_offset - elemno.U - 1.U) & MEMINDEX_MASK.U

      read_output_vec(elemno) := read_ports_vec(read_memno)
      val print_read_ports_vec = Wire(UInt(BYTE_SIZE.W))
      print_read_ports_vec := read_ports_vec(read_memno)
      when (read_result_valid) {
        CompressAccelLogger.logInfo("got hist_read(elemno:%d): from memno:%d,memaddr:%d = val:0x%x\n", elemno.U, read_memno, read_memaddr, print_read_ports_vec)
      }
  }

  io.read_resp_out.bits.data := Cat(read_output_vec)
  when (read_result_valid) {
    CompressAccelLogger.logInfo("read_resp: 0x%x\n", io.read_resp_out.bits.data)
  }

  // HANDLE WRITES:
  val write_addr_ptr = RegInit(0.U(HIST_BUF_INDEX_WIDTH.W))
  when (io.writes_in.valid) {
    write_addr_ptr := write_addr_ptr + io.writes_in.bits.valid_bytes
  }

  val write_indexing_vec = Wire(Vec(HIST_BUF_WIDTH, UInt(HIST_BUF_INDEX_WIDTH.W)))
  val write_ports_vec = Wire(Vec(HIST_BUF_WIDTH, UInt(BYTE_SIZE.W)))
  val write_ports_write_enable = Wire(Vec(HIST_BUF_WIDTH, Bool()))

  for (elemno <- 0 until HIST_BUF_WIDTH) {
    write_ports_write_enable(elemno) := false.B
    write_indexing_vec(elemno) := DontCare
    write_ports_vec(elemno) := DontCare
  }

  for (elemno <- 0 until HIST_BUF_WIDTH) {
    when (write_ports_write_enable(elemno)) {
      recent_history_vec(elemno)(write_indexing_vec(elemno)) := write_ports_vec(elemno)
    }
  }

  val recent_history_vec_next = Wire(Vec(HIST_BUF_WIDTH, UInt(BYTE_SIZE.W)))
  for (elemno <- 0 until HIST_BUF_WIDTH) {
    recent_history_vec_next(elemno) := DontCare
  }
  for (elemno <- 0 until HIST_BUF_WIDTH) {
    recent_history_vec_next(io.writes_in.bits.valid_bytes - elemno.U - 1.U) := io.writes_in.bits.data(((elemno+1) << 3) - 1, elemno << 3)
  }
  for (elemno <- 0 until HIST_BUF_WIDTH) {
    when (io.writes_in.valid && (elemno.U(MEMINDEX_BITS.W) < io.writes_in.bits.valid_bytes)) {
      val full_address = write_addr_ptr + io.writes_in.bits.valid_bytes - elemno.U - 1.U
      val memno = full_address & (MEMINDEX_MASK).U
      val memaddr = full_address >> MEMINDEX_BITS
      write_indexing_vec(memno) := memaddr
      write_ports_vec(memno) := recent_history_vec_next(elemno)
      write_ports_write_enable(memno) := true.B
      val print_recent_history_vec = Wire(UInt(BYTE_SIZE.W))
      //recent_history_vec_next(elemno))
      print_recent_history_vec := recent_history_vec_next(elemno)
      CompressAccelLogger.logInfo("do_write:mem(memno:%d,memaddr:%d): from rhvn(elemno:%d) = val:0x%x\n", memno, memaddr, elemno.U, print_recent_history_vec)
    }
  }
}
