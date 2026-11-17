package net.portswigger.mcp.repeater

import kotlinx.serialization.Serializable
import java.awt.Component
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.*

/** Read-only, one-event M0 experiment. No native facade or message is retained. */
internal class M0AutomaticContext(
    source: JTable, owns: (Component) -> Boolean, permitted: () -> Boolean,
    private val ui: M0AutomaticContextUi = M0AutomaticContextUi()
) {
    private sealed interface State {
        data object Open : State
        class Issued(val event: MouseEvent) : State {
            val source = event.component
            val x = event.x
            val y = event.y
            val timestamp = event.`when`
            fun matches(input: InputEvent) = input === event && event.component === source &&
                event.id == MouseEvent.MOUSE_RELEASED && event.button == MouseEvent.BUTTON3 &&
                event.modifiersEx == 0 && event.clickCount == 1 && event.isPopupTrigger &&
                event.x == x && event.y == y && event.`when` == timestamp
        }
        data object Observed : State
        data object Closed : State
    }
    private val state = AtomicReference<State>(State.Open)
    @Volatile private var source: JTable? = source
    @Volatile private var owns: ((Component) -> Boolean)? = owns
    @Volatile private var permitted: (() -> Boolean)? = permitted
    private val ignored = AtomicInteger()
    @Volatile private var issued = false
    @Volatile private var observed = false
    @Volatile private var dispatched = 0
    @Volatile private var popupObserved = false
    @Volatile private var popupOwned = false
    @Volatile private var cleanupConfirmed = false
    @Volatile private var reason = "NOT_ISSUED"

    fun accept(event: InputEvent): Boolean {
        check(SwingUtilities.isEventDispatchThread())
        val current = state.get()
        if (current !is State.Issued || !current.matches(event) || !state.compareAndSet(current, State.Observed)) {
            ignored.updateAndGet { minOf(it + 1, 1000) }
            return false
        }
        observed = true
        return true
    }

    /** Returns only after synchronous dispatch and positively owned popup cleanup. */
    fun dispatchOnce(): Boolean {
        check(SwingUtilities.isEventDispatchThread())
        val table = source ?: return false
        // In-flight local cleanup evidence survives close until this dispatch
        // returns; close still revokes every callback/model-access token.
        val cleanupOwnership = owns ?: return false
        fun reject(code: String): Boolean { reason = code; return false }
        if (state.get() !== State.Open) return reject("ISSUANCE_CLOSED_OR_ALREADY_USED")
        if (owns?.invoke(table) != true || permitted?.invoke() != true) return reject("OWNERSHIP_OR_PERMISSION_CHANGED")
        if (!ui.showing(table) || table.width <= 0 || table.height <= 0 || table.isEditing)
            return reject("TABLE_NOT_READY_OR_CELL_EDIT_ACTIVE")
        if (ui.menuPath().isNotEmpty()) return reject("EXISTING_MENU_PATH")
        val visible = table.visibleRect.intersection(Rectangle(0, 0, table.width, table.height))
        if (visible.isEmpty) return reject("NO_VISIBLE_TABLE_RECTANGLE")
        val event = MouseEvent(table, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
            0, visible.x + visible.width / 2, visible.y + visible.height / 2, 1, true, MouseEvent.BUTTON3)
        val token = State.Issued(event)
        if (!state.compareAndSet(State.Open, token)) return reject("ISSUANCE_CLOSED_OR_ALREADY_USED")
        issued = true
        // Repeat the authoritative predicates immediately before dispatch. Cancellation
        // can close the token concurrently; it never enables a later callback.
        if (owns?.invoke(table) != true || permitted?.invoke() != true || state.get() !== token)
            return reject("CLOSED_OR_CHANGED_BEFORE_DISPATCH")
        dispatched = 1
        try { ui.dispatch(table, event) } catch (_: Exception) {
            try { cleanup(table, cleanupOwnership) } catch (_: Exception) { /* no broad cleanup fallback */ }
            reason = "PUBLIC_EVENT_DISPATCH_FAILED"
            return false
        }
        return try { cleanup(table, cleanupOwnership) } catch (_: Exception) {
            reason = "POPUP_CLEANUP_FAILED"; false
        }
    }

    private fun cleanup(table: JTable, cleanupOwnership: (Component) -> Boolean): Boolean {
        val path = ui.menuPath()
        if (path.isEmpty()) { cleanupConfirmed = true; reason = "DISPATCH_RETURNED_NO_MENU"; return true }
        popupObserved = true
        val popup = path.firstOrNull()?.component as? JPopupMenu
        popupOwned = observed && popup?.invoker === table && path.all { element ->
            element.component === popup || SwingUtilities.isDescendingFrom(element.component, popup)
        }
        if (!popupOwned || !cleanupOwnership(table)) { reason = "POPUP_OWNERSHIP_UNCONFIRMED"; return false }
        val unchanged = ui.menuPath().let { now -> now.size == path.size && now.indices.all { now[it] === path[it] } }
        if (!unchanged || popup?.invoker !== table) { reason = "POPUP_PATH_CHANGED"; return false }
        // No global Escape, menu action, or cleanup of a replacement path.
        ui.hide(checkNotNull(popup))
        val after = ui.menuPath()
        // Hiding may synchronously run application listeners. Clear only the exact
        // captured path if still present; never clear a replacement menu.
        if (after.isNotEmpty()) {
            if (after.size != path.size || after.indices.any { after[it] !== path[it] }) {
                reason = "POPUP_TAKEOVER_DURING_CLEANUP"; return false
            }
            if (popup.invoker !== table) { reason = "POPUP_INVOKER_CHANGED_DURING_CLEANUP"; return false }
            if (!cleanupOwnership(table)) { reason = "POPUP_NATIVE_OWNER_CHANGED_DURING_CLEANUP"; return false }
            ui.clearPath()
        }
        cleanupConfirmed = ui.menuPath().isEmpty() && !popup.isVisible
        reason = if (cleanupConfirmed) "OWNED_POPUP_CLEANED" else "POPUP_CLEANUP_UNCONFIRMED"
        return cleanupConfirmed
    }

    fun close() {
        state.set(State.Closed)
        source = null
        owns = null
        permitted = null
    }

    fun diagnostics() = M0AutomaticContextObservation(issued, dispatched, observed, ignored.get(),
        popupObserved, popupOwned, cleanupConfirmed, reason)
}

/** Public APIs in production; injectable only for deterministic headless component checks. */
internal class M0AutomaticContextUi(
    val showing: (JTable) -> Boolean = { it.isShowing && it.isDisplayable },
    val dispatch: (JTable, MouseEvent) -> Unit = { table, event -> table.dispatchEvent(event) },
    val menuPath: () -> Array<MenuElement> = { MenuSelectionManager.defaultManager().selectedPath },
    val hide: (JPopupMenu) -> Unit = { it.isVisible = false },
    val clearPath: () -> Unit = { MenuSelectionManager.defaultManager().clearSelectedPath() }
)

@Serializable
internal data class M0AutomaticContextObservation(
    val eventIssued: Boolean, val dispatchCount: Int, val exactIssuedEventObserved: Boolean,
    val ignoredEventCountCapped: Int, val popupObserved: Boolean, val popupInvokerOwned: Boolean,
    val popupCleanupConfirmed: Boolean, val reason: String
)
