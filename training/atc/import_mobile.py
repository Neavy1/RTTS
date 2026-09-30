"""Import RTTS review ZIPs without extracting arbitrary archive paths."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import wave
import zipfile
from atc_dataset import write_rows, validate, render_review


def read_package(path, split):
    if split not in ('train', 'dev', 'test'):
        raise ValueError('Split inválido')
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Entradas ZIP duplicadas')
        if sum(i.file_size for i in archive.infolist()) > 1024 ** 3:
            raise ValueError('Paquete demasiado grande (máximo 1 GiB sin comprimir)')
        if archive.getinfo('records.jsonl').file_size > 20 * 1024 ** 2:
            raise ValueError('Manifiesto demasiado grande')
        records = [json.loads(line) for line in archive.read('records.jsonl').decode('utf-8').splitlines() if line.strip()]
        if not records or len(records) > 10000 or len({r['id'] for r in records}) != len(records):
            raise ValueError('Registros vacíos, duplicados o demasiados')
        rows, audio = [], {}
        for record in records:
            identifier = record['id']
            if record.get('schema') != 'rtts.atc.review.v1' or not re.fullmatch(r'[A-Za-z0-9_-]{1,120}', identifier):
                raise ValueError('Esquema o ID inválido')
            relative = f'audio/{identifier}.wav'
            if record['audio'] != relative:
                raise ValueError('Ruta de audio inválida')
            if archive.getinfo(relative).file_size > 10 * 1024 ** 2:
                raise ValueError('Fragmento demasiado grande')
            data = archive.read(relative)
            digest = hashlib.sha256(data).hexdigest()
            if digest != record['audio_sha256']:
                raise ValueError('Hash de audio incorrecto')
            with wave.open(io.BytesIO(data), 'rb') as wav:
                if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1, 2, 16000):
                    raise ValueError('Audio debe ser PCM16 mono 16 kHz')
                duration = wav.getnframes() / 16000
                if duration <= 0 or abs(duration - record['duration_s']) > 1 / 16000:
                    raise ValueError('Duración inválida')
                if len(wav.readframes(wav.getnframes())) != wav.getnframes() * 2:
                    raise ValueError('WAV truncado')
            if record['status'] not in ('pending', 'verified', 'unintelligible', 'no_speech'):
                raise ValueError('Estado inválido')
            if record['status'] in ('verified', 'no_speech') and not record.get('reviewer', '').strip():
                raise ValueError('Falta revisor')
            if record['status'] == 'verified' and (not record['text'].strip() or record['language'] not in ('es', 'en', 'mixed')):
                raise ValueError('Referencia incompleta')
            if record['status'] == 'no_speech' and record['text'].strip():
                raise ValueError('Sin voz debe tener texto vacío')
            rows.append({**record, 'source_sha256': digest, 'source_hash_scope': 'clip',
                         'split': split, 'critical': []})
            audio[relative] = data
        return rows, audio


def import_package(path, output, split):
    if output.exists():
        raise ValueError('La carpeta de salida ya existe; no se sobrescriben referencias')
    rows, audio = read_package(path, split)
    output.mkdir(parents=True)
    (output / 'audio').mkdir()
    for relative, data in audio.items():
        (output / relative).write_bytes(data)
    validate(rows, output)
    write_rows(output / 'manifest.jsonl', rows)
    render_review(output, rows)
    return len(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('zip', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--split', choices=('train', 'dev', 'test'), required=True)
    args = parser.parse_args()
    try:
        print(json.dumps({'imported': import_package(args.zip, args.output, args.split)}))
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, wave.Error) as error:
        parser.exit(2, f'Error: {error}\n')


if __name__ == '__main__':
    main()
