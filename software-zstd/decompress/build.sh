#!/usr/bin/env bash

set -ex

BASEDIR=$(pwd)
INPUTDIR="/scratch/joonho.whangbo/silesia-slice-1kB-first"
OUTPUTDIR="$BASEDIR/../zstd-silesia-1k-baremetal-decomp"
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

    cd $OUTPUTDIR
    INPUT_FILE=$INPUTDIR/$1
    OUTPUT_FILE="$OUTPUTDIR/$1.zst"

    $ZSTD_COMPRESSOR $INPUT_FILE -o $OUTPUT_FILE



    cd $BASEDIR
    xxd -i -n benchmark_uncompressed_data $INPUT_FILE > benchmark_data.h
    xxd -i -n benchmark_compressed_data $OUTPUT_FILE >> benchmark_data.h

    rm $OUTPUT_FILE


    riscv64-unknown-elf-g++ -DRISCV -fno-common -fno-builtin-printf -specs=htif_nano.specs -c test.c
    riscv64-unknown-elf-g++ -DRISCV -fno-common -fno-builtin-printf -specs=htif_nano.specs -c accel.c
    riscv64-unknown-elf-g++ -DRISCV -static -specs=htif_nano.specs test.o accel.o -o $OUTPUTDIR/$1.riscv

}

function buildbench_all() {
  for i in {1..12}
  do
    buildbench "$i"
  done
}

compile_zstd
buildbench_all

