#include <stdio.h>
#include <stddef.h>
#include <stdint.h>
#include <inttypes.h>
#include <stdlib.h>

#include "accellib.h"
#include "encoding.h"
#include "benchmark_data.h"

#define ACCEL_RESULT_AREA_BYTES_LOG 20
#define BUFF_AREA_BYTES_LOG 20

int main() {
    size_t litBuffSize = 1 << BUFF_AREA_BYTES_LOG;
    size_t seqBuffSize = 1 << BUFF_AREA_BYTES_LOG;
    unsigned char* litBuff = ZstdCompressWorkspaceSetup(litBuffSize);
    unsigned char* seqBuff = ZstdCompressWorkspaceSetup(seqBuffSize);

    size_t accelResultBuffSize = 1 << ACCEL_RESULT_AREA_BYTES_LOG;
    unsigned char* result_area = ZstdCompressAccelSetup(accelResultBuffSize, 0); // FIXME : Jack this up later

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

    return 0;
}
