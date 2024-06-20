#include <stdio.h>
#include <stddef.h>
#include <stdint.h>
#include <inttypes.h>
#include <stdlib.h>

#include "accel.h"
#include "encoding.h"
#include "benchmark_data.h"
#include "zstd_decompress.h"

#define ACCEL_RESULT_AREA_BYTES_LOG 24
#define BUFF_AREA_BYTES_LOG 20

int main() {
    u8* litBuff = WkspSetup(BUFF_AREA_BYTES_LOG);
    u8* seqBuff = WkspSetup(BUFF_AREA_BYTES_LOG);
    u8* result_area = AccelSetup(ACCEL_RESULT_AREA_BYTES_LOG); // FIXME : Jack this up later

    size_t litBuffSize = 1 << BUFF_AREA_BYTES_LOG;
    size_t seqBuffSize = 1 << BUFF_AREA_BYTES_LOG;
    const int clevel = 3;

    printf("src start addr: 0x%016" PRIx64 "\n", (uint64_t)benchmark_raw_data);
    printf("Starting benchmark.\n");
    uint64_t t1 = rdcycle();
    ZstdAccelCompress(benchmark_raw_data,
                      benchmark_raw_data_len,
                      litBuff,
                      litBuffSize,
                      seqBuff,
                      seqBuffSize,
                      result_area,
                      clevel);
    uint64_t t2 = rdcycle();
    printf("Start cycle: %" PRIu64 ", End cycle: %" PRIu64 ", Took: %" PRIu64 "\n", 
        t1, t2, t2 - t1);

/* printf("Start SW decompression\n"); */
/* const size_t sw_dst_len = 1 << ACCEL_RESULT_AREA_BYTES_LOG; */
/* u8* sw_dst = (u8*)malloc(sizeof(u8)*sw_dst_len); */
/* accel_zstd_test_full(sw_dst, sw_dst_len, result_area, result_area_len); */

/* printf("Checking output results\n"); */
/* int fail = 0; */
/* for (size_t i = 0; i < benchmark_raw_data_len; i++) { */
/* if (sw_dst[i] != benchmark_raw_data[i]) { */
/* printf("decomp result different at byte %lu, decomp: %d raw: %d\n", */
/* i, sw_dst[i], benchmark_raw_data[i]); */
/* fail = 1; */
/* } */
/* } */

/* if (fail) { */
/* printf("[*] Test Failed :(\n"); */
/* } else { */
/* printf("[*] Test Passed!!\n"); */
/* } */

    return 0;
}
