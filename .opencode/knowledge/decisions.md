# Decisioni architetturali

1. **Pronostici, non fantarosa** (scelta utente): niente rose/budget/aste; ogni giocatore punta su esiti gara.
2. **Supabase come unica fonte di verità**: nessun server proprio; Row Level Security = autorizzazione lato DB (solo membri leggono, solo admin scrivono risultati/calendario, pronostici solo entro deadline via `now() < deadline`).
3. **Motore punteggio client-side in Kotlin** (`core/domain/ScoringEngine`): l'unica fonte di verità del regolamento, testato con unit test JVM; punteggi ricalcolati a ogni lettura (leghe piccole ⇒ OK).
4. **`rules` e `race_results` come JSONB**: formato definito dalle classi Kotlin serializzabili (`Rules`, `RaceResultData`), che restano la sorgente di verità.
5. **DI manuale (`AppContainer`)**: niente Hilt/KSP per ridurre incompatibilità versioni.
6. **JDK 21 pinned**: AGP 8.x richiede JDK 21; JDK 27 di sistema incompatibile.
7. **Credenziali Supabase**: default da `local.properties`/BuildConfig, override a runtime via schermata Configurazione (DataStore) — niente rebuild per cambiare chiavi.
8. **Ordine tie-break classifica**: totalPoints → raceWins (configurabile con `TieBreaker`).
9. **Deadline pronostici**: enforcement doppio, client-side + RLS.
