package ir.page.persianocr.log

import android.util.Log
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * نوشتنِ رکوردها روی دیسک، تا گزارش پس از بسته‌شدن (یا کرشِ) اپ هم باقی بماند.
 *
 * چرا یک نخِ جدا؟ رکوردها از هر نخی (از جمله نخ اصلی) ثبت می‌شوند و نوشتن روی دیسک
 * نباید UI را کند کند. یک `ExecutorService` تک‌نخی ترتیب خطوط را هم تضمین می‌کند.
 *
 * پس از هر خط `flush` می‌کنیم: حجم گزارش کم است و در عوض اگر اپ ناگهانی کشته شود
 * هیچ خطی از دست نمی‌رود — دقیقاً همان لحظه‌ای که گزارش بیشترین ارزش را دارد.
 *
 * Writes records to disk on a single background thread so the log survives a crash.
 */
internal class LogSink(private val file: File) : Closeable {

    private companion object {
        const val TAG = "LogSink"
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diag-log").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    /** فقط از نخِ executor لمس می‌شود؛ پس نیازی به همگام‌سازی ندارد. */
    private var writer: BufferedWriter? = null
    private var broken = false

    @Volatile
    private var closed = false

    /** افزودن یک خط به انتهای فایل (غیرمسدودکننده). */
    fun append(line: String) {
        if (closed) return
        submit { writeLine(line) }
    }

    /**
     * منتظر می‌ماند تا همهٔ خطوطِ ثبت‌شده تا این لحظه روی دیسک بنشینند.
     * از دست‌گیرندهٔ کرش صدا زده می‌شود، جایی که چند ده میلی‌ثانیه صبر ارزشش را دارد.
     */
    fun flushBlocking(timeoutMillis: Long = 2_000) {
        if (closed) return
        try {
            // چون هر خط بلافاصله flush می‌شود، تمام‌شدنِ یک وظیفهٔ خالی یعنی
            // تمام خطوطِ پیش از آن هم نوشته شده‌اند.
            executor.submit { }.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            // مهلت تمام شد یا executor خاموش است — گزارش‌دادنش کمکی نمی‌کند.
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        submit {
            runCatching { writer?.flush() }
            runCatching { writer?.close() }
            writer = null
        }
        executor.shutdown()
    }

    private fun submit(task: () -> Unit) {
        try {
            executor.execute(task)
        } catch (_: Throwable) {
            // executor خاموش شده؛ گزارش‌گیری هرگز نباید خودش اپ را بیندازد.
        }
    }

    private fun writeLine(line: String) {
        if (broken) return
        try {
            val target = writer ?: openWriter().also { writer = it }
            target.write(line)
            target.write("\n")
            target.flush()
        } catch (t: Throwable) {
            // اگر یک بار نوشتن شکست بخورد (دیسک پر، فایل حذف‌شده)، دیگر تلاش نمی‌کنیم
            // تا در حلقهٔ خطا نیفتیم. بافر درون‌حافظه‌ای همچنان کار می‌کند.
            broken = true
            runCatching { writer?.close() }
            writer = null
            Log.w(TAG, "Log file write failed; continuing in memory only", t)
        }
    }

    private fun openWriter(): BufferedWriter {
        file.parentFile?.mkdirs()
        return BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
    }
}
