"""Offline ATC annotation/evaluation tooling. Python standard library only."""
import argparse
from collections import Counter
import hashlib
import html
import json
from pathlib import Path
import re
import wave

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
CATEGORIES = set(json.loads((HERE / "vocabulary.json").read_text(encoding="utf-8"))["annotation_categories"])


def read_rows(path):
    rows = [json.loads(line) for line in Path(path).read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    ids = [row["id"] for row in rows]
    if len(ids) != len(set(ids)):
        raise ValueError("IDs duplicados")
    return rows


def write_rows(path, rows):
    Path(path).write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")


def tokens(text):
    # Preserve accents and decimal separators: 118.1 must not equal 1181.
    return re.findall(r"\d+(?:[.,]\d+)*|[^\W\d_]+", text.casefold(), re.UNICODE)


def distance(reference, hypothesis):
    previous = list(range(len(hypothesis) + 1))
    for i, word in enumerate(reference, 1):
        current = [i]
        for j, other in enumerate(hypothesis, 1):
            current.append(min(current[-1] + 1, previous[j] + 1, previous[j - 1] + (word != other)))
        previous = current
    return previous[-1]


def contains(sequence, phrase):
    return bool(phrase) and any(sequence[i:i + len(phrase)] == phrase for i in range(len(sequence) - len(phrase) + 1))


def has_uncertainty_marker(text):
    """Reviewer annotations are not literal words for supervised labels or WER."""
    return bool(re.search(r'\[[^\]]*\]|\([^)]*\)|\.{3,}|…|\b(?:inaudible|ininteligible)\b', text, re.IGNORECASE))


def is_digital_silence(path):
    """A narrow proof: every PCM16 sample is exactly zero, not merely quiet."""
    with wave.open(str(path), 'rb') as wav:
        if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1, 2, 16000):
            raise ValueError('Formato no compatible para certificar silencio')
        frames = wav.getnframes()
        data = wav.readframes(frames)
        if len(data) != frames * 2:
            raise ValueError('Audio truncado')
        return frames > 0 and not any(data)


def certify_silence(rows, root):
    result = []
    for row in rows:
        if row['status'] == 'pending' and is_digital_silence(root / row['audio']):
            row = {**row, 'status': 'no_speech', 'text': '', 'critical': [],
                   'reviewer': 'automatic:pcm-all-zero-v1', 'reference_method': 'pcm_all_zero',
                   'notes': 'Todas las muestras PCM son cero. Verificación determinista, sin escucha humana.'}
        result.append(row)
    return result


def validate(rows, root):
    groups = {}
    for row in rows:
        if row["split"] not in ("train", "dev", "test"):
            raise ValueError(f"{row['id']}: split inválido")
        for group in (row["source_group"], row["source_sha256"]):
            if group in groups and groups[group] != row["split"]:
                raise ValueError("Una misma grabación está en varios splits")
            groups[group] = row["split"]
        audio = (root / row["audio"]).resolve()
        if not audio.is_relative_to(root.resolve()):
            raise ValueError("Audio fuera del dataset")
        with wave.open(str(audio), "rb") as wav:
            if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1, 2, 16000):
                raise ValueError("Se requiere PCM16 mono 16 kHz")
            if abs(wav.getnframes() / 16000 - row["duration_s"]) > 1 / 16000:
                raise ValueError("Duración incorrecta")
        if hashlib.sha256(audio.read_bytes()).hexdigest() != row["audio_sha256"]:
            raise ValueError("El audio cambió después de preparar las referencias")
        if row.get('reference_method') == 'pcm_all_zero':
            if row['status'] != 'no_speech' or not is_digital_silence(audio):
                raise ValueError('La referencia automática de silencio no coincide con el audio')
        if row["status"] not in ("pending", "verified", "unintelligible", "no_speech"):
            raise ValueError("Estado inválido")
        if row["status"] in ("verified", "no_speech"):
            if not row.get("reviewer", "").strip():
                raise ValueError("Falta identificar al revisor")
            if row["status"] == "verified" and (not tokens(row["text"]) or row["language"] not in ("es", "en", "mixed")):
                raise ValueError("Una referencia verificada requiere texto e idioma")
            if row["status"] == "verified" and has_uncertainty_marker(row["text"]):
                raise ValueError(f"{row['id']}: una referencia verificada no puede contener marcas de incertidumbre")
            if row["status"] == "no_speech" and row["text"].strip():
                raise ValueError("no_speech debe tener texto vacío")
        for item in row.get("critical", []):
            if item["category"] not in CATEGORIES or not contains(tokens(row["text"]), tokens(item["text"])):
                raise ValueError("Cada entidad crítica debe aparecer literalmente en la referencia")
    return rows


