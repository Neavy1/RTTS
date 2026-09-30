import unittest
from segment_transmissions import merge_regions


class SegmentTransmissionTests(unittest.TestCase):
    def test_merges_short_pauses_and_keeps_transmissions_apart(self):
        regions = [(1000, 2000), (2500, 3000), (6000, 7000)]
        self.assertEqual(merge_regions(regions, sample_rate=1000, merge_gap_s=1, padding_s=.1,
                                       total_samples=8000), [(900, 3100), (5900, 7100)])

    def test_padding_is_bounded_and_never_overlaps(self):
        regions = [(50, 100), (150, 200)]
        result = merge_regions(regions, sample_rate=100, merge_gap_s=.1, padding_s=1,
                               total_samples=240)
        self.assertEqual(result[0][0], 0)
        self.assertEqual(result[-1][1], 240)
        self.assertLessEqual(result[0][1], result[1][0])

    def test_empty_audio_has_no_transmissions(self):
        self.assertEqual(merge_regions([]), [])


if __name__ == '__main__':
    unittest.main()
