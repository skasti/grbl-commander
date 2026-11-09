# GRBL Commander

GRBL Commander is a cross-platform desktop application for controlling CNC machines that use the GRBL firmware. 
It provides a user-friendly interface for sending G-code commands, monitoring machine status, and managing jobs.

Focus is on providing a familiar UI to users of conventional CNC controllers, like Fanuc, Haas, and Siemens on a touchscreen interface. 
While it is not necessarily easy to use or intuitive for users who are not familiar with CNC controllers, it is designed to be a powerful tool for those who are.

## Desktop shell

The initial application uses Kotlin/JVM and Compose Multiplatform Desktop. It renders a branded, non-interactive splash image in the workspace and the application logo in the header and desktop window icon, within the common operator layout (header, workspace, bottom softkeys and right softkeys). It does not connect to a controller or execute machine commands.

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

GitHub Actions runs the build and tests on Linux, Windows and macOS. This initial build does not produce native installers or execute GUI/physical-machine integration tests.

For implementation constraints, see [AGENTS.md](AGENTS.md), [architecture](docs/architecture.md), and the [UI layout contract](docs/ui-layout.md).
