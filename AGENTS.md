# Agent Instructions

These instructions apply to all agents working in the GRBL Commander repository.

## 1. Read the documentation first

Before making changes, read:

- `README.md`
- `docs/architecture.md`
- Any other documents in `docs/` relevant to the task.

The documentation defines the intended architecture, responsibilities,
interfaces, and design constraints of GRBL Commander.

**Do not implement functionality without first understanding the documented
architecture and relevant contracts.**

Follow existing architectural decisions unless there is a concrete reason to
change them.

If a proposed implementation conflicts with the documentation, investigate the
discrepancy instead of silently ignoring the documented design.

## 2. Keep documentation and implementation consistent

**Documentation and implementation must remain consistent.**

Whenever a change affects documented behavior, architecture, responsibilities,
interfaces, or limitations, update the relevant documentation as part of the
same change.

This includes:

- Introducing or changing application components.
- Changing boundaries or responsibilities between components.
- Adding or modifying public interfaces and contracts.
- Changing machine communication or command execution behavior.
- Adding or changing supported controller capabilities.
- Introducing new application modes or workflows.
- Changing macro, program, or tool-management behavior.
- Changing platform compatibility or requirements.

Documentation updates are part of the implementation, not optional follow-up
work.

Do not postpone necessary documentation changes to a separate task or pull
request.

### When documentation and implementation disagree

Determine whether:

1. The implementation is incorrect and should be brought into alignment with the
   documented architecture.
2. The documented architecture needs to change because a different design is
   justified.
3. The documentation is outdated or inaccurate and needs correction.

Resolve the discrepancy explicitly.

Do not change documentation merely to justify an implementation shortcut or
accidental architectural deviation.

Significant deviations from documented architectural decisions must be explained
in the pull request.

## 3. Documentation describes the current architecture

Documentation describing implemented functionality must explain GRBL Commander
**as it currently exists**, as though the architecture had been designed that
way from the beginning.

- Describe current contracts, behavior, responsibilities, and limitations
  directly.
- Do not narrate implementation history, refactorings, or migrations.
- Avoid historical language such as "previously", "now", "new", "original
  implementation", or "after this change".
- Update existing documentation coherently instead of appending descriptions of
  changes.
- Keep historical context and migration notes in pull requests, changelogs, or
  dedicated migration documents.

### Distinguish architecture, implemented features, and plans

Some documentation, particularly `docs/architecture.md`, may describe intended
architecture or functionality that has not yet been implemented.

Clearly distinguish between:

- **Architectural requirements and design decisions** — Contracts that
  implementations must follow.
- **Implemented functionality** — Behavior that actually exists and has been
  verified.
- **Planned functionality** — Intended behavior that is not yet available.

Do not present planned functionality as implemented.

Do not remove valid architectural requirements merely because the corresponding
functionality has not yet been implemented.

Documentation must remain useful both as an architectural reference and as an
accurate description of actual functionality.

## 4. Respect architectural boundaries

Follow the separation of concerns described in `docs/architecture.md`.

In particular:

- UI components must not communicate directly with machine transports.
- Machine communication must be coordinated through the designated machine
  session and protocol components.
- Controller state is authoritative; locally predicted state must not masquerade
  as confirmed state.
- G-code analysis and visualization must remain separate from execution.
- Application modes must share machine-control infrastructure rather than
  implementing independent communication paths.
- Controller-side macros should remain executable independently of GRBL
  Commander.
- Optional grblHAL capabilities must not be assumed to exist on every
  controller.

Avoid introducing parallel implementations of functionality that already has an
established owner.

Prefer extending existing abstractions over creating competing ones.

Architectural changes are allowed when justified, but must be documented and
tested.

## 5. Cross-platform requirements

GRBL Commander is a cross-platform desktop application with verified Linux and
Windows support. macOS is not a supported or regularly tested target.

Linux is the primary development and deployment focus; cross-platform
compatibility between Linux and Windows is an architectural requirement.

- Keep core application logic platform-independent.
- Isolate operating-system-specific behavior behind appropriate boundaries.
- Avoid introducing Linux-only dependencies into shared application
  functionality.
- Consider Windows compatibility when choosing libraries and APIs; macOS
  compatibility is not a release requirement.
- Document intentional platform-specific functionality and limitations.
- Preserve Linux touchscreen, serial communication, and kiosk-style operation
  requirements.

Do not assume that a feature is portable merely because it compiles on one
operating system.

## 6. Machine control and safety

GRBL Commander interacts with physical machinery. Incorrect behavior may cause
unintended motion, equipment damage, or injury.

When implementing machine-control functionality:

- Prefer established grblHAL protocols and semantics.
- Distinguish command acknowledgment from completed execution.
- Validate operation preconditions against confirmed controller state.
- Prevent conflicting motion and program-execution operations.
- Preserve responsiveness of real-time commands.
- Treat lost communication as an unknown machine state.
- Never automatically replay uncertain motion commands after reconnection.
- Never silently resume interrupted machine operations.
- Do not treat toolpath previews or static analysis as proof of safe execution.

Physical emergency-stop systems remain independent of the application.

Changes affecting motion, probing, tool changes, offsets, or program execution
require particular care and appropriate testing.

## 7. Testing and verification

Changes should include tests appropriate to their behavior and architectural
responsibilities.

