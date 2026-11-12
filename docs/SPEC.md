# Build: Offline Habit Tracker (Android)

Build a private, offline, single-user Android habit tracker. Read this whole spec, propose a short plan (modules, data model, screens), then build it end to end with tests. Before you start, ask me only for my GitHub username, which you need for the package name. After that, ask only if something here is contradictory.

This repository will be **public**. Do not include personal data anywhere: not in code, seed data, test fixtures, screenshots, comments or commit messages.

## Tech stack
- Kotlin, Jetpack Compose, Material 3, single-activity app
- Package: `io.github.<github-username>.habittracker`
- Room for the database, DataStore for settings, Hilt for dependency injection, MVVM with StateFlow
- Glance for the home-screen widget; WorkManager plus exact alarms for reminders
- minSdk 26, targetSdk latest stable
- No INTERNET permission and no analytics. All data stays on the device.

## Core concepts

### Habits
- At most **4 active habits**, enforced.
- Each habit has: name, type, current target, starting target, step size, `mandatory` flag, and the date it was created or last replaced.
- Types:
  1. **DURATION**: "do X minutes". Step defaults to +5 min.
  2. **DEADLINE**: "by time T", e.g. wake up by 7:30 AM. Moving the deadline earlier makes it harder. Step defaults to 15 min. Also store an optional actual time so the user can see how close they got.
  3. **BOOLEAN**: done or not done, with no target.
- **No habits are hard-coded.** First-run onboarding asks the user to create 1–4 habits. Show these generic examples as suggestions: "Wake up by 7:30 AM", "10 min walk", "5 min exercise", "5 min focused work".

### Daily entry
- One row per date. Each habit is YES or NO, **defaulting to NO**. There is no blank state, so a missed day is recorded as NO.
- If the app isn't opened for several days, create the missing rows (all NO) on the next launch, and also through a daily worker.
- One optional **remark per day**, max 50 characters, with a live counter.
- **Edit window:** only today and yesterday can be edited. Older rows are read-only, except in history-import mode.

### Reward rule
- A day's reward unlocks when **all mandatory habits are YES** and **at least 3 habits are YES**. If fewer than 3 habits exist, all of them must be YES.
- Show a clear "Guilt-free reward unlocked" state for the day. There's no reward catalogue; the user picks the reward.
- If the reward is locked, show exactly what's missing, e.g. "<habit name> still needed."

### 14-day cycles and changing targets
- Tracking runs in 14-day cycles. The start date is set during onboarding.
- At the end of each cycle, for each DURATION or DEADLINE habit:
  - **10 or more of 14 YES:** prompt "Level up? 10 → 15 min" with Yes / Not yet. Never level up without confirmation.
  - **Fewer than 5 of 14 YES:** **step down automatically**. Show a notice afterwards. A target never goes below its starting target.
  - **5–9 YES:** no change.
- Both thresholds are editable in settings, with defaults of 10 and 5.
- Keep a history of target changes for each habit.

### Replacing a habit (graduation)
- A habit **graduates** at **at least 26 YES in the last 30 days**. Only then can it be replaced.
- When a habit is replaced, the old one is archived with its history, and the new habit takes its slot.
- A habit's name, target and step can be edited freely for 7 days after it is created. After that they're locked until graduation.
- Show progress toward graduation on each habit card, e.g. "22/30."

### Overall goal
- One free-text "overall goal" field. Show it in the daily reminder notification, and as a small dismissible popup on the first app open each day.

## Screens
1. **Onboarding:** set up habits, cycle start date, reminder time, overall goal, and the backup folder.
2. **Today:** up to 4 large habit tiles. Tap to toggle between NO and YES. Deadline habits also accept an optional actual time. Below the tiles: the reward status, the remark field, and the overall goal.
3. **History:** a grid with dates as rows and habits as columns. One cycle per page; swipe between cycles. Colour-coded cells, the remark on each row, and a reward icon on each day where the reward unlocked.
4. **Stats:** the 14-day completion % per habit (**no streak counters**), the timeline of target changes, and graduation progress.
5. **Habits:** edit or replace habits, set the mandatory flag, and view archived habits.
6. **Settings:** reminder time, cycle start date, thresholds, overall goal, data-retention limit, and backup / restore / import.
7. **Cycle review:** shown when a cycle ends. It presents the level-up prompts and the automatic step-down notices.

