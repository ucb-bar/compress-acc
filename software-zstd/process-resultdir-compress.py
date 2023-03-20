import os
import argparse
import matplotlib.pyplot as plt
import matplotlib.colors as mcolors
from matplotlib.patches import FancyBboxPatch
import numpy as np



parser = argparse.ArgumentParser(description="Obtain csv files for hypercompress-zstd")
args = parser.parse_args()

results_basedir = "../../../sims/firesim/deploy/results-workload/"

# 0      1    2        3      4        5        6            7     8        9        10      11    12                 13  14               15  16   17       18    19   20         21 22      23 24      25
# TOTAL: Took 11588313 cycles consumed 25746432 uncompressed bytes produced compsize 5678970 bytes SuccessNBenchmarks 188 TotalNBenchmarks 188 with histsram 65536 with log2HTSize 14 latency 1 hasCache 0


all_placements = ["RoCC", "Chiplet", "PCIeNoCache", "PCIeLocalCache"]
all_placements_set = set(all_placements)

placement_dict = {
  (1, 0): "RoCC",
  (50, 0): "Chiplet",
  (400, 0): "PCIeNoCache",
  (400, 1): "PCIeLocalCache"
}

area_cached = {
  14: {  
       2**16: 3.489998747,
       2**15: 3.405639603,
       2**14: 3.363460531,
       2**13: 3.342371495,
       2**12: 3.331827477,
       2**11: 3.326555968
  },
  9: {
    2**16: 3.101485854,
    2**15: 3.017125741,
    2**14: 2.9749457,
    2**13: 2.953855695,
    2**12: 2.943310709,
    2**11: 2.938038231
  }
}


accel64k14ht_area = 0.0

def parse_results():
  compress_acc_data = dict()

  for slot_dir in os.listdir(results_basedir):
    if "hyper-compress-bench-zstd-sweep" not in slot_dir:
      continue

    for slot in os.listdir(os.path.join(results_basedir, slot_dir)):
      if slot == ".monitoring-dir":
        continue
      uartlog = open(os.path.join(results_basedir, slot_dir, slot, "uartlog"))
      lines = uartlog.readlines()
      uartlog.close()


      for line in lines:
        words = line.split()
        if len(words) > 1 and "TOTAL" in words[0]:
          cycles = int(words[2])
          uncomp_bytes = int(words[5])
          comp_bytes = int(words[10])
          sram_size = int(words[18])
          htlog2 = int(words[21])
          latency = int(words[23])
          has_cache = int(words[25])

          cur_key = (htlog2, sram_size, latency, has_cache)
          if cur_key not in compress_acc_data.keys():
            compress_acc_data[cur_key] = (cycles, uncomp_bytes, comp_bytes)
          else:
            (cy, ub, cb) = compress_acc_data[cur_key]
            compress_acc_data[cur_key] = (cycles + cy, uncomp_bytes + ub, comp_bytes + cb)

  data_ht9 = dict()
  data_ht14 = dict()
  for (k, v) in compress_acc_data.items():
    (ht, sr, lat, hc) = k
    (cyc, ucd, cd) = v
    placement = placement_dict[(lat, hc)]
    area = area_cached[ht][sr]
    print(f"{placement},{sr},{ht},{ucd},{cd},{cyc},{area}")





def main():
  print("placement,sram_size,ht_entries_log2,uncomp_data_size,comp_data_size,cycles,area")
  parse_results()




if __name__ == "__main__":
  main()
