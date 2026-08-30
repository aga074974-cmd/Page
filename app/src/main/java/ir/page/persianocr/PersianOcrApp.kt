package ir.page.persianocr

import android.app.Application
import ir.page.persianocr.log.DiagnosticLog

/**
 * کلاس Application — عمداً سبک نگه داشته شده است.
 *
 * بارگذاری OpenCV و مقداردهی Tesseract عمداً اینجا انجام *نمی‌شود*: هر دو ممکن است
 * شکست بخورند و باید خطایشان در UI به کاربر نمایش داده شود، نه اینکه اپ هنگام
 * راه‌اندازی کرش کند. این کارها با تنبلی (lazy) و داخل ViewModel انجام می‌شوند.
 *
 * تنها کاری که همین‌جا انجام می‌شود راه‌اندازی گزارش‌گیر است؛ باید پیش از هر چیز
 * دیگری آماده باشد تا حتی خطاهای لحظهٔ راه‌اندازی هم ثبت شوند.
 *
 * The Application class is intentionally thin: both OpenCV loading and Tesseract
 * init can fail and must surface as a user-visible error rather than a crash.
 * Only the diagnostic log is started here, first, so early failures are recorded.
 */
class PersianOcrApp : Application() {

    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.install(this)
    }
}
