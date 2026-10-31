# Sparkle Lock

The Sony Xperia Z2 (Android 4.4) "fairy dust" lock screen, rebuilt for Android 9 and newer.
No root, no accessibility service, no internet permission.

## What it does

- **Fairy dust**: touch and hold anywhere and a cloud of tiny white specks swarms around your
  fingertip. They drift and twinkle out, and leave a trail when you move.
- **Flowing colours**: while your finger is down, the whole wallpaper slowly cycles through the
  colour wheel: blue → purple → magenta → red → orange → yellow → green → … By default it keeps
  the colour it reached for next time, as the Z2 did.
- **Unlock on release**: nothing unlocks while your finger is down, so you can play as long as you
  like. Once you've moved far enough from where you touched, letting go unlocks with a puff of dust
  and a fade.
- **Camera shortcut**: swipe up from the camera icon (bottom right) to open the camera, even when
  locked.
- **Widgets**: swipe left/right on the clock for your widgets (music, weather, calendar…), "+" to
  add more.
- **Lock & unlock sounds**: a click when the screen turns off, and another when the phone unlocks
  (only with the ringer on; adjustable volume, can be turned off).
- Haptics, current wallpaper / chosen image / built-in silk wallpaper, PIN & fingerprint hand-off,
  hidden navigation bar, Android 9 compatible rendering.

## Install & set up

1. Install `release/SparkleLock-v1.4.apk`. If Play Protect says it doesn't recognise the app, tap
   *More details → Install anyway*.
2. Open **Sparkle Lock**, turn on **Enable sparkle lock screen**, and grant **Display over other apps**.
   On Android 15+, if it's greyed out: App info → ⋮ → *Allow restricted settings* first.
3. Set battery to **Unrestricted**. On Xiaomi/Oppo/Vivo, also allow *Show on lock screen* and
   *Open new windows while running in background*.

## How it works

A foreground service opens the lock screen activity (`showWhenLocked`) when the screen turns off,
so it sits above the system keyguard. Unlocking asks Android to dismiss its keyguard. With a
PIN/pattern, the app hands over to Android's own unlock screen, so security is unchanged.
The colour shift is a luminance-preserving hue rotation (`ColorMatrix`) applied to the wallpaper.
The dust is a particle system drawn with batched `drawPoints`, which works on every Android version's
hardware renderer.

## Build

```
./gradlew assembleDebug           # or assembleRelease (signed when keystore/ is present)
./gradlew testReleaseUnitTest     # Robolectric tests on Android 9, 11 and 14 + rendered frames
```

The release signing key is **not** in this repository.
