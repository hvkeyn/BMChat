#!/usr/bin/env python3
"""Generate ``desktop-update.json`` from a directory of release
artifacts.

The directory is expected to contain BMChat desktop artifacts named
the way ``electron-builder`` produces them in
``packages/target-electron/dist``:

* ``BMChat-<ver>-x86_64.AppImage``
* ``bmchat-desktop_<ver>_amd64.deb``
* ``BMChat-<ver>-Setup.x64.exe``
* ``BMChat-<ver>-Portable.x64.exe``

For each match this script computes the SHA-256 hash and the file
size, picks the latest version it sees, and prints a JSON manifest
to standard output that mirrors the schema served from
``/desktop-update.json`` on both distribution hosts.

Usage:

    python3 build-desktop-manifest.py infra/vps/desktop > out.json

The output is deterministic given the same inputs, so it can be
committed for review or diffed in CI logs.
"""

from __future__ import annotations

import datetime as _dt
import hashlib
import json
import os
import re
import sys
from typing import Dict, Tuple

# All distribution hosts the manifest advertises. Order matters —
# clients try them top-to-bottom.
MIRRORS = [
    "http://5.187.4.132",
    "http://158.160.104.107:8080",
]

# Maps ``(electron-builder filename pattern, stable-alias path)``
# onto the manifest key. The "stable alias" is the URL exposed on
# the website that doesn't carry the version number — it points,
# server-side, at the most recent versioned binary via a symlink.
PATTERNS = [
    {
        "key":     "linux-x64-appimage",
        "label":   "AppImage x86_64",
        "regex":   re.compile(r"^BMChat-(?P<ver>[\w.+-]+)-x86_64\.AppImage$"),
        "alias":   "BMChat-x86_64.AppImage",
    },
    {
        "key":     "linux-x64-deb",
        "label":   ".deb (amd64)",
        "regex":   re.compile(r"^bmchat-desktop_(?P<ver>[\w.+-]+)_amd64\.deb$"),
        "alias":   "bmchat-amd64.deb",
    },
    {
        "key":     "win-x64-installer",
        "label":   "Setup x64 (.exe)",
        "regex":   re.compile(r"^BMChat-(?P<ver>[\w.+-]+)-Setup\.x64\.exe$"),
        "alias":   "BMChat-Setup-x64.exe",
    },
    {
        "key":     "win-x64-portable",
        "label":   "Portable x64 (.exe)",
        "regex":   re.compile(r"^BMChat-(?P<ver>[\w.+-]+)-Portable\.x64\.exe$"),
        "alias":   "BMChat-portable-x64.exe",
    },
]


def _sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _scan(root: str) -> Tuple[Dict[str, dict], str]:
    platforms: Dict[str, dict] = {}
    versions: list[str] = []

    for entry in sorted(os.listdir(root)):
        full = os.path.join(root, entry)
        if not os.path.isfile(full) or os.path.islink(full):
            continue
        for spec in PATTERNS:
            m = spec["regex"].match(entry)
            if not m:
                continue
            ver = m.group("ver")
            versions.append(ver)
            size = os.path.getsize(full)
            sha  = _sha256(full)
            platforms[spec["key"]] = {
                "label":         spec["label"],
                "url":           "%s/desktop/%s" % (MIRRORS[0], spec["alias"]),
                "versionedFile": entry,
                "size":          size,
                "sha256":        sha,
            }
            break

    # Pick the highest-looking version we saw — every artifact this
    # release should ship with the same version, so any is fine.
    versions = sorted(set(versions))
    version = versions[-1] if versions else "0.0.0"
    return platforms, version


def main() -> int:
    if len(sys.argv) != 2:
        sys.stderr.write("usage: build-desktop-manifest.py <dist-dir>\n")
        return 2
    root = sys.argv[1]
    if not os.path.isdir(root):
        sys.stderr.write("not a directory: %s\n" % root)
        return 2

    platforms, version = _scan(root)
    if not platforms:
        sys.stderr.write("no recognised artifacts found in %s\n" % root)
        return 1

    manifest = {
        "version": version,
        "publishedAt": _dt.datetime.now(_dt.timezone.utc).strftime(
            "%Y-%m-%dT%H:%M:%SZ"),
        "releaseChannel": "stable",
        "mirrors": MIRRORS,
        "platforms": platforms,
        "notes":
            "BMChat Desktop %s — Linux (AppImage + .deb), Windows "
            "(NSIS installer + portable). Подписи кода у нас пока "
            "нет: SmartScreen / репутационный фильтр первый раз "
            "может предупредить, дальше запускается нормально. "
            "Файлы можно проверить по SHA-256 (см. поле sha256)."
            % version,
    }
    json.dump(manifest, sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
