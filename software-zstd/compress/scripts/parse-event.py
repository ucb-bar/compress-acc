from pathlib import Path
import argparse
from typing import List, Dict, Tuple
import json

parser = argparse.ArgumentParser(description='args for parse-event.py')
parser.add_argument('--synth-printf-file', type=str, default='synthesized-prints.out0', help='synthesized printf file')
parser.add_argument('--uartlog', type=str, default='uartlog', help='firesim uartlog')
args = parser.parse_args()

class SweepConfig():
  histsram: int
  log2HTSize: int
  latency: int
  hascache: bool

  def __init__(self, histsram: int = 65536,
               log2HTSize: int = 14,
               latency: int = 1,
               hascache: bool = False) -> None:
    self.histsram = histsram
    self.log2HTSize = log2HTSize
    self.latency = latency
    self.hascache = hascache

  def __str__(self) -> str:
    return f'histSRAM-{self.histsram}-log2HTSize-{self.log2HTSize}-latency-{self.latency}-hascache-{self.hascache}'

#    0    1     2         3        4      5           6        7     8          9     10     11         12             13       14           15   16 17     18       19  20        21  22    23    24     25
# TOTAL: Took 16565677 cycles consumed 25746432 uncompressed bytes produced compsize 5678766 bytes SuccessNBenchmarks 188 TotalNBenchmarks 188 with histsram 65536 with log2HTSize 14 latency 1 hasCache 0
class BenchmarkInfo():
  uartlog: Path
  files_per_iter: int
  sweep_configs: List[SweepConfig]

  def __init__(self):
    self.uartlog = Path(args.uartlog)
    self.sweep_configs = list()

    total_files_run = 0
    with open(self.uartlog, 'r') as f:
      lines = f.readlines()
      for line in lines:
        words = line.split()
        if len(words) > 2 and words[0] == 'Start' and words[1] == 'cycle:':
          total_files_run += 1
        elif len(words) > 25 and words[0] == 'TOTAL:':
          print(words[25])
          self.sweep_configs.append(SweepConfig(
            int(words[18]),
            int(words[21]),
            int(words[23]),
            bool(words[25].replace(r'\r', ''))))

    if len(self.sweep_configs) == 0:
      self.files_per_iter = 1
      self.sweep_configs.append(SweepConfig())
    else:
      self.files_per_iter = total_files_run // len(self.sweep_configs)
# print(f'files per iteration: {self.files_per_iter}')

class Event():
  name: str
  cycle: int
  event_id: int
  parent_id: int
  unique_id: int
  root: bool

  def __init__(self, name: str, cycle: int, eid: int, pid: int) -> None:
    self.name = name
    self.cycle = cycle
    self.event_id = eid
    self.parent_id = pid
    self.unique_id = -1
    self.root = False

  def set_unique_id(self, uid: int) -> None:
    self.unique_id = uid

  def set_as_root(self) -> None:
    self.root = True

  def __str__(self) -> str:
    return f'{self.name} {self.cycle} {self.event_id} {self.parent_id} {self.unique_id}'

class EventGraph():
  config: SweepConfig
  events: Dict[int, List[Event]] # event_id -> List[Event]
  graph: Dict[int, List[Event]]  # unique_id -> List[Event]
  unique_id: int
  root_eid: int

  def __init__(self, cfg: SweepConfig) -> None:
    self.config = cfg
    self.events = dict()
    self.graph = dict()
    self.unique_id = 0

  def get_unique_id(self) -> int:
    ret = self.unique_id
    self.unique_id += 1
    return ret

  def add_root_event(self, e: Event) -> None:
    e.set_unique_id(self.get_unique_id())
    e.set_as_root()
    self.events[e.event_id] = [e]
    self.graph[e.unique_id] = list()
    self.root_eid = e.event_id

  def add_event(self, e: Event) -> bool:
    pid = e.parent_id

    # Parent not in graph, ignore this event
    if pid not in self.events:
      return True

    # Hack ignore other stuff for now
    if (pid == self.root_eid) and (e.name != 'LZ77Fire'):
      return True

    # Add event
    e.set_unique_id(self.get_unique_id())
    if e.event_id not in self.events:
      self.events[e.event_id] = list()
    self.events[e.event_id].append(e)

    # Dequeue parent event and add edge to the graph
    if len(self.events[pid])  == 0:
      return False

    parent_event = self.events[pid][0]
    if parent_event.unique_id not in self.graph:
      self.graph[parent_event.unique_id] = list()
    self.graph[parent_event.unique_id].append(e)

    if not parent_event.root:
      self.events[pid].pop(0)

    return False

  def cleanup_leaf_events(self) -> None:
    for (eid, events) in self.events.items():
      if len(events) > 0 and not events[0].root:
        for e in events:
          self.graph[e.unique_id] = list()

  def print_graph(self) -> None:
    for (uid, elist) in self.graph.items():
      for e in elist:
        print(e)

  def analyze(self) -> Dict[str, List[int]]:
    latency_stats: Dict[str, List[int]] = dict()
    stack = list()
    stack.append(self.graph[self.root_eid][0])

    while len(stack) > 0:
      top = stack.pop(-1)
      childs = self.graph[top.unique_id]
      for c in childs:
        if c.name not in latency_stats:
          latency_stats[c.name] = list()
        latency_stats[c.name].append(c.cycle - top.cycle)
        stack.append(c)
    return latency_stats

  def print_stats(self) -> None:
    stats = self.analyze()
    for (name, lats) in stats.items():
      print(name, sum(lats) / len(lats))

class Stack():
  def __init__(self):
    self.data = list()

  def push(self, x):
    self.data.append(x)

  def pop(self):
    ret = self.data.pop(-1)
    return ret

  def empty(self) -> bool:
    return len(self.data) == 0

def parse_event_line(line: str) -> Event:
  line = line.replace('\\\"', '').replace(',', '')
  words = line.split()[2:]
  return Event(words[7],
               int(words[5]),
               int(words[1]),
               int(words[3]))

def parse():
  bm_info = BenchmarkInfo()
# print(len(bm_info.sweep_configs))

  file_cnt = 0
  cur_bm = 0
  with open(Path(args.synth_printf_file), 'r') as f:
    lines = f.readlines()
    eventgraphs = list()
    for line in lines:
      if 'FHDR_WRITE' in line:
        eventgraphs.append(EventGraph(bm_info.sweep_configs[cur_bm]))
        event = parse_event_line(line)
        eventgraphs[-1].add_root_event(event)
        if (file_cnt % bm_info.files_per_iter) == bm_info.files_per_iter - 1:
          cur_bm += 1
        file_cnt += 1
      elif 'CYCLE:' in line:
        event = parse_event_line(line)
        eventgraphs[-1].add_event(event)

  for eg in eventgraphs:
    eg.cleanup_leaf_events()

  event_groups_by_cfg: Dict[SweepConfig, List[EventGraph]] = dict()

  for eg in eventgraphs:
    if eg.config not in event_groups_by_cfg:
      event_groups_by_cfg[eg.config] = list()
    event_groups_by_cfg[eg.config].append(eg)

  for (cfg, egs) in event_groups_by_cfg.items():
    print(cfg)
    all_stats: Dict[str, List[int]] = dict()
    for eg in egs:
      stats = eg.analyze()
      for (name, data) in stats.items():
        if name not in all_stats:
          all_stats[name] = list()
        all_stats[name] += data
    for (name, data) in all_stats.items():
      print(f'{name},{sum(data) / len(data)}')


def main():
  parse()

if __name__=="__main__":
  main()
