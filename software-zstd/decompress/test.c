#include <stdio.h>
#include <stddef.h>
#include <stdint.h>
#include <inttypes.h>
#include <stdlib.h>

#include "accellib.h"
#include "encoding.h"
#include "benchmark_data.h"

int main() {
    uint8_t* wksp_area = ZStdDecompressWorkspaceSetup(benchmark_uncompressed_data_len);
    uint8_t* result_area = ZStdDecompressAccelSetup(benchmark_uncompressed_data_len, 64 << 10);

    printf("src start addr: 0x%016" PRIx64 "\n", (uint64_t)benchmark_compressed_data);
    printf("Starting benchmark.\n");
    uint64_t t1 = rdcycle();
    ZStdAccelUncompress(benchmark_compressed_data,
                        benchmark_compressed_data_len,
                        wksp_area,
                       result_area);
    uint64_t t2 = rdcycle();

    printf("Start cycle: %" PRIu64 ", End cycle: %" PRIu64 ", Took: %" PRIu64 "\n", 
           t1, t2, t2 - t1);

    printf("Checking uncompressed output correctness:\n");
    bool fail = false;
    for (size_t i = 0; i < benchmark_uncompressed_data_len; i++) {
      if (benchmark_uncompressed_data[i] != result_area[i]) {
        printf("idx %" PRIu64 ": expected: %c got: %c\n",
            i, benchmark_uncompressed_data[i], result_area[i]);
        fail = true;
      }
    }

    if (fail) {
        printf("TEST FAILED!\n");
        exit(1);
    } else {
        printf("TEST PASSED!\n");
    }

    return 0;
}
