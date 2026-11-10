# Migrazioni Supabase e sicurezza dell'archiviazione stagioni

## Sequenza canonica

| Versione | File | Ruolo |
| --- | --- | --- |
| `0001`–`0012` | `0001_init.sql` … `0012_season_history.sql` | schema `main` |
| `0013`–`0020` | `0013_server_quotes.sql` … `0020_prediction_immutable_keys.sql` | sviluppo |
| `20261109200000` | `20261109200000_season_snapshot_integrity.sql` | prima remediation: RPC atomica + FK composita |
| `20261109210000` | `20261109210000_season_snapshot_deployment_guard.sql` | chiusura: riasserzione, ACL, FK RESTRICT, guard DDL |
| `20261109220000` | `20261109220000_season_archive_recovery.sql` | recupero legacy esplicito + audit + eleggibilita' |

`20261109200000` da sola **non è sufficiente**: la chiusura fail-closed è
`20261109210000`; `20261109220000` aggiunge recupero e validazione
partecipanti.

## Perché `0014` non deve più installare la funzione debole

`0014_audit_fixes.sql` è una migrazione di sviluppo. La versione precedente
sostituiva `archive_season` con un'implementazione vecchia e concedeva
`EXECUTE` ad authenticated. Se `20261109210000` non era ancora applicata, un
errore in una migrazione successiva poteva lasciare quella funzione debole
attiva.

La versione in questo branch:

- installa direttamente il **corpo hardened** (stessa semantica di
  `20261109200000`), non la vecchia implementazione;
- esegue una revoca `EXECUTE` in testa alla migrazione, prima di ogni altro
  DDL, e ripristina il grant solo dopo la `CREATE OR REPLACE` hardened;
- non sostituisce una FK composita già esistente, quindi non indebolisce la
  `RESTRICT` installata da `20261109210000`;
- conserva le altre modifiche di `0014` (policy, `prediction_exists`, unique
  constraint, ecc.).

**Limite di cronologia:** modificare `0014` non cambia i database dove quella
versione è già registrata. Per quelli valgono le regole seguenti:

- se `20261109200000`/`20261109210000` sono già applicate, la funzione è già
  hardened;
- se la funzione è ancora debole e le migrazioni finali sono ancora pending,
  eseguire il **preflight** e poi applicare `0014`/`20261109200000`/
  `20261109210000` come da sequenza.

## Preflight fail-closed

Prima di un deploy che può incontrare `0014`, `20261109200000` o
`20261109210000` su uno stato legacy:

```bash
DATABASE_URL='postgresql://...' \
  ./supabase/tests/preflight_revoke_archive_execute.sh
```

Il preflight, eseguito **fuori dalla transazione delle migrazioni**, revoca
`EXECUTE` su `archive_season` da `public`, `anon` e `authenticated` se la
funzione esiste. Se una migrazione successiva fallisce, authenticated non può
eseguire una vecchia funzione. Il grant viene ripristinato dalle migrazioni
canoniche solo dopo l'installazione della versione hardened.

Il preflight non è un rollback SQL e non modifica la cronologia. Va eseguito
dal ruolo owner/superuser.

## D2 — `20261109200000` applicata, `20261109210000` non applicata

Con il branch corrente:

1. eseguire il preflight;
2. `supabase db push --include-all --db-url "$DATABASE_URL"`.

La sequenza applica `0013`, `0014` (hardened), `0015`–`0020`, poi
`20261109210000` e `20261109220000`.

- Se una migrazione successiva a `0014` fallisce, authenticated può eseguire
  solo la funzione hardened già installata da `0014`.
- Se `0014` stessa fallisce prima del grant finale, la revoca del preflight
  resta attiva (e la funzione precedente, se presente, non è eseguibile da
  authenticated).
- Il verifier fallisce finché `20261109210000`/`20261109220000` non sono
  applicate o se la revoca è rimasta attiva.

Se `0014` è già registrata con il corpo vecchio e `20261109200000` è già
applicata, la funzione è già hardened; il preflight è comunque prudente.

## D3 — `20261109210000` applicata prima di `0014`

Il guard DDL resta **attivo**. Non va disabilitato.

La `0014` di questo branch contiene il corpo hardened, quindi:

