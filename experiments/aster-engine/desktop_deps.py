"""Pinned, hash-verified standalone components. This downloads no browser engine."""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import urllib.error
import time

ROOT = Path(__file__).resolve().parent
BUILD = ROOT / 'build'


def checked_download(url, target, digest):
    if target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest() == digest:
        return
    for attempt in range(2):
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                data = response.read(40 * 1024 * 1024 + 1)
            break
        except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
            if attempt or isinstance(error, urllib.error.HTTPError) and error.code not in {408, 429, 500, 502, 503, 504}:
                raise
            print(f'Transient download failure; retrying {target.name} once', flush=True)
            time.sleep(1)
    # A checksum mismatch is a hard failure, never retried or bypassed.
    if len(data) > 40 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != digest:
        raise RuntimeError(f'Dependency checksum mismatch: {target.name}')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)


def script_host():
    native = ROOT / 'desktop/native'
    lock = json.loads((native / 'quickjs.lock.json').read_text())
    sources = BUILD / 'deps/quickjs'
    def fetch(item):
        name, digest = item
        checked_download(f'https://raw.githubusercontent.com/quickjs-ng/quickjs/{lock["commit"]}/{name}', sources / name, digest)
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        list(pool.map(fetch, lock['files'].items()))
    output = BUILD / 'jar/native'
    output.mkdir(parents=True, exist_ok=True)
    suffix = '.exe' if os.name == 'nt' else ''
    target = output / ('aster-script-host' + suffix)
    fingerprint = hashlib.sha256((native/'host.c').read_bytes()+(native/'CMakeLists.txt').read_bytes()+(native/'quickjs.lock.json').read_bytes()).hexdigest()
    stamp=output/'build-source.sha256'
    if target.is_file() and stamp.is_file() and stamp.read_text()==fingerprint:
        return target
    if os.name == 'nt':
        intermediate = BUILD / 'script-host-build'
        subprocess.run(['cmake', '-S', str(native), '-B', str(intermediate), '-A', 'x64', f'-DQJS_SOURCE={sources}'], check=True)
        subprocess.run(['cmake', '--build', str(intermediate), '--config', 'Release', '--parallel', '2'], check=True)
        shutil.copyfile(intermediate / 'Release/aster-script-host.exe', target)
    else:
        subprocess.run([os.environ.get('CC', 'cc'), '-O2', '-std=c11', '-D_GNU_SOURCE', '-DQUICKJS_NG_BUILD', '-I', str(sources), str(native / 'host.c'),
            *[str(sources / name) for name in ['quickjs.c', 'libregexp.c', 'libunicode.c', 'dtoa.c']], '-lm', '-lpthread', '-ldl', '-o', str(target)], check=True)
    shutil.copyfile(sources / 'LICENSE', output / 'QuickJS-LICENSE.txt')
    stamp.write_text(fingerprint)
    print(f'Built standalone QuickJS {lock["version"]} host: {target}')
    return target


def javafx():
    platform = 'win' if os.name == 'nt' else 'linux' if sys.platform.startswith('linux') else None
    import platform as machine
    if platform is None or machine.machine().lower() not in {'x86_64','amd64'}:
        raise RuntimeError('Desktop scripting/media packages currently target Linux/Windows x64; Android core is built separately')
    lock = json.loads((ROOT/'desktop/native/javafx.lock.json').read_text())
    artifacts = [item for item in lock['artifacts'] if item['platform']==platform]
    def fetch(item):
        target=BUILD/'deps/javafx'/item['name']; checked_download(item['url'],target,item['sha256'])
        output=BUILD/'jar/lib'/('javafx-'+item['module']+'.jar');output.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(target,output)
        return output
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        libraries=list(pool.map(fetch,artifacts))
    def notice(item):
        cached=BUILD/'deps/javafx-legal'/item['name'];checked_download(item['url'],cached,item['sha256'])
        output=BUILD/'jar/legal/javafx'/item['name'];output.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(cached,output)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(notice,lock['notices']))
    (BUILD/'jar/legal/javafx/SOURCE.txt').write_text('OpenJFX '+lock['version']+'\nUpstream source: '+lock['source_url']+'\nExact-version Java source artifacts:\n'+'\n'.join(lock['java_sources'])+'\nMaven artifacts and SHA-256 pins are in desktop/native/javafx.lock.json.\nOnly base, graphics, media and Swing components are included; javafx.web is absent.\n')
    return os.pathsep.join(map(str,libraries))
