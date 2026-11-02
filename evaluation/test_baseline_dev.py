import unittest
from baseline_dev import score


class ScoreTest(unittest.TestCase):
    def test_one_chunk_can_cover_two_labeled_evidence_ranges(self):
        case = {'product_id': 'A', 'evidence': [
            {'filename': 'a.md', 'version': 1, 'line_start': 4, 'line_end': 4},
            {'filename': 'a.md', 'version': 1, 'line_start': 5, 'line_end': 5}]}
        match = {'product_id': 'A', 'filename': 'a.md', 'version': 1, 'line_start': 3, 'line_end': 5}
        self.assertEqual(score(case, [match])['evidence_recall_at_3'], 1.0)
        match['line_end'] = 4
        self.assertEqual(score(case, [match])['evidence_recall_at_3'], 0.5)

    def test_wrong_product_or_version_does_not_count_as_hit(self):
        case = {'product_id': 'A', 'evidence': [{'filename': 'a.md', 'version': 1, 'line_start': 4, 'line_end': 4}]}
        match = {'product_id': 'B', 'filename': 'a.md', 'version': 1, 'line_start': 4, 'line_end': 4}
        result = score(case, [match])
        self.assertFalse(result['hit_at_3'])
        self.assertTrue(result['wrong_product'])
        match.update(product_id='A', version=2)
        self.assertFalse(score(case, [match])['hit_at_3'])


if __name__ == '__main__':
    unittest.main()
