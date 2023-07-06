package compressacc

import Chisel._
import chisel3.{Printable}
import chisel3.experimental.DataMirror
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, TLBPTWIO, TLB, MStatus, PRV}
import freechips.rocketchip.util.DecoupledHelper
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.rocket.{RAS}
import freechips.rocketchip.tilelink._


class L2MemHelperLatencyInjection(printInfo: String = "", numOutstandingReqs: Int = 32, queueRequests: Boolean = false, queueResponses: Boolean = false, printWriteBytes: Boolean = false)(implicit p: Parameters) extends LazyModule {
  val numOutstandingRequestsAllowed = numOutstandingReqs
  val tlTagBits = log2Ceil(numOutstandingRequestsAllowed)


  lazy val module = new L2MemHelperLatencyInjectionModule(this, printInfo, queueRequests, queueResponses, printWriteBytes)
  val masterNode = TLClientNode(Seq(TLClientPortParameters(
    Seq(TLClientParameters(name = printInfo, sourceId = IdRange(0,
      numOutstandingRequestsAllowed)))
  )))
}

class L2MemHelperLatencyInjectionModule(outer: L2MemHelperLatencyInjection, printInfo: String = "", queueRequests: Boolean = false, queueResponses: Boolean = false, printWriteBytes: Boolean = false)(implicit p: Parameters) extends LazyModuleImp(outer)
  with HasCoreParameters
  with MemoryOpConstants {

  val io = IO(new Bundle {
    val userif = Flipped(new L2MemHelperBundle)
    val latency_inject_cycles = Input(UInt(64.W))

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


  val cur_cycle = RegInit(0.U(64.W))
  cur_cycle := cur_cycle + 1.U

  val release_cycle_q_depth = 2 * outer.numOutstandingRequestsAllowed

  val request_latency_injection_q = Module(new LatencyInjectionQueue(DataMirror.internal.chiselTypeClone[TLBundleA](dmem.a.bits), release_cycle_q_depth))

// val req_release_cycle_q = Module(new Queue(UInt(64.W), release_cycle_q_depth, flow=true))
// val req_q = Module(new Queue(DataMirror.internal.chiselTypeClone[TLBundleA](dmem.a.bits), release_cycle_q_depth, flow=true))

// req_release_cycle_q.io.enq.bits := cur_cycle + io.latency_inject_cycles

  request_latency_injection_q.io.latency_cycles := io.latency_inject_cycles

  when (request_input.bits.cmd === M_XRD) {
    val (legal, bundle) = edge.Get(fromSource=sendtag,
                            toAddress=tlb.io.resp.paddr,
                            lgSize=request_input.bits.size)

    request_latency_injection_q.io.enq.bits := bundle
// dmem.a.bits := bundle
  } .elsewhen (request_input.bits.cmd === M_XWR) {
    val (legal, bundle) = edge.Put(fromSource=sendtag,
                            toAddress=tlb.io.resp.paddr,
                            lgSize=request_input.bits.size,
                            data=request_input.bits.data << ((request_input.bits.addr(4, 0) << 3)))

    request_latency_injection_q.io.enq.bits := bundle
// dmem.a.bits := bundle
  } .elsewhen (request_input.valid) {
    CompressAccelLogger.logInfo(printInfo + " ERR")
    assert(Bool(false), "ERR")
  }

  val tl_resp_queues = Vec.fill(outer.numOutstandingRequestsAllowed)(
    Module(new Queue(new L2RespInternal, 4, flow=true)).io)

  val current_request_tag_has_response_space = tl_resp_queues(tags_for_issue_Q.io.deq.bits).enq.ready

  val fire_req = DecoupledHelper(
    request_input.valid,
    request_latency_injection_q.io.enq.ready,
    tlb_ready,
    outstanding_req_addr.io.enq.ready,
    free_outstanding_op_slots,
    tags_for_issue_Q.io.deq.valid,
    current_request_tag_has_response_space
  )

  outstanding_req_addr.io.enq.bits.addrindex := request_input.bits.addr & 0x1F.U
  outstanding_req_addr.io.enq.bits.tag := sendtag

  request_latency_injection_q.io.enq.valid := fire_req.fire(request_latency_injection_q.io.enq.ready)
  request_input.ready := fire_req.fire(request_input.valid)
  outstanding_req_addr.io.enq.valid := fire_req.fire(outstanding_req_addr.io.enq.ready)
  tags_for_issue_Q.io.deq.ready := fire_req.fire(tags_for_issue_Q.io.deq.valid)


  dmem.a <> request_latency_injection_q.io.deq

  when (dmem.a.fire) {
    when (request_input.bits.cmd === M_XRD) {
      CompressAccelLogger.logInfo(printInfo + " L2IF: req(read) vaddr: 0x%x, paddr: 0x%x, wid: 0x%x, opnum: %d, sendtag: %d\n",
        request_input.bits.addr,
        tlb.io.resp.paddr,
        request_input.bits.size,
        global_memop_sent,
        sendtag)
    }
  }

  when (fire_req.fire) {
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




  val response_latency_injection_q = Module(new LatencyInjectionQueue(DataMirror.internal.chiselTypeClone[TLBundleD](dmem.d.bits), release_cycle_q_depth))
  response_latency_injection_q.io.latency_cycles := io.latency_inject_cycles
  response_latency_injection_q.io.enq <> dmem.d



  val selectQready = tl_resp_queues(response_latency_injection_q.io.deq.bits.source).enq.ready

  val fire_actual_mem_resp = DecoupledHelper(
    selectQready,
    response_latency_injection_q.io.deq.valid,
    tags_for_issue_Q.io.enq.ready
  )

  when (fire_actual_mem_resp.fire(tags_for_issue_Q.io.enq.ready)) {
    tags_for_issue_Q.io.enq.valid := true.B
    tags_for_issue_Q.io.enq.bits := response_latency_injection_q.io.deq.bits.source
  }

  when (fire_actual_mem_resp.fire(tags_for_issue_Q.io.enq.ready) &&
    tags_for_issue_Q.io.enq.valid) {
      CompressAccelLogger.logInfo(printInfo + " tags_for_issue_Q add back tag %d\n", tags_for_issue_Q.io.enq.bits)
  }

  response_latency_injection_q.io.deq.ready := fire_actual_mem_resp.fire(response_latency_injection_q.io.deq.valid)

  for (i <- 0 until outer.numOutstandingRequestsAllowed) {
    tl_resp_queues(i).enq.valid := fire_actual_mem_resp.fire(selectQready) && (response_latency_injection_q.io.deq.bits.source === i.U)
    tl_resp_queues(i).enq.bits.data := response_latency_injection_q.io.deq.bits.data
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

  when (response_latency_injection_q.io.deq.fire) {
    global_memop_ackd := global_memop_ackd + 1.U
  }

  when (response_output.fire) {
    global_memop_resp_to_user := global_memop_resp_to_user + 1.U
  }

}