package ir.page.persianocr.log

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ساختِ متنِ نهاییِ گزارش — همان چیزی که کاربر می‌بیند، ذخیره می‌کند یا می‌فرستد.
 *
 * گزارش عمداً «متن ساده» است: در هر ویرایشگر، پیام‌رسان و ایمیلی بدون تبدیل باز
 * می‌شود و چیزی از آن گم نمی‌شود.
 *
 * Builds the plain-text report the user reads, saves or shares.
 */
object LogReport {

    private const val RULE = "────────────────────────────────────────────────────────"

    /** نامِ پیشنهادی فایل، با مهر زمانی تا فایل‌های پیاپی روی هم نیفتند. */
    fun fileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "persian-ocr-log-$stamp.txt"
    }

    /**
     * متن کامل گزارش.
     *
     * @param onlyProblems اگر true باشد فقط هشدارها و خطاها می‌آیند (برای مرور سریع).
     */
    fun build(context: Context, onlyProblems: Boolean = false): String {
        val entries = DiagnosticLog.snapshot()
        val shown = if (onlyProblems) entries.filter { it.level.isProblem } else entries

        return buildString {
            appendLine(RULE)
            appendLine("گزارش اشکال‌یابی — Persian OCR")
            appendLine("ساخته‌شده در: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("دستگاه: ${Build.MANUFACTURER} ${Build.MODEL} • اندروید ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("معماری‌ها: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("حافظه هم‌اکنون: ${DiagnosticLog.heapSummary()}")
            append("رکوردها: ${shown.size}")
            if (onlyProblems) append(" (فیلترشده از ${entries.size})")
            if (DiagnosticLog.dropped() > 0) {
                append(" • ${DiagnosticLog.dropped()} رکورد قدیمی به‌خاطر پرشدن بافر حذف شده")
            }
            appendLine()
            appendLine()
            // هشدار حریم خصوصی: گزارش شامل بخشی از متنِ تشخیص‌داده‌شده است.
            appendLine("توجه: این گزارش برای اشکال‌یابی، بخشی از متنِ استخراج‌شده از تصویر شما را")
            appendLine("در بر دارد. پیش از فرستادنش برای دیگران، یک بار آن را بخوانید.")
            appendLine(RULE)
            appendLine()

            if (shown.isEmpty()) {
                appendLine("(رکوردی ثبت نشده است.)")
            } else {
                shown.forEach { appendLine(it.format()) }
            }

            DiagnosticLog.previousCrash?.let { crash ->
                appendLine()
                appendLine(RULE)
                appendLine("کرشِ اجرای قبلی — مهم‌ترین بخش گزارش")
                appendLine(RULE)
                appendLine(crash)
            }
        }
    }

    /**
     * نوشتن گزارش در فایلی داخل cache برای اشتراک‌گذاری با `FileProvider`.
     * فایل‌های قبلی حذف می‌شوند تا cache انباشته نشود.
     */
    fun writeToCache(context: Context, text: String): File {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        return File(dir, fileName()).apply { writeText(text, Charsets.UTF_8) }
    }
}
