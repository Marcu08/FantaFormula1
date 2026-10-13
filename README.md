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
2. Apri **SQL Editor**, incolla tutto il contenuto di
   [`supabase/migrations/0001_init.sql`](supabase/migrations/0001_init.sql) ed eseguilo.
   Vengono create tabelle (`profiles`, `leagues`, `league_members`, `seasons`,
   `races`, `race_results`, `predictions`), le policy **RLS** e le funzioni di business.
3. Esegui anche [`supabase/migrations/0002_fixes.sql`](supabase/migrations/0002_fixes.sql):
   corregge i bug lato DB (deadline, stato gare, ultimo admin, scritture atomiche
   dei risultati, integrità referenziale tra leghe).
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
   DNF + motivo, penalità, pit stop, safety car, bandiere rosse, giri, meteo/note).
6. La **classifica** si ricalcola da sola a ogni apertura; da *Punti per gara* vedi
   il dettaglio gara per gara. Da *Regole* l'admin può cambiare punti, tipi attivi,
   moltiplicatori, spareggio e opzioni (preset "F1 ufficiale" o "Arcade").

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
└── ui/              # una cartella per feature: Screen + ViewModel + stato
```

- **`core/domain/ScoringEngine`** calcola punteggi e classifica; è l'unica fonte
  di verità del regolamento ed è coperto da 100+ unit test JVM.
- **`leagues.rules`** e **`race_results.result`** sono JSONB: il formato è definito
  dalle classi Kotlin serializzabili (`Rules`, `RaceResultData`), che restano la
  sorgente di verità.
- **DI manuale** via `AppContainer`: niente Hilt/KSP, build più affidabile.
- I punteggi vengono **ricalcolati a ogni lettura**: con leghe piccole il costo è
  trascurabile e non serve nessuna function server.

### Comandi utili

| Cosa | Comando |
| --- | --- |
| APK debug | `.\gradlew.bat assembleDebug` |
| Test | `.\gradlew.bat testDebugUnitTest` |
| Lint | `.\gradlew.bat lintDebug` |
| Setup ambiente | `.\scripts\setup-android-env.ps1` |
