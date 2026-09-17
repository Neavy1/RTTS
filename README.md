# RTTS — Radio-to-Text Transcription System

App para tablet que transcribe en vivo la comunicación de radio piloto ↔ torre de control (offline, sin conexión a internet), usando [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Whisper + VAD). Plataforma final: **iPad (iOS)**. Estado actual del código: Android/Kotlin Multiplatform, funcional y auditado; iOS pendiente de una Mac.

> Este documento está escrito para que cualquiera (humano o IA) que retome el proyecto entienda en minutos qué existe, qué se decidió y por qué, y qué falta. Si vas a tocar código aquí, léelo completo antes de empezar.

## Estado actual (17 de septiembre de 2026)

**Decisión de arquitectura vigente: nos quedamos con sherpa-onnx + Whisper. No se migra a whisper.cpp.** Se evaluó explícitamente la migración y se descartó tras una auditoría con datos reales — ver "Auditoría de producción" abajo. Cualquier sugerencia futura de cambiar el motor de inferencia debe justificarse contra esos datos, no contra preferencia genérica.

Lo que funciona hoy, probado end-to-end en emulador Android (Pixel Tablet, Android 14, x86_64):

- Transcripción 100% local (Whisper `small` int8 vía sherpa-onnx/ONNX Runtime), sin red.
- Pipeline completo: `MediaExtractor/MediaCodec → PCM → resample 16kHz mono → VAD (Silero) → Whisper → Room → UI en vivo`.
- Reproducción de audio: tanto reproducción bajo demanda de cada segmento ya transcrito (para verificar de oído) como reproducción del audio original **en segundo plano mientras se transcribe** (para escuchar la transmisión mientras el texto va apareciendo).
- El texto de cada transcripción aparece palabra por palabra (estilo chat), no de golpe.
- Reintento automático de idioma (es→en→es) cuando Whisper detecta un idioma fuera de es/en o el texto tiene pinta de alucinación (bucle de repetición).
- Login por PIN construido y probado, **pero suspendido temporalmente** (`MainActivity.kt`, `startDestination = "transcript"`) para agilizar las pruebas — el código sigue intacto en `feature/login/`, solo hay que restaurar `startDestination = "login"` cuando se retome.

### Bug de ANR corregido (17/09/2026)

`TranscriptionForegroundService.startCapture()` lanzaba todo el trabajo pesado (carga del modelo — hasta ~18s para `small` — más VAD y decodificación de cada chunk) dentro de `lifecycleScope.launch { }` **sin especificar dispatcher**, es decir, en el hilo principal. Esto producía el diálogo de Android "la aplicación se ha detenido" (ANR) cada vez que el modelo tardaba en cargar o un archivo era largo. Corregido cambiando a `lifecycleScope.launch(Dispatchers.Default) { ... }`. Si vuelve a aparecer un ANR durante carga/procesamiento, **revisar primero que ningún código nuevo esté corriendo trabajo pesado sin `withContext(Dispatchers.Default)` o sin dispatcher explícito en un `launch`** — es el error más fácil de reintroducir en este proyecto porque `lifecycleScope.launch` sin argumentos usa `Dispatchers.Main` por defecto.

### Auditoría de producción (17/09/2026)

Antes de tocar código se auditó la implementación actual contra 10 criterios (modelo, pipeline de audio, precisión, rendimiento, arquitectura de streaming, latencia, alucinaciones, tamaño de modelo, abstracción `SttEngine`, objetivo final). Resultados clave, con audio real de radio (4 archivos, 882.6s totales), comparando `tiny`/`base`/`small`:

| Métrica | tiny | base | small (en producción) |
|---|---|---|---|
| RTF global | 0.04x | 0.07x | 0.35x |
| RAM pico (PSS) | 482 MB | 602 MB | 970 MB |
| Tiempo de carga | 1.14 s | 3.81 s | 18.56 s |
| Calidad en frases ATC | Baja (alucina) | Media | Alta — y es honesto: marca `[INAUDIBLE]` en vez de inventar texto |

Desglose de latencia (con `small`): **97.3% del tiempo es inferencia de Whisper**; VAD + decodificación/resample combinados son solo 2.7%. Cualquier optimización futura de rendimiento debe apuntar al modelo (threads, cuantización, hardware), no al pipeline de audio — ahí no hay nada que optimizar.

