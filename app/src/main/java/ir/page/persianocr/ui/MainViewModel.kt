package ir.page.persianocr.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.page.persianocr.R
import ir.page.persianocr.image.BinarizationMethod
import ir.page.persianocr.image.ImagePreprocessor
import ir.page.persianocr.image.OpenCvBootstrap
import ir.page.persianocr.image.PreprocessResult
import ir.page.persianocr.image.PreprocessStep
import ir.page.persianocr.image.WorkingMemoryBudget
import ir.page.persianocr.log.DiagnosticLog
import ir.page.persianocr.ocr.MissingTessDataException
import ir.page.persianocr.ocr.OcrPhase
import ir.page.persianocr.ocr.OcrRepository
import ir.page.persianocr.ocr.PageMode
import ir.page.persianocr.ocr.TesseractInitException
import ir.page.persianocr.text.PersianTextOptions
import ir.page.persianocr.util.BitmapIo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel صفحهٔ اصلی — تمام منطق جریان کار اینجاست و هیچ کار سنگینی روی نخ اصلی
 * انجام نمی‌شود.
 *
 * چرا [AndroidViewModel]؟ برای دسترسی به `ContentResolver` و `assets` به Context
 * سطح‌اپلیکیشن نیاز داریم؛ نگه‌داشتن Context اپلیکیشن نشتی حافظه ایجاد نمی‌کند.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "UI"
    }

    private val repository = OcrRepository(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    /** خروجی خط لولهٔ پیش‌پردازش — حاوی Matهای بومی، باید صریحاً بسته شود. */
    private var preprocessed: PreprocessResult? = null

    private var runningJob: Job? = null

    // ─────────────────────────── انتخاب تصویر ───────────────────────────

    /** بارگذاری تصویر انتخاب‌شده از گالری یا دوربین و رفتن به مرحلهٔ برش. */
    fun onImagePicked(uri: Uri) {
        DiagnosticLog.section("انتخاب تصویر")
        cancelRunning()
        runningJob = viewModelScope.launch {
            _uiState.update {
                it.copy(busy = true, progressPercent = null, statusRes = R.string.status_loading_image, error = null)
            }
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    BitmapIo.decode(getApplication<Application>(), uri)
                }
                releasePreprocessed()
                // Bitmapهای وضعیت عمداً recycle نمی‌شوند: ممکن است هنوز به‌عنوان drawable
                // روی صفحه باشند و رسمِ یک Bitmap بازیافت‌شده اپ را کرش می‌کند. جمع‌آوری
                // زباله خودش آن‌ها را آزاد می‌کند.
                _uiState.update { previous ->
                    previous.copy(
                        stage = Stage.CROPPING,
                        sourceImage = bitmap,
                        preprocessedPreview = null,
                        preprocessInfo = null,
                        result = null,
                        busy = false,
                        progressPercent = null,
                        statusRes = null,
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                DiagnosticLog.e(TAG, "بارگذاری تصویر شکست خورد", t)
                fail(UiError(messageRes = R.string.err_image_load))
            }
        }
    }

    // ────────────────────────── پیش‌پردازش ──────────────────────────

    /**
     * برشِ تصویر با کادر انتخابیِ کاربر و اجرای خط لولهٔ OpenCV.
     * پیش‌نمایش نتیجه پیش از OCR به کاربر نشان داده می‌شود.
     */
    fun onCropConfirmed(cropped: Bitmap?) {
        val source = cropped ?: _uiState.value.sourceImage ?: return
        DiagnosticLog.i(
            TAG,
            "تأیید برش — ناحیهٔ انتخابی: ${source.width}×${source.height}" +
                if (cropped == null) " (بدون برش؛ کل تصویر)" else "",
        )
        cancelRunning()
        runningJob = viewModelScope.launch {
            // بارگذاری کتابخانهٔ بومی (~۲۴ مگابایت) نباید روی نخ اصلی انجام شود.
            val openCvReady = withContext(Dispatchers.Default) { OpenCvBootstrap.ensureLoaded() }
            if (!openCvReady) {
                DiagnosticLog.e(TAG, "OpenCV آماده نشد؛ پیش‌پردازش ممکن نیست.")
                fail(UiError(messageRes = R.string.err_opencv))
                return@launch
            }
            _uiState.update {
                it.copy(busy = true, progressPercent = 0, statusRes = R.string.status_preprocess_gray, error = null)
            }

            // ImagePreprocessor.process یک تابع مسدودکنندهٔ غیرقابل‌وقفه است: اگر کاربر
            // وسط کار لغو کند، withContext هنگام بازگشت CancellationException می‌اندازد و
            // مقدار بازگشتی از بین می‌رود. با نگه‌داشتن نتیجه در یک متغیر بیرونی مطمئن
            // می‌شویم Matهای بومی در هر مسیری آزاد می‌شوند.
            var produced: PreprocessResult? = null
            try {
                releasePreprocessed()
                val budget = WorkingMemoryBudget.forDevice(getApplication())
                withContext(Dispatchers.Default) {
                    produced = ImagePreprocessor.process(source, budget) { step ->
                        _uiState.update {
                            it.copy(statusRes = stepLabel(step), progressPercent = stepProgress(step))
                        }
                    }
                }
                val result = requireNotNull(produced)
                preprocessed = result
                // تصویر برش‌خورده هرگز روی صفحه نمی‌رود، پس آزادکردنش امن است.
                if (source !== _uiState.value.sourceImage) source.recycle()

                val method = _uiState.value.selectedMethod
                val preview = withContext(Dispatchers.Default) { result.preview(method) }

                _uiState.update { previous ->
                    previous.copy(
                        stage = Stage.PREPROCESSED,
                        preprocessedPreview = preview,
                        preprocessInfo = describe(result),
                        result = null,
                        busy = false,
                        progressPercent = null,
                        statusRes = null,
                    )
                }
            } catch (e: CancellationException) {
                DiagnosticLog.i(TAG, "پیش‌پردازش لغو شد.")
                if (preprocessed !== produced) produced?.close()
                throw e
            } catch (t: Throwable) {
                DiagnosticLog.e(TAG, "پیش‌پردازش شکست خورد", t)
                if (preprocessed !== produced) produced?.close()
                releasePreprocessed()
                fail(preprocessError(t))
            }
        }
    }

    /** تعویض حالت باینری‌سازیِ نمایش‌داده‌شده. */
    fun onMethodSelected(method: BinarizationMethod) {
        if (_uiState.value.selectedMethod == method) return
        DiagnosticLog.d(TAG, "حالت نمایش تغییر کرد: ${method.name}")
        _uiState.update { it.copy(selectedMethod = method) }
        val result = preprocessed ?: return
        viewModelScope.launch {
            val preview = withContext(Dispatchers.Default) { result.preview(method) }
            _uiState.update { previous -> previous.copy(preprocessedPreview = preview) }
        }
    }

    fun onMultiPassChanged(enabled: Boolean) {
        DiagnosticLog.d(TAG, "اجرای چندگذره: $enabled")
        _uiState.update { it.copy(multiPass = enabled) }
    }

    fun onPageModeSelected(mode: PageMode) {
        if (_uiState.value.pageMode == mode) return
        DiagnosticLog.d(TAG, "حالت قطعه‌بندی صفحه: ${mode.name} (PSM=${mode.psm})")
        _uiState.update { it.copy(pageMode = mode) }
    }

    fun onLexiconCorrectionChanged(enabled: Boolean) {
        DiagnosticLog.d(TAG, "اصلاح واژگانی: $enabled")
        _uiState.update { it.copy(lexiconCorrection = enabled) }
    }

    // ──────────────────────────── اجرای OCR ────────────────────────────

    fun onRunOcr() {
        val result = preprocessed ?: return
        cancelRunning()
        runningJob = viewModelScope.launch {
            val state = _uiState.value
            val methods = if (state.multiPass) result.methods else listOf(state.selectedMethod)
            val budget = WorkingMemoryBudget.forDevice(getApplication())

            _uiState.update {
                it.copy(busy = true, progressPercent = 0, statusRes = R.string.status_init_engine, error = null)
            }
            try {
                val ocr = repository.recognise(
                    preprocessed = result,
                    methods = methods,
                    pageMode = state.pageMode,
                    maxBitmapPixels = budget.maxOcrBitmapPixels,
                    textOptions = PersianTextOptions(correctWithLexicon = state.lexiconCorrection),
                ) { progress ->
                    _uiState.update {
                        it.copy(
                            progressPercent = progress.percent,
                            statusRes = phaseLabel(progress.phase),
                            statusArg = progress.variantLabel,
                        )
                    }
                }
                _uiState.update {
                    it.copy(
                        stage = Stage.RESULT,
                        result = ocr,
                        busy = false,
                        progressPercent = null,
                        statusRes = R.string.status_done,
                        statusArg = null,
                        message = if (ocr.text.isBlank()) {
                            UiMessage(R.string.msg_empty_result)
                        } else {
                            null
                        },
                    )
                }
            } catch (e: CancellationException) {
                DiagnosticLog.i(TAG, "اجرای OCR لغو شد.")
                throw e
            } catch (t: Throwable) {
                DiagnosticLog.e(TAG, "اجرای OCR شکست خورد", t)
                fail(ocrError(t))
            }
        }
    }

    /** لغو عملیات در حال اجرا. */
    fun onCancel() {
        DiagnosticLog.i(TAG, "کاربر عملیات را لغو کرد.")
        repository.requestStop()
        cancelRunning()
        _uiState.update { it.copy(busy = false, progressPercent = null, statusRes = null, statusArg = null) }
    }

    /** بازگشت به ابتدای جریان کار. */
    fun onRestart() {
        DiagnosticLog.i(TAG, "بازگشت به ابتدای جریان کار.")
        cancelRunning()
        releasePreprocessed()
        _uiState.update { previous ->
            MainUiState(
                multiPass = previous.multiPass,
                pageMode = previous.pageMode,
                lexiconCorrection = previous.lexiconCorrection,
                selectedMethod = previous.selectedMethod,
            )
        }
    }

    fun onErrorShown() = _uiState.update { it.copy(error = null) }

    fun onMessageShown() = _uiState.update { it.copy(message = null) }

    // ─────────────────────────── کمکی‌ها ───────────────────────────

    private fun fail(error: UiError) = _uiState.update {
        it.copy(busy = false, progressPercent = null, statusRes = null, statusArg = null, error = error)
    }

    private fun preprocessError(t: Throwable): UiError = when (t) {
        is OutOfMemoryError -> UiError(messageRes = R.string.err_out_of_memory)
        else -> UiError(messageRes = R.string.err_preprocess, formatArg = t.message ?: t::class.java.simpleName)
    }

    private fun ocrError(t: Throwable): UiError = when (t) {
        is MissingTessDataException ->
            UiError(messageRes = R.string.err_tessdata_missing, formatArg = t.missing.joinToString("، "))

        is TesseractInitException -> UiError(messageRes = R.string.err_tess_init)
        is OutOfMemoryError -> UiError(messageRes = R.string.err_out_of_memory)
        else -> UiError(messageRes = R.string.err_ocr, formatArg = t.message ?: t::class.java.simpleName)
    }

    private fun stepLabel(step: PreprocessStep): Int = when (step) {
        PreprocessStep.GRAYSCALE -> R.string.status_preprocess_gray
        PreprocessStep.UPSCALE -> R.string.status_preprocess_upscale
        PreprocessStep.DENOISE -> R.string.status_preprocess_denoise
        PreprocessStep.DESKEW -> R.string.status_preprocess_deskew
        PreprocessStep.BINARIZE -> R.string.status_preprocess_binarize
        PreprocessStep.MORPHOLOGY -> R.string.status_preprocess_morph
    }

    /** سهم تقریبی هر مرحله از کل زمان پیش‌پردازش — تا نوار پیشرفت معین بماند. */
    private fun stepProgress(step: PreprocessStep): Int = when (step) {
        PreprocessStep.GRAYSCALE -> 3
        PreprocessStep.UPSCALE -> 12
        PreprocessStep.DENOISE -> 30
        PreprocessStep.DESKEW -> 62
        PreprocessStep.BINARIZE -> 84
        PreprocessStep.MORPHOLOGY -> 94
    }

    private fun phaseLabel(phase: OcrPhase): Int = when (phase) {
        OcrPhase.INSTALLING_DATA -> R.string.status_copying_data
        OcrPhase.INITIALISING -> R.string.status_init_engine
        OcrPhase.RECOGNISING -> R.string.status_ocr
        OcrPhase.VOTING -> R.string.status_voting
        OcrPhase.POSTPROCESSING -> R.string.status_postprocess
    }

    /** خلاصهٔ فنیِ پیش‌پردازش برای نمایش زیر پیش‌نمایش. */
    private fun describe(result: PreprocessResult): String = buildString {
        append("اندازهٔ نهایی: ${result.width}×${result.height}")
        append(" • بزرگ‌نمایی: ×${"%.2f".format(result.upscaleFactor)}")
        append(" • چرخش: ${"%.2f".format(result.deskewAngleDegrees)}°")
        append(" • ارتفاع حروف: ${result.estimatedCharHeightPx.toInt()}px")
        // ارتفاع حروف مستقیم‌ترین پیش‌بینی‌کنندهٔ دقت است؛ اگر کم باشد کاربر باید
        // همان‌جا بداند، نه بعد از دیدن خروجیِ بد.
        if (result.estimatedCharHeightPx < 20) append(" ⚠ کم است؛ ناحیهٔ کوچک‌تری برش بزنید")
    }

    private fun cancelRunning() {
        runningJob?.cancel()
        runningJob = null
    }

    private fun releasePreprocessed() {
        preprocessed?.close()
        preprocessed = null
    }

    override fun onCleared() {
        super.onCleared()
        cancelRunning()
        releasePreprocessed()
        repository.close()
    }
}
