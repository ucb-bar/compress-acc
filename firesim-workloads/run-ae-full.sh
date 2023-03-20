#!/usr/bin/env bash

set -ex

{

read -p "Do you want to run ZStd (warning, this may take 100 hours)? (y/n) " RUN_ZSTD
while true; do
  case $RUN_ZSTD in
    y | Y)
      echo "Will run ZStd once Snappy is finished"
      break
      ;;
    n | N)
      echo "Skipping ZStd, will only run Snappy"
      break
      ;;
    *)
      error "Invalid response"
      ;;
  esac
done


# generate all software components
./build-snappy-sw.sh

# build host-side firesim drivers
# run all simulations
./sims-run-snappy.sh

# plot results from sim runs
./gen-snappy-plots.sh



if [[ $RUN_ZSTD == "y" || $RUN_ZSTD == "Y" ]]; then
  # generate all software components
  ./build-zstd-sw.sh

  # build host-side firesim drivers
  # run all simulations
  ./sims-run-zstd.sh

  # plot results from sim runs
  ./gen-zstd-plots.sh
fi

echo "run-ae-full.sh complete."

} 2>&1 | tee run-ae-full-log
