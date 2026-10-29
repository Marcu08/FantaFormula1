package com.fantaformula1.app.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Una verifica admin fallita (null) non deve cancellare la sync periodica. */
class F1SyncAdminDecisionTest {

    @Test
    fun `solo un no definitivo cancella la sync`() {
        assertTrue(shouldCancelSyncForAdmin(false))
    }

    @Test
    fun `admin confermato non cancella`() {
        assertFalse(shouldCancelSyncForAdmin(true))
    }

    @Test
    fun `verifica fallita non cancella`() {
        assertFalse(shouldCancelSyncForAdmin(null))
    }
}
