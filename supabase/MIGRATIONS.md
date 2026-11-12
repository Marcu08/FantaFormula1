# Migrazioni Supabase e sicurezza dell'archiviazione stagioni

## 1. Sequenza canonica

| Versione | File | Ruolo |
| --- | --- | --- |
| `0001`–`0012` | `0001_init.sql` … `0012_season_history.sql` | schema base |
| `0013`–`0020` | `0013_server_quotes.sql` … `0020_prediction_immutable_keys.sql` | sviluppo |
| `20261109200000` | `20261109200000_season_snapshot_integrity.sql` | prima remediation: RPC atomica + FK composita |
| `20261109210000` | `20261109210000_season_snapshot_deployment_guard.sql` | ACL, FK RESTRICT, primo guard DDL |
| `20261109220000` | `20261109220000_season_archive_recovery.sql` | recupero legacy + eleggibilità partecipanti |
| `20261109230000` | `20261109230000_archive_season_exact_protection.sql` | **chiusura finale**: identità esatta, guardie CREATE/ALTER/DROP FUNCTION/ROUTINE, repair history-independent |

La sequenza canonica completa è:

```text
0001 → 0002 → … → 0012 → 0013 → … → 0020
→ 20261109200000 → 20261109210000 → 20261109220000 → 20261109230000
```

`20261109200000` da sola non è sufficiente. `20261109210000` è obbligatoria,
`20261109220000` aggiunge il recupero, e `20261109230000` è la chiusura
history-independent.

## 2. Unico entry point operativo

Non usare direttamente `supabase db push` come procedura normale. L'unico
entry point supportato è:

```bash
DATABASE_URL='postgresql://...' \
  ./supabase/scripts/deploy_archive_safe.sh
```

Il wrapper, in ordine:

1. verifica versione CLI (`SUPABASE_CLI_VERSION`, default `2.114.0`);
2. esegue il preflight persistente e verifica `EXECUTE` effettivo;
3. ripara solo la cronologia remota nota (`20261109120000`) se necessario;
4. esegue `supabase db push --include-all --yes --skip-vault`;
5. esegue **sempre** il repair SQL esplicito `supabase/repair/archive_season_repair.sql`;
6. esegue il verifier in modalità lockout (`authenticated` ancora revocato);
7. solo se il verifier passa, esegue `finalize_archive_season_deployment.sql`
   per concedere `EXECUTE` ad `authenticated` e chiudere il lockout;
8. esegue il verifier finale con `authenticated` abilitato.

Il wrapper esce con errore se una qualsiasi fase fallisce e riapplica il
preflight dopo un errore. Un exit code di `supabase db push` non è mai
sufficiente per riaprire il traffico.

## 3. Preflight persistente

```bash
DATABASE_URL='postgresql://...' \
  ./supabase/tests/preflight_revoke_archive_execute.sh
```

Il preflight SQL:

- verifica il database atteso e lo schema `public`;
- verifica la firma canonica esatta `(uuid,uuid,text,jsonb)`;
- rifiuta overload inattesi;
- verifica owner, `SECURITY DEFINER` e `search_path=public`;
- revoca `EXECUTE` da `PUBLIC`, `anon`, `authenticated`, `service_role`;
- **non modifica** ruoli custom: se un ruolo custom conserva un grant, il
  preflight fallisce e richiede un intervento esplicito dell'owner;
- quando `archive_season` non esiste, ispeziona `pg_default_acl` e fallisce se
  un ruolo custom potrebbe ricevere `EXECUTE` alla creazione;
- verifica con `has_function_privilege` che `authenticated`, `anon` e
  `service_role` non possano più eseguire la funzione;
- crea/attiva `public.archive_season_deploy_lockout`.

La revoca è in una transazione separata e viene **committata prima** delle
migrazioni. Il CLI Supabase esegue ogni migrazione in una transazione: un
`REVOKE` dentro una migrazione fallita viene annullato. Il lockout esterno è
l'unica protezione che sopravvive al rollback.

Se il preflight fallisce, nessuna migrazione deve essere eseguita. Se il
preflight ha successo e una migrazione fallisce, `authenticated` resta senza
`EXECUTE`, il lockout resta attivo e il verifier fallisce.

