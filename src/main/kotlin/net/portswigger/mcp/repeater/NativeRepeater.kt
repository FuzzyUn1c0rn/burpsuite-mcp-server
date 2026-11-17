package net.portswigger.mcp.repeater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.portswigger.mcp.Swing
import java.awt.Component
import java.awt.Container
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.net.URI
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.UUID
import java.util.WeakHashMap
import javax.swing.AbstractButton
import javax.swing.JLabel
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/** Experimental adapter: only public Swing types, no reflection into Burp's obfuscated classes. */
internal class NativeRepeater(private val suiteFrame: () -> Component) {
    private val ids = WeakHashMap<Component, String>() // Accessed only on the event dispatch thread.
    private val paths = mutableMapOf<String, List<WeakStep>>()

    /** M0 investigation only: widget mutation is NOT yet a proven native-model edit. */
    suspend fun investigateEdit(tabId: String, expectedFingerprint: String, request: String): RepeaterSnapshot =
        withContext(Dispatchers.Swing) {
            require(request.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "REQUEST_TOO_LARGE" }
            require(REQUEST_LINE.containsMatchIn(request)) { "Unsupported request document" }
            val selected = select(tabId)
            val before = snapshot(selected)
            require(before.fingerprint == expectedFingerprint) { "STALE_FINGERPRINT" }
            val cancel = components(selected.content).filterIsInstance<AbstractButton>()
                .filter { visibleWithin(it, selected.content) && it.text == "Cancel" }.toList()
            require(cancel.size == 1 && !cancel.single().isEnabled) { "Cannot verify idle state" }
            val editors = components(selected.content).filterIsInstance<JTextArea>()
                .filter { visibleWithin(it, selected.content) && REQUEST_LINE.containsMatchIn(it.text) }.toList()
            require(editors.size == 1 && editors.single().isEditable) { "Unsupported editable view" }
            val oldProtocol = before.request.lineSequence().first().substringAfterLast(" ").trim()
            val newProtocol = request.lineSequence().first().substringAfterLast(" ").trim()
            require(oldProtocol == newProtocol) { "Request-line protocol changes are outside this probe" }
            verifySelection(selected.identity)
            editors.single().text = request
            val after = snapshot(selected)
            check(before.targetHostname == after.targetHostname && before.targetPort == after.targetPort &&
                before.usesHttps == after.usesHttps) { "UPDATE_UNCONFIRMED: destination changed; mutation may have occurred" }
            after
        }

    /** Bounded read-only control metadata, excluding editor contents and arbitrary accessible names. */
    suspend fun investigateControls(tabId: String): List<RepeaterControlProbe> = withContext(Dispatchers.Swing) {
        val selected = select(tabId)
        components(selected.content).mapNotNull { component ->
            val text = when (component) {
                is AbstractButton -> component.text
                is JLabel -> component.text
                else -> null
            }
            // Only known control terms: arbitrary labels can contain project or request data.
            val safeText = text?.takeIf { it.length <= 100 && CONTROL_LABEL.matches(it) }
            if (safeText == null && component !is JTabbedPane && component !is JTextArea) null
            else RepeaterControlProbe(component.javaClass.name.take(160), safeText,
                visibleWithin(component, selected.content), component.isEnabled,
                (component as? AbstractButton)?.isSelected,
                (component as? JTextArea)?.isEditable,
                (component as? JTabbedPane)?.let { pane ->
                    (0 until pane.tabCount).mapNotNull { pane.getTitleAt(it)?.takeIf(EDITOR_LABEL::matches) }.take(12)
                })
        }.take(200).toList()
    }

    suspend fun tabs(): List<RepeaterTab> = withContext(Dispatchers.Swing) {
        val entries = inventory()
        paths.clear()
        entries.forEach { entry ->
            paths[entry.description.tabId] = entry.path.map { WeakStep(WeakReference(it.pane), WeakReference(it.header)) }
        }
        entries.map { it.description }
    }

    suspend fun read(tabId: String): RepeaterSnapshot {
        var current = withContext(Dispatchers.Swing) { snapshot(select(tabId)) }
        // Burp populates a selected tab's response editor asynchronously. Yield without blocking Swing.
        repeat(8) {
            if (current.response != null) return current
            delay(25)
            current = withContext(Dispatchers.Swing) { snapshot(select(tabId)) }
        }
        return current
    }

