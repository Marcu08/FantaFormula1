package com.fantaformula1.app.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Le chiavi locali non devono mai essere condivise tra account. */
class LocalUserScopeTest {

    @Test
    fun `utenti diversi producono chiavi diverse`() {
        val a = scopedPreferenceName("draft_race-1", "user-a")
        val b = scopedPreferenceName("draft_race-1", "user-b")

        assertEquals("u_user-a_draft_race-1", a)
        assertNotEquals(a, b)
    }

    @Test
    fun `senza utente non si produce una chiave legacy`() {
        assertNull(scopedPreferenceName("draft_race-1", null))
        assertNull(scopedPreferenceName("draft_race-1", "   "))
    }
}
