#!/usr/bin/env bash

set -ex

BASEDIR=$(pwd)


ZSTD_DIR="$BASEDIR/../../software/zstd"
RUNDIR="$ZSTD_DIR/programs"
ZSTD_COMPRESSOR="$RUNDIR/zstd"


CYDIR=$BASEDIR/../../../../
VCSDIR=$CYDIR/sims/vcs
CONFIG=ZstdCompressorReducedAccuracyHyperscaleRocketConfig
ZSTD_BINARY_PATH="$BASEDIR/../../software/zstd/programs/zstd"

function compile_zstd() {
  cd "$ZSTD_DIR/lib"
  make -j$(nproc)
  cd "$ZSTD_DIR/programs"
  make -j$(nproc)
  cd $BASEDIR
}

# compile_zstd

function buildbench() {
# INPUTDIR="$BASEDIR/../../software/benchmarks/HyperCompressBench/extracted_benchmarks/ZSTD-COMPRESS"
    INPUTDIR="$BASEDIR/example-files"
    OUTPUTDIR="$BASEDIR/../../../../sims/firesim/deploy/workloads/hyper-compress-bench-zstd"

    if [ ! -d $OUTPUTDIR ]
    then
      mkdir $OUTPUTDIR
    fi

    cd $BASEDIR
    xxd -i -n benchmark_raw_data $INPUTDIR/$1 > benchmark_data.h

    riscv64-unknown-elf-gcc -fno-common -fno-builtin-printf -specs=htif_nano.specs -c test.c
    riscv64-unknown-elf-gcc -fno-common -fno-builtin-printf -specs=htif_nano.specs -c accellib.c
    riscv64-unknown-elf-gcc -fno-common -fno-builtin-printf -specs=htif_nano.specs -c zstd_decompress.c
    riscv64-unknown-elf-gcc -static -specs=htif_nano.specs test.o accellib.o zstd_decompress.o -o $OUTPUTDIR/$1.riscv
    cp $OUTPUTDIR/$1.riscv .
}

function runtest() {
  SIMOUTPUTDIR=$VCSDIR/output/chipyard.TestHarness.$CONFIG

  buildbench $1

  # Run VCS simulation
  cd $VCSDIR
  make -j20 CONFIG=$CONFIG run-binary-hex BINARY=$BASEDIR/$1.riscv

  cd $BASEDIR
  # Python script that will grab all the accelerator written bytes and output a compressed_bytes.h file
  python get-compressed.py --hwlog $SIMOUTPUTDIR/$1.out --algo zstd --out $BASEDIR/compressed_bytes.h

  # Using the above compressed_bytes.h file from the above, decompress it to check for correctness
  # Faster than checking for correctness in the target machine
  make all
  ./check.x86
}

function set_baremetal_max_heap_size() {
  echo $RISCV
  cp $BASEDIR/../../htif.ld $RISCV/riscv64-unknown-elf/lib
}

set_baremetal_max_heap_size

# runtest 000185_cl1_ws10 # 1k
buildbench 009987_cl0_ws12 # 4k
# runtest 007662_cl1_ws15 # 32k
