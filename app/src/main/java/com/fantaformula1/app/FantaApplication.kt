package com.fantaformula1.app

import android.app.Application
import com.fantaformula1.app.core.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Application: possiede il [AppContainer] condiviso per tutta la sessione. */
class FantaApplication : Application() {

    lateinit var container: AppContainer
        private set

    /** Scope dell'app per il lavoro in background non legato a una schermata. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, applicationScope)

        // Applica la configurazione Supabase salvata e ripristina la sessione.
        // Se mancano URL/chiavi l'app resta utilizzabile: mostra la schermata
        // di configurazione invece di andare in crash.
        applicationScope.launch {
            runCatching { container.initialize() }
        }
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
