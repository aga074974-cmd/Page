package ir.page.persianocr.text

import android.content.Context
import ir.page.persianocr.log.DiagnosticLog

/**
 * بارگذاریِ فرهنگِ واژگان از assets — تنها جایی از باگ ۴ که به اندروید وابسته است.
 *
 * فایل `assets/lexicon/fa-words.txt` یک واژه در هر خط است و کاملاً آفلاین
 * داخلِ خودِ APK قرار دارد؛ هیچ درخواستِ شبکه‌ای در کار نیست.
 *
 * فرهنگ **تنبل** و **یک‌بار** بارگذاری می‌شود: اگر کاربر اصلاحِ واژگانی را روشن
 * نکند، هیچ‌وقت خوانده نمی‌شود و حافظه‌ای نمی‌گیرد.
 */
object AssetLexicon {

    private const val TAG = "Lexicon"
    private const val PATH = "lexicon/fa-words.txt"

    @Volatile
    private var cached: PersianLexicon? = null

    /** فرهنگ را (در صورت نیاز) بار می‌کند. در صورت خطا، فرهنگِ خالی برمی‌گردد. */
    fun load(context: Context): PersianLexicon {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val loaded = runCatching {
                DiagnosticLog.timed(TAG, "بارگذاری فرهنگ واژگان") {
                    // هر خط: «واژه» و یک رقمِ بازهٔ بسامد (۰..۹).
                    val bands = HashMap<String, Int>(48_000)
                    context.assets.open(PATH).bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            val space = line.lastIndexOf(' ')
                            if (space <= 1) return@forEach
                            val word = line.substring(0, space)
                            if (word.length < 2) return@forEach
                            bands[word] = line.substring(space + 1).trim().toIntOrNull() ?: 0
                        }
                    }
                    SetLexicon(bands) as PersianLexicon
                }
            }.getOrElse {
                DiagnosticLog.w(TAG, "فرهنگ واژگان خوانده نشد؛ اصلاحِ واژگانی غیرفعال می‌ماند.", it)
                PersianLexicon.EMPTY
            }
            DiagnosticLog.i(TAG, "فرهنگ واژگان آماده شد: ${loaded.size} واژه")
            cached = loaded
            return loaded
        }
    }
}
