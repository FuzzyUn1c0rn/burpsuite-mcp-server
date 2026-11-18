package net.portswigger.mcp.repeater

import java.awt.Component
import java.awt.Container
import java.net.URI
import javax.swing.AbstractButton
import javax.swing.JLabel
import javax.swing.SwingUtilities

/** Experimental idle hypothesis; native idle/busy characterization remains required. */
internal class M0ActionBar private constructor(
    private val root: Component, private val requestOwner: Component, private val responseOwner: Component,
    private val showing: (Component) -> Boolean, private val bar: Component,
    private val send: Component, private val sendControl: Component, private val target: JLabel,
    private val cancel: AbstractButton, private val targetText: String, private val destination: URI,
    private val paths: List<List<Component>>
) {
    fun containsControl(component: Component) = ready() && (component === bar || SwingUtilities.isDescendingFrom(component, bar))
    fun isBar(component: Component) = component === bar
    fun matchesService(host: String, port: Int, tls: Boolean): Boolean =
        destination.host.equals(host, ignoreCase = true) && (destination.scheme == "https") == tls &&
            (destination.port.takeIf { it >= 0 } ?: if (tls) 443 else 80) == port

    fun ready(): Boolean = paths.all { path -> path.last() === root &&
        path.zipWithNext().all { (child, parent) -> child.parent === parent } } &&
        disjoint(bar, root, requestOwner, responseOwner) &&
        controls(root, showing, ::isSend).singleOrNull() === send &&
        controls(root, showing, ::isTarget).singleOrNull() === target &&
        controls(bar, showing, ::isCancel).singleOrNull() === cancel &&
        target.text == targetText && text(send) == "Send" && cancel.text == "Cancel" &&
        listOf(bar, send, sendControl, target, cancel).all { showing(it) && it.width > 0 && it.height > 0 } &&
        bar.isEnabled && target.isEnabled && send.isEnabled && sendControl.isEnabled && !cancel.isEnabled &&
        (send is AbstractButton || send.parent === sendControl && sendControl.mouseListeners.isNotEmpty())

    companion object {
        fun capture(root: Component, requestOwner: Component, responseOwner: Component,
                    showing: (Component) -> Boolean = { it.isShowing }): M0ActionBar? = try {
            val send = controls(root, showing, ::isSend).singleOrNull()
            val target = controls(root, showing, ::isTarget).singleOrNull() as? JLabel
            if (send == null || target == null) null else {
                val sendPath = path(send, root)
                val targetPath = path(target, root)
                val bar = sendPath.firstOrNull { candidate -> targetPath.any { it === candidate } }
                val cancel = bar?.let { controls(it, showing, ::isCancel).singleOrNull() as? AbstractButton }
                val control = if (send is AbstractButton) send else send.parent
                val text = target.text
                val uri = URI(text.removePrefix("Target: "))
                if (bar == null || cancel == null || control == null ||
                    !disjoint(bar, root, requestOwner, responseOwner) ||
                    uri.scheme !in setOf("http", "https") || uri.host.isNullOrEmpty() || uri.userInfo != null ||
                    uri.port !in -1..65535 || uri.port == 0 || !uri.rawPath.isNullOrEmpty() && uri.rawPath != "/" ||
                    uri.rawQuery != null || uri.rawFragment != null) null
                else M0ActionBar(root, requestOwner, responseOwner, showing, bar, send, control, target, cancel,
                    text, uri, listOf(sendPath, targetPath, path(bar, root), path(control, root), path(cancel, root),
                        path(requestOwner, root), path(responseOwner, root))).takeIf { it.ready() }
            }
        } catch (_: Exception) { null }

        private fun path(component: Component, root: Component): List<Component> =
            generateSequence(component) { if (it === root) null else it.parent }.toList().also {
                require(it.lastOrNull() === root)
            }
        private fun within(child: Component, parent: Component) = child === parent || SwingUtilities.isDescendingFrom(child, parent)
        private fun disjoint(bar: Component, root: Component, request: Component, response: Component) =
            bar !== root && listOf(request, response).none { within(bar, it) || within(it, bar) }
        private fun text(c: Component): String? = when (c) { is JLabel -> c.text; is AbstractButton -> c.text; else -> null }
        private fun isSend(c: Component) = text(c) == "Send"
        private fun isTarget(c: Component) = c is JLabel && c.text?.startsWith("Target: ") == true
        private fun isCancel(c: Component) = c is AbstractButton && c.text == "Cancel"
        private fun controls(root: Component, showing: (Component) -> Boolean, predicate: (Component) -> Boolean): List<Component> =
            components(root).filter { showing(it) && predicate(it) }.toList()
        private fun components(root: Component): Sequence<Component> = sequence {
            yield(root)
            if (root is Container) root.components.forEach { yieldAll(components(it)) }
        }
    }
}
