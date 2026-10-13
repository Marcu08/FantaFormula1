# Note di sessione (fase finale)

- 150 test verdi, 0 failure; lint 0 errori, 87 warning (tutti preesistenti: toml, plurals, manifest); APK ~21 MB
- Commits: cc352af (Fase 0-1), ec05fa7 (Fase 2-3), 3695074 (Fase 4), b228091 (Fase 5), d1a36d0 (Fase 6), c0599d3 (Fase 7), 1a4bb5b (Fase 8-9), 88179fa (Fase 1 fix), c191c2a (Fase 2 fix), 5332601 (Fase B-C: sync F1)
- Identita' visiva per gara: `ui/components/RaceIdentity.kt` (nome GP → paese → palette per round); header a gradiente in RaceCard (Calendar), DeadlineBanner (Prediction), RaceHeader (RaceResult). Bandiere emoji di sistema, nessuna risorsa grafica aggiunta.
- Fase B-C non ancora committate: data/f1api (client Jolpica/Ergast + mapper + test JSON reali), data/sync (repository, worker, state holder, prefs), SyncBar, integrazione punteggio (Fase C)
- ⚠️ ANCORA DA FARE (utente): eseguire `supabase/migrations/0001_init.sql` **e** `0002_fixes.sql` nel SQL Editor Supabase + inserire URL/anon key (schermata Configurazione o `local.properties` con chiavi `SUPABASE_URL` / `SUPABASE_ANON_KEY`).
- ⚠️ Sync non testabile su device fino alla stagione 2026 reale: Jolpica/Ergast non ha ancora dati 2026; il flusso e' coperto da unit test con payload realistici.
- Compose gotcha: `Avatar` non esiste in Material3 → Surface(CircleShape); `LocalClipboard.current.setText` assente in Compose UI 1.8/1.9 → ClipboardManager di platform
- supabase-kt `rpc()` accetta buildJsonObject, non Map
- PowerShell: apostrofi swollowano codice nelle stringhe singole → usare doppie virgolette
- `StateFlow.update` puo' rieseguire la lambda dopo un CAS fallito: mai usare side-effect (`var` esterni) dentro `update` per decisioni di concorrenza → usare `Mutex.tryLock`