def prepare(output, seconds):
    if not 1 <= seconds <= 30:
        raise ValueError("Duración entre 1 y 30 segundos")
    if output.exists():
        raise ValueError("Usa una carpeta nueva para no sobrescribir anotaciones")
    sources = sorted((REPO / "rtts-app/app/src/main/assets/samples").glob("*.wav"))
    if len(sources) < 3:
        raise ValueError("Se requieren al menos tres grabaciones independientes")
    output.mkdir(parents=True)
    (output / "audio").mkdir()
    rows = []
    inventory = []
    for index, source in enumerate(sources):
        # Whole recordings remain together. Provisional split, review source independence.
        split = "test" if index == 0 else "dev" if index == 1 else "train"
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        with wave.open(str(source), "rb") as wav:
            if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1, 2, 16000):
                raise ValueError(f"Formato incompatible: {source}")
            inventory.append({"source": source.relative_to(REPO).as_posix(), "sha256": digest,
                              "duration_s": wav.getnframes() / 16000, "split": split})
            start = 0
            while start < wav.getnframes():
                count = min(round(seconds * 16000), wav.getnframes() - start)
                identifier = f"{source.stem}-{start:09d}"
                relative = f"audio/{identifier}.wav"
                target = output / relative
                with wave.open(str(target), "wb") as clip:
                    clip.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
                    clip.writeframes(wav.readframes(count))
                rows.append({"id": identifier, "audio": relative, "audio_sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
                             "source_group": source.stem, "source_sha256": digest, "start_s": start / 16000,
                             "duration_s": count / 16000, "split": split, "language": "unknown",
                             "status": "pending", "text": "", "critical": [], "reviewer": "", "notes": ""})
                start += count
    write_rows(output / "manifest.jsonl", rows)
    (output / "inventory.json").write_text(json.dumps(inventory, indent=2), encoding="utf-8")
    render_review(output, rows)
    return {"recordings": len(inventory), "clips": len(rows), "seconds": sum(r["duration_s"] for r in rows), "verified": 0}


def attach_drafts(rows, drafts):
    by_id = {row['id']: row for row in rows}
    if len({d['id'] for d in drafts}) != len(drafts):
        raise ValueError('Borradores con IDs duplicados')
    for draft in drafts:
        row = by_id.get(draft['id'])
        if row is None or draft.get('audio_sha256') != row['audio_sha256']:
            raise ValueError('El borrador no corresponde al audio del manifiesto')
        if not isinstance(draft.get('text'), str):
            raise ValueError('El borrador debe contener texto')
    mapped = {d['id']: d for d in drafts}
    return [{**r, 'draft': mapped.get(r['id'])} for r in rows]


def render_review(output, rows):
    template = (HERE / "review.html").read_text(encoding="utf-8")
    vocabulary = json.loads((HERE / "vocabulary.json").read_text(encoding="utf-8"))
    # Escape script terminators in metadata. UI renders annotation content with textContent/value.
    data = json.dumps(rows, ensure_ascii=False).replace("<", "\\u003c")
    terms = ", ".join(vocabulary["phrases"])
    page = template.replace("__MANIFEST__", data).replace("__VOCABULARY__", html.escape(terms))
    (output / "revisar.html").write_text(page, encoding="utf-8")


