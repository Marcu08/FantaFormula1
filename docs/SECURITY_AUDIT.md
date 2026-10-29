# FantaFormula 1 - Security hardening e release safety audit

Data: 2026-10-29
Repository: `Marcu08/FantaFormula1`
Branch di lavoro: `security-hardening-audit` (creato dal HEAD `9edba12`, senza
commit, push, reset, clean, rebase o stash)

## 1. Perimetro e regole rispettate

L'audit ha esaminato il codice Android/Kotlin, le configurazioni Gradle, le
migrazioni Supabase/PostgreSQL, i workflow GitHub Actions e la documentazione.

Vincoli rispettati:

- tutte le modifiche locali preesistenti sono state preservate;
- nessun commit, push, merge o release automatica;
- nessuna migrazione applicata a Supabase (né di produzione né di test);
- nessun keystore generato, cancellato, modificato o sostituito;
- nessun segreto, password, token o contenuto di keystore stampato nel report;
- il keystore locale è rimasto sul disco; è stato rimosso solo dal tracking Git.

## 2. Sintesi delle modifiche

### Firma Android

File: `app/build.gradle.kts`

- rimosso il `signingConfigs.shared` che usava il keystore versionato e
  password hardcoded;
- la build **debug** usa `signingConfigs.debug` (firma debug standard di
  Gradle, keystore locale, non versionato);
