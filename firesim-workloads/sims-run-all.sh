#!/usr/bin/env bash

set -ex

./x86-runs.sh

cd ../../../sims/firesim/deploy

CONFIG_RELPATH=../../../generators/compress-acc/firesim-workloads/config_runtimes/
CONFIG_OTHER_RELPATH=../../../generators/compress-acc/firesim-workloads/config_others/
date

OLD_RESULTS_DIR="OLD_RESULTS-$(date +%Y-%m-%d__%H-%M-%S)"

# move any old results:
mkdir -p $OLD_RESULTS_DIR
mv results-workload/* $OLD_RESULTS_DIR/


COMMON_FSIM_ARGS="-b $CONFIG_OTHER_RELPATH/config_build.yaml -r $CONFIG_OTHER_RELPATH/config_build_recipes.yaml -a $CONFIG_OTHER_RELPATH/config_hwdb.yaml"

# $1 <- config file
# $2 <- override
run_firesim_workload () {
    firesim infrasetup -c $CONFIG_RELPATH/$1 $COMMON_FSIM_ARGS
    firesim runworkload -c $CONFIG_RELPATH/$1 $COMMON_FSIM_ARGS
}

launch_firesim_runfarm() {
    firesim launchrunfarm -c $CONFIG_RELPATH/$1 $COMMON_FSIM_ARGS
}

terminate_firesim_runfarm() {
    firesim terminaterunfarm -c $CONFIG_RELPATH/$1 $COMMON_FSIM_ARGS --forceterminate
}

build_firesim_driver () {
    firesim builddriver -c $CONFIG_RELPATH/$1 $COMMON_FSIM_ARGS
}

configs_to_run=(
    "config_runtime_compress_rocc.yaml"
    "config_runtime_compress_chiplet.yaml"
    "config_runtime_compress_pcie.yaml"
    "config_runtime_decompress_rocc.yaml"
    "config_runtime_decompress_chiplet.yaml"
    "config_runtime_decompress_pciec.yaml"
    "config_runtime_decompress_pcienc.yaml"
)

for confname in "${configs_to_run[@]}"
do
    echo "Building FireSim Driver for $confname"
    build_firesim_driver "$confname"
done

echo "Launching FireSim Run Farm for Workloads"
launch_firesim_runfarm "${configs_to_run[0]}"

for confname in "${configs_to_run[@]}"
do
    echo "Running FireSim Simulation for $confname"
    run_firesim_workload "$confname"
done

echo "Terminating FireSim Run Farm for Workloads"
terminate_firesim_runfarm "${configs_to_run[0]}"

date
