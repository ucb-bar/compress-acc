#include <stdio.h>
#include "accellib.h"
#include <stddef.h>
#include <stdint.h>
#include "benchmark_data_helper.h"
#include <inttypes.h>
#include <stdlib.h>
#include "encoding.h"
#include "zstd_decompress.h"

//#define DO_PRINT



bool run_benchmark(char * benchmark_compressed_data, size_t benchmark_compressed_data_len,
        char * write_region, char * benchmark_uncompressed_data, size_t benchmark_uncompressed_data_len,
        char * bench_name, unsigned int benchno, uint64_t hist_size, char * write_region_decomp, uint64_t hash_table_entries_log2,
        size_t * total_data_uncompressed_processed, size_t * total_data_compressed_processed, uint64_t * total_cycles_taken, size_t * num_bench_pass, uint64_t * benchmark_sum_overall,



                unsigned char * lit_buf,
                size_t lit_buf_size,
                unsigned char * seq_buf,
                size_t seq_buf_size,
                int clevel




        ) {

#ifdef DO_PRINT
    printf("Starting benchmark num %d: %s\n", benchno, bench_name);
#endif

    // zero-out parts of write region to ensure past correct results don't count
    uint64_t * write_region_zero_by8 = (uint64_t *) write_region;
    size_t bench_expected_write_num_words = benchmark_compressed_data_len / 8;
    size_t bench_expected_tail_start = bench_expected_write_num_words * 8;

    for (size_t i = 0; i < bench_expected_write_num_words; i++) {
        write_region_zero_by8[i] = 0;
    }
    for (size_t i = bench_expected_tail_start; i < benchmark_compressed_data_len; i++) {
        write_region[i] = 0;
    }

    uint64_t * benchmark_uncompressed_data_by8 = (uint64_t *) benchmark_uncompressed_data;
    size_t bench_uncompressed_num_words = benchmark_uncompressed_data_len / 8;
    size_t uncompressed_tail_start = bench_uncompressed_num_words * 8;

    uint64_t bench_sum = 0;
    for (size_t i = 0; i < bench_uncompressed_num_words; i+=8) {
        *benchmark_sum_overall += benchmark_uncompressed_data_by8[i];
    }
    for (size_t i = uncompressed_tail_start; i < benchmark_uncompressed_data_len; i++) {
        *benchmark_sum_overall += benchmark_uncompressed_data[i];
    }

#ifdef DO_PRINT
    printf("Benchmark sum: %" PRIu64 "\n", *benchmark_sum_overall);
#endif

    uint64_t t1 = rdcycle();
    uint64_t compressed_size = ZstdAccelCompress(
            benchmark_uncompressed_data,
            benchmark_uncompressed_data_len,
            lit_buf,
            lit_buf_size,
            seq_buf,
            seq_buf_size,
            write_region,
            clevel
            );


    uint64_t t2 = rdcycle();

    //printf("Start cycle: %" PRIu64 "\n", t1);
    //printf("End cycle: %" PRIu64 "\n", t2);

//#ifdef DO_PRINT
    printf("Took %" PRIu64 " cycles produced %" PRIu64 " compressed bytes uncompsize %" PRIu64 " for benchmark %s with histsram %" PRIu64 " with log2HTEntries: %" PRIu64 "\n", t2 - t1, compressed_size, benchmark_uncompressed_data_len, bench_name, hist_size, hash_table_entries_log2);

//#endif


 //   printf("SOFTWARE DECOMP:\n");
    uint64_t t3 = rdcycle();

    size_t compressor_output_size = accel_zstd_test_full(write_region_decomp,
                           benchmark_uncompressed_data_len * 2,
                           write_region,
                           benchmark_uncompressed_data_len * 2);
    uint64_t t4 = rdcycle();



//    printf("DONE SOFTWARE DECOMP\n");


    //printf("Start cycle: %" PRIu64 "\n", t1);
    //printf("End cycle: %" PRIu64 "\n", t2);
//#ifdef DO_PRINT
    printf("SWDECOMP: Took %" PRIu64 " cycles, produced %" PRIu64 " uncompressed bytes. comp size: %" PRIu64 " for benchmark: %s, with histsram: %" PRIu64 ", with log2HTEntries: %" PRIu64 "\n", t4 - t3, benchmark_uncompressed_data_len, compressor_output_size, bench_name, hist_size, hash_table_entries_log2);
//#endif

#ifdef DO_PRINT
    printf("Checking compressed output correctness:\n");
#endif

    //uint64_t * benchmark_uncompressed_data_by8 = (uint64_t *) benchmark_uncompressed_data;
    uint64_t * result_area_by8 = (uint64_t *) write_region_decomp;
    size_t bench_num_words = benchmark_uncompressed_data_len / 8;
    size_t tail_start = bench_num_words * 8;

    bool fail = false;
    bool first_fail = true;


    for (size_t i = 0; i < bench_num_words; i++) {
        if (benchmark_uncompressed_data_by8[i] != result_area_by8[i]) {
            fail = true;
            if (first_fail) {
                printf("FAIL ON BENCHMARK! N: %d, name: %s, with histsram: %" PRIu64 ", log2HTEntries: %" PRIu64 "\n", benchno, bench_name, hist_size, hash_table_entries_log2);
                first_fail = false;
                break;
            }

#ifdef DO_PRINT
            printf("FAIL: mismatch on word %" PRIu64 ": expected: 0x%016" PRIx64 ", got: 0x%016" PRIx64 "\n", i, (uint64_t)((uint64_t)benchmark_uncompressed_data_by8[i]), (uint64_t)((uint64_t)result_area_by8[i]));
#endif
        }
        if ((((i*8) % 10000000) == 0) && !fail) {
#ifdef DO_PRINT
            printf("Good after %" PRIu64 " bytes\n", i*8);
#endif
        }
    }

    for (size_t i = tail_start; i < benchmark_uncompressed_data_len; i++) {
        if (fail) {
            break;
        }
        if (benchmark_uncompressed_data[i] != write_region_decomp[i]) {
            fail = true;
            if (first_fail) {
                printf("FAIL ON BENCHMARK! N: %d, name: %s, with histsram: %" PRIu64 ", log2HTEntries: %" PRIu64 "\n", benchno, bench_name, hist_size, hash_table_entries_log2);
                first_fail = false;
            }
#ifdef DO_PRINT
            printf("FAIL: mismatch on char %" PRIu64 ": expected: 0x%02x, got: 0x%02x\n", i, (uint32_t)((uint8_t)benchmark_uncompressed_data[i]), (uint32_t)((uint8_t)write_region_decomp[i]));
#endif

        }
        if (((i % 100000) == 0) && !fail) {
#ifdef DO_PRINT
            printf("Good after %" PRIu64 " bytes\n", i);
#endif
        }
    }

    if (!fail) {
        *total_data_uncompressed_processed += benchmark_uncompressed_data_len;
        *total_data_compressed_processed += compressed_size;
        *total_cycles_taken += (t2 - t1);
        *num_bench_pass += 1;
    }

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
//        2048 << 10,
//        1024 << 10,
//        512 << 10,
//        256 << 10,


        64 << 10, // warmup
        64 << 10, // warmup

//        128 << 10,
        64 << 10,
        32 << 10,
        16 << 10,
        8 << 10,
        4 << 10,
        2 << 10,
    };


    bool is_warmup[] = {

        false, // warmup
        false, // warmup

        false,
        false,
        false,
        false,
        false,
        false,
        false
    };




    unsigned int num_hash_table_sizes = 1;
    uint64_t hash_table_sizes_log2[] = {
        14,
        13,
        12,
        11,
        10,
        9
    };