    suspend fun awaitIdle(tabId: String) {
        while (!withContext(Dispatchers.Swing) {
            val selected = select(tabId)
            val cancel = components(selected.content).filterIsInstance<AbstractButton>()
                .filter { visibleWithin(it, selected.content) && it.text == "Cancel" }.toList()
            cancel.size == 1 && !cancel.single().isEnabled
        }) delay(25)
    }

    suspend fun send(tabId: String, expectedFingerprint: String, beforeClick: () -> Unit = {}) = withContext(Dispatchers.Swing) {
        val selected = select(tabId)
        val current = snapshot(selected)
        require(current.fingerprint == expectedFingerprint) {
            "Repeater request or target changed. Read the tab again before sending."
        }
        val cancel = components(selected.content).filterIsInstance<AbstractButton>()
            .filter { visibleWithin(it, selected.content) && it.text == "Cancel" }.toList()
        require(cancel.size == 1 && !cancel.single().isEnabled) {
            "Cannot verify that the Repeater tab is idle; no request was sent."
        }
        val sendLabels = components(selected.content).filterIsInstance<JLabel>()
            .filter { visibleWithin(it, selected.content) && it.text == "Send" }.toList()
        val buttons = components(selected.content).filterIsInstance<AbstractButton>()
            .filter { visibleWithin(it, selected.content) && it.text == "Send" }.toList()
        if (buttons.size == 1 && sendLabels.isEmpty()) {
            require(buttons.single().isEnabled) { "Repeater Send is disabled." }
            beforeClick()
            verifySelection(selected.identity)
            buttons.single().doClick(0)
        } else {
            require(buttons.isEmpty() && sendLabels.size == 1) {
                "Unsupported or ambiguous Repeater Send control; no request was sent."
            }
            // Burp 2026.10 uses a label inside a clickable panel instead of a JButton.
            val control = sendLabels.single().parent
            require(control != null && control.isEnabled && control.mouseListeners.isNotEmpty()) {
                "Unsupported Repeater Send control; no request was sent."
            }
            val now = System.currentTimeMillis()
            val x = control.width / 2
            val y = control.height / 2
            require(x > 0 && y > 0) { "Repeater Send control has no usable bounds." }
            beforeClick()
            verifySelection(selected.identity)
            control.dispatchEvent(MouseEvent(control, MouseEvent.MOUSE_PRESSED, now,
                InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1))
            control.dispatchEvent(MouseEvent(control, MouseEvent.MOUSE_RELEASED, now + 1,
                0, x, y, 1, false, MouseEvent.BUTTON1))
            control.dispatchEvent(MouseEvent(control, MouseEvent.MOUSE_CLICKED, now + 2,
                0, x, y, 1, false, MouseEvent.BUTTON1))
        }
    }

    private data class Step(val pane: JTabbedPane, val header: Component)
    private data class WeakStep(val pane: WeakReference<JTabbedPane>, val header: WeakReference<Component>)
    private data class Entry(val description: RepeaterTab, val path: List<Step>)
    private data class SelectionIdentity(val pane: JTabbedPane, val header: Component, val content: Component)
    private data class Selected(val description: RepeaterTab, val content: Component, val identity: List<SelectionIdentity>)

    private fun verifySelection(identity: List<SelectionIdentity>, requireSelected: Boolean = true) {
        val frame = suiteFrame()
        identity.forEach { selected ->
            val pane = selected.pane
            require(pane === frame || SwingUtilities.isDescendingFrom(pane, frame)) {
                "Repeater tab detached or closed during selection; no neighboring tab may be used."
            }
            val index = (0 until pane.tabCount).singleOrNull { pane.getTabComponentAt(it) === selected.header }
            require(index != null && (!requireSelected || pane.selectedIndex == index) &&
                pane.getComponentAt(index) === selected.content) {
                "Repeater tab identity changed during selection; no neighboring tab may be used."
            }
        }
        identity.zipWithNext().forEach { (parent, child) ->
            require(child.pane === parent.content || SwingUtilities.isDescendingFrom(child.pane, parent.content)) {
                "Repeater group ancestry changed during selection; no neighboring tab may be used."
            }
        }
    }

