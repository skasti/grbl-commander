# Shared keypad layout unit

Closes #41.

## Plan

1. Derive a shared 44–72 dp key unit from the logical window dimensions at `AppShell`, including its header and softkey bars. Expose it through `LocalKeyUnit` and use the 1024×720 layout to anchor the scaling.
2. Use the shared unit by default in `GCodeKeyboard`, `KeypadPanel` and `KeyCluster`. Preserve explicit instance overrides, grid gaps, key spans and edit actions. Adapt panel margins and scroll on constrained axes.
3. Update the README and layout/architecture docs. Extend functional layout tests and the existing six-resolution screenshot checkpoints for Terminal, MDI and connection settings. Run the screenshot suite and inspect representative captures.
