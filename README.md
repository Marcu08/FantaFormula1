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

> L'APK di debug è firmato con la chiave di debug locale: se in futuro ricevi una
> versione diversa con un'altra firma, Android chiederà prima di disinstallare la precedente.

### Compilare l'APK in locale

Prerequisiti (una tantum):

```powershell
# JDK 21 (obbligatorio: AGP non funziona con JDK più recenti)
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
   `remove_league_member`).
   Vengono create tabelle (`profiles`, `leagues`, `league_members`, `seasons`,
   `races`, `race_results`, `predictions`), le policy **RLS**, le funzioni di
   business e il trigger di validazione server-side del contenuto dei
   pronostici (fasce obbligatorie, quote congelate in range, soglie).
   I test predisposti (non eseguiti, con self-rollback) sono in `supabase/tests/`.
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
