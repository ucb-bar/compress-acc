#include <stdio.h>
#include <stddef.h>
#include <stdint.h>
#include <inttypes.h>
#include <stdlib.h>
#include "accellib.h"
#include "encoding.h"
#include "benchmark_data_compress.h"


int main() {
    size_t total_benchmarks_uncompressed_size = benchmark_data_uncomp_len;
    total_benchmarks_uncompressed_size += 4096;

    unsigned int num_hist_sizes = 1;
    uint64_t hist_sizes[] = {
        64 << 10
    };

    printf("Setting up...\n");
    unsigned char * result_area = SnappyCompressAccelSetup(total_benchmarks_uncompressed_size, hist_sizes[0]);
    unsigned char * result_area_decomp = SnappyDecompressAccelSetup(total_benchmarks_uncompressed_size * 2, hist_sizes[0]);

    bool fail = false;
    SnappyCompressSetDynamicHistSize(hist_sizes[0]);
    SnappyDecompressSetDynamicHistSize(hist_sizes[0]);

    uint64_t t1 = rdcycle();
    uint64_t compressed_size = SnappyAccelRawCompress(benchmark_data_uncomp, benchmark_data_uncomp_len, result_area);
    uint64_t t2 = rdcycle();

    uint64_t t3 = rdcycle();
    uint64_t uncompress_success = SnappyAccelRawUncompress(result_area, compressed_size, result_area_decomp);
    uint64_t t4 = rdcycle();

    uint64_t * benchmark_uncompressed_data_by8 = (uint64_t *) benchmark_data_uncomp;
    uint64_t * result_area_decomp_by8 = (uint64_t *) result_area_decomp;
    size_t bench_num_words = benchmark_data_uncomp_len / 8;
    size_t tail_start = bench_num_words * 8;

    for (size_t i = 0; i < bench_num_words; i++) {
        if (benchmark_uncompressed_data_by8[i] != result_area_decomp_by8[i]) {
            printf("GOT: 0x%016" PRIx64 "\n", (uint64_t)((uint64_t)result_area_decomp_by8[i]));
            fail = true;
        }
        if ((((i*8) % 10000000) == 0) && !fail) {
            printf("Good after %" PRIu64 " bytes\n", i*8);
        }
    }

    for (size_t i = tail_start; i < compressed_size; i++) {
        if (benchmark_data_uncomp[i] != result_area_decomp[i]) {
            printf("gotb: 0x%02x\n", (uint32_t)((uint8_t)result_area_decomp[i]));
            fail = true;
        }
        if (((i % 100000) == 0) && !fail) {
            printf("Good after %" PRIu64 " bytes\n", i);
        }
    }

    if (fail) {
        printf("TEST FAILED!\n");
        exit(1);
    } else {
        printf("TEST PASSED!\n");
    }

    return fail;
}
