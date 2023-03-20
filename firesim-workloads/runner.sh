

git clone https://github.com/inikep/lzbench.git
cd lzbench

git checkout 609d783118ca6026aa0d1bab7e485a62b6c7e4f0

git apply ../LZBENCH_DIFF

make -j4

cd ../Snappy-DECOMPRESS
rm -rf *.comp

../lzbench/lzbench -esnappy -t1,1 -o4 * &> ~/XEON_DECOMPRESS_RESULT



cd ../Snappy-COMPRESS
rm -rf *.comp

../lzbench/lzbench -esnappy -t1,1 -o4 * &> ~/XEON_COMPRESS_RESULT
