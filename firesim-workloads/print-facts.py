import csv
import argparse

parser = argparse.ArgumentParser(description="Print stuff")
parser.add_argument("--enable-zstd", action='store_true', help="enable zstd")
args = parser.parse_args()

snappy_accel_comp = "hyper_results/ACCEL_COMP_RESULTS.csv"
snappy_accel_decomp = "hyper_results/ACCEL_DECOMP_RESULTS.csv"
snappy_xeon_both = "hyper_results/XEON_FINAL_RESULT.csv"

if args.enable_zstd:
    zstd_accel_comp = "hyper_results/ACCEL_ZSTD_COMP_RESULTS.csv"
    zstd_accel_decomp = "hyper_results/ACCEL_ZSTD_DECOMP_RESULTS.csv"
    zstd_xeon_both = "hyper_results/XEON_ZSTD_FINAL_RESULT.csv"

def read_csv(filename):
    f = open(filename, 'r', newline='')
    reader = csv.DictReader(f)
    return [f, reader]


snappy_accel_comp_fr = read_csv(snappy_accel_comp)
snappy_accel_decomp_fr = read_csv(snappy_accel_decomp)
snappy_xeon_both_fr = read_csv(snappy_xeon_both)

if args.enable_zstd:
    zstd_accel_comp_fr = read_csv(zstd_accel_comp)
    zstd_accel_decomp_fr = read_csv(zstd_accel_decomp)
    zstd_xeon_both_fr = read_csv(zstd_xeon_both)

def filter_read(file_and_reader, pairs_dict, output_key):
    fileobj = file_and_reader[0]
    reader = file_and_reader[1]
    fileobj.seek(0)
    return_vals = []
    for row in reader:
        passed = True
        for k, v in pairs_dict.items():
            if row[k] != v:
                passed = False
                break
        if passed:
            return_vals.append(row[output_key])
    if len(return_vals) != 1:
        print("FAIL TOO MANY OR NO RESULTS: " + str(len(return_vals)))
        exit(1)

    fileobj.seek(0)
    return return_vals[0]

xeon_area_mm2 = 17.98
two_GHz = 2000000000.0
B_to_GB = 1000000000.0

xeon_decompress_time_s = float(filter_read(snappy_xeon_both_fr, {'OPERATION': 'DECOMPRESS'}, 'time_s'))
xeon_decompress_throughput_GBps = float(filter_read(snappy_xeon_both_fr, {'OPERATION': 'DECOMPRESS'}, 'uncomp_data_size')) / B_to_GB / xeon_decompress_time_s

xeon_compress_time_s = float(filter_read(snappy_xeon_both_fr, {'OPERATION': 'COMPRESS'}, 'time_s'))
xeon_compress_throughput_GBps = float(filter_read(snappy_xeon_both_fr, {'OPERATION': 'COMPRESS'}, 'uncomp_data_size')) / B_to_GB / xeon_compress_time_s
xeon_compression_ratio = float(filter_read(snappy_xeon_both_fr, {'OPERATION': 'COMPRESS'}, 'uncomp_data_size')) / float(filter_read(snappy_xeon_both_fr, {'OPERATION': 'COMPRESS'}, 'comp_data_size'))

if args.enable_zstd:
    zstd_xeon_decompress_time_s = float(filter_read(zstd_xeon_both_fr, {'OPERATION': 'DECOMPRESS'}, 'time_s'))
    zstd_xeon_decompress_throughput_GBps = float(filter_read(zstd_xeon_both_fr, {'OPERATION': 'DECOMPRESS'}, 'uncomp_data_size')) / B_to_GB / zstd_xeon_decompress_time_s

    zstd_xeon_compress_time_s = float(filter_read(zstd_xeon_both_fr, {'OPERATION': 'COMPRESS'}, 'time_s'))
    zstd_xeon_compress_throughput_GBps = float(filter_read(zstd_xeon_both_fr, {'OPERATION': 'COMPRESS'}, 'uncomp_data_size')) / B_to_GB / zstd_xeon_compress_time_s
    zstd_xeon_compression_ratio = float(filter_read(zstd_xeon_both_fr, {'OPERATION': 'COMPRESS'}, 'uncomp_data_size')) / float(filter_read(zstd_xeon_both_fr, {'OPERATION': 'COMPRESS'}, 'comp_data_size'))

