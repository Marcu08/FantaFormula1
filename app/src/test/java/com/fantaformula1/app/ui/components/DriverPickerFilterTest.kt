package com.fantaformula1.app.ui.components

import com.fantaformula1.app.core.domain.DriversCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Filtro del selettore piloti: nome, cognome, sigla, scuderia e combinazioni. */
class DriverPickerFilterTest {

    @Test
    fun `query vuota e filtro null restituiscono tutto il catalogo`() {
        assertEquals(DriversCatalog.all, filterDrivers("", null))
        assertEquals(DriversCatalog.all, filterDrivers("   ", null))
    }

    @Test
    fun `ricerca per nome completo`() {
        assertEquals(listOf("VER"), filterDrivers("Max Verstappen", null).map { it.id })
    }

    @Test
    fun `ricerca per cognome parziale insensibile al caso`() {
        assertEquals(listOf("VER"), filterDrivers("verst", null).map { it.id })
    }

    @Test
    fun `ricerca per sigla`() {
        val results = filterDrivers("nor", null)
        assertEquals(1, results.size)
        assertEquals("NOR", results.single().id)
    }

    @Test
    fun `filtro scuderia restituisce solo i suoi titolari`() {
        assertEquals(listOf("LEC", "HAM"), filterDrivers("", "Ferrari").map { it.id })
        assertEquals(listOf("VER", "HAD"), filterDrivers("", "Red Bull Racing").map { it.id })
    }

    @Test
    fun `nome e scuderia si combinano in AND`() {
        assertEquals(listOf("NOR"), filterDrivers("nor", "McLaren").map { it.id })
        assertTrue(filterDrivers("nor", "Ferrari").isEmpty())
    }

    @Test
    fun `nessun risultato per query inventata`() {
        assertTrue(filterDrivers("pilotoinesistente", null).isEmpty())
    }
}