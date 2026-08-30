package ir.page.persianocr.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import ir.page.persianocr.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * نمای برشِ دستی.
 *
 * تصویر با نسبت اصلی داخل نما جا داده می‌شود (fit-center) و کاربر یک کادر مستطیلی
 * را با کشیدنِ گوشه‌ها، ضلع‌ها یا خودِ کادر جابه‌جا می‌کند. [getCropRect] همان کادر را
 * در مختصات پیکسلیِ تصویرِ اصلی برمی‌گرداند.
 *
 * A dependency-free manual crop view: fit-center image + draggable rectangle.
 */
class CropImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private companion object {
        /** کوچک‌ترین اندازهٔ مجاز کادر بر حسب پیکسلِ نما. */
        const val MIN_CROP_PX = 64f

        /** حاشیهٔ اولیهٔ کادر: ۵٪ از هر طرف. */
        const val INITIAL_INSET_RATIO = 0.05f
    }

    private enum class Grip { NONE, MOVE, LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private var bitmap: Bitmap? = null

    /** محل ترسیم تصویر در مختصات نما. */
    private val imageRect = RectF()

    /** کادر برش در مختصات نما. */
    private val cropRect = RectF()

    private var activeGrip = Grip.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    /** شعاع تشخیص لمسِ دستگیره‌ها. */
    private val touchSlop: Float =
        resources.getDimensionPixelSize(R.dimen.crop_handle_touch).toFloat()

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.crop_scrim)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = ContextCompat.getColor(context, R.color.crop_stroke)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = ContextCompat.getColor(context, R.color.crop_grid)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.crop_handle)
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33000000")
    }

    private val handleLength: Float get() = dp(20f)

    /** تصویری که باید برش بخورد. `null` یعنی نما خالی است. */
    fun setBitmap(source: Bitmap?) {
        bitmap = source
        // چون RTL است، layoutDirection را نادیده می‌گیریم؛ مختصات همیشه فیزیکی‌اند.
        requestLayout()
        computeImageRect()
        resetCrop()
        invalidate()
    }

    /** بازگرداندن کادر به حالت اولیه (تقریباً کل تصویر). */
    fun resetCrop() {
        if (imageRect.isEmpty) return
        val insetX = imageRect.width() * INITIAL_INSET_RATIO
        val insetY = imageRect.height() * INITIAL_INSET_RATIO
        cropRect.set(
            imageRect.left + insetX,
            imageRect.top + insetY,
            imageRect.right - insetX,
            imageRect.bottom - insetY,
        )
        invalidate()
    }

    /**
     * کادر انتخاب‌شده در مختصات پیکسلیِ تصویرِ اصلی.
     * اگر تصویری تنظیم نشده باشد `null` برمی‌گرداند.
     */
    fun getCropRect(): Rect? {
        val source = bitmap ?: return null
        if (imageRect.isEmpty || cropRect.isEmpty) return null
        val scale = source.width / imageRect.width()
        val left = ((cropRect.left - imageRect.left) * scale).roundToInt()
        val top = ((cropRect.top - imageRect.top) * scale).roundToInt()
        val right = ((cropRect.right - imageRect.left) * scale).roundToInt()
        val bottom = ((cropRect.bottom - imageRect.top) * scale).roundToInt()
        return Rect(
            left.coerceIn(0, source.width - 1),
            top.coerceIn(0, source.height - 1),
            right.coerceIn(1, source.width),
            bottom.coerceIn(1, source.height),
        ).takeIf { it.width() > 0 && it.height() > 0 }
    }

    /** ساخت Bitmap برش‌خورده. اگر برشی ممکن نباشد خودِ تصویر برگردانده می‌شود. */
    fun getCroppedBitmap(): Bitmap? {
        val source = bitmap ?: return null
        val rect = getCropRect() ?: return source
        return Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val hadCrop = !cropRect.isEmpty && !imageRect.isEmpty
        // نسبت کادر نسبت به تصویر را نگه می‌داریم تا با چرخش صفحه از بین نرود.
        val relative = if (hadCrop) {
            RectF(
                (cropRect.left - imageRect.left) / imageRect.width(),
                (cropRect.top - imageRect.top) / imageRect.height(),
                (cropRect.right - imageRect.left) / imageRect.width(),
                (cropRect.bottom - imageRect.top) / imageRect.height(),
            )
        } else {
            null
        }
        computeImageRect()
        if (relative != null && !imageRect.isEmpty) {
            cropRect.set(
                imageRect.left + relative.left * imageRect.width(),
                imageRect.top + relative.top * imageRect.height(),
                imageRect.left + relative.right * imageRect.width(),
                imageRect.top + relative.bottom * imageRect.height(),
            )
        } else {
            resetCrop()
        }
    }

    private fun computeImageRect() {
        val source = bitmap
        if (source == null || width == 0 || height == 0) {
            imageRect.setEmpty()
            return
        }
        val viewRatio = width.toFloat() / height
        val imageRatio = source.width.toFloat() / source.height
        val drawWidth: Float
        val drawHeight: Float
        if (imageRatio > viewRatio) {
            drawWidth = width.toFloat()
            drawHeight = width / imageRatio
        } else {
            drawHeight = height.toFloat()
            drawWidth = height * imageRatio
        }
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        imageRect.set(left, top, left + drawWidth, top + drawHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val source = bitmap
        if (source == null) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), emptyPaint)
            return
        }
        if (imageRect.isEmpty) computeImageRect()
        canvas.drawBitmap(source, null, imageRect, null)

        // سایه روی نواحی خارج از کادر
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, cropRect.top, scrimPaint)
        canvas.drawRect(imageRect.left, cropRect.bottom, imageRect.right, imageRect.bottom, scrimPaint)
        canvas.drawRect(imageRect.left, cropRect.top, cropRect.left, cropRect.bottom, scrimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, imageRect.right, cropRect.bottom, scrimPaint)

        // خطوط راهنمای یک‌سوم
        val thirdW = cropRect.width() / 3f
        val thirdH = cropRect.height() / 3f
        for (i in 1..2) {
            canvas.drawLine(
                cropRect.left + thirdW * i, cropRect.top,
                cropRect.left + thirdW * i, cropRect.bottom, gridPaint,
            )
            canvas.drawLine(
                cropRect.left, cropRect.top + thirdH * i,
                cropRect.right, cropRect.top + thirdH * i, gridPaint,
            )
        }

        canvas.drawRect(cropRect, strokePaint)
        drawHandles(canvas)
    }

    private fun drawHandles(canvas: Canvas) {
        val len = min(handleLength, min(cropRect.width(), cropRect.height()) / 3f)
        with(cropRect) {
            // بالا-چپ
            canvas.drawLine(left, top, left + len, top, handlePaint)
            canvas.drawLine(left, top, left, top + len, handlePaint)
            // بالا-راست
            canvas.drawLine(right, top, right - len, top, handlePaint)
            canvas.drawLine(right, top, right, top + len, handlePaint)
            // پایین-چپ
            canvas.drawLine(left, bottom, left + len, bottom, handlePaint)
            canvas.drawLine(left, bottom, left, bottom - len, handlePaint)
            // پایین-راست
            canvas.drawLine(right, bottom, right - len, bottom, handlePaint)
            canvas.drawLine(right, bottom, right, bottom - len, handlePaint)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null || imageRect.isEmpty) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeGrip = detectGrip(event.x, event.y)
                if (activeGrip == Grip.NONE) return false
                lastTouchX = event.x
                lastTouchY = event.y
                // والدِ اسکرول‌شونده نباید لمس را بدزدد.
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeGrip == Grip.NONE) return false
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                lastTouchX = event.x
                lastTouchY = event.y
                applyDrag(dx, dy)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeGrip == Grip.MOVE) performClick()
                activeGrip = Grip.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun detectGrip(x: Float, y: Float): Grip {
        val nearLeft = abs(x - cropRect.left) <= touchSlop
        val nearRight = abs(x - cropRect.right) <= touchSlop
        val nearTop = abs(y - cropRect.top) <= touchSlop
        val nearBottom = abs(y - cropRect.bottom) <= touchSlop
        val withinX = x >= cropRect.left - touchSlop && x <= cropRect.right + touchSlop
        val withinY = y >= cropRect.top - touchSlop && y <= cropRect.bottom + touchSlop

        return when {
            nearLeft && nearTop -> Grip.TOP_LEFT
            nearRight && nearTop -> Grip.TOP_RIGHT
            nearLeft && nearBottom -> Grip.BOTTOM_LEFT
            nearRight && nearBottom -> Grip.BOTTOM_RIGHT
            nearLeft && withinY -> Grip.LEFT
            nearRight && withinY -> Grip.RIGHT
            nearTop && withinX -> Grip.TOP
            nearBottom && withinX -> Grip.BOTTOM
            cropRect.contains(x, y) -> Grip.MOVE
            else -> Grip.NONE
        }
    }

    private fun applyDrag(dx: Float, dy: Float) {
        when (activeGrip) {
            Grip.MOVE -> {
                val shiftX = dx.coerceIn(imageRect.left - cropRect.left, imageRect.right - cropRect.right)
                val shiftY = dy.coerceIn(imageRect.top - cropRect.top, imageRect.bottom - cropRect.bottom)
                cropRect.offset(shiftX, shiftY)
            }

            Grip.LEFT -> cropRect.left = clampLeft(cropRect.left + dx)
            Grip.RIGHT -> cropRect.right = clampRight(cropRect.right + dx)
            Grip.TOP -> cropRect.top = clampTop(cropRect.top + dy)
            Grip.BOTTOM -> cropRect.bottom = clampBottom(cropRect.bottom + dy)

            Grip.TOP_LEFT -> {
                cropRect.left = clampLeft(cropRect.left + dx)
                cropRect.top = clampTop(cropRect.top + dy)
            }

            Grip.TOP_RIGHT -> {
                cropRect.right = clampRight(cropRect.right + dx)
                cropRect.top = clampTop(cropRect.top + dy)
            }

            Grip.BOTTOM_LEFT -> {
                cropRect.left = clampLeft(cropRect.left + dx)
                cropRect.bottom = clampBottom(cropRect.bottom + dy)
            }

            Grip.BOTTOM_RIGHT -> {
                cropRect.right = clampRight(cropRect.right + dx)
                cropRect.bottom = clampBottom(cropRect.bottom + dy)
            }

            Grip.NONE -> Unit
        }
    }

    // از coerceIn استفاده نمی‌کنیم: روی تصاویر بسیار کوچک ممکن است کرانِ پایین از کرانِ
    // بالا بزرگ‌تر شود و coerceIn استثنا پرتاب کند.
    private fun clampLeft(value: Float) =
        clamp(value, imageRect.left, max(imageRect.left, cropRect.right - MIN_CROP_PX))

    private fun clampRight(value: Float) =
        clamp(value, min(imageRect.right, cropRect.left + MIN_CROP_PX), imageRect.right)

    private fun clampTop(value: Float) =
        clamp(value, imageRect.top, max(imageRect.top, cropRect.bottom - MIN_CROP_PX))

    private fun clampBottom(value: Float) =
        clamp(value, min(imageRect.bottom, cropRect.top + MIN_CROP_PX), imageRect.bottom)

    private fun clamp(value: Float, lower: Float, upper: Float) = min(max(value, lower), max(lower, upper))

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    init {
        // برای اینکه ScrollView والد، درگ عمودی را ندزدد.
        isClickable = true
        isFocusable = true
    }
}
