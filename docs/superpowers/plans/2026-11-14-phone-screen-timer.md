# Ebb Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sideloaded Android app that gates chosen apps behind a session prompt, lockouts and a daily cap, and is hard to remove.

**Architecture:** An event-driven AccessibilityService feeds a pure-Kotlin session engine. State is persisted as absolute timestamps. Overlays are Compose views in `TYPE_ACCESSIBILITY_OVERLAY` windows. A guard in the same service plus Device Admin, a pledge and a change-cooldown resist removal.

**Tech Stack:** Kotlin, Compose + Material 3 Expressive, Room, DataStore, Glance, Navigation Compose, manual DI, JUnit4. No Hilt, no chart library.

**Spec:** `docs/superpowers/specs/2026-11-14-phone-screen-timer-design.md`

## Global Constraints
- App name **Ebb**; package `com.jscyril.ebb`; minSdk 29; target/compile SDK 36. Kotlin code root `app/src/main/java/com/jscyril/ebb/` (the File Structure tree below uses the old folder name `screentimer/`; read it as `ebb/`).
- Production-ready: release signing from a git-ignored `keystore.properties` (never commit keys), versionCode/versionName set, no debug logging in release, all user-visible strings in `strings.xml`, content descriptions on icons, TalkBack-usable overlays.
- Public GitHub repo `jscyril/ebb`; no secrets, keystores or `local.properties` committed.
- Gradle runs on `JAVA_HOME=/usr/lib/jvm/java-17-openjdk`; system default JDK 27 is not changed.
- No foreground service, no polling; detection listens to `TYPE_WINDOW_STATE_CHANGED` only; `packageNames` limited to blocked apps + guard packages.
- No "skip"/"just this once" button anywhere.
- Default tiers (session -> lockout): 5 -> 60 min, 15 -> 120, 30 -> 240, 60 -> 480. Default daily cap 60 min. All editable.
- Loosening a rule waits 24 h; tightening is immediate. Pledge choices 7/30/90 days; passcode is 24 chars, shown once, stored as a salted PBKDF2 hash. Disabling protection after a pledge: 48 h wait + typed phrase.
- Emergency pass: 2 per calendar month; 60 s wait, then typed phrase, then 10-minute window for one app; logged and resets the streak; never affects guard, pledge or rules.
- Release APK about 6 MB or smaller; R8 + resource shrinking; vector icons; system font; charts via Canvas.
- Session is a wall-clock window; the lockout starts when it ends; daily usage counts actual foreground seconds.
- Time never moves backward or forward in a way that shortens a cooldown or a pledge.

## Review Focus
1. System clock changed during a cooldown or pledge: must never shorten it (Task 3).
2. Midnight passes during a session: the day's usage resets, the session is not cut off unfairly (Task 2).
3. Reboot or process death mid-session or mid-cooldown: state recovers from absolute timestamps (Tasks 2, 4).
4. A rule with zero tiers, cap 0, or an uninstalled package must not crash or lock the user out of everything (Task 2).
5. The session alarm fires while the user already left the app: no stray overlay, state still advances (Task 5).

## File Structure
```
app/src/main/java/com/jscyril/screentimer/
  engine/    SessionEngine.kt  Models.kt  TrustedClock.kt
  guard/     Pledge.kt  ChangeQueue.kt  EmergencyPass.kt  DisableRequest.kt  GuardMatcher.kt  AdminReceiver.kt
  data/      Db.kt (Room)  Settings.kt (DataStore)  Repo.kt
  service/   BlockerService.kt  AlarmReceiver.kt  BootReceiver.kt  OverlayHost.kt
  ui/theme/  Theme.kt  Motion.kt
  ui/overlay/ PromptScreen.kt  BlockScreen.kt  TimesUpScreen.kt  EmergencyScreen.kt
  ui/app/    Onboarding.kt  Home.kt  Rules.kt  Dashboard.kt  Settings.kt  charts/*.kt
  widget/    TodayWidget.kt  AppsWidget.kt
app/src/test/java/com/jscyril/screentimer/...  (mirrors engine/ and guard/)
```

---

