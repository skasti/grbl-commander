#!/usr/bin/env python3
"""Validate and flatten native installers downloaded from GitHub Actions artifacts."""

import argparse
import shutil
from pathlib import Path

REQUIRED_FORMATS = frozenset({".deb", ".rpm", ".msi", ".dmg"})


def stage_release_assets(source: Path, destination: Path) -> list[Path]:
    """Copy one or more native packages per format from nested artifacts into one directory.

    Linux's multi-path upload keeps "deb/" and "rpm/" prefixes, while Windows
    and macOS uploads have their installers at the artifact root.
    """
    if not source.is_dir():
        raise ValueError(f"Artifact directory not found: {source}")

    installers = sorted(
        (path for path in source.rglob("*")
         if path.is_file() and path.suffix.lower() in REQUIRED_FORMATS),
        key=str,
    )
    present = {path.suffix.lower() for path in installers}
    missing = REQUIRED_FORMATS - present
    if missing:
        raise ValueError(f"Missing installer format(s): {', '.join(sorted(missing))}")

    names = [path.name for path in installers]
    if len(names) != len(set(names)):
        raise ValueError("Installer filenames must be unique across all downloaded artifacts")

    destination.mkdir(parents=True, exist_ok=True)
    staged = []
    for path in installers:
        output = destination / path.name
        if output.exists():
            raise ValueError(f"Installer already exists in staging directory: {output}")
        shutil.copy2(path, output)
        print(f"Staged {path} -> {output}")
        staged.append(output)
    return staged


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    try:
        staged = stage_release_assets(args.source, args.destination)
    except ValueError as error:
        parser.error(str(error))
    print(f"Ready to publish {len(staged)} installers.")


if __name__ == "__main__":
    main()
