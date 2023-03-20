import os
import argparse
import matplotlib.pyplot as plt
import matplotlib.colors as mcolors
from matplotlib.patches import FancyBboxPatch
import numpy as np


parser = argparse.ArgumentParser(description="Draw stuff")
parser.add_argument("--compress-csv", type=str, default="SAMPLE_CSV_COMPRESS.csv", help="compression result file")
parser.add_argument("--decompress-csv", type=str, default="SAMPLE_CSV_DECOMPRESS.csv", help="compression result file")
parser.add_argument("--xeon-csv", type=str, default="SAMPLE_CSV_XEON.csv", help="xeon result file")
parser.add_argument("--output-dir", type=str, default="hyper_results", help="Output directory to place plots")
parser.add_argument("--figsize-x", type=float, default=24.0, help="")
parser.add_argument("--figsize-y", type=float, default=14.0, help="")
parser.add_argument("--dpi", type=float, default=300.0, help="")
parser.add_argument("--fontsize", type=int, default=50, help="")
parser.add_argument("--labelsize", type=int, default=50, help="")
parser.add_argument("--handlelength", type=int, default=0.8, help="")
parser.add_argument("--plot-linewidth", type=int, default=8, help="")

args = parser.parse_args()

figsize = (args.figsize_x, args.figsize_y)
dpi = args.dpi
fontsize = args.fontsize
labelsize = args.labelsize
linewidth = args.plot_linewidth
handlelength = args.handlelength

bar_width = 0.13
bar_space = 0.14

gridlinewidth=3



BLUE = mcolors.CSS4_COLORS["cornflowerblue"]
RED = mcolors.CSS4_COLORS["red"]
ORANGE = mcolors.CSS4_COLORS["darkorange"]
GREEN = mcolors.CSS4_COLORS["forestgreen"]
YELLOW = mcolors.CSS4_COLORS["gold"]

all_placements = ["RoCC", "Chiplet", "PCIeNoCache", "PCIeLocalCache"]
all_placements_set = set(all_placements)

placement_colors = {
  "RoCC": BLUE,
  "Chiplet": RED,
  "PCIeLocalCache": GREEN,
  "PCIeNoCache": YELLOW
}


accel64k14ht_area = 0.0


def parse_comp_result_file():
  f = open(args.xeon_csv, "r")
  lines = f.readlines()
  f.close()

  xeon_comp_bytes = 0
  xeon_time = 0.0
  for line in lines:
    words = line.replace("\n", "").split(",")
    if "COMPRESS" == words[0]:
      xeon_comp_bytes = float(words[2])
      xeon_time = float(words[3])

  f = open(args.compress_csv, "r")
  lines = f.readlines()
  f.close()

  data_ht9 = dict()
  data_ht14 = dict()
  for line in lines:
    words = line.replace("\n", "").split(",")
    if words[0] in all_placements_set:
      placement = words[0]
      sram = int(words[1])
      ht_log2 = int(words[2])
      uncomp_bytes = int(words[3])
      accel_comp_bytes = int(words[4])
      accel_cycles = int(words[5])
      area = float(words[6])

      accel_time = float(accel_cycles) / float(2.0 * 1000 * 1000 * 1000)

      comp_ratio = xeon_comp_bytes / accel_comp_bytes
      speedup = xeon_time / accel_time

      if ht_log2 == 14:
        if sram not in data_ht14.keys():
          data_ht14[sram] = list()
        data_ht14[sram].append(((placement, speedup), comp_ratio, area))

        if sram == (64 * 1024):
          global accel64k14ht_area
          accel64k14ht_area = area
      else:
        if sram not in data_ht9.keys():
          data_ht9[sram] = list()
        data_ht9[sram].append(((placement, speedup), comp_ratio, area))
    else:
      print(words)
      print("skipping")
      continue
  return (data_ht9, data_ht14)


def split_data_compression(data):
  keys = list(data.keys())
  keys.sort(reverse=True)
  data_sorted = {i: data[i] for i in keys}
  speedup = dict()
  comp_ratio = dict()
  area = dict()

  for (k, v) in data_sorted.items():
    for ((p, s), c, a) in v:
      for cur_p in all_placements:
        if p == cur_p:
          if p not in speedup:
            speedup[p] = list()
          speedup[p].append(s)

          if p not in comp_ratio:
            comp_ratio[p] = list()
          comp_ratio[p].append(c)

          if p not in area:
            area[p] = list()
          area[p].append(a)
  area_norm = list(map(lambda x: x/accel64k14ht_area, area["RoCC"]))
  return (keys, speedup, comp_ratio["RoCC"], area_norm)