Le migrazioni `0014`, `20261109200000`, `20261109210000` e
`20261109230000` controllano il lockout e falliscono con
`archive_season deployment preflight required` prima di qualsiasi DDL se il
lockout manca. Un `supabase db push` diretto senza wrapper viene quindi
**rifiutato**: non esegue migrazioni e non è un deploy. Se la funzione era già
debole, resta nel suo stato preesistente; il wrapper è obbligatorio per
bloccarla e ripararla. Non interpretare questo rifiuto come un deploy
riuscito.

## 4. Perché `0014` non può più installare la funzione debole

La `0014_audit_fixes.sql` di questo branch:

- installa direttamente il corpo canonico hardened, non la vecchia
  implementazione;
- esegue una revoca `EXECUTE` in testa, ma **non** si affida a questa revoca
  per la sicurezza post-rollback;
- richiede il lockout del preflight;
- non sostituisce una FK composita già esistente;
- revoca `authenticated` e **non concede mai** `EXECUTE`: il grant viene
  ripristinato esclusivamente dal finalize del wrapper dopo il verifier
  lockout.

La `0014` modificata è copiata in modo esatto dalla funzione canonica di
`20261109200000`/`20261109210000`.

## 5. Stati legacy

### D2 — `20261109200000` applicata, `20261109210000` pendente

Il wrapper esegue preflight → push → repair esplicito → verifier → finalize →
verifier. `0014` pending installa il corpo canonico ma non concede `EXECUTE`.
Un errore successivo lascia la funzione hardened e `authenticated` revocato;
solo il finalize, dopo il verifier lockout, ripristina il grant.

### D3 — `20261109210000` applicata, `0014` pendente

Il guard può restare attivo. La `0014` canonica passa il controllo esatto di
`20261109230000` (quando presente) o i marcatori della versione precedente.
Non disabilitare mai il trigger. Un vecchio `0014` debole viene rifiutato.
Le migrazioni non concedono `EXECUTE`: un errore successivo lascia
`authenticated` revocato e il lockout attivo.

### C1 — vecchia `20261109120000` e `0014` registrate, finali pendenti

`migration repair --status reverted 20261109120000` cambia **solo la
cronologia**. Non esegue SQL. Il wrapper lo usa solo per rimuovere la versione
remota che non esiste più nei file locali. L'hardening reale viene eseguito da
`20261109200000`/`20261109210000`/`20261109230000` e dal repair esplicito.

### C2 — `20261109200000` e vecchia `0014` debole registrate, `20261109210000` pendente

Il wrapper esegue il preflight (lockout), applica `20261109210000` e
`20261109230000`, poi il repair esplicito. La `0014` registrata viene saltata:
l'hardening avviene nelle migrazioni finali e nel repair. Se una migrazione
fallisce, il preflight resta attivo e la funzione debole non è eseguibile.

### C3 — tutte le versioni registrate, funzione ancora debole

`supabase db push --include-all` può rispondere `upToDate:true` e non eseguire
nulla. Per questo il wrapper esegue **sempre**:

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -1 \
  -f supabase/repair/archive_season_repair.sql
