package com.fantaformula1.app.core.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Helper del calendario per nascondere le gare passate: una gara e'
 * "passata" se la deadline e' superata oppure il risultato e' registrato,
 * indipendentemente dallo stato salvato.
 */
class RaceTest {

    private fun race(
        deadline: Instant? = null,
        status: RaceStatus = RaceStatus.SCHEDULED,
    ) = Race(
        id = "r1",
        seasonId = "s1",
        leagueId = "l1",
        round = 1,
        name = "Gran Premio di Prova",
        deadline = deadline,
        status = status,
    )

    private val now: Instant = Instant.parse("2026-10-13T12:00:00Z")

    @Test
    fun `gara con deadline futura non e' passata`() {
        val race = race(deadline = Instant.parse("2026-10-20T15:00:00Z"))

        assertFalse(race.isPastRace(now))
    }

    @Test
    fun `gara con deadline superata e' passata anche senza risultato`() {
        // Caso chiave: gare sincronizzate prima di oggi, risultato non ancora
        // salvato. Lo stato resta SCHEDULED ma la deadline e' passata.
        val race = race(
            deadline = Instant.parse("2026-10-11T15:00:00Z"),
            status = RaceStatus.SCHEDULED,
        )

        assertTrue(race.isPastRace(now))
    }

    @Test
    fun `gara completata e' passata anche con deadline futura`() {
        val race = race(
            deadline = Instant.parse("2026-10-20T15:00:00Z"),
            status = RaceStatus.COMPLETED,
        )

        assertTrue(race.isPastRace(now))
    }

    @Test
    fun `gara con deadline esattamente ad ora non e' passata`() {
        // isDeadlinePassed usa !now.isBefore(deadline): alla deadline esatta
        // la gara risulta passata, un istante prima no.
        val race = race(deadline = now)
        val justBefore = race(deadline = now.plusSeconds(1))

        assertTrue(race.isPastRace(now))
        assertFalse(justBefore.isPastRace(now))
    }

    @Test
    fun `gara sempre aperta (senza deadline) non e' passata`() {
        val race = race(deadline = null)

        assertFalse(race.isPastRace(now))
    }

    @Test
    fun `gara chiusa manualmente con deadline futura non e' passata`() {
        // L'admin puo' chiudere i pronostici in anticipo: la gara resta in
        // lista perche' non e' ancora passata.
        val race = race(
            deadline = Instant.parse("2026-10-20T15:00:00Z"),
            status = RaceStatus.CLOSED,
        )

        assertFalse(race.isPastRace(now))
    }
}