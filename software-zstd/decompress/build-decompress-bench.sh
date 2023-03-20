set -ex

BASEDIR=$(pwd)

NUMCHUNKS=200
PARALLELISM_MAX=10
ZSTD_BINARY_PATH="$BASEDIR/../../software/zstd/zstd"

BENCH_DATA_DIR="$BASEDIR/../../software/benchmarks/HyperCompressBench/extracted_benchmarks/ZSTD-DECOMPRESS"
COMP_OR_DECOMP=decompress

function buildbench(){
    FINAL_OUTPUT_DIR="$BASEDIR/zstd-$COMP_OR_DECOMP-$1-baremetal/"
    mkdir -p $FINAL_OUTPUT_DIR/RoCC
    mkdir -p $FINAL_OUTPUT_DIR/Chiplet
    mkdir -p $FINAL_OUTPUT_DIR/PCIeC
    mkdir -p $FINAL_OUTPUT_DIR/PCIeNC
    OUTPUTDIR="$FINAL_OUTPUT_DIR/build-$3/"
    
    mkdir -p $OUTPUTDIR

    cd $OUTPUTDIR
    
    cp $BASEDIR/splitter.py .
    cp $BASEDIR/*.c .
    cp $BASEDIR/*.h .

    python3 splitter.py $BENCH_DATA_DIR $OUTPUTDIR $NUMCHUNKS $3 $ZSTD_BINARY_PATH

    riscv64-unknown-elf-gcc -fno-common -fno-builtin-printf -specs=htif_nano.specs -c accellib.c
    riscv64-unknown-elf-gcc -fno-common -fno-builtin-printf -specs=htif_nano.specs -c test-decompress-rocc.c
    riscv64-unknown-elf-gcc -static -specs=htif_nano.specs accellib.o test-decompress-rocc.o -o $FINAL_OUTPUT_DIR/RoCC/$3.riscv

    riscv64-unknown-elf-gcc -fno-common -fno-builtin-printf -specs=htif_nano.specs -c test-decompress-chiplet.c
    riscv64-unknown-elf-gcc -static -specs=htif_nano.specs accellib.o test-decompress-chiplet.o -o $FINAL_OUTPUT_DIR/Chiplet/$3.riscv

    riscv64-unknown-elf-gcc -fno-common -fno-builtin-printf -specs=htif_nano.specs -c test-decompress-pciec.c
    riscv64-unknown-elf-gcc -static -specs=htif_nano.specs accellib.o test-decompress-pciec.o -o $FINAL_OUTPUT_DIR/PCIeC/$3.riscv

    riscv64-unknown-elf-gcc -fno-common -fno-builtin-printf -specs=htif_nano.specs -c test-decompress-pcienc.c
    riscv64-unknown-elf-gcc -static -specs=htif_nano.specs accellib.o test-decompress-pcienc.o -o $FINAL_OUTPUT_DIR/PCIeNC/$3.riscv
}

END_INDEX=$((NUMCHUNKS/PARALLELISM_MAX))

for ((j=0; j < $END_INDEX; j++))
do
    for ((i=0; i < PARALLELISM_MAX; i++))
    do
        buildbench external $BENCH_DATA_DIR $(($j * $PARALLELISM_MAX + $i)) &
    done
    wait
done


TAIL_START=$((END_INDEX * PARALLELISM_MAX))

for ((i=$TAIL_START; i < $NUMCHUNKS; i++))
do
    buildbench external $BENCH_DATA_DIR $i &
done

wait
