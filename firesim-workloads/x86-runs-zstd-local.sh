#!/usr/bin/env bash

set -ex


BASEDIR=$(pwd)
RUNDIR=$BASEDIR/hyper_results/x86-zstd
mkdir -p $RUNDIR

cp -r ../software/benchmarks/HyperCompressBench/extracted_benchmarks/ZSTD-* $RUNDIR
cp -r LZBENCH_DIFF $RUNDIR
cp runner-zstd.sh $RUNDIR

function collect_aggregate_results() {
    cd $RUNDIR

    rm ZSTD-COMPRESS/*.zst || true
    rm ZSTD-DECOMPRESS/*.zst || true
    rm XEON_ZSTD_COMPRESS_RESULT || true
    rm XEON_ZSTD_DECOMPRESS_RESULT || true

    ./runner-zstd.sh ALL 2>&1   | tee hyper-localrun-zstd
    cp $RUNDIR/XEON_ZSTD_DECOMPRESS_RESULT intermediates/
    cp $RUNDIR/XEON_ZSTD_COMPRESS_RESULT intermediates/
    cd $BASEDIR
    python xeon-postprocess-zstd.py &> hyper_results/XEON_ZSTD_FINAL_RESULT.csv
}


function collect_flamegraphs() {
    cd $RUNDIR

    if [ ! -d FlameGraph ]
    then
        git clone https://github.com/brendangregg/FlameGraph
    fi

    sudo perf record -F 999 -g ./runner-zstd.sh COMPRESS

    cd FlameGraph
    sudo perf script | ./stackcollapse-perf.pl ../perf.data > ../comp.out.perf-folded
    sudo ./flamegraph.pl ../comp.out.perf-folded > zstd-compress.svg

    sudo perf record -F 999 -a -g ./runner-zstd.sh DECOMPRESS
    sudo perf script | ./stackcollapse-perf.pl ../perf.data > ../decomp.out.perf-folded
    sudo ./flamegraph.pl decomp.out.perf-folded > zstd-decompress.svg
}
# collect_flamegraphs
collect_aggregate_results
