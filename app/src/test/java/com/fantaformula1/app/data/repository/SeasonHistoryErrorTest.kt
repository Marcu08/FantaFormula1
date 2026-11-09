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
    fun `violazione RLS indica permessi o app non aggiornata`() {
        val message = mapSeasonHistoryError(
            "new row violates row-level security policy for table \"season_snapshots\"",
            "archiviare la stagione",
        )

        assertEquals(
            "Permessi insufficienti o versione dell'app non aggiornata. Aggiorna l'app e riprova.",
            message,
        )
    }

    @Test
    fun `messaggio esplicito di non-admin resta un errore di autorizzazione`() {
        val message = mapSeasonHistoryError(
            "Solo un amministratore puo archiviare la stagione",
            "archiviare la stagione",
        )

        assertEquals("Solo un amministratore può archiviare la stagione", message)
    }

    @Test
    fun `funzione RPC assente indica server non aggiornato`() {
        val message = mapSeasonHistoryError(
            "Could not find the function public.archive_season(p_league, p_season, p_name, p_standings) in the schema cache",
            "archiviare la stagione",
        )

        assertEquals(
            "Il server non è ancora aggiornato. Riprova più tardi o contatta l'amministratore.",
            message,
        )
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
    fun `permission denied di postgres non viene presentato come errore admin`() {
        val message = mapSeasonHistoryError(
            "permission denied for table season_snapshots",
            "archiviare la stagione",
        )

        assertEquals(
            "Permessi insufficienti o versione dell'app non aggiornata. Aggiorna l'app e riprova.",
            message,
        )
    }

    @Test
    fun `valore null nei campi obbligatori diventa dati non validi`() {
        val message = mapSeasonHistoryError(
            "null value in column \"season_name\" violates not-null constraint",
            "archiviare la stagione",
        )

        assertEquals("Dati della stagione non validi", message)
    }

    @Test
    fun `errore RPC stagione di altra lega resta comprensibile`() {
        assertEquals(
            "La stagione non appartiene a questa lega",
            mapSeasonHistoryError(
                "La stagione non appartiene a questa lega",
                "archiviare la stagione",
            ),
        )
    }

    @Test
    fun `errore RPC stagione non attiva resta comprensibile`() {
        assertEquals(
            "La stagione non è attiva e non può essere archiviata",
            mapSeasonHistoryError(
                "La stagione non è attiva e non può essere archiviata",
                "archiviare la stagione",
            ),
        )
    }

    @Test
    fun `errore RPC classifica non valida resta comprensibile`() {
        assertEquals(
            "La classifica archiviata non è valida",
            mapSeasonHistoryError(
                "La classifica archiviata non è valida",
                "archiviare la stagione",
            ),
        )
    }
}
