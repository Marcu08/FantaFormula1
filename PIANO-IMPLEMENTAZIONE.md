# FantaFormula 1 — App Android nativa (Kotlin) con lega Supabase

Piano di implementazione approvato (sessione pianificazione, 12/10/2026).
Da eseguire in una **nuova sessione opencode** (dispatch code agent non raggiungibile in quella corrente: `ConnectionRefused localhost:4096`).

## Objective
Creare da zero (cartella `C:\Users\marzu\OneDrive\Desktop\fantaformula1` — attualmente vuota) un'app Android nativa in Kotlin, **FantaFormula 1**: una lega di pronostici F1 tra amici, con:

- **Multiplayer cloud Supabase** (lega condivisa in tempo reale, tutti vedono gli stessi dati);
- **Formato "solo pronostici"**: niente rose/budget — ogni giocatore punta su gare/piloti, chi totalizza più punti vince;
- **Tutto configurabile**: editor regole completo (preset F1 predefiniti + parametri custom), e **inserimento di tutti i parametri della gara** dall'admin (griglia, classifica, sprint, giro veloce, DNF, penalità, pit stop, safety car, ecc.);
- Consegna finale: **APK** generato in `app/build/outputs/apk/debug/app-debug.apk`.

UI interamente in **italiano**, Jetpack Compose + Material 3, look moderno e "avanzato".

## Stack tecnico (decisioni)
- Kotlin 2.1.x + Jetpack Compose (BOM recente), Material 3, Navigation Compose
- MVVM: `ViewModel` + Repository + supabase-kt 3.x (postgrest-kt, auth-kt, realtime-kt, ktor client, kotlinx-serialization)
- DI manuale leggera (`AppContainer` su `Application`) — niente Hilt/KSP per ridurre rischi di incompatibilità versioni
- AGP 8.7–8.9, Gradle Wrapper 8.13, `compileSdk 35`, `minSdk 26`, `targetSdk 35`, version catalog `gradle/libs.versions.toml`
- Package: `com.fantaformula1.app`
- Nessun backend proprio: Supabase è BaaS; il **motore di punteggio gira in Kotlin** (lato client) con test unitari JVM

## Requisiti preliminari (da parte dell'utente, non bloccanti per lo sviluppo)
- Account Supabase gratuito + progetto → eseguire `supabase/migrations/0001_init.sql` (SQL Editor) e attivare Email/Password in Auth. Istruzioni precise nel `README.md`.
- L'app accetta URL/anon-key **a runtime** (schermata Configurazione con valori di default da `BuildConfig`/`local.properties`): senza chiavi mostra uno stato "configurazione mancante" invece di crashare.

## Vincoli ambienti (verificati in ricerca)
- Windows; JDK **27** presente (`C:\Program Files\Java\jdk-27`) → **non compatto con AGP/Gradle**: va installato **Temurin JDK 21 LTS** (winget o choco disponibili) e usato via `org.gradle.java.home` in `gradle.properties` + setup script.
- **Nessun Android SDK, nessun Gradle, nessun Android Studio** → setup automatico: cmdline-tools da `dl.google.com` (raggiungibile), `sdkmanager` installa `platform-tools`, `platforms;android-35`, `build-tools;35.0.0`, licenze accettate (`--licenses`).
- Disco libero ~29 GB, rete OK a `dl.google.com` e `services.gradle.org`.

---

## Phases

### Fase 0 — Ambiente di build Android
- Script `scripts/setup-android-env.ps1` che: installa Temurin 21 (winget, se assente), scarica cmdline-tools, installa SDK richiesti, accetta licenze, scrive `local.properties` (`sdk.dir`, `JAVA_HOME`).
- `gradle.properties`: `org.gradle.java.home=JDK21`, `android.useAndroidX=true`.
- **AC**: `.\scripts\setup-android-env.ps1` exit 0; `.\gradlew.bat -version` riporta Gradle 8.13 su JVM 21; `sdkmanager --list_installed` mostra `platforms;android-35`.