```

Il repair include `20261109230000` e quindi:

1. verifica che il lockout sia attivo;
2. rifiuta overload inattesi;
3. rimuove temporaneamente entrambe le guardie nel controllo transazionale;
4. installa il corpo canonico esatto;
5. salva `pg_get_functiondef` nella tabella canonica;
6. installa il guard esatto per `CREATE`, `ALTER` e `DROP`;
7. ripristina le FK validated `RESTRICT`;
8. riasserisce `prediction_exists` e la policy `leagues_delete_admin` della
   `0014`, indipendentemente dalla cronologia;
9. revoca `authenticated`/`anon`/`service_role` e verifica che non restino
   grantee custom su `archive_season`/`prediction_exists`;
10. verifica SHA-256 e proprietà prima del commit.

Il repair è idempotente. Può essere rieseguito dopo un'interruzione.

### C3 con cronologia incompleta o incoerente

Se manca una versione finale o il repair non può verificare la funzione, il
comando fallisce. Non inventare righe di cronologia per forzare l'esecuzione:
usare il wrapper e il repair SQL esplicito.

## 6. Interruzioni

| Interruzione | Stato |
| --- | --- |
| Prima del preflight | stato precedente; nessuna migrazione parte |
| Dopo il preflight | `EXECUTE` revocato, lockout attivo |
| Durante `db push` | la migrazione corrente è transazionale; il lockout esterno resta |
| Dopo una migrazione fallita | wrapper riapplica il preflight; `authenticated` resta revocato |
| Durante il repair | transazione del repair annullata; lockout resta |
| Dopo il repair, prima del verifier | funzione canonica, `authenticated` revocato |
| Verifier lockout fallito | nessun grant, traffico bloccato |
| Durante il finalize | transazione annullata oppure grant+lockout chiusi insieme |
| Verifier finale fallito | wrapper riapplica il preflight e blocca il traffico |

## 7. Guardia DDL esatta

`20261109230000` sostituisce il vecchio controllo a marcatori con:

- tabella `public.archive_season_canonical_definition` contenente il
  `pg_get_functiondef` canonico e il suo SHA-256;
- `archive_season_hardening_guard` su `CREATE FUNCTION`/`CREATE PROCEDURE`:
  accetta solo la firma canonica con definizione **identica**;
- `archive_season_mutation_guard` su `ALTER/DROP` di `FUNCTION`, `ROUTINE` e
  `PROCEDURE`:
  rifiuta ogni modifica o cancellazione di `archive_season`;
- rifiuto di overload;
- verifier con SHA-256 hardcoded e test comportamentali che tentano una
  funzione no-op con marcatori nei commenti;
- normalizzazione CRLF/CR -> LF prima del confronto e dell'hash, per evitare
  falsi negativi dovuti al checkout Windows.

La tabella canonica e le funzioni di guardia non sono accessibili ai ruoli
client. Un owner/superuser può sempre alterare il database: questo è il confine
amministrativo fidato, non una garanzia contro un owner compromesso.

## 8. Verifier

```bash
DATABASE_URL='postgresql://...' \
  ./supabase/tests/verify_archive_season_hardening.sh
```

Il wrapper lo esegue due volte:

- lockout: `app.archive_verify_expect_auth=false`,
  `app.archive_verify_expect_lockout=true`;
- finale: `app.archive_verify_expect_auth=true`,
  `app.archive_verify_expect_lockout=false`.

Controlla, tra l'altro:

- firma canonica, owner, `SECURITY DEFINER`, `search_path`;
- SHA-256 e definizione canonica esatta (i marcatori nei commenti non bastano);
- assenza di overload;
- `authenticated`/`anon`/`service_role` secondo la policy;
- ACL di snapshot, seasons, leagues, canonical store, lockout e audit;
- FK snapshot: esistenza, colonne, tabelle referenziate, `ON DELETE RESTRICT`,
  validazione;
- entrambi gli event trigger e il comportamento di rifiuto di weak
  `CREATE OR REPLACE`, `ALTER`, `DROP` e overload;
- presenza delle migrazioni finali in `supabase_migrations.schema_migrations`
  quando la cronologia esiste.

## 9. ACL e service_role

Policy esplicita:

- `archive_season`: eseguibile solo da `authenticated`; `PUBLIC`, `anon` e
  `service_role` sono revocati;
- `complete_partial_season_archive`: eseguibile da `authenticated`; `PUBLIC`,
  `anon` e `service_role` revocati;
- `prediction_exists`: eseguibile solo da `authenticated`, con policy
  `leagues_delete_admin` basata su `is_league_admin(id)`;
- helper (`season_snapshot_shape_is_valid`,
  `season_snapshot_participants_are_eligible`, `harden_season_archive_acl`,
  guardie, canonical store e deployment lockout): nessun accesso client;
- `season_archive_repairs`: nessun accesso client;
- `season_snapshots`: `authenticated` solo `SELECT`; `anon` e `service_role`
  non hanno alcun privilegio (service_role bypassa RLS, quindi una lettura
  diretta sarebbe una fuga dello storico);
- `seasons`: `SELECT/INSERT/DELETE` e `UPDATE` solo su `name`, `start_date`,
  `end_date`; `is_active` escluso;
- `leagues`: DML ordinario senza `TRUNCATE/REFERENCES/TRIGGER/MAINTAIN`.

`service_role` è un ruolo infrastrutturale fidato per le tabelle operative, ma
**non** riceve `EXECUTE` su `archive_season` o sulla RPC di recupero e non ha
accesso a `season_snapshots`, canonical store, lockout o audit. Se un backend
richiede un grant, deve essere esplicito, motivato e verificato in staging.

## 10. Recupero legacy

Diagnostica read-only:

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 \
  -f supabase/tests/legacy_season_archive_diagnostic.sql
```

