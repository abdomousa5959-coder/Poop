# POOP AI

A clean, dark-themed Android AI chat client starter built with Kotlin and Jetpack Compose.

## Build the APK

1. Open the **Actions** tab.
2. Run **Build Android APK** (or push a change to `main`).
3. Download `poop-ai-debug-apk` from the workflow run's Artifacts.

The workflow builds a debug APK. It does not require Floot.

## Project status

This repository starts as a native Android application shell. The chat UI works locally; an AI provider/backend is not connected yet. Do not put API keys in source code or commit them to Git.

## Local build

Requires JDK 17 and Gradle 8.9:

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`