    private fun inventory(): List<Entry> {
        val roots = components(suiteFrame()).filterIsInstance<JTabbedPane>()
            .flatMap { pane -> (0 until pane.tabCount).asSequence()
                .filter { pane.getTitleAt(it) == "Repeater" }.map { pane to it } }.toList()
        require(roots.size == 1) { "Cannot uniquely locate native Repeater in the main Burp window." }
        val (suiteTabs, repeaterIndex) = roots.single()
        suiteTabs.selectedIndex = repeaterIndex
        val root = suiteTabs.getComponentAt(repeaterIndex)
        val panes = firstTabPanes(root)
        if (panes.isEmpty()) return emptyList()
        require(panes.size == 1) { "Unsupported Repeater tab layout." }
        val result = mutableListOf<Entry>()
        fun collect(pane: JTabbedPane, path: List<Step>, names: List<String>) {
            val flatGroups = (0 until pane.tabCount).any { pane.getComponentAt(it) == null }
            for (index in 0 until pane.tabCount) {
                val header = pane.getTabComponentAt(index)
                    ?: error("Unsupported Repeater tab header. No tab index fallback is allowed.")
                val nextPath = path + Step(pane, header)
                val nextNames = names + pane.getTitleAt(index)
                val child = pane.getComponentAt(index)
                val nested = child?.let { components(it).filterIsInstance<JTabbedPane>()
                    .filter { nestedPane -> nestedPane.javaClass == pane.javaClass }.toList() }.orEmpty()
                if (nested.isNotEmpty()) {
                    require(nested.size == 1) { "Unsupported nested Repeater group layout." }
                    collect(nested.single(), nextPath, nextNames)
                } else {
                    val id = ids.getOrPut(header) { UUID.randomUUID().toString() }
                    result += Entry(RepeaterTab(id, nextNames.last(),
                        if (flatGroups) null else nextNames.dropLast(1),
                        if (child == null) "group" else "request"), nextPath)
                }
            }
        }
        collect(panes.single(), emptyList(), emptyList())
        return result
    }

    private fun select(tabId: String): Selected {
        val path = paths[tabId]
            ?: error("Repeater tab ID is unknown or closed. List tabs again; no request was sent.")
        val frame = suiteFrame()
        val names = mutableListOf<String>()
        var flatGroups = false
        // Capture the entire path before ANY selection can invoke application callbacks,
        // including switching the main Burp tool or an ancestor group.
        val identity = path.map { step ->
            val pane = step.pane.get()
                ?: error("Repeater tab is closed. List tabs again; no request was sent.")
            val header = step.header.get()
                ?: error("Repeater tab is closed. List tabs again; no request was sent.")
            require(pane === frame || SwingUtilities.isDescendingFrom(pane, frame)) {
                "Repeater tab is detached or closed. List tabs again; no request was sent."
            }
            val index = (0 until pane.tabCount).singleOrNull {
                pane.getTabComponentAt(it) === header
            } ?: error("Repeater tab changed while selecting it; no request was sent.")
            val expectedContent = pane.getComponentAt(index)
            require(expectedContent != null) {
                "This ID identifies a group header. List tabs and choose an ID with kind=request."
            }
            SelectionIdentity(pane, header, expectedContent)
        }
        verifySelection(identity, requireSelected = false)
        identity.forEachIndexed { stepIndex, selected ->
            val pane = selected.pane
            val header = selected.header
            // A client can call this while another main Burp tool is selected.
            generateSequence(pane.parent) { it.parent }.filterIsInstance<JTabbedPane>().toList().asReversed()
                .forEach { owner ->
                    val ownerIndex = (0 until owner.tabCount).singleOrNull {
                        val page = owner.getComponentAt(it)
                        page === pane || SwingUtilities.isDescendingFrom(pane, page)
                    } ?: error("Repeater ancestor changed while selecting it; no request was sent.")
                    owner.selectedIndex = ownerIndex
                    verifySelection(identity, requireSelected = false)
                }
            val index = (0 until pane.tabCount).single { pane.getTabComponentAt(it) === header }
            flatGroups = (0 until pane.tabCount).any { pane.getComponentAt(it) == null }
            pane.selectedIndex = index
            // Selecting a tab synchronously invokes application ChangeListeners. They may
            // close, replace or reorder tabs before this assignment returns.
            verifySelection(identity, requireSelected = false)
            verifySelection(identity.take(stepIndex + 1))
            val confirmedIndex = (0 until pane.tabCount).single { pane.getTabComponentAt(it) === header }
            names += pane.getTitleAt(confirmedIndex)
        }
        // Selecting cached identities avoids cycling through other tabs and resetting Burp's pooled response editor.
        verifySelection(identity)
        return Selected(RepeaterTab(tabId, names.last(), if (flatGroups) null else names.dropLast(1), "request"), identity.last().content, identity)
    }

