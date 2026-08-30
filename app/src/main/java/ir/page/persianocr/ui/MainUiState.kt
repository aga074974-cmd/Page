package ir.page.persianocr.ui

import android.graphics.Bitmap
import androidx.annotation.StringRes
import ir.page.persianocr.image.BinarizationMethod
import ir.page.persianocr.ocr.OcrResult

/** مرحلهٔ جاری در جریان کار. */
enum class Stage {
    /** هنوز تصویری انتخاب نشده. */
    IDLE,

    /** تصویر بارگذاری شده و آمادهٔ برش است. */
    CROPPING,

    /** پیش‌پردازش تمام شده و پیش‌نمایش نشان داده می‌شود. */
    PREPROCESSED,

    /** نتیجهٔ OCR آماده است. */
    RESULT,
}

/** پیام خطای قابل نمایش — یا از منابع رشته‌ای یا متن آماده. */
data class UiError(
    @StringRes val messageRes: Int? = null,
    val formatArg: String? = null,
    val literal: String? = null,
)

/** پیام یک‌بارمصرف (Snackbar/Toast). */
data class UiMessage(@StringRes val messageRes: Int, val id: Long = System.nanoTime())

/**
 * تمام وضعیت قابل مشاهدهٔ صفحهٔ اصلی در یک شیء تغییرناپذیر.
 * A single immutable snapshot of everything the screen renders.
 */
data class MainUiState(
    val stage: Stage = Stage.IDLE,

    /** تصویر خام (برش‌نخورده) که در نمای برش نمایش داده می‌شود. */
    val sourceImage: Bitmap? = null,

    /** پیش‌نمایش کوچک‌شدهٔ تصویر پیش‌پردازش‌شده برای حالت انتخابی. */
    val preprocessedPreview: Bitmap? = null,

    /** حالت باینری‌سازی‌ای که کاربر برای پیش‌نمایش انتخاب کرده. */
    val selectedMethod: BinarizationMethod = BinarizationMethod.DEFAULT,

    /** اجرای OCR روی همهٔ حالت‌ها و انتخاب بهترین. */
    val multiPass: Boolean = true,

    /** استفاده از PSM_SINGLE_BLOCK به‌جای PSM_AUTO (برای متن پاراگرافی). */
    val singleBlockMode: Boolean = false,

    val result: OcrResult? = null,

    /** آیا کاری در حال انجام است (نوار پیشرفت را نشان بده). */
    val busy: Boolean = false,

    /** درصد پیشرفت؛ `null` یعنی نامعین (indeterminate). */
    val progressPercent: Int? = null,

    /** متن وضعیت زیر نوار پیشرفت. */
    @StringRes val statusRes: Int? = null,
    val statusArg: String? = null,

    val error: UiError? = null,
    val message: UiMessage? = null,

    /** اطلاعات فنیِ پیش‌پردازش برای نمایش زیر پیش‌نمایش. */
    val preprocessInfo: String? = null,
)
