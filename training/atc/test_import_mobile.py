import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import wave
import zipfile
from import_mobile import import_package, read_package


class MobileImportTests(unittest.TestCase):
    def package(self, root, **changes):
        buffer = io.BytesIO()
        with wave.open(buffer, 'wb') as wav:
            wav.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
            wav.writeframes(b'\0\0' * 16000)
        audio = buffer.getvalue()
        row = dict(schema='rtts.atc.review.v1', id='test-1', audio='audio/test-1.wav',
                   audio_sha256=hashlib.sha256(audio).hexdigest(), duration_s=1, source_group='session-1',
                   start_s=0, source_kind='spoken_example', original_text='raw output', text='corrected words',
                   display_text='123', language='en', reviewer='test', status='verified')
        row.update(changes)
        path = root / 'test.zip'
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('records.jsonl', json.dumps(row)+'\n')
            archive.writestr('audio/test-1.wav', audio)
        return path

    def test_round_trip_keeps_original_and_spoken_text(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.assertEqual(import_package(self.package(root), root / 'imported', 'dev'), 1)
            row = json.loads((root / 'imported/manifest.jsonl').read_text(encoding='utf-8'))
            self.assertEqual(row['text'], 'corrected words')
            self.assertEqual(row['original_text'], 'raw output')
            self.assertEqual(row['split'], 'dev')
            self.assertEqual(row['source_kind'], 'spoken_example')

    def test_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with self.assertRaises(ValueError):
                read_package(self.package(root, audio='../outside.wav'), 'train')

    def test_rejects_wrong_hash(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with self.assertRaisesRegex(ValueError, 'Hash'):
                read_package(self.package(root, audio_sha256='wrong'), 'train')

    def test_verified_requires_reviewer(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with self.assertRaises(ValueError):
                read_package(self.package(root, reviewer=''), 'train')

    def test_pending_is_not_promoted(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            rows, _ = read_package(self.package(root, status='pending'), 'train')
            self.assertEqual(rows[0]['status'], 'pending')


if __name__ == '__main__':
    unittest.main()
