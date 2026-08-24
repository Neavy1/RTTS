# RTTS — Radio-to-Text Transcription System

App Android para tablet que transcribe en vivo la comunicación de radio piloto ↔ torre de control (offline, sin conexión a internet), usando [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Whisper + VAD).

## Estructura

- `rtts-app/` — proyecto Kotlin Multiplatform (Fase 1 MVP):
  - `shared/` — módulo KMP compartido entre Android e iOS.
    - `commonMain/` — código sin dependencias de plataforma: interfaz `AudioSource`, contrato `SttEngine`, contrato `AuthRepository`, tema y pantalla de login en Compose Multiplatform.
    - `androidMain/` — implementaciones Android: motor ASR (sherpa-onnx), fuentes de audio (`AudioRecord`/`MediaCodec`), Room, auth.
    - `iosMain/` — stubs a implementar en iOS (ver sección "iOS" abajo).
  - `app/` — shell de la app Android: `MainActivity`, `TranscriptionForegroundService`, pantalla de transcripción en vivo, manifest, assets de modelos.
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

El AAR de sherpa-onnx (`shared/libs/sherpa-onnx-1.13.4.aar`) sí está versionado — se compiló a partir del código fuente oficial de sherpa-onnx v1.13.4 más las librerías nativas prebuilt del proyecto.

## Instalar en la tablet

Con la tablet conectada por USB y depuración USB activada:

```
adb install -r rtts-app/app/build/outputs/apk/debug/app-debug.apk
```

## iOS

El proyecto ya está estructurado como Kotlin Multiplatform (módulo `shared/` con `commonMain`/`androidMain`/`iosMain`), y Compose Multiplatform para iOS es estable desde su versión 1.8 — la app comparte la UI de login, el tema y los contratos (`AudioSource`, `SttEngine`, `AuthRepository`) entre ambas plataformas. Pero **falta un Mac** para terminarlo: Kotlin/Native solo compila el target iOS en un host macOS (restricción de Apple/Xcode, no de este proyecto), así que nada de esto se pudo probar todavía.

Los targets iOS están declarados en `shared/build.gradle.kts` pero solo se activan si `os.name` contiene "Mac", para no romper el build de Android en Windows/Linux.

Lo que falta implementar, en orden, todo en `shared/src/iosMain/kotlin/com/rtts/app/`:

1. **`asr/IosSttEngine.kt`** — build de sherpa-onnx para iOS (produce un `.xcframework`, ver [docs oficiales](https://k2-fsa.github.io/sherpa/onnx/ios/build-sherpa-onnx-swift.html)) + un binding cinterop de Kotlin/Native contra su API en C (`sherpa-onnx-c-api.h`), análogo al AAR/JNI que se usa en Android.
2. **`audio/IosMicrophoneAudioSource.kt`** — captura de audio con `AVAudioEngine`/`AVFoundation`, igual que `AnalogLineInAudioSource` usa `AudioRecord` en Android.
3. **`auth/IosAuthRepository.kt`** — decidir persistencia (Keychain vs `NSUserDefaults`) y hashing del PIN: `javax.crypto` (usado en `AndroidAuthRepository`) no existe en Kotlin/Native, hace falta una librería de crypto multiplataforma (p. ej. Ionspin `multiplatform-crypto`) o un binding a CryptoKit.
4. Crear el proyecto `iosApp/` en Xcode que consuma el framework `shared` y llame a la UI compartida.

Cada uno de estos archivos ya tiene un comentario `TODO(iOS)` explicando exactamente qué hacer.
