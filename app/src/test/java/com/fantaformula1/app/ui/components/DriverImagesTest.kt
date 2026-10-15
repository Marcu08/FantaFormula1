package com.fantaformula1.app.ui.components

import com.fantaformula1.app.core.domain.DriversCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mappatura caricature -> identifier stabili del catalogo: i 22 titolari
 * devono avere una risorsa, e ogni risorsa deve appartenere a un pilota
 * presente in catalogo (nessuno scarto, nessun doppione).
 */
class DriverImagesTest {

    @Test
    fun `tutti e soli i titolari del catalogo hanno una caricatura`() {
        val titolari = DriversCatalog.all.map { it.id }.toSet()
        assertEquals(22, titolari.size)
        assertEquals(titolari, DriverImages.byDriverIds.keys)
    }

    @Test
    fun `ogni id risolve una risorsa valida non nulla`() {
        DriversCatalog.all.forEach { driver ->
            assertNotNull("Manca la caricatura di ${driver.id}", DriverImages.resIdOf(driver.id))
        }
    }

    @Test
    fun `il pilota legacy non ha caricatura e usa il fallback`() {
        assertNull(DriverImages.resIdOf("TSU"))
        assertFalse(
            DriversCatalog.legacy.any { DriverImages.resIdOf(it.id) != null },
        )
    }

    @Test
    fun `nessun id inventato fuori dal catalogo`() {
        // La mappa non contiene chiavi che non siano id del catalogo.
        val known = DriversCatalog.all.map { it.id }.toSet() +
            DriversCatalog.legacy.map { it.id }.toSet()
        DriverImages.byDriverIds.keys.forEach { id ->
            assertTrue("Id non in catalogo: $id", id in known)
        }
    }
}