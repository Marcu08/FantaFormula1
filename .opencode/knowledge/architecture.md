# Architettura dati (Flussi principali)

## Supabase schema (0001_init.sql)
profiles → leagues (invite_code, rules jsonb) → league_members (ruoli admin/player)
→ seasons → races (round, sprint, deadline) → race_results (jsonb) + predictions (jsonb, 1 riga per utente/gara)

## Flusso punteggi
1. Admin registra risultati gara → `race_results.result` (JSONB) + stato gara completata
2. Client legge gare + risultati + pronostici (query per league_id, 2 query: race_results, predictions)
3. `LeagueStandings.build(rules, memberNames, races, results, predictionsByUser)` → Snapshot(rows, scoresByRace, completedRaceIds)
4. `ScoringEngine` calcola per-gara e totali; UI standings (tab Overall/Per-Race) mostri il risultato
5. Recomputazione a ogni apertura: nessuna persistenza dei punteggi, nessuna server function

## Auth flow
Email+password (Supabase Auth) → schermata Configurazione (URL/anon key) se mancanti → Leagues home → League (membri/gare/classifica/regole) → Calendar/RaceResult/Prediction/Rules

## Flusso sync F1 (Fase B-C)
1. Entrare in una lega schedula `F1SyncWorker` (WorkManager, ogni 30 min, richiede rete); icona Sync nelle schermate League (calendario) e Calendar (risultati) = sync manuale/retry.
2. UI e worker passano da `F1SyncStateHolder` (in AppContainer): `Mutex.tryLock` impedisce sync concorrenti, i successi registrano i timestamp in `F1SyncPrefs` (DataStore) e aggiornano `F1SyncUiState` (isSyncing, lastCalendarSync, lastResultsSync).
3. `F1SyncRepository` chiama `F1ApiClient` (Jolpica/Ergast, throttled con retry) → `F1Mapper` normalizza in `RaceResultData` (posizioni contigue 1..n, griglia da qualifica, sprint) → validazione `RaceResultValidation` → `raceRepository.saveResult` porta la gara a COMPLETED.
4. I punteggi non richiedono sync: la classifica ricalcola da `race_results` + `predictions` a ogni lettura (decisione 3); una gara completata dalla sync entra automaticamente nei punteggi.
5. "Ultimo aggiornamento" mostrato da `SyncBar` in League (calendario) e Calendar (risultati); spinner al posto dell'icona Sync durante la sync, pulsante disabilitato.
