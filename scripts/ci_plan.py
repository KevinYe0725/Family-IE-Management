"""Conservative CI selection. Unknown inputs fail closed to the complete suite.

Uses only the Python standard library and git/gh already installed on Actions.
No changed filename is ever evaluated as a shell command.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
JAVA = 'src/test/java/com/familyfinance'
CORE_BACKEND = {
    'MoneyTest', 'LedgerPostingServiceTest', 'AccountingCommandExecutorTest',
    'CashAccountingApiTest', 'FxTransferApiTest', 'AuthenticationApiTest',
    'RolePermissionApiTest', 'ReactDistributionTest', 'StaticApplicationTest',
}
CORE_FRONTEND = {
    'src/api/client.test.ts', 'src/auth/AuthPages.test.tsx',
    'src/shared/write-refresh.test.ts', 'src/features/ledger/FxTransfersPanel.test.tsx',
    'src/features/ledger/TransferAccounting.test.tsx',
}
# These are risk groups, not just directory matches. Financial writes affect
# cash, downstream aggregates, linked assets and budgets across module borders.
FINANCIAL = {'loan', 'asset', 'investment', 'ledger', 'transaction', 'fx', 'reporting', 'budget'}
BACKEND_GROUPS = {
    **{name: FINANCIAL | {'accounting', 'notification', 'extension'} for name in FINANCIAL},
    'market': {'market', 'investment', 'reporting'},
    'category': {'category', 'transaction', 'budget', 'reporting', 'ledger'},
    'notification': {'notification', 'loan', 'budget', 'ledger'},
    'ai': {'ai', 'plugins'}, 'plugins': {'ai', 'plugins', 'loan'},
    'extension': {'extension', 'reporting', 'transaction'},
}
FRONTEND_GROUPS = {
    'investment': {'investment', 'dashboard', 'ledger', 'asset'},
    'loan': {'loan', 'asset', 'ledger', 'dashboard'},
    'asset': {'asset', 'loan', 'ledger', 'dashboard', 'investment'},
    'ledger': {'ledger', 'loan', 'asset', 'investment', 'dashboard', 'budget', 'recurring'},
    'dashboard': {'dashboard', 'ledger', 'investment'},
    'budget': {'budget', 'ledger', 'dashboard'},
    'recurring': {'recurring', 'ledger', 'budget'},
    'notification': {'notification', 'recurring', 'loan', 'budget'},
    'ai': {'ai', 'loan'},
}


def inventory(root):
    backend = {p.stem for p in (root / JAVA).rglob('*Test.java')}
    frontend = {p.relative_to(root / 'frontend').as_posix()
                for p in (root / 'frontend/src').rglob('*')
                if re.search(r'\.(test|spec)\.[jt]sx?$', p.name)}
    return backend, frontend


def select(paths, root=ROOT, force_full=False):
    paths = sorted(set(paths))
    all_backend, all_frontend = inventory(root)
    plan = dict(application=False, mode='none', market=False, backend=[], frontend=[],
                changed=paths, reasons=[])
    backend_groups, frontend_groups = set(), set()
    full = force_full
    if force_full:
        plan['reasons'].append('Full verification requested or comparison unavailable')
    for path in paths:
        # Only known documentation locations/extensions are exempt. A script
        # hidden under docs is not classified as harmless documentation.
        if path in {'README.md', 'LICENSE', 'LICENSE.md'} or (
                path.startswith('docs/') and Path(path).suffix.lower() in {'.md', '.png', '.jpg', '.svg', '.pdf', '.docx'}):
            continue
        plan['application'] = True
        if re.search(r'(Test\.java|\.(?:test|spec)\.[jt]sx?)$', path) and not (root / path).is_file():
            full = True
            plan['reasons'].append('Deleted or renamed test: ' + path)
        front = re.match(r'frontend/src/features/([^/]+)/', path)
        java = re.match(r'src/(?:main|test)/java/com/familyfinance/([^/]+)/', path)
        if front and front[1] in FRONTEND_GROUPS:
            frontend_groups.update(FRONTEND_GROUPS[front[1]])
        elif java and java[1] in BACKEND_GROUPS:
            backend_groups.update(BACKEND_GROUPS[java[1]])
            # API contracts are consumed across feature boundaries; all UI
            # regressions run for Java changes, in parallel with backend work.
            frontend_groups.update(FRONTEND_GROUPS)
            if java[1] == 'market':
                plan['market'] = True
        elif path.startswith('scripts/market-data/') and Path(path).suffix == '.py':
            backend_groups.update(BACKEND_GROUPS['market'])
            frontend_groups.update(FRONTEND_GROUPS['investment'])
            plan['market'] = True
        else:
            full = True
            plan['reasons'].append('Shared, high-risk or unmapped change: ' + path)
    if full:
        plan.update(application=True, mode='full', market=True,
                    backend=sorted(all_backend), frontend=sorted(all_frontend))
    elif plan['application']:
        backend = set(CORE_BACKEND)
        for group in backend_groups:
            backend.update(p.stem for p in (root / JAVA / group).rglob('*Test.java'))
        frontend = set(CORE_FRONTEND)
        # Cross-page accounting regressions live directly under features/.
        frontend.update(p for p in all_frontend if p.startswith('src/features/') and p.count('/') == 2)
        frontend.update(p for p in all_frontend
                        if any(p.startswith('src/features/' + group + '/') for group in frontend_groups))
        # A backend contract change can also affect auth, app routing and plugins.
        if any(p.startswith('src/main/java/') or p.startswith('src/test/java/') for p in paths):
            frontend = all_frontend
        plan.update(mode='selected', backend=sorted(backend), frontend=sorted(frontend))
        plan['reasons'].append('Core safety suite plus explicitly related modules')
    else:
        plan['reasons'].append('Documentation only or no unverified changes; no application release')
    validate(plan, root)
    return plan


def validate(plan, root=ROOT):
    if plan['mode'] not in {'none', 'selected', 'full'}:
        raise ValueError('Unknown test selection mode')
    if plan['application'] != (plan['mode'] != 'none'):
        raise ValueError('Inconsistent application selection')
    if not plan['application']:
        return
    backend, frontend = inventory(root)
    for key, available in [('backend', backend), ('frontend', frontend)]:
        selected = set(plan[key])
        if not selected or not selected <= available:
            raise ValueError('Empty or missing ' + key + ' tests: ' + str(selected - available))
    if not CORE_BACKEND <= set(plan['backend']) or not CORE_FRONTEND <= set(plan['frontend']):
        raise ValueError('Core safety checks must not be omitted')


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()


def changed_paths(root, base, head):
    # --no-renames exposes both deleted and added paths, so moving a shared
    # component into a feature directory cannot hide its old impact.
    raw = subprocess.check_output(['git', '-C', str(root), 'diff', '--no-renames',
                                   '--name-only', '-z', base, head])
    return [p for p in raw.decode().split('\0') if p]


def choose_baseline(runs, branch, nightly=False):
    candidates = [r for r in runs if r.get('conclusion') == 'success'
                  and r.get('head_branch') == branch
                  and r.get('event') in ({'schedule'} if nightly else {'push', 'workflow_dispatch', 'schedule'})]
    if not candidates:
        return None
    return max(candidates, key=lambda r: r['created_at'])['head_sha']


def baseline_from_actions(event_name, event):
    if event_name == 'pull_request':
        return git(ROOT, 'merge-base', event['pull_request']['base']['sha'], 'HEAD')
    branch = os.environ['GITHUB_REF_NAME']
    raw = subprocess.check_output([
        'gh', 'api', '--method', 'GET',
        'repos/' + os.environ['GITHUB_REPOSITORY'] + '/actions/workflows/deploy-stage2.yml/runs',
        '-f', 'branch=' + branch, '-f', 'status=success', '-f', 'per_page=100'], text=True)
    return choose_baseline(json.loads(raw)['workflow_runs'], branch, event_name == 'schedule')


def build_plan(root, event_name, event, base, force_full=False):
    if base and not re.fullmatch(r'[0-9a-f]{40}', base):
        raise ValueError('Baseline must be an exact commit SHA')
    if base:
        # Do not use a successful run from a divergent or force-pushed history.
        subprocess.run(['git', '-C', str(root), 'merge-base', '--is-ancestor', base, 'HEAD'], check=True)
        paths = changed_paths(root, base, 'HEAD')
    else:
        paths = []
        force_full = True
    if event_name == 'schedule' and paths and select(paths, root)['application']:
        force_full = True
    inputs = event.get('inputs') or {}
    if inputs.get('full_tests') in (True, 'true'):
        force_full = True
    # An explicit redeploy still verifies and builds even at an unchanged SHA.
    if event_name == 'workflow_dispatch' and not paths:
        force_full = True
    plan = select(paths, root, force_full)
    plan['base'] = base
    return plan


def run_tests(plan, side, root=ROOT):
    validate(plan, root)
    if not plan['application']:
        raise ValueError('No application tests requested')
    if side == 'frontend':
        args = ['npm', 'test', '--', '--run', '--maxWorkers=2']
        if plan['mode'] != 'full':
            args.extend(plan['frontend'])
        return subprocess.run(args, cwd=root / 'frontend', check=True)
    args = ['./mvnw', '-B', '-Dskip.npm=true', '-Dskip.installnodenpm=true']
    if plan['mode'] != 'full':
        args.append('-Dtest=' + ','.join(plan['backend']))
    return subprocess.run([*args, 'verify'], cwd=root, check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--plan', default='.ci-plan.json')
    parser.add_argument('--run', choices=['frontend', 'backend'])
    parser.add_argument('--base')
    parser.add_argument('--force-full', action='store_true')
    args = parser.parse_args()
    if args.run:
        run_tests(json.loads(Path(args.plan).read_text()), args.run)
        return
    event_name = os.environ.get('GITHUB_EVENT_NAME', 'local')
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text()) if os.environ.get('GITHUB_EVENT_PATH') else {}
    try:
        base = args.base or baseline_from_actions(event_name, event)
        plan = build_plan(ROOT, event_name, event, base, args.force_full)
    except (KeyError, ValueError, TypeError, AttributeError, OSError, subprocess.SubprocessError):
        # Avoid echoing API responses/environment details; unavailable history
        # can never become permission to skip tests.
        plan = select([], ROOT, force_full=True)
        plan['base'] = None
        plan['reasons'] = ['Comparison unavailable or invalid; running full verification']
    Path(args.plan).write_text(json.dumps(plan, indent=2) + '\n')
    print(json.dumps(plan, indent=2))
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as out:
            for key in ['application', 'market']:
                out.write(f'{key}={str(plan[key]).lower()}\n')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as out:
            out.write('## Test selection\n\nMode: ' + plan['mode'] + '\n\n')
            out.write('Exact tests, comparison baseline, changed files and reasons:\n\n```json\n')
            out.write(json.dumps(plan, indent=2).replace('`', '\\u0060') + '\n```\n')


if __name__ == '__main__':
    main()
