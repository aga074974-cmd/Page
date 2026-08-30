package ir.page.persianocr

import ir.page.persianocr.log.LogEntry
import ir.page.persianocr.log.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * تست‌های قالب‌بندی گزارش.
 *
 * قالبِ خطوط قراردادِ عملیِ گزارش است: هم روی صفحه ستون‌بندی می‌شود و هم کاربر یا
 * توسعه‌دهنده با چشم دنبال ساعت و برچسب می‌گردد. پس همین‌جا میخکوبش می‌کنیم.
 */
class LogEntryTest {

    private fun entry(
        level: LogLevel = LogLevel.INFO,
        message: String = "پیام آزمایشی",
        sinceStartMillis: Long = 3_210,
        stackTrace: String? = null,
    ) = LogEntry(
        atMillis = 1_700_000_000_000,
        sinceStartMillis = sinceStartMillis,
        level = level,
        tag = "OCR",
        message = message,
        threadName = "main",
        stackTrace = stackTrace,
    )

    @Test
    fun `format starts with a wall clock time`() {
        val line = entry().format()
        // ساعت به منطقهٔ زمانیِ دستگاه بستگی دارد، پس فقط شکلش را می‌سنجیم.
        assertTrue(line, Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} ").containsMatchIn(line))
    }

    @Test
    fun `format carries level, tag, thread and message`() {
        val line = entry(level = LogLevel.WARN, message = "چیزی درست نبود").format()
        assertTrue(line, line.contains("W/OCR"))
        assertTrue(line, line.contains("[main]"))
        assertTrue(line, line.endsWith("چیزی درست نبود"))
    }

    @Test
    fun `elapsed column uses latin digits with three decimals`() {
        // ارقام لاتین عمدی است: گزارش باید در هر ویرایشگری قابل جست‌وجو بماند.
        assertTrue(entry(sinceStartMillis = 3_210).format().contains("+3.210s"))
        assertTrue(entry(sinceStartMillis = 0).format().contains("+0.000s"))
    }

    @Test
    fun `stack trace lines are indented under the message`() {
        val line = entry(
            level = LogLevel.ERROR,
            stackTrace = "java.io.IOException: boom\n\tat Foo.bar(Foo.kt:1)",
        ).format()

        val lines = line.lines()
        assertEquals(3, lines.size)
        assertTrue(lines[1], lines[1].startsWith("      java.io.IOException: boom"))
        assertTrue(lines[2], lines[2].startsWith("      "))
    }

    @Test
    fun `only warnings and above count as problems`() {
        assertFalse(LogLevel.DEBUG.isProblem)
        assertFalse(LogLevel.INFO.isProblem)
        assertTrue(LogLevel.WARN.isProblem)
        assertTrue(LogLevel.ERROR.isProblem)
        assertTrue(LogLevel.FATAL.isProblem)
    }
}
