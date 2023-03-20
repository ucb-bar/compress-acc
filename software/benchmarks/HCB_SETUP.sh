#!/usr/bin/env bash

set -ex

RDIR=$(pwd)

SNAPPY_TEST_TOOL=../../../../../software-snappy/snappy/build-writecomp/snappy_test_tool

cd HyperCompressBench/source_data


mkdir -p Calgary
cd Calgary
wget http://corpus.canterbury.ac.nz/resources/calgary.tar.gz
tar -xvf calgary.tar.gz
rm calgary.tar.gz
cd ../

mkdir -p Canterbury
cd Canterbury
wget http://corpus.canterbury.ac.nz/resources/cantrbry.tar.gz
tar -xvf cantrbry.tar.gz
rm cantrbry.tar.gz
cd ../

mkdir -p Silesia
cd Silesia
wget https://sun.aei.polsl.pl//~sdeor/corpus/silesia.zip
unzip silesia.zip
rm silesia.zip
cd ../

mkdir -p Snappy
wget https://github.com/google/snappy/archive/refs/tags/1.1.10.zip
unzip 1.1.10.zip
rm 1.1.10.zip

SNAPPY_DATA=snappy-1.1.10/testdata/

mv $SNAPPY_DATA/fireworks.jpeg Snappy/
mv $SNAPPY_DATA/geo.protodata Snappy/
mv $SNAPPY_DATA/html Snappy/
mv $SNAPPY_DATA/html_x_4 Snappy/
mv $SNAPPY_DATA/kppkn.gtb Snappy/
mv $SNAPPY_DATA/paper-100k.pdf Snappy/
mv $SNAPPY_DATA/urls.10K Snappy/

rm -rf snappy-1.1.10

cd ../

python3 reconstruct.py


cd extracted_benchmarks/Snappy-COMPRESS
$SNAPPY_TEST_TOOL *

cd ../Snappy-DECOMPRESS
$SNAPPY_TEST_TOOL *
