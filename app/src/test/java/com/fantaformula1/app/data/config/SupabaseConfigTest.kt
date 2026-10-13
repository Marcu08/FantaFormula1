package com.fantaformula1.app.data.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseConfigTest {

    @Test
    fun `isConfigured richiede URL https e chiave non vuota`() {
        assertTrue(SupabaseConfig("https://example.supabase.co", "anon-key").isConfigured)
    }

    @Test
    fun `isConfigured rifiuta URL http`() {
        assertFalse(SupabaseConfig("http://example.supabase.co", "anon-key").isConfigured)
    }

    @Test
    fun `isConfigured rifiota URL non valido`() {
        assertFalse(SupabaseConfig("ftp://example.supabase.co", "anon-key").isConfigured)
        assertFalse(SupabaseConfig("example.supabase.co", "anon-key").isConfigured)
    }

    @Test
    fun `isConfigured rifiuta chiave vuota`() {
        assertFalse(SupabaseConfig("https://example.supabase.co", "").isConfigured)
    }

    @Test
    fun `isConfigured rifiuta URL vuoto`() {
        assertFalse(SupabaseConfig("", "anon-key").isConfigured)
    }
}
