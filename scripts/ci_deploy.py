#!/usr/bin/python3
"""Restricted SSH deployment receiver. Install root-owned; never execute via a shell."""
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile


MAX_JAR_BYTES = 500 * 1024 * 1024
ADAPTER_FILES = ('server.py', 'overseas.py', 'overseas_sources.py', 'requirements.txt')
RELEASE_FILES = {'app.jar', *(f'market/{name}' for name in ADAPTER_FILES)}


def report_progress(stage, started, **details):
    """Best-effort diagnostics must never interrupt installation or rollback."""
    try:
        print('[deploy] ' + json.dumps({'stage': stage,
              'elapsed_seconds': round(time.monotonic() - started, 1), **details}),
              file=sys.stderr, flush=True)
    except TimeoutError:
        # SIGTERM uses TimeoutError: never swallow the recovery control signal.
        raise
    except OSError:
        pass


def unpack_release(bundle, directory, commit):
    """Never trust archive paths, links, duplicate entries or inflated sizes."""
    with zipfile.ZipFile(bundle) as archive:
        infos = archive.infolist()
        if len(infos) != len(RELEASE_FILES) + 1 or {i.filename for i in infos} != RELEASE_FILES | {'release.json'}:
            raise ValueError('Incomplete or unexpected release members')
        for info in infos:
            mode = info.external_attr >> 16
            if mode & 0o170000 not in (0, 0o100000):
                raise ValueError('Only regular release members allowed')
            limit = MAX_JAR_BYTES if info.filename == 'app.jar' else 1024 * 1024
            if info.file_size > limit:
                raise ValueError('Release member exceeds size limit')
        manifest = json.loads(archive.read('release.json'))
        if manifest.get('schema') != 1 or manifest.get('commit') != commit:
            raise ValueError('Release schema or commit mismatch')
        if set(manifest.get('files', {})) != RELEASE_FILES:
            raise ValueError('Incomplete release manifest')
        for name in sorted(RELEASE_FILES):
            raw = archive.read(name)
            if hashlib.sha256(raw).hexdigest() != manifest['files'][name]:
                raise ValueError('Release member checksum mismatch')
            target = directory / name
            target.parent.mkdir(exist_ok=True)
            target.write_bytes(raw)
        with zipfile.ZipFile(directory / 'app.jar') as jar:
            if 'Main-Class:' not in jar.read('META-INF/MANIFEST.MF').decode():
                raise ValueError('Not an executable JAR')
            jar.getinfo('BOOT-INF/classes/static/index.html')
        if jar_commit(directory / 'app.jar') != commit:
            raise ValueError('Artifact commit mismatch')
        (directory / 'market/deployment.json').write_text(json.dumps({
            'commit': commit, 'schema': 1,
            'files': {n: manifest['files'][f'market/{n}'] for n in ADAPTER_FILES}}))
        return manifest

MARKER = "BOOT-INF/classes/static/deployment.json"


def write_state(path, value):
    # The state directory can be on a different filesystem from the application.
    with tempfile.NamedTemporaryFile(mode='w', dir=path.parent, delete=False) as dest:
        temporary = Path(dest.name)
        try:
            json.dump(value, dest)
            dest.flush()
            os.fsync(dest.fileno())
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)



def parse_command(command):
    match = re.fullmatch(r"deploy ([0-9a-f]{40}) ([0-9a-f]{64}) ([1-9][0-9]{0,19})", command)
    if not match:
        raise ValueError("Only deploy <commit> <checksum> <run-id> is allowed")
    return match[1], match[2], int(match[3])


def jar_commit(path):
    with zipfile.ZipFile(path) as archive:
        info = archive.getinfo(MARKER)
        if info.file_size > 1024:
            raise ValueError("Oversized deployment marker")
        return json.loads(archive.read(MARKER))["commit"]


