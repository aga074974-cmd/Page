package ir.page.persianocr.image

import android.util.Log
import org.opencv.android.OpenCVLoader

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

    private const val TAG = "OpenCvBootstrap"

    @Volatile
    private var state: Boolean? = null

    /** `true` اگر OpenCV آمادهٔ استفاده باشد. نتیجه cache می‌شود. */
    @Synchronized
    fun ensureLoaded(): Boolean {
        state?.let { return it }
        val ok = try {
            OpenCVLoader.initLocal()
        } catch (t: Throwable) {
            // UnsatisfiedLinkError روی ABI پشتیبانی‌نشده در همین‌جا گرفته می‌شود.
            Log.e(TAG, "OpenCV native load failed", t)
            false
        }
        if (!ok) Log.e(TAG, "OpenCVLoader.initLocal() returned false")
        state = ok
        return ok
    }
}
