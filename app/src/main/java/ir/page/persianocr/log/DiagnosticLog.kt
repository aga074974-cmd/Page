package ir.page.persianocr.log

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * گزارش‌گیرِ مرکزی اپ.
 *
 * چرا به‌جای `android.util.Log` ساده؟ چون logcat روی گوشیِ کاربر در دسترس نیست؛ برای
 * اشکال‌یابی باید بشود گزارش را *داخل خود اپ* دید، ذخیره کرد و فرستاد. بنابراین هر
 * رکورد هم‌زمان به سه جا می‌رود:
 *
 *  ۱. یک بافر حلقوی در حافظه ([CAPACITY] رکورد) — برای نمایش فوری در صفحهٔ گزارش.
 *  ۲. فایل جلسهٔ جاری در `filesDir/logs/` — تا پس از بسته‌شدن یا کرشِ اپ باقی بماند.
 *  ۳. logcat — برای وقتی که دستگاه به کامپیوتر وصل است.
 *
 * تمام متدها thread-safe و «بی‌صدا در برابر خطا» هستند: گزارش‌گیری هرگز نباید خودش
 * باعث کرش شود.
 *
 * A central, crash-surviving diagnostic log that the user can read, save and share
 * from inside the app — logcat is not reachable on a normal phone.
 */
object DiagnosticLog {

    /** بیشینهٔ رکوردهای نگه‌داشته‌شده در حافظه. فایل روی دیسک محدودیتی ندارد. */
    const val CAPACITY = 4_000

    /** حداکثر تعداد فایل جلسه که نگه می‌داریم. */
    private const val MAX_SESSION_FILES = 5

    /**
     * فقط این مقدار از انتهای فایلِ جلسهٔ قبلی برای یافتن ردّ کرش خوانده می‌شود.
     * این خواندن هنگام راه‌اندازی و روی نخ اصلی انجام می‌شود، پس باید کراندار بماند.
     */
    private const val CRASH_SCAN_BYTES = 64L * 1024

    /** نشانه‌ای که دست‌گیرندهٔ کرش می‌نویسد تا اجرای بعدی بتواند تشخیصش دهد. */
    internal const val CRASH_MARKER = "*** UNCAUGHT EXCEPTION ***"

    private const val TAG = "DiagnosticLog"

    private val lock = Any()
    private val entries = ArrayDeque<LogEntry>()

    /** تعداد رکوردهایی که به‌خاطر پرشدن بافر از ابتدا حذف شده‌اند. */
    private var droppedCount = 0

    /** مبدأ زمانیِ ستون «فاصله از شروع». */
    private val startedAtUptime = SystemClock.elapsedRealtime()

    private val _revision = MutableStateFlow(0L)

    /** با هر رکورد تازه یک واحد جلو می‌رود؛ صفحهٔ گزارش به آن گوش می‌دهد. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    @Volatile
    private var sink: LogSink? = null

    @Volatile
    private var installed = false

    /** فایل جلسهٔ جاری (اگر نوشتن روی دیسک ممکن شده باشد). */
    @Volatile
    var sessionFile: File? = null
        private set

    /**
     * گزیده‌ای از کرشِ اجرای قبلی، اگر اجرای قبلی با استثنای گرفته‌نشده تمام شده باشد.
     * در گزارش خروجی گنجانده می‌شود — مهم‌ترین چیزی که کاربر باید بفرستد.
     */
    @Volatile
    var previousCrash: String? = null
        private set

    // ─────────────────────────── راه‌اندازی ───────────────────────────

    /**
     * راه‌اندازی: چرخاندن فایل‌های قدیمی، ساخت فایل جلسهٔ جاری، نصب دست‌گیرندهٔ کرش و
     * ثبت مشخصات دستگاه. از `Application.onCreate()` یک بار صدا زده می‌شود.
     */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true

        val appContext = context.applicationContext
        runCatching { prepareFiles(appContext) }
            .onFailure { Log.w(TAG, "Log file setup failed; memory-only logging", it) }

