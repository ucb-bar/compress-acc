#!/usr/bin/env bash

set -ex

RDIR=$(pwd)

cd ../software-snappy

./build-snappy-test-tool.sh

cd ../software/benchmarks
./HCB_SETUP.sh

cd ../../software-snappy

./build-compress.sh
marshal install snappy-complete-external-baremetal.json

./build-decompress.sh
marshal install snappy-decompress-external-baremetal.json
