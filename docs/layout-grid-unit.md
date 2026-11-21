# Workspace grid sizing

The [desktop shell](ui-layout.md) provides the workspace and its grid unit.

## Workspace grid unit

`AppShell` provides `ui.layout.LocalGridUnit` for proportional Compose layouts.
It chooses the largest whole-pixel grid unit within **44–72 dp** that satisfies
fixed reference dimensions within **65%** of the main workspace width and
**75%** of its height. The workspace excludes the header, both softkey banks
and native window decorations. The unit falls back to the 44 dp minimum when
the reference does not fit. Resizing the window updates the unit. Switching
screens, fields or pages keeps it stable.

The fixed reference dimensions are calibrated to the [QWERTY and NUM+ panel](keypads.md#connection-field-keymaps):

- **Width** is 12.7 units before rounding. QWERTY has ten columns and nine
  gaps of 0.3 units. Its spanning Space key occupies two columns and the
  intervening gap within that footprint. NUM+ has four columns, two 0.3-unit
  gaps and one 0.6-unit gap, for a width of 5.2 units.
- **Height** is 8.5 units before rounding. The two clusters have seven rows
  in total and five internal gaps of 0.3 units. The fixed vertical allowance
  totals 120 dp before pixel rounding. It covers two 44 dp page controls, two
  8 dp gaps above them and one 16 dp gap between the clusters.

The calculation rounds each gap and each fixed dimension separately to match
Compose measurement. Page control heights round up to meet their 44 dp minimum.
`ui.layout` owns the fixed dimensions. Keypad controls consume the resulting
unit, and changing a keymap alone leaves the shared grid unit stable.

A standard keypad key is **1 × 1 grid unit**, excluding gaps. Compose layouts
can use `LocalGridUnit.current * 0.5f` for a margin or
`LocalGridUnit.current * 0.3f` for spacing.

See [CNC and text touch keypads](keypads.md#sizing-and-spacing) for keypad-specific spacing, panel sizing and overrides.
