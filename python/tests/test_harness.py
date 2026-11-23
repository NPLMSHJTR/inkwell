"""Tests for tokenisation, phrase matching, and nDCG."""

from __future__ import annotations

import unittest

from inkwell.harness import contains_phrase, ndcg, tokenize


class TokenizerTests(unittest.TestCase):
    def test_lowercases_and_splits(self) -> None:
        self.assertEqual(tokenize("Hello, World! 42"), ["hello", "world", "42"])

    def test_ignores_punctuation_only(self) -> None:
        self.assertEqual(tokenize("..."), [])


class PhraseTests(unittest.TestCase):
    def test_consecutive_only(self) -> None:
        tokens = ["a", "b", "c", "d"]
        self.assertTrue(contains_phrase(tokens, ["b", "c"]))
        self.assertFalse(contains_phrase(tokens, ["b", "d"]))

    def test_phrase_longer_than_document(self) -> None:
        self.assertFalse(contains_phrase(["a"], ["a", "b"]))


class NdcgTests(unittest.TestCase):
    def test_perfect_ranking(self) -> None:
        self.assertAlmostEqual(ndcg([1, 2], {1, 2}), 1.0)

    def test_no_relevant_documents(self) -> None:
        self.assertEqual(ndcg([1, 2], set()), 0.0)

    def test_worse_ranking_scores_lower(self) -> None:
        relevant = {1}
        self.assertGreater(ndcg([1, 2], relevant), ndcg([2, 1], relevant))


if __name__ == "__main__":
    unittest.main()
