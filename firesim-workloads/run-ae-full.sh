#!/usr/bin/env bash

set -ex

{

# generate all software components
./build-all-sw.sh

# build host-side firesim drivers
# run all simulations
./sims-run-all.sh

# plot results from sim runs
./gen-all-plots.sh

echo "run-ae-full.sh complete."

} 2>&1 | tee run-ae-full-log
