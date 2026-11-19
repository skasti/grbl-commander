"""Select review captures and maintain one PR comment. Differences never gate builds."""

import argparse
import html
import json
import os
from pathlib import Path
import re
from urllib.parse import quote
from urllib.request import Request, urlopen

from PIL import Image

MARKER = "<!-- grbl-commander-ui-screenshots -->"
RUN_MARKER = re.compile(r"<!-- capture-run: (\d+) (\d+) -->")


def images(directory):
    return {p.relative_to(directory).as_posix(): p for p in directory.glob("*/*.png")}


def same_pixels(left, right):
    with Image.open(left) as a, Image.open(right) as b:
        return a.size == b.size and a.convert("RGBA").tobytes() == b.convert("RGBA").tobytes()


def select(current, baseline, changed_paths):
    """Return scenario -> reasons, all available sizes; no filename ownership guesses."""
    captures, reference = images(current), images(baseline)
    selected = {}
    for path, image in sorted(captures.items()):
        scenario, size = path.split("/")
        reasons = set()
        if path not in reference:
            reasons.add("new")
        elif not same_pixels(image, reference[path]):
            reasons.add("changed")
        ownership = current / scenario / "capture-source.txt"
        if ownership.exists() and ownership.read_text().strip() in changed_paths:
            reasons.add("test-modified")
        entry = selected.setdefault(scenario, {"reasons": set(), "sizes": []})
        entry["reasons"].update(reasons)
        entry["sizes"].append(size)
    selected = {scenario: entry for scenario, entry in selected.items() if entry["reasons"]}
    return selected, sorted(reference.keys() - captures.keys())


def render(current, baseline, changed_paths, repository, commit, pr, run):
    selected, removed = select(current, baseline, changed_paths)
    lines = [MARKER, f"<!-- capture-run: {run[0]} {run[1]} -->", "## Compose UI screenshots", "",
             "Compared with `pr-assets/main/`. Informational review images; no approval gate.", ""]
    if not images(baseline):
        lines += ["Main baseline unavailable; all available captures are shown as new.", ""]
    if not selected:
        lines += ["No visual differences or captures from modified tests.", ""]
    root = f"https://raw.githubusercontent.com/{repository}/{commit}/{pr}"
    for scenario, entry in selected.items():
        label = html.escape(scenario)
        lines += [f"### {label} — {', '.join(sorted(entry['reasons']))}", ""]
        sizes = entry["sizes"]
        default = "1024x720.png" if "1024x720.png" in sizes else sizes[0]
        def embed(size):
            return f"![{label} {size.removesuffix('.png')}]({root}/{quote(scenario)}/{quote(size)})"
        lines += [embed(default), ""]
        others = [size for size in sizes if size != default]
        if others:
            lines += ["<details><summary>Other resolutions</summary>", ""]
            for size in others:
                lines += [embed(size), ""]
            lines += ["</details>", ""]
    if removed:
        lines += ["<details><summary>Removed captures</summary>", ""]
        lines += [f"- <code>{html.escape(path)}</code>" for path in removed]
        lines += ["", "</details>", ""]
    # GitHub limits comment bodies to 65,536 characters. Keep every scenario's
    # expanded image; substitute a directory link for excessive secondary images.
    body = "\n".join(lines)
    if len(body) > 60000:
        body = re.sub(r"<details><summary>Other resolutions</summary>.*?</details>",
                      f"[Other resolutions](https://github.com/{repository}/tree/{commit}/{pr})",
                      body, flags=re.DOTALL)
    if len(body) > 60000:
        body = "\n".join(lines[:7]) + f"\nCapture set exceeds the comment limit. [Browse all captures](https://github.com/{repository}/tree/{commit}/{pr})."
    return body


class GitHub:
    def __init__(self):
        self.root = f"{os.environ.get('GITHUB_API_URL', 'https://api.github.com')}/repos/{os.environ['GITHUB_REPOSITORY']}"

    def request(self, path, method="GET", data=None):
        request = Request(self.root + path, method=method,
                          data=None if data is None else json.dumps(data).encode(),
                          headers={"Authorization": f"Bearer {os.environ['GH_TOKEN']}",
                                   "Accept": "application/vnd.github+json",
                                   "Content-Type": "application/json"})
        with urlopen(request, timeout=60) as response:
            return json.load(response)

    def pages(self, path):
        result = []
        page = 1
        while True:
            batch = self.request(f"{path}?per_page=100&page={page}")
            result.extend(batch)
            if len(batch) < 100:
                return result
            page += 1


def upsert(api, pr, body, run, head_sha):
    # Workflow-level concurrency covers publication AND commenting, including reruns.
    # The API check also excludes obsolete captures after another push to the PR.
    def current_head():
        pull = api.request(f"/pulls/{pr}")
        return pull["state"] == "open" and pull["head"]["sha"] == head_sha
    if not current_head():
        return "obsolete-head"
    comments = api.pages(f"/issues/{pr}/comments")
    matching = [c for c in comments if c.get("user", {}).get("type") == "Bot"
                and c.get("body", "").startswith(MARKER)]
    for comment in matching:
        previous = RUN_MARKER.search(comment["body"])
        if previous and tuple(map(int, previous.groups())) > run:
            return "stale"
    if not current_head():
        return "obsolete-head"
    if matching:
        if matching[0]["body"] == body:
            return "unchanged"
        api.request(f"/issues/comments/{matching[0]['id']}", "PATCH", {"body": body})
        return "updated"
    api.request(f"/issues/{pr}/comments", "POST", {"body": body})
    return "created"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--pr", type=int, required=True)
    parser.add_argument("--changed-paths", type=Path, help="Local JSON list; writes Markdown without API access")
    args = parser.parse_args()
    run = (int(os.environ["GITHUB_RUN_NUMBER"]), int(os.environ["GITHUB_RUN_ATTEMPT"]))
    current = args.assets / str(args.pr)
    if tuple(map(int, (current / ".workflow-run").read_text().split())) != run:
        raise ValueError("Asset snapshot does not belong to this capture run")
    api = None if args.changed_paths else GitHub()
    if args.changed_paths:
        changed = set(json.loads(args.changed_paths.read_text()))
    else:
        changed = {f["filename"] for f in api.pages(f"/pulls/{args.pr}/files")}
    body = render(current, args.assets / "main", changed, os.environ["GITHUB_REPOSITORY"],
                  args.commit, args.pr, run)
    if api is None:
        print(body)
    else:
        print(upsert(api, args.pr, body, run, os.environ["CAPTURE_HEAD_SHA"]))


if __name__ == "__main__":
    main()
