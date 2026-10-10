# Revisiones de la página web

La página [RTTS Revisión ATC](https://rtts-revision-atc.luifduquetab.chatgpt.site) guarda una fila por transmisión y navegador en D1. La base de datos web es distinta de los ZIP Android y de los manifiestos locales. El propietario puede recuperar la tabla `reviews` mediante Sites; la página pública no expone una exportación global.

## Primer lote: `videoejemplo.mp4` (octubre de 2026)

Llegaron ocho revisiones de un revisor. Una marcó el clip 001 como ininteligible. Los clips 002, 003, 004, 005 y 007 se marcaron «verificados» pero contienen dudas explícitas, palabras inaudibles o una frase incompleta. El clip 006 necesita adjudicación con el contexto del intercambio; el revisor señala que el piloto respondió de forma equivocada. El clip 008 es el único candidato sin incertidumbre escrita, pendiente de una segunda escucha.

Estos ocho cortes provienen de una sola grabación ya usada durante el desarrollo y asignada al split `test`. No constituyen un conjunto de entrenamiento ni una medida de WER. Comparados con los borradores del modelo, revelan tres fallos concretos: pérdida o deformación de indicativos/números, instrucciones ATC parcialmente reconocidas y dos salidas erróneas de idioma o `[inaudible]` (004 y 008). La parte operacionalmente incorrecta de un readback debe transcribirse tal como sonó.

El análisis detallado y la copia de las respuestas están en el workspace local `outputs/RTTS-ATC/videoejemplo-transmissions/`. No se incluyen datos personales ni claves de navegador en este repositorio.

## Regla de adjudicación

- `verified` significa **todas** las palabras de la referencia confirmadas por escucha, con números como fueron pronunciados.
- Un indicativo dudoso, `[inaudible]`, paréntesis explicativos o puntos suspensivos hacen la fila parcial/pendiente, aunque el formulario anterior la hubiese guardado como `verified`.
- `unintelligible` excluye el clip de WER de voz y entrenamiento. No se debe sustituir por el borrador de Whisper.
- Conservar siempre la fila original del revisor; crear una referencia adjudicada separada después de escuchar de nuevo.
- El validador local rechaza marcas explícitas de incertidumbre dentro de una referencia `verified`, para impedir que lleguen a evaluación o entrenamiento por accidente.
