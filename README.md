<h1>
  <img src="docs/app-icon.svg" alt="TimeTravel app icon" width="48" valign="middle" />
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

## 🛠️ Build

```bash
./gradlew :SaidIt:assembleDebug
./gradlew :SaidIt:assembleRelease
```
