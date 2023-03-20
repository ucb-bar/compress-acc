#!/usr/bin/env bash

set -ex

cd ../../../sims/firesim/deploy

CONFIG_RELPATH=../../../generators/compress-acc/firesim-workloads/config_runtimes/
CONFIG_OTHER_RELPATH=../../../generators/compress-acc/firesim-workloads/config_others/
date

COMMON_FSIM_ARGS="-b $CONFIG_OTHER_RELPATH/config_build.yaml -r $CONFIG_OTHER_RELPATH/config_build_recipes.yaml -a $CONFIG_OTHER_RELPATH/config_hwdb.yaml"

configs_to_run=(
    "config_runtime_compress_rocc.yaml"
    "config_runtime_compress_chiplet.yaml"
    "config_runtime_compress_pcie.yaml"
    "config_runtime_decompress_rocc.yaml"
    "config_runtime_decompress_chiplet.yaml"
    "config_runtime_decompress_pciec.yaml"
    "config_runtime_decompress_pcienc.yaml"
)


run_all_bitstream_builds() {
    firesim buildbitstream -c "${configs_to_run[0]}" $COMMON_FSIM_ARGS
}

run_all_bitstream_builds

date
