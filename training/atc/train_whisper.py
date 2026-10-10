"""Experimental supervised fine-tuning from reviewed references and a local HF checkpoint.

The default performs data preflight only. No download, GPU rental or deployment.
"""
import argparse
import hashlib
import json
from pathlib import Path
import random
import wave

from atc_dataset import read_rows, validate


def select_training(rows, language):
    selected = {split: [r for r in rows if r['split'] == split and r['status'] == 'verified'
                        and r['language'] == language] for split in ('train', 'dev')}
    for split, items in selected.items():
        if not items:
            raise ValueError(f'Faltan referencias humanas verificadas en {split} para {language}')
        if any(not 0 < r['duration_s'] <= 30 for r in items):
            raise ValueError('Whisper requiere fragmentos de hasta 30 s en este experimento')
    return selected


def run_training(args, selected):
    import numpy as np
    import torch
    import transformers
    from transformers import WhisperForConditionalGeneration, WhisperProcessor
    if args.output.exists():
        raise ValueError('Usa una salida nueva; no sobrescribimos checkpoints')
    if not args.checkpoint or not args.checkpoint.is_dir():
        raise ValueError('Se requiere --checkpoint con un modelo Whisper HF local (no ONNX)')
    if args.device == 'cuda' and not torch.cuda.is_available():
        raise ValueError('CUDA no disponible; este comando no contrata infraestructura remota')
    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)
    if args.device == 'cuda':
        torch.cuda.manual_seed_all(args.seed)
        torch.backends.cudnn.benchmark = False
    processor = WhisperProcessor.from_pretrained(str(args.checkpoint), local_files_only=True,
                                                 language=args.language, task='transcribe')
    model = WhisperForConditionalGeneration.from_pretrained(str(args.checkpoint), local_files_only=True)
    # A conservative first experiment; this is not a claim that encoder freezing is optimal.
    model.freeze_encoder()
    model.config.use_cache = False
    model.to(args.device)
    model.gradient_checkpointing_enable()
    optimizer = torch.optim.AdamW((p for p in model.parameters() if p.requires_grad), lr=args.learning_rate)

    def example(row):
        with wave.open(str(args.manifest.parent / row['audio']), 'rb') as wav:
            audio = np.frombuffer(wav.readframes(wav.getnframes()), dtype='<i2').astype(np.float32) / 32768
        features = processor.feature_extractor(audio, sampling_rate=16000, return_tensors='pt').input_features
        labels = processor.tokenizer(row['text'], return_tensors='pt').input_ids
        if labels[0, 0].item() == model.config.decoder_start_token_id:
            labels = labels[:, 1:]
        if labels.shape[1] > model.config.max_target_positions:
            raise ValueError(f"Referencia demasiado larga: {row['id']}")
        return features.to(args.device), labels.to(args.device)

    def dev_loss():
        model.eval()
        losses = []
        with torch.no_grad():
            for row in selected['dev']:
                features, labels = example(row)
                losses.append(model(input_features=features, labels=labels).loss.item())
        return sum(losses) / len(losses)

    args.output.mkdir(parents=True)
    checkpoint_hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                         for p in args.checkpoint.iterdir() if p.is_file()}
    metadata = {'seed': args.seed, 'language': args.language, 'epochs': args.epochs,
                'learning_rate': args.learning_rate, 'encoder_frozen': True, 'batch_size': 1,
                'device': args.device, 'torch': torch.__version__, 'transformers': transformers.__version__,
                'manifest_sha256': hashlib.sha256(args.manifest.read_bytes()).hexdigest(),
                'checkpoint_files_sha256': checkpoint_hashes,
                'train_ids': [r['id'] for r in selected['train']], 'dev_ids': [r['id'] for r in selected['dev']]}
    (args.output / 'experiment.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
    history = [{'epoch': 0, 'dev_loss': dev_loss()}]
    for epoch in range(1, args.epochs + 1):
        model.train()
        model.model.encoder.eval()
        ordered = list(selected['train'])
        random.shuffle(ordered)
        for index, row in enumerate(ordered, 1):
            optimizer.zero_grad(set_to_none=True)
            features, labels = example(row)
            loss = model(input_features=features, labels=labels).loss
            if not torch.isfinite(loss):
                raise ValueError('Pérdida no finita; entrenamiento detenido')
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            print(f'epoch {epoch} {index}/{len(ordered)} loss={loss.item():.4f}', flush=True)
        history.append({'epoch': epoch, 'dev_loss': dev_loss()})
        (args.output / 'loss-history.json').write_text(json.dumps(history, indent=2), encoding='utf-8')
    model.config.use_cache = True
    model.generation_config.language = args.language
    model.generation_config.task = 'transcribe'
    model.save_pretrained(args.output / 'candidate')
    processor.save_pretrained(args.output / 'candidate')
    print('Candidato guardado. Falta comparar WER, entidades y exportación Android antes de desplegar.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('--language', required=True, choices=('es', 'en'))
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--checkpoint', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--epochs', type=int, default=1)
    parser.add_argument('--learning-rate', type=float, default=1e-5)
    parser.add_argument('--seed', type=int, default=42)
    parser.add_argument('--device', choices=('cuda', 'cpu'), default='cuda')
    args = parser.parse_args()
    try:
        if args.epochs < 1 or not 0 < args.learning_rate <= .001:
            raise ValueError('Épocas o learning rate inválidos')
        selected = select_training(validate(read_rows(args.manifest), args.manifest.parent), args.language)
        print(json.dumps({split: {'clips': len(items), 'seconds': sum(r['duration_s'] for r in items)}
                          for split, items in selected.items()}, indent=2))
        if args.execute:
            if args.output is None:
                raise ValueError('Falta --output')
            run_training(args, selected)
        else:
            print('Preflight de datos correcto; no se cargaron modelos ni se entrenó.')
    except (ValueError, KeyError, OSError, ImportError) as error:
        parser.exit(2, f'Error: {error}\n')


if __name__ == '__main__':
    main()
