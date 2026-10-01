#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Check swap sizing and runner restrictions without changing host swap."""
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from prepare_ci_swap import GIB, prepare, required_swap


class SwapTest(unittest.TestCase):
    def test_observed_runner(self):
        self.assertEqual(required_swap(30 * GIB, 3 * GIB), 12 * GIB)

    def test_insufficient_disk(self):
        with self.assertRaises(RuntimeError):
            required_swap(27 * GIB, 3 * GIB)

    def test_disk_reserve_boundary(self):
        self.assertEqual(required_swap(28 * GIB, 3 * GIB), 12 * GIB)

    def test_existing_swap(self):
        self.assertEqual(required_swap(10 * GIB, 16 * GIB), 0)

    def test_no_swap(self):
        self.assertEqual(required_swap(40 * GIB, 0), 15 * GIB)

    def test_refuse_local_and_self_hosted_before_side_effects(self):
        for environment in ({}, {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'self-hosted'}):
            with patch.dict('os.environ', environment, clear=True), patch('prepare_ci_swap.subprocess.run') as run:
                with self.assertRaisesRegex(RuntimeError, 'restricted'):
                    prepare(Path('/nonexistent'))
                run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
