# Migrazioni Supabase e sicurezza dell'archiviazione stagioni

## Sequenza canonica

| Versione | File | Ruolo |
| --- | --- | --- |
| `0001`–`0012` | `0001_init.sql` … `0012_season_history.sql` | schema `main` |
| `0013`–`0020` | `0013_server_quotes.sql` … `0020_prediction_immutable_keys.sql` | migrazioni sviluppo |
| `20261109200000` | `20261109200000_season_snapshot_integrity.sql` | prima remediation: RPC atomica + FK composita |
| `20261109210000` | `20261109210000_season_snapshot_deployment_guard.sql` | **obbligatoria**: riasserzione, ACL complete, FK RESTRICT, guard DDL |
| `20261109220000` | `20261109220000_season_archive_recovery.sql` | recupero esplicito degli archivi legacy parziali |

`20261109200000` da sola **non è sufficiente**: la chiusura fail-closed è
`20261109210000`, che va applicata su ogni ambiente. `20261109220000`
introduce il recupero auditato degli stati parziali.

## Perché esiste `20261109210000`

La vecchia `0014_audit_fixes.sql` contiene una `CREATE OR REPLACE FUNCTION
public.archive_season(...)` con una versione precedente e più debole. Se una
banca dati ha già applicato `20261109200000` e in seguito riceve `0013`–`0020`,
la CLI può applicare `0014` dopo la funzione hardened. Questo è il caso A→D:

1. applicare `0001`–`0012`;
2. applicare `20261109200000`;
3. introdurre `0013`–`0020`;
4. eseguire `supabase db push`.

`20261109210000` risolve in due modi:

- se non è ancora stata applicata, viene applicata dopo `0013`–`0020` e
  riasserisce la funzione hardened;
- se è già stata applicata, installa un **DDL event trigger** che rifiuta
  qualunque `CREATE OR REPLACE FUNCTION public.archive_season(...)` priva dei
  marcatori di hardening. `0014` fallisce e la funzione hardened resta attiva.
  Il deploy si ferma: è un fail-closed, non un degrado silenzioso.

Il guard controlla i marcatori della funzione (duplicati utenti/posizioni,
posizioni contigue, valori negativi, stagione attiva, `FOR UPDATE`, nome
snapshot preso dal database) e rifiuta anche l'uso di `p_name` per il nome
dello snapshot.

## Scenari

La CLI 2.114.0 è stata verificata localmente, non contro il progetto live.
Prima di ogni scenario: backup del database e ruolo di migrazione owner delle
tabelle/funzioni; `CREATE EVENT TRIGGER` richiede superuser (Supabase:
`postgres`).

### A — database vuoto, solo main

`supabase db push` applica `0001`–`0012`, `20261109200000`,
`20261109210000`, `20261109220000`. Eseguire il post-deploy verifier.

### B — database vuoto, sviluppo completo