def evaluate(rows, predictions, split):
    references = [r for r in rows if r["split"] == split and r["status"] in ("verified", "no_speech")]
    if not references:
        raise ValueError("No hay referencias revisadas para este split; no se puede medir precisión")
    predicted = {p["id"]: p["text"] for p in predictions}
    expected = {r["id"] for r in references}
    if set(predicted) != expected:
        raise ValueError(f"Predicciones faltantes: {sorted(expected - set(predicted))}; IDs extra: {sorted(set(predicted) - expected)}")
    errors = words = silence_false_positives = silent = 0
    critical = Counter()
    per_clip = []
    for row in references:
        ref, hyp = tokens(row["text"]), tokens(predicted[row["id"]])
        edit = distance(ref, hyp)
        errors += edit
        words += len(ref)
        if row["status"] == "no_speech":
            silent += 1
            silence_false_positives += bool(hyp)
        for item in row.get("critical", []):
            category = item["category"]
            critical[category + "_total"] += 1
            critical[category + "_matched"] += contains(hyp, tokens(item["text"]))
        per_clip.append({"id": row["id"], "edits": edit, "reference_words": len(ref),
                         "reference": row["text"], "hypothesis": predicted[row["id"]]})
    return {"split": split, "clips": len(references), "excluded_unreviewed_or_unintelligible": sum(r["split"] == split for r in rows) - len(references),
            "word_errors": errors, "reference_words": words, "wer": errors / words if words else None,
            "no_speech_clips": silent, "no_speech_false_positives": silence_false_positives,
            "critical_literal_presence": dict(critical), "per_clip": per_clip}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prep = commands.add_parser("prepare")
    prep.add_argument("--output", type=Path, required=True)
    prep.add_argument("--seconds", type=float, default=20)
    review = commands.add_parser('review')
    review.add_argument('manifest', type=Path)
    review.add_argument('--drafts', type=Path, required=True)
    review.add_argument('--alternate-drafts', type=Path)
    silence = commands.add_parser('certify-silence')
    silence.add_argument('manifest', type=Path)
    silence.add_argument('--output', type=Path, required=True)
    for name in ("validate", "evaluate", "export"):
        command = commands.add_parser(name)
        command.add_argument("manifest", type=Path)
        if name != "validate":
            command.add_argument("--output", type=Path, required=True)
            command.add_argument("--split", choices=("train", "dev", "test"), required=True)
        if name == "evaluate":
            command.add_argument("--predictions", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            result = prepare(args.output, args.seconds)
        else:
            rows = validate(read_rows(args.manifest), args.manifest.parent)
            if args.command == 'certify-silence':
                if args.output.exists() or args.output.parent.resolve() != args.manifest.parent.resolve():
                    raise ValueError('Usa un archivo nuevo junto al manifiesto original')
                certified = certify_silence(rows, args.manifest.parent)
                validate(certified, args.manifest.parent)
                write_rows(args.output, certified)
                result = {'digital_silence': sum(r.get('reference_method') == 'pcm_all_zero' for r in certified)}
            elif args.command == 'review':
                enriched = attach_drafts(rows, read_rows(args.drafts))
                if args.alternate_drafts:
                    alternate = attach_drafts(rows, read_rows(args.alternate_drafts))
                    enriched = [{**r, 'alternative': other['draft']} for r, other in zip(enriched, alternate)]
                render_review(args.manifest.parent, enriched)
                result = {'drafts': sum(r['draft'] is not None for r in enriched), 'references_unchanged': True}
            elif args.command == "validate":
                result = dict(Counter(r["status"] for r in rows))
            elif args.command == "evaluate":
                result = evaluate(rows, read_rows(args.predictions), args.split)
                args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
            else:
                eligible = [r for r in rows if r["split"] == args.split and r["status"] == "verified"]
                if not eligible:
                    raise ValueError("No hay ejemplos verificados para exportar")
                write_rows(args.output, [{"id": r["id"], "audio": str((args.manifest.parent / r["audio"]).resolve()),
                                          "text": r["text"], "language": r["language"], "source_group": r["source_group"]} for r in eligible])
                result = {"exported": len(eligible), "split": args.split}
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (ValueError, KeyError, OSError, wave.Error) as error:
        parser.exit(2, f"Error: {error}\n")


if __name__ == "__main__":
    main()
