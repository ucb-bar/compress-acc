#include <stdio.h>
#include "accellib.h"
#include <stddef.h>
#include <stdint.h>
#include "benchmark_data_helper.h"
#include <inttypes.h>
#include <stdlib.h>
#include "encoding.h"


bool run_benchmark(char * benchmark_compressed_data, size_t benchmark_compressed_data_len,
        char * write_region, char * benchmark_uncompressed_data, size_t benchmark_uncompressed_data_len,
        char * bench_name, unsigned int benchno, uint64_t hist_size) {

    uint64_t * benchmark_uncompressed_data_by8 = (uint64_t *) benchmark_uncompressed_data;
    size_t bench_uncompressed_num_words = benchmark_uncompressed_data_len / 8;
    size_t uncompressed_tail_start = bench_uncompressed_num_words * 8;

    uint64_t bench_sum = 0;
    for (size_t i = 0; i < bench_uncompressed_num_words; i+=8) {
        bench_sum += benchmark_uncompressed_data_by8[i];
    }
    for (size_t i = uncompressed_tail_start; i < benchmark_uncompressed_data_len; i++) {
        bench_sum += benchmark_uncompressed_data[i];
    }
    printf("Benchmark sum: %" PRIu64 "\n", bench_sum);

    printf("Starting benchmark num %d: %s\n", benchno, bench_name);

    uint64_t t1 = rdcycle();
    uint64_t compressed_size = SnappyAccelRawCompress(benchmark_uncompressed_data, benchmark_uncompressed_data_len, write_region);
    uint64_t t2 = rdcycle();

    //printf("Start cycle: %" PRIu64 "\n", t1);
    //printf("End cycle: %" PRIu64 "\n", t2);
    printf("Took %" PRIu64 " cycles, produced %" PRIu64 " compressed bytes. uncomp size: %" PRIu64 " for benchmark: %s, with histsram: %" PRIu64 "\n", t2 - t1, compressed_size, benchmark_uncompressed_data_len, bench_name, hist_size);

    //printf("Got output:\n");
    //for (size_t i = 0; i < benchmark_uncompressed_data_len; i++) {
    //    printf("0x%02x\n", (uint32_t)((uint8_t)result_area[i]));
    //}

/*
    printf("Checking compressed output correctness:\n");

    uint64_t * benchmark_compressed_data_by8 = (uint64_t *) benchmark_compressed_data;
    uint64_t * result_area_by8 = (uint64_t *) write_region;
    size_t bench_num_words = compressed_size / 8;
    size_t tail_start = bench_num_words * 8;
*/
    bool fail = false;

/*
    for (size_t i = 0; i < bench_num_words; i++) {
        printf("GOT: 0x%016" PRIx64 "\n", (uint64_t)((uint64_t)result_area_by8[i]));
        if (benchmark_compressed_data_by8[i] != result_area_by8[i]) {
            fail = true;
        }
        if ((((i*8) % 10000000) == 0) && !fail) {
            printf("Good after %" PRIu64 " bytes\n", i*8);
        }
    }

    for (size_t i = tail_start; i < compressed_size; i++) {
        printf("gotb: 0x%02x\n", (uint32_t)((uint8_t)write_region[i]));
        if (benchmark_compressed_data[i] != write_region[i]) {
            fail = true;
        }
        if (((i % 100000) == 0) && !fail) {
            printf("Good after %" PRIu64 " bytes\n", i);
        }
    }
*/
    return fail;
}

int main() {

    size_t total_benchmarks_uncompressed_size = 0;
    for (unsigned int i = 0; i < num_benchmarks; i++) {
        size_t this_bench_size = *(benchmark_uncompressed_data_len_array[i]);
        this_bench_size = ((this_bench_size / 32) + 1) * 32;
        total_benchmarks_uncompressed_size += this_bench_size;
    }

    // ensure at least one page
    total_benchmarks_uncompressed_size += 4096;

    unsigned int num_hist_sizes = 1;
    uint64_t hist_sizes[] = {
/*        2048 << 10,
        1024 << 10,
        512 << 10,
        256 << 10,
        128 << 10,*/
        64 << 10 /*,
        32 << 10*/
    };

    printf("Setting up...\n");
    unsigned char * result_area = SnappyCompressAccelSetup(total_benchmarks_uncompressed_size, hist_sizes[0]);

    bool fail = false;

    for (unsigned int j = 0; j < num_hist_sizes; j++) {
        SnappyCompressSetDynamicHistSize(hist_sizes[j]);
        printf("Using HISTSRAM size: %" PRIu64 "\n", hist_sizes[j]);

        // offset write region on each iter to make sure first iter's correct
        // results don't count for later iters
        unsigned char * result_area2 = result_area + (j * 32);
        for (unsigned int i = 0; i < num_benchmarks; i++) {
            if(run_benchmark((benchmark_compressed_data_arrays[i]), *(benchmark_compressed_data_len_array[i]),
                result_area2, (benchmark_uncompressed_data_arrays[i]), *(benchmark_uncompressed_data_len_array[i]),
                *(benchmark_names[i]), i, hist_sizes[j])) {
                fail = true;
            }
            size_t this_bench_size = *(benchmark_uncompressed_data_len_array[i]);
            this_bench_size = ((this_bench_size / 32) + 1) * 32;

            result_area2 += this_bench_size;
        }
    }

/*
    if (fail) {
        printf("TEST FAILED!\n");
        exit(1);
    } else {
        printf("TEST PASSED!\n");
    }
*/
}
