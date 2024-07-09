import re
import argparse


parser = argparse.ArgumentParser(description="Extract the compressed byte stream")
parser.add_argument("--hwlog", dest="hwlog", type=str, required=True, help="log file from hw compression")
parser.add_argument("--out", dest="out", type=str, default="compressed_bytes.h", help="compressed bytes in xxd format")
parser.add_argument("--algo", dest="algo", type=str, required=True, help="zstd or snappy (algorithm used)")
args = parser.parse_args()


def getlines(filename):
  f = open(filename, 'r')
  lines = f.readlines()
  f.close()
  return lines


def xxd(data):
  out = open(args.out, 'w')
  out.write("unsigned char compressed[] = {\n")

  for (i, w) in enumerate(data):
    out.write(f"{w}, ")
    if i % 12 == 11:
      out.write("\n")

  out.write("};\n")
  out.write(f"unsigned int compressed_len = {len(data)};\n")
  out.close()

def get_compressed_bytes():
  lines = getlines(args.hwlog)
  data = list()
  for line in lines:
    words = line.split()
    if (len(words) >= 7) and "WRITE_BYTE" in words[2]:
      l2helper_name = words[7]

      if args.algo == "zstd" and "mf_seqwriter" in l2helper_name:
        continue

      try:
        data.append((words[4], words[6]))
      except:
        print("sth went wrong")
  return data

def get_original_size_bytes():
  lines = getlines(args.hwlog)
  for line in lines:
    words = line.split()
    if (len(words) >= 6) and "srcFileSize" in words[2]:
      try:
        return int(words[3].replace(",", ""), 0)
      except:
        print("went wrong")

def main():
  data = get_compressed_bytes()
  data.pop() # cmpflag
  data.sort()

# for (i, d) in data:
# print(i)

  if args.algo == "zstd":
    original_size = get_original_size_bytes()
    print(f"{len(data)}, {original_size}, {len(data)/original_size * 100}")

  written_bytes = [x[1] for x in data]
  xxd(written_bytes)
  print(f"[*] get-compressed done")

if __name__=="__main__":
  main()