### Task 1: Toolchain and empty app
**Files:** Create: project root Gradle files, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `AndroidManifest.xml`, `MainActivity.kt`. Environment: `~/Android/Sdk`.
**Interfaces:** Produces: a project where `./gradlew assembleDebug` and `./gradlew test` run on JDK 17.
- [ ] **Step 1:** Download Google's command-line tools to `~/Android/Sdk/cmdline-tools/latest`, run `sdkmanager --licenses` and install `platform-tools`, `platforms;android-36`, `build-tools;36.0.0`. If the network blocks this, stop and give the user the exact commands.
- [ ] **Step 2:** Create the project (Kotlin DSL, version catalog, Compose with the material3 version exposing `MaterialExpressiveTheme`; an alpha is acceptable). Write `local.properties` (`sdk.dir`) and set `org.gradle.java.home` in `gradle.properties` to JDK 17. Generate the wrapper with `gradle wrapper`.
- [ ] **Step 3:** `MainActivity` shows a Compose "Hello". Add one trivial JUnit test.
- [ ] **Step 4:** Run `./gradlew assembleDebug test`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5:** `git init`, add `.gitignore`, commit "chore: scaffold project".

### Task 2: Session engine (pure Kotlin)
**Files:** Create `engine/Models.kt`, `engine/SessionEngine.kt`; Test `engine/SessionEngineTest.kt`.
**Interfaces:** Produces:
```kotlin
data class Tier(val sessionMin: Int, val lockMin: Int)
data class AppRule(val pkg: String, val tiers: List<Tier>, val dailyCapMin: Int)
data class AppState(val dayKey: Int, val usedTodaySec: Int, val sessionEndsAt: Long?, val sessionLockMin: Int?,
                    val lockedUntil: Long?, val emergencyUntil: Long?)
enum class Reason { COOLDOWN, DAILY_CAP }
sealed interface Decision { data class Allow(val until: Long); data class Prompt(val options: List<Tier>); data class Blocked(val until: Long, val reason: Reason) }
object SessionEngine {
  fun decide(rule: AppRule, s: AppState, now: Long, zone: ZoneId): Decision
  fun startSession(s: AppState, tier: Tier, now: Long): AppState
  fun endSession(s: AppState, now: Long): AppState          // sets lockedUntil = sessionEndsAt + lockMin
  fun addForeground(s: AppState, deltaSec: Int, now: Long, zone: ZoneId): AppState // rolls the day over
  fun nextAlarmAt(rule: AppRule, s: AppState, now: Long): Long?  // earlier of session end and cap reached
  fun startEmergency(s: AppState, now: Long): AppState       // emergencyUntil = now + 10 min
}
```
- [ ] **Step 1: Write tests, with these assertions:** `decide` returns `Prompt` with all tiers when the state is fresh; only tiers whose `sessionMin*60` fits the remaining cap when partly used; `Blocked(DAILY_CAP, nextMidnight)` when the cap is spent; `Blocked(COOLDOWN, lockedUntil)` while locked; `Allow(sessionEndsAt)` during a session; `Allow(emergencyUntil)` during an emergency window even if locked or capped; `endSession` with 5-min tier at t sets `lockedUntil = t + 60 min`; `addForeground` across midnight resets `usedTodaySec` and `dayKey`; a rule with empty tiers or cap 0 returns `Blocked(DAILY_CAP, nextMidnight)` and never throws (Review Focus 2, 4); the session still allowed after midnight but the cap is evaluated with the new day.
- [ ] **Step 2:** Run `./gradlew test --tests '*SessionEngineTest'`. Expected: FAIL (not defined).
- [ ] **Step 3:** Implement per the signatures. Pure functions, no Android imports.
- [ ] **Step 4:** Re-run. Expected: PASS. **Step 5:** Commit.

