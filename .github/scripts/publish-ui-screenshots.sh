#!/usr/bin/env bash
set -euo pipefail

remote=${1:?expected Git remote URL}
pr_number=${2:?expected pull request number}
source_dir=$(cd "${3:?expected screenshot directory}" && pwd)
run_number=${GITHUB_RUN_NUMBER:?expected workflow run number}
run_attempt=${GITHUB_RUN_ATTEMPT:?expected workflow run attempt}
branch=refs/heads/pr-assets

is_number() { [[ $1 =~ ^[1-9][0-9]*$ ]]; }
is_number "$pr_number" && is_number "$run_number" && is_number "$run_attempt" || {
    echo "Invalid pull request or workflow run identity" >&2
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

        marker="$pr_number/.workflow-run"
        if [[ -f $marker ]] && read -r previous_run previous_attempt < "$marker" &&
            is_number "$previous_run" && is_number "$previous_attempt" &&
            (( previous_run > run_number || (previous_run == run_number && previous_attempt > run_attempt) )); then
            echo "PR #$pr_number already has screenshots from a newer workflow run."
            exit 0
        fi

        rm -rf -- "$pr_number"
        mkdir -- "$pr_number"
        cp -R -- "$source_dir/." "$pr_number/"
        printf '%s %s\n' "$run_number" "$run_attempt" > "$marker"
        git add -- "$pr_number"
        if git diff --cached --quiet; then
            echo "PR #$pr_number screenshots are already published."
            exit 0
        fi
        git -c user.name='github-actions[bot]'             -c user.email='41898282+github-actions[bot]@users.noreply.github.com'             commit --quiet -m "Publish UI screenshots for PR #$pr_number"
        # A concurrent push rejects this update. Retry with a fresh branch tip,
        # preserving other PR directories and checking same-PR run ordering again.
        git push "$remote" "HEAD:$branch" || exit 75
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
