#!/usr/bin/env python3
"""Vérifie les rapports scale avec les plafonds de régression du projet."""

import json
import math
from pathlib import Path
from statistics import median


def verify_reports(directory: Path, require_base: bool = False):
    report = json.loads((directory / 'scale-benchmark.json').read_text())
    graph = json.loads((directory / 'graph-scale-benchmark.json').read_text())
    federated = json.loads((directory / 'federated-budget-scale-benchmark.json').read_text())
    native = json.loads((directory / 'native-discovery-scale-benchmark.json').read_text())
    sqlite_full_path = (directory / 'scale-benchmark-sqlite-full.json')
    sqlite_report = json.loads(sqlite_full_path.read_text()) if sqlite_full_path.exists() else report
    baseline_path = (directory / 'scale-benchmark-base.json')
    baseline = json.loads(baseline_path.read_text()) if baseline_path.exists() else None
    population_comparable = (directory / 'scale-benchmark-base-population-comparable').exists()
    if require_base:
        assert baseline is not None, 'PR scale runs must measure the base on the same runner'
    if population_comparable:
        assert baseline is not None, 'Comparable population marker requires a base report'
        assert baseline.get('protocol') == sqlite_report.get('protocol'), 'Population protocols differ'
    profile = report['profile']
    assert profile in ('ci', 'full')
    assert graph['profile'] == profile
    assert report['sqliteTiers']
    assert sqlite_report['sqliteTiers']
    assert report['portfolio']['tiers']
    assert report['semanticRecovery']['documents'] > 0

    sqlite = {tier['symbols']: tier for tier in sqlite_report['sqliteTiers']}
    baseline_sqlite = ({tier['symbols']: tier for tier in baseline['sqliteTiers']}
                       if baseline is not None else {})
    portfolio = {tier['projects']: tier for tier in report['portfolio']['tiers']}
    expected_sqlite = {10_000, 100_000, 500_000, 1_000_000} if require_base or profile == 'full' else {10_000, 100_000}
    assert set(sqlite) == expected_sqlite, ('sqlite-tiers', sorted(sqlite), sorted(expected_sqlite))
    expected_portfolio = {10, 25, 50, 100} if profile == 'full' else {10, 25}
    assert set(portfolio) == expected_portfolio, ('portfolio-tiers', sorted(portfolio))
    if population_comparable:
        assert set(baseline_sqlite) == set(sqlite), 'Comparable population requires every base tier'

    sqlite_budgets = {
        10_000:  {'exact': 50,   'contains': 30,   'miss': 30,   'relation': 25},
        100_000: {'exact': 250,  'contains': 150,  'miss': 150,  'relation': 100},
        500_000: {'exact': 1000, 'contains': 600,  'miss': 600,  'relation': 400},
        1_000_000: {'exact': 2000, 'contains': 1200, 'miss': 1200, 'relation': 800},
    }
    population_safety_ceiling = {
        10_000: 1_400,
        100_000: 5_000,
        500_000: 20_000,
        1_000_000: 40_000,
    }
    population_jitter_ms = {
        10_000: 200,
        100_000: 500,
        500_000: 1_500,
        1_000_000: 3_000,
    }

    for population_report in (sqlite_report, baseline if population_comparable else None):
        if population_report is None or population_report.get('protocol', {}).get('version', 0) < 5:
            continue
        warmups = population_report['populationWarmupSamplesMs']
        assert len(warmups) == 2 and all(math.isfinite(value) and value >= 0 for value in warmups), warmups
        # Le plafond absolu reste aussi appliqué au démarrage à froid du candidat.
        if population_report is sqlite_report:
            assert max(warmups) <= population_safety_ceiling[10_000], ('population-cold-safety', warmups)
        for tier in population_report['sqliteTiers']:
            samples = tier['populationSamplesMs']
            assert len(samples) == (3 if tier['symbols'] == 10_000 else 1), ('population-samples', tier)
            assert all(math.isfinite(value) and value >= 0 for value in samples), samples
            assert tier['populationMs'] == median(samples), ('population-median', tier)

    for symbols, tier in sqlite.items():
        budget = sqlite_budgets[symbols]
        assert tier['symbolExact']['p95Ms'] <= budget['exact'], (symbols, 'exact', tier['symbolExact']['p95Ms'])
        assert tier['symbolContains']['p95Ms'] <= budget['contains'], (symbols, 'contains', tier['symbolContains']['p95Ms'])
        assert tier['symbolMissingWorstCase']['p95Ms'] <= budget['miss'], (symbols, 'miss', tier['symbolMissingWorstCase']['p95Ms'])
        assert tier['relationContains']['p95Ms'] <= budget['relation'], (symbols, 'relation', tier['relationContains']['p95Ms'])
        assert tier['targeted100Files']['p95Ms'] <= 30, (symbols, 'targeted-files', tier['targeted100Files']['p95Ms'])

        population = tier['populationMs']
        ceiling = population_safety_ceiling[symbols]
        assert population <= ceiling, (symbols, 'population-safety', population, ceiling)
        if population_comparable and symbols in baseline_sqlite:
            baseline_population = baseline_sqlite[symbols]['populationMs']
            relative_ceiling = max(
                int(baseline_population * 1.20),
                baseline_population + population_jitter_ms[symbols])
            assert population <= relative_ceiling, (
                symbols,
                'population-relative',
                population,
                baseline_population,
                relative_ceiling)
            print(
                'sqlite-population-relative=',
                symbols,
                'candidate=', population,
                'base=', baseline_population,
                'allowed=', relative_ceiling)

    if sqlite_full_path.exists():
        assert set(sqlite) == {10_000, 100_000, 500_000, 1_000_000}, sorted(sqlite)
    if require_base:
        assert 1_000_000 in baseline_sqlite, 'PR scale runs must measure the 1M base storage footprint'
    if 1_000_000 in sqlite and baseline is not None:
        assert 1_000_000 in baseline_sqlite, 'Base storage report is incomplete'
        candidate_bytes = sqlite[1_000_000]['databaseBytes']
        baseline_bytes = baseline_sqlite[1_000_000]['databaseBytes']
        storage_ceiling = int(baseline_bytes * 1.20)
        assert candidate_bytes <= storage_ceiling, (
            'database-storage-relative', candidate_bytes, baseline_bytes, storage_ceiling)
        print(
            'sqlite-storage-relative=',
            'candidate=', candidate_bytes,
            'base=', baseline_bytes,
            'allowed=', storage_ceiling)

    portfolio_budgets = {
        10: {'search': 120, 'context': 160},
        25: {'search': 160, 'context': 260},
        50: {'search': 250, 'context': 450},
        100: {'search': 400, 'context': 800},
    }
    for projects, tier in portfolio.items():
        budget = portfolio_budgets[projects]
        assert tier['search']['p95Ms'] <= budget['search'], (projects, 'search', tier['search']['p95Ms'], budget['search'])
        assert tier['context']['p95Ms'] <= budget['context'], (projects, 'context', tier['context']['p95Ms'], budget['context'])
        assert tier['representativeResults'] > 0
        assert tier['projectsRepresented'] > 0

    expected_graph_scale = 1_000_000 if profile == 'full' else 100_000
    assert graph['symbols'] == expected_graph_scale, graph['symbols']
    assert graph['relations'] == expected_graph_scale, graph['relations']
    assert graph['graphCandidates'] > 0
    assert graph['graphCandidates'] <= 4_000, graph['graphCandidates']
    assert graph['p95Ms'] <= 15_000, graph['p95Ms']
    assert graph['heapDeltaBytes'] <= 256 * 1024 * 1024, graph['heapDeltaBytes']
    assert graph['databaseBytes'] <= 900 * 1024 * 1024, graph['databaseBytes']

    assert federated['projects'] == 100, federated['projects']
    assert federated['globalBudget'] == 200_000, federated['globalBudget']
    assert federated['candidateBudgetTotal'] == 600_000, federated['candidateBudgetTotal']
    assert federated['candidateBudgetMultiplier'] <= 3.0, federated['candidateBudgetMultiplier']
    assert federated['maxPerProjectCandidateBudget'] <= 6_000, federated['maxPerProjectCandidateBudget']
    assert federated['selectedTokens'] <= 200_000, federated['selectedTokens']
    assert federated['deterministic'] is True
    assert federated['durationMs'] <= 5_000, federated['durationMs']

    assert native['skills'] == 1_000, native
    assert native['visitedEntries'] == 2_001, native
    assert native['candidateResources'] == 1_000, native
    assert native['cumulativeBytes'] > 0, native
    assert native['deterministic'] is True, native
    assert native['durationMs'] <= native['maxDurationMs'] <= 10_000, native

    if profile == 'full':
        assert report['portfolio']['totalFullIndexMs'] <= 6000, report['portfolio']['totalFullIndexMs']
        assert report['semanticRecovery']['initialRebuildMs'] <= 12000, report['semanticRecovery']['initialRebuildMs']
        assert report['semanticRecovery']['incompatibleRecoveryRebuildMs'] <= 12000, report['semanticRecovery']['incompatibleRecoveryRebuildMs']
        assert report['semanticRecovery']['semanticIndexBytes'] <= 8 * 1024 * 1024, report['semanticRecovery']['semanticIndexBytes']
        assert report['totalDurationMs'] <= 180000, report['totalDurationMs']

    assert report['concurrentReadWrite']['delete']['reader']['failures'] == 0
    assert report['concurrentReadWrite']['wal']['reader']['failures'] == 0
    assert report['usedHeapDeltaBytes'] <= 256 * 1024 * 1024, report['usedHeapDeltaBytes']

    print('profile=', profile)
    print('sqlite-profile=', sqlite_report['profile'])
    print('sqlite-max=', sqlite_report['sqliteTiers'][-1]['symbols'])
    print('portfolio-max=', report['portfolio']['maximumProjects'])
    print('semantic-documents=', report['semanticRecovery']['documents'])
    print('graph-symbols=', graph['symbols'])
    print('graph-relations=', graph['relations'])
    print('graph-p95-ms=', graph['p95Ms'])
    print('federated-projects=', federated['projects'])
    print('federated-global-budget=', federated['globalBudget'])
    print('federated-work-budget=', federated['candidateBudgetTotal'])
    print('native-skills=', native['skills'])
    print('native-visited=', native['visitedEntries'])
    print('native-duration-ms=', native['durationMs'])
    print('total-duration-ms=', report['totalDurationMs'])
    print('all-regression-budgets=PASS')

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, nargs="?", default=Path("target"))
    parser.add_argument("--require-base", action="store_true")
    args = parser.parse_args()
    verify_reports(args.directory, args.require_base)