### Task 3: Guard logic (pure Kotlin)
**Files:** Create `engine/TrustedClock.kt`, `guard/Pledge.kt`, `guard/ChangeQueue.kt`, `guard/EmergencyPass.kt`, `guard/DisableRequest.kt`; Tests alongside.
**Interfaces:** Produces:
```kotlin
object TrustedClock { fun trusted(anchorWall: Long, anchorElapsed: Long, elapsedNow: Long, wallNow: Long, lastTrusted: Long, rebooted: Boolean): Long }
// not rebooted: anchorWall + (elapsedNow - anchorElapsed); rebooted: max(wallNow, lastTrusted)
data class PledgeRecord(val endsAt: Long, val salt: ByteArray, val hash: ByteArray)
object Pledge { fun create(days: Int, trustedNow: Long): Pair<PledgeRecord, String>; fun isActive(r: PledgeRecord?, trustedNow: Long): Boolean; fun verify(r: PledgeRecord, passcode: String): Boolean }
data class Rules(val apps: Map<String, AppRule>, val passesPerMonth: Int)
data class Pending(val newRules: Rules, val effectiveAt: Long)
object ChangeQueue { fun submit(current: Rules, proposed: Rules, trustedNow: Long): Pair<Rules, Pending?>; fun mature(current: Rules, p: Pending?, trustedNow: Long): Pair<Rules, Pending?> }
object EmergencyPass { fun remaining(usedAt: List<Long>, perMonth: Int, now: Long, zone: ZoneId): Int }
object DisableRequest { fun canDisable(pledge: PledgeRecord?, requestedAt: Long?, trustedNow: Long): Boolean } // pledge over AND request >= 48 h old
```
- [ ] **Step 1: Tests:** wall clock set back 1 day while not rebooted does not change `trusted`; wall clock set forward 1 day does not change `trusted` (Review Focus 1); after reboot a backward wall time is clamped to `lastTrusted`; `Pledge.create(30,…)` gives a 24-character passcode that `verify` accepts and a one-character change rejects; `isActive` is false at `endsAt`; `submit` applies a shorter session or longer lockout or lower cap immediately; a longer session, shorter lockout, higher cap, removed app or higher `passesPerMonth` returns `Pending(effectiveAt = now + 24 h)` and leaves current rules unchanged; `mature` applies them only once `effectiveAt` has passed; `remaining` counts only passes within the current calendar month; `canDisable` is false during a pledge, false with a 47 h old request, true with a 48 h old request.
- [ ] **Step 2:** Run, expect FAIL. **Step 3:** Implement; PBKDF2WithHmacSHA256 with a 16-byte random salt and `SecureRandom` passcode from an unambiguous alphabet. Add a `// shortcut: reboot + forward wall-clock jump can still shorten a cooldown, close with Device Owner` comment in `TrustedClock`. **Step 4:** Run, expect PASS. **Step 5:** Commit.

### Task 4: Persistence
**Files:** Create `data/Db.kt`, `data/Settings.kt`, `data/Repo.kt`; Test `androidTest`-free: Room in-memory via Robolectric is NOT used; test only `Repo` mapping functions as pure code.
**Interfaces:** Consumes: Task 2 and 3 types. Produces: `class Repo(ctx)` with `suspend fun rules(): Rules`, `suspend fun saveRules(Rules)`, `suspend fun state(pkg): AppState`, `suspend fun saveState(pkg, AppState)`, `suspend fun logSession(pkg, start, end, plannedMin, kind: Kind)`, `fun dailyTotals(days: Int): Flow<List<DayTotal>>`, `fun perApp(day: Int): Flow<List<AppTotal>>`, `fun hourly(days: Int): Flow<IntArray /*24*/>`, plus pledge, pending-change, protection-disable request, trusted-clock anchor and theme prefs in DataStore. `enum Kind { SESSION, BLOCKED_ATTEMPT, EMERGENCY }`.
- [ ] **Step 1:** Define Room entities `usage_event(id, pkg, start, end, plannedMin, kind)` and `day_aggregate(dayKey, pkg, seconds, opens, blocked)`; pruning of events older than 90 days on app start.
- [ ] **Step 2:** Write a unit test for the pure mapping/aggregation helper (events -> day totals, hourly buckets across midnight). Run, expect FAIL, implement, run, expect PASS.
- [ ] **Step 3:** Implement `Repo`. **Step 4:** `./gradlew assembleDebug test` passes. **Step 5:** Commit.

