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

The Git tag is the authoritative published version. Release builds pass the calculated version to Gradle using the `RELEASE_VERSION` environment variable, which overrides the local `appVersion` baseline. Linux and Windows packages use this SemVer value. This avoids automated version commits and additional release-only merges to `main`. Direct pushes to `main` do not trigger releases: release versioning is defined for merged pull requests.

## Packages and publication

The Release workflow builds and tests the application on each supported OS using JDK 21 and Gradle 9.8.2. Compose Desktop's native packaging produces:

| Host | Release assets |
| --- | --- |
| Ubuntu Linux | `.deb` and `.rpm` |
| Windows | `.msi` |

The application's PolyForm Noncommercial License 1.0.0 is supplied to
jpackage through `nativeDistributions.licenseFile`. The same packaging
configuration includes the `app-desktop/packaging/common/` directory in
each native installer, containing the project copyright notice, principal
third-party attributions and their license texts. The bundled Java runtime
retains its own legal notices. These are separate from the GRBL Commander
license and remain governed by their respective terms.

Native packaging must run on its corresponding operating system. Packages include a
Java runtime. The Windows MSI adds a launcher in the **GRBL Commander** Start
menu group; it does not add a desktop shortcut. The Windows native launcher and
its Start menu shortcut use a multi-resolution `.ico` generated at build time
from `app-desktop/src/main/composeResources/drawable/grbl_commander_icon.png`,
the same icon used in the application window; the installer does not use the
default Java icon. The workflow locates installer files recursively in
downloaded CI artifacts (including Linux's `deb/` and `rpm/` subdirectories),
verifies that **all three required formats** exist, and stages them in one flat
directory before creating a GitHub Release with the version tag, generated
release notes, and installer assets.

The publishing job uses the repository Actions secret `RELEASE_TOKEN` (a token authorized to create GitHub Releases) rather than the default `GITHUB_TOKEN`. The built-in token has read-only access for source checkout and CI artifacts; release credentials are limited to the publication step. If `RELEASE_TOKEN` is missing, the publishing job fails with an explicit error. If a build or asset verification fails, publication fails rather than publishing an incomplete release. A failed run that created no version tag does not consume a version number: the next merged PR computes its version from the highest existing semantic release tag, or from the v0.1.0 baseline when no tags exist. The workflow serializes release jobs to reduce concurrent version conflicts.

Packages are currently unsigned. Windows may warn about an unknown publisher. Code signing, additional Linux formats, and platform-specific installation testing are separate concerns. macOS is not a supported release target and no DMG is produced. CI builds are not evidence of successful installation on every machine or physical CNC testing.
