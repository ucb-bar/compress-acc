#!/usr/bin/env bash

set -ex

BASEDIR=$(pwd)
INPUTDIR="$BASEDIR/../../software/benchmarks/export_benchmark_repo/ZSTD-COMPRESS"
OUTPUTDIR="$BASEDIR/../../../../sims/firesim/deploy/workloads/hyper-compress-bench-snappy"
ZSTD_DIR="$BASEDIR/../../software/zstd"

RUNDIR="$ZSTD_DIR/programs"
ZSTD_COMPRESSOR="$RUNDIR/zstd"

function compile_zstd() {
  cd "$ZSTD_DIR/lib"
  make -j$(nproc)
  cd "$ZSTD_DIR/programs"
  make -j$(nproc)
  cd $BASEDIR
}

function buildbench() {

    if [ ! -d $OUTPUTDIR ]
    then
      mkdir $OUTPUTDIR
    fi

    cd $BASEDIR
    xxd -i -n benchmark_raw_data $INPUTDIR/$1 > benchmark_data.h

    riscv64-unknown-elf-g++ -DRISCV -fno-common -fno-builtin-printf -specs=htif_nano.specs -c test-snappy.c
    riscv64-unknown-elf-g++ -DRISCV -fno-common -fno-builtin-printf -specs=htif_nano.specs -c accellib.c
    riscv64-unknown-elf-g++ -DRISCV -static -specs=htif_nano.specs test-snappy.o accellib.o -o $OUTPUTDIR/$1.riscv
}

function buildbench_all() {
  FILE_NAMES=`ls $INPUTDIR/*`
  for ENTRY in $FILE_NAMES
  do
    buildbench $(basename $ENTRY)
  done
}

# buildbench_all

buildbench 000001_cl1_ws10
buildbench 000012_cl1_ws11
buildbench 000003_cl1_ws12
buildbench 000010_cl1_ws13
buildbench 000016_cl1_ws14
buildbench 000089_cl0_ws15
buildbench 000018_cl0_ws15
buildbench 000093_cl0_ws15
buildbench 000091_cl0_ws15