### Task 5: Accessibility service, alarms, overlay host
**Files:** Create `service/BlockerService.kt`, `OverlayHost.kt`, `AlarmReceiver.kt`, `BootReceiver.kt`, `res/xml/accessibility_config.xml`; modify manifest.
**Interfaces:** Consumes: `Repo`, `SessionEngine`, `TrustedClock`. Produces: `OverlayHost.show(content: @Composable () -> Unit)` / `dismiss()`, using a `ComposeView` in a `TYPE_ACCESSIBILITY_OVERLAY` window with a minimal custom `LifecycleOwner`/`SavedStateRegistryOwner`.
- [ ] **Step 1:** On each window-change event for a watched package: update foreground-time accounting for the previous watched app, call `decide`, then show the prompt, block screen, or allow and schedule one `AlarmManager.setExactAndAllowWhileIdle` alarm from `nextAlarmAt` (inexact if exact-alarm permission is missing). Leaving a watched app cancels its alarm and closes its overlay.
- [ ] **Step 2:** `AlarmReceiver`: apply `endSession`, log it, and show `TimesUpScreen` then `GLOBAL_ACTION_HOME` only if a watched app is still in the foreground; otherwise just advance state silently (Review Focus 5).
- [ ] **Step 3:** `BootReceiver`: re-establish the trusted-clock anchor (`rebooted = true`) and re-arm nothing else, since state is absolute (Review Focus 3).
- [ ] **Step 4:** Maintain `serviceInfo.packageNames` from `Repo.rules()` whenever rules change.
- [ ] **Step 5:** Manual: on a device, open a watched app and confirm a placeholder overlay appears. `./gradlew assembleDebug` passes. Commit.

### Task 6: Guard, Device Admin, protection toggle
**Files:** Create `guard/GuardMatcher.kt`, `guard/AdminReceiver.kt`, `res/xml/device_admin.xml`; modify `BlockerService.kt`.
**Interfaces:** Produces: `object GuardMatcher { fun shouldEject(pkg: String, cls: String?, texts: List<CharSequence>, appName: String, pledgeActive: Boolean): Boolean }`.
- [ ] **Step 1: Tests:** returns true for Settings/installer/permission-controller windows whose text contains the app name; true for the Accessibility, Device Admin, Usage Access, battery-optimization pages; true for the Date & Time page only while `pledgeActive`; false for an unrelated Settings page and for non-guard packages.
- [ ] **Step 2:** Run, expect FAIL; implement; run, expect PASS.
- [ ] **Step 3:** In the service, add the guard packages to `packageNames`; on `shouldEject`, call `GLOBAL_ACTION_HOME`. Override `onUnbind` to re-raise a notification-less prompt on next launch (a service cannot restart itself; the app shows a prominent "protection off" banner).
- [ ] **Step 4:** `AdminReceiver` + `onDisableRequested` returns a warning text; deactivation is only reachable through the in-app flow gated by `DisableRequest.canDisable`.
- [ ] **Step 5:** Commit.