def get_sram_names(sram_int):
  K = 1024
  sram_str = list()
  for s in sram_int:
    size = s // K
    sram_str.append(f"{size}K")
  return sram_str


def plot_compression(data, name):
  (sram, speedup, comp_ratio, area) = split_data_compression(data)
  sram_str = get_sram_names(sram)

  fig = plt.figure(figsize=figsize, dpi=dpi, clear=True)

  # Speedup
  ax1 = fig.add_subplot(1, 1, 1)
  ax1.set_xlabel("SRAM Size (B)", fontsize=fontsize)
  ax1.xaxis.labelpad = 10

  ax1.set_ylabel("Speedup vs. Xeon", fontsize=fontsize)
  ax1.yaxis.labelpad = 40

  ax1.tick_params(axis="x", which="major", direction="inout", labelsize=labelsize)
  ax1.tick_params(axis="y", which="major", direction="inout", labelsize=labelsize)

  ax1.grid(axis="y", linewidth=gridlinewidth) # add horizontal grid lines
  ax1.set_axisbelow(True)


  x = np.arange(len(sram))
  spacing = bar_space
  width = bar_width
  multiplier = 0

  max_speedup = 0
  for (p, s) in speedup.items():
    max_speedup = max(max_speedup, max(s))
    offset = spacing * multiplier
    rects = ax1.bar(x + offset, s, width, label=p, color=placement_colors[p])
    multiplier += 1

# ax1.set_xticks(x + spacing, sram_str)
# ax1.set_xlabel(sram_str)

  n = int(max_speedup / 5.0) + 1
  ax1.set_ylim(0, n * 5)
  ax1.set_yticks(ticks=[i * 5 for i in range(n + 1)])
  ax1.spines["bottom"].set_linewidth(gridlinewidth)


  # Compression Ratio
  print(comp_ratio)
  ax2 = ax1.twinx()
  ax2.set_ylabel("Ratio", fontsize=fontsize)
  ax2.tick_params(axis="y", which="major", direction="inout", labelsize=fontsize)
  ax2.yaxis.labelpad = 40

  ax2.plot(range(len(sram_str)), comp_ratio,
           linewidth=linewidth,
           color=GREEN,
           label="Compression Ratio vs. SW")

  # Area
  print(area)
  ax3 = ax1.twinx()
  ax3.tick_params(right=False)
  ax3.set_yticklabels([])
  ax3.get_shared_y_axes().join(ax2, ax3)
  ax3.plot(range(len(sram_str)), area,
           linewidth=linewidth,
           color=ORANGE,
           label="Area vs. 64K14HT Accel.")

  max_ratio_value = max(max(area), max(comp_ratio))
  n = int(max_ratio_value / 0.25) + 1

  ax3.set_ylim(0, n * 0.25)
  ax2.set_yticks(ticks=[i * 0.25 for i in range(n + 1)])

  ax1.legend(loc="upper center", ncol=len(speedup.keys()), bbox_to_anchor=(0.5, 1.25), fontsize=fontsize, frameon=False, handlelength=handlelength)
  ax2.legend(loc="upper left", bbox_to_anchor=(-0.1, 1.17), fontsize=fontsize, frameon=False, handlelength=handlelength)
  ax3.legend(loc="upper right", bbox_to_anchor=(1.1, 1.17), fontsize=fontsize, frameon=False, handlelength=handlelength)

  plt.xticks(ticks=x+spacing, labels=sram_str)
  plt.subplots_adjust(left=0.25, right=0.88, top=0.80, bottom=0.15)


  axes = [ax1, ax2, ax3]
  for ax in axes:
    rside = ax.spines["right"]
    rside.set_visible(False)

    lside = ax.spines["left"]
    lside.set_visible(False)

    tside = ax.spines["top"]
    tside.set_visible(False)

  plt.tight_layout()
  plt.savefig(os.path.join(args.output_dir, f"{name}.pdf"), format="pdf")



def draw_compression_plot():
  (data_ht9, data_ht14) = parse_comp_result_file()
  plot_compression(data_ht9, "snappy-compression-ht9")
  plot_compression(data_ht14, "snappy-compression-ht14")


