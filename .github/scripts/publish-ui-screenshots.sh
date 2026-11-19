#!/usr/bin/env bash
set -euo pipefail

remote=${1:?expected Git remote URL}
target=${2:?expected PR number or main}
source_dir=$(cd "${3:?expected screenshot directory}" && pwd)
run_number=${GITHUB_RUN_NUMBER:?expected workflow run number}
run_attempt=${GITHUB_RUN_ATTEMPT:?expected workflow run attempt}
branch=refs/heads/pr-assets

is_number() { [[ $1 =~ ^[1-9][0-9]*$ ]]; }
[[ $target == main ]] || is_number "$target" || { echo "Invalid screenshot target" >&2; exit 1; }
is_number "$run_number" && is_number "$run_attempt" || {
    echo "Invalid workflow run identity" >&2
    exit 1
}
if [[ -z $(find "$source_dir" -type f -name '*.png' -print -quit) ]]; then
    echo "The screenshot artifact contains no PNG files" >&2
    exit 1
fi

temporary=$(mktemp -d)
trap 'rm -rf -- "$temporary"' EXIT

for attempt in {1..6}; do
    checkout="$temporary/$attempt"
    git init --quiet "$checkout"
    set +e
    (
        set -e
        cd "$checkout"
        if git ls-remote --exit-code --refs "$remote" "$branch" >/dev/null; then
            git fetch --quiet --depth=1 "$remote" "$branch"
            git checkout --quiet --detach FETCH_HEAD
        else
            result=$?
            # git ls-remote uses 2 for a valid remote with no matching branch.
            if [[ $result != 2 ]]; then
                exit "$result"
            fi
        fi

        marker="$target/.workflow-run"
        if [[ -f $marker ]] && read -r previous_run previous_attempt < "$marker" &&
            is_number "$previous_run" && is_number "$previous_attempt" &&
            (( previous_run > run_number || (previous_run == run_number && previous_attempt > run_attempt) )); then
            echo "$target already has screenshots from a newer workflow run."
            [[ -z ${GITHUB_OUTPUT:-} ]] || echo "published=false" >> "$GITHUB_OUTPUT"
            exit 0
        fi

        rm -rf -- "$target"
        mkdir -- "$target"
        cp -R -- "$source_dir/." "$target/"
        printf '%s %s\n' "$run_number" "$run_attempt" > "$marker"
        git add -- "$target"
        if git diff --cached --quiet; then
            echo "$target screenshots are already published."
            if [[ -n ${GITHUB_OUTPUT:-} ]]; then
                printf 'published=true\ncommit=%s\n' "$(git rev-parse HEAD)" >> "$GITHUB_OUTPUT"
            fi
            exit 0
        fi
        git -c user.name='github-actions[bot]'             -c user.email='41898282+github-actions[bot]@users.noreply.github.com'             commit --quiet -m "Publish UI screenshots for $target"
        # A concurrent push rejects this update. Retry with a fresh branch tip,
        # preserving other PR directories and checking same-PR run ordering again.
        git push "$remote" "HEAD:$branch" || exit 75
        if [[ -n ${GITHUB_OUTPUT:-} ]]; then
            printf 'published=true\ncommit=%s\n' "$(git rev-parse HEAD)" >> "$GITHUB_OUTPUT"
        fi
    )
    result=$?
    set -e
    if [[ $result != 75 ]]; then
        exit "$result"
    fi
    if (( attempt < 6 )); then
        sleep "$attempt"
    fi
done

echo "Unable to update the pr-assets branch after six attempts" >&2
exit 1
