
RISCV_PFX=riscv64-unknown-elf-
RISCV_GCC=$(RISCV_PFX)g++
RISCV_OBJDUMP=$(RISCV_PFX)objdump

OBJECT_OPT=-fno-common -fno-builtin-printf -specs=htif_nano.specs
BINARY_OPT=-static -specs=htif_nano.specs
OBJECT_DUMP_OPT=--disassemble-all

# BINARY_OPT += -DXML_CHUNK
# BINARY_OPT += -DDICKENS_CHUNK
# BINARY_OPT += -DNOACCEL_DEBUG

TARGET=test
TARGET_RISCV=$(TARGET).riscv
TARGET_OBJDUMP=$(TARGET).riscv.dump

TARGET_SNAPPY=test-snappy
TARGET_SNAPPY_RISCV=$(TARGET_SNAPPY).riscv

CHECK_HOST=check.x86
CHECK_SNAPPY_HOST=check-snappy.x86

JUNK += $(TARGET_RISCV) $(TARGET_OBJDUMP) $(CHECK_HOST)

all: $(TARGET_RISCV) $(TARGET_OBJDUMP) $(CHECK_HOST) $(TARGET_SNAPPY_RISCV) $(CHECK_SNAPPY_HOST)

$(TARGET_RISCV): test.c accellib.c accellib.h zstd_decompress.c zstd_decompress.h benchmark_data.h
	$(RISCV_GCC) $(BINARY_OPT) -o $@ $^

$(TARGET_OBJDUMP): $(TARGET_RISCV)
	$(RISCV_OBJDUMP) $(OBJECT_DUMP_OPT) $< > $@

$(TARGET_SNAPPY_RISCV): test-snappy.c accellib.c accellib.h benchmark_data.h
	$(RISCV_GCC) $(BINARY_OPT) -o $@ $^

$(CHECK_HOST): check.c zstd_decompress.c zstd_decompress.h benchmark_data.h compressed_bytes.h
	gcc -DRUN_ON_HOST -w -o $@ $^

$(CHECK_SNAPPY_HOST): check-snappy.c benchmark_data.h compressed_bytes.h ../../software-snappy/snappy/build/libsnappy.a
	g++ -DRUN_ON_HOST -w -o $@ $^

.PHONY: clean
clean:
	rm -f $(JUNK) *.o
