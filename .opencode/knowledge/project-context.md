# FantaFormula 1 — Contesto progetto

App Android nativa (Kotlin + Jetpack Compose Material 3) per una lega di pronostici F1 tra amici. Multiplayer su Supabase (Postgres + RLS), UI italiana, DI manuale via `AppContainer`.

## Stack
- Kotlin 2.1.20, AGP 8.9.1, Gradle 8.13, compileSdk 36, minSdk 26, targetSdk 35
- Compose BOM 2025.05.01, Navigation Compose 2.9.0, supabase-kt 3.2.6, Ktor 3.1.3, kotlinx-serialization 1.8.1
- JDK 21 Temurin obbligatorio (`org.gradle.java.home` in gradle.properties); il JDK 27 di sistema non funziona con AGP

## Struttura
- `core/domain/` — dominio puro Kotlin (Rules, ScoringEngine, RaceResultData, LeagueStandings): zero Android
- `data/` — supabase-kt + repository dietro interfacce
- `ui/` — una cartella per feature: Screen + ViewModel
- `supabase/migrations/0001_init.sql` — schema + RLS (da eseguire nel SQL Editor)

## Comandi
- `cmd /c ".\gradlew.bat testDebugUnitTest lintDebug assembleDebug"` (gate prima di ogni commit)
- APK finale: `app/build/outputs/apk/debug/app-debug.apk`
- Setup ambiente: `.\scripts\setup-android-env.ps1`

## Lingua e stile
- Tutta l'UI e i messaggi in italiano (strings.xml); codice commentato in italiano dove non ovvio
- Niente Hilt/KSP/Room: DI manuale, build affidabile senza Android Studio