class Deployer:
    def __init__(self, jar, state, service, base_url, market_root=None, market_service=None, market_url=None):
        self.jar = Path(jar)
        self.state = Path(state)
        self.service = service
        self.base_url = base_url.rstrip("/")
        self.market_root = Path(market_root) if market_root else None
        self.market_service = market_service
        self.market_url = market_url.rstrip('/') if market_url else None

    def stop(self):
        for service in (self.service, self.market_service):
            subprocess.run(['/usr/bin/systemctl', 'stop', service], check=True, timeout=90)

    def switch_adapter(self, target):
        link = self.market_root / '.current-next'
        link.unlink(missing_ok=True)
        link.symlink_to(target)
        os.replace(link, self.market_root / 'current')

    def check_market(self, commit):
        def get(path):
            with urllib.request.urlopen(self.market_url + path, timeout=25) as response:
                return json.load(response)
        if commit:
            marker = get('/health')
            if marker.get('commit') != commit or marker.get('status') != 'ready':
                raise ValueError('Wrong or incomplete adapter version')
            # Exercise real directory + decoding paths under the unchanged service sandbox.
            probes = [('HK', '00700'), ('US', 'AAPL')]
            directories = [get(f'/overseas/search?market={m}&q={s}') for m, s in probes]
            for (market, symbol), result in zip(probes, directories):
                if (result.get('state') != 'READY' or result.get('stale') is not False
                        or not any(i.get('symbol') == symbol and i.get('market') == market
                                   for i in result.get('items', []))):
                    raise ValueError(f'{market} directory is not ready')
                candles = get(f'/overseas/candles?market={market}&symbol={symbol}')
                if (candles.get('instrument', {}).get('market') != market or candles.get('symbol') != symbol
                        or candles.get('source') != 'SINA' or candles.get('adjustment') != 'none'
                        or candles.get('stale') is not False or not candles.get('bars')):
                    raise ValueError(f'{market} candles are not ready')
        else:
            # Legacy baseline may lack overseas routes; verify its process, not new features.
            subprocess.run(['/usr/bin/systemctl', 'is-active', '--quiet', self.market_service],
                           check=True, timeout=10)

    def restart(self):
        for service in (self.market_service, self.service):
            subprocess.run(["/usr/bin/systemctl", "restart", service], check=True, timeout=90)

    def wait_ready(self, commit, *, legacy_adapter=False):
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(self.base_url + "/api/csrf", timeout=5) as response:
                    payload = json.load(response)
                    if not payload.get("data", {}).get("token"):
                        raise ValueError("CSRF API is not ready")
                with urllib.request.urlopen(self.base_url + "/", timeout=5) as response:
                    if b"<html" not in response.read(200000).lower():
                        raise ValueError("Frontend is not ready")
                if commit:
                    with urllib.request.urlopen(self.base_url + "/deployment.json", timeout=5) as response:
                        if json.load(response).get("commit") != commit:
                            raise ValueError("Wrong served version")
                self.check_market(None if legacy_adapter else commit)
                return
            except (OSError, ValueError):
                time.sleep(3)
        raise RuntimeError("Service readiness check timed out")

    def deploy(self, commit, digest, run_id, stream):
        started = time.monotonic()
        self.state.mkdir(parents=True, exist_ok=True, mode=0o700)
        with (self.state / "deploy.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            pending_path = self.state / 'pending.json'
            if pending_path.exists():
                raise ValueError('Interrupted deployment requires operator recovery of pending.json')
            current_path = self.state / "current.json"
            current = json.loads(current_path.read_text()) if current_path.exists() else {}
            if run_id < current.get("run_id", 0):
                raise ValueError("Refusing older workflow run")
            if not self.jar.is_file() or self.jar.is_symlink():
                raise ValueError("Existing regular application JAR is required")
            if self.market_root is None or not self.market_service or not self.market_url:
                raise ValueError('Versioned market adapter configuration required')
            pointer = self.market_root / 'current'
            if not pointer.is_symlink() or not pointer.resolve().is_dir():
                raise ValueError('Existing adapter current symlink required; bootstrap first')
            old_adapter = pointer.resolve()
            # Same filesystem as JAR target; market staging separately uses market filesystem.
            with tempfile.TemporaryDirectory(prefix=".ci-deploy-", dir=self.jar.parent) as directory:
                bundle = Path(directory) / "release.zip"
                checksum = hashlib.sha256()
                count = 0
                last_report = started
                report_progress('receiving', started, commit=commit, run_id=run_id)
                with gzip.GzipFile(fileobj=stream, mode="rb") as source, bundle.open("wb") as dest:
                    while chunk := source.read(64 * 1024):
                        count += len(chunk)
                        if count > MAX_JAR_BYTES:
                            raise ValueError("Artifact exceeds size limit")
                        checksum.update(chunk)
                        dest.write(chunk)
                        now = time.monotonic()
                        if now - last_report >= 15:
                            report_progress('receiving', started, bundle_bytes=count)
                            last_report = now
                    dest.flush()
                    os.fsync(dest.fileno())
                report_progress('received', started, bundle_bytes=count)
                if checksum.hexdigest() != digest:
                    raise ValueError("Artifact checksum mismatch")
                report_progress('checksum_verified', started)
                manifest = unpack_release(bundle, Path(directory), commit)
                report_progress('release_validated', started)
                candidate = Path(directory) / 'app.jar'
                requirements = manifest['files']['market/requirements.txt']
                runtime = self.market_root / 'runtimes' / requirements
                if (not (runtime / '.venv/bin/python').is_file()
                        or not (runtime / 'requirements.txt').is_file()
                        or hashlib.sha256((runtime / 'requirements.txt').read_bytes()).hexdigest() != requirements):
                    raise ValueError('Reviewed matching adapter runtime must be provisioned first')
                releases = self.market_root / 'releases'
                releases.mkdir(exist_ok=True, mode=0o755)
                release = releases / f'{commit}-{run_id}-{time.time_ns()}'
                shutil.copytree(Path(directory) / 'market', release)
                release.chmod(0o755)
                for file in release.iterdir():
                    file.chmod(0o644)
                (release / '.venv').symlink_to(runtime / '.venv')
                backups = self.state / "backups"
                backups.mkdir(exist_ok=True, mode=0o700)
                backup = backups / f"{run_id}-{time.time_ns()}.jar"
                shutil.copy2(self.jar, backup)
                report_progress('backup_created', started)
                try:
                    old_commit = jar_commit(backup)
                except (KeyError, ValueError, zipfile.BadZipFile):
                    old_commit = None  # First migration from a manually deployed JAR.
                candidate.chmod(0o644)
                # Write recovery information before stopping either process or replacing files.
                write_state(pending_path, {'backup': str(backup),
                    'previous_adapter': str(old_adapter), 'candidate_adapter': str(release),
                    'previous': current, 'commit': commit, 'run_id': run_id})
                swapped = False
                try:
                    swapped = True
                    report_progress('stopping_services', started)
                    self.stop()
                    self.switch_adapter(release)
                    os.replace(candidate, self.jar)
                    report_progress('starting_services', started)
                    self.restart()
                    report_progress('health_check', started)
                    self.wait_ready(commit)
                    write_state(current_path, {"commit": commit, "sha256": digest,
                                                    "run_id": run_id, "backup": str(backup),
                                                    "adapter": str(release), "previous_adapter": str(old_adapter)})
                    pending_path.unlink()
                except BaseException as error:
                    if swapped:
                        report_progress('rollback_started', started)
                        # Stop both before restoring either, including partial switch failures.
                        try:
                            self.stop()
                        except BaseException:
                            raise RuntimeError('Cannot stop services for rollback; pending.json requires operator') from error
                        rollback = Path(directory) / "rollback.jar"
                        shutil.copy2(backup, rollback)
                        os.replace(rollback, self.jar)
                        self.switch_adapter(old_adapter)
                        try:
                            self.restart()
                            self.wait_ready(old_commit, legacy_adapter=not (old_adapter / "deployment.json").exists())
                        except BaseException:
                            raise RuntimeError("Previous release pair restored but recovery health check FAILED; operator required") from error
                        if current:
                            write_state(current_path, current)
                        else:
                            current_path.unlink(missing_ok=True)
                        pending_path.unlink()
                        report_progress('rollback_complete', started)
                        raise RuntimeError("Deployment failed; rolled back to previous release pair") from error
                    raise
                report_progress('complete', started, commit=commit)
                print(json.dumps({"result": "deployed", "commit": commit, "sha256": digest}), flush=True)


def main():
    os.umask(0o077)
    # Only this root-owned file controls paths. SSH input cannot override them.
    config = json.loads(Path("/etc/family-finance/ci-deploy.json").read_text())
    commit, digest, run_id = parse_command(os.environ.get("SSH_ORIGINAL_COMMAND", ""))
    runner = Deployer(config["jar"], config["state"], config["service"], config["base_url"],
                      config["market_root"], config["market_service"], config["market_url"])
    # Disconnection must not interrupt the backup/replace/recovery sequence.
    signal.signal(signal.SIGHUP, signal.SIG_IGN)
    def interrupted(signum, frame):
        raise TimeoutError("Deployment interrupted; attempting recovery")
    signal.signal(signal.SIGTERM, interrupted)
    runner.deploy(commit, digest, run_id, sys.stdin.buffer)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Deployment rejected or failed: {error}", file=sys.stderr)
        sys.exit(1)
