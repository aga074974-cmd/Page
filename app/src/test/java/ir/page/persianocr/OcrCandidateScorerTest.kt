package ir.page.persianocr

import ir.page.persianocr.ocr.OcrCandidateScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * امتیازِ یک حالت = اطمینان × کلماتِ مطمئن.
 *
 * تستِ کلیدی، اولی است: همان سناریوی واقعیِ گزارش که باعث شد فرمولِ قبلی عوض شود.
 */
class OcrCandidateScorerTest {

    @Test
    fun `a mode that drops a line no longer wins on confidence alone`() {
        // سناریوی واقعی از گزارش: SAUVOLA یک خط را انداخت ولی اطمینانش بالاتر بود.
        val incompleteButConfident = OcrCandidateScorer.score(
            meanConfidence = 88,
            strongWordCount = 210,
            wordCount = 250,
        )
        val completeButNoisier = OcrCandidateScorer.score(
            meanConfidence = 84,
            strongWordCount = 240,
            wordCount = 290,
        )
        assertTrue(
            "incomplete=$incompleteButConfident complete=$completeButNoisier",
            completeButNoisier > incompleteButConfident,
        )
    }

    @Test
    fun `confidence still separates modes of equal completeness`() {
        val better = OcrCandidateScorer.score(90, 100, 120)
        val worse = OcrCandidateScorer.score(70, 100, 120)
        assertTrue("better=$better worse=$worse", better > worse)
    }

    @Test
    fun `word count stands in when word confidences are unavailable`() {
        // مسیر جایگزین: ResultIterator چیزی نداد، پس strongWordCount برای همه صفر است.
        val long = OcrCandidateScorer.score(80, strongWordCount = 0, wordCount = 300)
        val short = OcrCandidateScorer.score(80, strongWordCount = 0, wordCount = 100)
        assertTrue("long=$long short=$short", long > short)
    }

    @Test
    fun `empty output scores zero`() {
        assertEquals(0.0, OcrCandidateScorer.score(99, 0, 0), 0.0)
    }
}
