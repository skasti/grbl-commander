# GRBL Commander

GRBL Commander is a cross-platform desktop application for controlling CNC machines that use the GRBL firmware. 
It provides a user-friendly interface for sending G-code commands, monitoring machine status, and managing jobs.

Focus is on providing a familiar UI to users of conventional CNC controllers, like Fanuc, Haas, and Siemens on a touchscreen interface. 
While it is not necessarily easy to use or intuitive for users who are not familiar with CNC controllers, it is designed to be a powerful tool for those who are.

## Desktop shell

The initial application uses Kotlin/JVM and Compose Multiplatform Desktop. It renders a branded welcome image in a screen-stack workspace and the application logo in the header and desktop window icon, within the common operator layout (header, workspace, bottom softkeys and right softkeys). The welcome screen opens an in-memory editor/file-explorer example through the screen-owned `FileExplorer("/programs").open(editor) { result -> ... }` API, with typed selection and cancellation callbacks. It preserves editor state while the overlay is open and delivers cancellation on back/pop. It does not connect to a controller, access real program files, or execute machine commands.

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

