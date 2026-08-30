package ir.page.persianocr.log

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * سطح اهمیت یک رکورد گزارش.
 * Severity of a single diagnostic record.
 */
enum class LogLevel(
    /** حرفِ کوتاهی که در خروجی متنی چاپ می‌شود. */
    val marker: Char,
    /** معادلِ همین سطح در `android.util.Log` (برای بازتاب در logcat). */
    val androidPriority: Int,
) {
    DEBUG('D', Log.DEBUG),
    INFO('I', Log.INFO),
    WARN('W', Log.WARN),
    ERROR('E', Log.ERROR),

    /** خطای مهلک؛ فقط از دست‌گیرندهٔ کرش استفاده می‌شود. */
    FATAL('F', Log.ASSERT),
    ;

    /** آیا این سطح در حالت «فقط مشکلات» هم باید نمایش داده شود؟ */
    val isProblem: Boolean get() = ordinal >= WARN.ordinal
}

/**
 * یک رکورد گزارش. تغییرناپذیر است تا بتوان بدون قفل بین نخ‌ها ردّش کرد.
 * An immutable diagnostic record, safe to hand between threads.
 */
data class LogEntry(
    /** زمان مطلق (میلی‌ثانیهٔ یونیکس) — برای نمایش ساعت. */
    val atMillis: Long,
    /** میلی‌ثانیه از شروع جلسه — برای اندازه‌گیری فاصلهٔ رویدادها. */
    val sinceStartMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val threadName: String,
    /** ردِّ پشتهٔ استثنا، اگر رکورد استثنایی داشته باشد. */
    val stackTrace: String? = null,
) {

    /**
     * یک خطِ متنیِ آمادهٔ نوشتن در فایل یا نمایش در صفحه.
     * قالب: `12:34:56.789  +3.210s  D/OcrRepository  [DefaultDispatcher-1]  متن`
     */
    fun format(): String = buildString {
        append(clock(atMillis))
        append("  ")
        // فاصله از شروع جلسه؛ برای تشخیص گلوگاه‌های زمانی مفیدتر از ساعت مطلق است.
        append(String.format(Locale.US, "%+8.3fs", sinceStartMillis / 1000.0))
        append("  ")
        append(level.marker)
        append('/')
        // برچسب با پهنای ثابت تا ستون پیام در همهٔ خطوط سرِ جای خودش بیفتد؛
        // مرور چشمیِ یک گزارش هزارخطی بدون این ستون‌بندی عملاً ممکن نیست.
        append(tag.padEnd(TAG_WIDTH))
        append(" [")
        append(threadName)
        append("]  ")
        append(message)
        stackTrace?.let {
            append('\n')
            // چند فاصله در ابتدای هر خطِ پشته تا از پیام اصلی جدا دیده شود.
            append(it.trimEnd().prependIndent("      "))
        }
    }

    companion object {

        /** پهنای ستون برچسب؛ اندازهٔ بلندترین برچسبِ در استفاده. */
        private const val TAG_WIDTH = 10

        /**
         * `SimpleDateFormat` اصلاً thread-safe نیست و رکوردها از چند نخ قالب‌بندی
         * می‌شوند؛ پس به ازای هر نخ یک نمونه نگه می‌داریم.
         *
         * `Locale.US` عمدی است: ارقام لاتین در گزارش فنی خواناتر و قابل‌جست‌وجوترند.
         */
        private val CLOCK = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        }

        fun clock(millis: Long): String = CLOCK.get()!!.format(Date(millis))
    }
}