def parse_decomp_result_file():
  f = open(args.xeon_csv, "r")
  lines = f.readlines()
  f.close()

  xeon_time = 0.0
  for line in lines:
    words = line.replace("\n", "").split(",")
    if "DECOMPRESS" == words[0]:
      xeon_time = float(words[3])

  f = open(args.decompress_csv, "r")
  lines = f.readlines()
  f.close()

  data = dict()
  for line in lines:
    words = line.replace("\n", "").split(",")
    if words[0] in all_placements_set:
      placement = words[0]
      sram = int(words[1])
      accel_cycles = int(words[2])
      area = float(words[4])

      accel_time = float(accel_cycles) / float(2.0 * 1000 * 1000 * 1000)
      speedup = xeon_time / accel_time

      if sram not in data:
        data[sram] = list()

      data[sram].append(((placement, speedup), area))
    else:
      print(words)
      print("skipping")
      continue
  return data


def split_data_decompression(data):
  keys = list(data.keys())
  keys.sort(reverse=True)
  data_sorted = {i: data[i] for i in keys}
  speedup = dict()
  area = dict()

  for (k, v) in data_sorted.items():
    for ((p, s), a) in v:
      for cur_p in all_placements:
        if p == cur_p:
          if p not in speedup:
            speedup[p] = list()
          speedup[p].append(s)

          if p not in area:
            area[p] = list()
          area[p].append(a)

  area_norm = list(map(lambda x: x/area["RoCC"][0], area["RoCC"]))
  return (keys, speedup, area_norm)

def plot_decompression(data, name):
  (sram, speedup, area) = split_data_decompression(data)
  sram_str = get_sram_names(sram)

  fig = plt.figure(figsize=figsize, dpi=dpi, clear=True)

  # Speedup
  ax1 = fig.add_subplot(1, 1, 1)
  ax1.set_xlabel("SRAM Size (B)", fontsize=fontsize)
  ax1.xaxis.labelpad = 10

  ax1.set_ylabel("Speedup vs. Xeon", fontsize=fontsize)
  ax1.yaxis.labelpad = 40

  ax1.tick_params(axis="x", which="major", direction="inout", labelsize=labelsize)
  ax1.tick_params(axis="y", which="major", direction="inout", labelsize=labelsize)

  ax1.grid(axis="y", linewidth=gridlinewidth) # add horizontal grid lines
  ax1.set_axisbelow(True)

  x = np.arange(len(sram))
  spacing = bar_space
  width = bar_width
  multiplier = 0

  max_speedup = 0
  for (p, s) in speedup.items():
    max_speedup = max(max_speedup, max(s))
    offset = spacing * multiplier
    rects = ax1.bar(x + offset, s, width, label=p, color=placement_colors[p])
    multiplier += 1

# ax1.set_xticks(x + spacing, sram_str)

  n = int(max_speedup / 2.0) + 1
  ax1.set_ylim(0, n * 2)
  ax1.set_yticks(ticks=[i * 2 for i in range(n + 1)])
  ax1.spines["bottom"].set_linewidth(gridlinewidth)


  # Area
  print(area)
  ax2 = ax1.twinx()
  ax2.set_ylabel("Area vs. 64K Accel", fontsize=fontsize)
  ax2.tick_params(axis="y", which="major", direction="inout", labelsize=fontsize)
  ax2.yaxis.labelpad = 40

  ax2.plot(range(len(sram_str)), area,
           linewidth=linewidth,
           color=ORANGE,
           label="Area Normalized")

  max_ratio_value = max(area)
  n = int(max_ratio_value / 0.25)

  ax2.set_ylim(0, n * 0.25)
  ax2.set_yticks(ticks=[i * 0.25 for i in range(n + 1)])

  ax1.legend(loc="upper center", ncol=len(speedup.keys()), bbox_to_anchor=(0.5, 1.25), fontsize=fontsize, frameon=False, columnspacing=1.2, handlelength=handlelength)
  ax2.legend(loc="upper center", bbox_to_anchor=(0.5, 1.17), fontsize=fontsize, frameon=False, handlelength=handlelength)

  plt.subplots_adjust(left=0.1, right=0.88, top=0.80, bottom=0.15)


  axes = [ax1, ax2]
  for ax in axes:
    rside = ax.spines["right"]
    rside.set_visible(False)

    lside = ax.spines["left"]
    lside.set_visible(False)

    tside = ax.spines["top"]
    tside.set_visible(False)

  plt.xticks(ticks=x+spacing, labels=sram_str)
  plt.tight_layout()
  plt.savefig(os.path.join(args.output_dir, f"{name}.pdf"), format="pdf")



def draw_decompression_plot():
  data = parse_decomp_result_file()
  plot_decompression(data, "snappy-decompression")


def main():
  draw_compression_plot()
  draw_decompression_plot()

if __name__=="__main__":
  main()