### Fase 1 — Scaffold progetto Compose + primo APK
- Struttura Gradle completa: `settings.gradle.kts`, `build.gradle.kts`, version catalog, wrapper (jar inclusa), `app/` con `MainActivity`, tema Compose, manifest, icone.
- **AC**: `.\gradlew.bat assembleDebug` produce `app/build/outputs/apk/debug/app-debug.apk` (>5 MB); `lintDebug` senza errori; test dummy verdi.

### Fase 2 — Supabase: schema, auth, configurazione
- `supabase/migrations/0001_init.sql`: tabelle `profiles`, `leagues` (con `invite_code`, `rules jsonb`), `league_members` (ruoli admin/player), `seasons`, `races` (round, tipo sprint, `deadline`, stato), `race_results` (jsonb: tutti i parametri gara), `predictions` (jsonb, una riga per utente/gara) + **RLS** (solo membri leggono, solo admin scrive risultati, pronostici solo entro deadline via `now() < deadline`), funzione join-by-code.
- Integrazione supabase-kt: client, auth email+password (signup/login/logout), schermata **Configurazione** (URL/anon key in DataStore).
- **AC**: `README.md` con passi Supabase; unit test parsing/config; schermate login+config presenti; SQL contiene tutte le tabelle e policy RLS.

### Fase 3 — Modello dominio + motore punteggi (cuore dell'app)
- Classi pure Kotlin: `Rules` (shape JSON allineato al jsonb), `PredictionEntry` (tipi pronostico), `RaceResultData` (parametri gara), `ScoringEngine.score(...)` + classifica con tie-break configurabile.
- Tipi pronostico supportati: vincitore, podio (top-3), pole, giro veloce, posizione esatta/range pilota, head-to-head A>B, DNF sì/no, punti pilota over/under, vincitore sprint, più/meno safety car o pit stop.
- **AC**: `ScoringEngineTest` (≥25 casi: happy path, DNF, penali, sprint, tie-break, regole custom, preset) tutti verdi su `.\gradlew.bat testDebugUnitTest`.

### Fase 4 — Leghe: crea, unisciti, membri
- Crea lega (genera codice invito 6 cifre), unisciti via codice, lista membri, ruoli, home con leghe dell'utente.
- **AC**: repository con test JVM (fake Supabase via interfacce); navigazione completa; stati vuoto/errore in italiano.

