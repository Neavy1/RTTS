# Android review export v1

Android provides Revisión ATC with historical segments, WAV playback, separate human spoken/display text, reviewer/language/status, noise and clipping flags, 30-second prompted recording, and Storage Access Framework ZIP export. New edits remain separate from immutable recognizer output in private AtomicFile JSON records. Existing Room schema remains unchanged.

ZIP schema `rtts.atc.review.v1`: `records.jsonl`, `README.txt`, `audio/<installation UUID>-<segment ID>.wav`. Each JSON row includes stable id, audio path/SHA-256, duration/sample format, source_group, source_kind, original_text/detected_language, text/display_text, language/status/reviewer, notes/noisy/clipped and timestamps. Export validates every audio with PcmWavReader; a missing audio fails the export rather than silently dropping records. It builds a complete cache ZIP before opening the destination picker.

Import a user-supplied ZIP into a **new** folder:

```powershell
python training/atc/import_mobile.py RTTS-ATC.zip --output training/atc/local/mobile-batch --split train
python training/atc/atc_dataset.py validate training/atc/local/mobile-batch/manifest.jsonl
```

Choose the split deliberately, keeping all clips of a session/source_group together across batches. The app does not assign splits. Import checks schema, fixed safe audio names (no arbitrary ZIP extraction), duplicate IDs/entries, sizes, WAV format/duration, SHA-256 and review requirements. It preserves original text and source kind, never promotes pending labels, and creates the same manifest/review page used by the existing tooling.

`source_sha256` in imported manifests is the available **clip** hash (`source_hash_scope=clip`), not a hash of the original full radio session. source_group links segments of the same session. This supports exact duplicate detection but cannot detect overlapping/re-encoded recordings; inspect provenance before combining datasets.

Repeated exports contain the same IDs. On aggregation, reconcile reviews by id/timestamp while requiring unchanged audio hash. Do not count duplicates as new examples, split the same session across train/dev/test, or silently mix `spoken_example` with operational audio when reporting quality. `legacy_unknown` requires provenance review. `qa_synthetic` is test data, not real training audio.

Verification: `ReviewSmokeInstrumentation` runs on an Android device with isolated review storage, checks real JSON/AtomicFile/ZIP APIs and exports a synthetic QA fixture. Run after assembling and installing both APKs:

```powershell
adb shell am instrument -w com.rtts.app.test/com.rtts.app.ReviewSmokeInstrumentation
python -m unittest discover -s training/atc -v
```

The synthetic fixture uses zero samples and status no_speech, never a verified speech label. Physical microphone quality and real human correction quality are separate from these tests.
