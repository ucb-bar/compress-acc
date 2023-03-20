set -ex

ZSTDDIR=$(pwd)/../../software/zstd

cd $ZSTDDIR
git checkout dev
make