# FantaFormula 1

Lega di **pronostici Formula 1** tra amici: niente rose, niente budget, niente aste.
Ogni giocatore scommette su gare e piloti (vincitore, podio, pole, giro veloce,
testa a testa, DNF, over/under…); chi totalizza più punti alla fine della stagione
vince la lega.

App Android nativa in **Kotlin + Jetpack Compose (Material 3)**, multiplayer su
**Supabase** (Postgres + Row Level Security), motore di punteggio interamente
client-side e testato con unit test JVM.

---

## Installazione su telefono

1. Scarica l'APK dalla cartella `app/build/outputs/apk/debug/app-debug.apk`
   (compilalo con il comando qui sotto se non è già presente).
2. Copia il file sul telefono (USB, cloud, messaggeria…).
3. Tocca il file APK. Android chiederà il permesso di **installare app da origini sconosciute**:
   concedilo per il gestore di file/browser che stai usando, poi conferma l'installazione.
4. Avvia **FantaFormula 1**.

> L'APK di debug usa la firma debug standard di Gradle, quindi è adatto solo
> allo sviluppo/test. La firma debug è locale alla macchina e non è una chiave
> di release. Se hai installato una vecchia build firmata con il keystore
> condiviso ormai rimosso, Android può richiedere una disinstallazione una
> tantum: la nuova build ha un'identità di firma diversa.

### Compilare l'APK in locale

Prerequisiti (una tantum):

```powershell
# JDK 21 (obbligatorio: AGP non funziona con JDK più recenti).
# Il progetto usa kotlin { jvmToolchain(21) }: Gradle seleziona o scarica da
# sola una JDK 21, senza bisogno di org.gradle.java.home in gradle.properties.
# Android SDK: basta eseguire lo script che installa cmdline-tools, SDK 35/36 e scrive local.properties
.\scripts\setup-android-env.ps1
```

Build:

```powershell
.\gradlew.bat clean assembleDebug
# -> app\build\outputs\apk\debug\app-debug.apk
```

Test e lint (gate di verifica prima di ogni commit):

```powershell
.\gradlew.bat testDebugUnitTest lintDebug
```

---

## Configurare Supabase (una tantum)

L'app non ha un server proprio: usa un progetto Supabase come unica fonte di verità.