## Widget and reminders
- **Home-screen widget (Glance):** shows the habits with **one-tap YES** each, plus the reward status. It stays in sync with the app instantly.
- **Daily reminder notification** at a time the user sets (default 9 PM). It shows the habits still at NO today and the overall goal, with action buttons to mark each one YES from the notification.
- Reminders must survive a reboot (use a BOOT_COMPLETED receiver) and handle the exact-alarm permission on Android 12 and later.

## Data safety (critical)
- Room with WAL mode and proper migrations. Never use destructive migration. Export the Room schema JSON into the repo.
- **Automatic daily backup** to a folder the user picks through the Storage Access Framework, using a persisted URI so the backup survives an uninstall. Keep the last 14 backup files.
- **Manual export to .xlsx** with sheets for Entries, Habits, TargetHistory and Settings. Use a lightweight library that works on Android, not full Apache POI, or write the OOXML directly. Also offer CSV export.
- **Restore** from an .xlsx backup, with a preview and a confirmation step.
- Enable Android Auto Backup for the database.
- **History import mode:** a screen for entering past data for any date. It bypasses the edit window and is clearly labelled.

### Data retention (6 months)
- Keep **6 months** of daily entries. Store this as a setting with a default of 6 months.
- Once a week, check for entries older than the limit. If there are any, show a prompt: "N days older than <date> can be deleted. Delete?" with **Delete** and **Not now** buttons. **Never delete without confirmation.**
- Before deleting, automatically export the data that is about to be deleted to `archive_<from>_<to>.xlsx` in the backup folder. If that export fails, do not delete anything.
- Also delete target-change history older than the limit, and archived habits that have no remaining entries. Never delete active habits or settings.
- The 14-day and 30-day calculations must keep working correctly after a purge.

## Quality bar
- Unit tests for: the reward rule, cycle evaluation (level-up prompt, step-down, no change, never below the starting target), graduation, the edit window, filling missed days with NO, the retention purge (which must refuse to delete if its export fails), and an xlsx export→restore round trip.
- At least one UI test for logging a habit from the Today screen.
- Test fixtures use generic habit names only, e.g. "Habit A" or "Walk".
- Dark mode, large touch targets, and no lag when logging.
- The Gradle build must be clean: no ignored warnings.

## Repo setup (public GitHub repo; I will push it myself)
- Run `git init` and add a standard Android `.gitignore`: build/, .gradle/, local.properties, .idea/, *.iml, captures/, *.apk, *.aab, keystore.properties, *.jks, *.keystore.
- **Never commit secrets or personal data:** no keystores, no signing passwords, no local paths, no email addresses or names in code or comments. Release signing reads from a git-ignored `keystore.properties` or from environment variables. Include `keystore.properties.example` with placeholder values.
- Before the first commit, check `git config user.email`. If it isn't a GitHub noreply address (`<id>+<username>@users.noreply.github.com`), warn me, because commit emails become public when the repo is.
- Include the Gradle wrapper (`gradlew`, `gradle/wrapper/*`) so the repo builds right after cloning.
- Make small, logical commits with clear messages. Do **not** add a remote and do **not** push.
- Add an MIT `LICENSE`, with the copyright holder set to my GitHub username.
- Save this spec as `docs/SPEC.md`.
- **README.md** must cover:
  1. The app name and a one-line description
  2. Features, briefly: habits (max 4), the reward rule with mandatory habits, 14-day cycles with level-up and automatic step-down, graduation, the widget, reminders, the overall goal, backups, and 6-month retention
  3. Requirements: JDK version, Android Studio version, and the minimum Android version
  4. How to build: debug and release, using `./gradlew` commands
  5. How to install: `adb install`, or sideloading the APK
  6. Release signing setup using `keystore.properties.example`
  7. Backup and restore: where the automatic backups go, how Excel export and restore work, how retention archives work, and how history import works
  8. Project structure: a short map of the packages
  9. How to run the tests
  10. Privacy: offline only, no INTERNET permission, all data stays on the device
  11. A screenshots section with placeholder paths under `docs/screenshots/`
  12. License
- When you're done, run a final scan of the repo for secrets and personal data (e.g. `git grep` for emails, absolute paths, "password", ".jks"). Then print the exact push commands: `git remote add origin <url>`, `git branch -M main`, `git push -u origin main`.

## Out of scope
Accounts, cloud sync, social features, ads, more than 4 active habits, streaks.
