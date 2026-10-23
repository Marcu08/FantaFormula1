# Prompt: correzione bug FantaFormula 1

Copia tutto quello che segue (dalla riga "Contesto" in poi) nel tuo agente di coding.

---

## Contesto

Repo: `github.com/Marcu08/fantaformula1` — app Android nativa (Kotlin, Jetpack Compose, Material 3) con backend Supabase (Postgres + RLS). Il motore di punteggio gira lato client (`core/domain/ScoringEngine`). Pacchetto `com.fantaformula1.app`. UI tutta in italiano.

Ho fatto una revisione statica del codice e ho trovato i bug elencati sotto (ID `B1`, `B2`, ...). Correggili tutti.

## Regole di lavoro

1. Lavora per **fasi** (1 → 5). Un commit per fase, messaggio in italiano.
2. Dopo ogni fase esegui `./gradlew testDebugUnitTest lintDebug` e correggi finché è tutto verde.
3. **Non rinominare** i valori degli enum `PredictionType`, `TieBreaker`, `RaceStatus.stored` né le chiavi JSON di `Rules`, `RaceResultData`, `RacePrediction`: i dati esistenti su Supabase devono continuare a funzionare.
4. **Non modificare** `supabase/migrations/0001_init.sql`: ogni modifica al DB va in `supabase/migrations/0002_fixes.sql`, idempotente (`create or replace`, `drop ... if exists`, `add constraint` protetti).
5. Per ogni fix di dominio aggiungi o aggiorna un test JVM. Nessuna nuova dipendenza.
6. Non usare `runCatching` attorno a codice `suspend` senza rilanciare `CancellationException` (crea un helper `runCatchingCancellable` e usalo nei ViewModel/repository).
7. Se una scelta di design è ambigua, scegli l'opzione più semplice, annotala in `.opencode/knowledge/decisions.md` e vai avanti senza chiedermi nulla.

---

## Fase 1 — Dominio e punteggio (`core/domain`)

**B1 — Spareggio usato come criterio primario (critico).**
`ScoringEngine.standingsComparator`: con `RACE_WINS` (default) e `BEST_SINGLE_RACE` si ordina prima per vittorie / miglior gara e solo dopo per punti totali. Un giocatore con 116 punti supera uno con 165. Deve essere: **punti totali decrescenti sempre per primi**, poi lo spareggio scelto (`RACE_WINS` → `raceWins`, `BEST_SINGLE_RACE` → `bestRacePoints`, `TOTAL_POINTS` → nessuno), poi catena deterministica, poi nome. È anche quanto scritto in `decisions.md` (punto 8). Il test in `ScoringEngineTest` che asserisce che con `RACE_WINS` vince alice (116 pt) su carlo (165 pt) codifica il bug: invertilo e aggiungi test su parità reale di punti per ciascun `TieBreaker`.

**B4 — Punteggio calcolato sul `raceId` contenuto nel JSON del pronostico (critico, barare possibile).**
`ScoringEngine.computeRaceScores` usa `prediction.raceId` (scritto dal client, non affidabile) per cercare il risultato. Un giocatore può inviare un pronostico su una gara aperta con `content.raceId` di una gara già conclusa e prendere i punti. In `SupabaseLeagueDataRepository.raceData` e `SupabasePredictionRepository` forza `content.copy(raceId = row.raceId)` (la colonna `race_id`), e fai sì che la classifica conti al massimo un pronostico per coppia (utente, gara). Test: pronostico con `raceId` falsificato non produce punti sulla gara sbagliata.

**B3 — `DRIVER_POINTS_OVER_UNDER` non può mai dare punti.**
`DriverRaceResult.pointsScored` non viene mai valorizzato da `DriverFormState.toDomain()` e il form non ha il campo, quindi è sempre `null` e il tipo vale sempre 0. Fix: (a) aggiungi un campo opzionale "Punti" nel form risultati; (b) in `RaceResultData` aggiungi `effectivePoints(driver)`: se `pointsScored` è null e il pilota è classificato usa la tabella F1 (25,18,15,12,10,8,6,4,2,1), altrimenti 0; (c) usa `effectivePoints` nello scoring così anche i risultati già salvati funzionano. Test dedicati.

