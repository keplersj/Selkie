package io.github.keplersj.selkie

import io.github.keplersj.selkie.engine.EngineEnv
import java.io.File

object TestEnv {
    fun env(dns: List<String> = emptyList()) =
        EngineEnv(File("/lib"), File("/files"), File("/cache"), 10234, 10234, dns)
}