Hallazgos pendientes de corregir (no bloquean producción, pero están documentados y localizados):

1. **Arranque en frío**: el primer segmento tras cargar el modelo tuvo RTF 1.78x (vs. 0.35x global). Se resolvería precargando el modelo al iniciar la app en vez de al primer `startCapture()`.
2. **Bug real en el detector de alucinaciones** (`looksLikeHallucination()` en `SherpaOnnxSttEngine.kt`): compara palabras sin separar puntuación, así que una repetición real detectada en la salida de `tiny` ("todo el chero, todo el chero...") no fue marcada como alucinación porque "todo," ≠ "todo" como tokens.
3. **`AudioResampler` sin filtro anti-aliasing**: hace decimación por vecino más cercano al bajar a 16kHz. Riesgo si entra audio que no viene ya en 16kHz mono (no ha causado problemas observados todavía, pero no está probado con esa condición).
4. **Todo lo medido es en emulador x86_64**, no representa rendimiento real en tablet ARM. Sigue siendo el hueco más importante antes de dar por cerrada la fase de producción: falta un dispositivo Android físico para validar.

Herramienta de benchmark (`app/src/main/kotlin/com/rtts/app/benchmark/BenchmarkRunner.kt`): corre el pipeline completo contra los audios de `assets/samples/`, mide RTF/RAM/tiempos por archivo y global, y escribe un reporte de texto a `getExternalFilesDir(null)/benchmark/`. Se dispara con una acción del servicio (`TranscriptionForegroundService.ACTION_BENCHMARK`) — hace falta foregrounding manual de la app antes de invocarlo por `adb` en Android 12+ (restricción de inicio de foreground services en background). No forma parte de la abstracción `SttEngine`: es diagnóstico específico de Android, deliberadamente.

### Próxima fase: Mac para iOS

