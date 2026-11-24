# G-code editor control

The owner retains a `ui.components.controls.editor.GCodeEditor` instance for
source, viewport position, editing actions and softkeys. The Compose extension `editor.Render(...)`
displays its numbered source list and single-line input within the owner's
workspace. Row numbers start at one and are independent of G-code
`N` words.

## Source and input

Initialize the state with source lines or call `replaceSource(lines)` to load a
different source. Both operations preserve each line verbatim, including blank
lines and blocks that the caller's edit validator might reject. `source` returns
an observable, immutable snapshot of saved lines. Replacing source clears the
row selection, drafts and validation feedback and preserves the display toggles
and read-only setting. Initial and replacement source opens at the first row.
Each editor retains its scroll position, selection, draft and caret while its
view is absent. An owner can switch between editor instances at the same
rendering site by retaining one instance per source.

Enter appends the new-line draft or replaces the selected row. The default
validator checks [GRBL line framing](grbl-session.md#protocol-framing). A caller
can supply a `validate` callback to replace that policy, or call
`GCodeEditor.validateGrblLine(line)` from the callback and check additional
rules when it returns null. A validation message appears under the input and
the draft stays available for correction. Selecting a row loads its source
into the input with the caret at the end. Selecting it again, tapping
empty list space, moving past the last row, or saving a replacement returns to
the unfinished new-line draft and restores its caret or selection. Browsing
another row discards an unsaved replacement. Selecting a row brings it into view
and focuses the input. Returning to new-line mode and appending a line bring
the end of the source into view.

An owner wires independently placed [keypad clusters](keypads.md#editing-and-input)
to `editor::onKeypadAction`. Keypad input returns focus to the text field.
Physical Enter, numpad Enter and keypad Enter use the same commit action.
`readOnly` blocks text entry, paste, keypad edits and commits while leaving
row inspection, navigation, display toggles and caller-driven source or marker
updates available.

## Actions and default softkeys

The owner can call the editor actions directly, bind individual definitions
from `editor.softKeys`, or compose the default `editor.bottomKeys` and
`editor.rightKeys` maps into its final bindings. Definitions stay stable and
read current editor state through the existing
[softkey predicates](screen-stack.md#screen-owned-softkey-contract). The screen
owns composition, collision precedence and navigation lifecycle.

| Slot | Action | Behavior |
| --- | --- | --- |
| F5 | Previous | Selects the preceding row, starting at the last saved row when there is no selection. Enabled when a previous row exists. |
| F6 | Next | Selects the following row or returns to new-line mode after the last row. Enabled while a row is selected. |
| R5 | Format | Adds display spacing between numeric words, for example `G1X10` appears as `G1 X10`. The source and input retain their text. |
| R6 | Executing | Shows the executing row's green marker and background. A selected row retains its selection background. |
| R7 | Sent | Shows the last-sent row's blue marker. |

Navigation softkeys repeat. The three display toggles default to on and act
once per press. The caller supplies resolved one-based positions through
`GCodeLineDecorations`. The executing and sent markers share a gutter column.
When both are visible on the same row, the executing marker takes precedence.
The first gutter column is reserved for breakpoint markers. The caller owns
execution progress, source matching and stale-status interpretation.
