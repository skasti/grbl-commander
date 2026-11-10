# GRBL Commander

GRBL Commander is a cross-platform desktop application for controlling CNC machines that use the GRBL firmware. 
It provides a user-friendly interface for sending G-code commands, monitoring machine status, and managing jobs.

Focus is on providing a familiar UI to users of conventional CNC controllers, like Fanuc, Haas, and Siemens on a touchscreen interface. 
While it is not necessarily easy to use or intuitive for users who are not familiar with CNC controllers, it is designed to be a powerful tool for those who are.

## Desktop shell

The desktop application uses Kotlin/JVM and Compose Multiplatform Desktop. The persistent `AppShell` owns a header with Program, Manual and MDI UI-mode buttons, a workspace, and bottom/right softkey placeholders. Each window owns a `ModeScreenStacks` registry with one `ScreenStack` per initialized mode. `ModeScreenStacksHost` keeps inactive modes and their overlays composed, retaining ordinary Compose `remember` state, while only the active mode is visible and interactive. Switching modes never pops screens or delivers pending typed results.\n\nProgram starts at the branded welcome screen, which opens an in-memory editor. The editor opens a typed `FileExplorer` overlay using `FileExplorer("/programs").open(this@EditorScreen) { result -> ... }`. Open the explorer, switch to Manual, navigate into its details screen, then return to Program: the explorer and unfinished editor are still present. Manual has a local notes screen; MDI has an unsent text buffer. These are UI fixtures, not machine functions. The application does not connect to a controller, access actual program files or execute commands.

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

GitHub Actions builds and tests on Linux, Windows, and macOS. Each merged PR triggers a versioned GitHub Release with native Linux (DEB/RPM), Windows (MSI), and macOS (DMG) installers. Headless Compose component tests cover navigation and overlay input isolation. Physical-machine integration tests are not included.

Versions start at v0.1.0. PRs labeled `major` or `minor` choose the corresponding semantic-version bump; other PRs bump patch. See [release process](docs/releases.md) for details, including the required PR label validation check.

For implementation constraints, see [AGENTS.md](AGENTS.md), [architecture](docs/architecture.md), and the [UI layout contract](docs/ui-layout.md).

