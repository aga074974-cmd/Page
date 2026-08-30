package ir.page.persianocr.image

import android.graphics.Bitmap
import android.util.Log
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/** مراحل خط لوله — برای گزارش پیشرفت به UI. */
enum class PreprocessStep { GRAYSCALE, UPSCALE, DENOISE, DESKEW, BINARIZE, MORPHOLOGY }

/**
 * خروجی خط لوله: یک [Mat] باینری به ازای هر حالت باینری‌سازی.
 *
 * تصاویر به‌صورت [Mat] (حافظهٔ بومی) نگه داشته می‌شوند نه [Bitmap]؛ یک صفحهٔ ۱۲ مگاپیکسلی
 * به‌صورت ARGB_8888 حدود ۴۸ مگابایت از هیپ جاوا می‌گیرد و پنج نسخه از آن اپ را از پا
 * درمی‌آورد. Bitmap فقط هنگام نمایش و برای یک حالت ساخته می‌شود.
 *
 * Variants live in native memory; a Bitmap is materialised only for on-screen preview.
 * فراموش نکنید که [close] را صدا بزنید.
 */
class PreprocessResult internal constructor(
    private val variants: LinkedHashMap<BinarizationMethod, Mat>,
    /** زاویه‌ای که برای صاف‌کردن کجی اعمال شد (درجه، مثبت = پادساعتگرد). */
    val deskewAngleDegrees: Double,
    /** ضریب بزرگ‌نمایی اعمال‌شده برای رسیدن به وضوح معادل ~۳۰۰ DPI. */
    val upscaleFactor: Double,
    /** بلندای تخمینیِ حروف پس از بزرگ‌نمایی (پیکسل). */
    val estimatedCharHeightPx: Double,
    val width: Int,
    val height: Int,
) : Closeable {

    private var closed = false

    val methods: List<BinarizationMethod> get() = variants.keys.toList()

    /**
     * Bitmap با وضوح کامل برای دادن به Tesseract.
     *
     * فراخوان **باید** پس از پایان کار `recycle()` را صدا بزند؛ این Bitmap برای یک
     * صفحهٔ ۱۰ مگاپیکسلی حدود ۴۰ مگابایت از هیپ می‌گیرد و همیشه فقط یکی از آن‌ها
     * هم‌زمان زنده نگه داشته می‌شود.
     */
    fun toBitmap(method: BinarizationMethod): Bitmap {
        check(!closed) { "PreprocessResult already closed" }
        val mat = requireNotNull(variants[method]) { "Unknown variant $method" }
        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bitmap)
        return bitmap
    }

    /** ساخت Bitmap برای نمایش، با کاهش مقیاس تا [maxDimension] برای صرفه‌جویی در حافظه. */
    fun preview(method: BinarizationMethod, maxDimension: Int = 1600): Bitmap {
        check(!closed) { "PreprocessResult already closed" }
        val mat = requireNotNull(variants[method]) { "Unknown variant $method" }
        val longest = max(mat.cols(), mat.rows())
        val scaled: Mat
        val owns: Boolean
        if (longest > maxDimension) {
            val ratio = maxDimension.toDouble() / longest
            scaled = Mat()
            Imgproc.resize(
                mat, scaled,
                Size((mat.cols() * ratio).roundToInt().toDouble(), (mat.rows() * ratio).roundToInt().toDouble()),
                0.0, 0.0, Imgproc.INTER_AREA,
            )
            owns = true
        } else {
            scaled = mat
            owns = false
        }
        val bitmap = Bitmap.createBitmap(scaled.cols(), scaled.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(scaled, bitmap)
        if (owns) scaled.release()
        return bitmap
    }

    override fun close() {
        if (closed) return
        closed = true
        variants.values.forEach { it.release() }
        variants.clear()
    }
}

