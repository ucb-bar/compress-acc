#include "accellib.h"
#include <assert.h>
#include <malloc.h>
#include <stdio.h>
#include <stdint.h>
#include <inttypes.h>

#define PAGESIZE_BYTES 4096

void SnappyCompressSetDynamicHashTableSizeLog2(uint64_t hash_table_entries_log2) {
    ROCC_INSTRUCTION_S(SNAPPY_COMPRESS_OPCODE, hash_table_entries_log2, SNAPPY_COMPRESS_RUNTIME_HT_NUM_ENTRIES_LOG2);
}

void SnappyCompressSetDynamicHistSize(uint64_t hist_sram_size_limit_bytes) {
    ROCC_INSTRUCTION_S(SNAPPY_COMPRESS_OPCODE, hist_sram_size_limit_bytes, SNAPPY_COMPRESS_MAX_OFFSET_ALLOWED);
}


unsigned char * SnappyCompressAccelSetup(size_t write_region_size, uint64_t hist_sram_size_limit_bytes) {
#ifndef NOACCEL_DEBUG
    ROCC_INSTRUCTION(SNAPPY_COMPRESS_OPCODE, SNAPPY_COMPRESS_FUNCT_SFENCE);
    SnappyCompressSetDynamicHistSize(hist_sram_size_limit_bytes);
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

volatile uint64_t BlockOnCompressCompletion(volatile uint64_t * compressed_size) {
    uint64_t retval;
#ifndef NOACCEL_DEBUG
    ROCC_INSTRUCTION_D(SNAPPY_COMPRESS_OPCODE, retval, SNAPPY_COMPRESS_FUNCT_CHECK_COMPLETION);
#endif
    asm volatile ("fence");

#ifndef NOACCEL_DEBUG
    while (! *(compressed_size)) {
        asm volatile ("fence");
    }
#endif
    return *compressed_size;
}

void SnappyAccelRawCompressNonblocking(const unsigned char* uncompressed, size_t uncompressed_length, unsigned char* compressed, uint64_t* compressed_size) {
#ifndef NOACCEL_DEBUG
    ROCC_INSTRUCTION_SS(SNAPPY_COMPRESS_OPCODE, (uint64_t)uncompressed, (uint64_t)uncompressed_length, SNAPPY_COMPRESS_FUNCT_SRC_INFO);
    ROCC_INSTRUCTION_SS(SNAPPY_COMPRESS_OPCODE, (uint64_t)compressed, (uint64_t)compressed_size, SNAPPY_COMPRESS_FUNCT_DEST_INFO_AND_START);
#endif
}

uint64_t SnappyAccelRawCompress(const unsigned char* uncompressed, size_t uncompressed_length, unsigned char* compressed) {
    uint64_t compressed_size = 0;
    SnappyAccelRawCompressNonblocking(uncompressed, uncompressed_length, compressed, &compressed_size);
    return BlockOnCompressCompletion(&compressed_size);
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

