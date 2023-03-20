



COMP_total_uncompressed = 0
COMP_total_compressed = 0
COMP_total_time_s = 0

with open('intermediates/XEON_COMPRESS_RESULT', 'r') as xeon_data:
    f = xeon_data.readlines()

    for line in f:
        if line.startswith("snappy 2020"):
            l = line.split(",")
            compression_MBps = float(l[1])
            uncompressed_size = float(l[3])
            compressed_size = float(l[4])

            compression_Bps = compression_MBps * 1000000.0

            time_s = uncompressed_size / compression_Bps

            COMP_total_time_s += time_s
            COMP_total_uncompressed += uncompressed_size
            COMP_total_compressed += compressed_size



DECOMP_total_uncompressed = 0
DECOMP_total_compressed = 0
DECOMP_total_time_s = 0

with open('intermediates/XEON_DECOMPRESS_RESULT', 'r') as xeon_data:
    f = xeon_data.readlines()

    for line in f:
        if line.startswith("snappy 2020"):
            l = line.split(",")
            decompression_MBps = float(l[2])
            uncompressed_size = float(l[3])
            compressed_size = float(l[4])

            decompression_Bps = decompression_MBps * 1000000.0

            time_s = uncompressed_size / decompression_Bps

            DECOMP_total_time_s += time_s
            DECOMP_total_uncompressed += uncompressed_size
            DECOMP_total_compressed += compressed_size



print("OPERATION,uncomp_data_size,comp_data_size,time_s")
print(f"COMPRESS,{COMP_total_uncompressed},{COMP_total_compressed},{COMP_total_time_s}")
print(f"DECOMPRESS,{DECOMP_total_uncompressed},{DECOMP_total_compressed},{DECOMP_total_time_s}")