/**
 * خط لولهٔ پیش‌پردازش با OpenCV — دقیقاً به ترتیب خواسته‌شده:
 *
 *  ۱. خاکستری‌سازی
 *  ۲. بزرگ‌نمایی تا وضوح معادل ~۳۰۰ DPI
 *  ۳. کاهش نویز
 *  ۴. صاف‌کردن کجی صفحه (deskew)
 *  ۵. باینری‌سازی (چند حالت به‌صورت موازی تولید می‌شود)
 *  ۶. عملیات مورفولوژیک ملایم
 *
 * همهٔ توابع همگام (blocking) هستند؛ فراخوان باید آن‌ها را روی Dispatchers.Default اجرا کند.
 * Everything here is blocking — call it from a background dispatcher.
 */
object ImagePreprocessor {

    private const val TAG = "ImagePreprocessor"

    /**
     * بلندای هدف برای حروف بعد از بزرگ‌نمایی. Tesseract روی متنی با ارتفاع ۳۰ تا ۳۵ پیکسل
     * (معادل چاپ ۱۰pt در ۳۰۰ DPI) بهترین عملکرد را دارد.
     */
    private const val TARGET_CHAR_HEIGHT_PX = 34.0

    /** بیشینهٔ ضریب بزرگ‌نمایی — فراتر از این، درون‌یابی فقط نویز می‌سازد. */
    private const val MAX_UPSCALE = 4.0

    /** سقف تعداد پیکسل پس از بزرگ‌نمایی (کنترل مصرف حافظهٔ بومی). */
    private const val MAX_WORKING_PIXELS = 10_000_000.0

    /** حاشیهٔ سفیدی که به تصویر نهایی اضافه می‌شود؛ Tesseract به حاشیه نیاز دارد. */
    private const val OUTPUT_MARGIN_PX = 24

    /** بازهٔ جست‌وجوی زاویهٔ کجی (درجه). */
    private const val SKEW_RANGE_DEG = 15.0

    /**
     * اجرای کامل خط لوله.
     *
     * @param source تصویر برش‌خوردهٔ کاربر.
     * @param onStep گزارش پیشرفت (روی همان نخِ فراخوان صدا زده می‌شود).
     * @throws IllegalStateException اگر OpenCV بارگذاری نشده باشد.
     */
    fun process(
        source: Bitmap,
        onStep: (PreprocessStep) -> Unit = {},
    ): PreprocessResult {
        check(OpenCvBootstrap.ensureLoaded()) { "OpenCV is not loaded" }

        val rgba = Mat()
        Utils.bitmapToMat(source, rgba)

        var gray: Mat? = null
        var upscaled: Mat? = null
        var denoised: Mat? = null
        var deskewed: Mat? = null
        val variants = LinkedHashMap<BinarizationMethod, Mat>()

        try {
            // ── ۱) خاکستری‌سازی ────────────────────────────────────────────────
            onStep(PreprocessStep.GRAYSCALE)
            gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            rgba.release()
            // قطبیت را یکدست می‌کنیم: از این به بعد همه‌جا «متن تیره روی زمینهٔ روشن».
            normalisePolarity(gray)

            // ── ۲) بزرگ‌نمایی تا ~۳۰۰ DPI ─────────────────────────────────────
            onStep(PreprocessStep.UPSCALE)
            val charHeightBefore = estimateCharHeight(gray)
            val factor = computeUpscaleFactor(gray, charHeightBefore)
            upscaled = if (factor > 1.001) {
                Mat().also {
                    Imgproc.resize(
                        gray, it,
                        Size(
                            (gray.cols() * factor).roundToInt().toDouble(),
                            (gray.rows() * factor).roundToInt().toDouble(),
                        ),
                        0.0, 0.0,
                        // LANCZOS4 گران است ولی لبهٔ حروف را تمیزتر از CUBIC نگه می‌دارد.
                        Imgproc.INTER_LANCZOS4,
                    )
                }
            } else {
                gray.clone()
            }
            gray.release(); gray = null

            // ── ۳) کاهش نویز ──────────────────────────────────────────────────
            onStep(PreprocessStep.DENOISE)
            denoised = denoise(upscaled)
            upscaled.release(); upscaled = null

            // ── ۴) صاف‌کردن کجی ───────────────────────────────────────────────
            onStep(PreprocessStep.DESKEW)
            val angle = estimateSkewAngle(denoised)
            deskewed = if (abs(angle) >= 0.05) {
                rotateExpand(denoised, angle)
            } else {
                denoised.clone()
            }
            denoised.release(); denoised = null

            val charHeightAfter = (charHeightBefore * factor).takeIf { it > 1.0 }
                ?: TARGET_CHAR_HEIGHT_PX

            // ── ۵) باینری‌سازی (همهٔ حالت‌ها) + ۶) مورفولوژی ───────────────────
            onStep(PreprocessStep.BINARIZE)
            val window = oddInRange((charHeightAfter * 2.0).roundToInt(), 15, 101)
            for (method in BinarizationMethod.entries) {
                val binary = binarize(deskewed, method, window)
                variants[method] = binary
            }

            onStep(PreprocessStep.MORPHOLOGY)
            for ((method, mat) in variants) {
                variants[method] = finish(mat).also { mat.release() }
            }

            val any = variants.values.first()
            return PreprocessResult(
                variants = variants,
                deskewAngleDegrees = angle,
                upscaleFactor = factor,
                estimatedCharHeightPx = charHeightAfter,
                width = any.cols(),
                height = any.rows(),
            )
        } catch (t: Throwable) {
            variants.values.forEach { it.release() }
            throw t
        } finally {
            // آزادسازی همهٔ Matهای میانی؛ نشتی حافظهٔ بومی به‌سرعت اپ را می‌کشد.
            listOf(rgba, gray, upscaled, denoised, deskewed).forEach { it?.release() }
        }
    }

