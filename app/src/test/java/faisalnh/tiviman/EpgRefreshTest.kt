package faisalnh.tiviman

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class EpgRefreshTest {
    private val max = EpgRefreshPolicy.MAX_JOBS

    @Test fun idGrammarMatchesPlaylistStore() {
        assertTrue(EpgRefreshPolicy.isValidId("legacy"))
        assertTrue(EpgRefreshPolicy.isValidId(UUID.randomUUID().toString()))
        assertFalse(EpgRefreshPolicy.isValidId(""))
        assertFalse(EpgRefreshPolicy.isValidId("../legacy"))
        assertFalse(EpgRefreshPolicy.isValidId(UUID.randomUUID().toString().uppercase()))
    }

    @Test fun jobIdsStayInReservedRange() {
        assertEquals(EpgRefreshPolicy.JOB_ID_BASE, EpgRefreshPolicy.jobId(0))
        assertEquals(0, EpgRefreshPolicy.slotOf(EpgRefreshPolicy.jobId(0)))
        assertEquals(max - 1, EpgRefreshPolicy.slotOf(EpgRefreshPolicy.jobId(max - 1)))
        assertNull(EpgRefreshPolicy.slotOf(EpgRefreshPolicy.JOB_ID_BASE - 1))
        assertNull(EpgRefreshPolicy.slotOf(EpgRefreshPolicy.JOB_ID_BASE + max))
        assertThrows(IllegalArgumentException::class.java) { EpgRefreshPolicy.jobId(max) }
    }

    @Test fun preferredSlotIsStableHash() {
        val id = "123e4567-e89b-12d3-a456-426614174000"
        val expected = Math.floorMod(id.hashCode(), max)
        assertEquals(expected, EpgRefreshPolicy.assignSlot(id, emptySet()))
        assertEquals(expected, EpgRefreshPolicy.assignSlot(id, emptySet()))
    }

    @Test fun collisionsProbeToNextFreeSlot() {
        val id = "legacy"
        val start = Math.floorMod(id.hashCode(), max)
        assertEquals((start + 1) % max, EpgRefreshPolicy.assignSlot(id, setOf(start)))
        assertEquals((start + 2) % max, EpgRefreshPolicy.assignSlot(id, setOf(start, (start + 1) % max)))
    }

    @Test fun existingSlotIsKeptWhileFreeOtherwiseReassigned() {
        val id = "legacy"
        assertEquals(7, EpgRefreshPolicy.assignSlot(id, setOf(3), current = 7))
        val start = Math.floorMod(id.hashCode(), max)
        assertEquals(start, EpgRefreshPolicy.assignSlot(id, setOf(7), current = 7))
        assertNull(EpgRefreshPolicy.assignSlot(id, emptySet(), current = 99)?.takeIf { it !in 0 until max })
    }

    @Test fun thirtyTwoPlaylistsGetUniqueSlotsAndThirtyThirdIsRejected() {
        val taken = mutableSetOf<Int>()
        repeat(max) {
            val slot = EpgRefreshPolicy.assignSlot(UUID.randomUUID().toString(), taken)
            assertNotNull(slot)
            assertTrue(taken.add(slot!!))
        }
        assertEquals((0 until max).toSet(), taken)
        assertNull(EpgRefreshPolicy.assignSlot(UUID.randomUUID().toString(), taken))
    }

    @Test fun intervalIsSixHours() {
        assertEquals(6 * 60 * 60 * 1000L, EpgRefreshPolicy.INTERVAL_MS)
    }
}
