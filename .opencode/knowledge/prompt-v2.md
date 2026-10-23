# Prompt v2: bug residui, nuovi bug, UX/UI e funzioni (FantaFormula 1)

Copia tutto quello che segue (dalla riga "Contesto" in poi) nel tuo agente di coding.

---

## Contesto

App Android nativa (Kotlin, Jetpack Compose, Material 3, Supabase, WorkManager, Ktor) in `com.fantaformula1.app`. Il motore di punteggio gira lato client in `core/domain`. Nella versione attuale esiste già `prompt-fix-fantaformula1.md` con i bug `B1…B30`: la maggior parte è stata corretta. Questo prompt chiude i bug rimasti, corregge i bug introdotti dalla sincronizzazione automatica con l'API F1 e dalla migration `0002`, e aggiunge miglioramenti di UX/UI e di funzioni.

Gli ID `N1…N12` sono bug nuovi, `R1…R8` sono bug della prima revisione ancora aperti, `U1…U12` sono miglioramenti UX/UI, `F1…F6` sono funzioni nuove.

## Regole di lavoro

1. Lavora per **fasi** (1 → 6). Un commit per fase, messaggio in italiano.
2. Dopo ogni fase esegui `./gradlew testDebugUnitTest lintDebug` e correggi finché è tutto verde.
3. **Non rinominare** gli enum (`PredictionType`, `TieBreaker`, `RaceStatus`) né le chiavi JSON di `Rules`, `RaceResultData`, `RacePrediction`: i dati già su Supabase devono continuare a funzionare.
4. Le modifiche al DB vanno in un nuovo file `supabase/migrations/0003_fixes.sql`, idempotente. Non modificare `0001` né `0002` (potrebbero essere già applicate).
5. Ogni fix di dominio o di mapping ha un test JVM. Nessuna dipendenza nuova **senza motivarla**; le uniche accettate sono Coil (immagini piloti) e una libreria di test se serve.
6. Non usare `runCatching` attorno a codice `suspend` senza rilanciare `CancellationException`.
7. Quando una scelta di design è ambigua, scegli la più semplice, annotala in `.opencode/knowledge/decisions.md` e continua senza chiedere.
8. Alla fine dammi una tabella con ogni ID (`N`, `R`, `U`, `F`), stato (fatto / non fatto / non verificabile) e commit.

---

## Fase 1 — Sincronizzazione F1 (`data/f1api`, `data/sync`)

