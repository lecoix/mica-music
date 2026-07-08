package com.mica.music.media

import com.mica.music.BuildConfig

/** Gate 4: P0 format trace and verbose PCM logs are debug/perf-only. */
internal object AudioPipelineDebugDiagnostics {
    val formatTraceEnabled: Boolean
        get() = BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "perf"
}