Completamento:

```sql
select public.complete_partial_season_archive(p_league, p_season);
```

La RPC verifica admin, lega/stagione, stagione attiva, snapshot esistente,
forma/duplicati/posizioni/valori e appartenenza dei partecipanti (membro
corrente oppure ex-membro con pronostico nella stagione archiviata). Non
modifica lo snapshot; in caso di rifiuto non scrive audit e non disattiva la
stagione. Snapshot non eleggibili o cross-league richiedono backup e
remediation manuale.

## 11. Prerequisiti operativi

- backup verificato (`pg_dump` o snapshot fisico);
- ruolo owner/superuser per preflight, migrazioni, repair, event trigger e
  verifier;
- nessun deploy/repair concorrente;
- finestra di manutenzione;
- CLI Supabase 2.114.0 (pinned) e PostgreSQL 17.5;
- accesso alla cronologia Supabase solo per la riparazione della versione
  legacy `20261109120000`.

## 12. Provenienza migrazioni 0013–0020

| File | Provenienza locale | Stato |
| --- | --- | --- |
| `0013_server_quotes.sql` | `security-hardening-audit` / `90b6c71`, blob `2af7e740` | byte-identical |
| `0014_audit_fixes.sql` | `0bab3e5`, blob `af62a2a` | modifica intenzionale: corpo canonico, preflight, FK guard |
| `0015_security_hardening.sql` | `0bab3e5`, blob `7fdf5ea` | byte-identical |
| `0016_atomic_prediction_revision.sql` | nessun commit locale; file untracked nel worktree principale, blob `ad3f969` | target byte-identical all'untracked; provenienza Git **non verificata** |
| `0017_server_revision_bigint.sql` | nessun commit locale; untracked, blob `84633ca` | target byte-identical; provenienza Git **non verificata** |
| `0018_initial_quotes_after_predictions.sql` | nessun commit locale; untracked, blob `27a681a` | target byte-identical; provenienza Git **non verificata** |
| `0019_quote_publish_lock.sql` | nessun commit locale; untracked, blob `652fb3c` | target byte-identical; provenienza Git **non verificata** |
| `0020_prediction_immutable_keys.sql` | `security-hardening-audit` / `c500fdf`, blob `1e3203f` | byte-identical |

`0016`–`0019` restano necessari alla catena attuale; non sono stati rimossi
solo perché la provenienza non è committed. Prima di un rilascio esterno la
provenienza deve essere confermata dal maintainer.

I template di test `0014`–`0019` sono stati importati dal worktree principale
locale (file `0014_audit_fixes_tests.sql`,
`0015_security_hardening_tests.sql`, `0016_atomic_prediction_revision_tests.sql`,
`0017_server_revision_bigint_tests.sql`, `0018_initial_quotes_tests.sql`,
`0019_quote_publish_lock_tests.sql`). Il runner
`supabase/tests/legacy_dev_migrations.sh` sostituisce gli UUID placeholder e li
esegue su un clone disposable con i grant tabellari Supabase-like; i test
terminano intenzionalmente con self-rollback. Anche questi template non hanno
una provenienza upstream committed.

## 13. Limiti

- Verifiche locali/CI su PostgreSQL 17.5 e CLI 2.114.0; **non** live.
- Il verifier controlla lo stato PostgreSQL, non PostgREST o i default grant
  live.
- Il confine owner/superuser è fidato: un owner compromesso può modificare
  funzioni, tabelle e guardie.
- La provenance di `0016`–`0019` è unresolved.
- I test GitHub Actions devono essere eseguiti realmente; un file YAML
  valido non è un PASS.