Se decidió qué comprar: **MacBook Air 15" (chip M4), 16 GB de RAM como mínimo, 24 GB si el presupuesto lo permite**. Justificación: Whisper corre en el iPad, no en el Mac — el Mac solo compila/firma/despliega con Xcode, así que un MacBook Pro sería sobrecosto. El 15" (no el 13") es por espacio de pantalla para tener Xcode y el Simulador de iOS lado a lado, no por más cómputo. Precios de lista aproximados (verificar al comprar): ~USD 999 (13") / ~USD 1,199 (15") para la config. base M4; Apple ya vende M5 desde marzo 2026 a ~USD 1,299/1,499 si se prefiere el chip más reciente.

## Arquitectura

- `rtts-app/` — proyecto Kotlin Multiplatform:
  - `shared/` — módulo KMP compartido entre Android e iOS.
    - `commonMain/` — sin dependencias de plataforma: interfaz `AudioSource`, contrato `SttEngine`, contrato `AuthRepository`, tema y pantalla de login en Compose Multiplatform.
    - `androidMain/` — implementaciones Android: `SherpaOnnxSttEngine` (única implementación concreta de `SttEngine` — el resto de la app nunca referencia sherpa-onnx directamente), fuentes de audio (`AudioRecord`/`MediaCodec`), Room, auth.
    - `iosMain/` — stubs a implementar en iOS (ver sección "iOS" abajo).
  - `app/` — shell de la app Android: `MainActivity`, `TranscriptionForegroundService` (orquesta el pipeline como foreground service), pantalla de transcripción en vivo, `benchmark/BenchmarkRunner`, manifest, assets de modelos y muestras de audio.
- `spike-android/` — spike de validación de Fase 0 (no es parte del producto final, se conserva como referencia).

Pipeline de captura (`TranscriptionForegroundService.startCapture()`): `AudioSource.start()` (Flow de chunks PCM) → `SttEngine.acceptAudioChunk()` (VAD, devuelve segmentos completos de voz) → `SttEngine.transcribe()` por segmento → persistencia en Room → la UI observa Room vía `Flow` y se actualiza sola. Todo corre en `Dispatchers.Default`, nunca en el hilo principal (ver nota de ANR arriba).

Configuración actual de `SherpaOnnxSttEngine`: VAD Silero con `threshold=0.6`, `minSpeechDuration=0.3s`, `minSilenceDuration=0.5s`, `maxSpeechDuration=20s`; 4 threads; `task="transcribe"` (nunca traduce); reintento de idioma en cadena es→en→es cuando el resultado no es es/en o parece alucinado.

## Requisitos para compilar

- JDK 21
- Android SDK (compileSdk 34, minSdk 26) con `platform-tools`, `platforms;android-34`, `build-tools;34.0.0`
- Gradle 8.9

## Modelos de IA (no incluidos en el repo)

Los modelos ONNX (~360 MB) están excluidos del repo por tamaño (el decoder de Whisper supera el límite de 100 MB de GitHub). Antes de compilar, hay que colocarlos en `rtts-app/app/src/main/assets/models/`:

```
assets/models/
├── silero_vad.onnx
└── sherpa-onnx-whisper-small/
    ├── small-encoder.int8.onnx
    ├── small-decoder.int8.onnx
    └── small-tokens.txt
```

Descarga:
- VAD: https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
- Whisper small: https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2 (extraer solo `small-encoder.int8.onnx`, `small-decoder.int8.onnx`, `small-tokens.txt`)

`small` es el tamaño de modelo en producción, confirmado por la auditoría del 17/09/2026 (tabla arriba). No cambiar a `tiny`/`base` sin repetir ese benchmark — ambos alucinan con más frecuencia en frases ATC cortas.

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

El proyecto ya está estructurado como Kotlin Multiplatform (módulo `shared/` con `commonMain`/`androidMain`/`iosMain`), y Compose Multiplatform para iOS es estable desde su versión 1.8 — la app comparte la UI de login, el tema y los contratos (`AudioSource`, `SttEngine`, `AuthRepository`) entre ambas plataformas. Pero **falta un Mac** para terminarlo: Kotlin/Native solo compila el target iOS en un host macOS (restricción de Apple/Xcode, no de este proyecto), así que nada de esto se pudo probar todavía. Ver "Próxima fase: Mac para iOS" arriba para qué máquina comprar.

Los targets iOS están declarados en `shared/build.gradle.kts` pero solo se activan si `os.name` contiene "Mac", para no romper el build de Android en Windows/Linux.

Lo que falta implementar, en orden, todo en `shared/src/iosMain/kotlin/com/rtts/app/`:

1. **`asr/IosSttEngine.kt`** — build de sherpa-onnx para iOS (produce un `.xcframework`, ver [docs oficiales](https://k2-fsa.github.io/sherpa/onnx/ios/build-sherpa-onnx-swift.html)) + un binding cinterop de Kotlin/Native contra su API en C (`sherpa-onnx-c-api.h`), análogo al AAR/JNI que se usa en Android.
2. **`audio/IosMicrophoneAudioSource.kt`** — captura de audio con `AVAudioEngine`/`AVFoundation`, igual que `AnalogLineInAudioSource` usa `AudioRecord` en Android.
3. **`auth/IosAuthRepository.kt`** — decidir persistencia (Keychain vs `NSUserDefaults`) y hashing del PIN: `javax.crypto` (usado en `AndroidAuthRepository`) no existe en Kotlin/Native, hace falta una librería de crypto multiplataforma (p. ej. Ionspin `multiplatform-crypto`) o un binding a CryptoKit.
4. Crear el proyecto `iosApp/` en Xcode que consuma el framework `shared` y llame a la UI compartida.

Cada uno de estos archivos ya tiene un comentario `TODO(iOS)` explicando exactamente qué hacer.

## Qué falta / próximos pasos

En orden de impacto:

1. **Probar en un dispositivo Android físico (ARM)** — el hueco más importante ahora mismo. Todo lo medido es en emulador x86_64.
2. Precargar el modelo al iniciar la app (elimina el arranque en frío, hallazgo #1 de la auditoría).
3. Corregir `looksLikeHallucination()` para tokenizar sin puntuación (hallazgo #2).
4. Evaluar si hace falta un filtro anti-aliasing en `AudioResampler` (hallazgo #3) — solo si empieza a entrar audio que no está ya en 16kHz.
5. Comprar el MacBook Air 15" y arrancar la implementación de `iosMain/` en el orden descrito arriba.
6. Restaurar el login (`startDestination = "login"` en `MainActivity.kt`) cuando se retome el flujo de autenticación de verdad.
7. Integrar diarización de hablantes (piloto/torre) — se probó por separado en la Fase 0 pero no está integrada al MVP actual.