1. Crea un progetto gratuito su [supabase.com](https://supabase.com).
2. Apri **SQL Editor** ed esegui le migrazioni **in ordine**, una alla volta:
   [`0001_init.sql`](supabase/migrations/0001_init.sql) →
   [`0002_fixes.sql`](supabase/migrations/0002_fixes.sql) →
   [`0003_security.sql`](supabase/migrations/0003_security.sql) →
   [`0004_prediction_validation.sql`](supabase/migrations/0004_prediction_validation.sql) →
   [`0005_prediction_count.sql`](supabase/migrations/0005_prediction_count.sql) →
   [`0006_prediction_bands.sql`](supabase/migrations/0006_prediction_bands.sql) →
   [`0007_over_under_thresholds.sql`](supabase/migrations/0007_over_under_thresholds.sql) →
   [`0008_safety_pit_bands.sql`](supabase/migrations/0008_safety_pit_bands.sql) →
   [`0009_quota.sql`](supabase/migrations/0009_quota.sql) →
   [`0010_quota_dnf.sql`](supabase/migrations/0010_quota_dnf.sql) →
   [`0011_fixes.sql`](supabase/migrations/0011_fixes.sql)
   (FK con cascade per eliminare gare, trigger "ultimo admin" compatibile con
   la cancellazione a cascata, deadline obbligatoria per le nuove gare, RPC
   `remove_league_member`) →
   [`0012_season_history.sql`](supabase/migrations/0012_season_history.sql)
   (tabella `season_snapshots` per lo storico stagioni: solo SELECT membri e
   INSERT admin, nessuna policy di modifica — gli snapshot sono immutabili) →
   [`0013_server_quotes.sql`](supabase/migrations/0013_server_quotes.sql)
   (tabella `race_quotes` + RPC `publish_race_quotes`: quote e soglie dei tipi
   a quota pubblicate dal server, RLS in sola lettura per i membri e trigger
   dei pronostici che rifiuta valori diversi da quelli pubblicati) →
   [`0014_audit_fixes.sql`](supabase/migrations/0014_audit_fixes.sql)
   (cancellazione lega solo admin, `prediction_exists` limitata a se stessi,
   FK composita stagione/lega per gli snapshot e RPC atomica
   `archive_season`) →
   [`0015_security_hardening.sql`](supabase/migrations/0015_security_hardening.sql)
   (codice invito con entropia crittografica, limite nome profilo e limite
   dimensione del JSONB dei pronostici).

   Verifica delle migrazioni: tutte (0001→0015) e i test SQL passano il
   parse del parser Postgres reale (libpg_query via `pglast`), 24/24 file.
   L'applicazione su un **progetto Supabase di prova dedicato** non e' stata
   eseguita: sull'organizzazione e' raggiunto il limite di 2 progetti free
   attivi (FantaFormula1 e FantaCalcio365), quindi per una prova isolata
   serve prima liberare un posto o usare un piano a pagamento. Prima di
   applicare `0013`-`0015` al progetto reale, eseguire le migrazioni in ordine
   nell'SQL Editor come sopra (tutte le nuove sono idempotenti).

   Vengono create tabelle (`profiles`, `leagues`, `league_members`, `seasons`,
   `races`, `race_results`, `predictions`, `race_quotes`), le policy **RLS**,
   le funzioni di business e il trigger di validazione server-side del
   contenuto dei pronostici (fasce obbligatorie, quote congelate in range,
   soglie e, dalla 0013, quote pubblicate).

   Test SQL (predisposti, **non eseguiti**, con self-rollback) in
   `supabase/tests/`: `0004_prediction_validation_tests.sql`,
   `0006_prediction_bands_tests.sql`, `0009_quota_tests.sql`,
   `0010_quota_dnf_tests.sql`, `0011_fixes_tests.sql`,
   `0012_season_history_tests.sql`, `0013_server_quotes_tests.sql`,
   `0014_audit_fixes_tests.sql`, `0015_security_hardening_tests.sql`.
   Si eseguono dall'**SQL Editor** del progetto di prova come script unico:
   il blocco `DO` termina sempre con un'eccezione e annulla la transazione
   (self-rollback), quindi non lasciano dati residui. Alcuni richiedono di
   sostituire gli UUID segnaposto (`<UUID_...>`) con utenti reali del progetto.
3. Da **Authentication → Providers → Email**, disattiva "Confirm email" se vuoi che i
   nuovi giocatori entrino subito senza dover cliccare il link di conferma.
4. Copia **Project URL** e **anon public key** da **Settings → API**.

### Inserire le credenziali nell'app

All'avvio l'app mostra la schermata **Configurazione**: incolla URL e chiave anon.
I valori vengono salvati sul dispositivo (DataStore) e da lì in poi non servono più.

In alternativa, per distribuire un APK già configurato, aggiungi al file
`local.properties` (non è versionato) le credenziali del tuo progetto:

```properties
SUPABASE_URL=https://tuoprogetto.supabase.co
SUPABASE_ANON_KEY=eyJhbGciOi...
```

Poi rigenera l'APK con una clean build (così `BuildConfig` viene aggiornato):

```powershell
.\gradlew.bat clean assembleDebug
# -> app\build\outputs\apk\debug\app-debug.apk
```

All'avvio l'app salterà la schermata di configurazione e arriverà direttamente ad
accesso/registrazione. I valori restano comunque modificabili in-app dalla
schermata **Configurazione** (raggiungibile dal menu in alto nelle leghe).

> Nota: in alternativa ai nomi maiuscoli sopra, la build accetta anche
> `supabase.url` e `supabase.anonKey`.

---

## Giocare con gli amici

1. **Crea una lega** (o unisciti con il codice invito di 6 caratteri).
2. Da *Membri* condividi il codice invito con gli amici.
3. L'**amministratore** costruisce il calendario: nome gara, round, paese,
   flag sprint e **deadline** dei pronostici (formato `2026-03-15 14:00`, ora locale).
4. Ogni giocatore apre la gara e compila i **pronostici** entro la deadline.
   Dopo la deadline i pronostici si chiudono e quelli degli altri diventano visibili.
5. Sempre l'admin inserisce i **risultati** (griglia, posizione, sprint, giro veloce,
   DNF + motivo, penalità, pit stop, safety car + virtual sommate, bandiere rosse,
   giri, meteo/note).
