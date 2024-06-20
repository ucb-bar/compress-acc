#ifndef __ACCEL_H
#define __ACCEL_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "rocc.h"

#define COMPRESS_OPCODE 2

#define COMPRESS_SFENCE 0
#define FUNCT_ZSTD_SRC_INFO 1
#define FUNCT_ZSTD_LIT_BUFF_INFO 2
#define FUNCT_ZSTD_SEQ_BUFF_INFO 3
#define FUNCT_ZSTD_DST_INFO 4
#define FUNCT_ZSTD_CLEVEL_INFO 5
#define FUNCT_SNPY_SRC_INFO 6
#define FUNCT_SNPY_DST_INFO 7
#define FUNCT_SNPY_MAX_OFFSET_ALLOWED 8
#define FUNCT_SNPY_RUNTIME_HT_NUM_ENTRIES_LOG2 9
#define FUNCT_LATENCY_INJECTION_INFO 10
#define FUNCT_CHECK_COMPLETION 11


typedef struct {
  uint64_t cycles;
  bool has_cache;
} latency_info_t ;


void ZstdCompressSetDynamicHistSize(uint64_t hist_sram_size_limit_bytes);

void ZstdCompressSetDynamicHashTableSizeLog2(uint64_t hash_table_size_log2);

void ZstdCompressSetLatencyInjectionInfo(uint64_t latency_inject_cycles, bool has_intermediate_cache);

unsigned char * ZstdCompressAccelSetup(size_t write_region_size);

unsigned char * ZstdCompressWorkspaceSetup(size_t write_region_size);

void ZstdAccelCompressNonblocking(const unsigned char * src,
                                   const size_t srcSize,
                                   unsigned char * litBuff,
                                   const size_t litBuffSize,
                                   unsigned char * seqBuff,
                                   const size_t seqBuffSize,
                                   unsigned char * dst,
                                   const int clevel,
                                   int* success_flag);

int ZstdAccelCompress(const unsigned char * src, // src file
                      const size_t srcSize, // src file length
                      unsigned char * litBuff, // tmp buffer for storing literals
                      const size_t litBuffSize,
                      unsigned char * seqBuff, // tmp buffer for storing sequences
                      const size_t seqBuffSize,
                      unsigned char * dst, // dst file
                      const int clevel
                      );

volatile int ZstdBlockOnCompressCompletion(volatile int * completion_flag);

#endif //__ACCEL_H
