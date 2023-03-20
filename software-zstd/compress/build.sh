#!/usr/bin/env bash

set -ex

BASEDIR=$(pwd)
INPUTDIR="$BASEDIR/../../software/benchmarks/export_benchmark_repo/ZSTD-COMPRESS"
OUTPUTDIR="$BASEDIR/../../../../sims/firesim/deploy/workloads/hyper-compress-bench"
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

    riscv64-unknown-elf-g++ -DRISCV -fno-common -fno-builtin-printf -specs=htif_nano.specs -c test.c
    riscv64-unknown-elf-g++ -DRISCV -fno-common -fno-builtin-printf -specs=htif_nano.specs -c accellib.c
    riscv64-unknown-elf-g++ -DRISCV -fno-common -fno-builtin-printf -specs=htif_nano.specs -c zstd_decompress.c
    riscv64-unknown-elf-g++ -DRISCV -static -specs=htif_nano.specs test.o accellib.o zstd_decompress.o -o $OUTPUTDIR/$1.riscv
}

# function buildbench_all() {
# for i in {10..40}
# do
# buildbench "0$i"
# done

# for i in {10..29}
# do
# buildbench $i
# done
# }

# buildbench_all


function buildbench_all() {
  FILE_NAMES=`ls $INPUTDIR/*`
  for ENTRY in $FILE_NAMES
  do
    buildbench $(basename $ENTRY)
  done
}

buildbench_all
