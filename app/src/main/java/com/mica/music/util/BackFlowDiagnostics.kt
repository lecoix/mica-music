package com.mica.music.util

internal const val BackFlowDebugTag = "DEBUG-BACK-FLOW-6C4D"

internal fun logBackFlow(message: String) {
    DiagnosticLog.event("BackFlow", "$BackFlowDebugTag $message")
}
