#include "rocc.h"
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

#define SNAPPY_COMPRESS_OPCODE 3
#define SNAPPY_COMPRESS_FUNCT_SFENCE 0
#define SNAPPY_COMPRESS_FUNCT_SRC_INFO 1
#define SNAPPY_COMPRESS_FUNCT_DEST_INFO_AND_START 2
#define SNAPPY_COMPRESS_FUNCT_CHECK_COMPLETION 3
#define SNAPPY_COMPRESS_MAX_OFFSET_ALLOWED 4
#define SNAPPY_COMPRESS_RUNTIME_HT_NUM_ENTRIES_LOG2 5

#define SNAPPY_DECOMPRESS_OPCODE 2
#define SNAPPY_DECOMPRESS_FUNCT_SFENCE 0
#define SNAPPY_DECOMPRESS_FUNCT_SRC_INFO 1
#define SNAPPY_DECOMPRESS_FUNCT_DEST_INFO_AND_START 2
#define SNAPPY_DECOMPRESS_FUNCT_CHECK_COMPLETION 3
#define SNAPPY_DECOMPRESS_FUNCT_SET_ONCHIP_HIST 4


void SnappyCompressSetDynamicHashTableSizeLog2(uint64_t hash_table_entries_log2);

void SnappyCompressSetDynamicHistSize(uint64_t hist_sram_size_limit_bytes);

unsigned char * SnappyCompressAccelSetup(size_t write_region_size, uint64_t hist_sram_size_limit_bytes);

void SnappyAccelRawCompressNonblocking(const unsigned char* uncompressed, size_t uncompressed_length, unsigned char* compressed, uint64_t* compressed_size);

uint64_t SnappyAccelRawCompress(const unsigned char* uncompressed, size_t uncompressed_length, unsigned char* compressed);

volatile uint64_t BlockOnCompressCompletion(volatile uint64_t * compressed_size);


void SnappyDecompressSetDynamicHistSize(uint64_t sram_size_limit_bytes);

unsigned char * SnappyDecompressAccelSetup(size_t write_region_size, uint64_t sram_size_limit_bytes);

void SnappyAccelRawUncompressNonblocking(const unsigned char* compressed, size_t compressed_length, unsigned char* uncompressed, bool* success_flag);

bool SnappyAccelRawUncompress(const unsigned char* compressed, size_t compressed_length, unsigned char* uncompressed);

volatile bool BlockOnUncompressCompletion(volatile bool * completion_flag);
