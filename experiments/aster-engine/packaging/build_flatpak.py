#!/usr/bin/env python3
"""Package the native Linux bundle against a shared Freedesktop runtime.

Requires the matching Freedesktop Platform and SDK installed for this user.
This packages Aster's own renderer; it does not use a browser engine runtime.
"""
from pathlib import Path
import hashlib
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / 'build'
APP_ID = 'io.aster.browser.EnginePreview'
RUNTIME = '25.08'


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def main():
    source = BUILD / 'native/AsterEnginePreview'
    if not (source / 'bin/AsterEnginePreview').is_file():
        raise SystemExit('Build the Linux native package with build.py desktop --test --package first.')
    output = BUILD / 'flatpak-app'
    if output.exists():
        shutil.rmtree(output)
    run('flatpak', 'build-init', '--arch=x86_64', output, APP_ID,
        'org.freedesktop.Sdk', 'org.freedesktop.Platform', RUNTIME)
    prefix = output / 'files'
    shutil.copytree(source, prefix / 'aster')
    command = prefix / 'bin/aster'
    command.parent.mkdir(parents=True, exist_ok=True)
    command.write_text('#!/bin/sh\nexec /app/aster/bin/AsterEnginePreview "$@"\n')
    command.chmod(0o755)
    desktop = prefix / f'share/applications/{APP_ID}.desktop'
    desktop.parent.mkdir(parents=True, exist_ok=True)
    desktop.write_text('[Desktop Entry]\nType=Application\nName=Aster\nComment=Independent browsing and reading preview\nExec=aster\nIcon=' + APP_ID + '\nTerminal=false\nCategories=Network;WebBrowser;\n')
    icon = prefix / f'share/icons/hicolor/scalable/apps/{APP_ID}.svg'
    icon.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / 'packaging/aster.svg', icon)
    run('flatpak', 'build-finish', '--command=aster', '--share=network', '--share=ipc',
        '--socket=x11', '--socket=pulseaudio', '--device=dri',
        '--filesystem=xdg-download', '--filesystem=xdg-documents:ro', output)
    repository = BUILD / 'flatpak-repository'
    run('flatpak', 'build-export', repository, output, 'preview')
    bundle = BUILD / 'aster-linux-x64.flatpak'
    run('flatpak', 'build-bundle', repository, bundle, APP_ID, 'preview',
        '--runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo')
    (BUILD / 'FLATPAK-SHA256SUMS.txt').write_text(hashlib.sha256(bundle.read_bytes()).hexdigest() + '  ' + bundle.name + '\n')
    print(f'Flatpak bundle: {bundle}')


if __name__ == '__main__':
    main()
