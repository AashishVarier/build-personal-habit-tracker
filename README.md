# Habit Tracker

[![CI](https://github.com/AashishVarier/build-personal-habit-tracker/actions/workflows/ci.yml/badge.svg)](https://github.com/AashishVarier/build-personal-habit-tracker/actions/workflows/ci.yml)

A private, offline, single-user Android app for building up to four habits, with gradual targets and a guilt-free daily reward.

## Features

- **Up to 4 active habits**, each one of three types:
  - **Minutes**, for example "10 min walk"
  - **By a time**, for example "wake up by 7:30 AM", with an optional actual time
  - **Yes / no**

  Nothing is hard-coded: you create your habits during onboarding.
- **Daily YES / NO.** Every day defaults to NO, and days you miss are filled in as NO. You can edit today and yesterday.
- **Reward rule.** The day's guilt-free reward unlocks when all **mandatory** habits are YES and at least 3 habits are YES. With fewer than 3 habits, all of them must be YES. When the reward is locked, the app shows exactly what's still missing.
- **14-day cycles.** At the end of each cycle, a habit with 10 or more YES gets a level-up prompt, which you must confirm. A habit with fewer than 5 YES steps down automatically, but never below its starting target. Both thresholds can be changed in Settings, and every target change is kept in a history.
- **Graduation.** A habit graduates at 26 YES in the last 30 days, and only then can it be replaced. The old habit is archived with its history. A habit's name, target and step can be edited freely for 7 days after it is created; after that they are locked until it graduates.
- **Home-screen widget** with one-tap YES for each habit and the reward status. It stays in sync with the app.
- **Daily reminder** at a time you choose (default 9 PM). It lists the habits still at NO and has a button to mark each one YES. Reminders survive a reboot and use exact alarms when Android allows them.
- **Overall goal.** One free-text goal, shown in the reminder and in a small popup the first time you open the app each day.
- **Backups**:
  - an automatic daily Excel backup to a folder you pick (the newest 14 are kept)
  - manual Excel and CSV export
  - restore with a preview before anything changes
  - a history import mode for entering past data
  - Android Auto Backup of the database
- **6-month retention.** Once a week the app offers to delete entries older than the limit. It never deletes without your confirmation, and it archives the data to Excel first.
- **No streak counters.** Stats show 14-day completion, the timeline of target changes, and progress toward graduation.

## Requirements

| | |
|---|---|
| JDK | 17 or newer (built and tested with JDK 21) |
| Android Studio | Rabbit 2 (2026.2.2) or newer, which supports Android Gradle Plugin 9.5 |
| Android SDK | Platform 37. Gradle installs it automatically if the SDK licences are accepted. |
| Device | Android 8.0 (API 26) or newer |

The build uses the Gradle wrapper (Gradle 9.9.1), so you don't need a local Gradle install.

## Build

```bash
# Debug build: app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug

# Release build: app/build/outputs/apk/release/app-release.apk
# Signed if release signing is configured (see below); otherwise app-release-unsigned.apk
./gradlew assembleRelease
```

## Install

With USB debugging enabled on the phone:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or sideload it: copy the APK to the phone, open it in a file manager, and allow "Install unknown apps" for that file manager when Android asks.

## Release signing

Signing secrets are never stored in the repository.

1. Create a keystore once. Keep it somewhere safe and **back it up**: without it you can't ship updates that install over an existing version.
   ```bash
   keytool -genkeypair -v -keystore release-keystore.jks -alias habittracker \
     -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Copy `keystore.properties.example` to `keystore.properties` and fill in the path, alias and passwords. Both `keystore.properties` and `*.jks` are git-ignored.
3. Run `./gradlew assembleRelease`.

You can use environment variables instead of the file (for example in CI): `HABIT_TRACKER_STORE_FILE`, `HABIT_TRACKER_STORE_PASSWORD`, `HABIT_TRACKER_KEY_ALIAS` and `HABIT_TRACKER_KEY_PASSWORD`.

## Backup and restore

- **Automatic backups.** You choose a folder during onboarding or in **Settings → Backup folder**, through Android's folder picker (the Storage Access Framework). Once a day the app writes `habit-tracker_backup_YYYY-MM-DD.xlsx` there and keeps the newest 14. The folder is outside the app's private storage, so backups survive uninstalling the app. **Back up now** writes one immediately.
- **Excel export.** **Settings → Export to Excel** writes a workbook with four sheets:
  - `Entries`: one row per date and habit, with YES/NO, the actual time, the remark and the reward
  - `Habits`, including archived habits
  - `TargetHistory`
  - `Settings`

  **Export entries to CSV** writes the Entries table as CSV. The xlsx file is written directly as OOXML, with no Apache POI.
- **Restore.** **Settings → Restore from an Excel backup** (also offered during onboarding) reads a backup and shows a preview: the date range, the number of days, the habits and the target changes. Nothing changes until you confirm. Restoring replaces all current data, and if a backup folder is set, the current data is first saved there as `habit-tracker_pre-restore_<timestamp>.xlsx`. Files edited and re-saved in Excel or LibreOffice can still be restored.
- **Retention archives.** Entries older than the retention limit (default 6 months) are never deleted silently. Once a week the app asks: "N days older than *date* can be deleted. Delete?" If you choose **Delete**, the data is first exported to `archive_<from>_<to>.xlsx` in the backup folder. If that export fails, nothing is deleted. Old target changes and archived habits with no remaining entries are removed too. Active habits and settings never are. The last 30 days and any cycle still waiting for review are always kept.
- **History import.** **Settings → History import mode** lets you enter YES/NO, actual times and remarks for any past date. It bypasses the today-and-yesterday edit window and is clearly labelled.
- **Android Auto Backup** is enabled for the database and the settings file.

## Project structure

```
app/src/main/java/com/variableduck/build/habittracker/
├── model/        Room entities and enums (Habit, DayRecord, HabitLog, TargetChange, CycleReview…)
├── domain/       Pure rules: reward, 14-day cycles, targets, graduation, edit window, day filling, retention
├── data/
│   ├── db/       Room database, DAOs, migrations (WAL, schema exported to app/schemas/)
│   ├── settings/ DataStore settings
│   └── repo/     TrackerRepository (entries, habits), CycleRepository (evaluation, level-ups)
├── backup/       xlsx writer/reader, backup codec, backup folder (SAF), backup manager, retention purge
├── reminders/    Notifications, exact alarms, broadcast receivers, WorkManager daily worker
├── widget/       Glance home-screen widget
├── di/           Hilt modules and entry points
└── ui/           Compose screens: onboarding, today, history, stats, habits, settings, review, importmode
app/schemas/      Exported Room schema JSON (one file per database version)
docs/SPEC.md      The original specification
.github/workflows/ci.yml   GitHub Actions: tests, lint and APK builds
```

## Tests

```bash
# Everything: build all variants, unit tests, lint and the offline check
./gradlew build

# Unit tests on the JVM, including Room and Compose UI tests via Robolectric
./gradlew testDebugUnitTest

# Instrumented UI test on a connected device or emulator
./gradlew connectedDebugAndroidTest

# Android lint
./gradlew lintDebug
```

The unit tests cover:
- the reward rule
- cycle evaluation: level-up prompt, automatic step-down, no change, never below the starting target, deadline direction
- graduation
- the edit window
- filling missed days with NO
- the retention purge, including refusing to delete when the archive export fails
- an xlsx export → restore round trip, in pure JVM and against a database
- a UI test that logs habits from the Today screen

## Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every push to `main`, on every pull request, and on demand from the Actions tab. On an Ubuntu runner with JDK 21 it:

1. validates the Gradle wrapper checksum
2. runs the unit tests (`testDebugUnitTest`), including the Room and Compose UI tests under Robolectric
3. runs Android lint (`lintDebug`)
4. builds the debug APK and the instrumented-test APK
5. builds the release APK in a separate step, minified with R8. Both build steps fail if the INTERNET permission ever appears in the manifest.
6. uploads the test and lint reports, the debug APK and the release APK as build artifacts (kept for 14 days)

The release APK built in CI is unsigned (`app-release-unsigned`), because no signing secrets are stored in the repository or in CI. To install a release build, sign it locally as described in [Release signing](#release-signing). The workflow only has read access to the repository, and Gradle caching uses the open-source `basic` provider of `gradle/actions/setup-gradle`. The instrumented test (`connectedDebugAndroidTest`) isn't run in CI, because it needs an emulator or a device.

## Privacy

- **Offline only.** The app has no `INTERNET` permission. The build checks the merged manifest and fails if any library ever adds it (`verify<Variant>NoInternetPermission`, which runs with `assemble` and `check`).
- No accounts, no analytics, no ads, no tracking.
- All data stays on the device and in the backup folder you choose.
- Permissions the app requests:
  - `POST_NOTIFICATIONS` for the reminder
  - `SCHEDULE_EXACT_ALARM` for an on-time reminder
  - `RECEIVE_BOOT_COMPLETED` to restore reminders after a reboot

  WorkManager adds `WAKE_LOCK`, `FOREGROUND_SERVICE` and `ACCESS_NETWORK_STATE`. The last one only lets the app ask whether a network is available; without `INTERNET`, the app can't send or receive anything.



## License

[MIT](LICENSE)