- la build **release** usa un signing config creato solo se sono presenti tutte
  le variabili d'ambiente:
  `RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
  `RELEASE_KEY_PASSWORD`;
- il task `validateReleaseSigning` fa fallire `assembleRelease`,
  `bundleRelease` e `packageRelease` se la configurazione manca;
- nessun fallback alla chiave debug.

File: `.gitignore`

Aggiunte le esclusioni per `*.keystore`, `*.jks`, `*.p12`,
`keystore.properties`, `signing.properties`, `release-signing.properties`,
`.env` e `.env.*`.

File: `app/fantaformula1.keystore`

Rimosso dal tracking con `git rm --cached` (operazione non distruttiva: il file
locale è ancora presente). La rimozione è **staged**, non committata. Il file
resta nella storia Git precedente, introdotto dal commit `a8a8b13`.

File: `README.md`, `docs/RELEASE_SIGNING.md`

Documentazione separata per debug e release, variabili d'ambiente, secret
GitHub, rotazione della chiave e procedura manuale di rimozione dalla storia.
La vecchia sezione "debito di sicurezza noto" è stata sostituita.

### CI

File: `.github/workflows/android.yml`

- il job esistente continua a eseguire
  `testDebugUnitTest lintDebug assembleDebug checkNoCjkCharacters`;
- aggiunto il job `secret-scan` con `gitleaks/gitleaks-action@v2`, checkout con
  storia completa e `GITHUB_TOKEN` del repository;
- aggiunti permessi minimi (`contents: read`, `pull-requests: read`);
- documentato che per un'organizzazione può essere necessaria la licenza
  Gitleaks (`GITLEAKS_LICENSE` come secret), mentre per un repository personale
  non è richiesta.

### Supabase / PostgreSQL

File nuovi:

- `supabase/migrations/0015_security_hardening.sql`
- `supabase/tests/0015_security_hardening_tests.sql`

Contenuto della 0015:

- `generate_invite_code()` usa ora entropia crittografica (`gen_random_uuid()`)
  invece di `random()`, con controllo collisioni globale;
- vincolo `profiles_display_name_length` (1-60 caratteri dopo `btrim`), aggiunto
  come `NOT VALID` per non bloccare righe storiche;
- trigger `on_prediction_size` che rifiuta JSONB di pronostico oltre 128 KiB.

La 0014 preesistente è stata verificata e mantenuta; il suo blocco per
`seasons_id_league_unique` è idempotente e non droppa la unique usata dalla FK
`races_season_league_fk`.

## 3. Vulnerabilità e classificazione

### 3.1 Keystore condiviso nel repository pubblico (Critico)

**Stato: MITIGATO, VERIFICA MANUALE NECESSARIA**

Il keystore era tracciato, con configurazione di firma condivisa tra debug e
release. Chiunque abbia accesso al repository o alla sua storia può produrre un
APK con la stessa identità dell'app.

Azioni eseguite:

- configurazione condivisa rimossa;
- file rimosso dal tracking corrente (non dal disco);
- debug e release separate;
- release fail-closed se manca il keystore esterno;
- `.gitignore` aggiornato;
- procedura di rotazione e di history rewrite documentata.

Cosa resta manuale:

- decidere se e come ruotare la chiave;
- verificare il metodo di distribuzione effettivo (sideload, CI, Play);
- verificare l'eventuale Google Play App Signing;
- riscrivere la storia Git con backup e force-push coordinato;
- comunicare agli utenti l'eventuale reinstallazione.

Non è possibile dichiarare il rischio chiuso finché la chiave nella storia non
è stata ruotata o la storia non è stata riscritta e propagata.

### 3.2 Chiave debug usata per le release / fallback silenzioso (Alto)

**Stato: RISOLTO E VERIFICATO**

La configurazione release non usa più la chiave debug. La verifica eseguita
senza variabili d'ambiente ha prodotto il fallimento esplicito di
`validateReleaseSigning` e di `assembleRelease`, senza output APK release.

La firma di una release **con** un keystore esterno non è stata eseguita: non
era disponibile un keystore di release e non ne è stato creato uno.

### 3.3 Password/segreti hardcoded nella configurazione di firma (Alto)

**Stato: RISOLTO E VERIFICATO**

Le credenziali hardcoded sono state rimosse dal file Gradle corrente. La
scansione dei file testuali versionati/candidati non ha trovato chiavi private
in chiaro, token Supabase privilegiati o password reali; i match residui sono
testi di documentazione o placeholder. Il keystore binario locale è il rischio
noto del punto 3.1, non un segreto testuale. La chiave Supabase `anon` è
trattata come credenziale client pubblica: l'autorizzazione effettiva deve
restare nelle policy RLS, non nel segreto della chiave. La cronologia contiene
ancora la vecchia configurazione e il keystore.

### 3.4 Segreti in CI (Medio)

**Stato: MITIGATO, VERIFICA MANUALE NECESSARIA**

Il workflow ora include Gitleaks. La configurazione YAML e il file
`.gitleaks.toml` sono stati validati con un parser. Lo scanner Gitleaks è stato
eseguito localmente sulla storia completa (75 commit): **nessun leak testuale
trovato**. Il job GitHub Actions non è stato eseguito in questa macchina; la
licenza Gitleaks per organizzazioni e l'eventuale attivazione di GitHub Secret
Scanning restano passaggi manuali.

### 3.5 RLS e isolamento multiutente Supabase (Alto)

**Stato: MITIGATO, VERIFICA MANUALE NECESSARIA**

Revisione statica delle migrazioni 0001-0015:

- accesso a leghe, membri, stagioni, gare, risultati, pronostici, quote e
  snapshot limitato ai membri della lega;
- scritture di calendario/risultati/regole riservate agli admin;
- pronostici leggibili dagli altri solo dopo la deadline (o secondo la regola
  della lega) e modificabili solo dal proprietario;
- `prediction_exists` (0014) non rivela più l'esistenza di pronostici altrui;
- cancellazione lega (0014) riservata a un admin attuale, non al creatore
  retrocesso;
- snapshot di stagione (0014) con FK composita stagione/lega;
- `archive_season` atomica;
- `race_quotes` in sola lettura per i membri, scrittura solo via RPC admin;
- funzioni `SECURITY DEFINER` con `search_path = public` e grant espliciti.

Limite: non è stato possibile eseguire le migrazioni su un progetto Supabase
di test né eseguire i test SQL integrati. La verifica runtime di RLS e funzioni
resta da fare.

### 3.6 Codice invito prevedibile (Medio/Basso)

**Stato: MITIGATO, VERIFICA MANUALE NECESSARIA**

La 0015 sostituisce il PRNG di sessione con `gen_random_uuid()` e mantiene il
formato a 6 caratteri compatibile con client e schema. La funzione viene
eseguita come `SECURITY DEFINER` per il collision check globale.

Resta un rischio residuo separato: `join_league_by_code` non ha rate limiting
applicativo e il codice di 6 caratteri ha 30 bit di entropia. Non è stata
introdotta una tabella di tentativi/rate-limit perché richiederebbe una scelta
di prodotto e una nuova superficie di stato.

**Sotto-punto rate limiting: NON RISOLTO** (hardening futuro, non automatizzato
in questa revisione).

### 3.7 Input non limitati (Basso)

**Stato: MITIGATO, VERIFICA MANUALE NECESSARIA**

La 0015 limita nome profilo e dimensione del JSONB dei pronostici. I limiti sono
additivi e non riscrivono righe storiche. Non sono stati eseguiti test runtime.

### 3.8 Isolamento account, cache, bozze, coda offline e worker (Alto)

**Stato: MITIGATO, VERIFICA MANUALE NECESSARIA**

Il codice corrente usa namespace per utente per cache offline, bozze e coda;
il logout cancella i dati locali; i worker verificano l'utente proprietario
prima di salvare/notificare; il repository di salvataggio accetta un
`expectedUserId` e la RLS rifiuta comunque `user_id` diversi da `auth.uid()`.

Sono presenti test JVM per `LocalUserScope`, serializzazione coda, appartenenza
delle voci e logica dei promemoria. Durante l'audit è stato corretto un caso
concreto: l'enqueue di una voce per una gara poteva rimuovere la voce della
stessa gara appartenente a un altro utente; ora la sostituzione è limitata
all'utente corrente (`upsertPendingWriteForUser`) e c'è un test di regressione.
La verifica su device reale (cambio account durante worker/notifica, processo
ucciso, più account sullo stesso profilo) non è stata eseguita.

### 3.9 Token e dati locali (Medio)

**Stato: MITIGATO, VERIFICA MANUALE NECESSARIA**

La sessione Supabase è gestita dal client e conservata nell'area privata
dell'app; `android:allowBackup="false"` evita il backup automatico dei dati
locali. Non è stata aggiunta crittografia artificiale né una nuova dipendenza.
Un dispositivo con root o un backup manuale dell'area privata non è coperto da
queste misure.

### 3.10 Logging e messaggi di errore (Basso)

**Stato: RISOLTO E VERIFICATO**

Non risultano chiamate a `Log.*`, `println`, `printStackTrace` o `System.out`
nel codice applicativo. Gli errori mostrati all'utente sono messaggi mappati;
non includono chiavi o token.

### 3.11 Manifest, deep link, traffico di rete (Medio)

**Stato: RISOLTO E VERIFICATO**

- unica activity esportata: launcher `MainActivity`;
- nessun deep link o intent-filter aggiuntivo;
- `allowBackup=false`;
- `usesCleartextTraffic=false` esplicito (oltre al default di targetSdk 35);
- gli URL Supabase sono validati come `https` e rifiutano userinfo/query/fragment.

Non è stata eseguita una scansione dinamica del traffico di rete.

### 3.12 Dipendenze vulnerabili (Medio)

**Stato: NON VERIFICABILE NELL'AMBIENTE DISPONIBILE**

Non è stato eseguito un vulnerability scanner sulle dipendenze Gradle/Maven
(es. OWASP Dependency-Check o Gradle dependency analysis). Non sono state
aggiunte dipendenze o servizi a pagamento.

### 3.13 Scansione della cronologia Git (Alto)

**Stato: NON RISOLTO**

La scansione dei nomi di file in tutta la storia ha trovato come file sensibile
solo `app/fantaformula1.keystore`. Gitleaks è stato eseguito sulla storia
completa (75 commit) con redazione dei valori: nessun leak testuale trovato.
Questo non rileva il contenuto binario del keystore, che resta il rischio
principale e richiede la procedura manuale descritta in
`docs/RELEASE_SIGNING.md`.

## 4. File modificati in questa revisione

- `app/build.gradle.kts`: separazione debug/release, validazione fail-closed.
- `.gitignore`: esclusione file di firma e segreti locali.
- `app/src/main/AndroidManifest.xml`: `usesCleartextTraffic=false` esplicito.
- `app/src/main/java/.../data/offline/PendingWritesStore.kt`: l'enqueue
  sostituisce solo la voce della stessa gara dell'utente corrente.
- `app/src/test/java/.../data/offline/PendingWritesSerializationTest.kt`:
  regressione sulla coda di un altro account.
- `app/fantaformula1.keystore`: rimosso dall'indice Git (file locale intatto).
- `.github/workflows/android.yml`: job Gitleaks + permessi minimi.
- `.gitleaks.toml`: estensione della config default e allowlist dei soli
  segnaposto documentali.
- `README.md`: istruzioni debug/release e nota sulla chiave esposta.
- `.opencode/knowledge/decisions.md`: decisioni di hardening registrate.
- `.opencode/knowledge/known-issues.md`: rischi residui e stato delle migrazioni.
- `docs/RELEASE_SIGNING.md`: procedura release, rotazione, history rewrite.
- `docs/SECURITY_AUDIT.md`: questo report.
- `supabase/migrations/0015_security_hardening.sql`: hardening additivo.
- `supabase/tests/0015_security_hardening_tests.sql`: test SQL self-rollback.

Le modifiche preesistenti (0013/0014, cache per utente, worker, ecc.) sono state
preservate.

## 5. Verifiche eseguite

| Verifica | Comando | Esito |
| --- | --- | --- |
| Test unitari | `.\gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon` | 57 suite, 520 test, 0 failure, 0 error, 0 skipped |
| Lint | `.\gradlew.bat lintDebug` | 0 error, 104 warning, 1 information |
| Guardia CJK | `.\gradlew.bat checkNoCjkCharacters` | PASS |
| Build debug | `.\gradlew.bat assembleDebug` | BUILD SUCCESSFUL |
| Firma debug | `apksigner verify --print-certs app-debug.apk` + confronto con `~/.android/debug.keystore` | exit 0; certificato `CN=Android Debug`; SHA-256 uguale al keystore debug standard |
| Release senza segreti | `.\gradlew.bat validateReleaseSigning` | fallimento esplicito atteso |
| Release senza segreti | `.\gradlew.bat assembleRelease` | fallimento esplicito atteso; nessun APK release prodotto |
| Sintassi SQL | parser `pglast` su migrazioni + test | 24/24 file |
| Sintassi workflow | parser YAML | `android.yml` valido |
| Gitleaks | `gitleaks detect --source . --config .gitleaks.toml --redact` | 75 commit, nessun leak testuale |
| Scansione segreti locale | script euristico su file testuali tracked + untracked | solo placeholder/documentazione; nessuna chiave privata o service-role nei file testuali (il keystore binario è trattato al punto 3.1) |

Non eseguiti:

- test SQL integrati su PostgreSQL/Supabase;
- applicazione delle migrazioni 0013-0015;
- build release firmata con keystore reale;
- job GitHub Actions completo (Gitleaks è stato eseguito localmente, non dalla CI);
- scansione CVE delle dipendenze;
- test su device/emulatore;
- history rewrite e rotazione della chiave.

## 6. Procedura manuale richiesta

### 6.1 Prima del commit

1. Esaminare `git diff` e `git diff --cached` (la rimozione del keystore è
   staged).
2. Verificare che `app/fantaformula1.keystore` compaia come `D` nell'indice e
   non venga aggiunto al commit.
3. Non committare il file locale e non forzare l'aggiunta con `git add -f`.
4. Decidere se la modifica alla firma debug è accettabile per gli utenti
   esistenti: la nuova build debug standard ha una firma diversa dal vecchio
   keystore condiviso.

### 6.2 Rotazione della chiave

1. Verificare se l'APK distribuito è firmato con la chiave esposta e se è
   presente Google Play App Signing.
2. Creare un nuovo keystore fuori dal repository.
3. Conservarlo in un password manager o secret store CI.
4. Firmare una build di prova e verificare l'impronta del certificato.
5. Coordinare la reinstallazione degli utenti, se la firma cambia.
6. Solo dopo la verifica, considerare la vecchia chiave compromessa.

### 6.3 Rimozione dalla storia Git

Eseguire su un clone di backup, con `git filter-repo` o BFG, poi:
`git push --force-with-lease`. Non è stato eseguito automaticamente e richiede
coordinamento con tutti i collaboratori. La rotazione della chiave resta
necessaria anche dopo la riscrittura.

### 6.4 Supabase

1. Creare o usare un progetto Supabase di test isolato.
2. Applicare le migrazioni `0001` -> `0015` in ordine.
3. Eseguire i test SQL in `supabase/tests/`, sostituendo gli UUID segnaposto.
4. Verificare con utenti reali: membro/non membro, admin/creatore retrocesso,
   deadline, quote pubblicate, snapshot di stagione, cancellazione lega.
5. Solo dopo, applicare in produzione con backup e finestra concordata.
6. Non usare `supabase db push` sul progetto reale senza revisione.

### 6.5 GitHub

1. Configurare eventuali secret per una release CI (`RELEASE_KEYSTORE_BASE64`,
   password, alias).
2. Se il repository è di un'organizzazione, aggiungere `GITLEAKS_LICENSE`.
3. Abilitare GitHub Secret Scanning e branch protection.
4. Eseguire il workflow su un branch di prova e verificare che il job
   `secret-scan` non trovi la vecchia chiave (il file binario non è testo, ma
   eventuali copie o configurazioni sì).

## 7. Checklist per una release futura

- [ ] `git status` pulito o modifiche revisionate.
- [ ] `app/fantaformula1.keystore` non tracciato.
- [ ] Nessun segreto in `git diff`.
- [ ] `validateReleaseSigning` passa con il keystore esterno.
- [ ] `assembleRelease` produce un APK firmato con il certificato atteso.
- [ ] `versionCode`/`versionName` incrementati.
- [ ] Migrazioni applicate prima dell'APK, nell'ordine documentato.
- [ ] Test SQL eseguiti su progetto di test.
- [ ] Firma compatibile con le installazioni esistenti o migrazione comunicata.
- [ ] CI verde: test, lint, assembleDebug, CJK, Gitleaks.
- [ ] Nessun artifact release pubblico senza decisione esplicita.

## 8. Rischi residui principali

1. La vecchia chiave è ancora nella storia pubblica e non è stata ruotata.
2. La storia Git non è stata riscritta e non è stato eseguito un force-push.
3. Le migrazioni 0013-0015 non sono state applicate né testate runtime.
4. `join_league_by_code` non ha rate limiting.
5. La firma debug standard può rompere l'aggiornamento delle vecchie
   installazioni firmate con il keystore condiviso.
6. Non è stata eseguita una scansione CVE delle dipendenze.
7. I test su device reale per cambio account/worker/notifiche non sono stati
   eseguiti.
