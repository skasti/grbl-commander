# GRBL Commander

GRBL Commander is a cross-platform desktop application for controlling CNC machines that use the GRBL firmware. 
It provides a user-friendly interface for sending G-code commands, monitoring machine status, and managing jobs.

Focus is on providing a familiar UI to users of conventional CNC controllers, like Fanuc, Haas, and Siemens on a touchscreen interface. 
While it is not necessarily easy to use or intuitive for users who are not familiar with CNC controllers, it is designed to be a powerful tool for those who are.

## Desktop shell

The desktop application uses Kotlin/JVM and Compose Multiplatform Desktop. The persistent `AppShell` owns a header with Program, Manual and MDI UI-mode buttons, a workspace, and fixed bottom/right screen-owned softkeys. Each window owns a persistent `ScreenStack` for each UI mode. `ScreenStackHost` hosts all these stacks, retaining inactive compositions and overlays (including ordinary Compose `remember` state), while only the active stack is visible and interactive. Switching modes never pops screens or delivers pending typed results.

Program starts at the branded welcome screen, which opens an in-memory editor. The editor opens a typed `FileExplorer` overlay using `FileExplorer("/programs").open(this@EditorScreen) { result -> ... }`. Use F1 to open the editor and its file explorer. Selecting a fixture enables F1 Select and F2 Clear selection; F8 cancels. Right key R7 Info (Shift+F7) toggles its active state. Mouse/touch and keyboard use the same guarded activation path. Only the top screen supplies bindings; cross-screen inheritance remains planned in #4. Open the explorer, switch to Manual, navigate into its details screen, then return to Program: the explorer and unfinished editor are still present. Manual has a local notes screen; MDI has an unsent text buffer. These are UI fixtures, not machine functions. The application does not connect to a controller, access actual program files or execute commands.

### Requirements

- JDK 21 (the build uses a Java 21 toolchain)
- Gradle 9.8.2 (matching the version used by Yame)

Run the app from the repository root:

```bash
gradle :app-desktop:run
```

Build and test:

```bash
gradle :app-desktop:build
```

GitHub Actions builds and tests on Linux and Windows, the officially supported desktop platforms. Each merged PR triggers a versioned GitHub Release with native Linux (DEB/RPM) and Windows (MSI) installers. macOS is neither tested nor packaged and is not an officially supported target; the shared Kotlin/JVM and Compose codebase may still run there without a compatibility guarantee. Headless Compose component tests cover navigation and overlay input isolation. Physical-machine integration tests are not included.

Versions start at v0.1.0. PRs labeled `major` or `minor` choose the corresponding semantic-version bump; other PRs bump patch. See [release process](docs/releases.md) for details, including the required PR label validation check.

For implementation constraints, see [AGENTS.md](AGENTS.md), [architecture](docs/architecture.md), and the [UI layout contract](docs/ui-layout.md).