#ifdef DO_PRINT
    printf("Setting up...\n");
#endif

    unsigned char * result_area = ZstdCompressAccelSetup(total_benchmarks_uncompressed_size, hist_sizes[0]);
    unsigned char * lit_buf = ZstdCompressWorkspaceSetup(total_benchmarks_uncompressed_size);
    unsigned char * seq_buf = ZstdCompressWorkspaceSetup(total_benchmarks_uncompressed_size);
    unsigned char * result_area_decomp = ZstdCompressWorkspaceSetup(total_benchmarks_uncompressed_size);




    const int clevel = 16;

    bool fail = false;
    uint64_t benchmark_sum_overall = 0;


    for (unsigned int k = 0; k < num_hash_table_sizes; k++) {

//        ZstdCompressSetDynamicHashTableSizeLog2(hash_table_sizes_log2[k]);
#ifdef DO_PRINT
        printf("Using Hash Entries Log2: %" PRIu64 "\n", hash_table_sizes_log2[k]);
#endif




    for (unsigned int j = 0; j < num_hist_sizes; j++) {
        ZstdCompressSetDynamicHistSize(hist_sizes[j]);

//        SnappyDecompressSetDynamicHistSize(hist_sizes[j]);

#ifdef DO_PRINT
        printf("Using HISTSRAM size: %" PRIu64 "\n", hist_sizes[j]);
#endif

        size_t total_data_uncompressed_processed = 0;
        size_t total_data_compressed_processed = 0;
        uint64_t total_cycles_taken = 0;
        size_t num_bench_passed = 0;




        // offset write region on each iter to make sure first iter's correct
        // results don't count for later iters
        unsigned char * result_area2 = result_area + (j * 32);
        unsigned char * result_area_decomp2 = result_area_decomp + (j * 32);
/* for (unsigned int i = 0; i < num_benchmarks; i++) { */
        for (unsigned int i = num_benchmarks - 1; i >= 0; i--) {


            if(run_benchmark((benchmark_compressed_data_arrays[i]), *(benchmark_compressed_data_len_array[i]),
                result_area2, (benchmark_uncompressed_data_arrays[i]), *(benchmark_uncompressed_data_len_array[i]),
                *(benchmark_names[i]), i, hist_sizes[j], result_area_decomp2, hash_table_sizes_log2[k], &total_data_uncompressed_processed, &total_data_compressed_processed, &total_cycles_taken, &num_bench_passed,
                &benchmark_sum_overall,
                lit_buf,
                total_benchmarks_uncompressed_size,
                seq_buf,
                total_benchmarks_uncompressed_size,
                clevel
                )) {
                fail = true;
            }

            size_t this_bench_size = *(benchmark_uncompressed_data_len_array[i]);
            this_bench_size = ((this_bench_size / 32) + 1) * 32;

            result_area2 += this_bench_size;
            result_area_decomp2 += this_bench_size;
        }

        if (!is_warmup[j]) {
            printf("TOTAL: Took %" PRIu64 " cycles consumed %" PRIu64 " uncompressed bytes produced compsize %" PRIu64 " bytes SuccessNBenchmarks %d TotalNBenchmarks %d with histsram %" PRIu64 " with log2HTSize %" PRIu64 "\n", total_cycles_taken, total_data_uncompressed_processed, total_data_compressed_processed, num_bench_passed, num_benchmarks, hist_sizes[j], hash_table_sizes_log2[k]);
        }


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
