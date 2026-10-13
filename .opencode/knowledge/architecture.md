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
