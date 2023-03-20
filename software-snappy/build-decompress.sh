#!/usr/bin/env bash

set -ex

BASEDIR=$(pwd)

NUMCHUNKS=16

COMP_OR_DECOMP=decompress

function buildbench() {

    INPUTDIR="$BASEDIR/../software/benchmarks/$2/"
    FINAL_OUTPUT_DIR="$BASEDIR/snappy-$COMP_OR_DECOMP-$1-baremetal/"
    OUTPUTDIR="$FINAL_OUTPUT_DIR/build-$3/"

    mkdir -p $OUTPUTDIR

    cd $OUTPUTDIR
    INPUT_FILE_BASE=$INPUTDIR/$3
    INPUT_FILE=$INPUT_FILE_BASE.raw
    INPUT_FILE_COMPRESSED=$INPUT_FILE_BASE.comp

    cp $BASEDIR/splitter.py .
    cp $BASEDIR/*.c .
    cp $BASEDIR/*.h .

    python3 splitter.py $INPUTDIR $OUTPUTDIR $NUMCHUNKS $3

    TEST_FILE_NAME="test-$COMP_OR_DECOMP.c"
    TEST_FILE_O="test-$COMP_OR_DECOMP.o"

    riscv64-unknown-elf-gcc -fno-common -fno-builtin-printf -specs=htif_nano.specs -c $TEST_FILE_NAME
    riscv64-unknown-elf-gcc -fno-common -fno-builtin-printf -specs=htif_nano.specs -c accellib.c
    riscv64-unknown-elf-gcc -static -specs=htif_nano.specs $TEST_FILE_O accellib.o -o $FINAL_OUTPUT_DIR/$3.riscv


}

for i in {0..15}
do
    buildbench external "HyperCompressBench/extracted_benchmarks/Snappy-DECOMPRESS" $i &
done

wait