    // ───────────────────────────── قطبیت ─────────────────────────────

    /**
     * اگر تصویر «متن روشن روی زمینهٔ تیره» باشد، معکوسش می‌کند.
     * سهم پیکسل‌های تیره در یک سند معمولی باید اقلیت باشد (جوهر کمتر از کاغذ است).
     */
    private fun normalisePolarity(gray: Mat) {
        val otsu = Mat()
        val threshold = Imgproc.threshold(gray, otsu, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        // شمارش پیکسل‌های روشن (بالای آستانه) — یعنی زمینه در حالت عادی.
        val brightRatio = Core.countNonZero(otsu).toDouble() / (otsu.rows().toDouble() * otsu.cols())
        otsu.release()
        if (brightRatio < 0.5) {
            Log.d(TAG, "Inverting polarity (bright ratio=$brightRatio, otsu=$threshold)")
            Core.bitwise_not(gray, gray)
        }
    }

    // ─────────────────────────── بزرگ‌نمایی ───────────────────────────

    /**
     * تخمین بلندای حروف با تحلیل مؤلفه‌های همبند.
     *
     * به‌جای حدس‌زدن DPI فیزیکی (که در یک عکس موبایل اصلاً مشخص نیست)، اندازهٔ واقعی
     * حروف را می‌سنجیم و آن را به بلندای هدف می‌رسانیم — این معادل عملیِ «۳۰۰ DPI» است.
     *
     * @return میانهٔ بلندای مؤلفه‌های شبیه‌حرف، یا `-1.0` اگر تخمین ممکن نبود.
     */
    private fun estimateCharHeight(gray: Mat): Double {
        val binary = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            // متن تیره است، پس INV می‌گیریم تا حروف سفید (پیش‌زمینه) شوند.
            Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
            val count = Imgproc.connectedComponentsWithStats(binary, labels, stats, centroids, 8, CvType.CV_32S)
            if (count <= 1) return -1.0

            // خواندن یک‌بارهٔ کل جدول آمار؛ فراخوانی Mat.get به ازای هر مؤلفه بسیار کند است.
            val raw = IntArray(count * 5)
            stats.get(0, 0, raw)

            val maxHeight = max(6, gray.rows() / 6)
            val maxWidth = max(6, gray.cols() / 4)
            val heights = ArrayList<Int>(count)
            for (i in 1 until count) {
                val base = i * 5
                val w = raw[base + Imgproc.CC_STAT_WIDTH]
                val h = raw[base + Imgproc.CC_STAT_HEIGHT]
                val area = raw[base + Imgproc.CC_STAT_AREA]
                // مؤلفه‌های خیلی ریز (نقطه/نویز) و خیلی بزرگ (کادر/تصویر) کنار گذاشته می‌شوند.
                if (h in 5..maxHeight && w in 2..maxWidth && area >= 12) heights.add(h)
            }
            if (heights.size < 8) return -1.0
            heights.sort()
            return heights[heights.size / 2].toDouble()
        } catch (t: Throwable) {
            Log.w(TAG, "Char-height estimation failed", t)
            return -1.0
        } finally {
            binary.release(); labels.release(); stats.release(); centroids.release()
        }
    }

