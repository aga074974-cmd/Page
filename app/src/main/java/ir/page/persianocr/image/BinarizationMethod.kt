package ir.page.persianocr.image

/**
 * حالت‌های باینری‌سازی که خط لوله تولید می‌کند.
 *
 * چون دقت اولویت مطلق است، به‌جای انتخاب یک روش، همهٔ این حالت‌ها ساخته می‌شوند و
 * (در حالت چندگذره) OCR روی همه اجرا شده و بهترین خروجی انتخاب می‌شود.
 *
 * The pipeline produces every variant; multi-pass OCR then keeps the best output.
 */
enum class BinarizationMethod(
    /** برچسب فارسی برای نمایش در UI */
    val label: String,
) {
    /** آستانه‌گذاری محلیِ Sauvola — بهترین انتخاب برای اسکن سند با روشنایی ناهمگون. */
    SAUVOLA("Sauvola (محلی)"),

    /** adaptiveThreshold با هستهٔ گاوسی. */
    ADAPTIVE_GAUSSIAN("تطبیقی گاوسی"),

    /** adaptiveThreshold با میانگین محلی. */
    ADAPTIVE_MEAN("تطبیقی میانگین"),

    /** آستانهٔ سراسری Otsu — برای تصاویر با روشنایی یکنواخت عالی است. */
    OTSU("Otsu سراسری"),

    /** CLAHE (تعادل هیستوگرام محلی) و سپس Otsu — برای عکس‌های کم‌کنتراست. */
    CLAHE_OTSU("CLAHE + Otsu"),
    ;

    companion object {
        /**
         * حالتی که به‌صورت پیش‌فرض به کاربر نشان داده می‌شود.
         * Sauvola برای متن چاپیِ فارسی معمولاً پایدارترین نتیجه را می‌دهد.
         */
        val DEFAULT: BinarizationMethod = SAUVOLA
    }
}
