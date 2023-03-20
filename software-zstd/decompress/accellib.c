#include <assert.h>
#include <malloc.h>
#include <stdio.h>
#include <stdint.h>
#include <inttypes.h>
#include "accellib.h"

#define PAGESIZE_BYTES 4096

// Zstd Functions

void ZStdDecompressSetDynamicHistSize(uint64_t hist_sram_size_limit_bytes) {
    ROCC_INSTRUCTION_S(DECOMPRESS_OPCODE, hist_sram_size_limit_bytes, ZSTD_DECOMPRESS_FUNCT_SET_ONCHIP_HIST);
}

unsigned char * ZStdDecompressWorkspaceSetup(size_t workspace_size) {
  size_t regionsize = sizeof(char) * (workspace_size);
  unsigned char * fixed_alloc_region = (unsigned char*)memalign(PAGESIZE_BYTES, regionsize);

  for (uint64_t i = 0; i < regionsize; i += PAGESIZE_BYTES) {
      fixed_alloc_region[i] = 0;
  }

  uint64_t fixed_ptr_as_int = (uint64_t)fixed_alloc_region;

  assert((fixed_ptr_as_int & 0x7) == 0x0);

  printf("constructed %" PRIu64 " byte region, starting at 0x%016" PRIx64 ", paged-in, for accel\n",
          (uint64_t)regionsize, fixed_ptr_as_int);

  return fixed_alloc_region;
}

unsigned char * ZStdDecompressAccelSetup(size_t write_region_size, uint64_t hist_sram_size_limit_bytes) {
#ifndef NOACCEL_DEBUG
    ROCC_INSTRUCTION(DECOMPRESS_OPCODE, DECOMPRESS_FUNCT_SFENCE);
    ZStdDecompressSetDynamicHistSize(hist_sram_size_limit_bytes);
#endif

    size_t regionsize = sizeof(char) * (write_region_size);

    unsigned char* fixed_alloc_region = (unsigned char*)memalign(PAGESIZE_BYTES, regionsize);
    for (uint64_t i = 0; i < regionsize; i += PAGESIZE_BYTES) {
        fixed_alloc_region[i] = 0;
    }

    uint64_t fixed_ptr_as_int = (uint64_t)fixed_alloc_region;

    assert((fixed_ptr_as_int & 0x7) == 0x0);

    printf("constructed %" PRIu64 " byte region, starting at 0x%016" PRIx64 ", paged-in, for accel\n",
            (uint64_t)regionsize, fixed_ptr_as_int);

    return fixed_alloc_region;
}

volatile int ZStdAccelBlockOnUncompressCompletion(volatile int * completion_flag) {
    uint64_t retval;
#ifndef NOACCEL_DEBUG
    ROCC_INSTRUCTION_D(DECOMPRESS_OPCODE, retval, ZSTD_DECOMPRESS_FUNCT_CHECK_COMPLETION);
#endif
    asm volatile ("fence");

#ifndef NOACCEL_DEBUG
    while (! *(completion_flag)) {
        asm volatile ("fence");
    }
#endif
    return *completion_flag;
}

void ZStdAccelUncompressNonblocking(const unsigned char* compressed, 
                                      size_t compressed_length, 
                                      unsigned char* workspace,
                                      unsigned char* uncompressed, 
                                      int* success_flag) {
#ifndef NOACCEL_DEBUG
    ROCC_INSTRUCTION_S(DECOMPRESS_OPCODE,
                       (uint64_t)ZSTD_ALGORITHM,
                       DECOMPRESS_FUNCT_ALGORITHM);
    ROCC_INSTRUCTION_SS(DECOMPRESS_OPCODE,
                        (uint64_t)compressed,
                        (uint64_t)compressed_length,
                        ZSTD_DECOMPRESS_FUNCT_SRC_INFO);

    ROCC_INSTRUCTION_S(DECOMPRESS_OPCODE,
                       (uint64_t)workspace,
                       ZSTD_DECOMPRESS_FUNCT_WKSP_INFO);

    ROCC_INSTRUCTION_SS(DECOMPRESS_OPCODE,
                        (uint64_t)uncompressed,
                        (uint64_t)success_flag,
                        ZSTD_DECOMPRESS_FUNCT_DST_INFO);
#endif
}

int ZStdAccelUncompress(const unsigned char* compressed, 
                           size_t compressed_length, 
                           unsigned char* workspace,
                           unsigned char* uncompressed) {
    int completion_flag = 0;

#ifdef NOACCEL_DEBUG
    printf("completion_flag addr : 0x%x\n", &completion_flag);
#endif

    ZStdAccelUncompressNonblocking(compressed, 
                                  compressed_length, 
                                  workspace,
                                  uncompressed, 
                                  &completion_flag);
    return ZStdAccelBlockOnUncompressCompletion(&completion_flag);
}

// Snappy Functions

void SnappyDecompressSetDynamicHistSize(uint64_t sram_size_limit_bytes) {
    ROCC_INSTRUCTION_S(DECOMPRESS_OPCODE, sram_size_limit_bytes, SNAPPY_DECOMPRESS_FUNCT_SET_ONCHIP_HIST);
}


unsigned char * SnappyDecompressAccelSetup(size_t write_region_size, uint64_t sram_size_limit_bytes) {
#ifndef NOACCEL_DEBUG
    ROCC_INSTRUCTION(DECOMPRESS_OPCODE, DECOMPRESS_FUNCT_SFENCE);
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
    ROCC_INSTRUCTION_D(DECOMPRESS_OPCODE, retval, SNAPPY_DECOMPRESS_FUNCT_CHECK_COMPLETION);
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
    ROCC_INSTRUCTION_S(DECOMPRESS_OPCODE, (uint64_t)SNAPPY_ALGORITHM, DECOMPRESS_FUNCT_ALGORITHM);
    ROCC_INSTRUCTION_SS(DECOMPRESS_OPCODE, (uint64_t)compressed, (uint64_t)compressed_length, SNAPPY_DECOMPRESS_FUNCT_SRC_INFO);
    ROCC_INSTRUCTION_SS(DECOMPRESS_OPCODE, (uint64_t)uncompressed, (uint64_t)success_flag, SNAPPY_DECOMPRESS_FUNCT_DEST_INFO_AND_START);
#endif
}

bool SnappyAccelRawUncompress(const unsigned char* compressed, size_t compressed_length, unsigned char* uncompressed) {
    bool completion_flag = false;
    SnappyAccelRawUncompressNonblocking(compressed, compressed_length, uncompressed, &completion_flag);
    return BlockOnUncompressCompletion(&completion_flag);
}