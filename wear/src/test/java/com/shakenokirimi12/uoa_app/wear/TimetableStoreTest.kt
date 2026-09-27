package com.shakenokirimi12.uoa_app.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class TimetableStoreTest {
    private fun at(h: Int, m: Int, day: Int = 28): Long =
        LocalDateTime.of(2026, 9, day, h, m).atZone(TimetableStore.ZONE).toInstant().toEpochMilli()

    private val events = listOf(
        ClassEvent("線形代数", "M1", at(9, 0), at(10, 40)),
        ClassEvent("【休講】英語", "S3", at(10, 50), at(12, 30)),
        ClassEvent("プログラミング", "std1", at(13, 20), at(15, 0)),
        ClassEvent("物理", "M2", at(9, 0, day = 29), at(10, 40, day = 29)),
    )

    @Test
    fun currentOrNext_prefersOngoing_thenSkipsCancelled() {
        assertEquals("線形代数", TimetableStore.currentOrNext(events, at(9, 30))!!.title)
        // 休講の英語を飛ばしてプログラミング
        assertEquals("プログラミング", TimetableStore.currentOrNext(events, at(10, 45))!!.title)
        assertEquals("物理", TimetableStore.currentOrNext(events, at(16, 0))!!.title)
        assertNull(TimetableStore.currentOrNext(events, at(11, 0, day = 29)))
    }

    @Test
    fun upcomingDays_groupsByDay_andKeepsTodaysPastClasses() {
        val days = TimetableStore.upcomingDays(events, at(14, 0), days = 5)
        assertEquals(2, days.size)
        assertEquals(3, days[0].second.size) // 今日は終わった授業も並べる (グレー表示)
        assertEquals(1, days[1].second.size)
    }

    @Test
    fun nextBoundary_isTheNextStartOrEnd() {
        assertEquals(at(10, 40), TimetableStore.nextBoundary(events, at(9, 30)))
        assertEquals(at(13, 20), TimetableStore.nextBoundary(events, at(12, 40)))
        assertNull(TimetableStore.nextBoundary(events, at(11, 0, day = 29)))
    }
}
