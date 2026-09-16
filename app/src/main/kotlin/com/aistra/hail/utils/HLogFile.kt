package com.aistra.hail.utils

import com.aistra.hail.HailApp.Companion.app
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small append-only log used to diagnose the unfreeze gate on remote devices. */
object HLogFile {
    private const val NAME = "unfreeze_guard.log"
    private const val MAX_LENGTH = 32_000
    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private val file get() = File(app.filesDir, NAME)

    @Synchronized
    fun append(line: String) = runCatching {
        val f = file
        f.appendText("${format.format(Date())}  $line\n")
        if (f.length() > MAX_LENGTH) f.writeText(f.readText().takeLast(MAX_LENGTH / 2))
    }.let { }

    @Synchronized
    fun read(): String = runCatching { file.readText() }.getOrDefault("")

    @Synchronized
    fun clear() = runCatching { file.delete() }.let { }
}
