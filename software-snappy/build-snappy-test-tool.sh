

set -ex


SNAPPYDIR=$(pwd)/snappy

cd $SNAPPYDIR

git checkout snappy_test_tool.cc
git apply ../SNAPPY_WRITE_COMP

mkdir -p build-writecomp
cd build-writecomp
cmake ../
make -j32

cd $SNAPPYDIR

git checkout snappy_test_tool.cc
git apply ../SNAPPY_WRITE_UNCOMP

mkdir -p build-writeuncomp
cd build-writeuncomp
cmake ../
make -j32


