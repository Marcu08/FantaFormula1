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
24. **Validazione pronostici server-side** (migration `0004_prediction_validation.sql`): il trigger `check_prediction_content` (security invoker, ricreato idempotente) replica `PredictionValidation` nel DB: tipi conosciuti, un tipo per pronostico, tipo attivo in `enabledTypes` (assente = tutti, non-array = degrada come client B22), numero piloti per tipo (1/2/3/0), driverId non vuoti e distinti, SPRINT_WINNER solo su gare sprint (colonna is_sprint), position/fasce interi 1..22 (MAX_DRIVERS 2026), DNF booleanValue, over/under threshold >= 0 + over. JSON null = campo assente (AppJson ha encodeDefaults=true). NON imposti: appartenenza piloti alla gara (catalogo solo client), submittedAtEpochMs vs clock server (skew legittimo), content.raceId (verita' = colonna race_id, B4), limite superiore soglia. Limiti tecnici: max 32 voci (client usa <= 12). Contratto client↔server testato in `PredictionContentContractTest` (valida il JSON prodotto da AppJson contro la stessa logica del SQL). Test SQL di integrazione predisposti in `supabase/tests/0004_prediction_validation_tests.sql` (self-rollback, richiedono progetto di test con 2 utenti).

## Sincronizzazione F1 (prompt v2, Fase 1)

25. **Mapping piloti via `code` FIA** (N1, verificato su risposta reale `f1/2026/drivers.json`): Jolpica usa il cognome (`norris`, `hamilton`, `sainz`, `hulkenberg`) con eccezioni nome+cognome (`max_verstappen`, `arvid_lindblad`); i vecchi id stile `lando_norris` NON esistono. `F1DriverMapping.toAppId(F1Driver)` usa per primo il campo `code` (VER, NOR... = id dell'app) con fallback su una tabella di `driverId` noti. Le fixture dei test sono le risposte reali salvate in `app/src/test/resources/jolpica_*.json`.
26. **Pilota fuori catalogo blocca il risultato** (N2): `F1Mapper.toRaceResultData` restituisce `RaceMapping.UnknownDriver` invece di scartare il pilota e rinormalizzare le posizioni (una riserva 5ª non fa più salire tutti gli altri). La sync saltala con errore esplicito "Pilota sconosciuto: X" e la gara resta in attesa.
27. **Safety car e pit stop totali nullable** (N3): `RaceResultData.safetyCars` e il nuovo campo serializzato `totalPitStops` sono nullable; null = "in attesa di conferma admin" e i tipi over/under collegati valgono 0 punti (`ScoringEngine.pendingDataNotes` genera l'avviso). Compatibilità: i dati vecchi hanno sempre `safetyCars` con un numero; per `totalPitStops` assente si usa la somma dei pit stop dei piloti (se almeno uno compilato). Il form admin evidenzia i campi mancanti come "da completare".
28. **Deadline = primo evento utile del weekend** (N4): SprintQualifying → Sprint → Qualifying → gara. Nei weekend sprint la qualifica è DOPO lo sprint: usare la qualifica permetterebbe di pronosticare lo sprint sapendone l'esito.
29. **Sync solo per admin** (N5): worker, pulsanti di LeagueScreen/CalendarScreen e `F1SyncScheduler.schedule` sono riservati agli admin; il worker riverifica `isAdmin` prima di ogni esecuzione e si ferma con `Result.failure()` (niente retry all'infinito su errori RLS). Le leghe schedulate su questo dispositivo sono ricordate su DataStore (`F1SyncPrefs`) per `cancelAll` al logout. "Uscita dalla lega" interpretata come abbandono definitivo (`leaveLeague`), non la semplice navigazione indietro: altrimenti la sync periodica smetterebbe di funzionare appena si lascia la schermata.
30. **Stagione = anno corrente** (N6): nessun `2026` fisso; `F1ApiClient.currentSeason()` e `F1SyncRepository.season()` usano `LocalDate.now(UTC)`. `syncResults` interroga l'API solo per gare con deadline superata e senza risultato (niente richieste per le gare future); `SyncResult.Success.pastSkipped` comunica quante gare del calendario sono state saltate perché già disputate.
31. **Migrazione "0003" del prompt = `0011_fixes.sql`**: nel repo esistono già le migrazioni 0001–0010, quindi le modifiche DB del prompt vanno nel numero successivo, sempre idempotente e senza toccare i file esistenti. Il testo completo del prompt v2 è salvato in `.opencode/knowledge/prompt-v2.md`.

## Scelte della Fase 2 (prompt v2: N8, N9, R4, R8)

32. **Deadline obbligatoria anche in modifica** (R4): il trigger `before insert` di `0011_fixes.sql` vale solo per le nuove gare (come da prompt, per non rompere le righe esistenti), ma `validateRaceForm` del form richiede la deadline sia in creazione sia in modifica: l'alternativa (consentire di svuotarla in modifica) ricreerebbe il bug della gara aperta per sempre. La validazione del form è una funzione pura testata (`RaceFormValidationTest`).
33. **Ultimo admin: controllo saltato se lega o utente non esistono più** (N9): `check_last_admin_on_delete` torna `old` se la lega è già stata cancellata (cascata da `leagues`) **oppure** se il profilo del membro non esiste più (cascata da `profiles`/`auth.users`). I due controlli coprono l'ordinamento imprevedibile delle azioni a cascata; le cancellazioni dirette di un ultimo admin restano bloccate.
34. **`remove_league_member` riusa lo stesso messaggio del trigger** (`Non puoi rimuovere l'ultimo amministratore`): così la UI (R3, fase 3) può riconoscerlo sia che la chiamata passi dalla RPC sia che il trigger scatti su una DELETE diretta. L'RPC non può essere chiamata da `anon` (revoke esplicito in 0011).

## Piano: sincronizzazione lato server (N7)

Oggi i risultati arrivano solo se il telefono di un admin esegue il worker (la RPC `save_race_result` richiede il ruolo admin). Per renderla indipendente dai dispositivi:

1. **Edge Function Supabase** `sync-f1-results`: funzione Deno che:
   - legge le gare con `status != 'completed'` e deadline passata di tutte le leghe (query con service role, bypass RLS);
   - chiama Jolpica (`f1/{anno}/{round}/results.json`, qualifying, sprint, pitstops) con la stessa logica di `F1Mapper` (portare il mapping in un modulo condiviso o duplicarlo in TS — 200 righe, da tenere allineate con i test JVM);
   - valida con le invariance (posizioni contigue 1..n, piloti tutti nel catalogo: id FIA = codici, quindi niente tabella di traduzione lato server);
   - salva con `service_role` dentro `race_results` (bypassa RLS) e porta `races.status = 'completed'`.
2. **`pg_cron`**: `cron.schedule('sync-results', '*/30 * * * *', $$select net.http_post(...)$$)` chiama la Edge Function ogni 30 minuti; serve l'extension `pg_cron` + `pg_net` nel progetto.
3. **Punti da cambiare nel client**:
   - `F1SyncWorker` diventa solo un fallback manuale (o viene rimosso); i pulsanti di sync restano come "forza aggiornamento" ma non servono per i risultati;
   - la schermata risultati mostra l'avviso "In attesa di conferma admin" solo per i dati che nessuna fonte fornisce (safety car): l'import OpenF1 assistito resta il percorso di conferma;
   - RLS di `race_results`: la scrittura client può restare admin-only, la Edge Function scrive con service role;
   - `F1SyncPrefs` "Ultimo aggiornamento" va spostato su una tabella `sync_log` (lettura pubblica ai membri) così il telefono dell'admin non è più l'unica fonte.
4. **Sicurezza**: la Edge Function non espone la service role (server-side only); rate-limit Jolpica gestito dentro la funzione; retry con backoff su 429/5xx.

Nel frattempo (fatto in questa revisione): UI con "Ultimo aggiornamento risultati" in evidenza (`SyncBar`) e banner di allerta nelle gare finite da >12h senza risultato.

## Scelte della Fase 3 (prompt v2: N10–N12, R1–R3, R5–R7)

35. **"Prova connessione" con client temporaneo** (N10): `SupabaseHolder.testConnection(config)` crea un client usa-e-getta (solo Postgrest, senza Auth/Realtime), prova una `select` su `leagues` e lo chiude in `finally`. Il client globale non viene toccato, quindi un test fallito non lascia l'app su una configurazione sbagliata e non serve più "salva per provare".
36. **Errori auth dal codice strutturato** (N11): `mapAuthErrorMessage` legge `AuthRestException.errorCode` (enum `AuthErrorCode` di supabase-kt) invece di riflettere sullo status HTTP. In particolare `BadJwt`/`NoAuthorization` (anon key errata) ora danno "Configurazione Supabase non valida" e non più "Email o password non corretti". La parte pura è testata su JVM (`AuthErrorMappingTest`).
37. **Observer sessione idempotente e legato al client** (N12): `startSessionObserver` annulla il proprio job precedente (niente collector doppi) e osserva `SupabaseHolder.clientState` (StateFlow): al cambio di configurazione il collector sul vecchio client viene annullato e ne parte uno nuovo. Niente `flatMapLatest` (richiederebbe un refactor a channelFlow): il job interno annullato ha lo stesso effetto con meno superficie.
38. **`RaceIdentity` a parola intera** (N12): regex precompilate `(?<![a-z])chiave(?![a-z])`; i trattini (Emilia-Romagna) restano match perché non sono lettere, mentre "uk" non intercetta più "Turkey".
39. **Toolchain Gradle al posto di `org.gradle.java.home`** (R1): `gradle.properties` è portabile (nessun percorso locale); `kotlin { jvmToolchain(21) }` fa selezionare o scaricare la JDK 21 a Gradle. `scripts/setup-android-env.ps1` installa ancora la JDK 21 (serve a Gradle come candidato/toolchain locale) ma non la scrive più nel file di proprietà.
40. **`allowBackup="false"`** (R2, scelta più semplice): niente `dataExtractionRules` (servirebbero file XML extra per un'app non commerciale); la sessione Supabase (DataStore) esce dai backup di sistema. `Realtime` resta installato ma inutilizzato: la decisione "usarlo per la classifica live o rimuoverlo" è rimandata alla Fase 5 (F4) per non aggiungere assunzioni ora.
41. **Azioni ruoli membri via RPC** (R3): `setMemberRole` chiama `set_league_member_role` (0001) e `removeMember` chiama `remove_league_member` (0011); la UI mostra conferma per ogni azione e nasconde "Retrocedi"/"Abbandona" all'ultimo admin sostituendoli con la spiegazione. I messaggi DB in italiano ("Non puoi rimuovere l'ultimo amministratore") vengono riproposti all'utente così com'erano, già veri e propri in italiano.
42. **Errori scrittura gare distinti** (R5): `mapRaceWriteError` è una funzione pura testata (`RaceWriteErrorTest`) con rami separati per permessi RLS, round duplicato, foreign key (gara con pronostici/risultati collegati, vincoli `_fk` aggiunti in 0011), ultimo admin e deadline obbligatoria; solo i check constraint generici restano "Dati della gara non validi".
43. **Avviso pronostici parziali non bloccante** (R6): i parziali restano ammessi (decisione #13); `missingTypesMessage` (funzione pura testata) elenca i tipi mancanti e l'UI li mostra in colore avviso sopra la lista errori, azzerandoli appena si tocca una bozza.
44. **Test R7 sul serializer reale del client**: `RaceUpdateDto` è `internal` per essere testato; il test usa `KotlinXSerializer(Json.Default)` (lo stesso che supabase-kt usa come `defaultSerializer`) e verifica che la chiave `deadline` sia presente anche con valore null, confermando B27 con il Json realmente utilizzato in runtime.

