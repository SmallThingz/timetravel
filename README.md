<h1>
  <img src="SaidIt/src/main/icon.svg" alt="TimeTravel app icon" width="52" valign="middle" />
  TimeTravel
</h1>

![android](https://img.shields.io/badge/android-30%2B-3ddc84?logo=android&logoColor=0f172a)
![kotlin](https://img.shields.io/badge/kotlin-2.1.10-7c3aed?logo=kotlin&logoColor=ffffff)
![material](https://img.shields.io/badge/ui-Material%203-2563eb)

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

## 📸 Screens

<p>
  <img src="docs/home.png" alt="Home screen" width="220" />
  <img src="docs/recordings.png" alt="Recordings screen" width="220" />
</p>

<p>
  <img src="docs/settings_1.png" alt="Settings screen 1" width="220" />
  <img src="docs/settings_2.png" alt="Settings screen 2" width="220" />
</p>

## 🛠️ Build

```bash
./gradlew :SaidIt:assembleDebug
./gradlew :SaidIt:assembleRelease
```
