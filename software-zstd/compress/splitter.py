


# required arguments:
# abs path to benchmark input files
# abs path to output/build dir (usually current dir)
# number of chunks
# this chunkno (zero indexed)
# ZSTD binary path (for generated compressed version of input)
#
# assume current working
#



import sys

assert len(sys.argv) == 6

input_path = sys.argv[1]
output_path = sys.argv[2]
num_chunks = int(sys.argv[3])
this_chunk = int(sys.argv[4])
ZSTD_BINARY_PATH = sys.argv[5]


import os

all_benchmarks = os.listdir(input_path)


import collections

filenames_with_size = []

for filename in all_benchmarks:
    if not filename.endswith(".comp"):
        filenames_with_size.append([filename, os.stat(input_path + "/" + filename).st_size])


filenames_with_size = sorted(filenames_with_size, key=lambda x: x[0])
filenames_with_size = sorted(filenames_with_size, key=lambda x: x[1], reverse=True)
#filenames_with_size = sorted(filenames_with_size, key=lambda x: x[1])


chunks_list = [ [] for chunk in range(num_chunks) ]


for index, filename_size_pair in enumerate(filenames_with_size):
    chunks_list[index % len(chunks_list)].append(filename_size_pair)

import subprocess

my_chunk = chunks_list[this_chunk]


includes = []
num_benchmarks = 0
benchmark_names = []
uncompressed_arrays = []
uncompressed_lens_array = []
compressed_arrays = []
compressed_lens_array = []


for benchno, fileinfo in enumerate(my_chunk):
    filename = fileinfo[0]
    file_size = fileinfo[1]


    header_base_name = f"benchmark_data_{benchno}.h"
    output_file = f"{output_path}/{header_base_name}"
    input_file_raw = f"{input_path}/{filename}"
    filename_fixed = filename.replace(".raw", '')
    filename_fixed_comp = f"{filename_fixed}.comp"
    input_file_comp = f"{input_path}/{filename_fixed_comp}"

    # check if compressed input file existed, otherwise make it
    if filename_fixed_comp not in all_benchmarks:
        # need to compress file

        compression_level = int(filename_fixed_comp.split("_")[1].replace("cl", ""))

        compression_level_command = ""

        if compression_level == 0:
            compression_level = 3

        if compression_level < 0:
            negate_compression_level = -1 * compression_level
            compression_level_command = f"--fast={negate_compression_level}"
        else:
            if compression_level > 19:
                compression_level_command = f"--ultra -{compression_level}"
            else:
                compression_level_command = f"-{compression_level}"

        subprocess.run(f"{ZSTD_BINARY_PATH} {compression_level_command} {input_file_raw} -o {input_file_comp}", shell=True)


    with open(output_file, 'w', encoding='utf-8') as f:
        f.write("\n")

    uncompressed_varname = f"benchmark_uncompressed_data_{benchno}"
    compressed_varname = f"benchmark_compressed_data_{benchno}"

    subprocess.run(f"xxd -i -n {uncompressed_varname} {input_file_raw} >> {output_file}", shell=True)
    subprocess.run(f"xxd -i -n {compressed_varname} {input_file_comp} >> {output_file}", shell=True)

    benchmark_name_variable = f"benchmark_name_{benchno}"
    with open(output_file, 'a', encoding='utf-8') as f:
        f.write(f"char * {benchmark_name_variable} = \"{filename_fixed}\";\n")


    includes.append(f"#include \"{header_base_name}\"")
    num_benchmarks += 1
    benchmark_names.append(f"&{benchmark_name_variable}")
    uncompressed_arrays.append(f"(char*)(&{uncompressed_varname})")
    uncompressed_lens_array.append(f"&{uncompressed_varname}_len")
    compressed_arrays.append(f"(char*)(&{compressed_varname})")
    compressed_lens_array.append(f"&{compressed_varname}_len")


overall_header = f"{output_path}/benchmark_data_helper.h"

with open(overall_header, 'w', encoding='utf-8') as f:
    f.write("\n".join(includes))
    f.write("\n\n")
    f.write(f"unsigned int num_benchmarks = {num_benchmarks};\n\n")

    f.write("char ** benchmark_names[] = {\n")
    f.write(",\n".join(benchmark_names))
    f.write("};\n\n")

    f.write("unsigned char * benchmark_uncompressed_data_arrays[] = {\n")
    f.write(",\n".join(uncompressed_arrays))
    f.write("};\n\n")

    f.write("unsigned int * benchmark_uncompressed_data_len_array[] = {\n")
    f.write(",\n".join(uncompressed_lens_array))
    f.write("};\n\n")


    f.write("unsigned char * benchmark_compressed_data_arrays[] = {\n")
    f.write(",\n".join(compressed_arrays))
    f.write("};\n\n")

    f.write("unsigned int * benchmark_compressed_data_len_array[] = {\n")
    f.write(",\n".join(compressed_lens_array))
    f.write("};\n\n")