    private fun computeUpscaleFactor(gray: Mat, estimatedCharHeight: Double): Double {
        val raw = if (estimatedCharHeight > 1.0) {
            TARGET_CHAR_HEIGHT_PX / estimatedCharHeight
        } else {
            // تخمین شکست خورد: یک قاعدهٔ سرانگشتی بر پایهٔ ابعاد تصویر.
            1400.0 / max(1, min(gray.cols(), gray.rows()))
        }
        var factor = raw.coerceIn(1.0, MAX_UPSCALE)
        // سقف حافظه: اگر بزرگ‌نمایی از حد بگذرد، ضریب را کم می‌کنیم.
        val pixels = gray.cols().toDouble() * gray.rows()
        val maxByMemory = sqrt(MAX_WORKING_PIXELS / pixels)
        if (maxByMemory < factor) {
            Log.d(TAG, "Clamping upscale $factor -> $maxByMemory (memory budget)")
            factor = max(1.0, maxByMemory)
        }
        return factor
    }

    // ───────────────────────────── نویز ─────────────────────────────

    /**
     * fastNlMeansDenoising کیفیت بهتری می‌دهد ولی هزینه‌اش با تعداد پیکسل به‌سرعت بالا می‌رود.
     * روی تصاویر خیلی بزرگ به‌جایش از یک محو گاوسیِ ملایم استفاده می‌کنیم (هر دو در صورت
     * مسئله مجازند) تا پردازش در محدودهٔ چند ده ثانیه بماند.
     */
    private fun denoise(src: Mat): Mat {
        val dst = Mat()
        val pixels = src.cols().toLong() * src.rows()
        if (pixels <= 6_000_000L) {
            try {
                Photo.fastNlMeansDenoising(src, dst, 7f, 7, 21)
                return dst
            } catch (t: Throwable) {
                Log.w(TAG, "fastNlMeansDenoising failed, falling back to Gaussian", t)
            }
        }
        Imgproc.GaussianBlur(src, dst, Size(3.0, 3.0), 0.0)
        return dst
    }

    // ───────────────────────────── کجی ─────────────────────────────

