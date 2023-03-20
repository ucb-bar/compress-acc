import sys
import os
from collections import defaultdict

results_basedir = "../../../sims/firesim/deploy/results-workload/"
all_result_directories = os.listdir(results_basedir)

final_dir_map = defaultdict(list)


for directory in all_result_directories:
    if directory.endswith("-ZSTD-DECOMPRESS-ROCC"):
        final_dir_map["RoCC"].append(results_basedir + directory)
        final_dir_map["Spec16"].append(results_basedir + directory)
    elif directory.endswith("-ZSTD-DECOMPRESS-CHIPLET"):
        final_dir_map["Chiplet"].append(results_basedir + directory)
    elif directory.endswith("-ZSTD-DECOMPRESS-PCIEC"):
        final_dir_map["PCIeLocalCache"].append(results_basedir + directory)
    elif directory.endswith("-ZSTD-DECOMPRESS-PCIENC"):
        final_dir_map["PCIeNoCache"].append(results_basedir + directory)
    elif directory.endswith("-ZSTD-DECOMPRESS-SPEC32"):
        final_dir_map["Spec32"].append(results_basedir + directory)
    elif directory.endswith("-ZSTD-DECOMPRESS-SPEC4"):
        final_dir_map["Spec4"].append(results_basedir + directory)


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
    2**16: 1.899,
    2**15: 1.815,
    2**14: 1.773,
    2**13: 1.752,
    2**12: 1.741,
    2**11: 1.736
}

def collect_data_for_dir(directories, placement):
    inputdirs = directories

    all_files = []
    if placement == 'Spec32':
        all_files = [inputdir+'/'+x for x in os.listdir(inputdir) if x.startswith('uartlog')]
    else:
        for inputdir in inputdirs:
            all_files += list(map(lambda x: inputdir + "/" + x + "/uartlog", filter(lambda x: x != ".monitoring-dir", os.listdir(inputdir))))


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
        if placement == 'Spec32':
            area = 2.245
        elif placement == 'Spec4':
            area = 1.623
        else:
            area = area_cached[sram]

        if not (placement == 'Spec16' and sram != 65536):
            print(f"{placement},{sram},{cyc},{dat},{area}")
        #print(f"sram: {sram}")
        #print(f"GB/s: {data_by_sram_size[sram]/cycles_by_sram_size[sram]*2}")

        #print(f"GB/s: {data_by_sram_size[sram]/*2}")


print("placement,sram_size,cycles,uncomp_data_size,area")

for placement, directories in directories:
    collect_data_for_dir(directories, placement)


