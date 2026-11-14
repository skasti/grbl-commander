#!/usr/bin/env bash
set -euo pipefail

# Pin the upstream driver and its recorded core/SD-card submodule revisions.
revision=69ca5198e1697c026049ee74c7d6a124f6d34846
repository=https://github.com/grblHAL/Simulator.git
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
workspace=${1:-"$root/build/grblhal-simulator"}
mkdir -p "$workspace"
workspace=$(cd "$workspace" && pwd)
source_dir="$workspace/source"
build_dir="$workspace/build"

if [[ ! -d "$source_dir/.git" ]]; then
    git clone --no-checkout "$repository" "$source_dir"
fi
git -C "$source_dir" fetch "$repository" "$revision"
git -C "$source_dir" checkout --detach "$revision"
git -C "$source_dir" submodule update --init --recursive
cmake -S "$source_dir" -B "$build_dir" -DCMAKE_BUILD_TYPE=Release
cmake --build "$build_dir" --target grblHAL_sim --parallel 2
printf '\nSimulator binary: %s/grblHAL_sim\n' "$build_dir"
