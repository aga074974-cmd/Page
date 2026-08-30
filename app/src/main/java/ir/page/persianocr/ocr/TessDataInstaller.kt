package ir.page.persianocr.ocr

import android.content.Context
import ir.page.persianocr.log.DiagnosticLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** وقتی فایل زبانی در assets نباشد پرتاب می‌شود. */
class MissingTessDataException(val missing: List<String>) :
    IOException("Missing traineddata in assets/tessdata: ${missing.joinToString()}")

/**
 * کپیِ امنِ فایل‌های `*.traineddata` از assets به پوشهٔ خصوصی اپ.
 *
 * چرا اصلاً کپی لازم است؟ Tesseract یک کتابخانهٔ بومی (C++) است و فایل‌ها را با مسیر
 * فایل‌سیستمی باز می‌کند؛ اما assets داخل خودِ APK فشرده‌اند و مسیر واقعی ندارند.
 * پس یک بار (و فقط یک بار) به `filesDir/tesseract/tessdata/` منتقل می‌شوند.
 *
 * «امن» یعنی:
 *  • نوشتن در فایل موقت و سپس rename اتمیک — یک کپیِ نیمه‌تمام (به‌خاطر کشته‌شدن
 *    پروسه یا پرشدن حافظه) هرگز به‌جای فایل سالم جا نمی‌افتد.
 *  • مقایسهٔ اندازهٔ فایل مقصد با اندازهٔ asset — اگر یکی بود دوباره کپی نمی‌شود.
 *  • هیچ دسترسی شبکه‌ای؛ همه‌چیز از داخل APK می‌آید.
 */
class TessDataInstaller(private val context: Context) {

    companion object {
        private const val TAG = "TessData"
        private const val ASSET_DIR = "tessdata"
        private const val EXTENSION = ".traineddata"
    }

    /** مسیری که باید به `TessBaseAPI.init()` داده شود (والدِ پوشهٔ tessdata). */
    val dataPath: File = File(context.filesDir, "tesseract")

    /** پوشه‌ای که خودِ فایل‌های زبان در آن قرار می‌گیرند. */
    val tessdataDir: File = File(dataPath, ASSET_DIR)

    /**
     * اطمینان از حاضربودن زبان‌های [languages] (مثلاً `listOf("fas", "ara")`).
     *
     * @param onCopy وقتی فایلی واقعاً کپی می‌شود صدا زده می‌شود (برای نمایش پیام «بار اول»).
     * @return مسیری که باید به `init()` داده شود.
     * @throws MissingTessDataException اگر فایلی در assets نباشد.
     * @throws IOException اگر کپی شکست بخورد.
     */
    @Throws(IOException::class)
    fun install(languages: List<String>, onCopy: (String) -> Unit = {}): File {
        if (!tessdataDir.exists() && !tessdataDir.mkdirs()) {
            DiagnosticLog.e(TAG, "ساخت پوشهٔ ${tessdataDir.absolutePath} ممکن نشد.")
            throw IOException("Cannot create ${tessdataDir.absolutePath}")
        }

        val available = runCatching { context.assets.list(ASSET_DIR)?.toSet().orEmpty() }
            .getOrDefault(emptySet())
        DiagnosticLog.d(TAG, "فایل‌های موجود در assets/$ASSET_DIR: ${available.joinToString().ifEmpty { "(هیچ)" }}")

        val missing = languages.filterNot { "$it$EXTENSION" in available }
        if (missing.isNotEmpty()) {
            // شایع‌ترین علتِ خطای «موتور آماده نشد»: فایل‌های زبان در بیلد جا نیفتاده‌اند.
            DiagnosticLog.e(TAG, "فایل زبان در assets نیست: ${missing.joinToString()}")
            throw MissingTessDataException(missing)
        }

        for (language in languages) {
            val name = "$language$EXTENSION"
            val target = File(tessdataDir, name)
            val expectedSize = assetSize("$ASSET_DIR/$name")

            val upToDate = target.isFile &&
                target.length() > 0 &&
                (expectedSize < 0 || target.length() == expectedSize)

            if (upToDate) {
                DiagnosticLog.d(TAG, "$name از قبل نصب است (${target.length()} بایت)")
                continue
            }

            DiagnosticLog.i(
                TAG,
                "کپی $name به ${target.absolutePath}" +
                    " • اندازهٔ مورد انتظار: $expectedSize بایت" +
                    " • فضای آزاد: ${freeSpaceBytes()} بایت",
            )
            onCopy(name)
            DiagnosticLog.timed(TAG, "کپی $name") { copyAsset("$ASSET_DIR/$name", target) }
        }
        return dataPath
    }

    /** فضای آزادِ حافظهٔ داخلی — نبودِ فضا شایع‌ترین علتِ شکستِ کپی است. */
    private fun freeSpaceBytes(): Long = runCatching { dataPath.usableSpace }.getOrDefault(-1L)

    /** اندازهٔ واقعیِ asset، یا `-1` اگر قابل تعیین نباشد (asset فشرده). */
    private fun assetSize(assetPath: String): Long = try {
        context.assets.openFd(assetPath).use { it.length }
    } catch (_: IOException) {
        // اگر asset فشرده باشد openFd خطا می‌دهد. با noCompress در build.gradle
        // این حالت نباید پیش بیاید، ولی مسیر جایگزین را نگه می‌داریم.
        -1L
    }

    @Throws(IOException::class)
    private fun copyAsset(assetPath: String, target: File) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        if (temp.exists()) temp.delete()
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE * 8)
                    // اطمینان از رسیدن داده به دیسک پیش از rename.
                    output.flush()
                    output.fd.sync()
                }
            }
            if (target.exists() && !target.delete()) {
                throw IOException("Cannot replace ${target.absolutePath}")
            }
            if (!temp.renameTo(target)) {
                throw IOException("Cannot rename ${temp.absolutePath} -> ${target.absolutePath}")
            }
            DiagnosticLog.i(TAG, "نصب شد: ${target.name} (${target.length()} بایت)")
        } catch (t: Throwable) {
            DiagnosticLog.e(TAG, "کپی $assetPath شکست خورد", t)
            temp.delete()
            throw t
        }
    }
}
