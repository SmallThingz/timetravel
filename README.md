# TimeTravel

TimeTravel is an Android audio time-buffer recorder. It continuously keeps a rolling window of recent audio, lets you pause without clearing the buffer, and exports the past audio on demand.

This codebase started as a fork of `Echo`, but the current app is rebranded and developed as `TimeTravel`.

## Status

- Package: `app.timetravel`
- Platform: Android
- UI: Kotlin + Material 3
- Output: hardware-supported audio codecs, sample rates, and channel modes

## Core behavior

- Maintains a rolling audio buffer in memory with disk-backed persistence support.
- Restores buffered audio after normal process death and relaunch.
- Supports live export in the selected output format.
- Includes in-app playback, recording management, rename, delete, share, and move-to-folder flows.

## Architecture

- `TimeTravelFragment`: main capture screen and export controls.
- `SavedRecordingsFragment`: saved recordings browser, selection actions, and in-app playback entry point.
- `TimeTravelService`: foreground audio service, rolling buffer manager, export pipeline, and persistence coordinator.
- `AudioMemory`: in-memory PCM ring buffer.
- `PersistentAudioRingStore`: mmap-backed persisted buffer cache for relaunch recovery.
- `LiveExportHistory`: live rolling export cache used to make saves fast in the selected output format.

## Build

```bash
./gradlew :SaidIt:assembleDebug
./gradlew :SaidIt:assembleRelease
```

## Distribution

This repository does not currently document a live public store listing for the rebranded app. If that changes, add the current listing here instead of the legacy `eu.mrogalski.saidit` entry.
