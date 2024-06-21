#include <stdio.h>
#include <stddef.h>
#include <stdint.h>
#include <inttypes.h>
#include <stdlib.h>

#include "benchmark_data.h"
#include "zstd_decompress.h"
#include "compressed_bytes.h"


int main() {
  printf("Start SW decompression\n");
  const size_t sw_dst_len = 1 << 24;
  u8* sw_dst = (u8*)malloc(sizeof(u8)*sw_dst_len);
  accel_zstd_test_full(sw_dst, sw_dst_len, compressed, compressed_len);

  printf("Checking output results\n");
  int fail = 0;
  for (size_t i = 0; i < benchmark_raw_data_len; i++) {
    if (sw_dst[i] != benchmark_raw_data[i]) {
/* printf("decomp result different at byte %lu, decomp: %d raw: %d\n", */
/* i, sw_dst[i], benchmark_raw_data[i]); */
      fail = 1;
    }
  }

  if (fail) {
    printf("[*] Test Failed :(\n");
  } else {
    printf("[*] Test Passed!!\n");
  }

  return 0;
}
