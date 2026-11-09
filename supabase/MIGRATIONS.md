# Ordine delle migrazioni Supabase (P4)

Sequenza canonica del branch `fix/season-snapshot-integrity`:

| Versione | File | Origine |
| --- | --- | --- |
| `0001`–`0012` | `0001_init.sql` … `0012_season_history.sql` | `main` |
| `0013`–`0020` | `0013_server_quotes.sql` … `0020_prediction_immutable_keys.sql` | branch di sviluppo |
| `20261109200000` | `20261109200000_season_snapshot_integrity.sql` | questo branch |

La migrazione di integrità usa un timestamp **successivo** a tutte le
migrazioni note (`0013`–`0020`) e alla precedente versione
`20261109120000`. In questo modo viene sempre applicata per ultima e
`0014_audit_fixes.sql` non può sostituire la versione più restrittiva di
`archive_season`.

## Scenari verificati con Supabase CLI 2.114.0

| Scenario | Stato del database | Comando | Esito |
| --- | --- | --- | --- |
| A | vuoto, solo migrazioni `main` | `supabase db push` | applica `0001`–`0012` + integrità |
| B | vuoto, sviluppo completo | `supabase db push` | applica `0001`–`0020` + integrità (ultima) |
| C | già a `0020` | `supabase db push` | applica solo l'integrità |
| D | inizializzato da `main`, poi merge sviluppo | `supabase db push` | applica `0013`–`0020` + integrità (ultima) |
| E | live con `0013`–`0020` già applicate | `supabase db push` | applica solo l'integrità |

## Ambienti che richiedono una riparazione esplicita

Solo un database che abbia **già registrato la vecchia versione
`20261109120000`** (per esempio un ambiente locale usato per provare il
primo commit di remediation) e che successivamente riceva le migrazioni
`0013`–`0020` richiede la riparazione della cronologia:

```bash
supabase migration repair --status reverted 20261109120000 --db-url "$DATABASE_URL"
supabase db push --db-url "$DATABASE_URL"
```

Senza `migration repair`, la CLI rifiuta il push perché la versione remota
non esiste più tra i file locali. La riparazione è stata verificata:
dopo il repair il push applica `0013`–`0020` e poi
`20261109200000_season_snapshot_integrity.sql`, che ripristina la funzione
hardened.

Il progetto Supabase live non richiede questa riparazione: non ha mai
ricevuto `20261109120000`, perché il branch non è stato distribuito.

Non applicare `20261109200000` a un database `main`-only prima di aver
mergiato `0013`–`0020`: la CLI, quando in seguito aggiunge quelle versioni
più vecchie, richiede `--include-all` e le applicherebbe dopo l'integrità.
Se questo è già successo in un ambiente di prova, dopo il merge eseguire:

```bash
supabase db push --include-all --db-url "$DATABASE_URL"
psql "$DATABASE_URL" -f supabase/migrations/20261109200000_season_snapshot_integrity.sql
```

La seconda riga è sicura perché la migrazione è idempotente e riporta
`archive_season` e i grant allo stato hardened. Gli ambienti reali non sono
in questo stato: il branch non è mai stato distribuito.

## Regole operative

- Non applicare manualmente i file in ordine diverso da quello dei filename.
- Non usare `--include-all` come scorciatoia generale: serve solo nel caso
  di cronologia da riparare descritto sopra.
- La migrazione di integrità è idempotente e può essere rieseguita in caso
  di emergenza, ma la cronologia va comunque allineata con `migration repair`.
- Dopo ogni push verificare che `archive_season` contenga la validazione
  hardened (per esempio la stringa `userId duplicati`) e che
  `authenticated` non abbia `UPDATE` su `seasons.is_active`.
