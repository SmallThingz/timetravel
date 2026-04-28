<h1>
  <img src="docs/app-icon.svg" alt="TimeTravel app icon" width="44" valign="middle" />
  TimeTravel
</h1>

`Android` `Kotlin` `Material 3`

Rolling audio buffer recorder for Android. Keeps recent audio alive, survives restarts, and exports past audio fast.

Forked from `Echo`, now reworked and shipped as `TimeTravel`.

## ✨ Highlights

- Rolling buffer with disk-backed restore
- Fast export in selected output format
- In-app player, rename, share, delete, and move flows
- Hardware-aware codec, rate, and channel selection

## 📦 App

- Package: `app.smallthingz.timetravel`
- Module: `SaidIt`

## 🛠️ Build

```bash
./gradlew :SaidIt:assembleDebug
./gradlew :SaidIt:assembleRelease
```
