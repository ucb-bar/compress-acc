#include <assert.h>
#include <malloc.h>
#include <stddef.h>
#include <stdio.h>
#include <stdint.h>
#include <inttypes.h>
#include "accellib.h"
#include "rocc.h"

#define PAGESIZE_BYTES 4096


unsigned char * ZstdCompressAccelSetup(size_t write_region_size, uint64_t hist_sram_size_limit_bytes) {
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

//////////////////////////////////////////////////////////////////////////////

void SnappyCompressSetDynamicHistSize(uint64_t hist_sram_size_limit_bytes) {
    ROCC_INSTRUCTION_S(COMPRESS_OPCODE, hist_sram_size_limit_bytes, FUNCT_SNPY_MAX_OFFSET_ALLOWED);
}

volatile int SnappyBlockOnCompressCompletion(volatile int * completion_flag) {
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

unsigned char * SnappyCompressAccelSetup(size_t write_region_size, uint64_t hist_sram_size_limit_bytes) {
#ifndef NOACCEL_DEBUG
    ROCC_INSTRUCTION(COMPRESS_OPCODE, COMPRESS_SFENCE);
    SnappyCompressSetDynamicHistSize(hist_sram_size_limit_bytes);
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

void SnappyAccelCompressNonblocking(const unsigned char *src,
                                    const size_t srcSize,
                                    unsigned char *dst,
                                    int *success_flag) {
  ROCC_INSTRUCTION_SS(COMPRESS_OPCODE,
                      (uint64_t)src,
                      (uint64_t)srcSize,
                      FUNCT_SNPY_SRC_INFO);

  ROCC_INSTRUCTION_SS(COMPRESS_OPCODE,
                      (uint64_t)dst,
                      (uint64_t)success_flag,
                      FUNCT_SNPY_DST_INFO);
}

int SnappyAccelCompress(const unsigned char *src,
                        const size_t srcSize,
                        unsigned char *dst) {

  int completion_flag = 0;

#ifdef NOACCEL_DEBUG
  printf("completion_flag addr : 0x%x\n", &completion_flag);
#endif

  SnappyAccelCompressNonblocking(src, srcSize, dst, &completion_flag);

  return SnappyBlockOnCompressCompletion(&completion_flag);
}





void SnappyDecompressSetDynamicHistSize(uint64_t sram_size_limit_bytes) {
    ROCC_INSTRUCTION_S(SNAPPY_DECOMPRESS_OPCODE, sram_size_limit_bytes, SNAPPY_DECOMPRESS_FUNCT_SET_ONCHIP_HIST);
}


unsigned char * SnappyDecompressAccelSetup(size_t write_region_size, uint64_t sram_size_limit_bytes) {
#ifndef NOACCEL_DEBUG
    ROCC_INSTRUCTION(SNAPPY_DECOMPRESS_OPCODE, SNAPPY_DECOMPRESS_FUNCT_SFENCE);
    SnappyDecompressSetDynamicHistSize(sram_size_limit_bytes);
#endif

    size_t regionsize = sizeof(char) * (write_region_size);
    //size_t regionsize = sizeof(unsigned char) * (PAGESIZE_BYTES);

    unsigned char * fixed_alloc_region = (unsigned char*)memalign(PAGESIZE_BYTES, regionsize);
    for (uint64_t i = 0; i < regionsize; i += PAGESIZE_BYTES) {
        fixed_alloc_region[i] = 0;
    }

    uint64_t fixed_ptr_as_int = (uint64_t)fixed_alloc_region;

    assert((fixed_ptr_as_int & 0x7) == 0x0);

    printf("constructed %" PRIu64 " byte region, starting at 0x%016" PRIx64 ", paged-in, for accel\n", (uint64_t)regionsize, fixed_ptr_as_int);

    return fixed_alloc_region;
}

volatile bool BlockOnUncompressCompletion(volatile bool * completion_flag) {
    uint64_t retval;
#ifndef NOACCEL_DEBUG
    ROCC_INSTRUCTION_D(SNAPPY_DECOMPRESS_OPCODE, retval, SNAPPY_DECOMPRESS_FUNCT_CHECK_COMPLETION);
#endif
    asm volatile ("fence");

#ifndef NOACCEL_DEBUG
    while (! *(completion_flag)) {
        asm volatile ("fence");
    }
#endif
    return *completion_flag;
}

void SnappyAccelRawUncompressNonblocking(const unsigned char* compressed, size_t compressed_length, unsigned char* uncompressed, bool* success_flag) {
#ifndef NOACCEL_DEBUG
    ROCC_INSTRUCTION_SS(SNAPPY_DECOMPRESS_OPCODE, (uint64_t)compressed, (uint64_t)compressed_length, SNAPPY_DECOMPRESS_FUNCT_SRC_INFO);
    ROCC_INSTRUCTION_SS(SNAPPY_DECOMPRESS_OPCODE, (uint64_t)uncompressed, (uint64_t)success_flag, SNAPPY_DECOMPRESS_FUNCT_DEST_INFO_AND_START);
#endif
}

bool SnappyAccelRawUncompress(const unsigned char* compressed, size_t compressed_length, unsigned char* uncompressed) {
    bool completion_flag = false;
    SnappyAccelRawUncompressNonblocking(compressed, compressed_length, uncompressed, &completion_flag);
    return BlockOnUncompressCompletion(&completion_flag);
}

