"""Deployment safety tests: real archives/files; only service operations are faked."""
import gzip
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "ci_deploy.py"
spec = importlib.util.spec_from_file_location("ci_deploy", SCRIPT)
deploy = importlib.util.module_from_spec(spec) if SCRIPT.exists() else None
if deploy:
    spec.loader.exec_module(deploy)

SHA = "a" * 40


def artifact(commit=SHA):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as jar:
        jar.writestr("META-INF/MANIFEST.MF", "Main-Class: org.springframework.boot.loader.launch.JarLauncher\n")
        jar.writestr("BOOT-INF/classes/static/index.html", "<html>new version</html>")
        jar.writestr("BOOT-INF/classes/static/deployment.json", json.dumps({"commit": commit}))
    raw = buffer.getvalue()
    return raw, gzip.compress(raw), hashlib.sha256(raw).hexdigest()


class DeployTest(unittest.TestCase):
    def setUp(self):
        from test_release_bundle import BundleTest
        BundleTest.setUp(self)
        process = patch.object(deploy.subprocess, 'run')
        process.start()
        self.addCleanup(process.stop)

    def run_deploy(self, checksum=None, commit=SHA, run_id=100):
        from test_release_bundle import bundle
        compressed, digest = bundle(commit=commit)
        with zipfile.ZipFile(io.BytesIO(gzip.decompress(compressed))) as release:
            uploaded_jar = release.read("app.jar")
        self.runner.deploy(SHA, checksum or digest, run_id, io.BytesIO(compressed))
        return uploaded_jar

    def test_success_installs_exact_bytes_and_keeps_backup(self):
        with patch.object(self.runner, "restart"), patch.object(self.runner, "wait_ready"):
            raw = self.run_deploy()
        self.assertEqual(self.jar.read_bytes(), raw)
        self.assertEqual(next((self.root / "state/backups").glob("*.jar")).read_bytes(), b"old jar")
        self.assertEqual(json.loads((self.root / "state/current.json").read_text())["commit"], SHA)

    def test_reports_receive_and_switch_stages_without_changing_stdout_contract(self):
        logs, output = io.StringIO(), io.StringIO()
        with contextlib.redirect_stderr(logs), contextlib.redirect_stdout(output), patch.object(self.runner, 'restart'), patch.object(self.runner, 'wait_ready'):
            self.run_deploy()
        events = [json.loads(line.removeprefix('[deploy] ')) for line in logs.getvalue().splitlines()]
        stages = [event['stage'] for event in events]
        self.assertLess(stages.index('receiving'), stages.index('received'))
        self.assertLess(stages.index('checksum_verified'), stages.index('stopping_services'))
        self.assertLess(stages.index('health_check'), stages.index('complete'))
        self.assertGreater(next(e['bundle_bytes'] for e in events if e['stage']=='received'), 0)
        self.assertEqual(json.loads(output.getvalue())['commit'], SHA)

    def test_broken_progress_sink_does_not_prevent_recovery(self):
        with patch.object(deploy.sys.stderr, 'write', side_effect=OSError('closed log')), patch.object(self.runner, 'restart'), patch.object(self.runner, 'wait_ready', side_effect=[RuntimeError('unhealthy'), None]):
            with self.assertRaisesRegex(RuntimeError, 'rolled back'):
                self.run_deploy()
        self.assertEqual(self.jar.read_bytes(), b'old jar')

    def test_progress_does_not_swallow_timeout_recovery_signal(self):
        with patch.object(deploy.sys.stderr, 'write', side_effect=TimeoutError('interrupted')):
            with self.assertRaises(TimeoutError):
                deploy.report_progress('receiving', 0)

    def test_exact_bytes_check_survives_zip_timestamp_change(self):
        # A deploy can cross ZIP's two-second timestamp boundary. Compare the
        # installed JAR with the uploaded JAR, not a newly generated fixture.
        with patch("zipfile.time.localtime", return_value=(2026, 9, 8, 12, 0, 0, 1, 251, 0)) as clock:
            def advance_clock():
                clock.return_value = (2026, 9, 8, 12, 0, 2, 1, 251, 0)

            with patch.object(self.runner, "restart", side_effect=advance_clock), patch.object(self.runner, "wait_ready"):
                raw = self.run_deploy()
        self.assertEqual(self.jar.read_bytes(), raw)

    def test_bad_checksum_leaves_current_jar_untouched(self):
        with self.assertRaisesRegex(ValueError, "checksum"):
            self.run_deploy(checksum="0" * 64)
        self.assertEqual(self.jar.read_bytes(), b"old jar")

    def test_wrong_commit_in_jar_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "commit"):
            self.run_deploy(commit="b" * 40)
        self.assertEqual(self.jar.read_bytes(), b"old jar")

    def test_unhealthy_new_version_restores_old_jar_and_metadata(self):
        with patch.object(self.runner, "restart"), patch.object(self.runner, "wait_ready", side_effect=[RuntimeError("not ready"), None]):
            with self.assertRaisesRegex(RuntimeError, "rolled back"):
                self.run_deploy()
        self.assertEqual(self.jar.read_bytes(), b"old jar")
        self.assertFalse((self.root / "state/current.json").exists())

    def test_restart_failure_also_restores_old_jar(self):
        with patch.object(self.runner, "restart", side_effect=[RuntimeError("restart failed"), None]), patch.object(self.runner, "wait_ready"):
            with self.assertRaisesRegex(RuntimeError, "rolled back"):
                self.run_deploy()
        self.assertEqual(self.jar.read_bytes(), b"old jar")

    def test_older_run_cannot_overwrite_newer_success(self):
        with patch.object(self.runner, "restart"), patch.object(self.runner, "wait_ready"):
            raw = self.run_deploy(run_id=101)
            with self.assertRaisesRegex(ValueError, "older"):
                self.run_deploy(run_id=100)
        self.assertEqual(self.jar.read_bytes(), raw)

    def test_command_injection_and_invalid_commands_are_rejected(self):
        for command in ["bash", "status; id", f"deploy {SHA} {'0' * 64} 1;id", "deploy x y 1"]:
            with self.subTest(command=command), self.assertRaises(ValueError):
                deploy.parse_command(command)

    def test_valid_command_is_parsed_without_shell(self):
        self.assertEqual(deploy.parse_command(f"deploy {SHA} {'0' * 64} 12"), (SHA, "0" * 64, 12))


if __name__ == "__main__":
    unittest.main()
