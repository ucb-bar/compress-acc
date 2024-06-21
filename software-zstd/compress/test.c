#include <stdio.h>
#include <stddef.h>
#include <stdint.h>
#include <inttypes.h>
#include <stdlib.h>

#include "accellib.h"
#include "encoding.h"
#include "benchmark_data.h"
#include "zstd_decompress.h"

#define ACCEL_RESULT_AREA_BYTES_LOG 17
#define BUFF_AREA_BYTES_LOG 17



#define FIRESIM

int main() {
    size_t litBuffSize = 1 << BUFF_AREA_BYTES_LOG;
    size_t seqBuffSize = 1 << BUFF_AREA_BYTES_LOG;
    unsigned char* litBuff = ZstdCompressWorkspaceSetup(litBuffSize);
    unsigned char* seqBuff = ZstdCompressWorkspaceSetup(seqBuffSize);

    size_t accelResultBuffSize = (1 << ACCEL_RESULT_AREA_BYTES_LOG) + 4096;
    unsigned char* result_area = ZstdCompressAccelSetup(accelResultBuffSize); // FIXME : Jack this up later

    const int clevel = 3;

    printf("src start addr: 0x%016" PRIx64 "\n", (uint64_t)benchmark_raw_data);
    printf("Starting benchmark.\n");

    ZstdCompressSetDynamicHashTableSizeLog2(14);
    ZstdCompressSetDynamicHistSize(64L << 10);
    ZstdCompressSetLatencyInjectionInfo(0L, false);

    uint64_t t1 = rdcycle();
    uint64_t compressed_size = ZstdAccelCompress(benchmark_raw_data,
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
  
#ifdef FIRESIM
  unsigned char * result_area_decomp  = ZstdCompressWorkspaceSetup(accelResultBuffSize);

  printf("Starting software decomp\n");
  size_t compressor_output_size = accel_zstd_test_full(result_area_decomp,
      benchmark_raw_data_len * 2,
      result_area,
      benchmark_raw_data_len * 2);


  uint64_t* benchmark_raw_data_by8 = (uint64_t*)benchmark_raw_data;
  uint64_t* result_area_by8 = (uint64_t *)result_area_decomp;
  size_t benchmark_num_words = benchmark_raw_data_len / 8;
  size_t tail_start = benchmark_num_words * 8;

  printf("Checking correctness\n");
  bool fail = false;
  for (size_t i = 0; i < benchmark_num_words; i++) {
    if (benchmark_raw_data_by8[i] != result_area_by8[i]) {
      fail = true;
      printf("FAIL ON WORD %d, expected %" PRIu64 " got %" PRIu64 "\n",
          i, benchmark_raw_data_by8[i], result_area_by8[i]);
    }
  }

  for (size_t i = tail_start; i < benchmark_raw_data_len; i++) {
    if (benchmark_raw_data[i] != result_area_decomp[i]) {
      fail = true;
      printf("FAIL ON CHAR %d, expected %" PRIu64 " got %" PRIu64 "\n",
          i, benchmark_raw_data[i], result_area_decomp[i]);
    }
  }

  if (fail) {
    printf("TEST FAILED\n");
  } else {
    printf("TEST SUCCESS\n");
  }
#endif

    return 0;
}
