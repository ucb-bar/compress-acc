#include <stdio.h>
#include <stddef.h>
#include <stdint.h>
#include <inttypes.h>
#include <stdlib.h>
#include "encoding.h"
#include "accellib.h"
#include "benchmark_data_decompress.h"



int main() {
    size_t total_benchmarks_uncompressed_size = benchmark_data_uncomp_len;
    total_benchmarks_uncompressed_size += 4096;

    unsigned int num_sram_sizes = 8;
    uint64_t sram_sizes[] = {
        64 << 10, // warmup
        64 << 10, // warmup

        64 << 10,
        32 << 10,
        16 << 10,
        8 << 10,
        4 << 10,
        2 << 10
    };

#ifdef DO_PRINT
    printf("Setting up...\n");
#endif

    unsigned char * result_area = SnappyDecompressAccelSetup(total_benchmarks_uncompressed_size, sram_sizes[0]);

    uint64_t benchmark_sum_overall = 0;
    SnappyDecompressSetDynamicHistSize(sram_sizes[0]);



    uint64_t t1 = rdcycle();
    SnappyAccelRawUncompress(benchmark_data_comp, benchmark_data_comp_len, result_area);
    uint64_t t2 = rdcycle();


    uint64_t * benchmark_uncompressed_data_by8 = (uint64_t *) benchmark_data_uncomp;
    uint64_t * result_area_by8 = (uint64_t *) result_area;
    size_t bench_num_words = benchmark_data_uncomp_len / 8;
    size_t tail_start = bench_num_words * 8;
    bool fail = false;
    bool first_fail = true;

    for (size_t i = 0; i < bench_num_words; i++) {
        if (benchmark_uncompressed_data_by8[i] != result_area_by8[i]) {
            fail = true;
            printf("FAIL: mismatch on word %" PRIu64 ": expected: 0x%016" PRIx64 ", got: 0x%016" PRIx64 "\n", i, (uint64_t)((uint64_t)benchmark_uncompressed_data_by8[i]), (uint64_t)((uint64_t)result_area_by8[i]));
            break;
        }
        if ((((i*8) % 10000000) == 0) && !fail) {
#ifdef DO_PRINT
            printf("Good after %" PRIu64 " bytes\n", i*8);
#endif
        }
    }

    for (size_t i = tail_start; i < benchmark_data_uncomp_len; i++) {
        if (fail) {
            break;
        }
        if (benchmark_data_uncomp[i] != result_area[i]) {
            fail = true;
            printf("FAIL: mismatch on char %" PRIu64 ": expected: 0x%02x, got: 0x%02x\n",
                i, (uint32_t)((uint8_t)benchmark_data_uncomp[i]), (uint32_t)((uint8_t)result_area[i]));
        }
        if (((i % 100000) == 0) && !fail) {
#ifdef DO_PRINT
            printf("Good after %" PRIu64 " bytes\n", i);
#endif

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
