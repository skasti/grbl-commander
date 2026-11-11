package no.grblcommander.desktop.ui.navigation

import androidx.compose.runtime.Composable
import no.grblcommander.desktop.ui.softkeys.BottomSoftKeySlot
import no.grblcommander.desktop.ui.softkeys.RightSoftKeySlot
import no.grblcommander.desktop.ui.softkeys.SoftKeyOverride

/**
 * A stateful workspace screen with a typed completion result.
 *
 * Use Unit for screens without meaningful results. The cancellation value is
 * delivered when an opened screen is dismissed without explicit completion.
 * The stack owns the binding and result delivery, not the Compose host.
 */
abstract class Screen<R>(private val cancellationResult: R) : NavigationParent {
    /**
     * Screen-owned overrides. Prefer stable maps and definitions with predicates
     * reading Compose state. For structural changes, expose a snapshot-state map
     * or a getter backed by mutableStateOf(mapOf(...)) so Compose observes them.
     * Cross-screen inheritance is reserved for issue #4.
     */
    open val bottomKeys: Map<BottomSoftKeySlot, SoftKeyOverride> = emptyMap()
    open val rightKeys: Map<RightSoftKeySlot, SoftKeyOverride> = emptyMap()

    private var owningStack: ScreenStack? = null
    private var onResult: ((R) -> Unit)? = null

    override val screenStack: ScreenStack
        get() = checkNotNull(owningStack) { "Screen is not attached to a ScreenStack" }

    /** Open above an active parent screen, or directly above a stack's current top. */
    fun open(parent: NavigationParent, onResult: (R) -> Unit = {}) {
        val stack = parent.screenStack
        if (parent is Screen<*>) {
            check(stack.top === parent) { "A covered screen cannot open another screen" }
        }
        stack.open(this, onResult)
    }

    /** Completes this active screen exactly once and restores its parent first. */
    protected fun finish(result: R): Boolean = owningStack?.finish(this, result) ?: false

    /** Subclasses own their state and presentation, not navigation injection. */
    @Composable
    abstract fun Content()

    internal fun bind(stack: ScreenStack, receiver: (R) -> Unit) {
        check(owningStack == null) { "Screen instance is already attached to a ScreenStack" }
        owningStack = stack
        onResult = receiver
    }

    /** Called only after the stack has removed this screen from its entries. */
    internal fun deliver(result: R) {
        val callback = onResult
        owningStack = null
        onResult = null
        callback?.invoke(result)
    }

    internal fun deliverCancellation() = deliver(cancellationResult)
}
