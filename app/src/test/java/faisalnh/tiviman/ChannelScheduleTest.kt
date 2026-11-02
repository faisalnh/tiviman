package faisalnh.tiviman

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ChannelScheduleTest {
    private val fixture = Fixtures.channels.first()
    private fun clock(minute: Int): String {
        val normalized = ((minute % 1440) + 1440) % 1440
        return "%02d:%02d".format(Locale.ROOT, normalized / 60, normalized % 60)
    }

    @Test fun currentFixtureRowIsInitiallySelected() {
        assertEquals(0, ChannelScheduleLogic.currentIndex(fixture.programmes, Fixtures.NOW))
    }

    @Test fun startIsInclusiveAndEndIsExclusive() {
        val programme = fixture.programmes.first()
        assertEquals(ChannelScheduleLogic.Position.FUTURE, ChannelScheduleLogic.position(programme, -1))
        assertEquals(ChannelScheduleLogic.Position.CURRENT, ChannelScheduleLogic.position(programme, programme.start))
        assertEquals(ChannelScheduleLogic.Position.PAST, ChannelScheduleLogic.position(programme, programme.end))
        assertEquals(1, ChannelScheduleLogic.currentIndex(fixture.programmes, programme.end))
    }

    @Test fun fixturesIncludeFutureAndPastRowsWithoutTuning() {
        assertEquals(ChannelScheduleLogic.Position.FUTURE,
            ChannelScheduleLogic.position(fixture.programmes.last(), Fixtures.NOW))
        assertEquals(ChannelScheduleLogic.Position.PAST,
            ChannelScheduleLogic.position(fixture.programmes.first(), 60))
        assertEquals(-1, ChannelScheduleLogic.currentIndex(fixture.programmes, 1000))
    }

    @Test fun invalidDurationIsNotCurrent() {
        for (duration in listOf(0, -1)) {
            val programme = fixture.programmes.first().copy(duration = duration)
            assertEquals(ChannelScheduleLogic.Position.UNKNOWN, ChannelScheduleLogic.position(programme, Fixtures.NOW))
            assertEquals(-1, ChannelScheduleLogic.currentIndex(listOf(programme), Fixtures.NOW))
        }
    }

    @Test fun noInformationFixtureAndEmptyChannelAreEmpty() {
        assertTrue(ChannelScheduleLogic.isEmpty(Fixtures.channels[7].programmes))
        assertTrue(ChannelScheduleLogic.isEmpty(emptyList()))
        assertFalse(ChannelScheduleLogic.isEmpty(fixture.programmes))
        assertEquals(-1, ChannelScheduleLogic.currentIndex(Fixtures.channels[7].programmes, Fixtures.NOW))
    }

    @Test fun missingInformationIsFilteredFromMixedGuide() {
        val rows = Fixtures.channels[7].programmes + fixture.programmes
        assertFalse(ChannelScheduleLogic.isEmpty(rows))
        assertEquals(fixture.programmes,
            ChannelScheduleLogic.split(rows, Fixtures.NOW, Fixtures::time).today)
    }

    @Test fun relativeFixtureClockDoesNotInventTomorrow() {
        val days = ChannelScheduleLogic.split(fixture.programmes, Fixtures.NOW, Fixtures::time)
        assertEquals(fixture.programmes, days.today)
        assertTrue(days.tomorrow.isEmpty())
    }

    @Test fun minutesFromMidnightSplitAtNextMidnight() {
        val rows = fixture.programmes.mapIndexed { index, programme -> programme.copy(start = 1380 + index * 30) }
        val days = ChannelScheduleLogic.split(rows, 1400, ::clock)
        assertEquals(rows.take(2), days.today)
        assertEquals(rows.drop(2), days.tomorrow)
    }

    @Test fun programmeCrossingMidnightBelongsToStartingDay() {
        val crossing = fixture.programmes.first().copy(start = 1430, duration = 60)
        val tomorrow = fixture.programmes[1].copy(start = 1440)
        val days = ChannelScheduleLogic.split(listOf(tomorrow, crossing), 1435, ::clock)
        assertEquals(listOf(crossing), days.today)
        assertEquals(listOf(tomorrow), days.tomorrow)
    }

    @Test fun splitExcludesDayAfterTomorrow() {
        val rows = fixture.programmes.take(3).mapIndexed { index, programme -> programme.copy(start = index * 1440) }
        val days = ChannelScheduleLogic.split(rows, 0, ::clock)
        assertEquals(listOf(rows[0]), days.today)
        assertEquals(listOf(rows[1]), days.tomorrow)
    }

    @Test fun unclassifiableFormatterKeepsOnlyToday() {
        val days = ChannelScheduleLogic.split(fixture.programmes, Fixtures.NOW) { "Unknown time" }
        assertEquals(fixture.programmes, days.today)
        assertTrue(days.tomorrow.isEmpty())
    }

    @Test fun realEpgEpochMinutesUseLocalMidnight() {
        val midnight = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 30, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val next = (midnight.timeInMillis / 60000L).toInt()
        val rows = fixture.programmes.take(2).mapIndexed { index, programme ->
            programme.copy(start = next - 30 + index * 30)
        }
        val format = SimpleDateFormat("HH:mm", Locale.ROOT)
        val days = ChannelScheduleLogic.split(rows, next - 10) { format.format(java.util.Date(it * 60000L)) }
        assertEquals(listOf(rows[0]), days.today)
        assertEquals(listOf(rows[1]), days.tomorrow)
    }

    @Test fun durationLabelsUseStoredMinutes() {
        assertEquals("30 min", ChannelScheduleLogic.durationLabel(fixture.programmes.first()))
        assertEquals("15 min", ChannelScheduleLogic.durationLabel(fixture.programmes[1]))
        assertEquals("Duration unavailable", ChannelScheduleLogic.durationLabel(fixture.programmes.first().copy(duration = 0)))
    }
}
