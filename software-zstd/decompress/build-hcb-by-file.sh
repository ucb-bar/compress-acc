#!/usr/bin/env bash

set -ex

BASEDIR=$(pwd)
INPUTDIR="$BASEDIR/../../software/benchmarks/HyperCompressBench/extracted_benchmarks/ZSTD-DECOMPRESS"
OUTPUTDIR="$BASEDIR/../tests/hcb-zstd-decompress"
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
      mkdir -p $OUTPUTDIR
    fi

    cd $OUTPUTDIR
    INPUT_FILE=$INPUTDIR/$1
    OUTPUT_FILE="$OUTPUTDIR/$1.zst"

    $ZSTD_COMPRESSOR $INPUT_FILE -o $OUTPUT_FILE

    cd $BASEDIR
    xxd -i -n benchmark_uncompressed_data $INPUT_FILE > benchmark_data.h
    xxd -i -n benchmark_compressed_data $OUTPUT_FILE >> benchmark_data.h
    rm $OUTPUT_FILE

    riscv64-unknown-elf-g++ -DRISCV -fno-common -fno-builtin-printf -specs=htif_nano.specs -c test.c
    riscv64-unknown-elf-g++ -DRISCV -fno-common -fno-builtin-printf -specs=htif_nano.specs -c accellib.c
    riscv64-unknown-elf-g++ -DRISCV -static -specs=htif_nano.specs test.o accellib.o -o $OUTPUTDIR/$1.riscv
}

# buildbench 009990_cl0_ws16
buildbench 000543_cl0_ws25
