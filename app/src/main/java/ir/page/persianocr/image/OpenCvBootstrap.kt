package ir.page.persianocr.image

import ir.page.persianocr.log.DiagnosticLog
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core

/**
 * بارگذاری یک‌بارهٔ کتابخانهٔ بومی OpenCV.
 *
 * از `initLocal()` استفاده می‌کنیم که کتابخانهٔ باندل‌شده در خودِ APK را بارگذاری
 * می‌کند و برخلاف روش قدیمیِ `initAsync` هیچ نیازی به نصب اپِ «OpenCV Manager»
 * یا هیچ ارتباط شبکه‌ای ندارد.
 *
 * Loads the bundled OpenCV native libs. `initLocal()` needs no OpenCV Manager app
 * and performs no network access — a hard requirement for this offline app.
 */
object OpenCvBootstrap {

    private const val TAG = "OpenCV"

    @Volatile
    private var state: Boolean? = null

    /** `true` اگر OpenCV آمادهٔ استفاده باشد. نتیجه cache می‌شود. */
    @Synchronized
    fun ensureLoaded(): Boolean {
        state?.let { return it }

        DiagnosticLog.i(TAG, "بارگذاری کتابخانهٔ بومی OpenCV ${OpenCVLoader.OPENCV_VERSION}…")
        val ok = try {
            DiagnosticLog.timed(TAG, "OpenCVLoader.initLocal()") { OpenCVLoader.initLocal() }
        } catch (t: Throwable) {
            // UnsatisfiedLinkError روی ABI پشتیبانی‌نشده در همین‌جا گرفته می‌شود.
            DiagnosticLog.e(TAG, "بارگذاری کتابخانهٔ بومی OpenCV شکست خورد", t)
            false
        }

        if (ok) {
            // نسخهٔ واقعیِ کتابخانهٔ بومی — فقط پس از بارگذاری قابل خواندن است و
            // اگر با نسخهٔ جاوا نخواند، ریشهٔ خطاهای عجیبِ بعدی همین‌جاست.
            val native = runCatching { Core.getVersionString() }.getOrNull()
            DiagnosticLog.i(TAG, "OpenCV آماده است — نسخهٔ بومی: ${native ?: "نامشخص"}")
        } else {
            DiagnosticLog.e(TAG, "OpenCVLoader.initLocal() مقدار false برگرداند (کتابخانهٔ بومی برای این معماری وجود ندارد؟)")
        }

        state = ok
        return ok
    }
}
