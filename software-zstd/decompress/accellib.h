#ifndef __ACCEL_H
#define __ACCEL_H

#include <stdint.h>
#include <stddef.h>
#include "rocc.h"
#include <stdbool.h>

#define DECOMPRESS_OPCODE 2

#define ZSTD_ALGORITHM 0
#define SNAPPY_ALGORITHM 1

#define DECOMPRESS_FUNCT_SFENCE 0
#define DECOMPRESS_FUNCT_ALGORITHM 1
#define DECOMPRESS_FUNCT_LATENCY 2

#define ZSTD_DECOMPRESS_FUNCT_SRC_INFO 3
#define ZSTD_DECOMPRESS_FUNCT_WKSP_INFO 4
#define ZSTD_DECOMPRESS_FUNCT_DST_INFO 5
#define ZSTD_DECOMPRESS_FUNCT_CHECK_COMPLETION 6
#define ZSTD_DECOMPRESS_FUNCT_SET_ONCHIP_HIST 7

#define SNAPPY_DECOMPRESS_FUNCT_SRC_INFO 8
#define SNAPPY_DECOMPRESS_FUNCT_DEST_INFO_AND_START 9
#define SNAPPY_DECOMPRESS_FUNCT_CHECK_COMPLETION 10
#define SNAPPY_DECOMPRESS_FUNCT_SET_ONCHIP_HIST 11

// Zstd Functions
void ZStdDecompressSetDynamicHistSize(uint64_t hist_sram_size_limit_bytes);

void DecompressSetLatencyInjection(uint32_t latency_injection_cycles, bool has_intermediate_cache);

unsigned char * ZStdDecompressWorkspaceSetup(size_t workspace_size);

unsigned char * ZStdDecompressAccelSetup(size_t write_region_size, uint64_t hist_sram_size_limit_bytes);

void ZStdAccelUncompressNonblocking(const unsigned char* compressed,
                                      size_t compressed_length,
                                      unsigned char* workspace,
                                      unsigned char* uncompressed,
                                      int* success_flag);

int ZStdAccelUncompress(const unsigned char* compressed,
                           size_t compressed_length,
                           unsigned char* workspace,
                           unsigned char* uncompressed);

volatile int ZStdAccelBlockOnUncompressCompletion(volatile int * completion_flag);

// Snappy Functions
void SnappyDecompressSetDynamicHistSize(uint64_t sram_size_limit_bytes);

unsigned char * SnappyDecompressAccelSetup(size_t write_region_size, uint64_t sram_size_limit_bytes);

void SnappyAccelRawUncompressNonblocking(const unsigned char* compressed, size_t compressed_length, unsigned char* uncompressed, bool* success_flag);

bool SnappyAccelRawUncompress(const unsigned char* compressed, size_t compressed_length, unsigned char* uncompressed);

volatile bool BlockOnUncompressCompletion(volatile bool * completion_flag);

#endif //__ACCEL_H
