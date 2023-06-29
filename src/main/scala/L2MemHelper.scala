package compressacc

import Chisel._
import chisel3.{Printable}
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, TLBPTWIO, TLB, MStatus, PRV}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.rocket.{RAS}
import freechips.rocketchip.tilelink._

case object CompressAccelTLB extends Field[Option[TLBConfig]](None)

class L2ReqInternal extends Bundle {
  val addr = UInt()
  val size = UInt()
  val data = UInt(width=256)
  val cmd = UInt()
}

class L2RespInternal extends Bundle {
  val data = UInt(width=256)
}

class L2InternalTracking extends Bundle {
  val addrindex = UInt(width=5)
  val tag = UInt()
}

class L2MemHelperBundle extends Bundle {
  val req = Decoupled(new L2ReqInternal)
  val resp = Flipped(Decoupled(new L2RespInternal))
  val no_memops_inflight = Input(Bool())
}


class L2MemHelper(printInfo: String = "", numOutstandingReqs: Int = 32, queueRequests: Boolean = false, queueResponses: Boolean = false, printWriteBytes: Boolean = false)(implicit p: Parameters) extends LazyModule {
  val numOutstandingRequestsAllowed = numOutstandingReqs
  val tlTagBits = log2Ceil(numOutstandingRequestsAllowed)


  lazy val module = new L2MemHelperModule(this, printInfo, queueRequests, queueResponses, printWriteBytes)
  val masterNode = TLClientNode(Seq(TLClientPortParameters(
    Seq(TLClientParameters(name = printInfo, sourceId = IdRange(0,
      numOutstandingRequestsAllowed)))
  )))
}

