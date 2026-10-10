import tempfile
import unittest
from pathlib import Path
import atc_dataset as dataset
from train_whisper import select_training
from compare_runs import compare


class EvaluationTests(unittest.TestCase):
    def test_digital_silence_requires_exact_zero(self):
        import wave
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'silence.wav'
            for data, expected in ((b'\x00\x00' * 160, True), (b'\x01\x00' + b'\x00\x00' * 159, False)):
                with wave.open(str(path), 'wb') as wav:
                    wav.setparams((1,2,16000,0,'NONE','not compressed'))
                    wav.writeframes(data)
                self.assertEqual(dataset.is_digital_silence(path), expected)

    def test_digital_silence_rejects_truncation(self):
        import wave
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'silence.wav'
            with wave.open(str(path), 'wb') as wav:
                wav.setparams((1,2,16000,0,'NONE','not compressed'))
                wav.writeframes(b'\x00\x00' * 160)
            path.write_bytes(path.read_bytes()[:-2])
            with self.assertRaisesRegex(ValueError, 'truncado'):
                dataset.is_digital_silence(path)

    def test_comparison_does_not_score_pending(self):
        row = self.row('', status='pending', audio_sha256='abc')
        before = [{'id':'clip', 'text':'roger', 'audio_sha256':'abc'}]
        after = [{'id':'clip', 'text':'', 'audio_sha256':'abc'}]
        report = compare([row], before, after)
        self.assertEqual(report['changed_texts'], 1)
        self.assertEqual(report['verified_metrics_by_split'], {})

    def test_comparison_requires_all_clips(self):
        with self.assertRaises(ValueError):
            compare([self.row('', audio_sha256='abc')], [], [])

    def row(self, text, status="verified", **extra):
        return {"id": "clip", "text": text, "status": status, "split": "test", "critical": [], **extra}

    def test_insertion_deletion_substitution(self):
        self.assertEqual(dataset.distance(['a', 'b'], ['a', 'x', 'c']), 2)
        self.assertEqual(dataset.distance(['a', 'b'], []), 2)

    def test_punctuation_case_but_not_numbers(self):
        self.assertEqual(dataset.tokens('Roger, WILCO!'), ['roger', 'wilco'])
        self.assertNotEqual(dataset.tokens('118.1'), dataset.tokens('1181'))
        self.assertNotEqual(dataset.tokens('two'), dataset.tokens('three'))

    def test_missing_prediction_cannot_improve_score(self):
        with self.assertRaises(ValueError):
            dataset.evaluate([self.row('roger')], [], 'test')

    def test_no_verified_reference_rejects_fake_metric(self):
        with self.assertRaises(ValueError):
            dataset.evaluate([self.row('', status='pending')], [], 'test')

    def test_silence_hallucination_and_undefined_wer(self):
        result = dataset.evaluate([self.row('', status='no_speech')], [{'id':'clip', 'text':'roger'}], 'test')
        self.assertIsNone(result['wer'])
        self.assertEqual(result['no_speech_false_positives'], 1)

    def test_critical_negation_missing(self):
        row = self.row('unable climb', critical=[{'category':'negation', 'text':'unable'}])
        result = dataset.evaluate([row], [{'id':'clip', 'text':'climb'}], 'test')
        self.assertEqual(result['wer'], .5)
        self.assertEqual(result['critical_literal_presence']['negation_matched'], 0)

    def test_critical_word_boundaries(self):
        self.assertFalse(dataset.contains(dataset.tokens('identification'), dataset.tokens('ident')))

    def test_duplicate_ids_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'rows.jsonl'
            dataset.write_rows(path, [self.row('roger'), self.row('wilco')])
            with self.assertRaises(ValueError):
                dataset.read_rows(path)

    def test_drafts_never_become_references(self):
        row = self.row('', status='pending', audio_sha256='abc')
        result = dataset.attach_drafts([row], [{'id':'clip', 'audio_sha256':'abc', 'text':'invented'}])
        self.assertEqual(result[0]['text'], '')
        self.assertEqual(result[0]['status'], 'pending')
        self.assertNotIn('draft', row)

    def test_draft_for_different_audio_rejected(self):
        with self.assertRaises(ValueError):
            dataset.attach_drafts([self.row('', audio_sha256='abc')], [{'id':'clip', 'audio_sha256':'wrong', 'text':'roger'}])

    def test_training_blocks_pending_references(self):
        with self.assertRaisesRegex(ValueError, 'Faltan referencias'):
            select_training([self.row('', status='pending', language='en')], 'en')

    def test_training_never_uses_test_split(self):
        rows = [self.row('roger', split=s, language='en', duration_s=1, id=s) for s in ('train', 'dev', 'test')]
        selected = select_training(rows, 'en')
        self.assertEqual([r['id'] for values in selected.values() for r in values], ['train', 'dev'])

    def test_verified_reference_rejects_uncertain_words(self):
        import wave, hashlib
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            audio = root / 'clip.wav'
            with wave.open(str(audio), 'wb') as wav:
                wav.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
                wav.writeframes(b'\x00\x00' * 160)
            row = self.row('hold short [inaudible] runway one', source_group='session',
                           source_sha256='session-hash', audio='clip.wav',
                           audio_sha256=hashlib.sha256(audio.read_bytes()).hexdigest(),
                           duration_s=.01, reviewer='human', language='en')
            with self.assertRaisesRegex(ValueError, 'incertidumbre'):
                dataset.validate([row], root)
            row['status'] = 'pending'
            self.assertEqual(dataset.validate([row], root)[0]['status'], 'pending')

    def test_source_leakage_rejected(self):
        # Collision must be rejected before accessing the second row's audio.
        row = self.row('roger', source_group='same', source_sha256='same', split='test')
        row['source_sha256'] = 'other'
        # Use conflicting group/hash within the first row to trigger on next record.
        with tempfile.TemporaryDirectory() as folder:
            import wave, hashlib
            path = Path(folder) / 'clip.wav'
            with wave.open(str(path), 'wb') as wav:
                wav.setparams((1,2,16000,0,'NONE','not compressed'))
                wav.writeframes(b'\x00\x00' * 160)
            row.update(audio='clip.wav', duration_s=.01, audio_sha256=hashlib.sha256(path.read_bytes()).hexdigest(), reviewer='test', language='en')
            other = dict(row, id='clip2', split='train')
            with self.assertRaisesRegex(ValueError, 'varios splits'):
                dataset.validate([row, other], Path(folder))


if __name__ == '__main__':
    unittest.main()