1. eseguire il preflight;
2. `supabase db push --include-all --db-url "$DATABASE_URL"`;
3. eseguire il verifier;
4. solo dopo il successo riaprire il traffico.

Il guard vede una `CREATE OR REPLACE` con tutti i marcatori hardened e la
accetta. Se una migrazione successiva fallisce, la funzione hardened resta
attiva. Se `0014` fallisce prima del grant, authenticated resta senza
`EXECUTE` (stato di manutenzione) e il verifier lo rileva. Non esiste un
intervallo in cui una vecchia funzione sia eseguibile da authenticated.

Se per errore viene usato un file `0014` vecchio, il guard rifiuta la
sostituzione: la funzione hardened resta attiva e il deploy si ferma. Non
disabilitare il guard per aggirare l'errore: correggere la migrazione.

### Ripristino del guard

La procedura corrente non disabilita mai `archive_season_hardening_guard`.
Se una procedura precedente lo ha lasciato disabilitato, riabilitarlo prima di
riaprire il traffico:

```sql
alter event trigger archive_season_hardening_guard enable;
```

Il verifier controlla che il trigger esista e sia attivo (`evtenabled = 'O'`);
un guard disabilitato è un FAIL. Non usare `DISABLE` per aggirare un errore di
migrazione: correggere il file e ripetere la procedura.

## Prerequisiti operativi

Prima di un deploy o di una riparazione:

- backup verificato (snapshot fisico o `pg_dump`) del database;
- ruolo owner/superuser per preflight, migrazioni, `ALTER EVENT TRIGGER` e
  verifier;
- nessun deploy o riparazione concorrente;
- finestra di manutenzione: dopo il preflight `archive_season` non è
  eseguibile da authenticated finché non viene installata la funzione
  hardened;
- accesso alla cronologia Supabase solo se serve `migration repair`.

Il preflight e le migrazioni non sono un rollback: non ripristinano dati
storici e non cancellano snapshot. Per un ripristino dati serve il backup.

## Vecchia `20261109120000` o `0014` vecchia già applicata

Se la funzione debole è già presente e le migrazioni finali sono pending:

```bash
DATABASE_URL='postgresql://...' \
  ./supabase/tests/preflight_revoke_archive_execute.sh
supabase db push --db-url "$DATABASE_URL"
```

Il preflight revoca l'esecuzione prima della transazione delle migrazioni; se
il push fallisce, authenticated non può eseguire la vecchia funzione. Se le
migrazioni `0014`/`20261109200000`/`20261109210000` riescono, il grant viene
ripristinato solo sulla funzione hardened.

Per la vecchia versione della prima remediation:

```bash
supabase migration repair --status reverted 20261109120000 --db-url "$DATABASE_URL"
supabase db push --db-url "$DATABASE_URL"
```

`migration repair` modifica **solo la cronologia**. Non annulla funzioni,
policy o grant già eseguiti. Precondizioni: backup, ruolo owner/superuser,
nessun deploy concorrente, preflight eseguito quando la funzione legacy è
ancora presente.

## Interruzioni e verifica

Con il preflight e la `0014` hardened, ogni interruzione lascia uno di questi
stati:

| Interruzione | Stato |
| --- | --- |
| Prima di qualsiasi migrazione | funzione precedente, `EXECUTE` revocato dal preflight |
| Dopo il preflight, prima di `0014` | funzione precedente non eseguibile da authenticated |
| Durante `0014` | la transazione può annullare le modifiche; il preflight esterno resta |
| Dopo `0014` | funzione hardened e grant ripristinato da `0014` |
| Dopo una migrazione successiva fallita | funzione hardened, grant presente |
| Prima di `20261109210000` | funzione hardened; verifier fallisce per stato finale incompleto |
| Dopo `20261109210000` | funzione/ACL/FK/guard completi |

Il verifier è obbligatorio. Controlla funzione, property, ACL, FK, guard,
schema `public`, recovery/audit e, quando la cronologia Supabase è presente,
la registrazione delle tre migrazioni finali. Un exit code non-zero blocca la
riapertura del traffico.

## Recupero legacy

Diagnostica read-only:

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 \
  -f supabase/tests/legacy_season_archive_diagnostic.sql
