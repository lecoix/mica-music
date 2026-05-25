package com.mica.music.data

enum class ScanSource(val storageValue: String) {
    DEVICE("device"),
    FOLDER("folder"),
    ;

    companion object {
        fun fromStorage(value: String?): ScanSource =
            entries.firstOrNull { it.storageValue == value } ?: DEVICE
    }
}