6. La **classifica** si ricalcola da sola a ogni apertura; da *Punti per gara* vedi
   il dettaglio gara per gara. Da *Regole* l'admin può cambiare punti, tipi attivi,
   moltiplicatori, spareggio e opzioni (preset "F1 ufficiale" o "Arcade").

### I pronostici, in breve

| Tipo | Meccanica |
| --- | --- |
| Vincitore / Pole / Giro veloce / Sprint | scelta del pilota |
| Podio | i primi 3 nell'ordine esatto (bonus opzionale per podio senza ordine) |
| Posizione esatta | posizione precisa, con fattore ±1 (e ±2 opzionale) |
| Fascia di posizione | fasce fisse obbligatorie 1-5 / 6-10 / 11-15 / 16-22 |
| Testa a testa | piloti divisi in 3 fasce (ALTA/MEDIA/BASSA) dai dati reali; vale solo se entrambi classificati; quota per difficoltà (pari ×1.2, favorito ×0.6-×0.8, sorpresa fino a ×2.5) |
| Ritiro (DNF) | si/no, con quote a valore atteso (la fascia è 0.5-8.0) |
| Punti pilota over/under | soglia = mediana empirica dei punti/gara dai dati reali ("0.5" = va a punti?), con quota 0.5-3.0 |
| Numero safety car | numero ESATTO di SC+VSC sommate (0, 1, 2, 3, 4, 5, 6+), quota per rarità |
| Fascia pit stop | fascia fissa sui pit stop TOTALI della griglia (0-14 … 66+) |

Per DNF, punti over/under e testa a testa la **quota viene congelata nel
pronostico** al momento dell'invio: le statistiche della stagione cambiano
col tempo, ma i punteggi già assegnati restano per sempre identici.

### Quote lato server (0013)

Quote e soglie dei tipi a quota (**DNF**, **punti over/under**, **testa a
testa**) non sono più calcolate dal telefono: l'admin le pubblica nella tabella
`race_quotes` con la RPC `publish_race_quotes`, e tutti i membri leggono gli
stessi valori. Il trigger server-side rifiuta pronostici con quota o soglia
diverse da quelle pubblicate. Senza quote pubblicate i tre tipi a quota sono
disabilitati (la schermata lo spiega) e **gli altri tipi restano compilabili**.
Le quote si possono ripubblicare finché non esiste **alcun pronostico** per la
gara: dopo il primo invio sono immutabili.

### Sync e statistiche reali

L'app si collega all'API Jolpica/Ergast per il calendario e i risultati reali:
l'admin può sincronizzare gare e risultati (con pit stop reali) invece di
inserirli a mano, e un promemoria arriva 1h prima della deadline.
Le statistiche piloti (media punti, tasso di ritiro, fasce testa a testa)
combinano i risultati della lega con **tutta la stagione reale già disputata**:
i round esistono in una cache locale (DataStore, rinfrescata al massimo ogni
6 ore, solo per i round mancanti, salvato round per round) e
una lega creata a stagione in corso parte con statistiche complete, non a zero.

### Sicurezza

Tutta l' autorizzazione è nel database: le policy **RLS** impediscono a chi non è
membro di leggere i dati della lega, a chi non è admin di scrivere calendario e
risultati, e a chiunque di modificare il pronostico di un altro giocatore o
oltre la deadline.

---

## Aggiornare app e database

Ogni migrazione è idempotente. Per la **0013** l'ordine conta:

1. Aggiorna l'APK **dell'admin** (versione 1.3).
2. Applica `0013_server_quotes.sql` **tra due gare**, quando non ci sono
   pronostici aperti in scadenza.
3. L'admin apre la **prossima gara aperta**: le quote vengono pubblicate
   automaticamente (e si aggiornano dopo la sincronizzazione dei risultati).
4. Applica `0014_audit_fixes.sql` e `0015_security_hardening.sql`, sempre
   dopo aver eseguito i test SQL su un progetto di prova.
5. Solo dopo aggiornano i **membri**.

> ⚠️ Dopo la 0013 i **vecchi client** (che non pubblicano le quote) non possono
> più inviare pronostici a quota: il server li rifiuta finché l'admin non ha
> pubblicato le quote della gara. I pronostici già salvati restano validi e
> vengono punteggiati come prima.

---

## Firma APK e release

### Build debug

