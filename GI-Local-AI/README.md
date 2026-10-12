# G.I Local AI (ARMv7 MVP)

Cyber-terminal Android UI for local GGUF inference. Model files are imported from device storage; they are not bundled into the APK.

## Important build note
The workflow fetches a pinned `llama.cpp` source revision and compiles its native library. This is a starter MVP, not a verified release build. Native C API changes between llama.cpp revisions can require small compatibility fixes. GitHub Actions logs are the source of truth for the first build.

## Build using GitHub Actions
1. Create a GitHub repository named `GI-Local-AI`.
2. From Termux, upload this project folder with git. The workflow installs Gradle 8.9, so a local wrapper is not required.
3. Open the repository's **Actions** tab.
4. Run **Android APK** (or push to `main`).
5. Download artifact `GI-Local-AI-armv7-debug`.
6. Install `app-debug.apk`.

## Termux setup
```sh
pkg update
pkg install git unzip
cd ~
unzip GI-Local-AI.zip
cd GI-Local-AI
git init
git add .
git commit -m "feat: create G.I Local AI ARMv7 MVP"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/GI-Local-AI.git
git push -u origin main
```

## Current MVP features
- Cyber terminal Compose UI
- GGUF file picker
- Native model load/unload and greedy generation
- Local SQLite chat history
- System prompt presets
- Java heap and output token controls

## Limitations to address after first successful build
- Confirm the device supports `armeabi-v7a`; Android shell architecture alone does not prove app ABI support.
- The selected GGUF is copied to app-private storage before native loading; this requires free storage approximately equal to the model file size.
- ARMv7 is CPU-only and may be slow; 0.5B quantized models are a more realistic starting point.
- Native API compatibility may need adjustments against the pinned llama.cpp tag.
- Token streaming, stop-generation cancellation, tokenizer-aware chat templates, model checksums, and native RSS / tokens-per-second metrics are planned improvements.
- Use only GGUF files you are licensed and authorized to use.

## Generate the Gradle wrapper before first push
The archive contains a bootstrap placeholder because wrapper JARs are binary files. Install a compatible Gradle distribution in a Linux/Ubuntu environment, then run:
```sh
gradle wrapper --gradle-version 8.9
```
Commit `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` + `gradle-wrapper.properties`. On Termux, wrapper generation may not be available; alternatively change the workflow to install Gradle and run `gradle assembleDebug`.
