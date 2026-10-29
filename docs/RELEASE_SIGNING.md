# Firma debug e release

Questo documento descrive la configurazione attuale e i passaggi manuali che
richiedono una decisione del proprietario. Non contiene chiavi, password o
altri segreti.

## 1. Stato della configurazione

- **Debug**: usa la firma debug standard di Gradle (`signingConfigs.debug`).
  Il keystore è locale (`~/.android/debug.keystore` su Linux/macOS,
  `%USERPROFILE%\.android\debug.keystore` su Windows) e non è versionato.
- **Release**: usa solo un keystore esterno. Il percorso e le password arrivano
  da variabili d'ambiente. Se la configurazione manca, il task
  `validateReleaseSigning` fa fallire `assembleRelease`, `bundleRelease` e
  `packageRelease`.
- **Nessun fallback**: la chiave debug non viene mai usata per una release.
- **Keystore rimosso dal tracking**: `app/fantaformula1.keystore` non è più
  nell'indice Git di questa revisione. Il file locale non è stato cancellato;
  la storia Git precedente lo contiene ancora (commit `a8a8b13`).

## 2. Build release locale

Prerequisiti:

1. Un keystore creato **fuori** dal repository e conservato in un password
   manager o in un secret store CI.
2. Alias e password conservati separatamente dal repository.

PowerShell:

```powershell
$env:RELEASE_KEYSTORE_PATH = "C:\percorso\sicuro\release.jks"
$env:RELEASE_KEYSTORE_PASSWORD = "<inserire-a-runtime>"
$env:RELEASE_KEY_ALIAS = "<alias>"
$env:RELEASE_KEY_PASSWORD = "<inserire-a-runtime>"

.\gradlew.bat validateReleaseSigning
.\gradlew.bat assembleRelease
```

Il percorso può essere assoluto oppure relativo alla radice del repository.
Non inserire i valori in `gradle.properties`, in `local.properties`, in
`app/build.gradle.kts` o in file versionati.

Se manca anche una sola variabile, oppure il file indicato non esiste, il
build si ferma con un messaggio che elenca solo i **nomi** delle variabili
mancanti.

## 3. GitHub Actions (solo se serve una release da CI)

Il workflow attuale costruisce e verifica la **debug**; non pubblica release.
Per una release manuale da CI si possono creare questi secret:

- `RELEASE_KEYSTORE_BASE64` — keystore codificato in base64, non il file.
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Il job deve decodificare il keystore in `$RUNNER_TEMP`, impostare
`RELEASE_KEYSTORE_PATH` a quel file e passare gli altri tre valori come
variabili d'ambiente. Non usare `pull_request` per un job che legge questi
secret: i secret non sono disponibili alle PR provenienti da fork e il job
fallirebbe o esporrebbe la configurazione in modo errato. Usare
`workflow_dispatch` o un branch protetto.

Esempio di passaggio (da adattare, non è un workflow completo):

```yaml
- name: Decodifica keystore release
  if: github.event_name == 'workflow_dispatch'
  shell: bash
  run: |
    echo "${{ secrets.RELEASE_KEYSTORE_BASE64 }}" | base64 --decode > "$RUNNER_TEMP/release.jks"
    echo "RELEASE_KEYSTORE_PATH=$RUNNER_TEMP/release.jks" >> "$GITHUB_ENV"

- name: Build release firmata
  if: github.event_name == 'workflow_dispatch'
  env:
    RELEASE_KEYSTORE_PASSWORD: ${{ secrets.RELEASE_KEYSTORE_PASSWORD }}
    RELEASE_KEY_ALIAS: ${{ secrets.RELEASE_KEY_ALIAS }}
    RELEASE_KEY_PASSWORD: ${{ secrets.RELEASE_KEY_PASSWORD }}
  run: ./gradlew validateReleaseSigning assembleRelease
```

Non caricare l'APK release come artifact pubblico senza una decisione
esplicita: un artifact scaricabile può essere redistribuito.

## 4. Rotazione della chiave esposta

La chiave precedente era un keystore condiviso, tracciato in un repository
pubblico. **Non è stata ruotata automaticamente**: una rotazione cambia
l'identità di firma dell'app e può impedire l'aggiornamento delle installazioni
esistenti.

Prima di ruotare, verificare manualmente:

1. **Come è stata distribuita l'app**: APK sideload, link diretto, CI,
   marketplace interno o Google Play.
2. **Quale firma hanno gli APK installati**: usare `apksigner verify
   --print-certs` su un APK noto e confrontare l'impronta del certificato. Non
   pubblicare il keystore.
3. **Google Play App Signing**: se è attivo, la chiave di upload è separata
   dalla chiave di firma dell'app; seguire la procedura Play Console per
   caricare una nuova upload key. Se non è attivo, la chiave di firma è quella
   distribuita e va gestita la migrazione.
4. **Comunicazione agli utenti**: se la firma cambia, Android richiede una
   disinstallazione/reinstallazione (o una migrazione firmata con la vecchia
   chiave, quando tecnicamente possibile). Coordinare il passaggio prima di
   pubblicare.

Procedura consigliata, da eseguire solo dopo la verifica:

1. Creare un nuovo keystore fuori dal repository, con password robuste generate
   dal password manager.
2. Conservare keystore, alias e password in un secret store; non inviarli via
   chat o email.
3. Configurare la release con le variabili d'ambiente di questa guida.
4. Firmare una build di prova e verificare l'impronta del certificato con
   `apksigner verify --print-certs`.
5. Distribuire la nuova versione con la procedura di migrazione concordata.
6. Solo dopo aver verificato l'installazione, considerare la vecchia chiave
   compromessa e inutilizzabile.

## 5. Rimozione dalla storia Git

La rimozione dall'indice non cancella il file dalla storia. Per rimuoverlo dalla
storia serve una riscrittura, **non eseguita da questo audit**:

```bash
# Eseguire su un clone di backup, non sul repository di lavoro con modifiche.
git filter-repo --path app/fantaformula1.keystore --invert-paths
```

`git filter-repo` non è incluso in Git: va installato separatamente. In
alternativa si può usare BFG Repo-Cleaner. Dopo la riscrittura:

- tutti i collaboratori devono ri-clonare o riallineare il repository;
- il push richiede `--force-with-lease` (o `--force`) e va coordinato;
- i commit e i link precedenti cambiano hash;
- eventuali cache, fork, artifact e log di terze parti possono conservare il
  file: la rimozione dalla storia non è una garanzia di cancellazione globale.

La rotazione della chiave è più importante della sola rimozione dalla storia:
una chiave nota e mai ruotata resta utilizzabile anche se il file non è più
nell'ultimo commit.

## 6. Checklist prima di una release

- [ ] Il keystore release è fuori dal repository e non è ignorato per errore
      insieme a un file che deve essere versionato.
- [ ] Le variabili d'ambiente sono impostate nel processo di build, non scritte
      in file versionati.
- [ ] `validateReleaseSigning` passa.
- [ ] `assembleRelease` produce un APK firmato con il certificato atteso
      (`apksigner verify --print-certs`).
- [ ] La versione (`versionCode`/`versionName`) è stata incrementata.
- [ ] La firma è compatibile con le installazioni esistenti, oppure la
      migrazione/reinstallazione è stata comunicata.
- [ ] Il workflow CI non stampa variabili d'ambiente o contenuti del keystore.
- [ ] `app/fantaformula1.keystore` non compare in `git status` come file
      tracciato e non verrà aggiunto al prossimo commit.
