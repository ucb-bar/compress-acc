

import sys
import os


results_basedir = "../../../sims/firesim/deploy/results-workload/"

all_result_directories = os.listdir(results_basedir)

final_dir_map = dict()

for directory in all_result_directories:
    if directory.endswith("-COMPRESS-ROCC"):
        final_dir_map["RoCC"] = results_basedir + directory
    elif directory.endswith("-COMPRESS-CHIPLET"):
        final_dir_map["Chiplet"] = results_basedir + directory
    elif directory.endswith("-COMPRESS-PCIE"):
        final_dir_map["PCIeNoCache"] = results_basedir + directory

config_order = [
    "RoCC",
    "Chiplet",
    "PCIeNoCache",
]

directories = []

for conf in config_order:
    directories.append([conf, final_dir_map[conf]])

# cannot be re-collected due to licensing
#
# index by log2HTSize, then Hist SRAM Size
area_cached = {
    9: {
        2**16: 0.460,
        2**15: 0.374,
        2**14: 0.331,
        2**13: 0.309,
        2**12: 0.299,
        2**11: 0.293
    },
    14: {
        2**16: 0.850,
        2**15: 0.764,
        2**14: 0.721,
        2**13: 0.699,
        2**12: 0.688,
        2**11: 0.683
    }
}

def process_dir(direct, placement, filter_ht):
    inputdir = direct

    all_files = list(map(lambda x: inputdir + "/" + x + "/uartlog", filter(lambda x: x != ".monitoring-dir", os.listdir(inputdir))))


    import collections

    cycles_by_sram_HT_size = collections.defaultdict(int)
    uncompressed_data_by_sram_HT_size = collections.defaultdict(int)
    compressed_data_by_sram_HT_size = collections.defaultdict(int)

    for filename in all_files:
        a = open(filename, 'r')
        b = a.readlines()
        a.close()

        for line in b:
            if "TOTAL: Took" in line:
                l = line.split(" ")
                sram = None
                cycles = None
                compressed_dataproc = None
                uncompressed_dataproc = None
                log2HTsize = None
                for ind, val in enumerate(l):
                    if val == "histsram":
                        sram = int(l[ind+1])
                    if val == "log2HTSize":
                        log2HTsize = int(l[ind+1])
                    if val == "Took":
                        cycles = int(l[ind+1])
                    if val == "consumed":
                        uncompressed_dataproc = int(l[ind+1])
                    if val == "compsize":
                        compressed_dataproc = int(l[ind+1])

                if (log2HTsize != filter_ht):
                    continue

                pair = (sram, log2HTsize)
                cycles_by_sram_HT_size[pair] += cycles
                uncompressed_data_by_sram_HT_size[pair] += uncompressed_dataproc
                compressed_data_by_sram_HT_size[pair] += compressed_dataproc

    for pair in sorted(cycles_by_sram_HT_size.keys(), key=lambda x: x[0], reverse=True):
        sram = pair[0]
        ht = pair[1]
        ucd = uncompressed_data_by_sram_HT_size[pair]
        cd =  compressed_data_by_sram_HT_size[pair]
        cyc = cycles_by_sram_HT_size[pair]
        area = area_cached[ht][sram]
        print(f"{placement},{sram},{ht},{ucd},{cd},{cyc},{area}")

print("placement,sram_size,ht_entries_log2,uncomp_data_size,comp_data_size,cycles,area")

filter_ht = 14
for placement, directory in directories:
    process_dir(directory, placement, filter_ht)


filter_ht = 9
for placement, directory in directories:
    process_dir(directory, placement, filter_ht)
