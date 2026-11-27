# G.I Core AI — v2 Feature Plan

Design direction: PrivateLM-inspired information architecture with an original G.I identity. Dark, premium Midnight Gold UI; compact cards and clear navigation. Target: Redmi 9A, Android 10, armeabi-v7a, 3 GB RAM. Local-first; no model bundled in the APK.

## 30 planned capabilities

1. GGUF import through Android Storage Access Framework.
2. GGUF header and metadata validation before loading.
3. Model library with size, path, and load state.
4. Model unload and safe replacement.
5. Low-memory ARMv7 preset (CPU-only, conservative context and threads).
6. Adjustable context length with device-safe limits.
7. Adjustable output-token limit.
8. Streaming token output.
9. Stop-generation control.
10. Regenerate last answer.
11. Copy message and share answer.
12. Markdown rendering for headings, lists, and code.
13. Multiple named conversations.
14. Conversation search.
15. Rename, archive, and delete conversations.
16. Export conversation to TXT/Markdown/JSON.
17. Import conversation backup.
18. System-prompt preset library.
19. Per-model generation presets.
20. Sampling controls (temperature, top-p, top-k, repeat penalty) where supported by the native bridge.
21. Context/token usage indicator when supported by the engine.
22. Runtime diagnostics (ABI, engine state, heap, model size).
23. Tokens-per-second and load-time metrics.
24. RAM-aware model-load guard and clear error messages.
25. Model checksum verification.
26. Resumable model download with explicit user action.
27. Offline document library and text/PDF extraction.
28. Local document Q&A / lightweight RAG.
29. Optional OpenAI-compatible local HTTP server, disabled by default and loopback-only by default.
30. Privacy controls, local-only mode, and data export/clear controls.

## Delivery rules

- Do not display a feature as available until its implementation and tests exist.
- Prioritize ARMv7 build correctness and real-device stability over feature count.
- Keep native inference CPU-only on this target; do not enable ARM64-only kernels or Vulkan paths.
- Keep model files outside the APK. Recommend a small text-only Q4 GGUF first.
- Preserve the existing application ID `com.gi.localai` so test upgrades do not create a second app.
- Run GitHub Actions on this feature branch and inspect the build result before calling the APK installable.
