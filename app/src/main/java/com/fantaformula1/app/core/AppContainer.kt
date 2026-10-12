package com.fantaformula1.app.core

import android.content.Context

/**
 * Contenitore delle dipendenze dell'app — DI manuale leggera, senza Hilt/KSP.
 * Scelta deliberata: meno superficie di rottura tra versioni e build più affidabile.
 *
 * I repository (Supabase) vengono registrati qui in fase di inizializzazione.
 */
class AppContainer(context: Context) {

    /** Contesto applicazione, unico usato per DataStore e client di rete. */
    val applicationContext: Context = context.applicationContext
}
