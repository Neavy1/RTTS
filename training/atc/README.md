# Preparación de datos ATC

Herramientas locales para convertir los WAV incluidos en referencias revisables, generar borradores, evaluar transcripciones y preparar un primer entrenamiento experimental. La preparación/evaluación usa solo Python estándar; inferencia y entrenamiento tienen dependencias separadas. Las salidas de la app no cambian automáticamente.

Las correcciones recibidas en la página pública se analizan en [WEB_REVIEW.md](WEB_REVIEW.md). Los textos parciales o con marcas de incertidumbre no deben entrar como referencias `verified` aunque el formulario antiguo los haya etiquetado así.

## Segmentar por transmisiones

Para audio de radio continuo, crea clips siguiendo las regiones de voz en lugar de ventanas rígidas. Las pausas de hasta 1.25 s se conservan dentro de una misma transmisión y se añaden 200 ms de contexto:

```text
python training/atc/segment_transmissions.py AUDIO.wav --output CARPETA_NUEVA --vad-model rtts-app/app/src/main/assets/models/silero_vad.onnx --split test
```

El comando produce `manifest.jsonl`, `segmentation.json` y los WAV bajo `audio/`. Todo queda `pending`: VAD decide límites, nunca el texto correcto. Después genera borradores con `transcribe_local.py` y abre la página de revisión con `atc_dataset.py review`.

## Vocabulario

