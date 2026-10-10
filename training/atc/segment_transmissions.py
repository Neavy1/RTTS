"""Split PCM16 16 kHz recordings into review clips using Silero VAD.

Nearby speech islands are merged so short pauses inside a readback do not create
separate training examples. Output is always pending and must be reviewed by ear.
"""
import argparse
import hashlib
import json
from pathlib import Path
import wave

from atc_dataset import write_rows


def merge_regions(regions, sample_rate=16000, merge_gap_s=1.25, padding_s=.2, total_samples=None):
    """Merge VAD regions and add bounded context. Regions are (start, end) samples."""
    if not regions:
        return []
    gap = round(merge_gap_s * sample_rate)
    padding = round(padding_s * sample_rate)
    merged = []
    start, end = regions[0]
    for next_start, next_end in regions[1:]:
        if next_start - end <= gap:
            end = max(end, next_end)
        else:
            merged.append((start, end))
            start, end = next_start, next_end
    merged.append((start, end))
    limit = total_samples if total_samples is not None else merged[-1][1] + padding
    padded = []
    for index, (start, end) in enumerate(merged):
        left = max(0, start - padding)
        right = min(limit, end + padding)
        # Split padding at the midpoint rather than duplicating audio between clips.
        if padded and left < padded[-1][1]:
            midpoint = (start + merged[index - 1][1]) // 2
            previous = padded[-1]
            padded[-1] = (previous[0], midpoint)
            left = midpoint
        padded.append((left, right))
    return padded


def detect(samples, model_path):
    import numpy as np
    import sherpa_onnx
    config = sherpa_onnx.VadModelConfig()
    config.silero_vad.model = str(model_path)
    config.silero_vad.threshold = .6
    config.silero_vad.min_silence_duration = .5
    config.silero_vad.min_speech_duration = .3
    config.silero_vad.max_speech_duration = 20
    config.silero_vad.window_size = 512
    config.sample_rate = 16000
    config.num_threads = 1
    vad = sherpa_onnx.VoiceActivityDetector(config, buffer_size_in_seconds=120)
    regions = []
    for offset in range(0, len(samples), 3200):
        vad.accept_waveform(samples[offset:offset + 3200])
        while not vad.empty():
            segment = vad.front
            regions.append((segment.start, segment.start + len(segment.samples)))
            vad.pop()
    vad.flush()
    while not vad.empty():
        segment = vad.front
        regions.append((segment.start, segment.start + len(segment.samples)))
        vad.pop()
    return regions


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('audio', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--vad-model', type=Path, required=True)
    parser.add_argument('--split', choices=('train', 'dev', 'test'), default='test')
    parser.add_argument('--merge-gap', type=float, default=1.25)
    parser.add_argument('--padding', type=float, default=.2)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('La carpeta de salida ya existe')
    if not 0 <= args.merge_gap <= 3 or not 0 <= args.padding <= 1:
        parser.error('Parámetros de segmentación fuera de rango')
    import numpy as np
    with wave.open(str(args.audio), 'rb') as source:
        if (source.getnchannels(), source.getsampwidth(), source.getframerate()) != (1, 2, 16000):
            parser.error('Se requiere WAV PCM16 mono 16 kHz')
        frames = source.readframes(source.getnframes())
    samples = np.frombuffer(frames, dtype='<i2').astype(np.float32) / 32768.0
    source_hash = hashlib.sha256(args.audio.read_bytes()).hexdigest()
    raw_regions = detect(samples, args.vad_model)
    regions = merge_regions(raw_regions, merge_gap_s=args.merge_gap,
                            padding_s=args.padding, total_samples=len(samples))
    args.output.mkdir(parents=True)
    audio_dir = args.output / 'audio'
    audio_dir.mkdir()
    rows = []
    stem = args.audio.stem
    for index, (start, end) in enumerate(regions, 1):
        identifier = f'{stem}-tx-{index:03d}'
        target = audio_dir / f'{identifier}.wav'
        pcm = (samples[start:end] * 32768).clip(-32768, 32767).astype('<i2').tobytes()
        with wave.open(str(target), 'wb') as clip:
            clip.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
            clip.writeframes(pcm)
        rows.append({'id': identifier, 'audio': f'audio/{target.name}',
                     'audio_sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
                     'source_group': stem, 'source_sha256': source_hash,
                     'start_s': start / 16000, 'duration_s': (end - start) / 16000,
                     'split': args.split, 'language': 'en', 'status': 'pending',
                     'text': '', 'critical': [], 'reviewer': '',
                     'notes': 'Segmentación VAD automática; verificar límites y texto de oído.'})
    write_rows(args.output / 'manifest.jsonl', rows)
    (args.output / 'segmentation.json').write_text(json.dumps({
        'source': str(args.audio.resolve()), 'source_sha256': source_hash,
        'vad_threshold': .6, 'merge_gap_s': args.merge_gap, 'padding_s': args.padding,
        'raw_regions': len(raw_regions), 'transmissions': len(rows),
    }, indent=2), encoding='utf-8')
    print(json.dumps({'transmissions': len(rows), 'seconds': sum(r['duration_s'] for r in rows)}, indent=2))


if __name__ == '__main__':
    main()
