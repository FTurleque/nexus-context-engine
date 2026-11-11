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

    def test_full_profile_total_duration_budget(self):
        self.report['totalDurationMs'] = 240_000
        self.verify()
        self.report['totalDurationMs'] = 240_001
        with self.assertRaisesRegex(AssertionError, 'full-total-duration.*240001'):
            self.verify()

    def test_incorrect_median_fails(self):
        self.report['sqliteTiers'][0]['populationMs'] = 1
        with self.assertRaisesRegex(AssertionError, 'population-median'):
            self.verify()

    def test_reported_cold_start_does_not_fail_measured_population(self):
        self.baseline()
        self.report['populationWarmupSamplesMs'] = [1_547, 259]
        self.verify(require_base=True)

    def test_measured_population_safety_ceiling_still_applies(self):
        self.report['populationWarmupSamplesMs'] = [1_547, 259]
        self.report['sqliteTiers'][0].update(populationMs=1_401, populationSamplesMs=[1_401, 1_402, 1_400])
        with self.assertRaisesRegex(AssertionError, 'population-safety.*1401.*1400'):
            self.verify()

    def test_warmup_samples_must_still_be_complete_finite_and_nonnegative(self):
        for warmups in ([1_547], [float('nan'), 259], [float('inf'), 259], [-1, 259]):
            with self.subTest(warmups=warmups):
                self.report['populationWarmupSamplesMs'] = warmups
                with self.assertRaises(AssertionError):
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

    def repeated_curve(self):
        self.report['protocol'] = {'version': 6, 'population': {'largeTierSamples': 3}}
        self.report['sqliteOnly'] = True
        for tier in self.report['sqliteTiers']:
            tier['populationSamplesMs'] = [tier['populationMs']] * 3

    def test_isolated_million_symbol_outlier_preserves_absolute_and_relative_gates(self):
        self.repeated_curve()
        self.report['sqliteTiers'][-1].update(populationMs=38_789, populationSamplesMs=[38_789] * 3)
        self.baseline()
        self.report['sqliteTiers'][-1].update(populationMs=38_999, populationSamplesMs=[38_789, 45_880, 38_999])
        self.verify(require_base=True)

    def test_repeated_million_symbol_slowdown_still_fails_absolute_gate(self):
        self.repeated_curve()
        self.report['sqliteTiers'][-1].update(populationMs=45_880, populationSamplesMs=[45_000, 45_880, 46_000])
        with self.assertRaisesRegex(AssertionError, 'population-safety.*45880.*40000'):
            self.verify()

    def test_isolated_million_symbol_outlier_above_ceiling_is_tolerated_by_median(self):
        self.repeated_curve()
        self.report['sqliteTiers'][-1].update(populationMs=31_200, populationSamplesMs=[31_000, 45_880, 31_200])
        self.verify()

    def million_symbol_population(self, population_500k, population_1m):
        self.report['sqliteTiers'][2].update(populationMs=population_500k, populationSamplesMs=[population_500k])
        self.report['sqliteTiers'][3].update(populationMs=population_1m, populationSamplesMs=[population_1m])

    def test_slow_runner_million_symbol_population_follows_same_report_scaling(self):
        # Rapport général v0.2.0 du 2026-11-11 : runner lent à tous les paliers, rapport 1M/500k de 2,42.
        self.million_symbol_population(16_810, 40_755)
        self.verify()

    def test_super_linear_million_symbol_slowdown_still_fails(self):
        self.million_symbol_population(16_810, 43_707)
        with self.assertRaisesRegex(AssertionError, 'population-safety.*43707.*43706'):
            self.verify()

    def test_fast_runner_keeps_absolute_million_symbol_ceiling(self):
        self.million_symbol_population(12_652, 40_001)
        with self.assertRaisesRegex(AssertionError, 'population-safety.*40001.*40000'):
            self.verify()

    def test_uniform_slowdown_still_fails_on_500k_ceiling(self):
        self.million_symbol_population(20_001, 48_500)
        with self.assertRaisesRegex(AssertionError, '500000.*population-safety.*20001.*20000'):
            self.verify()

    def test_uniform_slowdown_fails_on_million_symbol_hard_ceiling(self):
        # Palier 500k à son plafond : le plafond proportionnel atteindrait 52 000 ms.
        self.million_symbol_population(20_000, 52_000)
        with self.assertRaisesRegex(
                AssertionError,
                r"1000000, 'population-safety', 52000, 48906, 'proportional=', 52000, 'hard=', 48906"):
            self.verify()

    def test_uniform_slowdown_on_repeated_curve_fails_on_million_symbol_hard_ceiling(self):
        self.repeated_curve()
        self.report['sqliteTiers'][2].update(populationMs=20_000, populationSamplesMs=[20_000] * 3)
        self.report['sqliteTiers'][3].update(populationMs=52_000, populationSamplesMs=[51_900, 52_000, 52_100])
        with self.assertRaisesRegex(AssertionError, 'population-safety.*52000.*48906.*hard=.*48906'):
            self.verify()

    def test_million_symbol_hard_ceiling_is_inclusive(self):
        self.million_symbol_population(20_000, 48_906)
        self.verify()
        self.million_symbol_population(20_000, 48_907)
        with self.assertRaisesRegex(AssertionError, 'population-safety.*48907.*48906'):
            self.verify()

    def test_million_symbol_hard_ceiling_also_bounds_comparable_base_runs(self):
        self.million_symbol_population(20_000, 48_000)
        self.baseline()
        self.million_symbol_population(20_000, 50_000)
        with self.assertRaisesRegex(AssertionError, 'population-safety.*50000.*48906'):
            self.verify(require_base=True)

    def test_repeated_million_symbol_slowdown_on_slow_runner_still_fails(self):
        self.repeated_curve()
        self.report['sqliteTiers'][2].update(populationMs=16_244, populationSamplesMs=[16_185, 16_244, 16_244])
        self.report['sqliteTiers'][3].update(populationMs=45_000, populationSamplesMs=[44_900, 45_000, 45_200])
        with self.assertRaisesRegex(AssertionError, 'population-safety.*45000.*42234'):
            self.verify()

    def test_dedicated_curve_requires_three_samples_at_every_tier(self):
        self.repeated_curve()
        self.report['sqliteTiers'][-1]['populationSamplesMs'] = [289]
        with self.assertRaisesRegex(AssertionError, 'population-samples'):
            self.verify()


if __name__ == '__main__':
    unittest.main()