### Task 7: Theme, motion, design system
**Files:** Create `ui/theme/Theme.kt`, `Motion.kt`, shared components (`BigButton`, `SpringCard`, `TierChip`).
- [ ] **Step 1:** `ScreenTimerTheme(darkMode: System|Light|Dark|Amoled, accent: Dynamic|Preset)` wrapping `MaterialExpressiveTheme`; presets: 5 accent seeds. Overlay screens read the same theme prefs.
- [ ] **Step 2:** `Motion.kt` exposes the app's spring specs (`Fast`, `Standard`, `Slow`) and an enter/exit transition for overlays. All screens use only these.
- [ ] **Step 3:** Logo: an adaptive launcher icon in vector form (`ic_launcher_foreground`, `ic_launcher_background`, and a `monochrome` layer for Android 13+ themed icons). Motif: a smooth wave sitting inside a ring that is partly drained (a tide inside a timer), deep teal/ink on a soft sea-glass background. Also export a 512 px PNG to `docs/brand/ebb-logo-512.png` for the README/repo (render with the build's vector or an inline SVG source kept at `docs/brand/ebb-logo.svg`).
- [ ] **Step 4:** Splash: `androidx.core:core-splashscreen` with `Theme.Ebb.Starting`; an animated vector of the wave rising in the ring (at most 800 ms, per the Android 12+ splash limits); hand-off to the app with a spring scale/fade exit. Set the app theme so there's no white flash on cold start.
- [ ] **Step 5:** `./gradlew assembleDebug`. Commit.

### Task 8: Overlay screens
**Files:** Create `ui/overlay/PromptScreen.kt`, `BlockScreen.kt`, `TimesUpScreen.kt`, `EmergencyScreen.kt`.
**Interfaces:** Consumes: `Decision`, `Tier`, `EmergencyPass.remaining`. Produces: composables `PromptScreen(appName, options, onPick(Tier))`, `BlockScreen(appName, until, reason, passesLeft, onEmergency)`, `TimesUpScreen(appName, nextUnlock)`, `EmergencyScreen(appName, passesLeft, onConfirm, onCancel)`.
- [ ] **Step 1:** Prompt: large animated tier chips showing "5 min -> blocked 1 h". No dismiss control other than Home. Block: animated ring to unlock time, a calm message, a small "Emergency" action.
- [ ] **Step 2:** Emergency: a 60-second animated countdown that must finish, then a typed-phrase field; on confirm log `Kind.EMERGENCY`, call `startEmergency`.
- [ ] **Step 3:** Wire into `BlockerService` in place of the placeholder. Manual: verify flow on device. Commit.

### Task 9: App shell, onboarding, rules, settings
**Files:** Create `ui/app/Onboarding.kt`, `Home.kt`, `Rules.kt`, `Settings.kt`; `MainActivity.kt`.
- [ ] **Step 1:** Onboarding: explain and deep-link the Accessibility and Device Admin permissions; app picker (launchable apps list, searchable); tier editor; pledge choice, passcode shown once with a copy/share action and an explicit "I gave this to someone else" confirmation.
- [ ] **Step 2:** Rules screen edits go through `ChangeQueue.submit`; pending changes shown with a cancel action and effective time.
- [ ] **Step 3:** Settings: theme, accent, emergency passes (follows ChangeQueue), "Disable protection" (starts the 48 h request; the final step requires `canDisable` and the typed phrase), pledge status. A banner when the service is not enabled.
- [ ] **Step 4:** Matured pending changes are applied on app open and by the service on each event.
- [ ] **Step 5:** Manual run-through; commit.

### Task 10: Dashboard
**Files:** Create `ui/app/Dashboard.kt`, `ui/app/charts/{Ring,Bars,Heatmap}.kt`.
- [ ] **Step 1:** Canvas composables: `RingChart(fraction)`, `WeekBars(values)`, `HourHeatmap(IntArray24)`, each animated on first draw.
- [ ] **Step 2:** Dashboard: today ring and total, 7-day bars, per-app list, heatmap, opens vs. blocked attempts, streak (days without `Kind.EMERGENCY` and under the cap), estimated time saved (sum of served lockout minutes, labelled as an estimate).
- [ ] **Step 3:** Unit test the streak calculation as pure code (broken by an emergency day, preserved otherwise). Run, fail, implement, pass. Commit.

### Task 11: Widgets
**Files:** Create `widget/TodayWidget.kt`, `widget/AppsWidget.kt`, receivers and `res/xml` provider info.
- [ ] **Step 1:** Glance Today (2x2): ring with remaining/total. Apps (4x2): up to 4 apps with status ("Open", "Locked, unlocks 4:30 PM"). Themed with the app palette; absolute times only, no live countdowns.
- [ ] **Step 2:** `Repo` change hooks call `GlanceAppWidget.updateAll` on state change; schedule one midnight refresh.
- [ ] **Step 3:** Manual: add both widgets on a device. Commit.

### Task 12: Release hardening and handoff
- [ ] **Step 1:** Enable R8 + `shrinkResources`; build a release APK signed with a local debug-style keystore; record the size, aiming for about 6 MB or smaller, and trim if over.
- [ ] **Step 2:** Verify no wakeups when no watched app is in the foreground (code review of the service: no timers outside the single alarm).
- [ ] **Step 3:** Write `README.md` with install commands (`adb install`), the permissions to grant (including "Allow restricted settings" for sideloaded apps), and the manual device checklist: prompt, session end, lockout, daily cap, emergency pass, guard ejects from the app's info page, clock change does not shorten a lockout, reboot mid-lockout, widgets.
- [ ] **Step 4:** Add `.github/workflows/ci.yml`: on push/PR, JDK 17, `./gradlew test assembleDebug lint`, upload the debug APK artifact. Add an MIT `LICENSE`.
- [ ] **Step 5:** Run `./gradlew test lint assembleRelease`. Expected: BUILD SUCCESSFUL. Commit.

---
## Self-review notes
- Coverage: tiers/cap/lockout (T2), clock + pledge + change cooldown + emergency + disable (T3), persistence/analytics (T4), detection/overlays/alarms (T5, T8), guard + Device Admin (T6), theme/animation (T7), setup/rules (T9), dashboard (T10), widgets (T11), size/battery (T12).
- On-device behaviour (overlay, guard, widgets) can't be tested here; those tasks end in a manual check and are flagged as such.
