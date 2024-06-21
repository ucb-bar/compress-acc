# ZStd Compressor SW

## Quickstart
- To compile the accelerator test code run:

```bash
./build-hcb-single-file.sh
```

- Running RTL simulations:

```bash
make run-binary-hex CONFIG=ZstdCompressorRocketConfig BINARY=<filename>
```

- You can also use a python script to check for correctness (as checking in the target machine takes a long time)

```bash
python get-compressed.py --hwlog <sim output directory>/<binary name>.out --algo zstd
make
./check.x86
```
