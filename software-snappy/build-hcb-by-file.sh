#!/usr/bin/env bash

set -ex

BASEDIR=$(pwd)



function buildbench_compress() {
    INPUTDIR="$BASEDIR/../software/benchmarks/HyperCompressBench/extracted_benchmarks/Snappy-COMPRESS"
    OUTPUTDIR="$BASEDIR/tests/hcb-snappy"

    if [ ! -d $OUTPUTDIR ]
    then
      mkdir -p $OUTPUTDIR
    fi

    cd $BASEDIR
    xxd -i -n benchmark_data_uncomp $INPUTDIR/$1 > benchmark_data_compress.h
    xxd -i -n benchmark_data_comp   $INPUTDIR/$1.comp >> benchmark_data_compress.h

    riscv64-unknown-elf-gcc -fno-common -fno-builtin-printf -specs=htif_nano.specs -c test-compress.c
    riscv64-unknown-elf-gcc -fno-common -fno-builtin-printf -specs=htif_nano.specs -c accellib.c
    riscv64-unknown-elf-gcc -static -specs=htif_nano.specs test-compress.o accellib.o -o $OUTPUTDIR/comp-$1.riscv
}

function buildbench_decompress() {
    INPUTDIR="$BASEDIR/../software/benchmarks/HyperCompressBench/extracted_benchmarks/Snappy-DECOMPRESS"
    OUTPUTDIR="$BASEDIR/tests/hcb-snappy"

    if [ ! -d $OUTPUTDIR ]
    then
      mkdir -p $OUTPUTDIR
    fi

    cd $BASEDIR
    xxd -i -n benchmark_data_uncomp $INPUTDIR/$1 > benchmark_data_decompress.h
    xxd -i -n benchmark_data_comp   $INPUTDIR/$1.comp >> benchmark_data_decompress.h

    riscv64-unknown-elf-gcc -fno-common -fno-builtin-printf -specs=htif_nano.specs -c test-decompress.c
    riscv64-unknown-elf-gcc -fno-common -fno-builtin-printf -specs=htif_nano.specs -c accellib.c
    riscv64-unknown-elf-gcc -static -specs=htif_nano.specs test-decompress.o accellib.o -o $OUTPUTDIR/decomp-$1.riscv
}

buildbench_compress   009990_cl0_ws16
buildbench_decompress 009997_cl0_ws16