class L2MemHelperModule(outer: L2MemHelper, printInfo: String = "", queueRequests: Boolean = false, queueResponses: Boolean = false, printWriteBytes: Boolean = false)(implicit p: Parameters) extends LazyModuleImp(outer)
  with HasCoreParameters
  with MemoryOpConstants {

  val io = IO(new Bundle {
    val userif = Flipped(new L2MemHelperBundle)

    val sfence = Bool(INPUT)
    val ptw = new TLBPTWIO
    val status = Valid(new MStatus).flip
  })

  val (dmem, edge) = outer.masterNode.out.head

  val request_input = Wire(Decoupled(new L2ReqInternal))
  if (!queueRequests) {
    request_input <> io.userif.req
  } else {
    val requestQueue = Module(new Queue(new L2ReqInternal, 4))
    request_input <> requestQueue.io.deq
    requestQueue.io.enq <> io.userif.req
  }

  val response_output = Wire(Decoupled(new L2RespInternal))
  if (!queueResponses) {
    io.userif.resp <> response_output
  } else {
    val responseQueue = Module(new Queue(new L2RespInternal, 4))
    responseQueue.io.enq <> response_output
    io.userif.resp <> responseQueue.io.deq
  }

  val status = Reg(new MStatus)
  when (io.status.valid) {
    CompressAccelLogger.logInfo(printInfo + " setting status.dprv to: %x compare %x\n", io.status.bits.dprv, UInt(PRV.M))
    status := io.status.bits
  }

  val tlb = Module(new TLB(false, log2Ceil(coreDataBytes), p(CompressAccelTLB).get)(edge, p))
  tlb.io.req.valid := request_input.valid
  tlb.io.req.bits.vaddr := request_input.bits.addr
  tlb.io.req.bits.size := request_input.bits.size
  tlb.io.req.bits.cmd := request_input.bits.cmd
  tlb.io.req.bits.passthrough := Bool(false)
  val tlb_ready = tlb.io.req.ready && !tlb.io.resp.miss

  io.ptw <> tlb.io.ptw
  tlb.io.ptw.status := status
  tlb.io.sfence.valid := io.sfence
  tlb.io.sfence.bits.rs1 := Bool(false)
  tlb.io.sfence.bits.rs2 := Bool(false)
  tlb.io.sfence.bits.addr := UInt(0)
  tlb.io.sfence.bits.asid := UInt(0)
  tlb.io.kill := Bool(false)


  val outstanding_req_addr = Module(new Queue(new L2InternalTracking, outer.numOutstandingRequestsAllowed * 4))


  val tags_for_issue_Q = Module(new Queue(UInt(outer.tlTagBits.W), outer.numOutstandingRequestsAllowed * 2))
  tags_for_issue_Q.io.enq.valid := false.B

  val tags_init_reg = RegInit(0.U((outer.tlTagBits+1).W))
  when (tags_init_reg =/= (outer.numOutstandingRequestsAllowed).U) {
    tags_for_issue_Q.io.enq.bits := tags_init_reg
    tags_for_issue_Q.io.enq.valid := true.B
    when (tags_for_issue_Q.io.enq.ready) {
      CompressAccelLogger.logInfo(printInfo + " tags_for_issue_Q init with value %d\n", tags_for_issue_Q.io.enq.bits)
      tags_init_reg := tags_init_reg + 1.U
    }
  }

  val addr_mask_check = (UInt(0x1, 64.W) << request_input.bits.size) - UInt(1)
  val assertcheck = RegNext((!request_input.valid) || ((request_input.bits.addr & addr_mask_check) === UInt(0)))

  when (!assertcheck) {
    CompressAccelLogger.logInfo(printInfo + " L2IF: access addr must be aligned to write width\n")
  }
  assert(assertcheck,
    printInfo + " L2IF: access addr must be aligned to write width\n")

  val global_memop_accepted = RegInit(0.U(64.W))
  when (io.userif.req.fire) {
    global_memop_accepted := global_memop_accepted + 1.U
  }

  val global_memop_sent = RegInit(0.U(64.W))

  val global_memop_ackd = RegInit(0.U(64.W))

  val global_memop_resp_to_user = RegInit(0.U(64.W))

  io.userif.no_memops_inflight := global_memop_accepted === global_memop_ackd

  val free_outstanding_op_slots = (global_memop_sent - global_memop_ackd) < (1 << outer.tlTagBits).U
  val assert_free_outstanding_op_slots = (global_memop_sent - global_memop_ackd) <= (1 << outer.tlTagBits).U

  when (!assert_free_outstanding_op_slots) {
    CompressAccelLogger.logInfo(printInfo + " L2IF: Too many outstanding requests for tag count.\n")
  }
  assert(assert_free_outstanding_op_slots,
    printInfo + " L2IF: Too many outstanding requests for tag count.\n")

  when (request_input.fire) {
    global_memop_sent := global_memop_sent + 1.U
  }

  val sendtag = tags_for_issue_Q.io.deq.bits

  when (request_input.bits.cmd === M_XRD) {
    val (legal, bundle) = edge.Get(fromSource=sendtag,
                            toAddress=tlb.io.resp.paddr,
                            lgSize=request_input.bits.size)
    dmem.a.bits := bundle
  } .elsewhen (request_input.bits.cmd === M_XWR) {
    val (legal, bundle) = edge.Put(fromSource=sendtag,
                            toAddress=tlb.io.resp.paddr,
                            lgSize=request_input.bits.size,
                            data=request_input.bits.data << ((request_input.bits.addr(4, 0) << 3)))
    dmem.a.bits := bundle
  } .elsewhen (request_input.valid) {
    CompressAccelLogger.logInfo(printInfo + " ERR")
    assert(Bool(false), "ERR")
  }

  val tl_resp_queues = Vec.fill(outer.numOutstandingRequestsAllowed)(
    Module(new Queue(new L2RespInternal, 4, flow=true)).io)

  val current_request_tag_has_response_space = tl_resp_queues(tags_for_issue_Q.io.deq.bits).enq.ready

  val fire_req = DecoupledHelper(
    request_input.valid,
    dmem.a.ready,
    tlb_ready,
    outstanding_req_addr.io.enq.ready,
    free_outstanding_op_slots,
    tags_for_issue_Q.io.deq.valid,
    current_request_tag_has_response_space
  )

  outstanding_req_addr.io.enq.bits.addrindex := request_input.bits.addr & 0x1F.U
  outstanding_req_addr.io.enq.bits.tag := sendtag

  dmem.a.valid := fire_req.fire(dmem.a.ready)
  request_input.ready := fire_req.fire(request_input.valid)
  outstanding_req_addr.io.enq.valid := fire_req.fire(outstanding_req_addr.io.enq.ready)
  tags_for_issue_Q.io.deq.ready := fire_req.fire(tags_for_issue_Q.io.deq.valid)


  when (dmem.a.fire) {
    when (request_input.bits.cmd === M_XRD) {
      CompressAccelLogger.logInfo(printInfo + " L2IF: req(read) vaddr: 0x%x, paddr: 0x%x, wid: 0x%x, opnum: %d, sendtag: %d\n",
        request_input.bits.addr,
        tlb.io.resp.paddr,
        request_input.bits.size,
        global_memop_sent,
        sendtag)
    }
    when (request_input.bits.cmd === M_XWR) {
      CompressAccelLogger.logCritical(printInfo + " L2IF: req(write) vaddr: 0x%x, paddr: 0x%x, wid: 0x%x, data: 0x%x, opnum: %d, sendtag: %d\n",
        request_input.bits.addr,
        tlb.io.resp.paddr,
        request_input.bits.size,
        request_input.bits.data,
        global_memop_sent,
        sendtag)

      if (printWriteBytes) {
        for (i <- 0 until 32) {
          when (i.U < (1.U << request_input.bits.size)) {
            CompressAccelLogger.logInfo("WRITE_BYTE ADDR: 0x%x BYTE: 0x%x " + printInfo + "\n", request_input.bits.addr + i.U, (request_input.bits.data >> (i*8).U)(7, 0))
          }
        }
      }
    }
  }





  val selectQready = tl_resp_queues(dmem.d.bits.source).enq.ready

  val fire_actual_mem_resp = DecoupledHelper(
    selectQready,
    dmem.d.valid,
    tags_for_issue_Q.io.enq.ready
  )

  when (fire_actual_mem_resp.fire(tags_for_issue_Q.io.enq.ready)) {
    tags_for_issue_Q.io.enq.valid := true.B
    tags_for_issue_Q.io.enq.bits := dmem.d.bits.source
  }

  when (fire_actual_mem_resp.fire(tags_for_issue_Q.io.enq.ready) &&
    tags_for_issue_Q.io.enq.valid) {
      CompressAccelLogger.logInfo(printInfo + " tags_for_issue_Q add back tag %d\n", tags_for_issue_Q.io.enq.bits)
  }

  dmem.d.ready := fire_actual_mem_resp.fire(dmem.d.valid)

  for (i <- 0 until outer.numOutstandingRequestsAllowed) {
    tl_resp_queues(i).enq.valid := fire_actual_mem_resp.fire(selectQready) && (dmem.d.bits.source === i.U)
    tl_resp_queues(i).enq.bits.data := dmem.d.bits.data
  }




  val currentQueue = tl_resp_queues(outstanding_req_addr.io.deq.bits.tag)
  val queueValid = currentQueue.deq.valid

  val fire_user_resp = DecoupledHelper(
    queueValid,
    response_output.ready,
    outstanding_req_addr.io.deq.valid
  )

  val resultdata = currentQueue.deq.bits.data >> (outstanding_req_addr.io.deq.bits.addrindex << 3)

  response_output.bits.data := resultdata

  response_output.valid := fire_user_resp.fire(response_output.ready)
  outstanding_req_addr.io.deq.ready := fire_user_resp.fire(outstanding_req_addr.io.deq.valid)

  for (i <- 0 until outer.numOutstandingRequestsAllowed) {
    tl_resp_queues(i).deq.ready := fire_user_resp.fire(queueValid) && (outstanding_req_addr.io.deq.bits.tag === i.U)
  }


  when (dmem.d.fire) {
    when (edge.hasData(dmem.d.bits)) {
      CompressAccelLogger.logInfo(printInfo + " L2IF: resp(read) data: 0x%x, opnum: %d, gettag: %d\n",
        dmem.d.bits.data,
        global_memop_ackd,
        dmem.d.bits.source)
    } .otherwise {
      CompressAccelLogger.logInfo(printInfo + " L2IF: resp(write) opnum: %d, gettag: %d\n",
        global_memop_ackd,
        dmem.d.bits.source)
    }
  }

  when (response_output.fire) {
    CompressAccelLogger.logInfo(printInfo + " L2IF: realresp() data: 0x%x, opnum: %d, gettag: %d\n",
      resultdata,
      global_memop_resp_to_user,
      outstanding_req_addr.io.deq.bits.tag)
  }

  when (dmem.d.fire) {
    global_memop_ackd := global_memop_ackd + 1.U
  }

  when (response_output.fire) {
    global_memop_resp_to_user := global_memop_resp_to_user + 1.U
  }

}


