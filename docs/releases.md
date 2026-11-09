# Versions and releases

GRBL Commander follows semantic versioning with an initial development baseline of **v0.1.0**. The Gradle property `appVersion` in the root `gradle.properties` defines the baseline version for local builds.

## Version selection

Every pull request merged into `main` triggers the Release workflow. The workflow selects the highest valid `vMAJOR.MINOR.PATCH` Git tag and calculates the next version using the labels on the merged pull request:

| PR labels | Version change | Example from v0.1.0 |
| --- | --- | --- |
| `major` | Increase major; reset minor and patch | v1.0.0 |
| `minor` | Increase minor; reset patch | v0.2.0 |
| Neither | Increase patch | v0.1.1 |

A pull request must **not** have both `major` and `minor`. The **Validate release labels** status check runs on label changes and code changes. Repository maintainers must create the `major` and `minor` labels (if absent) and make this check **required** in the `main` branch's protection rules to block invalid merges. Release preparation checks the same invariant as an additional safeguard.

When no semantic-version tags exist, the workflow treats v0.1.0 as the baseline and applies the selected bump. Consequently, the first unlabeled merged PR publishes **v0.1.1**; v0.1.0 is the initial project version, not an automatically published release.

The Git tag is the authoritative published version. Release builds pass the calculated version to Gradle using the `RELEASE_VERSION` environment variable, which overrides the local `appVersion` baseline. Linux and Windows packages use this SemVer value. Compose Desktop 1.9 requires macOS DMG versions to have a positive major version, so the macOS package metadata uses `MAJOR+1.MINOR.PATCH` (for example, v0.1.1 is packaged as 1.1.1 on macOS); the release tag remains the project version, while the generated macOS installer filename may contain the mapped native package version. This avoids automated version commits and additional release-only merges to `main`. Direct pushes to `main` do not trigger releases: release versioning is defined for merged pull requests.

## Packages and publication

The Release workflow builds and tests the application on each supported OS using JDK 21 and Gradle 9.8.2. Compose Desktop's native packaging produces:

| Host | Release assets |
| --- | --- |
| Ubuntu Linux | `.deb` and `.rpm` |
| Windows | `.msi` |
| macOS | `.dmg` |

Native packaging must run on its corresponding operating system. Packages include a Java runtime. The workflow verifies that **all four formats** exist before creating a GitHub Release with the version tag, generated release notes, and installer assets.

The workflow requires GitHub Actions permission to create releases (`contents: write`). If a build or asset verification fails, publication fails rather than publishing an incomplete release; rerun the failed workflow after fixing the cause. The workflow serializes release jobs to reduce concurrent version conflicts.

Packages are currently unsigned. Windows may warn about an unknown publisher and macOS Gatekeeper may block the unsigned/unnotarized DMG. Signing, notarization, additional Linux formats, and platform-specific installation testing are separate concerns. CI builds are not evidence of successful installation on every machine or physical CNC testing.
