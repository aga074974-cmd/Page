package ir.page.persianocr.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import ir.page.persianocr.R
import ir.page.persianocr.databinding.ActivityMainBinding
import ir.page.persianocr.image.BinarizationMethod
import ir.page.persianocr.ocr.OcrResult
import ir.page.persianocr.util.BitmapIo
import kotlinx.coroutines.launch

/**
 * تنها صفحهٔ اپ. مسئولیتش فقط «رسمِ وضعیت» و «ارسال رویداد» است؛
 * هیچ منطق پردازشی اینجا نیست و هیچ کار سنگینی روی نخ اصلی انجام نمی‌شود.
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val KEY_CAMERA_URI = "camera_uri"
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    /** URI فایلی که به اپ دوربین داده‌ایم؛ باید از مرگ پروسه جان سالم به در ببرد. */
    private var pendingCameraUri: Uri? = null

    /** برای جلوگیری از تنظیم دوبارهٔ تصویر/متن در هر بار انتشار وضعیت. */
    private var appliedSourceImage: Bitmap? = null
    private var appliedPreview: Bitmap? = null
    private var appliedResult: OcrResult? = null
    private var bindingSpinner = false
    private var errorDialogShowing = false

    // ─────────────────────── قراردادهای Activity Result ───────────────────────

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::onImagePicked) }

    private val takePicture = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && uri != null) viewModel.onImagePicked(uri)
    }

    // ─────────────────────────── چرخهٔ حیات ───────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        savedInstanceState?.getString(KEY_CAMERA_URI)?.let { pendingCameraUri = Uri.parse(it) }

        setUpControls()
        observeState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingCameraUri?.let { outState.putString(KEY_CAMERA_URI, it.toString()) }
    }

    // ─────────────────────────── اتصال کنترل‌ها ───────────────────────────

    private fun setUpControls() {
        with(binding) {
            galleryButton.setOnClickListener {
                pickImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }

            cameraButton.setOnClickListener { launchCamera() }

            cropConfirmButton.setOnClickListener {
                // برش روی نخ اصلی انجام می‌شود چون فقط یک کپیِ حافظه است؛ بقیهٔ کار در ViewModel.
                viewModel.onCropConfirmed(cropView.getCroppedBitmap())
            }
            cropResetButton.setOnClickListener { cropView.resetCrop() }

            variantSpinner.adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                BinarizationMethod.entries.map { it.label },
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            variantSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (bindingSpinner) return
                    viewModel.onMethodSelected(BinarizationMethod.entries[position])
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

            multiPassSwitch.setOnCheckedChangeListener { _, checked -> viewModel.onMultiPassChanged(checked) }
            singleBlockSwitch.setOnCheckedChangeListener { _, checked -> viewModel.onSingleBlockChanged(checked) }

            runOcrButton.setOnClickListener { viewModel.onRunOcr() }
            cancelButton.setOnClickListener { viewModel.onCancel() }
            restartButton.setOnClickListener { viewModel.onRestart() }

            copyButton.setOnClickListener { copyToClipboard() }
            shareButton.setOnClickListener { shareText() }
        }
    }

    private fun launchCamera() {
        try {
            val file = BitmapIo.newCameraFile(this)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            pendingCameraUri = uri
            takePicture.launch(uri)
        } catch (_: Throwable) {
            pendingCameraUri = null
            // معمولاً یعنی هیچ اپ دوربینی روی دستگاه نیست.
            Snackbar.make(binding.root, R.string.err_no_camera, Snackbar.LENGTH_LONG).show()
        }
    }

    // ─────────────────────────── مشاهدهٔ وضعیت ───────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    private fun render(state: MainUiState) {
        with(binding) {
            // ── بخش‌ها ───────────────────────────────────────────────────────
            cropSection.isVisible(state.stage != Stage.IDLE)
            preprocessSection.isVisible(state.stage == Stage.PREPROCESSED || state.stage == Stage.RESULT)
            resultSection.isVisible(state.stage == Stage.RESULT)
            idleHint.isVisible(state.stage == Stage.IDLE)

            // ── تصویر مبدأ (فقط وقتی واقعاً عوض شده) ─────────────────────────
            if (state.sourceImage !== appliedSourceImage) {
                appliedSourceImage = state.sourceImage
                cropView.setBitmap(state.sourceImage)
            }

            // ── پیش‌نمایش پیش‌پردازش ─────────────────────────────────────────
            if (state.preprocessedPreview !== appliedPreview) {
                appliedPreview = state.preprocessedPreview
                preprocessedImage.setImageBitmap(state.preprocessedPreview)
            }
            preprocessInfo.text = state.preprocessInfo.orEmpty()
            preprocessInfo.isVisible(!state.preprocessInfo.isNullOrEmpty())

            // ── کنترل‌ها ────────────────────────────────────────────────────
            val methodIndex = BinarizationMethod.entries.indexOf(state.selectedMethod)
            if (variantSpinner.selectedItemPosition != methodIndex) {
                bindingSpinner = true
                variantSpinner.setSelection(methodIndex)
                bindingSpinner = false
            }
            if (multiPassSwitch.isChecked != state.multiPass) multiPassSwitch.isChecked = state.multiPass
            if (singleBlockSwitch.isChecked != state.singleBlockMode) {
                singleBlockSwitch.isChecked = state.singleBlockMode
            }

            val idle = !state.busy
            galleryButton.isEnabled = idle
            cameraButton.isEnabled = idle
            cropConfirmButton.isEnabled = idle
            cropResetButton.isEnabled = idle
            runOcrButton.isEnabled = idle
            variantSpinner.isEnabled = idle
            multiPassSwitch.isEnabled = idle
            singleBlockSwitch.isEnabled = idle

            // ── نوار پیشرفت ─────────────────────────────────────────────────
            progressContainer.isVisible(state.busy)
            if (state.busy) {
                progressBar.applyProgress(state.progressPercent)
                statusText.text = state.statusRes?.let { res ->
                    state.statusArg?.let { getString(res, it) } ?: getString(res)
                }.orEmpty()
            }

            // ── نتیجه ───────────────────────────────────────────────────────
            val result = state.result
            if (result !== appliedResult) {
                appliedResult = result
                resultText.setText(result?.best?.text.orEmpty())
                resultStats.text = result?.let { statsLine(it) }.orEmpty()
            }

            // ── خطا و پیام ──────────────────────────────────────────────────
            state.error?.let { showError(it) }
            state.message?.let { message ->
                Snackbar.make(root, message.messageRes, Snackbar.LENGTH_SHORT).show()
                viewModel.onMessageShown()
            }
        }
    }

    private fun statsLine(result: OcrResult): String {
        val confidence = getString(
            R.string.label_confidence,
            result.best.meanConfidence,
            result.best.method.label,
        )
        val elapsed = getString(R.string.label_elapsed, result.elapsedMillis / 1000f)
        return "$confidence\n$elapsed"
    }

    private fun showError(error: UiError) {
        // بدون این نگهبان، هر بار انتشار وضعیت یک دیالوگ تازه روی قبلی باز می‌شود.
        if (errorDialogShowing) return
        val message = error.literal ?: error.messageRes?.let { res ->
            error.formatArg?.let { getString(res, it) } ?: getString(res)
        } ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_name)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener {
                errorDialogShowing = false
                viewModel.onErrorShown()
            }
            .show()
        errorDialogShowing = true
    }

    // ─────────────────────── کپی و اشتراک‌گذاری ───────────────────────

    private fun currentText(): String = binding.resultText.text?.toString().orEmpty()

    private fun copyToClipboard() {
        val text = currentText()
        if (text.isBlank()) return
        val clipboard = getSystemService<ClipboardManager>() ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        // اندروید ۱۳ به بعد خودش پیام «کپی شد» را نشان می‌دهد؛ پیام دوم اضافه است.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            Snackbar.make(binding.root, R.string.msg_copied, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun shareText() {
        val text = currentText()
        if (text.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.msg_share_title)))
    }

    /**
     * تعویض امنِ حالتِ معین/نامعینِ نوار پیشرفت.
     *
     * `LinearProgressIndicator` متعلق به Material اگر در حالی که روی صفحه دیده می‌شود
     * به حالت نامعین سوییچ شود، `IllegalStateException` پرتاب می‌کند. بنابراین پیش از
     * تعویض، خودِ نوار را لحظه‌ای پنهان می‌کنیم.
     */
    private fun LinearProgressIndicator.applyProgress(percent: Int?) {
        val wantIndeterminate = percent == null
        if (isIndeterminate != wantIndeterminate) {
            val previous = visibility
            visibility = View.GONE
            isIndeterminate = wantIndeterminate
            visibility = previous
        }
        if (percent != null) setProgressCompat(percent, true)
    }

    private fun View.isVisible(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }
}
