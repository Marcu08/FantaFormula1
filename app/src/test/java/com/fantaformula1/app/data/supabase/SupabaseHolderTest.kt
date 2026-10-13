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
}