**B11 — `dnfMultiplier` non ha mai effetto.**
Un pilota DNF ha sempre `position = null` (validazione + `toDomain`), quindi tutti i rami che chiamano `adjust` con un DNF sono irraggiungibili. Scelta: segna `dnfMultiplier` come deprecato (resta nel JSON per compatibilità, ignorato), toglilo dall'editor regole e correggi il KDoc di `adjust` (che dice che le coincidenze esatte non vengono scontate ma `PODIUM` viene scontato).

**B12 — Risultato valido anche senza P1 o con posizioni non contigue.**
`RaceResultValidation` accetta ad esempio solo 5º e 7º classificati; `winner()` restituisce il 5º e `WINNER`/`PODIUM` pagano sul pilota sbagliato. Richiedi: posizioni dei classificati = `1..n` contigue, P1 presente; griglia senza duplicati; posizioni sprint contigue da 1 quando presenti. Test.

**B16 — Griglia e limite a 20 piloti obsoleti.**
Dal 2026 i piloti sono 22 (Cadillac aggiunta, Sauber → Audi). `DriversCatalog` contiene ancora Tsunoda e manca Hadjar, Bottas, Pérez. Aggiorna il catalogo 2026: Red Bull (VER, HAD), Racing Bulls (LAW, LIN), McLaren (NOR, PIA), Ferrari (LEC, HAM), Mercedes (RUS, ANT), Aston Martin (ALO, STR), Alpine (GAS, COL), Haas (OCO, BEA), Williams (ALB, SAI), Audi (HUL, BOR), Cadillac (BOT, PER). Mantieni una lista `legacy` con TSU così i risultati già salvati mostrano ancora il nome. Introduci `DriversCatalog.MAX_DRIVERS = 22` e usala per `RaceResultValidation.MAX_FIELD` e `PredictionValidation.MAX_POSITION`.

**B6 — Classifica incompleta e UUID mostrati.**
`computeStandings` costruisce le righe solo da chi ha pronostici: i membri senza pronostici non compaiono e gli ex-membri appaiono con l'UUID come nome (`displayNames[userId] ?: userId`). Fix: parti dalla lista dei membri (riga a 0 punti se non hanno pronostici), e per gli ex-membri con pronostici usa "Ex giocatore" (o escludili: scegli e annota).

**B9 / B10 — Codice morto e regole non applicate.**
`ScoringEngine.canSubmit`, `isClosed`, `canRevealOthers` non sono usate; `canSubmit` con `allowEditUntilDeadline = false` ritorna sempre false (hack `nowEpochMs == 0L`). Rimuovile oppure sistemale e usale davvero. `Race.isPredictionOpen` deve considerare anche `status` (`CLOSED`/`COMPLETED` ⇒ chiusa) e va usata in `PredictionViewModel`. Rimuovi anche `Rules.fromOrDefault`, `Routes.RACE` se inutilizzati.

**B22 / B23 — Decodifica troppo fragile, dati scartati in silenzio.**
`LeagueDto.toDomainOrNull()` ritorna `null` se `rules` non decodifica: la lega sparisce dalla lista (il commento promette il fallback ai default) e la classifica usa `Rules()` senza avviso. Pronostici e risultati non decodificabili sono scartati con `getOrNull()`. Fix: `Json { ignoreUnknownKeys = true; coerceInputValues = true }` condiviso (un solo oggetto `AppJson`), fallback a `Rules()` per la lega, e conteggio degli elementi non leggibili mostrato in `StandingsScreen` ("N pronostici non leggibili").

**B30 — Punti sprint scalati due volte.**
`SPRINT_WINNER` vale `points[SPRINT_WINNER] * sprintPointsMultiplier` (default 10 × 0.5 = 5) ma l'editor mostra 10. Nell'editor mostra i punti effettivi accanto al moltiplicatore.

**Minori:** refuso in `PredictionType.italianDescription` ("due pilota" → "due piloti"); `Rules.validate` ha un controllo ridondante (`isEmpty() || size < 3`).

---

## Fase 2 — Data layer, auth, configurazione

