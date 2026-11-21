# Shared workspace dialogs

The desktop `ui.components.dialogs` package provides the following Compose primitives. Use the owning [screen stack](screen-stack.md) to open a dialog and receive its result.

## BaseDialog and ModalDialog

`ui.components.dialogs.BaseDialog` is a Compose layout for a centered surface
inside the workspace. It supplies consistent sizing, padding, and styling,
and scrolls the title and body together when space is limited. Callers supply
the title and a composable body slot.

`ui.components.dialogs.ModalDialog<R>` uses `BaseDialog` to display a message
and provides labelled bottom-softkey choices carrying typed result values.
Open this `Screen<R>` with an explicit parent and handle the result after the
parent has been restored:

```kotlin
ModalDialog(
    title = "Interrupt current connection?",
    message = "Disconnect the current controller session?",
    cancellationResult = false,
    choices = mapOf(
        BottomSoftKeySlot.F1 to ModalDialog.Choice("Disconnect", true),
        BottomSoftKeySlot.F8 to ModalDialog.Choice("Cancel", false),
    ),
).open(this@ParentScreen) { confirmed ->
    if (confirmed) requestDisconnect()
}
```

The persistent bottom softkeys supply choices through the existing pointer and
keyboard activation path. The caller supplies labels, slots and result values.

Both key banks replace inherited bindings. Unassigned slots, including all right
keys, are blank, so covered form actions and Util navigation cannot leak through.
`ScreenStackHost` supplies the scrim, focus/input isolation and retained parent
composition. `Screen`/`ScreenStack` enforce topmost-only completion and at-most-once
result delivery. Pop/dismiss returns the supplied cancellation result. Mode
switching retains a pending modal on its original stack without confirming it.

## OptionSelectDialog

`OptionSelectDialog<R>` renders typed options through `BaseDialog`. Each
option supplies a label, a result value and an optional icon. The caller
supplies a suspending loader and empty or failure content. Loading is tied to
the dialog composition. Covering the dialog or switching modes retains that
composition. Removing it cancels its loading work.

Up to six numbered touch rows correspond to F1–F6. Unavailable rows have
disabled actions. F7 is present only for multiple pages and wraps after the
last page. F8 returns the cancellation value. The selector replaces both
softkey banks. Keep data acquisition and feature-specific choices in the
calling mode, as in the [connection form](#connection-dialogs).

## Connection dialogs

Connection interruption uses F1 **Disconnect** or **Disconnect and connect**
and F8 **Cancel**. F2 and F3 are blank while the confirmation is topmost.
Cancellation preserves the parent form and draft. The parent applies the
confirmed result through its core connection operation. Port and baud selectors
use `OptionSelectDialog<R>` with choices supplied by Util Connection.
