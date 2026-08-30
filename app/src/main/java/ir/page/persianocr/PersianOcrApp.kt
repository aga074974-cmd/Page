package ir.page.persianocr

import android.app.Application

/**
 * کلاس Application — عمداً سبک نگه داشته شده است.
 *
 * بارگذاری OpenCV و مقداردهی Tesseract عمداً اینجا انجام *نمی‌شود*: هر دو ممکن است
 * شکست بخورند و باید خطایشان در UI به کاربر نمایش داده شود، نه اینکه اپ هنگام
 * راه‌اندازی کرش کند. این کارها با تنبلی (lazy) و داخل ViewModel انجام می‌شوند.
 *
 * The Application class is intentionally thin: both OpenCV loading and Tesseract
 * init can fail and must surface as a user-visible error rather than a crash.
 */
class PersianOcrApp : Application()
