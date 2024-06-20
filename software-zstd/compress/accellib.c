#include <assert.h>
#include <malloc.h>
#include <stddef.h>
#include <stdio.h>
#include <stdint.h>
#include <inttypes.h>
#include "accellib.h"
#include "rocc.h"

#define PAGESIZE_BYTES 4096


void ZstdCompressSetDynamicHistSize(uint64_t hist_sram_size_limit_bytes) {
#ifndef NOACCEL_DEBUG
    ROCC_INSTRUCTION_S(COMPRESS_OPCODE, 
                     hist_sram_size_limit_bytes,
                     FUNCT_SNPY_MAX_OFFSET_ALLOWED);
#endif
}

void ZstdCompressSetDynamicHashTableSizeLog2(uint64_t hash_table_size_log2) {
#ifndef NOACCEL_DEBUG
    ROCC_INSTRUCTION_S(COMPRESS_OPCODE, 
                       hash_table_size_log2,
                       FUNCT_SNPY_RUNTIME_HT_NUM_ENTRIES_LOG2);
#endif
}

void ZstdCompressSetLatencyInjectionInfo(uint64_t latency_inject_cycles, bool has_intermediate_cache) {
#ifndef NOACCEL_DEBUG
    ROCC_INSTRUCTION_SS(COMPRESS_OPCODE,
                        latency_inject_cycles,
                        (uint64_t)has_intermediate_cache,
                        FUNCT_LATENCY_INJECTION_INFO);
#endif
}

unsigned char * ZstdCompressAccelSetup(size_t write_region_size) {
#ifndef NOACCEL_DEBUG
    ROCC_INSTRUCTION(COMPRESS_OPCODE, COMPRESS_SFENCE);
#endif

    size_t regionsize = sizeof(char) * (write_region_size);

    unsigned char * fixed_alloc_region = (unsigned char*)memalign(PAGESIZE_BYTES, regionsize);
    for (uint64_t i = 0; i < regionsize; i += PAGESIZE_BYTES) {
        fixed_alloc_region[i] = 0;
    }

    uint64_t fixed_ptr_as_int = (uint64_t)fixed_alloc_region;

    assert((fixed_ptr_as_int & 0x7) == 0x0);

    printf("constructed %" PRIu64 " byte region, starting at 0x%016" PRIx64 ", paged-in, for accel\n", (uint64_t)regionsize, fixed_ptr_as_int);

    return fixed_alloc_region;
}


unsigned char * ZstdCompressWorkspaceSetup(size_t write_region_size) {
    size_t regionsize = sizeof(char) * (write_region_size);

    unsigned char * fixed_alloc_region = (unsigned char*)memalign(PAGESIZE_BYTES, regionsize);
    for (uint64_t i = 0; i < regionsize; i += PAGESIZE_BYTES) {
        fixed_alloc_region[i] = 0;
    }

    uint64_t fixed_ptr_as_int = (uint64_t)fixed_alloc_region;

    assert((fixed_ptr_as_int & 0x7) == 0x0);

    printf("constructed %" PRIu64 " byte region, starting at 0x%016" PRIx64 ", paged-in, for accel\n", (uint64_t)regionsize, fixed_ptr_as_int);

    return fixed_alloc_region;
}


volatile int ZstdBlockOnCompressCompletion(volatile int * completion_flag) {
    uint64_t retval;
#ifndef NOACCEL_DEBUG
    ROCC_INSTRUCTION_D(COMPRESS_OPCODE, retval, FUNCT_CHECK_COMPLETION);
#endif
    asm volatile ("fence");

#ifndef NOACCEL_DEBUG
    while (! *(completion_flag)) {
        asm volatile ("fence");
    }
#endif

    return *completion_flag;
}

void ZstdAccelCompressNonblocking(const unsigned char * src,
                                   const size_t srcSize, 
                                   unsigned char * litBuff,
                                   const size_t litBuffSize,
                                   unsigned char * seqBuff,
                                   const size_t seqBuffSize,
                                   unsigned char * dst, 
                                   const int clevel,
                                   int* success_flag) {
#ifndef NOACCEL_DEBUG
    ROCC_INSTRUCTION_SS(COMPRESS_OPCODE,
                        (uint64_t)src,
                        (uint64_t)srcSize,
                        FUNCT_ZSTD_SRC_INFO);

    ROCC_INSTRUCTION_SS(COMPRESS_OPCODE,
                        (uint64_t)litBuff,
                        (uint64_t)litBuffSize,
                        FUNCT_ZSTD_LIT_BUFF_INFO);

    ROCC_INSTRUCTION_SS(COMPRESS_OPCODE,
                        (uint64_t)seqBuff,
                        (uint64_t)seqBuffSize,
                        FUNCT_ZSTD_SEQ_BUFF_INFO);

    ROCC_INSTRUCTION_SS(COMPRESS_OPCODE,
                       (uint64_t)dst,
                       (uint64_t)success_flag,
                       FUNCT_ZSTD_DST_INFO);

    ROCC_INSTRUCTION_S(COMPRESS_OPCODE,
                      (uint64_t)clevel,
                      FUNCT_ZSTD_CLEVEL_INFO);
#endif
}

int ZstdAccelCompress(const unsigned char * src,
                      const size_t srcSize,
                      unsigned char * litBuff,
                      const size_t litBuffSize,
                      unsigned char * seqBuff,
                      const size_t seqBuffSize,
                      unsigned char * dst,
                      const int clevel) {
    int completion_flag = 0;

#ifdef NOACCEL_DEBUG
    printf("completion_flag addr : 0x%x\n", &completion_flag);
#endif

    ZstdAccelCompressNonblocking(src,
                                 srcSize,
                                 litBuff,
                                 litBuffSize,
                                 seqBuff,
                                 seqBuffSize,
                                 dst,
                                 clevel,
                                 &completion_flag);
    return ZstdBlockOnCompressCompletion(&completion_flag);
}