def emit_paper_text():
    print("Abstract; 1. Introduction; 8. Conclusion")
    print("------------------------------------------")

    worst_speedup = xeon_decompress_time_s / (float(filter_read(snappy_accel_decomp_fr, {'placement': 'PCIeNoCache', 'sram_size': "2048"}, 'cycles')) / two_GHz)
    best_speedup = xeon_compress_time_s / (float(filter_read(snappy_accel_comp_fr, {'placement': 'RoCC', 'sram_size': "65536", 'ht_entries_log2': '14'}, 'cycles')) / two_GHz)

    largest_area = float(filter_read(snappy_accel_comp_fr, { 'placement': 'RoCC', 'sram_size': "65536", "ht_entries_log2": "14" }, 'area'))
    smallest_area = float(filter_read(snappy_accel_decomp_fr, { 'placement': 'RoCC', 'sram_size': "2048" }, 'area'))


    opt_compress_speedup = xeon_compress_time_s / (float(filter_read(snappy_accel_comp_fr, {'placement': 'RoCC', 'sram_size': "65536", 'ht_entries_log2': '14'}, 'cycles')) / two_GHz)
    opt_decompress_speedup = xeon_decompress_time_s / (float(filter_read(snappy_accel_decomp_fr, {'placement': 'RoCC', 'sram_size': "65536"}, 'cycles')) / two_GHz)

    opt_compress_area = float(filter_read(snappy_accel_comp_fr, {'placement': 'RoCC', 'sram_size': "65536", 'ht_entries_log2': '14'}, 'area'))
    opt_decompress_area = float(filter_read(snappy_accel_decomp_fr, {'placement': 'RoCC', 'sram_size': "65536"}, 'area'))
    abstract_text = f"""Our exploration spans a {best_speedup/worst_speedup}x range in CDPU speedup, {largest_area/smallest_area}× range in silicon area (for a single pipeline), and evaluates a variety of CDPU integration techniques to optimize CDPU designs for hyperscale contexts. Our final hyperscale-optimized CDPU instances are {opt_decompress_speedup}× to {opt_compress_speedup}× faster than a Xeon core, while consuming a small fraction ({opt_decompress_area/xeon_area_mm2*100}-{opt_compress_area/xeon_area_mm2*100}%) of the area of a single Xeon core.\n"""
    print(abstract_text)

    print("6.2. CDPU Design Space Exploration, Snappy Decompressor")
    print("--------------------------------------------------------")

    decompress_time_s_64K = (float(filter_read(snappy_accel_decomp_fr, {'placement': 'RoCC', 'sram_size': "65536"}, 'cycles')) / two_GHz)
    decompress_speedup_64K = xeon_decompress_time_s / decompress_time_s_64K

    decompress_throughput_64K = float(filter_read(snappy_accel_decomp_fr, {'placement': 'RoCC', 'sram_size': "65536"}, 'uncomp_data_size')) / B_to_GB / decompress_time_s_64K
    decompress_64K_area = float(filter_read(snappy_accel_decomp_fr, { 'placement': 'RoCC', 'sram_size': "65536" }, 'area'))
    section_text = f"""We see that the CDPU placed near-core (RoCC) with the largest on-accelerator window size (equal to Snappy’s SW maximum of 64 KB), achieves the highest speedup; it is over {decompress_speedup_64K}× faster than the Xeon ({decompress_throughput_64K} GB/s accelerated vs. {xeon_decompress_throughput_GBps} GB/s Xeon), while consuming {decompress_64K_area}mm2 of silicon area in 16nm. As a comparison, this is less than 2.4% of the area of a single modern Xeon Core Tile ({xeon_area_mm2}mm^2 in 14nm, reported in [63]).\n"""
    print(section_text)



    decompress_2K_area = float(filter_read(snappy_accel_decomp_fr, { 'placement': 'RoCC', 'sram_size': "2048" }, 'area'))
    area_reduction_2K_percent = (decompress_64K_area - decompress_2K_area) / decompress_64K_area * 100.0
    area_vs_xeon_2K_percent = decompress_2K_area / xeon_area_mm2 * 100.0

    decompress_time_s_2K = (float(filter_read(snappy_accel_decomp_fr, {'placement': 'RoCC', 'sram_size': "2048"}, 'cycles')) / two_GHz)
    decompress_speedup_2K = xeon_decompress_time_s / decompress_time_s_2K

    speedup_reduction_percent_2K = (decompress_speedup_64K - decompress_speedup_2K) / decompress_speedup_64K * 100.0

    section_text2 = f"""If we instead shrink the on-CDPU history to 2 KB, we find a potentially more fruitful design point: we can achieve a {area_reduction_2K_percent}% reduction in area for only a {speedup_reduction_percent_2K}% reduction in speedup (i.e., {decompress_speedup_2K}× speedup vs. Xeon while consuming {area_vs_xeon_2K_percent}% of the area).\n"""

    print(section_text2)



    decompress_time_s_64K_pcie = (float(filter_read(snappy_accel_decomp_fr, {'placement': 'PCIeNoCache', 'sram_size': "65536"}, 'cycles')) / two_GHz)
    decompress_speedup_64K_pcie = xeon_decompress_time_s / decompress_time_s_64K_pcie

    pcie_vs_nearcore_slowdown = decompress_time_s_64K_pcie / decompress_time_s_64K


    section_text3 = f"""Even with a 64K SRAM (no off-accelerator history lookups), we see that even the cost of loading/writing input/output data once over PCIe results in a significant ({pcie_vs_nearcore_slowdown}×) slowdown vs. the near-core CDPU, due to the large number of small decompressions in the fleet (Fig. 3c).\n"""
    print(section_text3)


    decompress_time_s_64K_chiplet = (float(filter_read(snappy_accel_decomp_fr, {'placement': 'Chiplet', 'sram_size': "65536"}, 'cycles')) / two_GHz)
    decompress_speedup_64K_chiplet = xeon_decompress_time_s / decompress_time_s_64K_chiplet

    section_text4 = f"""Considering the configuration with 64K history size, we can see that Chiplet integration is an attractive solution for a Snappy accelerator; it still achieves a {decompress_speedup_64K_chiplet}× speedup vs. the Xeon, despite the added latency.\n"""
    print(section_text4)


    print("6.3. CDPU Design Space Exploration, Snappy Compressor")
    print("------------------------------------------------------")


    compress_64K_14HT_area = float(filter_read(snappy_accel_comp_fr, { 'placement': 'RoCC', 'sram_size': "65536", 'ht_entries_log2': '14' }, 'area'))
    area_vs_xeon_64K_14HT_percent = compress_64K_14HT_area / xeon_area_mm2 * 100.0

    section_text5 = f"""This design consumes {compress_64K_14HT_area} mm2 in a 16nm process or about {area_vs_xeon_64K_14HT_percent}% the area of a Xeon Core [63].\n"""
    print(section_text5)

    compress_uncomp_data_size_64K = float(filter_read(snappy_accel_comp_fr, {'placement': 'RoCC', 'sram_size': "65536", 'ht_entries_log2': '14'}, 'uncomp_data_size'))
    compress_comp_data_size_64K = float(filter_read(snappy_accel_comp_fr, {'placement': 'RoCC', 'sram_size': "65536", 'ht_entries_log2': '14'}, 'comp_data_size'))
    compress_comp_ratio_64K = compress_uncomp_data_size_64K / compress_comp_data_size_64K

    xeon_vs_snappy_accel_comp_ratio_64K = (compress_comp_ratio_64K - xeon_compression_ratio) / xeon_compression_ratio * 100.0
    section_text6 = f"""Interestingly, the 64 KB SRAM design achieves a {xeon_vs_snappy_accel_comp_ratio_64K}% higher compression ratio than Snappy SW.\n"""
    print(section_text6)


    compress_uncomp_data_size_2K = float(filter_read(snappy_accel_comp_fr, {'placement': 'RoCC', 'sram_size': "2048", 'ht_entries_log2': '14'}, 'uncomp_data_size'))
    compress_comp_data_size_2K = float(filter_read(snappy_accel_comp_fr, {'placement': 'RoCC', 'sram_size': "2048", 'ht_entries_log2': '14'}, 'comp_data_size'))
    compress_comp_ratio_2K = compress_uncomp_data_size_2K / compress_comp_data_size_2K

    xeon_vs_snappy_accel_comp_ratio_2K = -1.0 * (compress_comp_ratio_2K - xeon_compression_ratio) / xeon_compression_ratio * 100.0
    compress_2K_14HT_area = float(filter_read(snappy_accel_comp_fr, { 'placement': 'RoCC', 'sram_size': "2048", 'ht_entries_log2': '14' }, 'area'))
    area_vs_64Kaccel_2K_14HT_percent = (compress_64K_14HT_area - compress_2K_14HT_area) / compress_64K_14HT_area * 100.0



    compress_uncomp_data_size_32K = float(filter_read(snappy_accel_comp_fr, {'placement': 'RoCC', 'sram_size': "32768", 'ht_entries_log2': '14'}, 'uncomp_data_size'))
    compress_comp_data_size_32K = float(filter_read(snappy_accel_comp_fr, {'placement': 'RoCC', 'sram_size': "32768", 'ht_entries_log2': '14'}, 'comp_data_size'))
    compress_comp_ratio_32K = compress_uncomp_data_size_32K / compress_comp_data_size_32K

    xeon_vs_snappy_accel_comp_ratio_32K = -1.0 * (compress_comp_ratio_32K - xeon_compression_ratio) / xeon_compression_ratio * 100.0
    compress_32K_14HT_area = float(filter_read(snappy_accel_comp_fr, { 'placement': 'RoCC', 'sram_size': "32768", 'ht_entries_log2': '14' }, 'area'))
    area_vs_64Kaccel_32K_14HT_percent = (compress_64K_14HT_area - compress_32K_14HT_area) / compress_64K_14HT_area * 100.0

    section_text7 = f"""As the SRAM size is reduced, we do see a drop-off in the achieved compression ratio as compared to software, ranging from an {xeon_vs_snappy_accel_comp_ratio_2K}% loss at 2 KB (with {area_vs_64Kaccel_2K_14HT_percent}% area savings) to a {xeon_vs_snappy_accel_comp_ratio_32K}% loss at 32 KB (with {area_vs_64Kaccel_32K_14HT_percent}% area savings).\n"""

    print(section_text7)

    compress_time_s_64K = (float(filter_read(snappy_accel_comp_fr, {'placement': 'RoCC', 'sram_size': "65536", 'ht_entries_log2': '14'}, 'cycles')) / two_GHz)
    compress_speedup_64K = xeon_compress_time_s / compress_time_s_64K

    compress_throughput_64K = float(filter_read(snappy_accel_comp_fr, {'placement': 'RoCC', 'sram_size': "65536", 'ht_entries_log2': '14'}, 'uncomp_data_size')) / B_to_GB / compress_time_s_64K


    compress_time_s_32K = (float(filter_read(snappy_accel_comp_fr, {'placement': 'RoCC', 'sram_size': "32768", 'ht_entries_log2': '14'}, 'cycles')) / two_GHz)
    compress_speedup_32K = xeon_compress_time_s / compress_time_s_32K

    compress_throughput_32K = float(filter_read(snappy_accel_comp_fr, {'placement': 'RoCC', 'sram_size': "32768", 'ht_entries_log2': '14'}, 'uncomp_data_size')) / B_to_GB / compress_time_s_32K


    compress_time_s_2K = (float(filter_read(snappy_accel_comp_fr, {'placement': 'RoCC', 'sram_size': "2048", 'ht_entries_log2': '14'}, 'cycles')) / two_GHz)
    compress_speedup_2K = xeon_compress_time_s / compress_time_s_2K

    compress_throughput_2K = float(filter_read(snappy_accel_comp_fr, {'placement': 'RoCC', 'sram_size': "2048", 'ht_entries_log2': '14'}, 'uncomp_data_size')) / B_to_GB / compress_time_s_2K

    section_text8 = f"""For example, the 64 KB configuration achieves over {compress_speedup_64K}× speedup compared to the Xeon ({compress_throughput_64K} GB/s accel. vs. {xeon_compress_throughput_GBps} GB/s Xeon). The various smaller configurations achieve between {compress_speedup_2K}× and {compress_speedup_32K}× speedup, losing performance only because of the increased amount of data they must write due to the lower achieved compression ratio.\n"""
    print(section_text8)



    worst_loss_of_speedup = 0.0

    for sram_size_bytes in [65536, 32768, 16384, 8192, 4096, 2048]:
        compress_time_s_loop_rocc = (float(filter_read(snappy_accel_comp_fr, {'placement': 'RoCC', 'sram_size': str(sram_size_bytes), 'ht_entries_log2': '14'}, 'cycles')) / two_GHz)
        compress_speedup_loop_rocc = xeon_compress_time_s / compress_time_s_loop_rocc

        compress_time_s_loop_chiplet = (float(filter_read(snappy_accel_comp_fr, {'placement': 'Chiplet', 'sram_size': str(sram_size_bytes), 'ht_entries_log2': '14'}, 'cycles')) / two_GHz)
        compress_speedup_loop_chiplet = xeon_compress_time_s / compress_time_s_loop_chiplet

        loss_of_speedup_percent = (compress_speedup_loop_rocc - compress_speedup_loop_chiplet) / compress_speedup_loop_rocc * 100.0
        if loss_of_speedup_percent > worst_loss_of_speedup:
            worst_loss_of_speedup = loss_of_speedup_percent


    pcie_speedups = []
    for sram_size_bytes in [65536, 32768, 16384, 8192, 4096, 2048]:
        compress_time_s_loop_pcie = (float(filter_read(snappy_accel_comp_fr, {'placement': 'PCIeNoCache', 'sram_size': str(sram_size_bytes), 'ht_entries_log2': '14'}, 'cycles')) / two_GHz)
        compress_speedup_loop_pcie = xeon_compress_time_s / compress_time_s_loop_pcie
        pcie_speedups.append(compress_speedup_loop_pcie)

    compress_speedup_64K_pcie = pcie_speedups[0]

    geom_speedup = 1.0
    for speedup in pcie_speedups:
        geom_speedup *= speedup
    geom_speedup = geom_speedup ** (1.0 / float(len(pcie_speedups)))

    section_text9 = f"""We see again that a Chiplet-integrated design performs very well, achieving less than {worst_loss_of_speedup}% loss of speedup vs. the near core design across the swath of SRAM sizes. PCIe again struggles, but fares much better than in the decompression case, with speedups shrinking to around {geom_speedup}×.\n"""
    print(section_text9)

    section_text10 = f"""In Figure 12 we can see that reducing the history window size to 2K for compression can result in negligible loss of speedup and a small, but potentially tolerable {xeon_vs_snappy_accel_comp_ratio_2K}% loss in compression ratio, while reducing accelerator area by {area_vs_64Kaccel_2K_14HT_percent}%.\n"""
    print(section_text10)




    compress_2K_9HT_area = float(filter_read(snappy_accel_comp_fr, { 'placement': 'RoCC', 'sram_size': "2048", 'ht_entries_log2': '9' }, 'area'))
    compress_2K_9HT_area_vs_xeon = compress_2K_9HT_area / xeon_area_mm2 * 100.0
    area_vs_64Kaccel_2K_9HT_percent = compress_2K_9HT_area / compress_64K_14HT_area * 100.0




    compress_uncomp_data_size_2K_9HT = float(filter_read(snappy_accel_comp_fr, {'placement': 'RoCC', 'sram_size': "2048", 'ht_entries_log2': '9'}, 'uncomp_data_size'))
    compress_comp_data_size_2K_9HT = float(filter_read(snappy_accel_comp_fr, {'placement': 'RoCC', 'sram_size': "2048", 'ht_entries_log2': '9'}, 'comp_data_size'))
    compress_comp_ratio_2K_9HT = compress_uncomp_data_size_2K_9HT / compress_comp_data_size_2K_9HT

    accel_2K_14HT_vs_snappy_accel_comp_ratio_2K_9HT = -1.0 * (compress_comp_ratio_2K_9HT - compress_comp_ratio_2K) / compress_comp_ratio_2K * 100.0

    section_text11 = f"""However, we can see that reducing the number of hash table entries can provide drastic area wins: a snappy compression accelerator with 2^9 hash table entires and a 2K history SRAM consumes only {area_vs_64Kaccel_2K_9HT_percent}% of the area of the full-size design (and only {compress_2K_9HT_area_vs_xeon}% of the area of a Xeon Core), with a negligible loss of speedup and while only increasing compression ratio loss by {accel_2K_14HT_vs_snappy_accel_comp_ratio_2K_9HT}% compared to the 2K history, 2^14 hash table entry design.\n"""
    print(section_text11)


    if args.enable_zstd:
        ##################
        print("6.4. CDPU Design Space Exploration, ZStd Decompressor")
        print("--------------------------------------------------------------------------------")

        zstd_decompress_time_s_64K = (float(filter_read(zstd_accel_decomp_fr, {'placement': 'RoCC', 'sram_size': "65536"}, 'cycles')) / two_GHz)
        zstd_decompress_speedup_64K = zstd_xeon_decompress_time_s / zstd_decompress_time_s_64K
        zstd_decompress_throughput_64K = float(filter_read(zstd_accel_decomp_fr, {'placement': 'RoCC', 'sram_size': "65536"}, 'uncomp_data_size')) / B_to_GB / zstd_decompress_time_s_64K
        zstd_decompress_64K_area = float(filter_read(zstd_accel_decomp_fr, { 'placement': 'RoCC', 'sram_size': "65536" }, 'area'))

        section_text_zd1 = f"""We see that the CDPU placed near-core (RoCC) with the largest on-accelerator window size achieves the highest speedup, which is {zstd_decompress_speedup_64K}× vs. the Xeon ({zstd_decompress_throughput_64K} GB/s accelerated vs. {zstd_xeon_decompress_throughput_GBps} GB/s Xeon).\n"""
        print(section_text_zd1)

        zstd_decompress_2K_area = float(filter_read(zstd_accel_decomp_fr, { 'placement': 'RoCC', 'sram_size': "2048" }, 'area'))
        zstd_area_reduction_2K_percent = (zstd_decompress_64K_area - zstd_decompress_2K_area) / zstd_decompress_64K_area * 100.0
        area_vs_xeon_2K_percent = decompress_2K_area / xeon_area_mm2 * 100.0

        section_text_zd2 = f"""The cost of the additional entropy decoding attenuates both the area savings and performance impact of reducing history SRAM compared to the Snappy decompressor; the overall savings moving from the 64K SRAM design ({zstd_decompress_64K_area} mm2 in 16nm) to the 2K SRAM design of the ZStd compressor is only {zstd_area_reduction_2K_percent}%.\n"""
        print(section_text_zd2)

        zstd_decompress_time_s_64K_pcie = (float(filter_read(zstd_accel_decomp_fr, {'placement': 'PCIeNoCache', 'sram_size': "65536"}, 'cycles')) / two_GHz)
        zstd_decompress_speedup_64K_pcie = zstd_xeon_decompress_time_s / zstd_decompress_time_s_64K_pcie
        ##################

        print("6.5. CDPU Design Space Exploration, ZStd Compressor")
        print("--------------------------------------------------------------------------------")

        zstd_compress_time_s_64K = (float(filter_read(zstd_accel_comp_fr, {'placement': 'RoCC', 'sram_size': "65536", 'ht_entries_log2': '14'}, 'cycles')) / two_GHz)
        zstd_compress_speedup_64K = xeon_compress_time_s / compress_time_s_64K

        zstd_compress_throughput_64K = float(filter_read(zstd_accel_comp_fr, {'placement': 'RoCC', 'sram_size': "65536", 'ht_entries_log2': '14'}, 'uncomp_data_size')) / B_to_GB / zstd_compress_time_s_64K
        zstd_compress_speedup = zstd_compress_throughput_64K / zstd_xeon_compress_throughput_GBps

        zstd_compress_uncomp_data_size_64K = float(filter_read(zstd_accel_comp_fr, {'placement': 'RoCC', 'sram_size': "65536", 'ht_entries_log2': '14'}, 'uncomp_data_size'))
        zstd_compress_comp_data_size_64K = float(filter_read(zstd_accel_comp_fr, {'placement': 'RoCC', 'sram_size': "65536", 'ht_entries_log2': '14'}, 'comp_data_size'))
        zstd_compress_comp_ratio_64K = zstd_compress_uncomp_data_size_64K / zstd_compress_comp_data_size_64K

        xeon_vs_zstd_accel_comp_ratio_64K = (zstd_compress_comp_ratio_64K / zstd_xeon_compression_ratio) * 100

        print(f"Looking first at compression ratio, we see that the accelerator achieves only {xeon_vs_zstd_accel_comp_ratio_64K}% of the compression ratio of software, likely primarily due to the fact that we are re-using the LZ77 encoder block as configured for Snappy.\n")
        print(f"With the caveat that compression ratio is reduced, the largest configuration of accelerator achieves a {zstd_compress_speedup}x speedup compared to the Xeon ({zstd_compress_throughput_64K} GB/s accelerated vs. {zstd_xeon_compress_throughput_GBps} GB/s Xeon).\n")

        zstd_pcie_speedups = []
        for sram_size_bytes in [65536, 32768, 16384, 8192, 4096, 2048]:
            zstd_compress_time_s_loop_pcie = (float(filter_read(zstd_accel_comp_fr, {'placement': 'PCIeNoCache', 'sram_size': str(sram_size_bytes), 'ht_entries_log2': '14'}, 'cycles')) / two_GHz)
            zstd_compress_speedup_loop_pcie = zstd_xeon_compress_time_s / zstd_compress_time_s_loop_pcie
            zstd_pcie_speedups.append(zstd_compress_speedup_loop_pcie)

        zstd_compress_speedup_64K_pcie = zstd_pcie_speedups[0]

    print("6.6. CDPU Design Space Exploration, Summary of Design-Space Exploration Lessons")
    print("--------------------------------------------------------------------------------")



    percent_savings_2K_9HT = 100.0 - area_vs_64Kaccel_2K_9HT_percent

    section_text12 = f"""Our design space exploration shows the importance of focusing not only on the micro-architectural design of CDPUs, but also their high-level parameters. By tuning these high-level parameters in the previous section, we observed for example, {best_speedup/worst_speedup}× differences in speedups and {percent_savings_2K_9HT}% savings in silicon area. Here, we summarize our key findings:\n"""
    print(section_text12)


    perf_compare_rocc_pcie = decompress_speedup_64K / decompress_speedup_64K_pcie
    perf_compare_rocc_chiplet = decompress_speedup_64K / decompress_speedup_64K_chiplet

    if not args.enable_zstd:
        did_not_run = "ZSTD EXPERIMENTS WERE NOT RUN."
        zstd_decompress_speedup_64K_pcie = did_not_run
        zstd_compress_speedup_64K_pcie = did_not_run
        zstd_compress_speedup_64K = did_not_run
        zstd_compress_throughput_64K = did_not_run
        zstd_decompress_throughput_64K = did_not_run
        total_area_zstd = did_not_run
    else:
        total_area_zstd = "5.7"

    section_text13 = f"""Decompression accelerator feasibility is very heavily af- fected by accelerator placement. Given data sizes ob- served in Google’s fleet, near-core accelerators ({decompress_speedup_64K}× speedup) perform over {perf_compare_rocc_pcie} times better than PCIe at- tached accelerators ({decompress_speedup_64K_pcie}× speedup for Snappy, {zstd_decompress_speedup_64K_pcie}× speedup for ZStd). Chiplets offer a rea- sonable middle ground, with our chiplet-integrated accel- erator ({decompress_speedup_64K_chiplet}× speedup) performing only {perf_compare_rocc_chiplet}× worse than the near-core accelerator.\n"""
    print(section_text13)

    section_text14 = f"""In contrast, compression is less sensitive to accelerator placement; we observe over {compress_speedup_64K_pcie}× speedup (Snappy) or {zstd_compress_speedup_64K_pcie}x speedup (ZStd) in the PCIe attached cases. However, the biggest performance gains are still seen for near-core and chiplet-integrated designs (around {compress_speedup_64K} to {zstd_compress_speedup_64K}× speedup for both Snappy and ZStd).\n"""
    print(section_text14)



    section_text15 = f"""Snappy decompression accelerator area is dominated by history size, which also affects speedup (but not compression ratio). Given data characteristics in Google’s fleet, a {area_reduction_2K_percent}% silicon area savings can be achieved by slightly sacrificing speedup ({decompress_speedup_2K}× vs. {decompress_speedup_64K}× speedup).\n"""
    print(section_text15)


    comp_ratio_total_loss_percent = (compress_comp_ratio_64K - compress_comp_ratio_2K_9HT) / compress_comp_ratio_64K * 100.0


    total_compress_size_reduce_percent = 100.0 - area_vs_64Kaccel_2K_9HT_percent

    section_text17 = f"""Snappy compression accelerator area is dominated both by his- tory buffer size and hash table size. When both are reduced, a negligible sacrifice in speedup and a {comp_ratio_total_loss_percent}% sacrifice in compression ratio can result in reducing accelerator silicon area by {total_compress_size_reduce_percent}%.\n"""
    print(section_text17)


    print("7. Related Work")
    print("-----------------")

    total_area = opt_compress_area + opt_decompress_area
    zstd_decompress_throughput_64K_spec32 = "ZSTD DECOMP SPEC32 RUN NOT INCLUDED"

    section_text18 = f"""Our results for compression ({compress_throughput_64K} GB/s Snappy, {zstd_compress_throughput_64K} GB/s ZStd) and decompression ({decompress_throughput_64K} GB/s Snappy, {zstd_decompress_throughput_64K_spec32} GB/s ZStd) are comparable, given our RISC- V SoC’s weaker memory system and algorithmic differences. In area terms, our academic prototype is similar, but could benefit from greater tuning/engineering effort, with our design consuming around {total_area} mm2 (Snappy) or {total_area_zstd} mm2 (ZStd) in a 16nm process.\n"""
    print(section_text18)



emit_paper_text()

# END
snappy_accel_comp_fr[0].close()
snappy_accel_decomp_fr[0].close()
snappy_xeon_both_fr[0].close()

if args.enable_zstd:
    zstd_accel_comp_fr[0].close()
    zstd_accel_decomp_fr[0].close()
    zstd_xeon_both_fr[0].close()