    private fun snapshot(selected: Selected): RepeaterSnapshot {
        verifySelection(selected.identity)
        val labels = components(selected.content).filterIsInstance<JLabel>()
            .filter { visibleWithin(it, selected.content) && it.text?.startsWith("Target: ") == true }.toList()
        require(labels.size == 1) { "Cannot uniquely read the HTTP target for this Repeater tab." }
        val target = URI(labels.single().text.removePrefix("Target: "))
        require(target.scheme in setOf("http", "https") && !target.host.isNullOrEmpty() && target.userInfo == null) {
            "Unsupported Repeater target. Only HTTP and HTTPS tabs are supported."
        }
        val editors = components(selected.content).filterIsInstance<JTextArea>()
            .filter { visibleWithin(it, selected.content) }.toList()
        val requests = editors.filter { REQUEST_LINE.containsMatchIn(it.text) }
        require(requests.size == 1) { "Cannot uniquely read the request editor. Select Pretty or Raw and try again." }
        val request = requests.single().text
        val responses = editors.filter { it !== requests.single() && !it.isEditable &&
            (it.text.isEmpty() || it.text.startsWith("HTTP/")) }
        val response = responses.singleOrNull()?.text
        val secure = target.scheme == "https"
        val port = target.port.takeIf { it >= 0 } ?: if (secure) 443 else 80
        val fingerprint = requestFingerprint(target.host, port, secure, request)
        return RepeaterSnapshot(selected.description.tabId, selected.description.name,
            selected.description.groupPath, target.host, port, secure, request, response, fingerprint)
    }

    private fun firstTabPanes(root: Component): List<JTabbedPane> = when (root) {
        is JTabbedPane -> listOf(root)
        is Container -> root.components.flatMap(::firstTabPanes)
        else -> emptyList()
    }

    private fun components(root: Component): Sequence<Component> = sequence {
        yield(root)
        if (root is Container) for (child in root.components) yieldAll(components(child))
    }

    private fun visibleWithin(component: Component, root: Component): Boolean =
        generateSequence(component) { if (it === root) null else it.parent }.all { it.isVisible }

    companion object {
        private val CONTROL_LABEL = Regex("(?i)(Send|Cancel|Pretty|Raw|Hex|Request|Response|HTTP/1(?:\\.1)?|HTTP/2|Follow redirects|Never|Always|On-site only|In-scope only|Single request|Send group.*)")
        private val EDITOR_LABEL = Regex("(?i)(Pretty|Raw|Hex|Request|Response|Inspector)")
        private val REQUEST_LINE = Regex("\\A[^\\s]+ [^\\r\\n]+ HTTP/(?:1\\.[01]|2(?:\\.0)?)(?:\\r?\\n|$)")
    }
}

@Serializable
internal data class RepeaterControlProbe(
    val componentType: String, val controlText: String?, val visible: Boolean, val enabled: Boolean,
    val selected: Boolean?, val editable: Boolean?, val editorViews: List<String>?
)

internal fun canonicalRequest(request: String): String {
    val separator = Regex("\\r?\\n\\r?\\n").find(request) ?: return request.replace("\r\n", "\n")
    return request.substring(0, separator.range.first).replace("\r\n", "\n") + "\n\n" +
        request.substring(separator.range.last + 1)
}

internal fun requestFingerprint(host: String, port: Int, secure: Boolean, request: String): String =
    MessageDigest.getInstance("SHA-256").digest(
        "$host:$port:$secure\n${canonicalRequest(request)}".toByteArray(Charsets.UTF_8)
    ).joinToString("") { "%02x".format(it) }

@Serializable
internal data class RepeaterTab(val tabId: String, val name: String, val groupPath: List<String>?, val kind: String)

@Serializable
internal data class RepeaterSnapshot(
    val tabId: String, val name: String, val groupPath: List<String>?,
    val targetHostname: String, val targetPort: Int, val usesHttps: Boolean,
    val request: String, val response: String?, val fingerprint: String
)
