#!/usr/bin/env python3
import argparse
import hashlib
import json
import mimetypes
import os
import shutil
import sys
import urllib.request
from pathlib import Path

API_BASE = "https://api.github.com"
DEFAULT_REPO = "launze/input-phone2pc"
ASSET_PATTERNS = [
    "voiceinput-android-",
    "voiceinput-desktop-",
    "voiceinput-server-",
]


def repo_api(path: str) -> str:
    return f"{API_BASE}{path}"


def request_json(url: str, token: str | None):
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "voiceinput-update-sync"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req) as resp:
        return json.load(resp)


def download_file(url: str, target: Path, token: str | None):
    headers = {"Accept": "application/octet-stream", "User-Agent": "voiceinput-update-sync"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req) as resp, open(target, "wb") as fp:
        shutil.copyfileobj(resp, fp)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def classify_asset(name: str):
    lower = name.lower()
    if lower.endswith(".apk"):
        return ("android", "universal")
    if "windows-x64" in lower:
        return ("windows", "x64")
    if "macos-arm64" in lower:
        return ("macos", "arm64")
    if "macos-x64" in lower:
        return ("macos", "x64")
    if "linux-x64" in lower:
        return ("linux", "x64")
    if "server-linux-x64" in lower:
        return ("server", "x64")
    return ("unknown", "unknown")


def load_manifest(path: Path, channel: str):
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    return {
        "channel": channel,
        "latest_version": "0.0.0",
        "minimum_supported_version": None,
        "releases": [],
    }


def upsert_release(manifest: dict, release_entry: dict):
    releases = [r for r in manifest.get("releases", []) if r.get("version") != release_entry["version"]]
    releases.insert(0, release_entry)
    manifest["releases"] = releases
    manifest["latest_version"] = release_entry["version"]
    if manifest.get("minimum_supported_version") in (None, "0.0.0"):
        manifest["minimum_supported_version"] = release_entry["version"]
    return manifest


def main():
    parser = argparse.ArgumentParser(description="Sync latest GitHub release assets into local updates directory")
    parser.add_argument("--repo", default=DEFAULT_REPO)
    parser.add_argument("--channel", default="stable")
    parser.add_argument("--updates-root", default=None)
    parser.add_argument("--github-token", default=os.getenv("GITHUB_TOKEN") or os.getenv("GH_TOKEN"))
    args = parser.parse_args()

    script_dir = Path(__file__).resolve().parent
    updates_root = Path(args.updates_root) if args.updates_root else script_dir / "updates"
    channel_dir = updates_root / args.channel
    channel_dir.mkdir(parents=True, exist_ok=True)

    release = request_json(repo_api(f"/repos/{args.repo}/releases/latest"), args.github_token)
    tag_name = release["tag_name"]
    version = tag_name[1:] if tag_name.startswith("v") else tag_name
    version_dir = channel_dir / version
    version_dir.mkdir(parents=True, exist_ok=True)

    assets = []
    for asset in release.get("assets", []):
        name = asset["name"]
        if not any(name.startswith(prefix) for prefix in ASSET_PATTERNS):
            continue
        target = version_dir / name
        if not target.exists() or target.stat().st_size != asset.get("size", 0):
            print(f"Downloading {name}...")
            download_file(asset["url"], target, args.github_token)
        platform, arch = classify_asset(name)
        assets.append(
            {
                "platform": platform,
                "arch": arch,
                "file_name": name,
                "sha256": sha256_file(target),
                "size": target.stat().st_size,
                "mime_type": mimetypes.guess_type(name)[0] or "application/octet-stream",
            }
        )

    release_entry = {
        "version": version,
        "release_notes": release.get("body", ""),
        "published_at": release.get("published_at"),
        "force_update": False,
        "assets": assets,
    }

    manifest_path = channel_dir / "manifest.json"
    manifest = load_manifest(manifest_path, args.channel)
    manifest = upsert_release(manifest, release_entry)
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"Synced release {tag_name} into {version_dir}")
    print(f"Updated manifest: {manifest_path}")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)