    /**
     * تخمین زاویهٔ کجی با «نمای تصویرِ افقی» (horizontal projection profile).
     *
     * وقتی سطرهای متن کاملاً افقی باشند، مجموع پیکسل‌های هر سطر بیشترین نوسان را
     * دارد (سطرهای پر و فاصله‌های خالی). زاویه‌ای را برمی‌گردانیم که این نوسان را
     * بیشینه کند. این روش از minAreaRect یا Hough برای متنِ چندستونی مقاوم‌تر است.
     *
     * جست‌وجو دو مرحله‌ای است: درشت (گام ۰٫۵ درجه) و سپس ظریف (گام ۰٫۰۵ درجه).
     */
    private fun estimateSkewAngle(gray: Mat): Double {
        val small = Mat()
        val binary = Mat()
        try {
            // برای سرعتِ معقول، جست‌وجو روی نسخهٔ کوچک‌شده انجام می‌شود؛ زاویه مقیاس‌ناپذیر است.
            val longest = max(gray.cols(), gray.rows())
            if (longest > 1000) {
                val ratio = 1000.0 / longest
                Imgproc.resize(
                    gray, small,
                    Size((gray.cols() * ratio).roundToInt().toDouble(), (gray.rows() * ratio).roundToInt().toDouble()),
                    0.0, 0.0, Imgproc.INTER_AREA,
                )
            } else {
                gray.copyTo(small)
            }
            // پیش‌زمینه (متن) سفید می‌شود تا مجموع سطرها معنا پیدا کند.
            Imgproc.threshold(small, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
            if (Core.countNonZero(binary) < 50) return 0.0

            val coarse = searchAngle(binary, -SKEW_RANGE_DEG, SKEW_RANGE_DEG, 0.5)
            val fine = searchAngle(binary, coarse - 0.5, coarse + 0.5, 0.05)
            Log.d(TAG, "Skew angle: coarse=$coarse fine=$fine")
            return fine
        } catch (t: Throwable) {
            Log.w(TAG, "Skew estimation failed; skipping deskew", t)
            return 0.0
        } finally {
            small.release(); binary.release()
        }
    }

    private fun searchAngle(binary: Mat, from: Double, to: Double, step: Double): Double {
        var bestAngle = 0.0
        var bestScore = -1.0
        var angle = from
        while (angle <= to + 1e-9) {
            val score = projectionScore(binary, angle)
            if (score > bestScore) {
                bestScore = score
                bestAngle = angle
            }
            angle += step
        }
        return bestAngle
    }

    /** مجموع مربعِ تفاضل‌های متوالیِ نمای افقی، پس از چرخاندن به اندازهٔ [angleDeg]. */
    private fun projectionScore(binary: Mat, angleDeg: Double): Double {
        val rotated = if (abs(angleDeg) < 1e-9) binary else rotateFixed(binary, angleDeg)
        val rowSums = Mat()
        try {
            Core.reduce(rotated, rowSums, 1, Core.REDUCE_SUM, CvType.CV_32F)
            val profile = FloatArray(rowSums.rows())
            rowSums.get(0, 0, profile)
            var score = 0.0
            for (i in 0 until profile.size - 1) {
                val d = (profile[i + 1] - profile[i]).toDouble()
                score += d * d
            }
            return score
        } finally {
            rowSums.release()
            if (rotated !== binary) rotated.release()
        }
    }

    /** چرخش بدون تغییر اندازهٔ بوم — فقط برای امتیازدهی استفاده می‌شود. */
    private fun rotateFixed(src: Mat, angleDeg: Double): Mat {
        val center = Point(src.cols() / 2.0, src.rows() / 2.0)
        val m = Imgproc.getRotationMatrix2D(center, angleDeg, 1.0)
        val dst = Mat()
        Imgproc.warpAffine(
            src, dst, m, src.size(),
            Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0),
        )
        m.release()
        return dst
    }

    /** چرخش با بزرگ‌کردن بوم تا هیچ گوشه‌ای بریده نشود. */
    private fun rotateExpand(src: Mat, angleDeg: Double): Mat {
        val center = Point(src.cols() / 2.0, src.rows() / 2.0)
        val m = Imgproc.getRotationMatrix2D(center, angleDeg, 1.0)
        val cos = abs(m.get(0, 0)[0])
        val sin = abs(m.get(0, 1)[0])
        val newWidth = (src.rows() * sin + src.cols() * cos).roundToInt()
        val newHeight = (src.rows() * cos + src.cols() * sin).roundToInt()
        m.put(0, 2, m.get(0, 2)[0] + newWidth / 2.0 - center.x)
        m.put(1, 2, m.get(1, 2)[0] + newHeight / 2.0 - center.y)
        val dst = Mat()
        Imgproc.warpAffine(
            src, dst, m,
            Size(newWidth.toDouble(), newHeight.toDouble()),
            Imgproc.INTER_CUBIC,
            // REPLICATE به‌جای مشکی، تا گوشه‌های تازه رنگ کاغذ بگیرند نه لکهٔ سیاه.
            Core.BORDER_REPLICATE,
        )
        m.release()
        return dst
    }

