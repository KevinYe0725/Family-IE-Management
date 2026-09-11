"""Selection must fail closed; exercise real git diffs without starting services."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


class SelectionTest(unittest.TestCase):
    def setUp(self):
        path = ROOT / 'scripts/ci_plan.py'
        self.assertTrue(path.exists(), 'CI selection is not implemented')
        spec = importlib.util.spec_from_file_location('ci_plan', path)
        self.ci = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.ci)

    def test_docs_skip_application(self):
        plan = self.ci.select(['docs/guide.md', 'README.md'], ROOT)
        self.assertFalse(plan['application'])

    def test_frontend_change_keeps_backend_core_and_selects_related_ui(self):
        plan = self.ci.select(['frontend/src/features/investment/StockChart.tsx'], ROOT)
        self.assertEqual(plan['mode'], 'selected')
        self.assertIn('src/features/investment/StockChart.test.tsx', plan['frontend'])
        self.assertIn('src/features/dashboard/HomePortfolioLive.test.tsx', plan['frontend'])
        self.assertIn('src/features/asset/AssetsInvestments.test.tsx', plan['frontend'])
        self.assertIn('CashAccountingApiTest', plan['backend'])
        self.assertNotIn('LoanPrepaymentStrategyApiTest', plan['backend'])

    def test_loan_backend_covers_cash_asset_reporting_and_frontend(self):
        plan = self.ci.select(['src/main/java/com/familyfinance/loan/LoanService.java'], ROOT)
        for name in ['LoanRepaymentApiTest', 'AssetApiTest', 'TransactionSummaryApiTest', 'BudgetApiTest',
                     'NotificationApiTest', 'AnnualStatsCalculationTest']:
            self.assertIn(name, plan['backend'])
        self.assertIn('src/features/loan/LoanPayoff.test.tsx', plan['frontend'])

    def test_high_risk_unknown_and_dependencies_force_full(self):
        for path in ['pom.xml', 'frontend/package-lock.json', 'src/main/java/com/familyfinance/accounting/LedgerPostingService.java',
                     'src/main/resources/db/migration/V99.sql', 'frontend/src/api/client.ts',
                     'src/main/java/com/familyfinance/newmodule/Thing.java', '.github/workflows/deploy-stage2.yml']:
            with self.subTest(path=path):
                self.assertEqual(self.ci.select([path], ROOT)['mode'], 'full')

    def test_manual_full_overrides_docs_and_empty_diff(self):
        for paths in [[], ['README.md']]:
            self.assertEqual(self.ci.select(paths, ROOT, force_full=True)['mode'], 'full')

    def test_market_adapter_selects_both_sides_of_contract(self):
        plan = self.ci.select(['scripts/market-data/overseas.py'], ROOT)
        self.assertTrue(plan['market'])
        self.assertIn('OverseasMarketApiTest', plan['backend'])
        self.assertIn('src/features/investment/OverseasMarket.test.tsx', plan['frontend'])

    def test_deleted_and_renamed_source_paths_are_not_lost(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            def git(*args):
                return subprocess.check_output(['git', '-C', temp, *args], text=True).strip()
            git('init', '-q')
            git('config', 'user.email', 'test@example.invalid')
            git('config', 'user.name', 'CI test')
            (root / 'old.ts').write_text('old')
            git('add', '.')
            git('commit', '-qm', 'baseline')
            baseline = git('rev-parse', 'HEAD')
            git('mv', 'old.ts', 'new.ts')
            git('commit', '-qam', 'rename')
            (root / 'second.ts').write_text('second commit')
            git('add', '.')
            git('commit', '-qm', 'later commit')
            paths = self.ci.changed_paths(root, baseline, 'HEAD')
            self.assertEqual(set(paths), {'old.ts', 'new.ts', 'second.ts'})

    def test_selected_tests_exist_and_lists_are_nonempty(self):
        plan = self.ci.select(['frontend/src/features/notification/NotificationsPage.tsx'], ROOT)
        self.assertTrue(plan['frontend'])
        self.assertTrue(plan['backend'])
        self.ci.validate(plan, ROOT)
        plan['backend'].append('MissingTest')
        with self.assertRaises(ValueError):
            self.ci.validate(plan, ROOT)

    def test_baseline_excludes_failed_pr_and_other_branch_runs(self):
        runs = [
            dict(conclusion='success', head_branch='main', event='push', created_at='2026-09-01', head_sha='a' * 40),
            dict(conclusion='failure', head_branch='main', event='push', created_at='2026-09-02', head_sha='b' * 40),
            dict(conclusion='success', head_branch='main', event='pull_request', created_at='2026-09-03', head_sha='c' * 40),
            dict(conclusion='success', head_branch='other', event='push', created_at='2026-09-04', head_sha='d' * 40),
        ]
        self.assertEqual(self.ci.choose_baseline(runs, 'main'), 'a' * 40)
        self.assertIsNone(self.ci.choose_baseline(runs, 'main', nightly=True))

    def test_nightly_compares_last_nightly_not_last_push(self):
        runs = [
            dict(conclusion='success', head_branch='main', event='schedule', created_at='2026-09-01', head_sha='a' * 40),
            dict(conclusion='success', head_branch='main', event='push', created_at='2026-09-02', head_sha='b' * 40),
        ]
        self.assertEqual(self.ci.choose_baseline(runs, 'main', nightly=True), 'a' * 40)

    def test_unknown_history_and_manual_same_sha_cannot_skip_tests(self):
        self.assertEqual(self.ci.build_plan(ROOT, 'push', {}, None)['mode'], 'full')
        sha = self.ci.git(ROOT, 'rev-parse', 'HEAD')
        self.assertFalse(self.ci.build_plan(ROOT, 'schedule', {}, sha)['application'])
        self.assertEqual(self.ci.build_plan(ROOT, 'workflow_dispatch', {}, sha)['mode'], 'full')

    def test_nightly_docs_only_does_not_rebuild_application(self):
        sha = self.ci.git(ROOT, 'rev-parse', 'HEAD')
        with patch.object(self.ci, 'changed_paths', return_value=['docs/readme.md']):
            self.assertFalse(self.ci.build_plan(ROOT, 'schedule', {}, sha)['application'])

    def test_financial_module_changes_include_underlying_multicurrency_ledger(self):
        plan = self.ci.select(['src/main/java/com/familyfinance/loan/LoanService.java'], ROOT)
        self.assertIn('MultiCurrencyLedgerTest', plan['backend'])

    def test_invalid_baseline_fails_instead_of_diffing_arbitrary_ref(self):
        with self.assertRaises(ValueError):
            self.ci.build_plan(ROOT, 'push', {}, '--all')

    def test_unknown_plan_mode_is_rejected(self):
        plan = self.ci.select(['frontend/src/features/investment/StockChart.tsx'], ROOT)
        plan['mode'] = 'unknown'
        with self.assertRaises(ValueError):
            self.ci.validate(plan, ROOT)

    def test_deleted_test_path_forces_complete_remaining_suite(self):
        plan = self.ci.select(['src/test/java/com/familyfinance/loan/RemovedRegressionTest.java'], ROOT)
        self.assertEqual(plan['mode'], 'full')

    def test_cli_invalid_history_produces_full_plan_not_successful_skip(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'plan.json'
            result = subprocess.run(['python3', str(ROOT / 'scripts/ci_plan.py'),
                                     '--base', 'invalid', '--plan', str(output)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            plan = json.loads(output.read_text())
            self.assertEqual(plan['mode'], 'full')
            self.assertTrue(plan['application'])
            self.assertIsNone(plan['base'])

    def test_both_runners_propagate_failure_and_keep_safe_process_arguments(self):
        plan = self.ci.select(['frontend/src/features/investment/StockChart.tsx'], ROOT)
        for side in ['frontend', 'backend']:
            with patch.object(self.ci.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, 'test')):
                with self.assertRaises(subprocess.CalledProcessError):
                    self.ci.run_tests(plan, side)


if __name__ == '__main__':
    unittest.main()
