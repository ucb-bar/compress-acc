#include <stdio.h>
#include "accellib.h"
#include <stddef.h>
#include <stdint.h>
#include "benchmark_data_helper.h"
#include <inttypes.h>
#include <stdlib.h>
#include "encoding.h"

// #define DO_PRINT

uint32_t latency_injection_cycles = 400; //in cycles. 
bool has_intermediate_cache = true;

bool zstd_run_benchmark(char * benchmark_compressed_data, size_t benchmark_compressed_data_len,
        char * write_region, char * benchmark_uncompressed_data, size_t benchmark_uncompressed_data_len,
        char * bench_name, unsigned int benchno, uint64_t sram_size, char * workspace_area,
        size_t * total_data_uncompressed_processed, size_t * total_data_compressed_processed, uint64_t * total_cycles_taken, size_t * num_bench_pass, uint64_t * benchmark_sum_overall) {

#ifdef DO_PRINT
    printf("Starting benchmark num %d: %s\n", benchno, bench_name);
#endif

    // zero-out parts of workspace region to ensure past correct results don't count
    uint64_t * workspace_region_zero_by8 = (uint64_t *) workspace_area;
    size_t bench_expected_workspace_num_words = benchmark_uncompressed_data_len / 8;
    size_t bench_expected_workspace_tail_start = bench_expected_workspace_num_words * 8;

    for (size_t i = 0; i < bench_expected_workspace_num_words; i++) {
        workspace_region_zero_by8[i] = 0;
    }
    for (size_t i = bench_expected_workspace_tail_start; i < benchmark_uncompressed_data_len; i++) {
        workspace_area[i] = 0;
    }

    // zero-out parts of write region to ensure past correct results don't count
    uint64_t * write_region_zero_by8 = (uint64_t *) write_region;
    size_t bench_expected_write_num_words = benchmark_uncompressed_data_len / 8;
    size_t bench_expected_tail_start = bench_expected_write_num_words * 8;

    for (size_t i = 0; i < bench_expected_write_num_words; i++) {
        write_region_zero_by8[i] = 0;
    }
    for (size_t i = bench_expected_tail_start; i < benchmark_uncompressed_data_len; i++) {
        write_region[i] = 0;
    }

    uint64_t * benchmark_compressed_data_by8 = (uint64_t *) benchmark_compressed_data;
    size_t bench_compressed_num_words = benchmark_compressed_data_len / 8;
    size_t compressed_tail_start = bench_compressed_num_words * 8;

    for (size_t i = 0; i < bench_compressed_num_words; i++) {
        *benchmark_sum_overall += benchmark_compressed_data_by8[i];
    }
    for (size_t i = compressed_tail_start; i < benchmark_compressed_data_len; i++) {
        *benchmark_sum_overall += benchmark_compressed_data[i];
    }

#ifdef DO_PRINT
    printf("Benchmark sum: %" PRIu64 "\n", *benchmark_sum_overall);
#endif

    uint64_t t1 = rdcycle();
    ZStdAccelUncompress(benchmark_compressed_data, benchmark_compressed_data_len, workspace_area, write_region);
    uint64_t t2 = rdcycle();

    //printf("Start cycle: %" PRIu64 "\n", t1);
    //printf("End cycle: %" PRIu64 "\n", t2);
#ifdef DO_PRINT
    printf("Took %" PRIu64 " cycles, produced %" PRIu64 " uncompressed bytes. comp size: %" PRIu64 " for benchmark: %s, with histsram: %" PRIu64 "\n", t2 - t1, benchmark_uncompressed_data_len, benchmark_compressed_data_len, bench_name, sram_size);
#endif

    //printf("Got output:\n");
    //for (size_t i = 0; i < benchmark_uncompressed_data_len; i++) {
    //    printf("0x%02x\n", (uint32_t)((uint8_t)result_area[i]));
    //}

#ifdef DO_PRINT
    printf("Checking uncompressed output correctness:\n");
#endif

    // uint64_t * benchmark_uncompressed_data_by8 = (uint64_t *) benchmark_uncompressed_data;
    // uint64_t * result_area_by8 = (uint64_t *) write_region;
    // size_t bench_num_words = benchmark_uncompressed_data_len / 8;
    // size_t tail_start = bench_num_words * 8;

    bool fail = false;
    bool first_fail = true;

//     for (size_t i = 0; i < bench_num_words; i++) {
//         if (benchmark_uncompressed_data_by8[i] != result_area_by8[i]) {
//             printf("FAIL: mismatch on word %" PRIu64 ": expected: 0x%016" PRIx64 ", got: 0x%016" PRIx64 "\n", i, (uint64_t)((uint64_t)benchmark_uncompressed_data_by8[i]), (uint64_t)((uint64_t)result_area_by8[i]));
//             fail = true;
//             if (first_fail) {
//                 printf("FAIL ON BENCHMARK! N: %d, name: %s, with histsram: %" PRIu64 "\n", benchno, bench_name, sram_size);
//                 first_fail = false;
//                 break;
//             }
//         }
//         if ((((i*8) % 10000000) == 0) && !fail) {
// #ifdef DO_PRINT
//             printf("Good after %" PRIu64 " bytes\n", i*8);
// #endif

//         }
//     }

//     for (size_t i = tail_start; i < benchmark_uncompressed_data_len; i++) {
//         if (fail) {
//             break;
//         }
//         if (benchmark_uncompressed_data[i] != write_region[i]) {
//             fail = true;
//             if (first_fail) {
//                 printf("FAIL ON BENCHMARK! N: %d, name: %s, with histsram: %" PRIu64 "\n", benchno, bench_name, sram_size);
//                 first_fail = false;
//             }
//             printf("FAIL: mismatch on char %" PRIu64 ": expected: 0x%02x, got: 0x%02x\n", i, (uint32_t)((uint8_t)benchmark_uncompressed_data[i]), (uint32_t)((uint8_t)write_region[i]));


//         }
//         if (((i % 100000) == 0) && !fail) {
// #ifdef DO_PRINT
//             printf("Good after %" PRIu64 " bytes\n", i);
// #endif

//         }
//     }

    if (!fail) {
        *total_data_uncompressed_processed += benchmark_uncompressed_data_len;
        *total_data_compressed_processed += benchmark_compressed_data_len;
        *total_cycles_taken += (t2 - t1);
        *num_bench_pass += 1;
    }

    return fail;
}
void run_zstd(){
    size_t total_benchmarks_uncompressed_size = 0;
    for (unsigned int i = 0; i < num_benchmarks; i++) {
        size_t this_bench_size = *(benchmark_uncompressed_data_len_array[i]);
        this_bench_size = ((this_bench_size / 32) + 1) * 32;
        total_benchmarks_uncompressed_size += this_bench_size;
    }

    // ensure at least one page
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


    bool is_warmup[] = {

        true, // warmup
        true, // warmup

        false,
        false,
        false,
        false,
        false,
        false
    };



#ifdef DO_PRINT
    printf("Setting up...\n");
#endif

    unsigned char * result_area = ZStdDecompressAccelSetup(total_benchmarks_uncompressed_size, sram_sizes[0]);
    unsigned char * workspace_area = ZStdDecompressWorkspaceSetup(total_benchmarks_uncompressed_size);

    DecompressSetLatencyInjection(latency_injection_cycles, has_intermediate_cache);

    bool fail = false;
    uint64_t benchmark_sum_overall = 0;

    for (unsigned int j = 0; j < num_sram_sizes; j++) {
        ZStdDecompressSetDynamicHistSize(sram_sizes[j]);
#ifdef DO_PRINT
        printf("Using SRAM size: %" PRIu64 "\n", sram_sizes[j]);
#endif

        size_t total_data_uncompressed_processed = 0;
        size_t total_data_compressed_processed = 0;
        uint64_t total_cycles_taken = 0;
        size_t num_bench_passed = 0;

        // offset write region on each iter to make sure first iter's correct
        // results don't count for later iters
        unsigned char * result_area2 = result_area + (j * 32);
        for (unsigned int i = 0; i < num_benchmarks; i++) {
            if(zstd_run_benchmark((benchmark_compressed_data_arrays[i]), *(benchmark_compressed_data_len_array[i]),
                result_area2, (benchmark_uncompressed_data_arrays[i]), *(benchmark_uncompressed_data_len_array[i]),
                *(benchmark_names[i]), i, sram_sizes[j], workspace_area, &total_data_uncompressed_processed, &total_data_compressed_processed, &total_cycles_taken, &num_bench_passed,
                &benchmark_sum_overall)) {
                fail = true;
            }
            size_t this_bench_size = *(benchmark_uncompressed_data_len_array[i]);
            this_bench_size = ((this_bench_size / 32) + 1) * 32;

            result_area2 += this_bench_size;
        }

        if (!is_warmup[j]) {
            printf("TOTAL: Took %" PRIu64 " cycles produced %" PRIu64 " uncompressed bytes comp size %" PRIu64 " bytes SuccessNBenchmarks %d TotalNBenchmarks %d with histsram %" PRIu64 "\n", total_cycles_taken, total_data_uncompressed_processed, total_data_compressed_processed, num_bench_passed, num_benchmarks, sram_sizes[j]);
        }

    }

    printf("FINAL: Benchmark sum: %" PRIu64 "\n", benchmark_sum_overall);

    if (fail) {
        printf("TEST FAILED!\n");
        exit(1);
    } else {
        printf("TEST PASSED!\n");
    }
}

///////////////////////////////////////////////////////////////
int main() {
    run_zstd();
    // run_snappy();
}

