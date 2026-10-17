package com.fantaformula1.app.core

import android.content.Context
import com.fantaformula1.app.data.config.SupabaseConfigStore
import com.fantaformula1.app.data.repository.AuthRepository
import com.fantaformula1.app.data.repository.LeagueDataRepository
import com.fantaformula1.app.data.repository.LeagueRepository
import com.fantaformula1.app.data.repository.PredictionRepository
import com.fantaformula1.app.data.repository.RaceRepository
import com.fantaformula1.app.data.repository.SupabaseAuthRepository
import com.fantaformula1.app.data.repository.SupabaseLeagueDataRepository
import com.fantaformula1.app.data.repository.SupabaseLeagueRepository
import com.fantaformula1.app.data.repository.SupabasePredictionRepository
import com.fantaformula1.app.data.repository.SupabaseRaceRepository
import com.fantaformula1.app.data.supabase.SupabaseHolder
import com.fantaformula1.app.data.sync.F1SyncPrefs
import com.fantaformula1.app.data.sync.F1SyncRepository
import com.fantaformula1.app.data.sync.F1SyncStateHolder
import com.fantaformula1.app.data.sync.SeasonResultsStore
import com.fantaformula1.app.data.f1api.F1ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Contenitore delle dipendenze dell'app — DI manuale leggera, senza Hilt/KSP.
 * Scelta deliberata: meno superficie di rottura tra versioni e build più affidabile.
 *
 * Costruito una sola volta in [com.fantaformula1.app.FantaApplication].
 */
class AppContainer(
    context: Context,
    private val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    /** Contesto applicazione, unico usato per DataStore e client di rete. */
    val applicationContext: Context = context.applicationContext

    /** URL/anon key Supabase, overrideabili a runtime. */
    val configStore = SupabaseConfigStore(applicationContext)

    /** Detiene il client Supabase corrente. */
    val supabaseHolder = SupabaseHolder()

    /** Client unico verso Jolpica/Ergast, condiviso da sync e cache stagione. */
    val f1ApiClient = F1ApiClient()

    val authRepository: AuthRepository = SupabaseAuthRepository(supabaseHolder)

    val leagueRepository: LeagueRepository = SupabaseLeagueRepository(supabaseHolder, authRepository)

    val raceRepository: RaceRepository = SupabaseRaceRepository(supabaseHolder, authRepository)

    val predictionRepository: PredictionRepository =
        SupabasePredictionRepository(supabaseHolder, authRepository)

    val leagueDataRepository: LeagueDataRepository =
        SupabaseLeagueDataRepository(supabaseHolder)

    /** Sincronizzazione calendario e risultati dal mondiale F1 reale. */
    val f1SyncRepository: F1SyncRepository = F1SyncRepository(
        f1ApiClient = f1ApiClient,
        raceRepository = raceRepository,
    )

    /** Cache dei risultati reali della stagione, informieren per le statistiche. */
    val seasonResultsStore: SeasonResultsStore = SeasonResultsStore(
        context = applicationContext,
        f1ApiClient = f1ApiClient,
    )

    /** Orario dell'ultima sincronizzazione riuscita (indicatore in UI). */
    val f1SyncPrefs = F1SyncPrefs(applicationContext)

    /**
     * Stato di sincronizzazione condiviso da UI e WorkManager: impedisce
     * sincronizzazioni concorrenti e aggiorna "Ultimo aggiornamento".
     */
    val f1SyncStateHolder: F1SyncStateHolder =
        F1SyncStateHolder(f1SyncRepository, f1SyncPrefs)

    /**
     * Applica la configurazione Supabase e carica l'eventuale sessione salvata.
     *
     * B25: idempotente. Poteva essere chiamata due volte all'avvio (Application
     * + primo Composable), e ogni chiamata creava un nuovo client chiudendo
     * quello precedente. Ora la seconda chiamata e' un no-op finche' la
     * configurazione non cambia.
     *
     * B8: aspetta l'inizializzazione del plugin Auth prima di leggere l'utente.
     */
    suspend fun initialize() {
        val config = configStore.current()
        val changed = config != supabaseHolder.currentConfigOrNull()
        if (changed) {
            supabaseHolder.configure(config)
        }
        // Attende il ripristino della sessione: senza questo, subito dopo la
        // creazione del client currentUserOrNull() puo' dare null a causa di
        // una condizione di gara e l'utente veniva mostrato come disconnesso.
        supabaseHolder.awaitAuthInitialization()
        authRepository.refresh()
        // B8: osserva login/logout/scadenza sessione per tutta la vita dell'app.
        authRepository.startSessionObserver(applicationScope)
        // Carica gli orari dell'ultima sincronizzazione F1 per l'indicatore in UI.
        f1SyncStateHolder.refresh()
    }
}
