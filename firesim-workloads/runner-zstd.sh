
M1_BASE_DIR=$PWD
LZBENCH_DIR=$M1_BASE_DIR/../../../software/zstd/

git clone https://github.com/inikep/lzbench.git
cd lzbench

git checkout 609d783118ca6026aa0d1bab7e485a62b6c7e4f0

git apply ../LZBENCH_DIFF

make -j4


function parse_filename() {
    cl_num=$(echo $1 | cut -f2 -d_)
    num=$(echo $cl_num | cut -c 3-)
    echo $num
}

function run_lzbench() {
    BENCHMARK_DIR=../ZSTD-$1
    echo $BENCHMARK_DIR

    rm -rf $BENCHMARK_DIR/*.comp

    FILE_NAMES=`ls $BENCHMARK_DIR/*`
    cd $BENCHMARK_DIR


    LZBENCH_RESULTS=$M1_BASE_DIR/XEON_ZSTD_$1_RESULT
    touch $LZBENCH_RESULTS

    for ENTRY in $FILE_NAMES
    do
        BASE_FILENAME=$(basename $ENTRY)
        CLEVEL=$(parse_filename $BASE_FILENAME)
        echo "${BASE_FILENAME} | ${CLEVEL}"
        CLEVEL_BASE_3=$((CLEVEL + 3))
        $LZBENCH_DIR/zstd -$CLEVEL_BASE_3 $ENTRY >> $LZBENCH_RESULTS
    done
}

if [ $1 == "ALL" ]
then
    run_lzbench COMPRESS
    run_lzbench DECCOMPRESS
fi

if [ $1 == "COMPRESS" ]
then
    run_lzbench COMPRESS
fi

if [ $1 == "DECOMPRESS" ]
then
    run_lzbench DECOMPRESS
fi
