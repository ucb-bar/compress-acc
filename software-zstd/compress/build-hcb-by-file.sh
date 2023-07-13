#!/usr/bin/env bash

set -ex

BASEDIR=$(pwd)


ZSTD_DIR="$BASEDIR/../../software/zstd"
RUNDIR="$ZSTD_DIR/programs"
ZSTD_COMPRESSOR="$RUNDIR/zstd"


CYDIR="$BASEDIR/../../../../"
VCSDIR=$CYDIR/sims/vcs
# CONFIG=ZstdCompressorHyperscaleRocketConfig
CONFIG=ZstdCompressorReducedAccuracyHyperscaleRocketConfig
ZSTD_BINARY_PATH="$BASEDIR/../../software/zstd/programs/zstd"

function compile_zstd() {
  cd "$ZSTD_DIR/lib"
  make -j$(nproc)
  cd "$ZSTD_DIR/programs"
  make -j$(nproc)
  cd $BASEDIR
}

compile_zstd

function buildbench() {
    INPUTDIR="$BASEDIR/../../software/benchmarks/HyperCompressBench/extracted_benchmarks/ZSTD-COMPRESS"
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

function buildbench_all() {
  FILE_NAMES=`ls $INPUTDIR/*`
  for ENTRY in $FILE_NAMES
  do
    buildbench $(basename $ENTRY)
  done
}

# buildbench_all

function runtest() {
  SIMOUTPUTDIR=$VCSDIR/output/chipyard.TestHarness.$CONFIG

  buildbench $1

  cd $VCSDIR
  make -j20 CONFIG=$CONFIG run-binary-debug-hex BINARY=$BASEDIR/$1.riscv

  cd $BASEDIR
  python $BASEDIR/../scripts/get-compressed.py --hwlog $SIMOUTPUTDIR/$1.out --algo zstd --out $BASEDIR/compressed_bytes.h
  make all
  ./check.x86
}

# runtest 007662_cl1_ws15
# runtest 000185_cl1_ws10

# buildbench 007662_cl1_ws15
buildbench 000185_cl1_ws10
