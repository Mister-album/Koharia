package koharia.connection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReadingQueueResumeTest {
    @Test
    fun `continue unfinished remembered book even if earlier books remain unread`() {
        assertEquals(2, readingQueueResumeIndex(listOf(false, true, false, false), 2))
    }

    @Test
    fun `completed remembered book advances then wraps to first unread`() {
        assertEquals(3, readingQueueResumeIndex(listOf(false, true, true, false), 2))
        assertEquals(0, readingQueueResumeIndex(listOf(false, true, true), 2))
    }

    @Test
    fun `missing saved item empty and completed queues have bounded fallbacks`() {
        assertEquals(1, readingQueueResumeIndex(listOf(true, false), -1))
        assertEquals(0, readingQueueResumeIndex(listOf(true, true), 1))
        assertEquals(null, readingQueueResumeIndex(emptyList(), -1))
    }
}