### Fase 5 — Calendario gare + inserimento risultati (tutti i parametri)
- Gestione stagione/calendario (CRUD gare dall'admin: nome, paese, flag sprint, deadline).
- Form risultati admin **completo**: per ogni pilota → griglia, posizione, posizione sprint, giro veloce sì/no, DNF + motivo, penalità, pit stop; a livello gara → safety car, bandiera rossa, giri, meteo/note. Salva in `race_results`, stato gara → completata.
- **AC**: form valida input (posizioni uniche, 0–20), test di mappatura result→jsonb, salvataggio invoca colonna giusta.

### Fase 6 — Pronostici
- Entro `deadline`: inserimento/modifica pronostici per gara in base ai tipi attivi nelle regole; blocco dopo deadline; riepilogo pronostici degli altri giocatori visibile solo dopo chiusura (regola configurabile).
- **AC**: UI disabilitata dopo deadline (test logica `canSubmit`), serializzazione round-trip testata.

### Fase 7 — Classifica, punteggi per gara, editor regole
- Classifica lega (leaderboard con realtime opzionale), dettaglio punteggi per gara per ogni giocatore, **editor regole completo** (preset "F1 ufficiale", modifica punti/abilita tipi/moltiplicatori, salvataggio in `rules jsonb`).
- **AC**: leaderboard calcolata tramite `ScoringEngine` (test), editor valida range, preset applicato correttamente (test).

### Fase 8 — Polish UI/UX
- Tema scuro/chiaro Material 3, componenti riusati (card, empty state, loading, error retry), animazioni di navigazione, tutto in italiano, icone app.
- **AC**: lint verde, nessun TODO/placeholder nelle stringhe, tutti gli stati di caricamento/errore gestiti.

### Fase 9 — Build finale APK + consegna
- `.\gradlew.bat clean assembleDebug` → consegna `app\build\outputs\apk\debug\app-debug.apk`; `README.md` finale: installazione su telefono (fonti sconosciute), setup Supabase, uso lega con gli amici.
- **AC**: APK esiste con dimensione plausibile; README completo.

### Fase 10 — Memory curation
- Task @Librarian: estrarre convention/decisioni/context di questa sessione (stack scelto, formato pronostici, decisione Supabase, vincolo JDK21↔AGP, struttura schema SQL) e scriverle in memoria progetto con nome branch/note.

---

## Verification
1. `.\gradlew.bat testDebugUnitTest` — tutti i test verdi (include `ScoringEngineTest` ≥25 casi, test config, test serializzazione, test logica deadline).
2. `.\gradlew.bat lintDebug` — 0 errori.
3. `.\gradlew.bat assembleDebug` → esiste `app/build/outputs/apk/debug/app-debug.apk` e pesa > 5 MB (verifica file).
4. File assertions: `supabase/migrations/0001_init.sql` contiene `leagues`, `races`, `predictions`, `race_results` e policy RLS; `ScoringEngine` espone `score(prediction, result, rules): Double`; `scripts/setup-android-env.ps1` esiste ed è idempotente.
5. `README.md` contiene istruzioni Supabase + percorso APK.

## Decisions
- **Pronostici, non fantarosa** (scelta esplicita utente): niente budget/aste; si punta su esiti gara.
- **Supabase come unica fonte di verità** (scelta esplicita): nessun server proprio; RLS = sicurezza lato DB.
- **Motore punteggi client-side in Kotlin**: testabile con unit test JVM (tier 1), zero server functions; punteggi ricalcolati alla lettura (leghe piccole ⇒ performance OK).
- **DI manuale, niente Hilt/Room/KSP**: meno superficie di rottura versioni, build affidabile su macchina senza Android Studio.
- **JDK 21 obbligatorio** per AGP: il JDK 27 di sistema non è usato.
- Config Supabase iniziale via `local.properties` + BuildConfig, override a runtime in-app (niente rebuild per cambiare chiavi).

## Conventions
- Package `com.fantaformula1.app`, architettura `core/` (dominio + punteggi, zero dipendenze Android), `data/` (supabase-kt, repositories), `ui/` (screen + ViewModel per feature).
- UI strings tutte in `strings.xml` (italiano), tema Material 3 custom F1 (rosso/nero/pista).
- Nessuna dipendenza oltre quelle elencate; ogni fase lascia la build verde prima di passare alla successiva.
- Codice commentato in italiano dove non ovvio; messaggi di errore utente in italiano.
- Gate di verifica prima di ogni commit: `testDebugUnitTest` + `lintDebug` + `assembleDebug`.

## Key Context
- Cartella progetto: `C:\Users\marzu\OneDrive\Desktop\fantaformula1` (vuota, greenfield, non un repo git).
- Winget e Chocolatey disponibili; nessun Android SDK/Gradle preinstallati; `dl.google.com` e `services.gradle.org` raggiungibili; 29 GB liberi.
- Shape `rules jsonb` documentata in `README.md` + condivisa tra SQL (validazione minima) e Kotlin (`@Serializable Rules`) — sorgente di verità = classe Kotlin.
- Deadline pronostici imposta da `races.deadline` con enforcement RLS (`now() < deadline`) oltre che client-side.

## Memory Curation
Fase finale: invocare @Librarian con istruzione di estrarre dalla sessione: (a) convention progetto FantaFormula1 (struttura moduli, gate di verifica Gradle), (b) decisioni (Supabase BaaS + RLS, pronostici non rose, motore client-side, JDK21/AGP, DI manuale), (c) context (percorso APK, setup script, schema SQL) — scriverle in memoria progetto con riferimento alla sessione/branch.
