package app.goga.assistant.session

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ring of the last [CAPACITY] lines, also flushed to cache so it survives a later force-stop.
 * This is a log, not a lock: it must never gate the next session.
 */
object SessionTrace {
    private const val CAPACITY = 500
    private const val TAG = "Goga/Trace"
    private val lines = ArrayDeque<String>(CAPACITY)
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var installed = false

    fun log(source: String, message: String) {
        val line = "${stamp.format(Date())} $source $message".replace('\n', '↵')
        synchronized(lines) {
            if (lines.size >= CAPACITY) lines.removeFirst()
            lines.addLast(line)
        }
        Log.i(TAG, line)
    }

    fun log(source: String, error: Throwable) {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        log(source, writer.toString())
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }

    fun file(context: Context): File {
        val dir = File(context.cacheDir, "trace").apply { mkdirs() }
        val file = File(dir, "goga-log.txt")
        file.writeText(text())
        return file
    }

    fun share(context: Context): Intent {
        val file = file(context)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.trace", uriFile(file))
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Журнал Гоги")
            putExtra(Intent.EXTRA_TEXT, text())
            clipData = ClipData.newRawUri("goga-log", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            log("crash", "${thread.name} ${error.stackTraceToString()}")
            runCatching { file(app) }
            previous?.uncaughtException(thread, error)
        }
        log("trace", "handler installed")
    }

    private fun uriFile(file: File): File = file
}