```

Lo stato “snapshot + stagione attiva” può essere completato dall'admin della
lega con:

```sql
select public.complete_partial_season_archive(p_league, p_season);
```

La RPC:

- verifica admin, lega/stagione, stagione attiva e snapshot esistente;
- valida forma, duplicati, posizioni contigue e non negatività;
- valida che ogni utente sia membro corrente **oppure** ex-membro con
  pronostico in una gara della stagione archiviata;
- non modifica lo snapshot e non accetta standings;
- scrive `season_archive_repairs` e disattiva la stagione nella stessa
  transazione;
- rifiuta snapshot con outsider, utenti di altre leghe, duplicati o dati
  malformati senza audit né deattivazione.

Snapshot cross-league o con partecipanti non eleggibili richiedono intervento
manuale con backup: non vengono corretti o cancellati automaticamente.

Errori del recupero e azione richiesta:

| Errore | Azione |
| --- | --- |
| `Lo snapshot esistente non e' valido o contiene partecipanti non eleggibili` | intervento manuale: diagnostica, backup, correzione verificata; non eliminare lo snapshot |
| `La stagione non appartiene a questa lega` | verificare `p_league`/`p_season`; non forzare |
| `Nessuno snapshot da completare` | usare `archive_season` o correggere lo stato con backup |
| `Solo un amministratore puo completare` | usare un admin attuale della lega |
| `La stagione non e' attiva` | nessuna riparazione necessaria; verificare di non ripetere l'operazione |

In ogni rifiuto la RPC non modifica lo snapshot, non scrive
`season_archive_repairs` e non disattiva la stagione. Un archivio rifiutato
resta disponibile per la remediation manuale.

## ACL e default privileges

`20261109210000` fa `REVOKE ALL` e ri-concede solo il necessario:

- `season_snapshots`: `authenticated` solo `SELECT`; nessun
  INSERT/UPDATE/DELETE/TRUNCATE/REFERENCES/TRIGGER/MAINTAIN;
- `seasons`: `authenticated` `SELECT/INSERT/DELETE` e `UPDATE` solo su
  `name`, `start_date`, `end_date`; `is_active` escluso;
- `leagues`: DML ordinario senza TRUNCATE/REFERENCES/TRIGGER/MAINTAIN;
- `season_archive_repairs`: nessun accesso client.

`service_role` e il ruolo owner sono infrastrutturali fidati e non vengono
revocati. `service_role` non riceve EXECUTE su `archive_season` dalle
migrazioni; se un backend lo richiede, il grant va esplicito e verificato.
I default grant Supabase variano per progetto: il verifier controlla i
privilegi effettivi, ma la verifica live resta obbligatoria.

## Deploy e client

1. preflight (quando c'è una funzione legacy o una migrazione con `0014`);
2. migrazioni + verifier;
3. pubblicazione app 1.3 (versionCode 4);
4. comunicazione agli admin: le versioni precedenti non possono archiviare,
   ma continuano a leggere.

Una nuova app installata prima delle migrazioni riceve “server non
aggiornato”; una vecchia app dopo le migrazioni riceve permission denied.
Non esiste un force-update nell'architettura attuale.

## Limiti noti

- I test D2/D3 sono implementati in `supabase/tests/fail_closed_lifecycle.sh`
  (stato database, ordine dei file) e devono essere eseguiti in CI/staging;
  non sono stati eseguiti sul progetto live. Un PASS non è valido senza
  l'esecuzione effettiva.
- Verifiche locali/CI su PostgreSQL 17.5; **non** live.
- `CREATE EVENT TRIGGER` richiede superuser/owner.
- Il guard DDL è un tripwire testuale: non copre `ALTER FUNCTION`, rename,
  drop/recreate o marker inseriti in commenti. Queste operazioni richiedono
  owner/superuser; authenticated/anon/service_role non possono eseguirle.
- Il displayName storico resta client-controlled; i punteggi sono calcolati
  dal client e non sono ricalcolati in SQL.
- La cancellazione completa di lega/account per privacy richiede un processo
  esplicito, autorizzato e auditato con service_role/backup; le FK RESTRICT
  impediscono la cascata silenziosa.
- L'eventuale repair di un `0014` già registrato e ancora debole richiede il
  preflight + migrazioni finali; non basta modificare il file locale.
