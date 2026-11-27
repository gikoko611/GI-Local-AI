# G.I Local AI — Private GGUF Assistant

G.I Local AI is an on-device Android assistant built around llama.cpp and GGUF models. It is designed first for older 32-bit Android phones, including Redmi 9A / Android 10 / armeabi-v7a / 3 GB RAM.

## Build identity

- App: **G.I Local AI**
- Application ID: `com.gi.localai` (debug APK uses `com.gi.localai.debug`)
- Version: `0.2.0`
- Minimum Android: 8.0 / API 26 (Android 10 is supported by the declared minimum)
- Native ABI: `armeabi-v7a` only
- Engine: llama.cpp, pinned to `b4514`
- Model files are imported by Android Storage Access Framework and copied into app-private storage; they are not bundled in the APK.
- No account, analytics, telemetry, network permission, or cloud inference is included.

## Included feature set (30+)

1. On-device chat UI.
2. GGUF model picker using Android Storage Access Framework.
3. Persistable read permission for selected model URI.
4. Model copy into app-private storage before native loading.
5. Free-storage preflight before model copy.
6. SHA-256 checksum after model copy.
7. Native llama.cpp inference.
8. ARMv7 / CPU-only model loading.
9. Explicit model load status.
10. Model unload control.
11. Stop-generation control.
12. Configurable context size (512–2048).
13. Configurable CPU thread count (1–4).
14. Output token limit (32–256).
15. Top-K sampling (native default 40).
16. Configurable Top-P sampling.
17. Configurable temperature sampling.
18. Local SQLite conversation history.
19. Search within saved messages.
20. Export conversation as Markdown.
21. Copy individual messages.
22. Selectable message text.
23. Editable system prompt.
24. Save prompt presets locally.
25. Load saved prompt presets.
26. Low-memory preset.
27. Java heap metric.
28. Native heap metric.
29. App-private free-storage metric.
30. Last generation duration metric.
31. Premium dark chocolate / champagne-gold interface.
32. Custom G.I adaptive launcher icon.
33. No model bundled in APK, reducing install size.
34. Local-only privacy status in the UI.

## Redmi 9A recommendations

Start with a small 0.3B–0.6B GGUF Q4 model. On a 3 GB device, select the low-memory preset (512 context, one thread, 96 output tokens). Larger models may fail to load or cause Android to kill the app. Performance and memory use depend on the exact model and other running apps.

## Build with GitHub Actions (no PC required)

1. Open the repository's **Actions** tab.
2. Run **Android APK** using `workflow_dispatch`, or push/merge this branch.
3. Wait for the ARMv7 build job to finish.
4. Download artifact `GI-Local-AI-armv7-debug`.
5. Extract and install `app-debug.apk` on the phone.

The workflow installs JDK 17, Android SDK / NDK, CMake 3.22.1 and Gradle 8.9. It builds only `armeabi-v7a`.

## Technical notes

- llama.cpp source is fetched at the pinned tag `b4514` during CI.
- JNI exposes model load, generation, stop and unload.
- Native generation uses a bounded context, top-k, top-p, temperature and a sampling seed.
- Token output is returned after generation completes; token-by-token streaming is not yet implemented.
- The selected model is copied into the app's private files directory. Keep enough free storage for both the source model and copied model.
- A successful GitHub Actions build verifies compilation, not runtime inference on every phone/model. Test with a small GGUF first.
- This project does not bundle model weights. Only use model files you are licensed and authorized to use.