`supabase db push` applica `0001`–`0020`, poi `20261109200000`,
`20261109210000`, `20261109220000` (nell'ordine dei filename). Il guard viene
installato per ultimo.

### C — database già a `0020`

`supabase db push` applica solo `20261109200000`, `20261109210000`,
`20261109220000`.

### D — main-first, poi merge sviluppo

Se il database è ancora a `0001`–`0012` (senza `20261109200000`), il push
applica `0013`–`0020` e poi le tre migrazioni di integrità: è sicuro.

Se `20261109200000` è già applicata ma `20261109210000` non lo è, il push con
`--include-all` applica `0013`–`0020` e poi `20261109210000` e
`20261109220000`; la funzione viene riasserita. È sicuro, ma richiede
`--include-all` perché le versioni `0013`–`0020` sono precedenti all'ultima
versione remota.

Se **anche** `20261109210000` è già applicata, il guard blocca `0014` e il push
fallisce. La funzione hardened resta attiva. Procedura di riparazione:

```bash
# 1. Backup e verifica stato
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 \
  -f supabase/tests/verify_archive_season_hardening.sql

# 2. Disabilitare temporaneamente il guard (solo ruolo owner/superuser)
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c \
  'alter event trigger archive_season_hardening_guard disable;'

# 3. Applicare le migrazioni sviluppo bloccate
supabase db push --include-all --db-url "$DATABASE_URL"

# 4. Riasserire funzione+ACL+FK e ricreare il guard, in una transazione
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -1 \
  -f supabase/migrations/20261109210000_season_snapshot_deployment_guard.sql

# 5. Verifica finale
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 \
  -f supabase/tests/verify_archive_season_hardening.sql
```

Il passo 2 non è un rollback SQL: disabilita solo il controllo finché le
vecchie migrazioni sviluppo vengono registrate. Il passo 4 è obbligatorio e
riporta la funzione, le ACL, le FK RESTRICT e il guard allo stato hardened.
Se il passo 4 fallisce, la transazione `-1` viene annullata: non considerare
l'ambiente sicuro e ripristinare il backup.

### E — live con `0013`–`0020` già applicate

`supabase db push` applica solo `20261109200000`, `20261109210000`,
`20261109220000`. È lo stesso stato remoto dello scenario C.

### Vecchia versione `20261109120000` già registrata

Se un ambiente ha applicato la vecchia versione della prima remediation:

```bash
supabase migration repair --status reverted 20261109120000 --db-url "$DATABASE_URL"
supabase db push --db-url "$DATABASE_URL"
```

`migration repair` modifica **solo la cronologia** (`supabase_migrations.
schema_migrations`): non annulla funzioni, policy o grant già eseguiti. Il push
successivo applica `0013`–`0020`, `20261109200000`, `20261109210000`,
`20261109220000`; la funzione viene riasserita. Precondizioni: backup, versione
vecchia presente nella cronologia remota, file locale vecchio assente, ruolo
owner/superuser, nessun deploy concorrente. Se il push fallisce, la cronologia
è già cambiata: ripristinare il backup o completare manualmente con `psql -1`
delle migrazioni di integrità e verificare.

## Recupero archivi legacy

Diagnostica **read-only** da eseguire prima del deploy:

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 \
  -f supabase/tests/legacy_season_archive_diagnostic.sql
```

Riporta season_id/league_id per:

- snapshot cross-league: la migrazione si ferma; correggere/ripristinare i dati
  con procedura autorizzata, mai in automatico;
- snapshot con stagione attiva: stato parziale del vecchio flusso;
- standings con radice/righe invalide;
- duplicati utenti/posizioni, buchi, posizioni non intere, valori negativi.

Per lo stato “snapshot + stagione attiva” l'admin della lega può usare, dopo
`20261109220000`:

```sql
select public.complete_partial_season_archive(p_league, p_season);
```

La RPC non accetta standings, non modifica lo snapshot, verifica lega/stagione/
snapshot, aggiorna solo `is_active` e scrive `season_archive_repairs`.
Non è una riparazione automatica: richiede una chiamata esplicita dell'admin.
Per snapshot cross-league o malformati non usare la RPC: serve un intervento
manuale con backup, non una cancellazione silenziosa.

## ACL e default privileges

`20261109210000` fa `REVOKE ALL` e ri-concede solo il necessario:

- `season_snapshots`: `authenticated` solo `SELECT`; nessun
  INSERT/UPDATE/DELETE/TRUNCATE/REFERENCES/TRIGGER/MAINTAIN;
- `seasons`: `authenticated` `SELECT/INSERT/DELETE` e `UPDATE` solo su
  `name`, `start_date`, `end_date`; `is_active` resta escluso;
- `leagues`: `authenticated` DML ordinario, senza
  TRUNCATE/REFERENCES/TRIGGER/MAINTAIN;
- `season_archive_repairs`: nessun accesso client.

Se il progetto Supabase ha i default grant `ALL` per `anon`/`authenticated`
(come in molti progetti Supabase), la migrazione li rimuove esplicitamente.
`service_role` e il ruolo owner non vengono revocati: sono ruoli infrastrutturali
fidati. `service_role` non riceve EXECUTE su `archive_season`; non esiste un
caller server-side nel repository. Se in futuro serve un backend con service_role,
concedere EXECUTE esplicitamente e rieseguire il verifier. Il post-deploy
verifier controlla `has_table_privilege` e `has_column_privilege`, non solo il
testo dei GRANT.

## Deploy e client

Sequenza consigliata:

1. applicare le migrazioni e il verifier sul database;
2. pubblicare l'app aggiornata (versione minima per l'archiviazione: **1.3**,
   versionCode 4);
3. comunicare agli admin che le versioni precedenti non possono più archiviare
   (la lettura continua a funzionare).

La nuova app usa la RPC; una versione vecchia usa INSERT/UPDATE diretti e dopo
la migrazione riceve `permission denied`. Una nuova app installata prima della
migrazione riceve un errore “server non aggiornato”. Non esiste un meccanismo
di force-update/minimum-version nell'architettura attuale: il rilascio va
coordinato. I messaggi client distinguono permessi/app non aggiornata da un
errore di autorizzazione esplicito.

## Verifica post-deploy

```bash
DATABASE_URL='postgresql://...' \
  ./supabase/tests/verify_archive_season_hardening.sh
```

Il comando è read-only e termina con exit code non-zero se:

- la funzione non contiene tutti i marcatori di hardening o usa `p_name`;
- `authenticated` conserva INSERT/UPDATE/DELETE/TRUNCATE/REFERENCES/TRIGGER
  (e MAINTAIN su PostgreSQL 17+) su `season_snapshots`;
- `authenticated` può aggiornare `seasons.is_active`;
- `anon` può eseguire `archive_season` o `authenticated` non può;
- il guard DDL non è attivo;
- una FK snapshot non è RESTRICT;
- l'audit table è accessibile ai client;
- `anon`/`authenticated` possono creare oggetti nello schema `public`.

## Limiti noti

- Le verifiche sono state eseguite su PostgreSQL 17.5 e Supabase CLI 2.114.0;
  non sul progetto Supabase live.
- `CREATE EVENT TRIGGER` richiede superuser/owner: verificare il ruolo reale
  del deploy.
- Se si deve cancellare un'intera lega/account per privacy, le FK RESTRICT
  impediscono la cascata silenziosa: serve un processo esplicito, autorizzato
  e auditato (backup/export + service_role), non una scorciatoia.
- La RPC non dimostra la correttezza matematica dei punteggi calcolati dal
  client; valida struttura, partecipazione e invarianti.
