"""Fetch the published branch into a disposable directory, with no OS changes."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile

spec = importlib.util.spec_from_file_location("aster_setup", Path(__file__).resolve().parents[1] / "setup.py")
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)

with tempfile.TemporaryDirectory(prefix="aster-setup-live-") as temp:
    root = Path(temp) / "Aster install with spaces"
    source = setup.Source()
    revision = source.head()
    source.head = lambda: revision
    first = setup.install(root, "firefox", "native", source)
    setup.verify_release(root / "releases" / first["commit"])
    second = setup.install(root, "firefox", "native", source)
    assert first["changed"] and not second["changed"]
    subprocess.run([sys.executable, str(root / "start-aster.py"), "--update", "--check"], check=True)
    print("PASS: real GitHub download, verified release, repeat update, generated launcher and path containing spaces")
