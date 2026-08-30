package ir.page.persianocr

import ir.page.persianocr.image.BinarizationMethod
import ir.page.persianocr.ocr.ModeOutlierDetector
import ir.page.persianocr.ocr.ModeStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** تشخیصِ حالتِ پرت — باگ ۲. */
class ModeOutlierDetectorTest {

    /** دقیقاً آمارِ گزارشِ واقعیِ دستگاه. */
    private val fromRealLog = listOf(
        ModeStats(BinarizationMethod.SAUVOLA, lineCount = 17, meanConfidence = 85),
        ModeStats(BinarizationMethod.ADAPTIVE_GAUSSIAN, lineCount = 40, meanConfidence = 53),
        ModeStats(BinarizationMethod.ADAPTIVE_MEAN, lineCount = 14, meanConfidence = 83),
        ModeStats(BinarizationMethod.OTSU, lineCount = 20, meanConfidence = 87),
        ModeStats(BinarizationMethod.CLAHE_OTSU, lineCount = 21, meanConfidence = 86),
    )

    @Test
    fun `the noisy mode from the real log is flagged`() {
        val analysis = ModeOutlierDetector.analyse(fromRealLog)
        assertEquals(setOf(BinarizationMethod.ADAPTIVE_GAUSSIAN), analysis.outliers)
        assertEquals(20, analysis.medianLines)
        assertEquals(85, analysis.medianConfidence)
        val reason = analysis.reasons.getValue(BinarizationMethod.ADAPTIVE_GAUSSIAN)
        assertTrue(reason, reason.contains("40"))
        assertTrue(reason, reason.contains("53"))
    }

    @Test
    fun `healthy modes are all kept`() {
        val healthy = listOf(
            ModeStats(BinarizationMethod.SAUVOLA, 18, 85),
            ModeStats(BinarizationMethod.ADAPTIVE_GAUSSIAN, 19, 84),
            ModeStats(BinarizationMethod.ADAPTIVE_MEAN, 20, 83),
            ModeStats(BinarizationMethod.OTSU, 20, 87),
            ModeStats(BinarizationMethod.CLAHE_OTSU, 21, 86),
        )
        assertTrue(ModeOutlierDetector.analyse(healthy).outliers.isEmpty())
    }

    @Test
    fun `a low-confidence mode alone is enough`() {
        val stats = listOf(
            ModeStats(BinarizationMethod.SAUVOLA, 20, 88),
            ModeStats(BinarizationMethod.OTSU, 20, 86),
            ModeStats(BinarizationMethod.ADAPTIVE_MEAN, 19, 40),
        )
        assertEquals(setOf(BinarizationMethod.ADAPTIVE_MEAN), ModeOutlierDetector.analyse(stats).outliers)
    }

    @Test
    fun `too few modes means no verdict`() {
        val stats = listOf(
            ModeStats(BinarizationMethod.OTSU, 20, 88),
            ModeStats(BinarizationMethod.SAUVOLA, 90, 20),
        )
        assertTrue(ModeOutlierDetector.analyse(stats).outliers.isEmpty())
    }

    @Test
    fun `when half the modes look wrong nobody is discarded`() {
        // صفحه‌ای که همهٔ حالت‌ها رویش بد کار می‌کنند: کنارگذاشتنِ نیمی از شواهد
        // بدتر از نگه‌داشتنِ همه‌شان است.
        val stats = listOf(
            ModeStats(BinarizationMethod.SAUVOLA, 10, 80),
            ModeStats(BinarizationMethod.OTSU, 10, 30),
            ModeStats(BinarizationMethod.ADAPTIVE_MEAN, 40, 28),
            ModeStats(BinarizationMethod.CLAHE_OTSU, 45, 26),
        )
        assertTrue(ModeOutlierDetector.analyse(stats).outliers.isEmpty())
    }
}
