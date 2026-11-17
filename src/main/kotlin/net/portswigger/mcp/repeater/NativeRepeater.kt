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
import java.util.Locale
import javax.accessibility.AccessibleState
import javax.swing.AbstractButton
import javax.swing.JLabel
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTable
import javax.swing.SwingUtilities

/** Experimental adapter: only public Swing types, no reflection into Burp's obfuscated classes. */
internal class NativeRepeater(private val suiteFrame: () -> Component) {
    private val ids = WeakHashMap<Component, String>() // Accessed only on the event dispatch thread.
    private val paths = mutableMapOf<String, List<WeakStep>>()

    /** Read-only M0 context experiment: never switches Pretty/Raw/Hex. */
    suspend fun modelContextTarget(tabId: String): M0ContextTarget = withContext(Dispatchers.Swing) {
        // This investigation requires the target already selected. Do not materialize
        // a different tab/view before capturing its editor-owner identities.
        val selected = select(tabId, activate = false)
        val labels = components(selected.content).filterIsInstance<JLabel>()
            .filter { it.text == "Request" }.toList()
        val responseLabels = components(selected.content).filterIsInstance<JLabel>()
            .filter { it.text == "Response" }.toList()
        require(labels.size == 1 && visibleWithin(labels.single(), selected.content)) { "Cannot uniquely identify a visible Request anchor" }
        require(responseLabels.size == 1 && visibleWithin(responseLabels.single(), selected.content)) { "Cannot uniquely identify a visible Response anchor" }
        val candidates = generateSequence(labels.single().parent) { if (it === selected.content) null else it.parent }
            .map { ancestor -> components(ancestor).filterIsInstance<JTabbedPane>()
                .filter { pane -> visibleWithin(pane, selected.content) &&
                    (0 until pane.tabCount).map(pane::getTitleAt).containsAll(listOf("Pretty", "Raw", "Hex")) }
                .toList() }
            .firstOrNull { it.size == 1 } ?: error("Cannot uniquely bind a native request view")
        val views = candidates.single()
        val viewIndex = views.selectedIndex
        require(viewIndex >= 0) { "No native request editor view selected" }
        val viewName = views.getTitleAt(viewIndex)
        require(viewName in setOf("Pretty", "Raw", "Hex")) { "Unsupported native request view" }
        val view = views.getComponentAt(viewIndex)
        fun allEditorSelectors(root: Component) = components(root).filterIsInstance<JTabbedPane>().filter { pane ->
                (0 until pane.tabCount).map(pane::getTitleAt).containsAll(listOf("Pretty", "Raw", "Hex"))
        }.toList()
        fun editorSelectors(root: Component) = allEditorSelectors(root).filter { visibleWithin(it, selected.content) }
        fun hasResponseOrInspector(root: Component) = components(root).any { component ->
            (component is JLabel && component.text in setOf("Response", "Inspector")) ||
                (component is JTabbedPane && (0 until component.tabCount).any { component.getTitleAt(it) == "Inspector" })
        }
        fun visibleTables(root: Component) = components(root).filterIsInstance<JTable>()
            .filter { visibleWithin(it, selected.content) }.toList()
        fun pathTo(component: Component, root: Component): List<Component> {
            val path = generateSequence(component) { if (it === root) null else it.parent }.toList()
            require(path.lastOrNull() === root) { "Native editor owner is not in the captured tab" }
            return path
        }
        fun pathValid(path: List<Component>) = path.zipWithNext().all { (child, parent) -> child.parent === parent }
        val requestAnchorPath = pathTo(labels.single(), selected.content)
        val responseAnchorPath = pathTo(responseLabels.single(), selected.content)
        val separator = requestAnchorPath.first { candidate -> responseAnchorPath.any { it === candidate } }
        val requestOwner = requestAnchorPath.getOrNull(requestAnchorPath.indexOfFirst { it === separator } - 1)
            ?: error("Request anchor has no distinct editor-side owner")
        val responseOwner = responseAnchorPath.getOrNull(responseAnchorPath.indexOfFirst { it === separator } - 1)
            ?: error("Response anchor has no distinct editor-side owner")
        require(requestOwner !== responseOwner && requestOwner !== selected.content &&
            editorSelectors(requestOwner).singleOrNull() === views && !hasResponseOrInspector(requestOwner)) {
            "Cannot prove distinct Request and Response editor owners"
        }
        val responseSelectors = allEditorSelectors(responseOwner)
        require(responseSelectors.size <= 1 && responseSelectors.all { visibleWithin(it, selected.content) }) {
            "Cannot bind an ambiguous or hidden Response editor selector"
        }
        val responseViews = responseSelectors.singleOrNull()
        fun responseStateValid(): Boolean = if (responseViews != null) {
            allEditorSelectors(responseOwner).singleOrNull() === responseViews && visibleWithin(responseViews, selected.content)
        } else {
            allEditorSelectors(responseOwner).isEmpty()
        }
        // Burp may use null-page tabs only as selectors. Bind the Hex table through
        // a separately captured narrow Request region, never the whole leaf tab.
        val requestRegion = if (view == null && viewName == "Hex") requestOwner.also {
            check(visibleTables(it).size == 1) { "Cannot uniquely bind a Request-only Hex table owner" }
        } else null
        val hexTable = requestRegion?.let { visibleTables(it).single() }
        val ownerPaths = listOf(requestAnchorPath, responseAnchorPath, pathTo(requestOwner, selected.content),
            pathTo(responseOwner, selected.content), pathTo(views, requestOwner)) +
            (if (responseViews != null) listOf(pathTo(responseViews, responseOwner)) else emptyList()) +
            if (hexTable != null) listOf(pathTo(hexTable, requestOwner)) else emptyList()
        fun boundaryValid(): Boolean = ownerPaths.all(::pathValid) &&
            requestOwner.parent === separator && responseOwner.parent === separator &&
            labels.single().text == "Request" && responseLabels.single().text == "Response" &&
            visibleWithin(labels.single(), selected.content) && visibleWithin(responseLabels.single(), selected.content) &&
            components(selected.content).filterIsInstance<JLabel>().filter { it.text == "Request" }.singleOrNull() === labels.single() &&
            components(selected.content).filterIsInstance<JLabel>().filter { it.text == "Response" }.singleOrNull() === responseLabels.single() &&
            editorSelectors(requestOwner).singleOrNull() === views && responseStateValid() &&
            !hasResponseOrInspector(requestOwner)
        fun regionValid(): Boolean = requestRegion != null && boundaryValid()
        fun tableValid() = requestRegion != null && hexTable != null && regionValid() &&
            visibleTables(requestRegion).singleOrNull() === hexTable
        M0ContextTarget(tabId, viewName, canMutateNow = {
            check(SwingUtilities.isEventDispatchThread())
            verifySelection(selected.identity)
            val cancel = components(selected.content).filterIsInstance<AbstractButton>()
                .filter { visibleWithin(it, selected.content) && it.text == "Cancel" }.toList()
            cancel.size == 1 && !cancel.single().isEnabled
        }, describeSource = { source ->
            check(SwingUtilities.isEventDispatchThread())
            var diagnosticException = false
            fun checkState(read: () -> Boolean): Boolean? = try { read() } catch (_: Exception) {
                diagnosticException = true
                null
            }
            val tabValid = try { verifySelection(selected.identity); true } catch (_: Exception) { false }
            val indexMatches = checkState { views.selectedIndex == viewIndex }
            val componentMatches = checkState { views.getComponentAt(viewIndex) === view }
            val titleMatches = checkState { views.getTitleAt(viewIndex) == viewName }
            val paneWithinTab = checkState { SwingUtilities.isDescendingFrom(views, selected.content) }
            val sourceEquals = source === view
            val sourceWithinView = checkState { view != null && SwingUtilities.isDescendingFrom(source, view) }
            val sourceWithinPane = checkState { source === views || SwingUtilities.isDescendingFrom(source, views) }
            val sourceWithinTab = checkState { source === selected.content || SwingUtilities.isDescendingFrom(source, selected.content) }
            val viewWithinSource = checkState { view != null && (view === source || SwingUtilities.isDescendingFrom(view, source)) }
            M0ContextOwnership(tabValid, indexMatches, componentMatches, titleMatches, paneWithinTab,
                sourceEquals, sourceWithinView, sourceWithinPane, sourceWithinTab, viewWithinSource,
                source.javaClass.name.take(160), when (source) {
                    is javax.swing.JTable -> "JTable"
                    is javax.swing.text.JTextComponent -> "JTextComponent"
                    is JTabbedPane -> "JTabbedPane"
                    is javax.swing.JComponent -> "JComponent"
                    is Container -> "Container"
                    else -> "Component"
                }, generateSequence(source.parent) { it.parent }.take(8).map { it.javaClass.name.take(160) }.toList(),
                view?.javaClass?.name?.take(160), views.javaClass.name.take(160), diagnosticException,
                if (requestRegion == null) "selected_page" else "captured_request_hex_table",
                requestRegion?.let { checkState(::regionValid) }, hexTable?.let { checkState(::tableValid) },
                hexTable?.let { checkState { source === it || SwingUtilities.isDescendingFrom(source, it) } },
                requestRegion?.let { checkState { source === it || SwingUtilities.isDescendingFrom(source, it) } },
                requestRegion?.let { visibleTables(it).size }, requestRegion?.let(::hasResponseOrInspector),
                checkState(::boundaryValid), checkState { !SwingUtilities.isDescendingFrom(responseOwner, requestOwner) },
                checkState { source === responseOwner || SwingUtilities.isDescendingFrom(source, responseOwner) },
                if (responseViews == null) "selector_absent" else "captured_response_selector")
        }) { source ->
            if (!SwingUtilities.isEventDispatchThread()) false
            else try {
                verifySelection(selected.identity)
                views.selectedIndex == viewIndex && views.getComponentAt(viewIndex) === view &&
                    views.getTitleAt(viewIndex) == viewName &&
                    SwingUtilities.isDescendingFrom(views, selected.content) && boundaryValid() &&
                    if (requestRegion != null && hexTable != null) {
                        tableValid() && (source === hexTable || SwingUtilities.isDescendingFrom(source, hexTable))
                    } else {
                        source === view || SwingUtilities.isDescendingFrom(source, view)
                    }
            } catch (_: Exception) { false }
        }
    }

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
                },
                component.accessibleContext?.accessibleRole?.toDisplayString(Locale.ENGLISH)?.take(80),
                component.accessibleContext?.accessibleStateSet?.let { states ->
                    ACCESSIBLE_STATES.filter { (_, state) -> states.contains(state) }.map { it.first }
                },
                (component as? JTabbedPane)?.let { pane ->
                    pane.selectedIndex.takeIf { it >= 0 }?.let { pane.getTitleAt(it)?.takeIf(EDITOR_LABEL::matches) }
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

    private fun select(tabId: String, activate: Boolean = true): Selected {
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
                    if (activate) owner.selectedIndex = ownerIndex
                    else require(owner.selectedIndex == ownerIndex) { "Model context probe requires the exact tab already selected" }
                    verifySelection(identity, requireSelected = false)
                }
            val index = (0 until pane.tabCount).single { pane.getTabComponentAt(it) === header }
            flatGroups = (0 until pane.tabCount).any { pane.getComponentAt(it) == null }
            if (activate) pane.selectedIndex = index
            else require(pane.selectedIndex == index) { "Model context probe requires the exact tab already selected" }
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
        private val ACCESSIBLE_STATES = listOf(
            "selected" to AccessibleState.SELECTED, "checked" to AccessibleState.CHECKED,
            "active" to AccessibleState.ACTIVE, "enabled" to AccessibleState.ENABLED,
            "visible" to AccessibleState.VISIBLE, "showing" to AccessibleState.SHOWING,
            "editable" to AccessibleState.EDITABLE, "expanded" to AccessibleState.EXPANDED,
            "collapsed" to AccessibleState.COLLAPSED, "indeterminate" to AccessibleState.INDETERMINATE
        )
        private val CONTROL_LABEL = Regex("(?i)(Send|Cancel|Pretty|Raw|Hex|Request|Response|HTTP/1(?:\\.1)?|HTTP/2|Follow redirects|Never|Always|On-site only|In-scope only|Single request|Send group)")
        private val EDITOR_LABEL = Regex("(?i)(Pretty|Raw|Hex|Request|Response|Inspector)")
        private val REQUEST_LINE = Regex("\\A[^\\s]+ [^\\r\\n]+ HTTP/(?:1\\.[01]|2(?:\\.0)?)(?:\\r?\\n|$)")
    }
}

@Serializable
internal data class RepeaterControlProbe(
    val componentType: String, val controlText: String?, val visible: Boolean, val enabled: Boolean,
    val selected: Boolean?, val editable: Boolean?, val editorViews: List<String>?,
    val accessibleRole: String?, val accessibleStates: List<String>?, val selectedEditorView: String?
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
