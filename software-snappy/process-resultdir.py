

import sys
import os


results_basedir = "../../../sims/firesim/deploy/results-workload/"

all_result_directories = os.listdir(results_basedir)

final_dir_map = dict()


for directory in all_result_directories:
    if directory.endswith("-DECOMPRESS-ROCC"):
        final_dir_map["RoCC"] = results_basedir + directory
    elif directory.endswith("-DECOMPRESS-CHIPLET"):
        final_dir_map["Chiplet"] = results_basedir + directory
    elif directory.endswith("-DECOMPRESS-PCIEC"):
        final_dir_map["PCIeLocalCache"] = results_basedir + directory
    elif directory.endswith("-DECOMPRESS-PCIENC"):
        final_dir_map["PCIeNoCache"] = results_basedir + directory


config_order = [
    "RoCC",
    "Chiplet",
    "PCIeLocalCache",
    "PCIeNoCache",
]

directories = []

for conf in config_order:
    directories.append([conf, final_dir_map[conf]])


# cannot be re-collected due to licensing
#
# index by Hist SRAM Size
area_cached = {
    2**16: 0.431,
    2**15: 0.347,
    2**14: 0.305,
    2**13: 0.284,
    2**12: 0.273,
    2**11: 0.268
}

def collect_data_for_dir(direct, placement):
    inputdir = direct

    all_files = list(map(lambda x: inputdir + "/" + x + "/uartlog", filter(lambda x: x != ".monitoring-dir", os.listdir(inputdir))))


    import collections

    cycles_by_sram_size = collections.defaultdict(int)
    data_by_sram_size = collections.defaultdict(int)

    for filename in all_files:
        a = open(filename, 'r')
        b = a.readlines()
        a.close()

        for line in b:
            if "TOTAL: Took" in line:
                l = line.split(" ")
                sram = None
                cycles = None
                dataproc = None
                for ind, val in enumerate(l):
                    if val == "histsram":
                        sram = int(l[ind+1])
                    if val == "Took":
                        cycles = int(l[ind+1])
                    if val == "produced":
                        dataproc = int(l[ind+1])

                cycles_by_sram_size[sram] += cycles
                data_by_sram_size[sram] += dataproc


    for sram in sorted(cycles_by_sram_size.keys(), reverse=True):
        cyc = cycles_by_sram_size[sram]
        dat = data_by_sram_size[sram]
        area = area_cached[sram]
        print(f"{placement},{sram},{cyc},{dat},{area}")
        #print(f"sram: {sram}")
        #print(f"GB/s: {data_by_sram_size[sram]/cycles_by_sram_size[sram]*2}")

        #print(f"GB/s: {data_by_sram_size[sram]/*2}")


print("placement,sram_size,cycles,uncomp_data_size,area")

for placement, directory in directories:
    collect_data_for_dir(directory, placement)


