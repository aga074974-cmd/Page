package ir.page.persianocr

import ir.page.persianocr.ocr.OcrCandidateScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrCandidateScorerTest {

    @Test
    fun `clean persian text beats garbage with the same confidence`() {
        val clean = OcrCandidateScorer.score("این یک متن فارسی سالم است", 80, maxLength = 21)
        val garbage = OcrCandidateScorer.score("§¤~|»½¬×÷þ¶©®°µ¿¡¦", 80, maxLength = 21)
        assertTrue("clean=$clean garbage=$garbage", clean > garbage)
    }

    @Test
    fun `longer output wins when quality is equal`() {
        val long = OcrCandidateScorer.score("متن کامل و طولانی فارسی", 70, maxLength = 20)
        val short = OcrCandidateScorer.score("متن", 70, maxLength = 20)
        assertTrue("long=$long short=$short", long > short)
    }

    @Test
    fun `empty text scores zero`() {
        assertEquals(0.0, OcrCandidateScorer.score("   ", 99, maxLength = 10), 0.0)
    }
}
