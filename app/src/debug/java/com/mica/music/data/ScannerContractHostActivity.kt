package com.mica.music.data

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView

/** Keeps device component tests foreground without creating a second MusicLibrary owner. */
class ScannerContractHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply {
            text = "Mica QA · scanner component test\nKeep this screen open until the test finishes."
            textSize = 18f
            setPadding(32, 64, 32, 32)
        })
    }
}
