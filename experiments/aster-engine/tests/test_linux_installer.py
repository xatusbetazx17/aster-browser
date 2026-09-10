"""Exercise the shell entrypoint and integrity boundary without changing this OS.

Flatpak and HTTP are subprocess fixtures here. The Flatpak CI job separately runs
this same installer against the real package and checks replacement/profile data.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[3] / "install-linux.sh"
APP_REF = "app/io.aster.browser.EnginePreview/x86_64/preview"


@unittest.skipUnless(sys.platform.startswith("linux") and shutil.which("bash"), "Linux shell entrypoint")
class LinuxInstallerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="aster-installer-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.bundle = self.root / "download with spaces.flatpak"
        self.bundle.write_bytes(b"first verified package")
        self.sums = self.root / "SHA256SUMS.txt"
        self.manifest()
        self.logs = self.root / "calls.jsonl"
        self.state = self.root / "installed"
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
            ASTER_TEST_LOG=str(self.logs), ASTER_TEST_STATE=str(self.state),
            ASTER_TEST_BUNDLE=str(self.bundle), ASTER_TEST_SUMS=str(self.sums), TMPDIR=str(self.root))
        self.command("flatpak", '''
import json, os, pathlib, sys
args=sys.argv[1:]
with open(os.environ['ASTER_TEST_LOG'],'a') as out: out.write(json.dumps(args)+'\\n')
state=pathlib.Path(os.environ['ASTER_TEST_STATE'])
if args[0]=='install':
    if os.environ.get('ASTER_TEST_FAIL_INSTALL'): sys.exit(2)
    state.write_bytes(pathlib.Path(args[-1]).read_bytes())
elif args[0]=='uninstall': state.unlink()
elif args[0] in ('info','run') and not state.exists(): sys.exit(1)
''')
        self.command("curl", '''
import os,pathlib,sys
args=sys.argv[1:]
assert args[args.index('--proto')+1]=='=https'
assert args[args.index('--proto-redir')+1]=='=https'
assert args[-1].startswith('https://github.com/xatusbetazx17/aster-browser/releases/download/codex-preview/')
if os.environ.get('ASTER_TEST_FAIL_DOWNLOAD'): sys.exit(22)
source='ASTER_TEST_SUMS' if args[-1].endswith('SHA256SUMS.txt') else 'ASTER_TEST_BUNDLE'
pathlib.Path(args[args.index('--output')+1]).write_bytes(pathlib.Path(os.environ[source]).read_bytes())
''')

    def command(self, name, body):
        path = self.bin / name
        path.write_text('#!' + sys.executable + '\n' + body)
        path.chmod(0o755)

    def manifest(self):
        self.sums.write_text(hashlib.sha256(self.bundle.read_bytes()).hexdigest() + '  aster-linux-x64.flatpak\n')

    def run_script(self, *args, success=True):
        result = subprocess.run(['bash', str(SCRIPT), *map(str, args)], env=self.env,
            text=True, capture_output=True, timeout=10)
        self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
        self.assertFalse(list(self.root.glob('aster-install.*')), 'Temporary package retained')
        return result

    def local(self, *args, **kwargs):
        return self.run_script('--bundle', self.bundle, '--checksums', self.sums, *args, **kwargs)

    def calls(self):
        return [json.loads(line) for line in self.logs.read_text().splitlines()] if self.logs.exists() else []

    def test_install_replace_and_launch_exact_user_preview(self):
        self.local('-y', '--run')
        self.assertEqual(self.state.read_bytes(), self.bundle.read_bytes())
        self.assertEqual(self.calls()[0][:-1], ['install', '--user', '--or-update', '-y', '--noninteractive'])
        self.assertEqual(self.calls()[-1], ['run', '--user', APP_REF])
        self.bundle.write_bytes(b'newer verified package')
        self.manifest()
        self.local()
        self.assertEqual(self.state.read_bytes(), b'newer verified package')

    def test_corruption_never_reaches_flatpak(self):
        self.bundle.write_bytes(b'changed after publication')
        self.assertIn('Checksum mismatch', self.local(success=False).stderr)
        self.assertEqual(self.calls(), [])

    def test_missing_ambiguous_or_malformed_checksum_is_rejected(self):
        valid = self.sums.read_text()
        for contents in ('', valid * 2, 'not-a-sha  aster-linux-x64.flatpak\n', valid.replace('aster-linux-x64', 'other')):
            with self.subTest(contents=contents):
                self.sums.write_text(contents)
                self.local(success=False)
                self.assertEqual(self.calls(), [])

    def test_published_download_is_verified(self):
        self.run_script('-y')
        self.assertEqual(self.state.read_bytes(), self.bundle.read_bytes())
        self.assertEqual(self.calls()[-1], ['info', '--user', APP_REF])

    def test_failed_download_does_not_replace_or_launch(self):
        self.env['ASTER_TEST_FAIL_DOWNLOAD'] = '1'
        self.run_script('--run', success=False)
        self.assertEqual(self.calls(), [])

    def test_failed_install_does_not_launch(self):
        self.env['ASTER_TEST_FAIL_INSTALL'] = '1'
        self.local('--run', success=False)
        self.assertEqual(len(self.calls()), 1)
        self.assertEqual(self.calls()[0][0], 'install')

    def test_check_and_uninstall_need_no_download(self):
        self.env['ASTER_TEST_FAIL_DOWNLOAD'] = '1'
        self.assertIn('not installed', self.run_script('--check').stdout)
        self.state.write_bytes(b'installed package')
        self.run_script('--uninstall', '-y')
        self.assertEqual(self.calls()[-1], ['uninstall', '--user', '-y', '--noninteractive', APP_REF])
        self.assertFalse(self.state.exists())
        self.assertNotIn('--delete-data', sum(self.calls(), []))

    def test_invalid_options_fail_before_side_effects(self):
        for args in [('--bundle',), ('--bundle', self.bundle), ('--checksums', self.sums),
                     ('--uninstall', '--run'), ('--check', '--uninstall'), ('--with-drm-capsule',)]:
            with self.subTest(args=args):
                self.run_script(*args, success=False)
                self.assertEqual(self.calls(), [])

    def test_unsupported_architecture_is_not_installed(self):
        self.command('uname', "import sys\nprint('Linux' if sys.argv[1]=='-s' else 'aarch64')\n")
        self.assertIn('Linux x86_64', self.local(success=False).stderr)
        self.assertEqual(self.calls(), [])


if __name__ == '__main__':
    unittest.main()
