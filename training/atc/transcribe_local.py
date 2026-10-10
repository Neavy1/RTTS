"""Generate unverified drafts with the same ONNX weights shipped in RTTS.

This is clip-level CPU inference, not the Android VAD/retry pipeline or a WER test.
"""
import argparse
import hashlib
import json
from pathlib import Path
import time
import wave

from atc_dataset import REPO, read_rows, validate


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def speech_segments(samples, model_path):
    """Match Android's VAD parameters; reset for each independent review clip."""
    import sherpa_onnx
    import numpy as np
    config = sherpa_onnx.VadModelConfig()
    config.silero_vad.model = str(model_path)
    config.silero_vad.threshold = .6
    config.silero_vad.min_silence_duration = .5
    config.silero_vad.min_speech_duration = .3
    config.silero_vad.max_speech_duration = 20
    config.silero_vad.window_size = 512
    config.sample_rate = 16000
    config.num_threads = 1
    vad = sherpa_onnx.VoiceActivityDetector(config, buffer_size_in_seconds=60)
    segments = []

    def drain():
        while not vad.empty():
            segment = vad.front
            segments.append((segment.start, np.array(segment.samples, dtype=np.float32, copy=True)))
            vad.pop()

    for start in range(0, len(samples), 3200):
        vad.accept_waveform(samples[start:start + 3200])
        drain()
    vad.flush()
    drain()
    return segments


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--models', type=Path, default=REPO / 'rtts-app/app/src/main/assets/models/sherpa-onnx-whisper-small')
    parser.add_argument('--language', choices=('en',), default='en',
                        help='RTTS opera en English ATC; se conserva la opción para registrar la configuración')
    parser.add_argument('--threads', type=int, default=4)
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--ids', nargs='+', help='IDs concretos; omitir para procesar todos')
    parser.add_argument('--vad', action='store_true', help='Decodificar solo segmentos detectados como voz')
    parser.add_argument('--vad-model', type=Path, default=REPO / 'rtts-app/app/src/main/assets/models/silero_vad.onnx')
    args = parser.parse_args()
    if args.threads < 1:
        parser.error('threads debe ser positivo')
    rows = validate(read_rows(args.manifest), args.manifest.parent)
    if args.ids:
        if set(args.ids) - {r['id'] for r in rows}:
            parser.error('IDs desconocidos')
        rows = [r for r in rows if r['id'] in set(args.ids)]
    import numpy as np
    import sherpa_onnx
    from importlib.metadata import version
    paths = {key: args.models / name for key, name in {
        'encoder': 'small-encoder.int8.onnx', 'decoder': 'small-decoder.int8.onnx', 'tokens': 'small-tokens.txt'}.items()}
    config = {'runtime': version('sherpa-onnx'), 'language': args.language, 'threads': args.threads,
              'manifest_sha256': sha256(args.manifest), 'weights': {k: sha256(p) for k, p in paths.items()},
              'selected_ids': [r['id'] for r in rows],
              'pipeline': 'whole clips, no VAD, no retry, no postprocessing; unverified draft only'}
    if args.vad:
        config['pipeline'] = 'clip-local VAD, no language retry or postprocessing; unverified draft only'
        config['vad'] = {'sha256': sha256(args.vad_model), 'threshold': .6, 'min_silence': .5,
                         'min_speech': .3, 'max_speech': 20, 'window': 512, 'chunk': 3200}
    metadata = args.output.with_suffix('.meta.json')
    if args.output.exists():
        if not args.resume or not metadata.exists() or json.loads(metadata.read_text(encoding='utf-8')) != config:
            parser.error('Salida existente; resume requiere la misma configuración y manifiesto')
        existing = read_rows(args.output)
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        metadata.write_text(json.dumps(config, indent=2), encoding='utf-8')
        existing = []
    done = {row['id'] for row in existing}
    if not done.issubset({r['id'] for r in rows}):
        parser.error('La salida contiene IDs ajenos al manifiesto')
    engine = sherpa_onnx.OfflineRecognizer.from_whisper(
        **{k: str(p) for k, p in paths.items()}, language=args.language,
        task='transcribe', num_threads=args.threads)
    with args.output.open('a', encoding='utf-8') as out:
        for index, row in enumerate(rows, 1):
            if row['id'] in done:
                continue
            with wave.open(str(args.manifest.parent / row['audio']), 'rb') as wav:
                samples = np.frombuffer(wav.readframes(wav.getnframes()), dtype='<i2').astype(np.float32) / 32768.0
            start = time.perf_counter()
            parts = speech_segments(samples, args.vad_model) if args.vad else [(0, samples)]
            decoded = []
            for offset, part in parts:
                stream = engine.create_stream()
                stream.accept_waveform(16000, part)
                engine.decode_stream(stream)
                result = stream.result
                decoded.append({'start_s': offset / 16000, 'duration_s': len(part) / 16000,
                                'text': result.text, 'language': getattr(result, 'lang', '')})
            languages = sorted({part['language'] for part in decoded})
            record = {'id': row['id'], 'text': ' '.join(part['text'] for part in decoded).strip(),
                      'detected_language': ','.join(languages), 'segments': decoded,
                      'decode_seconds': time.perf_counter() - start, 'audio_sha256': row['audio_sha256'],
                      'status': 'machine_draft'}
            out.write(json.dumps(record, ensure_ascii=False) + '\n')
            out.flush()
            print(f"{index}/{len(rows)} {row['id']} {record['decode_seconds']:.1f}s", flush=True)


if __name__ == '__main__':
    main()
