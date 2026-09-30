#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Measure build resources. No firmware download, compilation or phone access."""
import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess

GIB = 1024 ** 3
# Practical allowance for sources and output, not an OrangeFox guarantee.
RECOMMENDED_FREE_GIB = 100
CLEANUP_TARGETS = (
    "/usr/local/lib/android",
    "/usr/share/dotnet",
    "/opt/ghc",
    "/usr/local/.ghcup",
)

def memory():
    data = {}
    source = Path("/proc/meminfo")
    if source.exists():
        for line in source.read_text().splitlines():
            key, value = line.split(":", 1)
            data[key] = int(value.strip().split()[0]) * 1024
    return {key: data.get(key) for key in ("MemTotal", "MemAvailable", "SwapTotal")}

def disk(path):
    info = shutil.disk_usage(path)
    return {"path": str(path), "total_bytes": info.total, "used_bytes": info.used,
            "free_bytes": info.free, "device_id": os.stat(path).st_dev}

def measure():
    return {
        "cpu_count": os.cpu_count(), "architecture": platform.machine(),
        "system": platform.system(), "memory_bytes": memory(),
        "workspace": disk(Path.cwd()),
        "mnt": disk(Path("/mnt")) if Path("/mnt").exists() else None,
        "runner": {key: os.environ.get(key) for key in
                   ("GITHUB_ACTIONS", "RUNNER_ENVIRONMENT", "RUNNER_OS", "RUNNER_ARCH", "ImageOS", "ImageVersion")},
        "tools": {name: shutil.which(name) for name in ("git", "python3", "java", "make", "gcc", "g++", "curl")},
    }

def cleanup():
    # Never run this cleanup on a user's computer or a self-hosted runner.
    if not (os.environ.get("GITHUB_ACTIONS") == "true"
            and os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted"
            and platform.system() == "Linux"):
        raise RuntimeError("Cleanup is restricted to disposable GitHub-hosted Linux runners")
    removed = []
    for target in CLEANUP_TARGETS:
        if Path(target).exists():
            subprocess.run(["sudo", "rm", "-rf", "--", target], check=True)
            removed.append(target)
    return removed

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cleanup", action="store_true")
    args = parser.parse_args()
    before = measure()
    removed = cleanup() if args.cleanup else []
    after = measure()
    free_gib = after["workspace"]["free_bytes"] / GIB
    ram = after["memory_bytes"]["MemTotal"]
    report = {"before": before, "after": after, "removed_unused_sdks": removed,
              "recommended_free_gib": RECOMMENDED_FREE_GIB,
              "workspace_meets_recommended_space": free_gib >= RECOMMENDED_FREE_GIB,
              "compilation_attempted": False, "recovery_image_created": False,
              "note": "Resource check only. Source/branch compatibility and decryption are not validated."}
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "resources.json").write_text(json.dumps(report, indent=2) + "\n")
    summary = ("## Neo8 build resource check\n\n"
               f"- CPU: {after['cpu_count']} cores; architecture: {after['architecture']}\n"
               f"- RAM: {ram / GIB:.1f} GiB\n" if ram is not None else
               "## Neo8 build resource check\n\n- RAM could not be measured\n")
    summary += (f"- Free workspace disk: {free_gib:.1f} GiB\n"
                f"- Practical space allowance: {RECOMMENDED_FREE_GIB} GiB\n"
                f"- Meets this allowance: {report['workspace_meets_recommended_space']}\n\n"
                "This job does **not** compile recovery or create an image.\n"
                "A successful job only means the resource report was generated.\n"
                "Disk space on `/mnt` must not be added to workspace space without checking its device ID and layout.\n")
    (args.output / "summary.md").write_text(summary)
    print(summary)

if __name__ == "__main__":
    main()