**N1 — Mapping piloti probabilmente sbagliato (critico, da verificare per prima cosa).**
`F1DriverMapping` usa id Jolpica del tipo `lando_norris`. Per quanto ne so Ergast/Jolpica usa il cognome (`norris`, `hamilton`, `leclerc`, `sainz`…), con poche eccezioni come `max_verstappen`. Le fixture dei test (`F1MapperTest`, `F1JsonTest`) sono scritte a mano con `lando_norris`, quindi non lo scoprono.
- Verifica con una chiamata reale: `curl https://api.jolpi.ca/ergast/f1/2026/drivers.json`. Se non riesci a eseguirla, assumi che il problema esista.
- Cambia il mapping: usa prima il campo `code` di `F1Driver` (VER, NOR, HAM… coincide con gli id dell'app) e come ripiego una tabella di `driverId` corretta.
- Aggiungi come fixture dei test una risposta reale (anche ridotta) di Jolpica e rifai i test su quella.

**N2 — Piloti non mappati spostano le posizioni.**
`F1Mapper.toRaceResultData` scarta i piloti non presenti nel catalogo e rinormalizza le posizioni, quindi una riserva 5ª fa salire di un posto tutti i piloti dietro di lei. Fix: se nell'API c'è un pilota non mappabile, **non salvare** il risultato (ritorna un errore esplicito "Pilota sconosciuto: X") e fai restare la gara in attesa. Test.

**N3 — Dati che l'API non fornisce salvati come zero.**
`safetyCars`, `redFlags`, `laps`, `pitStops`, `penaltyPoints` vengono salvati a 0 e la gara viene marcata `completed`: i pronostici `SAFETY_CAR_COUNT_OVER_UNDER` e `PIT_STOPS_OVER_UNDER` vengono calcolati su dati falsi.
- In `RaceResultData` rendi `safetyCars` e `totalPitStops` nullable (compatibile col JSON esistente).
- Nel motore di punteggio, se il dato è `null` i tipi collegati danno 0 punti e nella classifica compare l'avviso "In attesa di conferma admin".
- Nel form risultati l'admin vede quei campi evidenziati come "da completare" per le gare sincronizzate automaticamente.

**N4 — Deadline sbagliata nei weekend sprint.**
`F1Mapper.toRaceCreate` usa l'inizio della qualifica, ma nei weekend sprint lo sprint si corre il sabato prima della qualifica: si può pronosticare lo sprint dopo che è finito. Fix: la deadline è il primo evento utile del weekend (`SprintQualifying` se presente, altrimenti `Sprint`, altrimenti `Qualifying`, altrimenti la gara). Test su un weekend sprint e su uno normale.

**N5 — Sincronizzazione aperta a tutti i membri.**
`F1SyncScheduler.schedule` parte quando qualsiasi membro apre una lega, e i pulsanti di sync in `LeagueScreen` e `CalendarScreen` non sono riservati agli admin: i non-admin ricevono errori RLS. Fix: pianifica il worker e mostra i pulsanti **solo agli admin**; chiama `F1SyncScheduler.cancel` al logout, all'uscita dalla lega e quando l'utente non è più admin; il worker non deve riprovare per sempre su errori di permesso (`Result.failure()`).

**N6 — Stagione e richieste fisse.**
- Il valore `2026` è scritto in `F1ApiClient` e `F1SyncRepository`: derivalo dalla stagione della lega (o dall'anno corrente).
- `syncResults` interroga l'API anche per le gare future (circa 20 richieste ogni 30 minuti per dispositivo): considera solo gare con data passata e senza risultato.
- Una gara già disputata ma assente dalla lega non viene mai creata (`isPast`): va bene, ma il toast del calendario deve dire "N gare passate saltate".

**N7 — Risultati dipendenti dal telefono dell'admin.**
`save_race_result` richiede un admin, quindi i risultati arrivano solo se il telefono di un admin esegue il worker. Scrivi un **piano** (non implementare) in `.opencode/knowledge/decisions.md` per spostare la sincronizzazione su una Edge Function Supabase con `pg_cron`, con i punti da cambiare. Nel frattempo, nella UI mostra "Ultimo aggiornamento risultati: …" in evidenza e un avviso se sono passate più di 12 ore dalla fine della gara senza risultato.

---

## Fase 2 — Migration `0003_fixes.sql`

**N8 — Non si possono più eliminare gare (cascade perse).**
Le chiavi esterne composite di `0002` non hanno `on delete cascade`: eliminare una gara con pronostici o risultato fallisce. Ricrea `predictions_race_league_fk` e `race_results_race_league_fk` con `on delete cascade` e `races_season_league_fk` con `on delete cascade`. Verifica con eliminazione di una gara che ha pronostici e risultato.

**N9 — Il trigger "ultimo admin" blocca l'eliminazione di una lega.**
`check_last_admin_on_delete` scatta anche nella cancellazione a cascata di una lega. Fix: se la lega non esiste più (`not exists (select 1 from public.leagues where id = old.league_id)`) il trigger lascia passare. Stesso controllo per l'eliminazione di un utente. Test manuale: elimina una lega con un solo admin.

**R4 — Deadline opzionale.**
Aggiungi un vincolo `check (deadline is not null)` per le nuove gare tramite trigger `before insert` (non `not null` sulla colonna, per non rompere i dati esistenti), e in `CalendarViewModel.submitForm` rendi la deadline obbligatoria con un messaggio in italiano.

**R8 — Gestione membri lato DB.**
Crea la RPC `remove_league_member(p_league uuid, p_user uuid)` (solo admin, non rimuove l'ultimo admin) con `grant execute` ad `authenticated`.

---

## Fase 3 — Bug rimasti nel client

**N10 — "Prova connessione" cambia il client globale.**
`ConfigViewModel.testConnection` chiama `container.supabaseHolder.configure(config)` con valori non salvati: se il test fallisce l'app resta su una configurazione sbagliata. Usa un client temporaneo, da chiudere a fine test, senza toccare quello globale.

**N11 — Errori di login.**
`SupabaseAuthRepository.mapAuthError` traduce ancora ogni 401/403 in "Email o password non corretti" (anche con anon key sbagliata) e usa la reflection su `getStatus`. Usa il codice errore dell'eccezione Supabase: `invalid_credentials`, `user_already_exists`, `email_not_confirmed`, `over_request_rate_limit`, `weak_password`; una chiave anon errata deve dare "Configurazione non valida".

**N12 — `RaceIdentity` e sessione.**
- `RaceIdentity.visualFor` confronta per sottostringa: la chiave `"uk"` troverebbe anche "Turkey". Usa confronto per parola intera.
- `startSessionObserver` può essere chiamato due volte (Application e `AppNavHost`) e lascia collector sul vecchio client dopo una riconfigurazione: rendilo idempotente e cancellalo quando il client cambia.

**R1 — `gradle.properties` non portabile.**
Rimuovi `org.gradle.java.home=C:/Program Files/...` e usa `kotlin { jvmToolchain(21) }`; aggiorna `scripts/setup-android-env.ps1` e il README.

**R2 — Backup e Realtime.**
`allowBackup="true"` salva la sessione nei backup: imposta `false` oppure aggiungi `dataExtractionRules`. `Realtime` è installato in `SupabaseHolder` e non viene usato: usalo per **F4** oppure rimuovi dipendenza e `install(Realtime)`.

**R3 — Gestione ruoli nella UI.**
In `LeagueMembersScreen` aggiungi per gli admin: promuovi, retrocedi (RPC `set_league_member_role`), rimuovi (`remove_league_member`), sempre con dialogo di conferma; l'ultimo admin non vede "Retrocedi/Esci" ma un messaggio che spiega perché.

**R5 — Scritture e messaggi d'errore.**
`SupabaseRaceRepository.mapWriteError` mostra "Dati della gara non validi" per qualunque violazione, incluse chiavi esterne e trigger. Distingui: permessi, duplicato di round, gara con dati collegati, ultimo admin.

**R6 — Pronostici parziali.**
Verifica che `PredictionValidation` accetti un pronostico parziale (almeno una voce valida) e mostri "Non hai compilato: …" prima dell'invio. Se è ancora obbligatorio compilare tutto, correggilo.

**R7 — `updateRace` e deadline nulla.**
`RaceUpdateDto.deadline` non ha default per essere sempre serializzata: aggiungi un test che serializza il DTO con il `Json` reale del client e verifica che la chiave `deadline` sia presente anche con valore `null`.

---

## Fase 4 — UX/UI

**U1 — Spiegazione dei punti.** Per ogni voce del pronostico, nella schermata gara e nel dettaglio classifica mostra "Podio: +12 (Verstappen 1º ✓, Norris 3º ✗…)". Crea in `ScoringEngine` una funzione `explain(prediction, result, rules)` che restituisce una lista di `ScoreLine(type, points, detail)`; `score()` deve usarla così che totale e spiegazione non possano divergere. Test.

**U2 — Selettori data e ora.** Sostituisci il campo di testo "2026-03-15 14:00" in `CalendarScreen` con `DatePicker` e `TimePicker` di Material 3 (fuso orario del dispositivo, salvataggio in UTC).

**U3 — Countdown.** Sulle gare aperte mostra "Si chiude tra 2 g 4 h" aggiornato ogni minuto, e un banner di avviso sotto le 2 ore se il pronostico non è completo.

**U4 — Home della lega.** In `LeagueScreen` aggiungi in alto una scheda "Prossima gara": nome, bandiera, countdown, stato del tuo pronostico ("8/12 compilati"), posizione attuale in classifica e pulsante "Pronostica".

**U5 — Bozza automatica.** Salva la bozza del pronostico localmente (DataStore) mentre l'utente compila; ripristinala al rientro e mostra un riepilogo prima dell'invio.

**U6 — Feedback uniforme.** Sostituisci i `Toast` con `Snackbar` (anche in `AppNavHost` per le sync), aggiungi pull-to-refresh a leghe, calendario e classifica, e stati vuoti con un'azione chiara ("Nessuna gara: sincronizza il calendario").

**U7 — Selezione piloti più riconoscibile.** Nei picker mostra colore della scuderia e immagine del pilota con Coil: le immagini vanno in `app/src/main/res/drawable-nodpi/` o negli asset, nome `driver_<ID>.webp`, e `DriversCatalog` espone `imageRes`; se manca l'immagine mostra le iniziali su cerchio colorato.

**U8 — Classifica più leggibile.** Aggiungi il dettaglio per gara (tap sulla riga), frecce di variazione rispetto alla gara precedente e la tua riga evidenziata.

**U9 — Editor regole semplificato.** In `RulesScreen` mostra in alto due preset (Classico e Arcade) con un'anteprima del punteggio massimo possibile; sposta i parametri avanzati sotto "Avanzate". Avvisa che modificare le regole ricalcola retroattivamente la classifica.

**U10 — Invito.** Pulsante "Condividi" nello share sheet con testo "Unisciti alla mia lega su FantaFormula 1: codice XXXXXX"; copia negli appunti con conferma.

**U11 — Accessibilità.** Sostituisci i `contentDescription = null` (circa 23) con descrizioni reali tranne che per elementi puramente decorativi; verifica contrasto e dimensioni minime dei tocchi (48 dp); i testi delle bandiere emoji devono avere una descrizione ("Bandiera Italia").

**U12 — Date e numeri in italiano.** Formatta date, orari e punti con il `Locale` del dispositivo (non stringhe fisse) e mostra il fuso orario nella deadline.

---

## Fase 5 — Funzioni nuove

**F1 — Notifiche locali.** Con WorkManager: avviso 24 ore e 1 ora prima della deadline se il pronostico è mancante o incompleto, e avviso quando i risultati di una gara sono disponibili. Canale dedicato, permesso `POST_NOTIFICATIONS` richiesto in contesto, impostazione per spegnerle.

**F2 — Storico stagioni.** Archivia una stagione conclusa: classifica finale congelata, consultabile da "Stagioni passate". Una nuova stagione parte da zero senza toccare i dati vecchi.

**F3 — Cache offline.** Mantieni in locale (DataStore o Room, scegli il più semplice) leghe, gare, tuoi pronostici e classifica; mostra il dato in cache con "Ultimo aggiornamento" quando manca la rete; i salvataggi senza rete vengono messi in coda e rispediti, e se la deadline nel frattempo è passata l'utente viene avvisato.

**F4 — Classifica live.** Usa `Realtime` per aggiornare la classifica quando arriva un risultato (o rimuovi la dipendenza, vedi R2).

**F5 — Test e CI.** Aggiungi `.github/workflows/android.yml` che esegue `./gradlew testDebugUnitTest lintDebug` su ogni push; aggiungi test sulle fixture reali di Jolpica (N1), sul mapper e sul calcolo della deadline sprint (N4).

**F6 — Dati F1 non disponibili.** Per i tipi che l'API non copre (safety car, pit stop) valuta una fonte alternativa; se non c'è, lascia il completamento manuale (N3) e rendi i due tipi disattivati nei preset nuovi.

---

## Fase 6 — Verifica finale

- `./gradlew clean testDebugUnitTest lintDebug assembleDebug` verde da una macchina pulita.
- Test con risposta reale di Jolpica: ogni pilota 2026 viene mappato; un pilota sconosciuto blocca il salvataggio.
- Eliminare una gara con pronostici e risultato funziona; eliminare una lega con un solo admin funziona.
- Un non-admin non vede i pulsanti di sync e non avvia il worker.
- In un weekend sprint la deadline è prima dello sprint.
- Un risultato sincronizzato senza safety car e pit stop non assegna punti su quei due tipi.
- Applica `0003_fixes.sql` su un progetto Supabase di prova prima di quello reale e annota il risultato nel README. 