package ir.page.persianocr.image

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import ir.page.persianocr.log.DiagnosticLog
import java.util.Locale

/**
 * سقفِ اندازهٔ تصویرِ کاری، بر پایهٔ حافظهٔ **واقعیِ در دسترسِ همین دستگاه**.
 *
 * ── چرا این کلاس ساخته شد ───────────────────────────────────────────────────
 * پیش از این، خط لوله یک عددِ ثابتِ ۱۰ مگاپیکسل را سقفِ کار می‌گرفت. نتیجه‌اش این
 * بود که روی گوشی‌ای با ۵۱۲ مگابایت هیپ و چند گیگابایت رم آزاد، بزرگ‌نمایی از ۴٫۰
 * به ۱٫۷۱ کاهش پیدا می‌کرد در حالی که کل مصرف حافظهٔ اپ ۶ مگابایت بود. متن با
 * ارتفاع حروفِ ۱۳٫۷ پیکسل به Tesseract می‌رسید — خیلی کمتر از چیزی که برای دقتِ
 * خوب لازم است.
 *
 * ── چرا هیپ جاوا معیار درستی نیست ───────────────────────────────────────────
 * تصاویر [org.opencv.core.Mat] در حافظهٔ **بومی** ساخته می‌شوند و اصلاً در
 * `Runtime.maxMemory()` شمرده نمی‌شوند؛ از اندروید ۸ به بعد پیکسل‌های Bitmap هم
 * همین‌طور. پس معیارِ درست، حافظهٔ آزادِ خودِ دستگاه است که با
 * [ActivityManager.MemoryInfo] خوانده می‌شود — نه هیپِ اپ.
 *
 * Sizes the working image from the device's actual free RAM: OpenCV Mats (and,
 * since Android 8, Bitmap pixels) are native allocations that never appear in
 * the Java heap figures.
 */
data class WorkingMemoryBudget(
    /** بیشینهٔ پیکسلِ تصویرِ کاری پس از بزرگ‌نمایی. */
    val maxWorkingPixels: Long,
    /** بیشینهٔ پیکسلِ هر Bitmapی که به Tesseract داده می‌شود (مبنای کاشی‌بندی). */
    val maxOcrBitmapPixels: Long,
    /** توضیح خوانا برای گزارش. */
    val summary: String,
) {

    companion object {

        /**
         * بایتِ حافظهٔ بومی به ازای هر پیکسلِ تصویرِ کاری.
         *
         * هم‌زمان زنده‌اند: تصویرِ صاف‌شده + ۵ حالت باینری + یک Matِ گذرا در
         * مورفولوژی، هرکدام ۱ بایت بر پیکسل. عدد ۸ همین را با کمی حاشیه پوشش می‌دهد.
         */
        private const val BYTES_PER_WORKING_PIXEL = 8L

        /** ARGB_8888 — چهار بایت برای هر پیکسلِ Bitmapی که به Tesseract می‌رود. */
        private const val BYTES_PER_BITMAP_PIXEL = 4L

        /** سهمی از حافظهٔ آزاد که برداشتنش امن است. */
        private const val FRACTION_OF_FREE_MEMORY = 0.35

        /** سهمِ کوچکِ جداگانه برای Bitmapِ گذرای هر کاشی. */
        private const val FRACTION_FOR_OCR_BITMAP = 0.12

        /** سقفِ مطلق — فراتر از این، بزرگ‌نمایی دیگر اطلاعاتی به تصویر اضافه نمی‌کند. */
        private const val ABSOLUTE_MAX_WORKING_PIXELS = 90_000_000L

        /** کفِ مطلق — زیر این مقدار متن آن‌قدر ریز می‌شود که OCR بی‌فایده است. */
        private const val MIN_WORKING_PIXELS = 12_000_000L

        private const val MAX_OCR_BITMAP_PIXELS = 12_000_000L
        private const val MIN_OCR_BITMAP_PIXELS = 2_000_000L

        private const val TAG = "Memory"

        /** بودجهٔ محافظه‌کارانه، وقتی خواندنِ وضعیت حافظه ممکن نباشد. */
        val CONSERVATIVE = WorkingMemoryBudget(
            maxWorkingPixels = MIN_WORKING_PIXELS,
            maxOcrBitmapPixels = MIN_OCR_BITMAP_PIXELS,
            summary = "بودجهٔ پیش‌فرض (وضعیت حافظهٔ دستگاه خوانده نشد)",
        )

        /** محاسبهٔ بودجه از روی حافظهٔ آزادِ دستگاه. */
        fun forDevice(context: Context): WorkingMemoryBudget {
            val info = runCatching {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            }.getOrElse {
                DiagnosticLog.w(TAG, "خواندن وضعیت حافظهٔ دستگاه ممکن نشد؛ بودجهٔ پیش‌فرض.", it)
                return CONSERVATIVE
            }

            // `threshold` مرزی است که پایین‌تر از آن سیستم شروع به کشتنِ پروسه‌ها می‌کند؛
            // آن را دست‌نخورده باقی می‌گذاریم.
            val usable = (info.availMem - info.threshold).coerceAtLeast(0L)

            val workingPixels = (usable * FRACTION_OF_FREE_MEMORY / BYTES_PER_WORKING_PIXEL)
                .toLong()
                .coerceIn(MIN_WORKING_PIXELS, ABSOLUTE_MAX_WORKING_PIXELS)

            // پیش از اندروید ۸، پیکسل‌های Bitmap روی هیپ جاواست، پس آنجا هیپ سقف است.
            val bitmapBudgetBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (usable * FRACTION_FOR_OCR_BITMAP).toLong()
            } else {
                val runtime = Runtime.getRuntime()
                val heapFree = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
                (heapFree * 0.5).toLong()
            }
            val bitmapPixels = (bitmapBudgetBytes / BYTES_PER_BITMAP_PIXEL)
                .coerceIn(MIN_OCR_BITMAP_PIXELS, MAX_OCR_BITMAP_PIXELS)

            val megabyte = 1024.0 * 1024.0
            val summary = String.format(
                Locale.US,
                "آزاد %.0fMB (آستانه %.0fMB) → تصویر کاری تا %.1f مگاپیکسل، هر کاشی تا %.1f مگاپیکسل",
                info.availMem / megabyte,
                info.threshold / megabyte,
                workingPixels / 1e6,
                bitmapPixels / 1e6,
            )
            return WorkingMemoryBudget(workingPixels, bitmapPixels, summary)
        }
    }
}
