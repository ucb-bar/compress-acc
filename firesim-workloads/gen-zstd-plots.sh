#!/usr/bin/env bash

pip install --no-input matplotlib
pip install --no-input numpy

THISDIR=$(pwd)

ACCEL_DECOMP_RESULT=hyper_results/ACCEL_ZSTD_DECOMP_RESULTS.csv
ACCEL_COMP_RESULT=hyper_results/ACCEL_ZSTD_COMP_RESULTS.csv
XEON_RESULT=hyper_results/XEON_ZSTD_FINAL_RESULT.csv

cd ../software-zstd/

python process-resultdir-decompress.py &> ../firesim-workloads/$ACCEL_DECOMP_RESULT
python process-resultdir-compress.py &> ../firesim-workloads/$ACCEL_COMP_RESULT

cd $THISDIR

python draw-plot-zstd.py --compress-csv=$ACCEL_COMP_RESULT --decompress-csv=$ACCEL_DECOMP_RESULT --xeon-csv=$XEON_RESULT

python print-facts.py --enable-zstd &> hyper_results/FINAL_TEXT_SUMMARIES.txt

