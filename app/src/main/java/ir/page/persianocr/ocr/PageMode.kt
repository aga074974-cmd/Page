package ir.page.persianocr.ocr

import com.googlecode.tesseract.android.TessBaseAPI

/**
 * حالت قطعه‌بندی صفحه (PSM) که به Tesseract داده می‌شود.
 *
 * چرا [SINGLE_BLOCK] پیش‌فرض است؟ `PSM_AUTO` (۳) پیش از تشخیص، تحلیلِ چیدمانِ کاملِ
 * صفحه انجام می‌دهد و متنِ فارسیِ یک بلوکِ پیوسته را به ستون‌ها و ناحیه‌های خیالی
 * می‌شکند؛ نتیجه‌اش هم‌ریختنِ ترتیب خطوط و از دست رفتن فاصلهٔ بین کلمات است
 * («خدمات را» ← «خدماترا»). برای عکسِ یک پاراگراف یا یک صفحهٔ کتاب — که کاربردِ
 * اصلی این اپ است — گفتنِ «این یک بلوک متن است» به Tesseract دقیق‌تر است.
 *
 * PSM_AUTO is kept as an option for genuinely multi-region pages, but it is the
 * wrong default for a single block of Persian prose.
 */
enum class PageMode(val psm: Int, val label: String) {

    /** یک بلوکِ یکپارچهٔ متن — دقیق‌ترین حالت برای عکسِ پاراگراف یا صفحهٔ کتاب. */
    SINGLE_BLOCK(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK, "بلوک یکپارچه (پیشنهادی)"),

    /** یک ستونِ متن با ارتفاع متغیر — وقتی خطوط فاصله‌های نامنظم دارند. */
    SINGLE_COLUMN(TessBaseAPI.PageSegMode.PSM_SINGLE_COLUMN, "تک‌ستونی"),

    /** تحلیل خودکار چیدمان — فقط برای صفحه‌هایی با چند ستون یا چند ناحیهٔ جدا. */
    AUTO(TessBaseAPI.PageSegMode.PSM_AUTO, "تشخیص خودکار چیدمان"),
    ;

    companion object {
        val DEFAULT = SINGLE_BLOCK
    }
}