        installCrashHandler()
        recordEnvironment(appContext)
    }

    private fun prepareFiles(context: Context) {
        val dir = File(context.filesDir, "logs")
        dir.mkdirs()

        val existing = dir.listFiles { file -> file.isFile && file.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

        // پیش از ساختِ فایل تازه، جدیدترین فایلِ قبلی را برای ردّ کرش وارسی می‌کنیم.
        existing.firstOrNull()?.let { previousCrash = extractCrash(it) }

        // فقط چند جلسهٔ آخر را نگه می‌داریم تا حافظهٔ دستگاه اشغال نشود.
        existing.drop(MAX_SESSION_FILES - 1).forEach { runCatching { it.delete() } }

        val stamp = LogEntry.clock(System.currentTimeMillis()).replace(':', '-').replace('.', '-')
        val file = File(dir, "session-${System.currentTimeMillis()}-$stamp.log")
        sessionFile = file
        sink = LogSink(file)
    }

    /**
     * دنبال نشانهٔ کرش در انتهای فایل جلسهٔ قبلی می‌گردد.
     * فقط انتهای فایل خوانده می‌شود تا حافظه اشغال نشود.
     */
    private fun extractCrash(file: File): String? = runCatching {
        val lines = readTail(file, CRASH_SCAN_BYTES).lines()
        val index = lines.indexOfLast { it.contains(CRASH_MARKER) }
        if (index < 0) {
            null
        } else {
            // چند خطِ پیش از کرش هم مهم است: نشان می‌دهد اپ داشت چه می‌کرد.
            lines.subList(maxOf(0, index - 12), lines.size)
                .joinToString("\n")
                .take(8_000)
        }
    }.getOrNull()

    /**
     * خواندنِ حداکثر [maxBytes] بایتِ آخرِ فایل.
     *
     * اگر از وسط فایل شروع کنیم ممکن است اولین خط ناقص (و حتی وسطِ یک کاراکتر
     * چندبایتیِ UTF-8) باشد؛ به همین دلیل خطِ اول را دور می‌اندازیم.
     */
    private fun readTail(file: File, maxBytes: Long): String {
        RandomAccessFile(file, "r").use { access ->
            val length = access.length()
            val from = maxOf(0L, length - maxBytes)
            access.seek(from)
            val buffer = ByteArray((length - from).toInt())
            access.readFully(buffer)
            val text = String(buffer, Charsets.UTF_8)
            return if (from == 0L) text else text.substringAfter('\n', text)
        }
    }

    /**
     * ثبتِ استثناهای گرفته‌نشده پیش از مرگ پروسه.
     *
     * دست‌گیرندهٔ قبلی همیشه در پایان صدا زده می‌شود؛ در غیر این صورت اپ به‌جای بستن،
     * برای همیشه فریز می‌ماند و گزارشِ خرابیِ خودِ اندروید هم ثبت نمی‌شود.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                record(LogLevel.FATAL, "Crash", "$CRASH_MARKER روی نخ «${thread.name}»", error)
                sink?.flushBlocking()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** مشخصات دستگاه و بیلد — سرآغاز هر گزارش. */
    private fun recordEnvironment(context: Context) {
        section("شروع جلسه — Persian OCR")

        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val versionCode = packageInfo?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                it.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                it.versionCode.toLong()
            }
        }
        i("Env", "اپ: ${context.packageName} ${packageInfo?.versionName ?: "?"} (build $versionCode)")
        i("Env", "دستگاه: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        i("Env", "اندروید: ${Build.VERSION.RELEASE} — API ${Build.VERSION.SDK_INT}")
        i("Env", "معماری‌ها: ${Build.SUPPORTED_ABIS.joinToString()}")
        i("Env", "زبان دستگاه: ${Locale.getDefault()}")
        i("Env", "حافظهٔ مجاز اپ: ${heapSummary()}")
        sessionFile?.let { i("Env", "فایل گزارش: ${it.absolutePath}") }

        previousCrash?.let {
            w("Env", "اجرای قبلی با کرش تمام شده بود؛ متن آن در انتهای همین گزارش آمده است.")
        }
    }

    /** خلاصهٔ وضعیت حافظهٔ جاوا — برای ردیابی خطاهای کمبود حافظه. */
    fun heapSummary(): String {
        val runtime = Runtime.getRuntime()
        val mb = 1024.0 * 1024.0
        return String.format(
            Locale.US,
            "used %.0fMB / heap %.0fMB / max %.0fMB",
            (runtime.totalMemory() - runtime.freeMemory()) / mb,
            runtime.totalMemory() / mb,
            runtime.maxMemory() / mb,
        )
    }

    // ─────────────────────────── ثبت رکورد ───────────────────────────

    fun d(tag: String, message: String) = record(LogLevel.DEBUG, tag, message, null)

    fun i(tag: String, message: String) = record(LogLevel.INFO, tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) =
        record(LogLevel.WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) =
        record(LogLevel.ERROR, tag, message, error)

    /** یک خطِ جداکننده با عنوان — مرزهای هر عملیات را در گزارش پیدا می‌کند. */
    fun section(title: String) = record(LogLevel.INFO, "──────", "── $title ──", null)

    /**
     * اجرای [block] و ثبتِ مدت‌زمانش. برای پیداکردن گلوگاه‌ها.
     * `inline` نیست چون بلوک‌ها اینجا سنگین‌اند و سربارِ فراخوانی بی‌اهمیت است.
     */
    fun <T> timed(tag: String, label: String, block: () -> T): T {
        val started = SystemClock.elapsedRealtime()
        var failure: Throwable? = null
        try {
            return block()
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            val millis = SystemClock.elapsedRealtime() - started
            if (failure == null) {
                d(tag, "$label — ${millis}ms")
            } else {
                w(tag, "$label — پس از ${millis}ms شکست خورد: ${failure::class.java.simpleName}")
            }
        }
    }

    fun record(level: LogLevel, tag: String, message: String, error: Throwable? = null) {
        val entry = LogEntry(
            atMillis = System.currentTimeMillis(),
            sinceStartMillis = SystemClock.elapsedRealtime() - startedAtUptime,
            level = level,
            tag = tag,
            message = message,
            threadName = Thread.currentThread().name,
            stackTrace = error?.let(::stackTraceOf),
        )

        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > CAPACITY) {
                entries.removeFirst()
                droppedCount++
            }
        }

        // بازتاب در logcat — وقتی دستگاه به کامپیوتر وصل است هنوز مفیدترین ابزار است.
        runCatching { Log.println(level.androidPriority, tag, message) }
        error?.let { runCatching { Log.println(level.androidPriority, tag, Log.getStackTraceString(it)) } }

        sink?.append(entry.format())
        _revision.value = _revision.value + 1
    }

    private fun stackTraceOf(error: Throwable): String = StringWriter().also { buffer ->
        PrintWriter(buffer).use { error.printStackTrace(it) }
    }.toString()

    // ─────────────────────────── خواندن ───────────────────────────

    /** کپیِ لحظه‌ایِ رکوردهای درون حافظه. */
    fun snapshot(): List<LogEntry> = synchronized(lock) { entries.toList() }

    /** تعداد رکوردهای درون حافظه — بدون کپی‌کردنِ خودِ فهرست. */
    fun size(): Int = synchronized(lock) { entries.size }

    /** تعداد رکوردهایی که به‌خاطر پرشدن بافر حذف شده‌اند. */
    fun dropped(): Int = synchronized(lock) { droppedCount }

    /** خالی‌کردن بافر حافظه. فایل روی دیسک دست‌نخورده می‌ماند. */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            droppedCount = 0
        }
        _revision.value = _revision.value + 1
        i(TAG, "گزارش درون‌حافظه‌ای پاک شد.")
    }
}
