#!/usr/bin/env bash

set -ex

CURRENT_DIR=$(pwd)

# Zstd Compress

./x86-runs-zstd.sh

cd ../../../sims/firesim/deploy

CONFIG_RELPATH=../../../generators/compress-acc/firesim-workloads/config_runtimes/
CONFIG_OTHER_RELPATH=../../../generators/compress-acc/firesim-workloads/config_others/
date

OLD_RESULTS_DIR="OLD_RESULTS-$(date +%Y-%m-%d__%H-%M-%S)"

# move any old results:
mkdir -p $OLD_RESULTS_DIR
mv results-workload $OLD_RESULTS_DIR/
mkdir -p results-workload
touch results-workload/.PLACEHOLDER

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
    "config_runtime_zstd_compress-0.yaml"
    "config_runtime_zstd_compress-1.yaml"
    "config_runtime_zstd_compress-2.yaml"
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


date

# Zstd Decompress
cd $CURRENT_DIR

cd ../../../
git apply scripts/no-tracer-diff


cd $CURRENT_DIR

date

configs_to_run_zstd_decompress=(
    "config_runtime_zstd_decompress_rocc.yaml"
    "config_runtime_zstd_decompress_chiplet.yaml"
    "config_runtime_zstd_decompress_pciec.yaml"
    "config_runtime_zstd_decompress_pcienc.yaml"
)

for i in {0..12}
do
    marshal install ../software-zstd/decompress/zstd-decompress-external-baremetal-$i.json
done

riscv64-unknown-elf-gcc -static -fno-common -fno-builtin-printf -specs=htif_nano.specs ../software-zstd/decompress/dummy_program.c -o ../software-zstd/decompress/zstd-decompress-external-baremetal/dummy_program.riscv

# Build driver
echo "Building FireSim Driver for ${configs_to_run_zstd_decompress[0]}"
build_firesim_driver "${configs_to_run_zstd_decompress[0]}"
build_firesim_driver "${configs_to_run_zstd_decompress[1]}"
build_firesim_driver "${configs_to_run_zstd_decompress[2]}"
build_firesim_driver "${configs_to_run_zstd_decompress[3]}"


# Infrasetup and runworkload
# RoCC
echo "Running FireSim Simulation for ${configs_to_run_zstd_decompress[0]}"
cp ../software-zstd/decompress/zstd-decompress-external-baremetal/RoCC/* ../software-zstd/decompress/zstd-decompress-external-baremetal
for i in {0..12}
do
    marshal install ../software-zstd/decompress/zstd-decompress-external-baremetal-$i.json
    run_firesim_workload "${configs_to_run_zstd_decompress[0]}"
done
# Chiplet
echo "Running FireSim Simulation for ${configs_to_run_zstd_decompress[1]}"
cp ../software-zstd/decompress/zstd-decompress-external-baremetal/Chiplet/* ../software-zstd/decompress/zstd-decompress-external-baremetal
for i in {0..12}
do
    marshal install ../software-zstd/decompress/zstd-decompress-external-baremetal-$i.json
    run_firesim_workload "${configs_to_run_zstd_decompress[1]}"
done
# PCIe+Cache
echo "Running FireSim Simulation for ${configs_to_run_zstd_decompress[2]}"
cp ../software-zstd/decompress/zstd-decompress-external-baremetal/PCIeC/* ../software-zstd/decompress/zstd-decompress-external-baremetal
for i in {0..12}
do
    marshal install ../software-zstd/decompress/zstd-decompress-external-baremetal-$i.json
    run_firesim_workload "${configs_to_run_zstd_decompress[2]}"
done
# PCIe+NoCache
echo "Running FireSim Simulation for ${configs_to_run_zstd_decompress[3]}"
cp ../software-zstd/decompress/zstd-decompress-external-baremetal/PCIeNC/* ../software-zstd/decompress/zstd-decompress-external-baremetal
for i in {0..12}
do
    marshal install ../software-zstd/decompress/zstd-decompress-external-baremetal-$i.json
    run_firesim_workload "${configs_to_run_zstd_decompress[3]}"
done

# Terminate FPGAs
echo "Terminating FireSim Run Farm for Workloads"
terminate_firesim_runfarm "${configs_to_run_zstd_decompress[0]}"

date

