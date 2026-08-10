# RTTS — Radio-to-Text Transcription System

App Android para tablet que transcribe en vivo la comunicación de radio piloto ↔ torre de control (offline, sin conexión a internet), usando [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Whisper + VAD).

## Estructura

- `rtts-app/` — proyecto Android real (Fase 1 MVP): login local, captura de audio (línea analógica / archivo de prueba / selector de archivo), transcripción en vivo.
- `spike-android/` — spike de validación de Fase 0 (no es parte del producto final, se conserva como referencia).
- `*.mp4`, `*.mpeg` — audios de muestra de comunicación ATC real, usados como fixtures de desarrollo.

## Requisitos para compilar

- JDK 21
- Android SDK (compileSdk 34, minSdk 26) con `platform-tools`, `platforms;android-34`, `build-tools;34.0.0`
- Gradle 8.9

## Modelos de IA (no incluidos en el repo)

Los modelos ONNX (~154 MB) están excluidos del repo por tamaño (el decoder de Whisper `base` supera el límite de 100 MB de GitHub). Antes de compilar, hay que colocarlos en `rtts-app/app/src/main/assets/models/`:

```
assets/models/
├── silero_vad.onnx
└── sherpa-onnx-whisper-base/
    ├── base-encoder.int8.onnx
    ├── base-decoder.int8.onnx
    └── base-tokens.txt
```

Descarga:
- VAD: https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
- Whisper base: https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2 (extraer solo `base-encoder.int8.onnx`, `base-decoder.int8.onnx`, `base-tokens.txt`)

## Compilar

```
cd rtts-app
gradle :app:assembleDebug
```

El AAR de sherpa-onnx (`app/libs/sherpa-onnx-1.13.4.aar`) sí está versionado — se compiló a partir del código fuente oficial de sherpa-onnx v1.13.4 más las librerías nativas prebuilt del proyecto.

## Instalar en la tablet

Con la tablet conectada por USB y depuración USB activada:

```
adb install -r rtts-app/app/build/outputs/apk/debug/app-debug.apk
```
