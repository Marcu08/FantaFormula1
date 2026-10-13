# Decisioni architetturali

1. **Pronostici, non fantarosa** (scelta utente): niente rose/budget/aste; ogni giocatore punta su esiti gara.
2. **Supabase come unica fonte di verità**: nessun server proprio; Row Level Security = autorizzazione lato DB (solo membri leggono, solo admin scrivono risultati/calendario, pronostici solo entro deadline via `now() < deadline`).
3. **Motore punteggio client-side in Kotlin** (`core/domain/ScoringEngine`): l'unica fonte di verità del regolamento, testato con unit test JVM; punteggi ricalcolati a ogni lettura (leghe piccole ⇒ OK).
4. **`rules` e `race_results` come JSONB**: formato definito dalle classi Kotlin serializzabili (`Rules`, `RaceResultData`), che restano la sorgente di verità.
5. **DI manuale (`AppContainer`)**: niente Hilt/KSP per ridurre incompatibilità versioni.
6. **JDK 21 pinned**: AGP 8.x richiede JDK 21; JDK 27 di sistema incompatibile.
7. **Credenziali Supabase**: default da `local.properties`/BuildConfig (chiavi `SUPABASE_URL`/`SUPABASE_ANON_KEY`, con fallback `supabase.url`/`supabase.anonKey`), override a runtime via schermata Configurazione (DataStore) — niente rebuild per cambiare chiavi. Solo URL `https://` sono accettati; `http://` viene rifiutato sia in validazione che in `BuildConfig`.
8. **Ordine tie-break classifica**: totalPoints → raceWins (configurabile con `TieBreaker`).
9. **Deadline pronostici**: enforcement doppio, client-side + RLS.

## Scelte della revisione bug (fix Fase 1)

10. **I punti totali dominano sempre** (B1): lo spareggio configurato (`TieBreaker`) sblocca solo una parità reale di totali, mai supera chi ha totalizzato di più. Ordine: totalPoints → spareggio scelto → raceWins → bestRacePoints → nome (deterministico).
11. **raceId dei pronostici = colonna `race_id`** (B4): il `raceId` nel JSON è scritto dal client, non è affidabile. I repository forzano `copy(raceId = row.raceId)` e il motore deduplica a un pronostico per coppia (utente, gara).
12. **`dnfMultiplier` deprecato e ignorato** (B11): resta nel JSON per compatibilità, tolto dall'editor, da `adjust()` e dalla validazione. Un pilota DNF non ha posizione e non classifica: non serve più scontare i punti.
13. **Pronostici parziali ammessi** (B15, scelta utente): i tipi attivi non pronosticati non generano errore e valgono 0. Resta il divieto di pronosticare un tipo non attivo.
14. **Catalogo 2026** (B16): 22 piloti (incl. HAD/BOT/PER; Audi e Cadillac), `MAX_DRIVERS = 22` usato anche come range di validazione. TSU resta in lista legacy (`byId`) per i dati salvati, non è titolare.
15. **Punti over/under = `effectivePoints`** (B3): usa `pointsScored` se inserito dall'admin, altrimenti la tabella F1 ufficiale (25/18/15/12/10/8/6/4/2/1), 0 se non classificato.
16. **Posizioni contigue da 1** (B12): la classifica dev'essere 1..n senza buchi e col primo posto; stessa regola per lo sprint. Evita errori di digitazione silenziosi.
17. **`Json` condiviso `AppJson` con `coerceInputValues`** (B22): enum/valori sconosciuti cadono sui default invece di scartare l'intera lega o i dati. Le regole malformate degradano a `Rules()`.
18. **Classifica dai membri** (B6): righe = membri ∪ chiavi pronostici; chi non gioca resta visibile a 0; ex-membro senza nome mostra "Ex giocatore".
19. **`Routes.RACE` rimossa** (richiesta utente): ogni gara apre direttamente risultati o pronostico.

## Integrazione dati F1 (Fasi B-C)

20. **Normalizzazione risultati API in `F1Mapper`** (Fase C): i piloti non nel catalogo vengono scartati e le posizioni dei classificati vengono rinormalizzate contigue 1..n sull'ordine di arrivo Ergast; la classificazione usa `positionText` numerico (fallback sullo stato testuale). Griglia solo da qualifica se presente, altrimenti da `result.grid` (mai mescolate; grid "0" = partenza dai box, scartata). Sprint rinormalizzato uguale. Ogni esito API passa `RaceResultValidation.validate` prima del salvataggio: se non conforme la gara viene saltata (si ritenta alla sync successiva).
21. **`F1SyncStateHolder` unico punto di sync**: UI e `F1SyncWorker` usano lo stesso holder; `Mutex.tryLock` (atomico, non un flag su StateFlow) impedisce sync concorrenti e le richieste in overlap restituiscono null (ignorate, non accodate). I timestamp "Ultimo aggiornamento" (`F1SyncPrefs`, DataStore) vengono registrati solo sul successo.
22. **Rate-limit Jolpica**: `F1ApiClient` con timeout 20s/10s, retry automatico su 5xx e 429 (`exponentialDelay`), throttle con Mutex e intervallo minimo 300ms tra richieste (tier gratuito ~4 req/s). Configurazione Json condivisa `F1Json` (client + test).
23. **Identita' visiva per gara** (`ui/components/RaceIdentity.kt`): bandiera emoji + gradiente tematico derivati dal nome del GP ("Miami Grand Prix" → rosa/teal), con fallback sul paese e infine palette deterministica per round (🏁). Usata in RaceCard, DeadlineBanner e RaceHeader cosi' la stessa gara ha lo stesso aspetto ovunque. Nessuna risorsa grafica aggiunta: solo colori e emoji di sistema.