    // ─────────────────────────── باینری‌سازی ───────────────────────────

    private fun binarize(gray: Mat, method: BinarizationMethod, window: Int): Mat = when (method) {
        BinarizationMethod.SAUVOLA -> sauvola(gray, window, k = 0.2)

        BinarizationMethod.ADAPTIVE_GAUSSIAN -> Mat().also {
            Imgproc.adaptiveThreshold(
                gray, it, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY,
                window, 12.0,
            )
        }

        BinarizationMethod.ADAPTIVE_MEAN -> Mat().also {
            Imgproc.adaptiveThreshold(
                gray, it, 255.0,
                Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY,
                window, 15.0,
            )
        }

        BinarizationMethod.OTSU -> Mat().also {
            val blurred = Mat()
            Imgproc.GaussianBlur(gray, blurred, Size(3.0, 3.0), 0.0)
            Imgproc.threshold(blurred, it, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            blurred.release()
        }

        BinarizationMethod.CLAHE_OTSU -> Mat().also {
            val equalised = Mat()
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            clahe.apply(gray, equalised)
            Imgproc.threshold(equalised, it, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            equalised.release()
        }
    }

    /**
     * آستانه‌گذاری محلی Sauvola:  `T(x,y) = m(x,y) · [1 + k · (s(x,y)/R − 1)]`
     *
     * OpenCV پیاده‌سازی آماده‌ای ندارد، ولی با فیلترهای جعبه‌ای (integral image) به‌سادگی
     * و سریع ساخته می‌شود. برای اسناد با روشنایی ناهمگون از adaptiveThreshold بهتر است،
     * چون واریانس محلی را هم لحاظ می‌کند و نواحی خالیِ کاغذ را لکه‌دار نمی‌کند.
     */
    private fun sauvola(gray: Mat, window: Int, k: Double): Mat {
        val r = 128.0 // دامنهٔ انحراف معیار برای تصویر ۸ بیتی
        val src32 = Mat()
        val mean = Mat()
        val sqMean = Mat()
        val variance = Mat()
        val std = Mat()
        val threshold = Mat()
        val dst = Mat()
        try {
            gray.convertTo(src32, CvType.CV_32F)
            val ksize = Size(window.toDouble(), window.toDouble())
            Imgproc.boxFilter(src32, mean, CvType.CV_32F, ksize)
            Imgproc.sqrBoxFilter(src32, sqMean, CvType.CV_32F, ksize)

            // variance = E[x²] − E[x]²  (به‌خاطر خطای ممیز شناور ممکن است کمی منفی شود)
            Core.multiply(mean, mean, variance)
            Core.subtract(sqMean, variance, variance)
            Core.max(variance, Scalar(0.0), variance)
            Core.sqrt(variance, std)

            Core.divide(std, Scalar(r), threshold)          // s/R
            Core.subtract(threshold, Scalar(1.0), threshold) // s/R − 1
            Core.multiply(threshold, Scalar(k), threshold)   // k·(…)
            Core.add(threshold, Scalar(1.0), threshold)      // 1 + …
            Core.multiply(mean, threshold, threshold)        // m·(…)

            // پیکسل روشن‌تر از آستانه = کاغذ (۲۵۵)، بقیه = جوهر (۰)
            Core.compare(src32, threshold, dst, Core.CMP_GT)
            return dst
        } catch (t: Throwable) {
            dst.release()
            throw t
        } finally {
            listOf(src32, mean, sqMean, variance, std, threshold).forEach { it.release() }
        }
    }

    // ───────────────────── مورفولوژی و پرداخت نهایی ─────────────────────

    /**
     * ۶) عملیات مورفولوژیک ملایم + حذف لکه‌های ریز + افزودن حاشیهٔ سفید.
     *
     * توجه: در فارسی نقطه‌ها معنادارند («ب/پ/ت/ث»). بنابراین آستانهٔ حذف نویز عمداً
     * بسیار کوچک گرفته شده تا هرگز نقطهٔ یک حرف را پاک نکند.
     *
     * Persian relies on dots to distinguish letters, so speck removal is deliberately
     * ultra-conservative — it must never eat a diacritic dot.
     */
    private fun finish(binary: Mat): Mat {
        val inverted = Mat()
        try {
            // عملیات مورفولوژیک روی نواحی روشن کار می‌کند؛ پس موقتاً متن را سفید می‌کنیم.
            Core.bitwise_not(binary, inverted)

            // بستنِ ملایم: شکاف‌های ریزِ داخل قلم را پر می‌کند بدون اینکه حروف را به هم بچسباند.
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
            Imgproc.morphologyEx(inverted, inverted, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()

            removeSpecks(inverted, maxArea = 4)

            Core.bitwise_not(inverted, inverted)

            val bordered = Mat()
            Core.copyMakeBorder(
                inverted, bordered,
                OUTPUT_MARGIN_PX, OUTPUT_MARGIN_PX, OUTPUT_MARGIN_PX, OUTPUT_MARGIN_PX,
                Core.BORDER_CONSTANT, Scalar(255.0),
            )
            return bordered
        } finally {
            inverted.release()
        }
    }

    /**
     * پاک‌کردن مؤلفه‌های همبندِ کوچک‌تر از [maxArea] پیکسل (نویز نمکی-فلفلی).
     *
     * پیمایش سطر-به-سطر انجام می‌شود: نه کل نقشهٔ برچسب‌ها را در هیپ جاوا کپی می‌کنیم
     * (که برای تصویر ۱۰ مگاپیکسلی حدود ۴۰ مگابایت می‌شد) و نه به ازای هر برچسب یک
     * `Core.compare` روی کل تصویر می‌زنیم (که با هزاران لکه بی‌نهایت کند می‌شد).
     */
    private fun removeSpecks(foregroundWhite: Mat, maxArea: Int) {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            val count = Imgproc.connectedComponentsWithStats(foregroundWhite, labels, stats, centroids, 8, CvType.CV_32S)
            if (count <= 1) return
            val raw = IntArray(count * 5)
            stats.get(0, 0, raw)

            val isSpeck = BooleanArray(count)
            var speckCount = 0
            for (i in 1 until count) {
                if (raw[i * 5 + Imgproc.CC_STAT_AREA] <= maxArea) {
                    isSpeck[i] = true
                    speckCount++
                }
            }
            if (speckCount == 0) return

            val cols = foregroundWhite.cols()
            val labelRow = IntArray(cols)
            val pixelRow = ByteArray(cols)
            for (y in 0 until foregroundWhite.rows()) {
                labels.get(y, 0, labelRow)
                var dirty = false
                for (x in 0 until cols) {
                    val label = labelRow[x]
                    if (label > 0 && isSpeck[label]) {
                        if (!dirty) {
                            foregroundWhite.get(y, 0, pixelRow)
                            dirty = true
                        }
                        pixelRow[x] = 0
                    }
                }
                if (dirty) foregroundWhite.put(y, 0, pixelRow)
            }
            Log.d(TAG, "Removed $speckCount specks")
        } catch (t: Throwable) {
            Log.w(TAG, "Speck removal skipped", t)
        } finally {
            labels.release(); stats.release(); centroids.release()
        }
    }

    private fun oddInRange(value: Int, minValue: Int, maxValue: Int): Int {
        val clamped = value.coerceIn(minValue, maxValue)
        return if (clamped % 2 == 0) clamped + 1 else clamped
    }
}
