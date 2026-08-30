package ir.page.persianocr.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import ir.page.persianocr.log.DiagnosticLog
import java.io.File
import java.io.IOException

/**
 * خواندن/نوشتن تصویر با کنترل حافظه.
 * Image decoding helpers with explicit memory control.
 */
object BitmapIo {

    private const val TAG = "BitmapIo"

    /**
     * بیشینهٔ بُعدِ تصویرِ خام که در حافظه نگه می‌داریم. تصویر خام فقط برای نمایش و
     * برش استفاده می‌شود؛ بزرگ‌نماییِ لازم برای OCR بعداً روی ناحیهٔ برش‌خورده انجام
     * می‌شود، بنابراین نمونه‌برداری در این مرحله به دقت نهایی آسیبی نمی‌زند.
     */
    const val MAX_SOURCE_DIMENSION = 4096

    /**
     * تصویر را از [uri] می‌خواند، در صورت لزوم نمونه‌برداری می‌کند و چرخش EXIF را اعمال می‌کند.
     * Decodes [uri], subsampling to [maxDimension] and applying the EXIF orientation.
     *
     * @throws IOException اگر تصویر قابل خواندن نباشد.
     */
    @Throws(IOException::class)
    fun decode(
        context: Context,
        uri: Uri,
        maxDimension: Int = MAX_SOURCE_DIMENSION,
    ): Bitmap {
        val resolver = context.contentResolver
        DiagnosticLog.i(TAG, "خواندن تصویر از: $uri")

        // گام ۱: فقط ابعاد را می‌خوانیم تا inSampleSize را حساب کنیم.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { input ->
            input ?: throw IOException("Cannot open input stream for $uri")
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            DiagnosticLog.e(TAG, "تصویر قابل رمزگشایی نیست (ابعاد صفر برگشت).")
            throw IOException("Not a decodable image: $uri")
        }
        DiagnosticLog.d(
            TAG,
            "ابعاد اصلی: ${bounds.outWidth}×${bounds.outHeight} • نوع: ${bounds.outMimeType ?: "?"}",
        )

        val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            // تصویر باید قابل تغییر نباشد تا بتوانیم آزادانه ارجاعش را به OpenCV بدهیم.
            inMutable = false
        }
        val decoded = DiagnosticLog.timed(TAG, "رمزگشایی (inSampleSize=$sample)") {
            resolver.openInputStream(uri).use { input ->
                input ?: throw IOException("Cannot open input stream for $uri")
                BitmapFactory.decodeStream(input, null, options)
            } ?: throw IOException("BitmapFactory returned null for $uri")
        }

        val rotation = readExifRotation(resolver, uri)
        DiagnosticLog.i(
            TAG,
            "تصویر آماده شد: ${decoded.width}×${decoded.height}" +
                " • چرخش EXIF: ${rotation.toInt()}°" +
                " • حافظه: ${decoded.byteCount / 1024} کیلوبایت",
        )
        return if (rotation == 0f) decoded else rotate(decoded, rotation)
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (width / sample > maxDimension || height / sample > maxDimension) {
            sample *= 2
        }
        return sample
    }

    private fun readExifRotation(resolver: ContentResolver, uri: Uri): Float = try {
        resolver.openInputStream(uri).use { input ->
            if (input == null) {
                0f
            } else {
                when (
                    ExifInterface(input).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }
    } catch (t: Exception) {
        // نبودِ EXIF خطا نیست، ولی ثبتش می‌کند که چرا چرخش اعمال نشد.
        DiagnosticLog.d(TAG, "EXIF خوانده نشد (${t::class.java.simpleName}); بدون چرخش ادامه می‌دهیم.")
        0f
    }

    private fun rotate(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }

    /** ساخت فایل موقتِ خروجی دوربین در پوشهٔ cache (سازگار با FileProvider). */
    fun newCameraFile(context: Context): File {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        // فایل قبلی را نگه نمی‌داریم؛ فضای cache را تمیز نگه می‌داریم.
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, "capture_${System.currentTimeMillis()}.jpg")
            .also { DiagnosticLog.d(TAG, "فایل خروجی دوربین: ${it.absolutePath}") }
    }
}