`vocabulary.json` contiene términos/expresiones en inglés de [Common Pilot-ATC Radio Phrases, FAA](https://www.faasafety.gov/files/events/NM/NM09/2026/NM09141454/Common_Pilot-ATC_Radio_Phrases.pdf) y casos fallidos observados en `videoejemplo.mp4`. Es una lista parcial para orientar anotación y cobertura, no un manual operativo ni una tabla de reemplazos automáticos. Las categorías de anotación son propias de este proyecto.

## Preparar y revisar

Python 3.10 o posterior. Desde la raíz del repositorio:

```powershell
python training/atc/atc_dataset.py prepare --output training/atc/local/pilot --seconds 20
```

Abre `revisar.html` de la carpeta generada en tu navegador. Los audios están junto a la página y no se suben a ningún servicio. Escucha cada fragmento, escribe la transcripción literal, selecciona idioma/estado e identifica al revisor. Descarga `referencias.jsonl` y guárdalo **junto a manifest.jsonl**, conservando la carpeta audio. Para continuar después, usa Recuperar revisión. Los cambios de la pestaña no se guardan automáticamente.

- `pending`: todavía no revisado; no participa en evaluación ni exportación.
- `verified`: texto confirmado escuchando el audio; apto para evaluación/exportación.
- `unintelligible`: no es posible una referencia fiel, incluso por una palabra cortada en el límite; excluido.
- `no_speech`: escucha verificada sin voz, texto vacío; mide falsos positivos, no se exporta al conjunto de entrenamiento de este piloto.

Excepción verificable sin escucha: `certify-silence` certifica exclusivamente archivos PCM16 cuyas muestras sean **todas exactamente cero**, con procedencia `pcm_all_zero`. No aplica umbrales de volumen ni interpreta ruido como silencio. Se guarda en un manifiesto separado, no sobrescribe referencias. El validador comprueba de nuevo el audio de cada certificado.

```powershell
python training/atc/atc_dataset.py certify-silence training/atc/local/pilot/manifest.jsonl --output training/atc/local/pilot/referencias-silencio.jsonl
```

Los cortes fijos son una ayuda para revisión, no segmentación final de entrenamiento. No rellenar palabras cortadas ni usar la hipótesis de Whisper como verdad. Si el fragmento está incompleto, excluirlo o regenerar cortes con límites de transmisión antes de entrenar. Escribir números como se pronunciaron; no convertirlos automáticamente a cifras. La guía no presupone cómo se pronuncian los indicativos locales.

Elementos críticos: una línea por elemento, por ejemplo `negation | unable`. Deben aparecer literalmente en la transcripción. Marcar indicativos, frecuencias, pistas, altitudes, rumbos, códigos de transpondedor, negaciones e instrucciones.

```powershell
python training/atc/atc_dataset.py validate training/atc/local/pilot/referencias.jsonl
```

La validación comprueba formato WAV, duración, huella de audio, datos de revisión y que una fuente no aparezca en varios splits. `manifest.jsonl` empieza completamente pendiente; no contiene referencias inventadas.

## Separación de datos

La asignación inicial ordena los nombres: primera grabación test, segunda dev, restantes train. Es provisional y reproducible con las cuatro fuentes actuales. Una misma fuente y su hash permanecen en un único split. Antes de entrenar, revisar si distintas grabaciones proceden de una misma conversación, hablante o sesión y agruparlas también: el hash no detecta versiones recortadas de una grabación.

Tenemos aproximadamente 14,7 minutos: sirven para un piloto de anotación, no para demostrar generalización. Además, estos audios ya se usaron al desarrollar RTTS, por lo que el split test es una prueba de regresión interna, no una evaluación independiente final. Recopilar audio nuevo, separado por sesiones/hablantes y con variedad de español, inglés, ruido y acentos. Confirmar que puede utilizarse para entrenamiento antes de incluirlo o compartirlo.

## Comparar hipótesis

### Generar borradores locales

En un entorno Python separado, instalar `requirements-inference.txt`. Usa los pesos ONNX existentes de la app:

```powershell
python training/atc/transcribe_local.py training/atc/local/pilot/manifest.jsonl --output training/atc/local/pilot/drafts-auto.jsonl
python training/atc/atc_dataset.py review training/atc/local/pilot/manifest.jsonl --drafts training/atc/local/pilot/drafts-auto.jsonl
```

La segunda orden regenera la página de revisión sin modificar el manifiesto. Antes de regenerarla, descargar cualquier trabajo pendiente de la pestaña. Cada borrador aparece aparte; copiarlo mantiene estado Pendiente y no sobrescribe texto ya escrito. Se comprueba el hash del audio antes de asociarlo con una referencia.

`--resume` permite continuar una inferencia interrumpida solo si coincide el manifiesto, versión de motor, pesos, idioma e hilos. El archivo `.meta.json` registra esos valores. English ATC es el valor predeterminado y el único admitido. Usa `--vad` para reproducir la segmentación de voz de Android; el script todavía no aplica los filtros de publicación de la app. No usar su tiempo como latencia de tablet.

Los borradores contienen todos los clips. Para `evaluate`, crear un archivo de predicciones con exactamente los IDs revisados elegibles del split, incluidos los clips sin voz. El evaluador rechaza extras para evitar comparar conjuntos distintos.

Para investigar errores, `transcribe_local.py --ids ID1 ID2 --language en --vad` procesa solo los clips seleccionados. `atc_dataset.py review ... --drafts drafts-auto.jsonl --alternate-drafts drafts-en-retry.jsonl` muestra ambas salidas sin promover ninguna a referencia. El segundo archivo puede contener solo un subconjunto de IDs.

### Comparación con detector de voz

`transcribe_local.py --vad` utiliza Silero con los parámetros actuales de Android (umbral 0.6, silencio 0.5 s, voz mínima 0.3 s, ventana 512, máximo 20 s, alimentación en bloques de 3200 muestras). Reinicia el detector para cada clip independiente. No reproduce continuidad entre clips ni los reintentos de idioma/filtro final de la app. Registra cada segmento con inicio, duración, idioma y texto. `decode_seconds` incluye VAD en este modo.

```powershell
python training/atc/transcribe_local.py training/atc/local/pilot/manifest.jsonl --output training/atc/local/pilot/drafts-vad.jsonl --vad
python training/atc/compare_runs.py training/atc/local/pilot/referencias-silencio.jsonl --baseline training/atc/local/pilot/drafts-auto.jsonl --candidate training/atc/local/pilot/drafts-vad.jsonl --output comparison.json
```

La comparación requiere la misma cobertura de IDs y huellas de audio en ambos experimentos. Solo puntúa referencias verificadas/certificadas, por split; cuenta cambios de texto en las demás sin atribuirles precisión. Un resultado vacío de VAD no certifica ausencia de voz. Los casos de silencio digital no proporcionan un WER de voz: el denominador es cero y se publica null.

Preparar un JSONL con una fila por fragmento revisado del split elegido:

```json
{"id":"ID exacto del manifiesto","text":"texto producido por el modelo"}
```

Ejecutar cada modelo con el mismo audio, idioma, segmentación y configuración; conservar la identificación del checkpoint/runtime en el nombre o documentación del experimento. Una hipótesis vacía se registra como `"text":""`, no se omite la fila.

```powershell
python training/atc/atc_dataset.py evaluate training/atc/local/pilot/referencias.jsonl --split test --predictions baseline.jsonl --output baseline-report.json
python training/atc/atc_dataset.py evaluate training/atc/local/pilot/referencias.jsonl --split test --predictions candidate.jsonl --output candidate-report.json
```

El evaluador exige todos los IDs elegibles y rechaza duplicados y extras. Publica WER por distancia de palabras agregada, errores/textos por clip, falsos positivos sin voz y presencia literal de las entidades críticas anotadas. WER ignora mayúsculas y puntuación general, conserva acentos y separadores decimales. No equipara palabras con cifras ni variantes fonéticas. Puede superar 100 %; si no hay palabras de referencia, devuelve null.

La presencia de entidades es una medida auxiliar: no comprueba su posición, número de apariciones, asignación a una aeronave ni el significado completo de la instrucción. Revisar siempre los pares referencia/hipótesis. No es una garantía de seguridad ni una precisión semántica.

## Exportar después de revisar

```powershell
python training/atc/atc_dataset.py export training/atc/local/pilot/referencias.jsonl --split train --output train.jsonl
python training/atc/atc_dataset.py export training/atc/local/pilot/referencias.jsonl --split dev --output dev.jsonl
```

Se exportan rutas absolutas al audio, texto, idioma y grupo de origen, únicamente de ejemplos verificados. El resultado es un manifiesto para preparar el entrenamiento, no un checkpoint ni un dataset cargado automáticamente en Transformers.

## Entrenamiento experimental

La receta `train_whisper.py` consume el manifiesto original revisado (con splits y estados), no los archivos de exportación anteriores. Primero comprueba datos sin importar Torch, descargar modelos ni usar GPU:

```powershell
python training/atc/train_whisper.py training/atc/local/pilot/referencias.jsonl --language en
```

Exige referencias verificadas train y dev del idioma seleccionado, de hasta 30 segundos. Excluye test, pendientes, ininteligibles, sin voz y lenguaje mixto. Una cantidad mínima de ejemplos permite probar el mecanismo, pero no demuestra mejora ni suficiencia estadística.

Tras revisar/ampliar los datos y preparar un checkpoint Hugging Face local de Whisper small y un entorno dedicado con `requirements-training.txt`:

```powershell
python training/atc/train_whisper.py training/atc/local/pilot/referencias.jsonl --language en --execute --checkpoint RUTA_CHECKPOINT_HF --output CARPETA_NUEVA --epochs 1 --device cuda
```

Receta inicial: encoder congelado, decoder ajustable, lote 1, AdamW, learning rate 1e-5, clipping de gradiente y semilla fija. Registra versiones, hashes de checkpoint/manifiesto, IDs usados y pérdida dev antes/después. Guardar un candidato no lo promueve a producción. La pérdida dev no sustituye a WER ni a las comprobaciones de entidades. Para español debe hacerse un experimento separado; no se ha validado conservación de capacidades bilingües.

**Estado de validación:** preflight y selección de splits probados; bucle de entrenamiento escrito pero aún no ejecutado, porque no existen referencias revisadas. Las dependencias de entrenamiento no se instalaron. La semilla y metadatos ayudan a reproducir el experimento; no garantizan resultados idénticos entre GPUs.

Después de entrenar, generar hipótesis con baseline y candidato sobre el mismo conjunto reservado y comparar WER/entidades. Validar exportación ONNX, cuantización y ejecución con sherpa-onnx antes de reemplazar modelos Android. La exportación compatible aún no está implementada. No entrenar directamente sobre los ONNX int8 desplegados.

APIs consultadas: [Whisper en Transformers 4.57.1](https://huggingface.co/docs/transformers/v4.57.1/en/model_doc/whisper), [motor Python sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/python/sherpa_onnx/offline_recognizer.py).

## Verificación

```powershell
python -m unittest discover -s training/atc -v
```

Estas herramientas no cambian pesos, no ejecutan gastos de GPU y no envían audio a servicios externos.
