package com.fantaformula1.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R5: gli errori di scrittura sulle gare vengono distinti invece di finire
 * tutti in "Dati della gara non validi".
 */
class RaceWriteErrorTest {

    @Test
    fun `permessi rls`() {
        val message = mapRaceWriteError(
            "new row violates row-level security policy for table \"races\"",
            "creare la gara",
        )

        assertEquals("Solo un amministratore può creare la gara", message)
    }

    @Test
    fun `permission denied classico di postgres`() {
        val message = mapRaceWriteError(
            "permission denied for table races",
            "eliminare la gara",
        )

        assertEquals("Solo un amministratore può eliminare la gara", message)
    }

    @Test
    fun `round duplicato`() {
        val message = mapRaceWriteError(
            "duplicate key value violates unique constraint \"races_season_id_round_key\"",
            "creare la gara",
        )

        assertEquals("Esiste già una gara con lo stesso numero di round", message)
    }

    @Test
    fun `gara con dati collegati via foreign key`() {
        // 0011: predictions_race_league_fk / race_results_race_league_fk.
        val message = mapRaceWriteError(
            "insert or update on table \"predictions\" violates foreign key constraint \"predictions_race_league_fk\"",
            "eliminare la gara",
        )

        assertEquals("La gara ha pronostici o risultati collegati: non puoi eliminare la gara", message)
    }

    @Test
    fun `ultimo amministratore da trigger`() {
        val message = mapRaceWriteError(
            "Non puoi rimuovere l'ultimo amministratore",
            "rimuovere il membro",
        )

        assertEquals("Non puoi rimuovere l'ultimo amministratore: promuovi prima un altro membro", message)
    }

    @Test
    fun `deadline obbligatoria da trigger 0011`() {
        val message = mapRaceWriteError(
            "La deadline e' obbligatoria per le nuove gare",
            "creare la gara",
        )

        assertEquals("La deadline è obbligatoria per le nuove gare", message)
    }

    @Test
    fun `check constraint rimane generico`() {
        val message = mapRaceWriteError(
            "new row for relation \"races\" violates check constraint \"races_round_check\"",
            "aggiornare la gara",
        )

        assertEquals("Dati della gara non validi", message)
    }

    @Test
    fun `errore sconosciuto passa invariato con il fallback di azione`() {
        val message = mapRaceWriteError(null, "creare la gara")

        assertEquals("Impossibile creare la gara", message)
        assertTrue(message.contains("creare la gara"))
    }
}
