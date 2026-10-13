package com.fantaformula1.app.data.repository

import com.fantaformula1.app.data.repository.PredictionWriteMessages.DEADLINE
import com.fantaformula1.app.data.repository.PredictionWriteMessages.PERMISSIONS
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mapping degli errori di scrittura del pronostico: un errore RLS diventa
 * "deadline passata" SOLO se il server conferma la gara chiusa; altrimenti e'
 * un problema di permessi/sessione. Messaggi diversi non vengono travestiti.
 */
class PredictionWriteErrorTest {

    @Test
    fun `errore RLS con gara chiusa sul server e' la deadline`() {
        assertEquals(
            DEADLINE,
            predictionWriteErrorMessage("new row violates row-level security policy", deadlineOpen = false),
        )
    }

    @Test
    fun `errore RLS con gara ancora aperta e' un problema di permessi`() {
        // Sessione scaduta, non piu' membro della lega, ecc.: NON e' la deadline.
        assertEquals(
            PERMISSIONS,
            predictionWriteErrorMessage("permission denied for table predictions", deadlineOpen = true),
        )
    }

    @Test
    fun `errore RLS con verita' server non determinabile resta permessi`() {
        assertEquals(
            PERMISSIONS,
            predictionWriteErrorMessage("row-level security", deadlineOpen = null),
        )
    }

    @Test
    fun `messaggio con parola deadline senza errore RLS parla di scadenza`() {
        assertEquals(
            DEADLINE,
            predictionWriteErrorMessage("La deadline è passata", deadlineOpen = null),
        )
    }

    @Test
    fun `altri errori passano attraverso senza travestimento`() {
        val raw = "duplicate key value violates unique constraint"
        assertEquals(raw, predictionWriteErrorMessage(raw, deadlineOpen = null))
    }

    @Test
    fun `errore senza messaggio ha un fallback generico`() {
        assertEquals(
            "Si è verificato un errore",
            predictionWriteErrorMessage(raw = null, deadlineOpen = null),
        )
    }

    @Test
    fun `il matching non dipende dal case`() {
        assertEquals(
            DEADLINE,
            predictionWriteErrorMessage("New row violates Row-Level Security Policy", deadlineOpen = false),
        )
    }
}