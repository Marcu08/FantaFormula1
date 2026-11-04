package com.fantaformula1.app.ui.predictions

import com.fantaformula1.app.core.domain.PredictionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Raggruppamento dei tipi di pronostico nelle sezioni comprimibili. */
class PredictionSectionsTest {

    @Test
    fun `mappatura esplicita tipo per tipo`() {
        val expected = mapOf(
            PredictionType.WINNER to PredictionSection.RACE_RESULT,
            PredictionType.PODIUM to PredictionSection.RACE_RESULT,
            PredictionType.FASTEST_LAP to PredictionSection.RACE_RESULT,
            PredictionType.POLE to PredictionSection.RACE_RESULT,
            PredictionType.DRIVER_EXACT_POSITION to PredictionSection.RACE_RESULT,
            PredictionType.DRIVER_POSITION_RANGE to PredictionSection.RACE_RESULT,
            PredictionType.HEAD_TO_HEAD to PredictionSection.HEAD_TO_HEAD,
            PredictionType.DRIVER_DNF to PredictionSection.RACE_EVENTS,
            PredictionType.SAFETY_CAR_COUNT_OVER_UNDER to PredictionSection.RACE_EVENTS,
            PredictionType.PIT_STOPS_OVER_UNDER to PredictionSection.RACE_EVENTS,
            PredictionType.DRIVER_POINTS_OVER_UNDER to PredictionSection.STATISTICS,
            PredictionType.SPRINT_WINNER to PredictionSection.SPRINT,
        )

        assertEquals(PredictionType.values().toSet(), expected.keys)
        expected.forEach { (type, section) ->
            assertEquals("Sezione errata per $type", section, type.section)
        }
    }

    @Test
    fun `ogni tipo appartiene a una sola sezione`() {
        val grouped = groupBySection(PredictionType.values().toList())

        assertEquals(PredictionType.values().toSet(), grouped.flatMap { it.second }.toSet())
        assertEquals(PredictionType.values().size, grouped.sumOf { it.second.size })
    }

    @Test
    fun `l'ordine delle sezioni segue l'enum`() {
        val grouped = groupBySection(
            listOf(
                PredictionType.SPRINT_WINNER,
                PredictionType.DRIVER_DNF,
                PredictionType.WINNER,
                PredictionType.HEAD_TO_HEAD,
            ),
        )

        assertEquals(
            listOf(
                PredictionSection.RACE_RESULT,
                PredictionSection.HEAD_TO_HEAD,
                PredictionSection.RACE_EVENTS,
                PredictionSection.SPRINT,
            ),
            grouped.map { it.first },
        )
    }

    @Test
    fun `l'ordine dei tipi dentro la sezione e preservato`() {
        val grouped = groupBySection(
            listOf(
                PredictionType.PODIUM,
                PredictionType.WINNER,
                PredictionType.FASTEST_LAP,
            ),
        )

        assertEquals(listOf(PredictionSection.RACE_RESULT), grouped.map { it.first })
        assertEquals(
            listOf(PredictionType.PODIUM, PredictionType.WINNER, PredictionType.FASTEST_LAP),
            grouped.single().second,
        )
    }

    @Test
    fun `le sezioni vuote sono escluse`() {
        val grouped = groupBySection(
            listOf(PredictionType.WINNER, PredictionType.HEAD_TO_HEAD),
        )

        assertEquals(
            listOf(PredictionSection.RACE_RESULT, PredictionSection.HEAD_TO_HEAD),
            grouped.map { it.first },
        )
    }

    @Test
    fun `lista vuota produce lista vuota`() {
        assertTrue(groupBySection(emptyList()).isEmpty())
    }
}
