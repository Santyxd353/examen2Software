#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID:-$(id -u)} -ne 0 ]]; then
  echo 'Run this installer with sudo.' >&2
  exit 1
fi
if [[ $(uname -m) != x86_64 ]]; then
  echo 'This VM release requires Linux x86_64.' >&2
  exit 1
fi

# Install the official native CLI without adding a Node runtime to the VM.
python3 - <<'PY'
import base64
import hashlib
import hmac
import io
import json
import os
import pathlib
import tarfile
import urllib.parse
import urllib.request

version = '0.167.2'
target = 'x86_64-unknown-linux-musl'
metadata_url = f'https://registry.npmjs.org/@openai%2fcodex/{version}-linux-x64'
with urllib.request.urlopen(metadata_url, timeout=30) as response:
    metadata = json.load(response)
tarball_url = metadata['dist']['tarball']
if urllib.parse.urlparse(tarball_url).hostname != 'registry.npmjs.org':
    raise RuntimeError('Unexpected Codex package download host')
with urllib.request.urlopen(tarball_url, timeout=120) as response:
    package = response.read(250 * 1024 * 1024)
integrity = metadata['dist']['integrity']
algorithm, expected = integrity.split('-', 1)
if algorithm != 'sha512' or not hmac.compare_digest(hashlib.sha512(package).digest(), base64.b64decode(expected)):
    raise RuntimeError('Codex package integrity check failed')

destination = pathlib.Path(f'/opt/classforge/tools/codex-{version}')
destination.mkdir(parents=True, exist_ok=True)
prefix = f'package/vendor/{target}/'
with tarfile.open(fileobj=io.BytesIO(package), mode='r:gz') as archive:
    for entry in archive.getmembers():
        if not entry.name.startswith(prefix):
            continue
        relative = pathlib.PurePosixPath(entry.name[len(prefix):])
        if not relative.parts or relative.is_absolute() or '..' in relative.parts or entry.issym() or entry.islnk():
            raise RuntimeError('Unsafe path in Codex package')
        output = destination.joinpath(*relative.parts)
        if entry.isdir():
            output.mkdir(parents=True, exist_ok=True)
        elif entry.isfile():
            output.parent.mkdir(parents=True, exist_ok=True)
            source = archive.extractfile(entry)
            with source, output.open('wb') as file:
                while chunk := source.read(1024 * 1024):
                    file.write(chunk)
            os.chmod(output, 0o755 if entry.mode & 0o111 else 0o644)

executable = destination / 'bin/codex'
if not executable.is_file():
    raise RuntimeError('Official Codex executable missing from package')
link = pathlib.Path('/opt/classforge/bin/codex')
link.parent.mkdir(parents=True, exist_ok=True)
temporary_link = link.with_name('codex.next')
temporary_link.symlink_to(executable)
temporary_link.replace(link)
print(f'Installed official Codex CLI {version}, package integrity verified.')
PY

/opt/classforge/bin/codex --version
install -d -o classforge -g classforge -m 700 /opt/classforge/data/codex
echo 'Provision the ChatGPT login privately in /opt/classforge/data/codex/auth.json.'
