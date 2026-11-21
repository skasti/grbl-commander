# Agent Instructions

These instructions apply to all agents working in the GRBL Commander repository.

## 1. Read the documentation first

Before making changes, read:

- `README.md`
- [Architecture overview](docs/architecture.md) for module and package ownership.
- The [system guides](docs/architecture.md#system-guides) for the contracts affected
  by the task. For desktop changes, start with the [UI layout](docs/ui-layout.md).

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

Document each contract in the guide belonging to its owner. Other documents
should reference that guide instead of duplicating the contract. Brief
contextual summaries are acceptable, but avoid multiple authoritative
descriptions of the same behavior.

Use Mermaid to explain flows, sequences, package structure, dependencies and
nesting when the relationships are easier to follow visually. Keep each
diagram focused and place detailed contracts alongside it.

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

The architecture overview defines cross-cutting boundaries. System guides may
also describe requirements for future functionality.

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

Follow the [architecture overview](docs/architecture.md#design-constraints)
and the relevant [system guides](docs/architecture.md#system-guides).

Avoid introducing parallel implementations of functionality that already has an
established owner.

Prefer extending existing abstractions over creating competing ones.

Architectural changes are allowed when justified, but must be documented and
tested.

## 5. Cross-platform requirements

Apply the [platform requirements](docs/architecture.md#design-constraints)
and verify behavior using the [testing guide](docs/testing.md#verification-strategy).

## 6. Machine control and safety

Apply the [machine safety constraints](docs/architecture.md#design-constraints)
and the relevant [session protocol contracts](docs/grbl-session.md). Changes
affecting motion, probing, tool changes, offsets or program execution require
the [hardware verification guidance](docs/testing.md#verification-strategy).

## 7. Testing and verification

Follow the [verification strategy](docs/testing.md#verification-strategy).
Report incomplete testing and unverified behavior explicitly.

### Screenshot coverage for UI changes

For visible UI changes, follow the [Compose screenshot coverage requirements](docs/ui-screenshots.md#screenshot-coverage-for-ui-changes).

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

Follow the [desktop package ownership rules](docs/architecture.md#desktop-package-ownership)
for screen placement, shared UI dependencies, names and source files. Update
the system guide that owns a changed contract in the same pull request.

### Deprecated APIs

- Do not introduce deprecated APIs when a supported replacement is available.
  Check the deprecation guidance and use the replacement that matches the
  project's dependency versions.
- Do not suppress a deprecation warning just to retain an old API. If a
  replacement cannot meet the requirement, keep the suppression as narrow as
  possible and add a comment explaining why it is necessary.
- For bundled UI images, use [the Compose resource API](docs/ui-layout.md#persistent-shell).

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
