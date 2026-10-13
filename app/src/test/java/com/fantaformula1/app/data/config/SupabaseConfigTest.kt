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

    // -- Validatore URL rafforzato (isValidSupabaseUrl) -----------------------

    @Test
    fun `url supabase valido e accettato`() {
        assertTrue(isValidSupabaseUrl("https://abcdefgh.supabase.co"))
        assertTrue(isValidSupabaseUrl("  https://abcdefgh.supabase.co  ")) // trim
    }

    @Test
    fun `url senza schema o con schema errato e rifiutato`() {
        assertFalse(isValidSupabaseUrl("abcdefgh.supabase.co"))
        assertFalse(isValidSupabaseUrl("http://abcdefgh.supabase.co"))
        assertFalse(isValidSupabaseUrl("ftp://abcdefgh.supabase.co"))
        assertFalse(isValidSupabaseUrl("javascript:alert(1)"))
    }

    @Test
    fun `host senza punto e rifiutato`() {
        assertFalse(isValidSupabaseUrl("https://localhost"))
        assertFalse(isValidSupabaseUrl("https://example"))
    }

    @Test
    fun `url con credenziali userinfo e rifiutato`() {
        assertFalse(isValidSupabaseUrl("https://user:secret@example.supabase.co"))
    }

    @Test
    fun `url con query o fragment e rifiutato`() {
        assertFalse(isValidSupabaseUrl("https://example.supabase.co?api=key"))
        assertFalse(isValidSupabaseUrl("https://example.supabase.co#fragment"))
    }

    @Test
    fun `url malformati o assurdi sono rifiutati`() {
        assertFalse(isValidSupabaseUrl(""))
        assertFalse(isValidSupabaseUrl("   "))
        assertFalse(isValidSupabaseUrl("https://"))
        assertFalse(isValidSupabaseUrl("https://example.supabase.co/" + "a".repeat(200)))
    }
}
