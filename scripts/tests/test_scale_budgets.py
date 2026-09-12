"""Contrats du gate CI : bruit isolé, régressions et absence de baseline."""

import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    'scale_budgets', Path(__file__).resolve().parents[1] / 'verify-scale-budgets.py')
GATES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATES)


class ScaleBudgetsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        tier = {
            'populationMs': 289, 'populationSamplesMs': [280, 289, 290], 'databaseBytes': 1_000,
            **{name: {'p95Ms': 1} for name in (
                'symbolExact', 'symbolContains', 'symbolMissingWorstCase', 'relationContains', 'targeted100Files')},
        }
        self.report = {
            'profile': 'full', 'protocol': {'version': 5}, 'populationWarmupSamplesMs': [990, 350],
            'sqliteTiers': [dict(copy.deepcopy(tier), symbols=count,
                                 populationSamplesMs=[280, 289, 290] if count == 10_000 else [289])
                            for count in (10_000, 100_000, 500_000, 1_000_000)],
            'portfolio': {
                'maximumProjects': 100, 'totalFullIndexMs': 1,
                'tiers': [{'projects': projects, 'search': {'p95Ms': 1}, 'context': {'p95Ms': 1},
                           'representativeResults': 1, 'projectsRepresented': 1}
                          for projects in (10, 25, 50, 100)],
            },
            'semanticRecovery': {'documents': 1, 'initialRebuildMs': 1,
                                 'incompatibleRecoveryRebuildMs': 1, 'semanticIndexBytes': 1},
            'concurrentReadWrite': {mode: {'reader': {'failures': 0}} for mode in ('delete', 'wal')},
            'usedHeapDeltaBytes': 1, 'totalDurationMs': 1,
        }
        self.write('graph-scale-benchmark', {
            'profile': 'full', 'symbols': 1_000_000, 'relations': 1_000_000, 'graphCandidates': 1,
            'p95Ms': 1, 'heapDeltaBytes': 1, 'databaseBytes': 1,
        })
        self.write('federated-budget-scale-benchmark', {
            'projects': 100, 'globalBudget': 200_000, 'candidateBudgetTotal': 600_000,
            'candidateBudgetMultiplier': 3, 'maxPerProjectCandidateBudget': 6_000,
            'selectedTokens': 200_000, 'deterministic': True, 'durationMs': 1,
        })
        self.write('native-discovery-scale-benchmark', {
            'skills': 1_000, 'visitedEntries': 2_001, 'candidateResources': 1_000,
            'cumulativeBytes': 1, 'deterministic': True, 'durationMs': 1, 'maxDurationMs': 10_000,
        })

    def write(self, name, report):
        (self.directory / (name + '.json')).write_text(json.dumps(report), encoding='utf-8')

    def baseline(self):
        self.write('scale-benchmark-base', self.report)
        (self.directory / 'scale-benchmark-base-population-comparable').touch()

    def verify(self, require_base=False):
        self.write('scale-benchmark', self.report)
        with contextlib.redirect_stdout(io.StringIO()):
            GATES.verify_reports(self.directory, require_base)

    def test_manual_full_run_needs_no_pr_baseline(self):
        self.verify()

    def test_pr_requires_base(self):
        with self.assertRaisesRegex(AssertionError, 'must measure the base'):
            self.verify(require_base=True)

    def test_isolated_population_outlier_does_not_replace_median(self):
        self.baseline()
        self.report['sqliteTiers'][0].update(populationMs=290, populationSamplesMs=[289, 990, 290])
        self.verify(require_base=True)

    def test_reported_population_regression_still_fails(self):
        self.baseline()
        self.report['sqliteTiers'][0].update(populationMs=990, populationSamplesMs=[990, 995, 989])
        with self.assertRaisesRegex(AssertionError, 'population-relative.*990.*289.*489'):
            self.verify(require_base=True)

    def test_reported_search_regression_still_fails(self):
        self.report['portfolio']['tiers'][1]['search']['p95Ms'] = 168.577
        with self.assertRaisesRegex(AssertionError, '25.*search.*168.577.*160'):
            self.verify()

    def test_incorrect_median_fails(self):
        self.report['sqliteTiers'][0]['populationMs'] = 1
        with self.assertRaisesRegex(AssertionError, 'population-median'):
            self.verify()

    def test_cold_start_safety_ceiling_still_applies(self):
        self.report['populationWarmupSamplesMs'][0] = 1_401
        with self.assertRaisesRegex(AssertionError, 'population-cold-safety'):
            self.verify()

    def test_incomplete_comparable_base_fails(self):
        self.baseline()
        incomplete = copy.deepcopy(self.report)
        incomplete['sqliteTiers'].pop(0)
        self.write('scale-benchmark-base', incomplete)
        with self.assertRaisesRegex(AssertionError, 'every base tier'):
            self.verify(require_base=True)

    def test_storage_regression_still_fails(self):
        self.baseline()
        self.report['sqliteTiers'][-1]['databaseBytes'] = 1_201
        with self.assertRaisesRegex(AssertionError, 'database-storage-relative'):
            self.verify(require_base=True)

    def test_mismatched_protocol_cannot_be_marked_comparable(self):
        self.baseline()
        self.report['protocol']['version'] = 4
        with self.assertRaisesRegex(AssertionError, 'Population protocols differ'):
            self.verify(require_base=True)


if __name__ == '__main__':
    unittest.main()
