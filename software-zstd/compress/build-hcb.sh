#!/usr/bin/env bash

set -ex

BASEDIR=$(pwd)

# The number of binaries you want to shard the benchmark files across.
# Should correspond with the # of sims you want to run in parallel.
#
# THIS SHOULD NOT EXCEED 300 WITHOUT CHANGING MARSHAL WORKLOAD JSON
# + REINSTALLING THE WORKLOAD
NUMCHUNKS=48 # number of shards

# not limiting parallelism has OOM'd the system before. recommend <= 20
PARALLELISM_MAX=20

# in zstd, run make in lib/ and programs/
ZSTD_BINARY_PATH="$BASEDIR/../../software/zstd/programs/zstd"

# benchmark data dir, relative to compress-acc-repo/software/benchmarks/
BENCH_DATA_DIR="HyperCompressBench/extracted_benchmarks/ZSTD-COMPRESS"


COMP_OR_DECOMP=complete

function buildbench() {

  INPUTDIR="$BASEDIR/../../software/benchmarks/$2/"
  FINAL_OUTPUT_DIR="$BASEDIR/zstd-$COMP_OR_DECOMP-$1-baremetal/"
  OUTPUTDIR="$FINAL_OUTPUT_DIR/build-$3/"

  mkdir -p $OUTPUTDIR

  cd $OUTPUTDIR
  INPUT_FILE_BASE=$INPUTDIR/$3
  INPUT_FILE=$INPUT_FILE_BASE.raw
  INPUT_FILE_COMPRESSED=$INPUT_FILE_BASE.comp

  cp $BASEDIR/splitter.py .
  cp $BASEDIR/*.c .
  cp $BASEDIR/*.h .

  python3 splitter.py $INPUTDIR $OUTPUTDIR $NUMCHUNKS $3 $ZSTD_BINARY_PATH

  TEST_FILE_NAME="test-$COMP_OR_DECOMP.c"
  TEST_FILE_O="test-$COMP_OR_DECOMP.o"

  riscv64-unknown-elf-gcc -fno-common -fno-builtin-printf -specs=htif_nano.specs -c $TEST_FILE_NAME
  riscv64-unknown-elf-gcc -fno-common -fno-builtin-printf -specs=htif_nano.specs -c accellib.c
  riscv64-unknown-elf-gcc -fno-common -fno-builtin-printf -specs=htif_nano.specs -c zstd_decompress.c
  riscv64-unknown-elf-gcc -static -specs=htif_nano.specs $TEST_FILE_O accellib.o zstd_decompress.o -o $FINAL_OUTPUT_DIR/$3.riscv
}


END_INDEX=$((NUMCHUNKS/PARALLELISM_MAX))

for ((j=0; j < $END_INDEX; j++))
do
  for ((i=0; i < PARALLELISM_MAX; i++))
  do
    buildbench external $BENCH_DATA_DIR $(($j * $PARALLELISM_MAX + $i)) &
  done
  wait
done


TAIL_START=$((END_INDEX * PARALLELISM_MAX))

for ((i=$TAIL_START; i < $NUMCHUNKS; i++))
do
  buildbench external $BENCH_DATA_DIR $i &
done

wait