**B2 — I risultati delle gare sprint non si possono salvare (critico).**
`SupabaseRaceRepository.saveResult` chiama `RaceResultValidation.validate(result)` **senza** `isSprintRace`: con posizioni sprint ottieni sempre "Posizione sprint di X presente ma la gara non ha sprint". Fix: leggi la gara (o aggiungi il parametro `isSprintRace` all'interfaccia) e passa il valore corretto; evita validazioni duplicate in conflitto con quelle del ViewModel.

**B21 — Scritture non atomiche e messaggi d'errore fuorvianti.**
- `saveResult`: update → insert → `updateStatus` in 3 chiamate (se l'ultima fallisce resta uno stato incoerente). Crea in migration l'RPC `save_race_result(p_race uuid, p_result jsonb)` (security definer: controlla admin, che la gara appartenga alla lega, fa upsert e porta lo stato a `completed`) e chiamala dal repository.
- `savePrediction`: sostituisci update-poi-insert con `upsert(onConflict = "race_id,user_id")`. Con RLS un update fuori deadline modifica 0 righe senza errore: se 0 righe, restituisci subito "La deadline è passata".
- `mapWriteError` di `SupabasePredictionRepository` traduce **ogni** violazione RLS in "La deadline è passata": distingui deadline / non sei membro / permessi.
- `mapWriteError` di `SupabaseRaceRepository` traduce ogni "unique" in "round già esistente" (vale anche per `race_results.race_id`).

**B7 — Registrazione: nome perso e nessun avviso di conferma email.**
`signUp` non passa `display_name` nei metadata (il trigger `handle_new_user` lo legge già da `raw_user_meta_data`). Poi tenta un `update` su `profiles` che, con "Confirm email" attivo, non ha sessione e fallisce in silenzio. Fix: `signUpWith(Email) { data = buildJsonObject { put("display_name", name) } }`; se dopo la registrazione la sessione è `null`, emetti un evento `ConfirmEmail` con messaggio "Controlla la tua email per confermare l'account" invece di trattarla come login. In `refresh()` leggi il nome dal profilo, non dai metadata.

**B8 — Sessione salvata e scadenza.**
`SupabaseAuthRepository.refresh()` legge `currentUserOrNull()` subito dopo `createSupabaseClient`, prima che la sessione sia caricata dallo storage: possibile "disconnesso" a ogni avvio (verifica su dispositivo). Usa `auth.awaitInitialization()` e osserva `auth.sessionStatus` per aggiornare `AuthState` (anche a scadenza del refresh token / sign-out remoto), così l'app torna al login invece di mostrare errori 401 grezzi.

**B25 — Inizializzazione doppia e senza attesa.**
`AppContainer.initialize()` è chiamata sia in `FantaApplication.onCreate` (fire-and-forget con `runCatching` che inghiotte gli errori) sia in `AppNavHost`. Lascia **un solo** punto (suggerito: `AppNavHost`), mostra il loading finché non è finita e, se fallisce, porta a `SignedOut` con un messaggio. Rimuovi `onTerminate` (non viene mai chiamato su device reali).

**B24 — Configurazione Supabase.**
- `SupabaseHolder.configure` con configurazione non valida ritorna `true` e tiene il vecchio client: il salvataggio sembra riuscito ma non è applicato. Ritorna `false` e non cambiare client.
- `ConfigViewModel.testConnection` chiama `holder.configure(config)` sul client **globale** con valori non salvati: se il test fallisce l'app resta puntata a una config sbagliata fino al riavvio. Usa un client temporaneo, da chiudere a fine test.
- `SupabaseConfigStore`: stringhe vuote salvate sovrascrivono i default di `BuildConfig`; trattale come "nessun override".
- Accetta solo `https://` (con targetSdk 35 il cleartext è bloccato: `http://` passa la validazione ma poi non funziona).

**B26 — Mapping errori auth.**
`mapAuthError` traduce ogni 401/403 in "Email o password non corretti" (anche con anon key sbagliata) e usa reflection su `getStatus`. Usa il codice errore dell'eccezione Supabase (`invalid_credentials`, `user_already_exists`, `over_request_rate_limit`, `email_not_confirmed`, `weak_password`) e una chiave anon errata deve dare "Configurazione non valida".

**B17 — Gestione membri e leghe.**
- L'ultimo admin può uscire (`leaveLeague`) lasciando la lega senza amministratori. Blocca lato client e lato DB (vedi Fase 4).
- `myLeagues()` mostra anche le leghe da cui il creatore è uscito (policy `leagues_select` con `created_by = auth.uid()`): filtra sui membri.
- Nessuna UI per promuovere/retrocedere/rimuovere membri: la RPC `set_league_member_role` non è mai chiamata. Aggiungi le azioni in `LeagueMembersScreen` per gli admin, con conferma.
- `updateRules`: errori non incapsulati in `UserException` e, se RLS blocca, 0 righe aggiornate senza errore. Verifica le righe modificate (`select()` dopo l'update) e lancia un errore chiaro.

**B27 — Impossibile cancellare campi opzionali (verificare).**
`RaceUpdateDto` con `deadline = null`/`country = null`: se il serializzatore ha `explicitNulls = false` il campo viene omesso dal PATCH e non si può svuotare. Verifica; se confermato, costruisci il body con `buildJsonObject` e `JsonNull` espliciti.

---

## Fase 3 — UI / ViewModel

**B5 (lato app) — Deadline obbligatoria.**
`CalendarViewModel.submitForm` accetta deadline vuota: la gara resta aperta per sempre e i pronostici altrui non vengono mai rivelati (vedi Fase 4). Rendi la deadline obbligatoria nel form (messaggio in italiano).

**B20 — Stato gara mai aggiornato.**
`OPEN` e `CLOSED` non vengono mai scritti: la gara resta "Programmata" finché non c'è un risultato. Deriva lo stato mostrato da deadline e risultato (Programmata / Aperta / Chiusa / Completata) in una funzione unica `Race.effectiveStatus(now, hasResult)` usata da calendario, pronostici e classifica.

**B13 — Form risultati: non si possono aggiungere piloti dopo il primo salvataggio.**
`ResultViewModel.load` con risultato esistente carica solo i piloti salvati. Fai il merge: catalogo completo + dati salvati (i piloti senza dati restano righe vuote).

**B14 — Bozze pronostico non allineate alle regole.**
`PredictionViewModel.load` usa `mine.entries` come bozze: se l'admin attiva un nuovo tipo dopo il primo invio, il tipo non compare e il salvataggio fallisce con "Manca il pronostico…"; se ne disattiva uno, la bozza resta e fallisce con "non è attivo per questa lega". Costruisci le bozze da `visibleTypes` e riempile con i valori di `mine` solo per i tipi ancora attivi.

**B15 — Tutti i tipi attivi sono obbligatori.**
`PredictionValidation.validate` richiede un pronostico per ogni tipo attivo: con i 12 tipi di default non si può salvare un pronostico parziale. Permetti pronostici parziali (almeno 1 voce valida) e mostra un avviso "Non hai compilato: …" prima del salvataggio.

**B10 (lato app) — `revealOthersAfterDeadline` / `allowEditUntilDeadline`.**
`PredictionScreen` mostra i pronostici altrui se la deadline è passata a prescindere da `revealOthersAfterDeadline`. Applica la regola nel ViewModel. Ricalcola `canSubmit`/`deadlinePassed` quando la deadline scade mentre la schermata è aperta (timer fino alla deadline, e al `ON_RESUME`).

**Minori:** `StandingsViewModel` usa `Rules()` se la lega è null senza avvisare; nell'editor regole avvisa che modificare le regole ricalcola retroattivamente tutta la classifica.

---

## Fase 4 — Migration `supabase/migrations/0002_fixes.sql`

**B5 (lato DB) — Gara senza deadline / risultato già inserito.**
Ridefinisci:
- `is_prediction_open(race)` = stato in (`scheduled`,`open`) **e** (`deadline is null` o `now() < deadline`);
- `is_prediction_closed(race)` = stato in (`closed`,`completed`) **o** (`deadline is not null` e `now() >= deadline`).
Così i pronostici si chiudono quando viene inserito il risultato e gli altri diventano leggibili anche per gare senza deadline.

**B18 — Integrità tra leghe.**
Oggi `predictions`, `race_results` e `races` non verificano che `race_id`/`season_id` appartengano alla stessa `league_id`: un membro può scrivere un pronostico con la propria `league_id` su una gara di un'altra lega, e un admin può occupare `race_results.race_id` (unique) di un'altra lega. Aggiungi `unique (id, league_id)` su `seasons` e `races` e chiavi esterne composite: `races (season_id, league_id)`, `predictions (race_id, league_id)`, `race_results (race_id, league_id)`. Aggiungile prima `not valid`, poi `validate constraint`.

**B10 (lato DB) — Applicare le regole anche sul server.**
Crea `league_rule_bool(p_league uuid, p_key text, p_default boolean)` (security definer, legge `leagues.rules`) e usala: `predictions_update_own`/`delete_own` negate se `allowEditUntilDeadline = false` e il pronostico esiste già; `predictions_select` per gli altri utenti solo se `revealOthersAfterDeadline` non è `false`.

**B17 (lato DB) — Ultimo admin e ruoli.**
- Trigger `before delete or update of role` su `league_members` che impedisce di rimuovere/retrocedere l'ultimo admin (se la lega non è in cancellazione).
- RPC `remove_league_member(p_league, p_user)` solo per admin.
- `leagues_select` solo per i membri (`is_league_member(id)`), non anche per `created_by`.
- Trigger su `leagues` che impedisce di cambiare `invite_code` e `created_by` con un update normale e che richiede `jsonb_typeof(rules) = 'object'`.

**Altro.**
- Trigger `before insert or update` su `predictions` che imposta `submitted_at = now()` lato server (oggi lo decide il client).
- `revoke execute on all functions in schema public from public, anon;` poi `grant execute` ad `authenticated` solo sulle RPC usate (`create_league`, `join_league_by_code`, `set_league_member_role`, `create_default_season`, `remove_league_member`, `save_race_result`).
- RPC `save_race_result` (vedi B21).
- Aggiorna il `README` con il passo "esegui anche `0002_fixes.sql`".

---

## Fase 5 — Build, documentazione, igiene

**B28 — Build non portabile e documentazione errata.**
- `gradle.properties` contiene `org.gradle.java.home=C:/Program Files/Eclipse Adoptium/...`: la build fallisce su qualunque altra macchina o CI. Rimuovilo e usa `kotlin { jvmToolchain(21) }` (o il `gradle.properties` utente); aggiorna `scripts/setup-android-env.ps1`.
- Il README documenta `SUPABASE_URL` / `SUPABASE_ANON_KEY` in `local.properties`, ma `app/build.gradle.kts` legge `supabase.url` / `supabase.anonKey`: i default non vengono mai caricati. Allinea codice e README (accetta entrambe le chiavi).
- `AndroidManifest`: `allowBackup="true"` fa finire nei backup anche la sessione/config; imposta `allowBackup="false"` oppure `dataExtractionRules` che escludono DataStore e preferenze di sessione.
- `Realtime` è installato in `SupabaseHolder` ma mai usato: rimuovi dipendenza e `install(Realtime)` (o implementa l'aggiornamento live della classifica).
- `testImplementation` include `androidx.junit` e `espresso` senza usarli: rimuovili se inutili.
- Aggiungi `.gitattributes` con `* text=auto eol=lf` e normalizza i finali di riga misti (`SupabaseAuthRepository.kt`, `SupabaseLeagueRepository.kt` hanno `\r\n` solo su alcune righe).

---

## Test da aggiungere/aggiornare (minimo)

- Classifica: 116 pt vs 165 pt con ogni `TieBreaker`; parità reale di punti per ciascuno spareggio; membro senza pronostici presente a 0; ex-membro senza UUID.
- Pronostico con `raceId` falsificato non fa punti; un solo pronostico per (utente, gara) conteggiato.
- `DRIVER_POINTS_OVER_UNDER` con `pointsScored = null` usa la tabella F1; over/under su soglia.
- Validazione risultato: manca P1, posizioni con buchi, 21º e 22º pilota validi, sprint con posizioni salvabile su gara sprint.
- `PredictionValidation`: pronostico parziale valido; tipo disattivato ignorato; nuovo tipo attivato dopo un invio.
- `Race.effectiveStatus` e `isPredictionOpen` con stato `COMPLETED`.
- `Rules` con JSON incompleto o enum sconosciuto decodifica con fallback.
- Config: stringhe vuote = default; `configure` con config invalida ritorna `false`.

## Criteri di accettazione

- `./gradlew clean testDebugUnitTest lintDebug assembleDebug` verde da una macchina pulita senza `org.gradle.java.home` hardcoded.
- Salvare un risultato di una gara sprint con posizioni sprint funziona.
- Con le regole di default, il giocatore con più punti totali è primo in classifica.
- Una gara senza risultato né deadline non può restare aperta: il form la richiede, il DB chiude i pronostici a risultato inserito.
- Un utente non può far contare un pronostico su un'altra gara né su un'altra lega.
- Alla fine, elencami in una tabella ogni `B…` con stato (fatto / non fatto / non riproducibile) e il commit corrispondente.