`assembleDebug` usa la firma debug standard di Gradle: il keystore resta nella
casa dell'utente (`.android/debug.keystore`) e non viene versionato. Non ci
sono password o file di firma **tracciati** nel repository.

La firma debug è **locale alla macchina**: due PC diversi producono APK debug
con firme diverse. Per distribuire una release usare la procedura sotto, non
un APK debug.

### Build release

La release usa esclusivamente un keystore esterno. I valori si passano tramite
variabili d'ambiente (in locale o da GitHub Actions Secrets):

```powershell
$env:RELEASE_KEYSTORE_PATH = "C:\percorso\sicuro\release.jks"
$env:RELEASE_KEYSTORE_PASSWORD = "<segreto>"
$env:RELEASE_KEY_ALIAS = "<alias>"
$env:RELEASE_KEY_PASSWORD = "<segreto>"

.\gradlew.bat assembleRelease
# -> app\build\outputs\apk\release\app-release.apk
```

Se una variabile manca o il file non esiste, `validateReleaseSigning` blocca
`assembleRelease`/`bundleRelease` con un errore esplicito. **Non** viene usata
la chiave debug come fallback e non viene prodotto un APK release firmato per
errore.

La procedura completa, inclusi i nomi dei secret GitHub e la rotazione della
chiave esposta, è in [`docs/RELEASE_SIGNING.md`](docs/RELEASE_SIGNING.md).

### Nota sul keystore rimosso

Il vecchio keystore condiviso è stato rimosso dall'indice Git in questa
revisione (modifica non ancora committata), ma **resta nella storia del
repository pubblico**. Finché la
cronologia non viene riscritta e la chiave non viene sostituita, chiunque
abbia accesso alla storia può ancora leggerla. La rotazione è una decisione
manuale del proprietario e richiede una reinstallazione degli APK già
distribuiti: Android non accetta un aggiornamento con una firma diversa.
Dettagli e comandi (non eseguiti automaticamente) in
[`docs/RELEASE_SIGNING.md`](docs/RELEASE_SIGNING.md).

L'audit completo è in [`docs/SECURITY_AUDIT.md`](docs/SECURITY_AUDIT.md).

## Architettura

```
app/src/main/java/com/fantaformula1/app/
├── core/            # dominio puro Kotlin (Rules, ScoringEngine, Race…), zero Android
├── data/            # Supabase (supabase-kt), repository dietro interfacce, DTO
│   ├── f1api/       # client Jolpica/Ergast + mapper -> modelli dominio
│   ├── sync/        # sincronizzazione calendario/risultati + cache stagione reale
│   └── ...          # config, notification (promemoria deadline), util
└── ui/              # una cartella per feature: Screen + ViewModel + stato
```

- **`core/domain/ScoringEngine`** calcola punteggi e classifica; è l'unica fonte
  di verità del regolamento ed è coperto da unit test JVM.
- **`core/domain/DriverSeasonStats`** deriva dalle gare reali le statistiche del
  giocatore: media punti shrinkata verso la stima pre-stagione (K=3), soglia
  over/under = mediana empirica, fasce testa a testa 7/8/7, tasso di ritiro.
  **`OverUnderQuotes`** contiene le quote a valore atteso (0.5/p, limitate) e
  le fasce safety car/pit stop: costanti di design fuori da `Rules`.
- **Quote congelate**: il motore ricalcola a ogni lettura, quindi ogni
  valore variabile nel tempo (quota, soglia scelta) viene scritto nel JSONB del
  pronostico all'invio: i punteggi storici non cambiano mai retroattivamente.
- **`leagues.rules`** e **`race_results.result`** sono JSONB: il formato è definito
  dalle classi Kotlin serializzabili (`Rules`, `RaceResultData`), che restano la
  sorgente di verità. Un trigger SQL valida il contenuto dei pronostici a ogni
  scrittura (fasce, quote, soglie ammesse).
- **DI manuale** via `AppContainer`: niente Hilt/KSP, build più affidabile
- I punteggi vengono **ricalcolati a ogni lettura**: con leghe piccole il costo è
  trascurabile e non serve nessuna function server.

### Comandi utili

| Cosa | Comando |
| --- | --- |
| APK debug | `.\gradlew.bat assembleDebug` |
| Test | `.\gradlew.bat testDebugUnitTest` |
| Lint | `.\gradlew.bat lintDebug` |
| Setup ambiente | `.\scripts\setup-android-env.ps1` |