Prefer testing machine-independent functionality without physical CNC hardware.

Use simulated controllers, recorded protocol messages, and representative G-code
or macro programs where practical.

Tests should cover relevant failure modes, not only successful execution.

For changes affecting shared functionality, consider all supported desktop
platforms.

Never claim that physical-machine behavior has been verified unless it has
actually been tested on hardware.

If testing is incomplete or a behavior cannot be verified, document the
limitation explicitly.

### Screenshot coverage for UI changes

Every PR adding or changing visible UI functionality must add or update saved
Compose screenshot checkpoints for the affected UI as part of the same work.

- Cover each added or affected screen, modal/dialog and visually distinct state,
  including relevant toggle/selection values, expanded content, validation and
  confirmation states. Include alternative layouts or interactions when they
  materially change what the reviewer needs to inspect.
- Minimize the number of capture scenarios while covering those states. For
  independent toggles on one screen/dialog, prefer one capture with all toggles
  off and one with all toggles on, rather than a separate before/after pair for
  each toggle. For controls with more than two values, group remaining values
  into as few additional captures as possible. Use valid, reachable combinations;
  add specific combinations only when their interaction changes the visible UI.
- Minimize checkpoints, not resolution coverage: use the shared
  `runAtScreenshotSizes` and explicit `screenshot("scenario")` checkpoints so
  every scenario retains the existing six-resolution output and source metadata.
  Extend existing functional tests and deterministic fixtures wherever possible;
  do not introduce another harness or per-screen workflow configuration.
- Keep functional assertions for individual controls and their interactions.
  Grouping controls into fewer screenshots does not reduce behavioral test
  coverage, and screenshots do not replace assertions.
- Run affected capture tests with `-PuiScreenshots`, verify that the intended
  PNGs are saved and inspect representative output for legibility and clipping.
  Review the available CI artifacts/comment. Report capture or inspection that
  could not be completed instead of claiming it was verified.
- Describe the covered screens/states and any remaining gaps in the PR. Compare
  the coverage with the UI changes before considering the work complete.

See [Compose UI screenshots](README.md#compose-ui-screenshots) for capture commands,
artifact paths, publication and review selection.

## 8. Keep implementations focused

Prefer simple, maintainable solutions consistent with the documented
architecture.

- Do not introduce abstractions without a concrete responsibility or use case.
- Do not create separate modules merely for organizational symmetry.
- Reuse established domain models and components.
- Avoid duplicating logic across UI modes.
- Keep domain logic independent of presentation frameworks.
- Avoid unrelated refactoring while implementing a task.

If a task reveals architectural shortcomings, address them deliberately rather
than introducing workarounds that undermine established boundaries.

### UI packages, screen names, and source files

- Keep reusable UI infrastructure separate from application-specific screens.
  `ui.navigation` owns screen and stack contracts, `ui.layout` owns the
  persistent shell and shared layout components. Mode packages own their concrete
  screens, such as `Editor` and `FileExplorer` in `mode.program`.
- Keep generic navigation, softkey, and layout code independent of concrete
  screens. Generic packages must not refer to `Editor`, `FileExplorer`, or other
  feature-specific implementations. Concrete screens may use the shared
  navigation and softkey contracts; the application composition root wires
  concrete screens into mode stacks.
- Name concrete screens for their user-facing role or mode. Use concise feature
  names such as `Welcome`, `Editor`, and `FileExplorer`, and mode names such as
  `ManualMode` and `MdiMode`. Do not add a `Screen` suffix just because the type
  extends `Screen<R>`; use one only when needed to distinguish the type from a
  same-named model or service. Avoid generic names such as `Screen1` or
  `BaseScreen` for concrete screens.
- Put each primary top-level type in a source file with the same name, for
  example `Editor.kt` for `Editor`. Keep supporting types in their own files by
  default. They may share a file with their owning screen when they form a
  small, cohesive part of that screen's contract or implementation and are not
  independently useful; typed results and screen-local fixtures are examples. Do
  not group unrelated types merely to reduce the file count.

### Deprecated APIs

- Do not introduce deprecated APIs when a supported replacement is available.
  Check the deprecation guidance and use the replacement that matches the
  project's dependency versions.
- Do not suppress a deprecation warning just to retain an old API. If a
  replacement cannot meet the requirement, keep the suppression as narrow as
  possible and add a comment explaining why it is necessary.
- For bundled UI images, use Compose Resources under
  `src/main/composeResources/drawable` and the generated `Res.drawable` accessors
  with `org.jetbrains.compose.resources.painterResource`. Do not use the
  deprecated `androidx.compose.ui.res.painterResource(String)` classpath API.

## 9. Pull requests and completion criteria

Before considering a task complete:

1. Verify that the implementation follows relevant documentation.
2. Update documentation affected by the change.
3. Ensure documentation accurately distinguishes implemented behavior from
   planned functionality.
4. Add or update relevant tests.
5. Run available checks appropriate to the change.
6. Review the change for violations of architectural boundaries.
7. Identify unresolved limitations and unverified behavior.

Pull requests should explain:

- What functionality changed.
- Any significant architectural decisions or deviations.
- Which documentation was updated.
- What testing was performed.
- Any remaining limitations or risks.

**A change that makes the implementation and documentation inconsistent is not
complete.**
