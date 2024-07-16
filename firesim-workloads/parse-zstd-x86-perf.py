from typing import Dict, List
from pathlib import Path


type Result = Dict[str, List[float]]



def parse(input_file_path: Path) -> Result:
  result: Result = dict()

  with open(input_file_path, 'r') as f:
    lines = f.readlines()
    for line in lines:
      words = line.split()
      us = float(words[-1])
      name = ' '.join(words[1:-1])
      if name not in result:
        result[name] = list()
      result[name].append(us)

  return result

def process_result(result: Result) -> None:
  for (stage, latencies) in result.items():
    avg_lat = sum(latencies) / len(latencies)
    print(f'{stage},{avg_lat}')


def main():
  input_file_path = Path('hyper_results/x86-zstd/XEON_ZSTD_COMPRESS_RESULT')
  result = parse(input_file_path)
  process_result(result)

if __name__=="__main__":
  main()
