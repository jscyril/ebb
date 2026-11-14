# Phone Screen Timer: Design

Date: 2026-11-14. Status: awaiting approval. Not a git repo, so this is not committed.

## Intent
The user doomscrolls and wants to use their phone less. They want an Android app-blocker where opening a chosen app (e.g. Instagram) forces them to pick a session length. The session is followed by a lockout whose length depends on the session length. A daily cap blocks the app for the rest of the day, even if used in short bursts. The user has a history of simply removing blockers, so **resistance to removal is the central requirement**, not a nice-to-have. The look must be excellent (Material 3 Expressive), and the app must be light on battery, CPU and storage.

## Goals
- Per-app rules, all user-editable: session options, the lockout for each, the daily cap.
- Default tier table (editable): 5 min -> 1 h lockout, 15 min -> 2 h, 30 min -> 4 h, 60 min -> 8 h.
- Daily cap per app (default 60 min). Once reached, the app is locked until local midnight.
- Pre-open prompt, block screen and "time's up" screen. All three are themeable (accent, style presets, dynamic color, light/dark/AMOLED).
- Analytics dashboard: today's total ring, 7-day bars, per-app breakdown, hour-of-day heatmap, opens vs. blocked attempts, streak, estimated time saved.
- Home widgets (Glance): "Today" ring (small), "Apps" status list (wide). Smooth, spring-based animations throughout.
- Strong anti-bypass (below).

## Non-goals
- Play Store distribution (Accessibility policy makes it unlikely). Sideload only.
- Accounts, cloud sync, iOS, tablets-specific layouts.
- Device Owner mode (approach C). Code is kept compatible so it can be added later.

## Anti-goals
- A "just this once" or "skip" button anywhere.
- Any always-running foreground service, polling loop, or large dependency (chart library, Hilt, image loaders).

## Approaches considered
- **A. Self-protection + change cooldown.** Stops impulsive removal; safe mode, adb and factory reset remain.
- **B. A + commitment lock (chosen by the user).** Adds a pledge period with a passcode that is shown once.
- **C. B + Device Owner via adb.** Closest to unbypassable, but needs a freshly reset phone with no accounts, is risky to develop without a device, and can lock the user out of their own settings. Deferred.

## Stack (my calls, stated so they can be corrected)
Kotlin, Jetpack Compose with Material 3 Expressive, Room (events), DataStore (settings/rules/pledge), Glance (widgets), Navigation Compose, manual DI (no Hilt). minSdk 29, target/compile 36. R8 + resource shrinking on release; vector icons only; system font. Charts drawn with Compose Canvas. Gradle wrapper pinned and run on JDK 17 (the system default is JDK 27, which Android Gradle Plugin does not support).

## Architecture
- **Detection: an AccessibilityService, event-driven.** It listens to `TYPE_WINDOW_STATE_CHANGED` only. `packageNames` is left unset so the service also sees the user leaving to the launcher; events are filtered in code (window-state changes only, which are infrequent). No polling.
- **Session engine (pure Kotlin, unit-tested).** Inputs: rule, current state, now. Outputs: `Allow(until)`, `Prompt`, `Blocked(until, reason)`.
  - A session is a wall-clock window. Leaving the app early does not refund time.
  - The lockout starts when the session ends.
  - Daily usage counts actual foreground seconds, accumulated from window events.
  - While an app is in the foreground with an active session, exactly one `AlarmManager` alarm is set, for the earlier of session end and cap reached.
  - All state is stored as absolute timestamps, so it survives reboot and process death.
- **Overlays** are drawn from the service with `TYPE_ACCESSIBILITY_OVERLAY` (Compose in a `ComposeView`). No "display over other apps" permission is needed. Fallback: a full-screen Activity if the overlay misbehaves on a given device. After "time's up" or a block, the service sends the user Home.
- **Time tampering.** Store `elapsedRealtime` anchors alongside wall-clock times. If the system clock jumps, cooldowns and pledges are never shortened. Date & Time settings are included in the guard during a pledge.

## Anti-bypass (approach B)
1. **Guard.** The service watches Settings, the package installer, and the permission controller. When the window text or title matches this app's name, or it is the Accessibility, Device Admin, Usage Access, battery-optimization or Date & Time page, it sends the user Home immediately. Matching is by package/class plus app-name text; localized-text coverage is best-effort.
2. **Device Admin** is enabled to block plain uninstall. It cannot be deactivated while the guard or a pledge is active.
3. **Change cooldown.** Making rules stricter applies immediately. Loosening anything (longer session, shorter lockout, higher cap, removing an app) is queued for 24 h and shown as pending. Pending changes can be cancelled.
4. **Pledge.** At setup the user chooses 7, 30 or 90 days. A 24-character passcode is generated once, shown once with a "give this to someone else" prompt, and only a salted PBKDF2 hash is stored. During the pledge, protection cannot be disabled and the pledge cannot be shortened. The passcode is the only way out early. There is no recovery path.
5. **After the pledge**, disabling protection needs a 48 h waiting period and typing a long confirmation phrase.
6. **Emergency pass (user request, parameters are my calls).** 2 passes per calendar month. On the block screen: 60 s forced wait, then type a short phrase, then a 10-minute window for that one app, ignoring lockout and daily cap. The pass count is set at setup and cannot be raised during a pledge (afterwards, raising it follows the 24 h loosening rule). Each use is logged, shown on the dashboard, and resets the streak. It never affects the guard, pledge or rules.
7. **Known holes (stated plainly):** safe mode, `adb uninstall`, factory reset, and clearing app data from a recovery environment. Closing these needs approach C.

## Analytics and widgets
- Room table `usage_event` (package, start, end, planned, kind) plus a per-day aggregate row. Dashboard queries read the aggregates. Raw events are pruned after 90 days.
- Widgets show absolute times ("Unlocks 4:30 PM") instead of live countdowns, so no ticking updates. They refresh on state change and once at midnight.

## Quality and verification
- Unit tests for the session engine (tier lookup, daily cap, midnight rollover, clock-jump handling) and for pledge/cooldown logic.
- `assembleDebug` and unit tests run in this environment. On-device behaviour (overlay, guard, widgets) is verified with a manual checklist, since there is no emulator or phone attached to this machine. The user installs the APK with `adb`.
- Targets: release APK under about 6 MB; no wakeups when no blocked app is in the foreground.

## Environment setup (after approval)
Install the Android command-line tools and the platform and build-tools into `~/Android/Sdk`, accept licenses, and set `JAVA_HOME=/usr/lib/jvm/java-17-openjdk` for the project's Gradle only. The system default JDK is left alone.

## Left to the builder
Exact tier defaults, screen layouts and motion specs, theme presets, icon design, package name `com.jscyril.ebb` (app name **Ebb**, chosen by the user), per-OEM overlay quirks.
