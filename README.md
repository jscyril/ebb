<p align="center"><img src="docs/brand/ebb-logo-512.png" width="120" alt="Ebb logo"></p>

# Ebb

An Android app blocker that is hard to talk yourself out of. Open a blocked app, pick a session length, and a lockout follows.

**Why:** blockers that you can remove in ten seconds don't help when the urge hits. Ebb puts friction in the way of removing it, not only in the way of the apps.

<p align="center">
  <img src="docs/screenshots/home.png" width="200" alt="Home: today's usage, opens, time left">
  <img src="docs/screenshots/prompt.png" width="200" alt="Session prompt over instagram.com in Chrome">
  <img src="docs/screenshots/block.png" width="200" alt="Block screen with unlock time">
  <img src="docs/screenshots/insights.png" width="200" alt="Insights: today's ring and streak">
</p>

## Features

- **Session prompt.** Opening a watched app asks how long you want. Each length carries a lockout once it ends. Defaults (all editable per app):

  | Session | Lockout |
  |---|---|
  | 5 min | 1 h |
  | 15 min | 2 h |
  | 30 min | 4 h |
  | 60 min | 8 h |

- **Daily cap.** Default 60 min per app. Once reached, the app is locked until local midnight, even if used in short bursts.
- **Shared daily limit** (optional, Rules tab). One budget for all watched apps together, on top of each app's own limit, so time doesn't just move to the next app.
- **Notifications snoozed while locked.** A locked app's notifications are snoozed until it unlocks, so they can't pull you back in; nothing is lost. Needs notification access (Settings > Notifications).
- **Tapering** (optional, per app). The daily limit drops by a step you choose every Monday until it reaches your target, so you cut down gradually. Stopping or easing a taper waits 24 h like any loosening.
- **Grayscale** (optional, per app). The screen turns grey for the last 30 seconds of a session as a cue to wrap up.
- **Website blocking.** Each app's website shares its session, lockout and cap, so instagram.com in a browser is gated like the Instagram app. Common apps come with their domains already filled in (YouTube, TikTok, Reddit, X, Facebook and more), and you can edit the list per app under Rules > Websites. Works in Chrome, Brave, Edge, Vivaldi, Firefox, Samsung Internet, Opera and DuckDuckGo. Leaving a blocked site opens a fresh tab instead of Home.
- **Timer chip.** While a session runs, a silent countdown notification shows the time left. On Android 16+ it appears as a chip in the status bar (Live Update).

  <img src="docs/screenshots/chip.png" width="400" alt="Status bar timer chip showing 00:56">

- **Emergency passes.** 2 per calendar month. A 60 s forced wait, then a typed phrase, then 10 minutes in that one app. Each use is logged and resets your streak. No "skip" button exists anywhere.
- **Payment pause.** Google Pay (and some other payment apps) refuse to pay while a sideloaded app's accessibility service is on. Pause Ebb for 5 minutes from Settings > Payments or the "Pause Ebb" Quick Settings tile, 3 times a day; it turns itself back on when the pause ends. Needs a one-time `adb shell pm grant com.jscyril.ebb android.permission.WRITE_SECURE_SETTINGS` (grayscale uses it too).
- **Rules survive uninstalling.** Uninstalling a watched app keeps its rule (shown as "Not installed"), so reinstalling it doesn't reset your limits.
- **Insights.** Today's ring, 7-day bars, per-app breakdown, hour-of-day heatmap, opens vs blocked attempts, streak, estimated time saved.
- **Widgets.** "Today" ring, "Apps" status list and a one-tap "Pause for payment" widget (Glance).
- **Share your week.** Insights can draw this week's summary as an image for the share sheet.
- **Backup.** Export your rules to a JSON file and import them later; an import that loosens anything waits 24 h.
- **Themes.** Accent colours, dynamic colour, light/dark/AMOLED.

<p align="center">
  <img src="docs/screenshots/rules.png" width="200" alt="Rules: per-app editor with session tiers">
  <img src="docs/screenshots/rules2.png" width="200" alt="Rules: daily limit and websites">
  <img src="docs/screenshots/insights2.png" width="200" alt="Insights: week, by app, heatmap">
  <img src="docs/screenshots/settings.png" width="200" alt="Settings: theme, pledge, protection">
</p>

## How hard is it to bypass?

Hard, not impossible.

