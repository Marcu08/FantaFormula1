package com.fantaformula1.app.data.supabase

import com.fantaformula1.app.data.config.SupabaseConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SupabaseHolderTest {

    @Test
    fun `configure con configurazione non valida restituisce false e azzera il client`() = runTest {
        val holder = SupabaseHolder()
        val result = holder.configure(SupabaseConfig("", ""))
        assertFalse(result)
        assertNull(holder.get())
        assertNull(holder.currentConfigOrNull())
    }

    @Test
    fun `configure con URL http restituisce false`() = runTest {
        val holder = SupabaseHolder()
        val result = holder.configure(SupabaseConfig("http://example.supabase.co", "anon-key"))
        assertFalse(result)
        assertNull(holder.get())
    }

    @Test
    fun `clientState resta null quando la configurazione non e' valida`() = runTest {
        val holder = SupabaseHolder()
        assertNull(holder.clientState.value)

        holder.configure(SupabaseConfig("", ""))
        assertNull(holder.clientState.value)

        holder.configure(SupabaseConfig("http://example.supabase.co", "anon-key"))
        assertNull(holder.clientState.value)
    }

    @Test
    fun `testConnection con configurazione non valida ritorna false senza toccare lo stato`() = runTest {
        // N10: il test connessione non deve MAI sostituire il client globale.
        // Con config non valida non viene neppure creato il client temporaneo.
        val holder = SupabaseHolder()

        assertFalse(holder.testConnection(SupabaseConfig("", "")))
        assertFalse(holder.testConnection(SupabaseConfig("http://example.supabase.co", "k")))

        assertNull(holder.get())
        assertNull(holder.currentConfigOrNull())
        assertNull(holder.clientState.value)
    }
}
