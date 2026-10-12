package com.fantaformula1.app.core

import android.content.Context
import com.fantaformula1.app.data.config.SupabaseConfigStore
import com.fantaformula1.app.data.repository.AuthRepository
import com.fantaformula1.app.data.repository.LeagueRepository
import com.fantaformula1.app.data.repository.RaceRepository
import com.fantaformula1.app.data.repository.SupabaseAuthRepository
import com.fantaformula1.app.data.repository.SupabaseLeagueRepository
import com.fantaformula1.app.data.repository.SupabaseRaceRepository
import com.fantaformula1.app.data.supabase.SupabaseHolder

/**
 * Contenitore delle dipendenze dell'app — DI manuale leggera, senza Hilt/KSP.
 * Scelta deliberata: meno superficie di rottura tra versioni e build più affidabile.
 *
 * Costruito una sola volta in [com.fantaformula1.app.FantaApplication].
 */
class AppContainer(context: Context) {

    /** Contesto applicazione, unico usato per DataStore e client di rete. */
    val applicationContext: Context = context.applicationContext

    /** URL/anon key Supabase, overrideabili a runtime. */
    val configStore = SupabaseConfigStore(applicationContext)

    /** Detiene il client Supabase corrente. */
    val supabaseHolder = SupabaseHolder()

    val authRepository: AuthRepository = SupabaseAuthRepository(supabaseHolder)

    val leagueRepository: LeagueRepository = SupabaseLeagueRepository(supabaseHolder, authRepository)

    val raceRepository: RaceRepository = SupabaseRaceRepository(supabaseHolder, authRepository)

    /**
     * Applica la configurazione Supabase e carica l'eventuale sessione salvata.
     * Da chiamare una sola volta all'avvio dell'app.
     */
    suspend fun initialize() {
        val config = configStore.current()
        supabaseHolder.configure(config)
        authRepository.refresh()
    }
}
