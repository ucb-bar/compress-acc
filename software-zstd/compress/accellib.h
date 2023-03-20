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
#define FUNCT_CHECK_COMPLETION 10




#define SNAPPY_DECOMPRESS_OPCODE 3
#define SNAPPY_DECOMPRESS_FUNCT_SFENCE 0
#define SNAPPY_DECOMPRESS_FUNCT_SRC_INFO 1
#define SNAPPY_DECOMPRESS_FUNCT_DEST_INFO_AND_START 2
#define SNAPPY_DECOMPRESS_FUNCT_CHECK_COMPLETION 3
#define SNAPPY_DECOMPRESS_FUNCT_SET_ONCHIP_HIST 4


void ZstdCompressSetDynamicHistSize(uint64_t hist_sram_size_limit_bytes);

unsigned char * ZstdCompressAccelSetup(size_t write_region_size, uint64_t hist_sram_size_limit_bytes);

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



void SnappyCompressSetDynamicHistSize(uint64_t hist_sram_size_limit_bytes);

unsigned char * SnappyCompressAccelSetup(size_t write_region_size, uint64_t hist_sram_size_limit_bytes);

void SnappyAccelCompressNonblocking(const unsigned char * src,
                                    const size_t srcSize,
                                    unsigned char *dst,
                                    int* success_flag);

int SnappyAccelCompress(const unsigned char * src,
                        const size_t srcSize,
                        unsigned char *dst);

volatile int SnappyBlockOnCompressCompletion(volatile int * completion_flag);




void SnappyDecompressSetDynamicHistSize(uint64_t sram_size_limit_bytes);

unsigned char * SnappyDecompressAccelSetup(size_t write_region_size, uint64_t sram_size_limit_bytes);

void SnappyAccelRawUncompressNonblocking(const unsigned char* compressed, size_t compressed_length, unsigned char* uncompressed, bool* success_flag);

bool SnappyAccelRawUncompress(const unsigned char* compressed, size_t compressed_length, unsigned char* uncompressed);

volatile bool BlockOnUncompressCompletion(volatile bool * completion_flag);

#endif //__ACCEL_H
