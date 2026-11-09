#!/usr/bin/env python3
"""Calculate GRBL Commander release versions from semantic Git tags and PR labels."""

import argparse
import json
import os
import re
import subprocess
from collections.abc import Iterable
from pathlib import Path

SEMVER_TAG = re.compile(r"^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")
BASE_VERSION = (0, 1, 0)


def bump_type(labels: Iterable[str]) -> str:
    names = set(labels)
    if "major" in names and "minor" in names:
        raise ValueError("A pull request cannot have both 'major' and 'minor' labels.")
    if "major" in names:
        return "major"
    if "minor" in names:
        return "minor"
    return "patch"


def next_version(tags: Iterable[str], labels: Iterable[str]) -> str:
    kind = bump_type(labels)
    versions = [
        tuple(map(int, match.groups()))
        for tag in tags
        if (match := SEMVER_TAG.fullmatch(tag.strip())) is not None
    ]
    major, minor, patch = max(versions, default=BASE_VERSION)
    if kind == "major":
        major, minor, patch = major + 1, 0, 0
    elif kind == "minor":
        minor, patch = minor + 1, 0
    else:
        patch += 1
    return f"{major}.{minor}.{patch}"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate", "next"))
    parser.add_argument(
        "--labels-json",
        default=os.environ.get("PR_LABELS", "[]"),
        help="JSON array of pull-request label names (defaults to PR_LABELS)",
    )
    args = parser.parse_args()
    try:
        labels = json.loads(args.labels_json)
        if not isinstance(labels, list) or any(not isinstance(label, str) for label in labels):
            raise ValueError("PR labels must be a JSON array of strings.")
        kind = bump_type(labels)
        if args.command == "validate":
            print(f"Release label validation passed: {kind} bump.")
            return

        tags = subprocess.check_output(["git", "tag", "--list", "v*"], text=True).splitlines()
        version = next_version(tags, labels)
        output_path = os.environ.get("GITHUB_OUTPUT")
        if output_path:
            with Path(output_path).open("a", encoding="utf-8") as output:
                output.write(f"version={version}\ntag=v{version}\n")
        print(f"Next release: v{version} ({kind} bump).")
    except (ValueError, subprocess.CalledProcessError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
