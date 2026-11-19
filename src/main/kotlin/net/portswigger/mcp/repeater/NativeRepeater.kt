package net.portswigger.mcp.repeater

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.portswigger.mcp.Swing
import java.awt.Component
import java.awt.Container
import java.net.URI
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.UUID
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
    // Accessed only on the event dispatch thread.
    private val paths = mutableMapOf<String, List<WeakStep>>()
    private var listedOwner: WeakRepeaterOwner? = null

    /** Read-only M0 context experiment: never switches Pretty/Raw/Hex. */
    suspend fun modelContextTarget(tabId: String, allowed: () -> Boolean = { true },
        activate: Boolean = false): M0ContextTarget = withContext(Dispatchers.Swing) {
        checkM0RepeaterAccess(allowed)
        // This investigation requires the target already selected. Do not materialize
        // a different tab/view before capturing its editor-owner identities.
        if (activate) {
            val path = paths[tabId] ?: throw DraftFailure("TAB_NOT_FOUND", "List Repeater tabs again.")
            if (path.last().content == null) throw DraftFailure("GROUP_NOT_REQUEST", "Choose a request tab.")
        }
        val selected = try { select(tabId, activate) } catch (failure: Exception) {
            if (!activate) throw failure
            throw DraftFailure("TAB_NOT_FOUND", "The identified Repeater tab is no longer available.")
        }
        checkM0RepeaterAccess(allowed)
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
        // Burp may use null-page tabs only as selectors. Bind the Hex table through
        // a separately captured narrow Request region, never the whole leaf tab.
        val requestRegion = if (view == null && viewName == "Hex") requestOwner.also {
            check(visibleTables(it).size == 1) { "Cannot uniquely bind a Request-only Hex table owner" }
        } else null
        val hexTable = requestRegion?.let { visibleTables(it).single() }
        val ownerPaths = listOf(requestAnchorPath, responseAnchorPath, pathTo(requestOwner, selected.content),
            pathTo(responseOwner, selected.content), pathTo(views, requestOwner)) +
            if (hexTable != null) listOf(pathTo(hexTable, requestOwner)) else emptyList()
        fun boundaryValid(): Boolean = ownerPaths.all(::pathValid) &&
            requestOwner.parent === separator && responseOwner.parent === separator &&
            labels.single().text == "Request" && responseLabels.single().text == "Response" &&
            visibleWithin(labels.single(), selected.content) && visibleWithin(responseLabels.single(), selected.content) &&
            components(selected.content).filterIsInstance<JLabel>().filter { it.text == "Request" }.singleOrNull() === labels.single() &&
            components(selected.content).filterIsInstance<JLabel>().filter { it.text == "Response" }.singleOrNull() === responseLabels.single() &&
            editorSelectors(requestOwner).singleOrNull() === views &&
            !hasResponseOrInspector(requestOwner)
        fun regionValid(): Boolean = requestRegion != null && boundaryValid()
        fun tableValid() = requestRegion != null && hexTable != null && regionValid() &&
            visibleTables(requestRegion).singleOrNull() === hexTable
        fun verifyDraftReadView() {
            checkM0RepeaterAccess(allowed)
            verifySelection(selected.owner, selected.identity)
            if (!boundaryValid() || views.selectedIndex != viewIndex || views.getComponentAt(viewIndex) !== view ||
                views.getTitleAt(viewIndex) != viewName || (hexTable != null && !tableValid())) {
                throw DraftFailure("UNSUPPORTED_LAYOUT", "The owned request editor changed during the read.")
            }
        }
        fun visibleResponse() = components(responseOwner).filterIsInstance<JTextArea>()
            .filter { !it.isEditable && visibleWithin(it, responseOwner) }.singleOrNull()
        val actionBar = M0ActionBar.capture(selected.content, requestOwner, responseOwner)
        M0ContextTarget(tabId, viewName, automaticSource = hexTable, readDraftUi = { limit ->
            check(SwingUtilities.isEventDispatchThread())
            verifyDraftReadView()
            val target = visibleTarget(selected)
            val cancel = components(selected.content).filterIsInstance<AbstractButton>()
                .filter { visibleWithin(it, selected.content) && it.text == "Cancel" }.singleOrNull()
            val response = visibleResponse()
            val document = response?.document
            val sampled = displayedDraftResponse(document, limit)
            val busyState = when (cancel?.isEnabled) {
                true -> "busy"
                false -> "idle"
                null -> "unverified"
            }
            val responseStillOwned = response != null && visibleResponse() === response &&
                response.document === document && visibleWithin(response, responseOwner) &&
                visibleWithin(responseOwner, selected.content)
            val display = if (responseStillOwned) sampled else DraftDisplayedResponse("unavailable")
            val snapshot = DraftUiState(selected.description, target.host, target.port.takeIf { it >= 0 }
                ?: if (target.scheme == "https") 443 else 80, target.scheme == "https", busyState, display)
            verifyDraftReadView()
            snapshot
        }, captureProfileMenuInvoker = { invoker ->
            check(SwingUtilities.isEventDispatchThread())
            val tabValid = try { verifySelection(selected.owner, selected.identity); true } catch (_: Exception) { false }
            val withinTab = invoker === selected.content || SwingUtilities.isDescendingFrom(invoker, selected.content)
            val invokerPath = if (withinTab) pathTo(invoker, selected.content) else emptyList()
            val inRequest = invoker === requestOwner || SwingUtilities.isDescendingFrom(invoker, requestOwner)
            val inResponse = invoker === responseOwner || SwingUtilities.isDescendingFrom(invoker, responseOwner)
            val withinBar = actionBar?.containsControl(invoker) == true
            val ownership = M0ProfileMenuOwnership(tabValid, boundaryValid(), withinTab, inRequest, inResponse,
                actionBar != null, withinBar, actionBar?.isBar(invoker) == true)
            M0ProfileMenuInvokerLease(ownership) {
                check(SwingUtilities.isEventDispatchThread())
                try {
                    verifySelection(selected.owner, selected.identity)
                    withinTab && invokerPath.isNotEmpty() && pathValid(invokerPath) && boundaryValid() &&
                        !inRequest && !inResponse && actionBar?.containsControl(invoker) == true
                } catch (_: Exception) { false }
            }
        }, canMutateNow = {
            check(SwingUtilities.isEventDispatchThread())
            verifySelection(selected.owner, selected.identity)
            boundaryValid() && actionBar?.ready() == true
        }, matchesNativeService = { host, port, tls ->
            actionBar?.matchesService(host, port, tls) == true
        }, describeMutationReadiness = {
            check(SwingUtilities.isEventDispatchThread())
            val tabValid = try { verifySelection(selected.owner, selected.identity); true } catch (_: Exception) { false }
            val cancel = components(selected.content).filterIsInstance<AbstractButton>()
                .filter { visibleWithin(it, selected.content) && it.text == "Cancel" }.toList()
            val send = components(selected.content).filter { component ->
                visibleWithin(component, selected.content) && ((component is AbstractButton && component.text == "Send") ||
                    (component is JLabel && component.text == "Send"))
            }.toList()
            val targets = components(selected.content).filterIsInstance<JLabel>()
                .filter { visibleWithin(it, selected.content) && it.text?.startsWith("Target: ") == true }.toList()
            val bar = if (send.size == 1 && targets.size == 1) {
                val sendPath = pathTo(send.single(), selected.content)
                val targetPath = pathTo(targets.single(), selected.content)
                sendPath.firstOrNull { candidate -> targetPath.any { it === candidate } }
            } else null
            val barDisjoint = bar != null && bar !== selected.content && bar !== requestOwner && bar !== responseOwner &&
                !SwingUtilities.isDescendingFrom(requestOwner, bar) && !SwingUtilities.isDescendingFrom(responseOwner, bar) &&
                !SwingUtilities.isDescendingFrom(bar, requestOwner) && !SwingUtilities.isDescendingFrom(bar, responseOwner)
            M0NativeReadiness(tabValid, cancel.size, cancel.take(8).map { it.isEnabled }, send.size, targets.size,
                cancel.take(8).map { button ->
                    M0CancelControlDiagnostic(button.javaClass.name.take(160), button.isEnabled,
                        button.isShowing, button.isDisplayable, button.width > 0 && button.height > 0,
                        SwingUtilities.isDescendingFrom(button, requestOwner), SwingUtilities.isDescendingFrom(button, responseOwner),
                        bar?.let { button === it || SwingUtilities.isDescendingFrom(button, it) },
                        generateSequence(button.parent) { it.parent }.take(8).map { it.javaClass.name.take(160) }.toList())
                }, bar?.javaClass?.name?.take(160), barDisjoint,
                bar?.let { owner -> cancel.count { it === owner || SwingUtilities.isDescendingFrom(it, owner) } },
                actionBar != null, actionBar?.ready() == true, send.count { it.isShowing }, targets.count { it.isShowing },
                send.take(8).map { it.isEnabled }, send.take(8).map { it.isShowing },
                send.take(8).map { it.width > 0 && it.height > 0 })
        }, describeSource = { source ->
            check(SwingUtilities.isEventDispatchThread())
            var diagnosticException = false
            fun checkState(read: () -> Boolean): Boolean? = try { read() } catch (_: Exception) {
                diagnosticException = true
                null
            }
            val tabValid = try { verifySelection(selected.owner, selected.identity); true } catch (_: Exception) { false }
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
                checkState { source === responseOwner || SwingUtilities.isDescendingFrom(source, responseOwner) })
        }) { source ->
            if (!SwingUtilities.isEventDispatchThread()) false
            else try {
                verifySelection(selected.owner, selected.identity)
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
    suspend fun investigateEdit(tabId: String, expectedFingerprint: String, request: String,
        allowed: () -> Boolean = { true }): RepeaterSnapshot =
        withContext(Dispatchers.Swing) {
            checkM0RepeaterAccess(allowed)
            require(request.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "REQUEST_TOO_LARGE" }
            require(REQUEST_LINE.containsMatchIn(request)) { "Unsupported request document" }
            val selected = select(tabId)
            checkM0RepeaterAccess(allowed)
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
            verifySelection(selected.owner, selected.identity)
            checkM0RepeaterAccess(allowed)
            val after = try {
                editors.single().text = request
                checkM0RepeaterAccess(allowed)
                snapshot(selected)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (_: Exception) {
                error("UPDATE_UNCONFIRMED: draft mutation may have occurred. Check the current draft before editing again.")
            }
            check(before.targetHostname == after.targetHostname && before.targetPort == after.targetPort &&
                before.usesHttps == after.usesHttps) { "UPDATE_UNCONFIRMED: destination changed; mutation may have occurred" }
            after
        }

    /** Bounded read-only control metadata, excluding editor contents and arbitrary accessible names. */
    suspend fun investigateControls(tabId: String, allowed: () -> Boolean = { true }): List<RepeaterControlProbe> = withContext(Dispatchers.Swing) {
        checkM0RepeaterAccess(allowed)
        val selected = select(tabId)
        checkM0RepeaterAccess(allowed)
        val controls = components(selected.content).mapNotNull { component ->
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
        verifySelection(selected.owner, selected.identity)
        controls
    }

    suspend fun tabs(allowed: () -> Boolean = { true }): List<RepeaterTab> = withContext(Dispatchers.Swing) {
        checkM0RepeaterAccess(allowed)
        val frame = suiteFrame()
        val (suiteTabs, repeaterIndex) = locateRepeater(frame)
        val owner = RepeaterOwner(frame, suiteTabs, suiteTabs.getComponentAt(repeaterIndex))
        suiteTabs.selectedIndex = repeaterIndex
        checkM0RepeaterAccess(allowed)
        verifyRepeaterOwner(owner, requireSelected = true)
        val entries = inventory(owner.root, listedOwner?.matches(owner) == true)
        verifyRepeaterOwner(owner, requireSelected = true)
        require(entries.isEmpty() || firstTabPanes(owner.root).singleOrNull() === entries.first().path.first().pane) {
            "Native Repeater pane moved or was replaced. List tabs again."
        }
        listedOwner = WeakRepeaterOwner(owner)
        paths.clear()
        entries.forEach { entry ->
            paths[entry.description.tabId] = entry.path.map {
                WeakStep(WeakReference(it.pane), WeakReference(it.header), it.content?.let { page -> WeakReference(page) })
            }
        }
        entries.map { it.description }
    }

    /** Inventory for the isolated creation witness. Requires Repeater already selected. */
    internal suspend fun draftCreationInventory(): M0DraftInventory = withContext(Dispatchers.Swing) {
        val frame = suiteFrame()
        val (suiteTabs, repeaterIndex) = locateRepeater(frame)
        check(suiteTabs.selectedIndex == repeaterIndex) { "Repeater must already be selected" }
        val root = suiteTabs.getComponentAt(repeaterIndex)
        val tabPane = firstTabPanes(root).single()
        val drafts = (0 until tabPane.tabCount).map { index ->
            val header = checkNotNull(tabPane.getTabComponentAt(index)) { "Unsupported Repeater tab header" }
            val content = tabPane.getComponentAt(index)
            // Inspect each row directly. Recursive inventory can omit a group
            // with no children and would allocate public tab IDs as a side effect.
            check(content == null || components(content).none { it is JTabbedPane && it.javaClass == tabPane.javaClass }) {
                "Nested Repeater groups are outside the draft witness layout"
            }
            M0DraftObservation(tabPane.getTitleAt(index), header, content)
        }
        val (currentSuiteTabs, currentIndex) = locateRepeater(frame)
        check(suiteFrame() === frame && currentSuiteTabs === suiteTabs && currentIndex == repeaterIndex &&
            firstTabPanes(root).singleOrNull() === tabPane && suiteTabs.selectedIndex == repeaterIndex &&
            suiteTabs.getComponentAt(repeaterIndex) === root)
        check(tabPane.tabCount == drafts.size && drafts.indices.all { index ->
            val row = drafts[index]
            tabPane.getTitleAt(index) == row.caption && tabPane.getTabComponentAt(index) === row.header &&
                tabPane.getComponentAt(index) === row.content
        }) { "Repeater rows changed during capture" }
        M0DraftInventory(draftCaptureSource, ++draftCaptureSequence, frame, root, tabPane, drafts)
    }

    private val draftCaptureSource = Any()
    private var draftCaptureSequence = 0L // Accessed on EDT only.

    suspend fun read(tabId: String, allowed: () -> Boolean = { true }): RepeaterSnapshot {
        suspend fun sample() = withContext(Dispatchers.Swing) {
            checkM0RepeaterAccess(allowed)
            val selected = select(tabId)
            checkM0RepeaterAccess(allowed)
            snapshot(selected)
        }
        var current = sample()
        // Burp populates a selected tab's response editor asynchronously. Yield without blocking Swing.
        repeat(8) {
            if (current.response != null) return current
            delay(25)
            current = sample()
        }
        return current
    }

    private data class Step(val pane: JTabbedPane, val header: Component, val content: Component?)
    private data class WeakStep(
        val pane: WeakReference<JTabbedPane>, val header: WeakReference<Component>, val content: WeakReference<Component>?
    ) {
        fun matches(step: Step): Boolean = pane.get() === step.pane && header.get() === step.header &&
            if (step.content == null) content == null else content?.get() === step.content
    }
    private data class Entry(val description: RepeaterTab, val path: List<Step>)
    private class RepeaterOwner(val frame: Component, val suiteTabs: JTabbedPane, val root: Component)
    private class WeakRepeaterOwner(owner: RepeaterOwner) {
        private val frame = WeakReference(owner.frame)
        private val suiteTabs = WeakReference(owner.suiteTabs)
        private val root = WeakReference(owner.root)

        fun matches(owner: RepeaterOwner) =
            frame.get() === owner.frame && suiteTabs.get() === owner.suiteTabs && root.get() === owner.root
    }

    private data class SelectionIdentity(val pane: JTabbedPane, val header: Component, val content: Component)
    private data class Selected(val description: RepeaterTab, val content: Component,
        val owner: RepeaterOwner, val identity: List<SelectionIdentity>)

    private fun verifyRepeaterOwner(owner: RepeaterOwner, requireSelected: Boolean) {
        val index = owner.suiteTabs.indexOfComponent(owner.root)
        require(suiteFrame() === owner.frame &&
            (owner.suiteTabs === owner.frame || SwingUtilities.isDescendingFrom(owner.suiteTabs, owner.frame)) &&
            index >= 0 && owner.suiteTabs.getTitleAt(index) == "Repeater" &&
            (!requireSelected || owner.suiteTabs.selectedComponent === owner.root)) {
            "Native Repeater page changed. List tabs again."
        }
    }

    private fun verifySelection(owner: RepeaterOwner, identity: List<SelectionIdentity>, requireSelected: Boolean = true) {
        verifyRepeaterOwner(owner, requireSelected)
        require(firstTabPanes(owner.root).singleOrNull() === identity.first().pane) {
            "Native Repeater pane moved or was replaced. List tabs again."
        }
        identity.forEach { selected ->
            val pane = selected.pane
            require(pane === owner.root || SwingUtilities.isDescendingFrom(pane, owner.root)) {
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

    private fun locateRepeater(frame: Component): Pair<JTabbedPane, Int> {
        val roots = components(frame).filterIsInstance<JTabbedPane>()
            .flatMap { pane -> (0 until pane.tabCount).asSequence()
                .filter { pane.getTitleAt(it) == "Repeater" }.map { pane to it } }.toList()
        require(roots.size == 1) { "Cannot uniquely locate native Repeater in the main Burp window." }
        return roots.single()
    }

    private fun inventory(root: Component, reuseIds: Boolean): List<Entry> {
        val panes = firstTabPanes(root)
        require(panes.size == 1) { "Unsupported Repeater tab layout." }
        val result = mutableListOf<Entry>()
        val previousByHeader = if (reuseIds) paths.entries.associateBy { it.value.last().header.get() } else emptyMap()
        fun collect(pane: JTabbedPane, path: List<Step>, names: List<String>) {
            val flatGroups = (0 until pane.tabCount).any { pane.getComponentAt(it) == null }
            for (index in 0 until pane.tabCount) {
                val header = pane.getTabComponentAt(index)
                    ?: error("Unsupported Repeater tab header. No tab index fallback is allowed.")
                val child = pane.getComponentAt(index)
                val nextPath = path + Step(pane, header, child)
                val nextNames = names + pane.getTitleAt(index)
                val nested = child?.let { components(it).filterIsInstance<JTabbedPane>()
                    .filter { nestedPane -> nestedPane.javaClass == pane.javaClass }.toList() }.orEmpty()
                if (nested.isNotEmpty()) {
                    require(nested.size == 1) { "Unsupported nested Repeater group layout." }
                    collect(nested.single(), nextPath, nextNames)
                } else {
                    val previous = previousByHeader[header]?.takeIf { (_, known) ->
                        known.size == nextPath.size && known.indices.all { known[it].matches(nextPath[it]) }
                    }
                    val id = previous?.key ?: UUID.randomUUID().toString()
                    result += Entry(RepeaterTab(id, nextNames.last(),
                        if (flatGroups) null else nextNames.dropLast(1),
                        if (child == null) "group" else "request",
                        unsupportedReason = if (child == null) "GROUP_NOT_REQUEST" else null), nextPath)
                }
            }
        }
        collect(panes.single(), emptyList(), emptyList())
        return result
    }

    private fun select(tabId: String, activate: Boolean = true): Selected {
        val path = paths[tabId]
            ?: error("Repeater tab ID is unknown or closed. List tabs again.")
        val frame = suiteFrame()
        val (suiteTabs, repeaterIndex) = locateRepeater(frame)
        val owner = RepeaterOwner(frame, suiteTabs, suiteTabs.getComponentAt(repeaterIndex))
        require(listedOwner?.matches(owner) == true) { "Native Repeater page changed. List tabs again." }
        val names = mutableListOf<String>()
        var flatGroups = false
        // Capture the entire path before ANY selection can invoke application callbacks,
        // including switching the main Burp tool or an ancestor group.
        val identity = path.map { step ->
            val pane = step.pane.get()
                ?: error("Repeater tab is closed. List tabs again.")
            val header = step.header.get()
                ?: error("Repeater tab is closed. List tabs again.")
            require(pane === owner.root || SwingUtilities.isDescendingFrom(pane, owner.root)) {
                "Repeater tab is detached or closed. List tabs again."
            }
            val index = (0 until pane.tabCount).singleOrNull {
                pane.getTabComponentAt(it) === header
            } ?: error("Repeater tab changed while selecting it.")
            require(step.content != null) {
                "This ID identifies a group header. List tabs and choose an ID with kind=request."
            }
            val expectedContent = step.content.get()
            if (expectedContent == null || pane.getComponentAt(index) !== expectedContent) {
                paths.remove(tabId)
                error("Repeater tab content was replaced or closed. List tabs again.")
            }
            SelectionIdentity(pane, header, expectedContent)
        }
        verifySelection(owner, identity, requireSelected = false)
        identity.forEachIndexed { stepIndex, selected ->
            val pane = selected.pane
            val header = selected.header
            // A client can call this while another main Burp tool is selected.
            generateSequence(pane.parent) { it.parent }.filterIsInstance<JTabbedPane>().toList().asReversed()
                .forEach { ancestor ->
                    val ownerIndex = (0 until ancestor.tabCount).singleOrNull {
                        val page = ancestor.getComponentAt(it)
                        page === pane || SwingUtilities.isDescendingFrom(pane, page)
                    } ?: error("Repeater ancestor changed while selecting it.")
                    if (activate) ancestor.selectedIndex = ownerIndex
                    else require(ancestor.selectedIndex == ownerIndex) { "Model context probe requires the exact tab already selected" }
                    verifySelection(owner, identity, requireSelected = false)
                }
            val index = (0 until pane.tabCount).single { pane.getTabComponentAt(it) === header }
            flatGroups = (0 until pane.tabCount).any { pane.getComponentAt(it) == null }
            if (activate) pane.selectedIndex = index
            else require(pane.selectedIndex == index) { "Model context probe requires the exact tab already selected" }
            // Selecting a tab synchronously invokes application ChangeListeners. They may
            // close, replace or reorder tabs before this assignment returns.
            verifySelection(owner, identity, requireSelected = false)
            verifySelection(owner, identity.take(stepIndex + 1))
            val confirmedIndex = (0 until pane.tabCount).single { pane.getTabComponentAt(it) === header }
            names += pane.getTitleAt(confirmedIndex)
        }
        // Selecting cached identities avoids cycling through other tabs and resetting Burp's pooled response editor.
        verifySelection(owner, identity)
        return Selected(RepeaterTab(tabId, names.last(), if (flatGroups) null else names.dropLast(1), "request"),
            identity.last().content, owner, identity)
    }

    private fun visibleTarget(selected: Selected): URI {
        val labels = components(selected.content).filterIsInstance<JLabel>()
            .filter { visibleWithin(it, selected.content) && it.text?.startsWith("Target: ") == true }.toList()
        require(labels.size == 1) { "Cannot uniquely read the HTTP target for this Repeater tab." }
        val target = URI(labels.single().text.removePrefix("Target: "))
        require(target.scheme in setOf("http", "https") && !target.host.isNullOrEmpty() && target.userInfo == null) {
            "Unsupported Repeater target. Only HTTP and HTTPS tabs are supported."
        }
        return target
    }

    private fun snapshot(selected: Selected): RepeaterSnapshot {
        verifySelection(selected.owner, selected.identity)
        val target = visibleTarget(selected)
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
        verifySelection(selected.owner, selected.identity)
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

    private fun visibleWithin(component: Component, root: Component): Boolean {
        var current: Component? = component
        while (current != null) {
            if (!current.isVisible) return false
            if (current === root) return true
            current = current.parent
        }
        return false
    }

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
internal data class RepeaterTab(val tabId: String, val name: String, val groupPath: List<String>?, val kind: String,
    val unsupportedReason: String? = null)

@Serializable
internal data class RepeaterSnapshot(
    val tabId: String, val name: String, val groupPath: List<String>?,
    val targetHostname: String, val targetPort: Int, val usesHttps: Boolean,
    val request: String, val response: String?, val fingerprint: String
)
