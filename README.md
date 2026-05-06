# ADHD Assistant

> A friendly nudge, when you need it.

A free Android app for people who open their phone to Google something and find themselves on TikTok 45 minutes later. ADHD Assistant gently checks in when you've been on a single app longer than you planned — no judgment, no alarm, just a warm shoulder tap.

---

## What it does

- **Detects** which app is in the foreground and how long you've been there
- **Checks in** with a warm, friendly alert when you pass your time threshold
- **Asks** what brought you here and whether you're doing something useful
- **Lets you set intentions** so the alert can remind you what you were actually trying to do
- **Learns your rhythm** and gently suggests adjusting timing if you're consistently continuing past check-ins
- **Sends a quiet summary** when you put the phone down — always framed positively

No ads. Ever. Free tier is genuinely useful. Optional $0.99 Pro upgrade for routines, weekly stats, and intentional-app lists.

---

## Screenshots

*Coming soon*

---

## Architecture

```
app/src/main/java/com/example/adhdassistant/
├── billing/          Google Play In-App Purchases
├── config/           DataStore-based settings & routine models
├── data/             Room database (event log)
├── domain/           Pure logic — resolver, merger, adaptive threshold
├── tracking/         Foreground service, boot receiver, session summary
└── ui/
    ├── alert/        Check-in screen (the core UX)
    ├── excluded/     Intentional-apps list (Pro)
    ├── main/         Home screen
    ├── onboarding/   First-run flow
    ├── routines/     Routine management (Pro)
    ├── settings/     Settings & billing
    └── stats/        Weekly patterns (Pro)
```

**Stack:** Kotlin · Room · DataStore · Coroutines + Flow · Material Design 3 · Google Play Billing 6.x · MVVM

---

## Free vs Pro

| Feature | Free | Pro |
|---|---|---|
| Usage tracking + check-ins | ✅ | ✅ |
| Background tracking + boot restart | ✅ | ✅ |
| Intentions list | ✅ | ✅ |
| Adaptive timing suggestions | ✅ | ✅ |
| Session summary notification | ✅ | ✅ |
| Multiple simultaneous routines | ❌ | ✅ |
| Routine inheritance (child routines) | ❌ | ✅ |
| Auto-scheduling by day/time | ❌ | ✅ |
| Intentional apps list | ❌ | ✅ |
| Weekly patterns | ❌ | ✅ |

---

## Setup

### Prerequisites
- Android Studio Hedgehog or later
- JDK 17
- Android SDK 34
- A device or emulator running Android 8.0+ (API 26+)

### Build

```bash
git clone https://github.com/yourusername/adhd-assistant.git
cd adhd-assistant
./gradlew assembleDebug
```

### Required setup steps

1. **Usage Access permission** — the app will prompt the user to grant this in Settings > Special app access > Usage access. It cannot be granted programmatically; the user must do it manually.

2. **Google Play Billing** (for the Pro upgrade) — requires the app to be uploaded to the Play Console as at least an internal testing track. Billing will not work in a local debug build against a live product ID without this step. See the [Play Billing setup guide](https://developer.android.com/google/play/billing/getting-ready).

3. **In-app product** — create a one-time product in Play Console with ID `adhd_assistant_pro` and price $0.99.

### Permissions

| Permission | When requested | Purpose |
|---|---|---|
| `PACKAGE_USAGE_STATS` | Onboarding (manual — system settings) | Detect foreground app |
| `FOREGROUND_SERVICE` | Always declared, no dialog | Run background service |
| `POST_NOTIFICATIONS` | When background toggle is enabled | Persistent service notification |
| `RECEIVE_BOOT_COMPLETED` | Always declared, no dialog | Restart service after reboot |

The app never requests camera, contacts, location, microphone, or any sensitive permission. No data ever leaves the device.

---

## Project structure — file inventory

### Kotlin source files

| File | Purpose |
|---|---|
| `billing/BillingManager.kt` | Google Play Billing, purchase flow, Pro status |
| `config/ConfigRepository.kt` | DataStore-based settings, all app preferences |
| `config/Routine.kt` | Routine & ResolvedRoutine models, presets, ExcludedAppOverrides |
| `data/Database.kt` | Room database, ActivityEvent entity, DAOs, migration |
| `domain/AdaptiveThresholdManager.kt` | Tracks "keep going" patterns, suggests threshold adjustments |
| `domain/RoutineResolver.kt` | Inheritance chain resolution, routine merger |
| `domain/TriggerClause.kt` | Which rule fired an alert, with inheritance attribution |
| `tracking/UsageTrackingService.kt` | Foreground service, 30s polling loop |
| `tracking/SessionSummaryManager.kt` | Screen-off summary notification, re-alert level tracker |
| `ui/alert/AlertActivity.kt` | Check-in screen — three language levels, grounding flow |
| `ui/excluded/ExcludedAppsActivity.kt` | Intentional-apps list (Pro) |
| `ui/routines/RoutineListActivity.kt` | Routine management with conflict info (Pro) |
| `ui/settings/SettingsActivity.kt` | Settings, Pro billing, background toggle |
| `BootReceiver.kt` + `ChoreListActivity` (in `BootAndChores.kt`) | Boot restart, Intentions list |

### Resources

| File | Purpose |
|---|---|
| `res/values/strings.xml` | All UI copy — zero judgment, warm companion tone |
| `res/values-es/strings.xml` | Spanish translation (partial — falls back to English) |
| `res/values/colors.xml` | Warm amber palette — no reds, no clinical blues |
| `res/values/themes.xml` | Material 3 theme — rounded corners, Nunito font |

### Tests

| File | Location | Purpose |
|---|---|---|
| `RoutineResolverTest.kt` | `src/test/` | 26 tests — inheritance, delta exclusions, merge |
| `UsageEvaluatorTest.kt` | `src/test/` | 11 tests — timer logic, resets, exclusions |
| `ChoreLogicTest.kt` | `src/test/` | Intention list ordering and mutation |
| `ActivityEventDaoTest.kt` | `src/androidTest/` | Room DAO — requires emulator |

---

## Contributing

This project is source-available under the [PolyForm Noncommercial License](LICENSE). You're welcome to fork it and make personal modifications for your own use. Pull requests are welcome for bug fixes. Please open an issue before working on a new feature.

**Commercial use is not permitted.** You may not distribute, sell, or offer a modified version of this app or its code as a product or service.

---

## Design principles

- **Zero judgment.** The user downloaded this app because they want to improve. The app is on their side.
- **Warm, not clinical.** No red colors, no alarm sounds, no words like "failed" or "exceeded."
- **Genuinely useful free tier.** The core loop works without paying. Pro adds depth, not access.
- **Privacy first.** All data stays on device. No analytics, no tracking, no server.

---

## License

Copyright (c) 2025 [Your Name]

Source-available under the [PolyForm Noncommercial License 1.0.0](LICENSE).
Personal and non-commercial use permitted. Commercial use prohibited.