- **Settings guard.** The accessibility service sends you Home when you open Ebb's app info, uninstall dialog, accessibility page, Device Admin page, usage access or battery pages. During a pledge it also covers Date & Time, Developer options and reset pages.
- **Device Admin** blocks plain uninstall.
- **24 h wait on loosening.** Tightening a rule is instant. Longer sessions, shorter lockouts, a higher cap or removing an app wait 24 h, and can be cancelled while pending.
- **Pledge.** Choose 7, 30 or 90 days at setup. A 24-character passcode is shown once (give it to someone else) and only a salted PBKDF2 hash is stored. During a pledge limits can only get stricter, protection can't be disabled, and the passcode is the only way out early. There is no recovery path.
- **After a pledge**, turning protection off takes a 48 h wait plus a typed phrase in the 24 h after it; an unused request then expires and must be filed again. (Ending a pledge early with the passcode does not skip this wait.)
- **Self-heal.** With the optional secure-settings grant, Ebb turns its accessibility service straight back on if it is switched off while protection is on (also checked every 15 minutes, after reboots and updates).
- **Trusted clock.** Time is anchored to elapsed realtime, so changing the system clock never shortens a lockout, cooldown or pledge.

**Known holes:** safe mode, `adb uninstall`, factory reset, and changing the clock while Ebb's service is off, then rebooting (a change made while it runs is undone after a reboot). Website blocking only covers the browsers listed above; other browsers and in-app web views are not checked. The Settings guard matches English text (class names and the app name still work in other languages). Device Owner mode (set up via adb on a freshly reset phone) could close most of these and is a possible future hardening.

## Install

Sideload only; the Accessibility policy makes Play Store distribution unlikely.

1. Build with JDK 17: `JAVA_HOME=/path/to/jdk17 ./gradlew assembleRelease`. Output: `app/build/outputs/apk/release/app-release.apk`. Without a `keystore.properties` the APK is debug-signed (fine for personal use). For your own signing key, copy `keystore.properties.example` to `keystore.properties` and fill it in. Or download the debug APK from the latest CI run's artifacts.
2. `adb install app-release.apk`
3. Open Ebb and follow onboarding: pick apps and tiers, enable Device Admin, grant the usage and battery pages, enable the accessibility service last, then choose a pledge.
4. **Android 13+:** sideloaded apps get the accessibility toggle greyed out. Open Settings > Apps > Ebb > the three-dot menu > **Allow restricted settings**, then enable the service.

## Permissions

- **Accessibility service:** detects which app is in the foreground (window events, no polling) and draws the prompt/block screens over it. In supported browsers it reads only the address bar, to match watched websites. It does not read what you type or the pages you view.
- **Device Admin:** blocks plain uninstall.
- **Write secure settings** (optional, granted over adb): lets Ebb switch its own accessibility service off for a payment pause and back on afterwards. It also turns the service back on if it goes off while protection is on (switched off, or after an update or reboot).
- **Notification access** (optional): snoozes a locked app's notifications until it unlocks. Ebb never reads or stores their content.
- **Notifications:** a silent countdown while a session runs. On Android 16+ it shows as a timer chip in the status bar (Live Update).

Ebb makes no network requests and declares no `INTERNET` permission (checked in the merged release manifest). Nothing leaves your device.

## Tech stack

- Kotlin, Jetpack Compose with Material 3 (Expressive)
- Room: usage events and daily aggregates
- DataStore + kotlinx-serialization: rules, state, pledge
- Glance: home-screen widgets
- Navigation Compose
- Manual DI, no Hilt; charts drawn with Canvas
- R8 and resource shrinking; minSdk 29, target/compile SDK 36

## Project structure

```
app/src/main/java/com/jscyril/ebb/
  engine/   session engine, models, trusted clock (pure Kotlin)
  guard/    pledge, change queue, emergency pass, disable request, guard matcher, Device Admin
  data/     Room, DataStore settings, repository, aggregation
  service/  accessibility service, overlay host, foreground tracking
  widget/   Today and Apps widgets, refresh scheduling
  ui/       theme, overlays, app screens, charts
```

## Development

Requires JDK 17 (Android Gradle Plugin does not support newer system JDKs).

```
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
./gradlew test            # unit tests
./gradlew lint            # fails on errors, not warnings
./gradlew assembleRelease
```

The debug build installs alongside the release as "Ebb Debug" and has a **Test mode** switch in Settings: rule changes apply instantly, with no 24 h wait and no pledge refusal, and turning protection off skips the 48 h wait. It is stripped from release builds.

## License

[MIT](LICENSE)
