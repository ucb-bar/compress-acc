package chipyard

import org.chipsalliance.cde.config.{Config}

class ZstdCompressorRocketConfig extends Config(
  new compressacc.WithZstdCompressor ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig)
