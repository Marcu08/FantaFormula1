# Note di sessione (fase finale)

- 104 test verdi, 0 failure; lint 0 errori, 76 warning; APK ~21 MB
- Commits: cc352af (Fase 0-1), ec05fa7 (Fase 2-3), 3695074 (Fase 4), b228091 (Fase 5), d1a36d0 (Fase 6), c0599d3 (Fase 7), 1a4bb5b (Fase 8-9)
- ⚠️ ANCORA DA FARE (utente): eseguire `supabase/migrations/0001_init.sql` nel SQL Editor Supabase + inserire URL/anon key (schermata Configurazione o local.properties)
- Compose gotcha: `Avatar` non esiste in Material3 → Surface(CircleShape); `LocalClipboard.current.setText` assente in Compose UI 1.8/1.9 → ClipboardManager di platform
- supabase-kt `rpc()` accetta buildJsonObject, non Map
- PowerShell: apostrofi swollowano codice nelle stringhe singole → usare doppie virgolette
