#!/usr/bin/env bash

set -ex

# Zstd compress
cd ../software-zstd/compress
FIRESIM_WORKLOAD_DIR="../../../../sims/firesim/deploy/workloads"
HCB_ZSTD_COMPRESS_BIN="$FIRESIM_WORKLOAD_DIR/hyper-compress-bench-zstd-sweep"

cd ../decompress
./build-zstd-tool.sh
cd ../compress

./build-hcb.sh

for i in {0..2}
do
  mkdir -p "$HCB_ZSTD_COMPRESS_BIN-$i"
  cp zstd-complete-external-baremetal/*.riscv "$HCB_ZSTD_COMPRESS_BIN-$i"
done
cp hyper-compress-bench-zstd-sweep-*.json $FIRESIM_WORKLOAD_DIR

# Zstd decompress
cd ../decompress
./build-decompress-bench.sh
# Installing json file with marshal for zstd decompression is done in sims-run-all part.

