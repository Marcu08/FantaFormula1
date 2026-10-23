package com.fantaformula1.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F2 — gli errori di archiviazione devono diventare messaggi in italiano:
 * il caso tipico (snapshot gia' esistente) deve dire "La stagione e' gia'
 * stata archiviata" invece di mostrare l'errore grezzo di Postgres.
 */
class SeasonHistoryErrorTest {

    @Test
    fun `snapshot gia' esistente diventa gia' archiviata`() {
        val message = mapSeasonHistoryError(
            "Duplicate primary key violation (\"season_snapshots_pkey\")",
            "archiviare la stagione",
        )

        assertEquals("La stagione è già stata archiviata", message)
    }

    @Test
    fun `unique violation diventa gia' archiviata`() {
        val message = mapSeasonHistoryError(
            "duplicate key value violates unique constraint \"season_snapshots_pkey\"",
            "archiviare la stagione",
        )

        assertEquals("La stagione è già stata archiviata", message)
    }

    @Test
    fun `violazione RLS diventa solo un amministratore`() {
        val message = mapSeasonHistoryError(
            "new row violates row-level security policy for table \"season_snapshots\"",
            "archiviare la stagione",
        )

        assertEquals("Solo un amministratore può archiviare la stagione", message)
    }

    @Test
    fun `messaggio grezzo sconosciuto viene passato com'e'`() {
        val message = mapSeasonHistoryError("errore di rete", "archiviare la stagione")

        assertEquals("errore di rete", message)
    }

    @Test
    fun `messaggio assente usa il fallback con l'azione`() {
        assertEquals(
            "Impossibile archiviare la stagione",
            mapSeasonHistoryError(null, "archiviare la stagione"),
        )
    }

    @Test
    fun `permission denied di postgres diventa messaggio admin`() {
        val message = mapSeasonHistoryError(
            "permission denied for table season_snapshots",
            "archiviare la stagione",
        )

        assertEquals("Solo un amministratore può archiviare la stagione", message)
    }

    @Test
    fun `valore null nei campi obbligatori diventa dati non validi`() {
        val message = mapSeasonHistoryError(
            "null value in column \"season_name\" violates not-null constraint",
            "archiviare la stagione",
        )

        assertEquals("Dati della stagione non validi", message)
    }
}
