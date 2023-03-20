#include <stdio.h>
#include <stddef.h>
#include <stdint.h>
#include <inttypes.h>
#include <stdlib.h>

#include "accellib.h"
#include "encoding.h"
#include "benchmark_data.h"
#include "zstd_decompress.h"

#define ACCEL_RESULT_AREA_BYTES_LOG 20


int main() {
    size_t accelResultBuffSize = 1 << ACCEL_RESULT_AREA_BYTES_LOG;
    unsigned char* result_area = SnappyCompressAccelSetup(accelResultBuffSize, 64 << 10);
    unsigned char * result_area_decomp = SnappyDecompressAccelSetup(accelResultBuffSize, 64 << 10);

    const int clevel = 3;

    printf("src start addr: 0x%016" PRIx64 "\n", (uint64_t)benchmark_raw_data);
    printf("Starting benchmark.\n");
    uint64_t t1 = rdcycle();

    uint64_t compressed_size = SnappyAccelCompress(benchmark_raw_data, benchmark_raw_data_len, result_area);

    uint64_t t2 = rdcycle();
    printf("Start cycle: %" PRIu64 ", End cycle: %" PRIu64 ", Took: %" PRIu64 " CompressedSize: %" PRIu64 "\n", 
        t1, t2, t2 - t1, compressed_size);

    uint64_t t3 = rdcycle();
    bool uncompress_success = SnappyAccelRawUncompress(result_area, compressed_size, result_area_decomp);
    uint64_t t4 = rdcycle();
    printf("Start cycle: %" PRIu64 ", End cycle: %" PRIu64 ", Took: %" PRIu64 " Success: %d\n", 
        t3, t4, t4 - t3, uncompress_success);

    int fail = 0;
    for (int i = 0; i < benchmark_raw_data_len; i++) {
      if (result_area_decomp[i] != benchmark_raw_data[i]) {
        printf("idx %d: accel %d raw %d\n",
            i,
            result_area_decomp[i],
            benchmark_raw_data[i]);
        fail = 1;
      }
    }

    if (fail) {
      printf("[*] Test Failed\n");
    } else {
      printf("[*] Test Passed\n");
    }

    return 0;
}
