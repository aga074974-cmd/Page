package ir.page.persianocr.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import ir.page.persianocr.R
import ir.page.persianocr.databinding.ActivityLogBinding
import ir.page.persianocr.log.DiagnosticLog
import ir.page.persianocr.log.LogReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * صفحهٔ نمایش، ذخیره و ارسالِ گزارش اشکال‌یابی.
 *
 * چرا یک صفحهٔ جدا؟ چون روی گوشیِ کاربر هیچ راهی برای دیدن logcat نیست؛ وقتی چیزی
 * درست کار نمی‌کند، این صفحه تنها منبعِ حقیقت است.
 *
 * ساخت متن گزارش (که می‌تواند هزاران خط باشد) روی نخِ پس‌زمینه انجام می‌شود.
 */
class LogActivity : AppCompatActivity() {

    private companion object {
        /**
         * فاصلهٔ کمینه بین دو بازآوریِ صفحه. بدون این، هر رکورد تازه یک بازرسمِ کاملِ
         * چند هزار خطی راه می‌اندازد و صفحه کند می‌شود.
         */
        const val REFRESH_DELAY_MILLIS = 300L

        /**
         * سقفِ کپی در کلیپ‌بورد. کلیپ‌بورد اندروید از طریق Binder منتقل می‌شود و
         * متنِ خیلی بزرگ باعث `TransactionTooLargeException` می‌شود.
         */
        const val CLIPBOARD_LIMIT = 120_000
    }

    private lateinit var binding: ActivityLogBinding

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refresh() }
    private var refreshJob: Job? = null

    /** آخرین متنِ ساخته‌شده — همان چیزی که ذخیره/ارسال/کپی می‌شود. */
    private var currentReport: String = ""

    /** درخواست ساختِ فایل از سیستم: کاربر خودش محل و نام را انتخاب می‌کند. */
    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> uri?.let(::writeReportTo) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.onlyProblemsSwitch.setOnCheckedChangeListener { _, _ -> refresh(scrollToEnd = true) }
        binding.saveLogButton.setOnClickListener { createDocument.launch(LogReport.fileName()) }
        binding.shareLogButton.setOnClickListener { shareReport() }
        binding.copyLogButton.setOnClickListener { copyReport() }
        binding.clearLogButton.setOnClickListener { confirmClear() }

        binding.crashBanner.visibility =
            if (DiagnosticLog.previousCrash != null) View.VISIBLE else View.GONE

        // اگر هنگام باز بودن این صفحه رکورد تازه‌ای ثبت شود (مثلاً OCR در پس‌زمینه
        // ادامه دارد)، صفحه خودش به‌روز می‌شود.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DiagnosticLog.revision.collect { scheduleRefresh() }
            }
        }

        refresh(scrollToEnd = true)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        super.onDestroy()
    }

    // ─────────────────────────── بازآوری ───────────────────────────

    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, REFRESH_DELAY_MILLIS)
    }

    private fun refresh(scrollToEnd: Boolean = false) {
        val onlyProblems = binding.onlyProblemsSwitch.isChecked
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            // ساخت متن می‌تواند هزاران خط را قالب‌بندی کند؛ روی نخ اصلی جایش نیست.
            val report = withContext(Dispatchers.Default) { LogReport.build(this@LogActivity, onlyProblems) }
            currentReport = report

            // اگر کاربر انتهای گزارش را می‌بیند، خودکار دنبالهٔ تازه را نشان می‌دهیم؛
            // ولی اگر وسط متن در حال خواندن است، جایش را به هم نمی‌زنیم.
            val wasAtBottom = !binding.logScroll.canScrollVertically(1)

            val entryCount = DiagnosticLog.size()
            binding.logText.text = report
            binding.logSummary.text = DiagnosticLog.sessionFile?.let {
                getString(R.string.log_summary, entryCount, it.name)
            } ?: getString(R.string.log_summary_no_file, entryCount)

            if (scrollToEnd || wasAtBottom) {
                binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    // ─────────────────────── ذخیره / ارسال / کپی ───────────────────────

    private fun writeReportTo(uri: Uri) {
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(currentReport.toByteArray(Charsets.UTF_8))
                        output.flush()
                    } ?: error("openOutputStream returned null")
                }.exceptionOrNull()
            }
            if (error == null) {
                DiagnosticLog.i("Log", "گزارش در $uri ذخیره شد.")
                toast(getString(R.string.msg_log_saved))
            } else {
                DiagnosticLog.e("Log", "ذخیرهٔ گزارش شکست خورد", error)
                toast(getString(R.string.err_log_save, error.message ?: error::class.java.simpleName))
            }
        }
    }

    private fun shareReport() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = LogReport.writeToCache(this@LogActivity, currentReport)
                    FileProvider.getUriForFile(this@LogActivity, "$packageName.fileprovider", file)
                }
            }
            result.onSuccess { uri ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, LogReport.fileName())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.msg_log_share_title)))
            }.onFailure { error ->
                DiagnosticLog.e("Log", "ارسال گزارش شکست خورد", error)
                toast(getString(R.string.err_log_share, error.message ?: error::class.java.simpleName))
            }
        }
    }

    private fun copyReport() {
        val clipboard = getSystemService<ClipboardManager>() ?: return
        val text = if (currentReport.length > CLIPBOARD_LIMIT) {
            currentReport.takeLast(CLIPBOARD_LIMIT)
        } else {
            currentReport
        }
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.log_title), text))
            toast(getString(R.string.msg_log_copied))
        } catch (t: Throwable) {
            // گزارش برای کلیپ‌بورد بزرگ است — «ذخیره در فایل» همیشه کار می‌کند.
            DiagnosticLog.w("Log", "کپی در کلیپ‌بورد ممکن نشد", t)
            toast(getString(R.string.err_log_copy, t::class.java.simpleName))
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.btn_log_clear)
            .setMessage(R.string.log_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DiagnosticLog.clear()
                toast(getString(R.string.msg_log_cleared))
                refresh(scrollToEnd = true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }
}
