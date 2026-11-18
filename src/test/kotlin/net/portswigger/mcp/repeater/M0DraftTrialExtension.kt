package net.portswigger.mcp.repeater

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.ByteArray
import burp.api.montoya.core.ToolType
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.ui.contextmenu.ContextMenuEvent
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider
import java.awt.Component
import java.awt.Container
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Standalone test extension. Packaged only by the draft-trial init script. */
class M0DraftTrialExtension : BurpExtension {
    override fun initialize(api: MontoyaApi) {
        api.extension().setName("M0 draft identity trial (test only)")
        val trial = DraftTrial(api)
        api.userInterface().registerContextMenuItemsProvider(trial)
        api.extension().registerUnloadingHandler { trial.unload() }
    }
}

private class DraftTrial(private val api: MontoyaApi) : ContextMenuItemsProvider {
    private val source = Any()
    private var sequence = 0L
    private var used = false
    private val unloaded = AtomicBoolean()
    private var timer: Timer? = null

    override fun provideMenuItems(event: ContextMenuEvent): List<Component> {
        if (unloaded.get() || !SwingUtilities.isEventDispatchThread() || !event.isFromTool(ToolType.REPEATER)) return emptyList()
        return when {
            timer != null -> listOf(JMenuItem("Cancel draft identity observation").apply { addActionListener { cancel() } })
            used -> emptyList()
            else -> {
                val sourceDraft = runCatching { DraftTrialSnapshot.selected(inventory()) }.getOrNull()
                    ?: return emptyList()
                if (!sourceDraft.isSupported()) return emptyList()
                listOf(JMenuItem("Clone selected synthetic M0 draft (local only)").apply {
                    addActionListener { start(sourceDraft) }
                })
            }
        }
    }

    private fun start(sourceDraft: DraftTrialSnapshot) {
        check(SwingUtilities.isEventDispatchThread())
        if (used || unloaded.get()) return
        used = true
        val marker = "M0-draft-${UUID.randomUUID()}"
        try {
            val bytes = sourceDraft.text.toByteArray(Charsets.US_ASCII)
            val request = HttpRequest.httpRequest(HttpService.httpService("127.0.0.1", 18180, false), ByteArray.byteArray(*bytes))
            val service = request.httpService()
            check(service.host() == "127.0.0.1" && service.port() == 18180 && !service.secure() &&
                request.toByteArray().getBytes().contentEquals(bytes))
            val repeater = api.repeater()
            val sourceRead = DraftTrialSnapshot.selected(inventory())
            val before = inventory()
            if (sourceRead == null || !sourceDraft.sameSource(sourceRead) || !sourceRead.retainedIn(before) ||
                before.drafts.any { it.caption == marker }) {
                log("UNCONFIRMED source_changed_or_marker_collision")
                return
            }
            val witness = M0DraftCreationWitness(marker, before)
            var firstMarkerSequence = 0L
            if (unloaded.get()) {
                finish("UNCONFIRMED canceled")
                return
            }
            // This Montoya method opens a draft in Repeater. This harness has no Send action.
            repeater.sendToRepeater(request, marker)
            log("STARTED baselineRows=${before.drafts.size}")
            timer = Timer(50) {
                try {
                    if (unloaded.get()) {
                        cancel()
                        return@Timer
                    }
                    val capture = inventory()
                    val baselinePreserved = before.drafts.all { previous ->
                        capture.drafts.count { it.header === previous.header && it.content === previous.content } == 1
                    }
                    if (!baselinePreserved) {
                        finish("UNCONFIRMED baseline_identity_lost")
                        return@Timer
                    }
                    when (val result = witness.observe(capture)) {
                        M0DraftEvidence.WAITING -> Unit
                        M0DraftEvidence.MARKER_OBSERVED -> firstMarkerSequence = capture.sequence
                        M0DraftEvidence.STABLE_MARKER -> {
                            val draft = capture.drafts.single { it.caption == marker }
                            val observed = DraftTrialSnapshot.selected(capture)
                            if (observed == null || !sourceDraft.matchesClone(draft, observed)) {
                                finish("UNCONFIRMED clone_readback_mismatch_or_unavailable")
                                return@Timer
                            }
                            val finalCapture = inventory()
                            if (unloaded.get()) {
                                cancel()
                                return@Timer
                            }
                            if (!observed.retainedIn(finalCapture) ||
                                witness.observe(finalCapture) != M0DraftEvidence.STABLE_MARKER) {
                                finish("UNCONFIRMED inventory_changed_during_readback")
                                return@Timer
                            }
                            finish("CLONE_CONFIRMED caption=$marker firstMarkerCapture=$firstMarkerSequence " +
                                "secondMarkerCapture=${capture.sequence} baselineIdentitiesPreserved=true " +
                                "finalInventoryCapture=${finalCapture.sequence} editorTextEqual=true destinationEqual=true")
                        }
                        else -> finish("UNCONFIRMED witness=$result")
                    }
                } catch (_: Exception) {
                    finish("UNCONFIRMED inventory_or_read_unavailable")
                }
            }.also { it.start() }
        } catch (_: Exception) {
            finish("UNCONFIRMED trial_setup_or_creation_failed")
        }
    }

    fun unload() {
        unloaded.set(true)
        SwingUtilities.invokeLater { cancel() }
    }

    private fun cancel() {
        check(SwingUtilities.isEventDispatchThread())
        used = true
        if (timer != null) finish("UNCONFIRMED canceled")
    }

    private fun finish(result: String) {
        timer?.stop()
        timer = null
        log(result)
    }

    private fun log(result: String) = api.logging().logToOutput("M0-draft-trial $result")

    /** Read the flat pane directly so the trial archive needs no runtime adapter. */
    private fun inventory(): M0DraftInventory {
        val frame = api.userInterface().swingUtils().suiteFrame()
        val suites = components(frame).filterIsInstance<JTabbedPane>().flatMap { pane ->
            (0 until pane.tabCount).asSequence().filter { pane.getTitleAt(it) == "Repeater" }.map { pane to it }
        }.toList()
        val (suite, index) = suites.single()
        check(suite.selectedIndex == index)
        val root = suite.getComponentAt(index)
        val pane = firstTabPanes(root).single()
        val rows = (0 until pane.tabCount).map { row ->
            val header = checkNotNull(pane.getTabComponentAt(row))
            val content = checkNotNull(pane.getComponentAt(row))
            check(components(content).none { it is JTabbedPane && it.javaClass == pane.javaClass })
            M0DraftObservation(pane.getTitleAt(row), header, content)
        }
        check(api.userInterface().swingUtils().suiteFrame() === frame &&
            suite.selectedIndex == index && suite.getComponentAt(index) === root && firstTabPanes(root).single() === pane)
        check(pane.tabCount == rows.size && rows.indices.all { row ->
            pane.getTitleAt(row) == rows[row].caption && pane.getTabComponentAt(row) === rows[row].header &&
                pane.getComponentAt(row) === rows[row].content
        })
        return M0DraftInventory(source, ++sequence, frame, root, pane, rows)
    }

    private fun firstTabPanes(root: Component): List<JTabbedPane> = when (root) {
        is JTabbedPane -> listOf(root)
        is Container -> root.components.flatMap(::firstTabPanes)
        else -> emptyList()
    }

}

internal class DraftTrialSnapshot private constructor(
    private val inventory: M0DraftInventory,
    private val draft: M0DraftObservation,
    val text: String,
    private val target: String
) {
    fun isSupported() = text == REQUEST && target == TARGET

    fun sameSource(current: DraftTrialSnapshot?): Boolean =
        current != null && samePane(current.inventory) && current.owns(draft) &&
            current.text == text && current.target == target

    fun matchesClone(marked: M0DraftObservation, current: DraftTrialSnapshot?): Boolean =
        current != null && samePane(current.inventory) && current.owns(marked) && !current.owns(draft) &&
            current.text == text && current.target == target

    /** Recheck every row after reading the editor and target, whose accessors may change Swing state. */
    fun retainedIn(current: M0DraftInventory): Boolean {
        if (!samePane(current) || current.sequence != inventory.sequence + 1 ||
            current.drafts.size != inventory.drafts.size) return false
        val selected = current.drafts.getOrNull(current.tabPane.selectedIndex) ?: return false
        return owns(selected) && current.drafts.indices.all { index ->
            val before = inventory.drafts[index]
            val after = current.drafts[index]
            after.caption == before.caption && after.header === before.header && after.content === before.content
        }
    }

    private fun samePane(other: M0DraftInventory) =
        inventory.source === other.source && inventory.frame === other.frame &&
            inventory.repeaterRoot === other.repeaterRoot && inventory.tabPane === other.tabPane

    private fun owns(row: M0DraftObservation) = draft.header === row.header && draft.content === row.content

    companion object {
        private const val REQUEST = "GET /m0-draft-witness HTTP/1.1\r\nHost: 127.0.0.1:18180\r\n\r\n"
        private const val TARGET = "Target: http://127.0.0.1:18180"
        private val REQUEST_START = Regex("\\A[^\\r\\n]+ HTTP/1\\.1(?:\\r?\\n|$)")

        /** Only sample the currently visible row. Never select the source or the clone. */
        fun selected(inventory: M0DraftInventory): DraftTrialSnapshot? {
            val pane = inventory.tabPane
            val index = pane.selectedIndex
            val row = inventory.drafts.getOrNull(index) ?: return null
            val content = row.content ?: return null
            if (inventory.drafts.count { it.header === row.header } != 1 ||
                inventory.drafts.count { it.content === content } != 1) return null
            fun stillSelected() = pane.selectedIndex == index && pane.getTabComponentAt(index) === row.header &&
                pane.selectedComponent === content && pane.getTitleAt(index) == row.caption
            if (!stillSelected()) return null
            val editor = components(content).filterIsInstance<JTextArea>().singleOrNull {
                it.isShowing && it.isEditable && REQUEST_START.containsMatchIn(it.text)
            } ?: return null
            val target = components(content).filterIsInstance<JLabel>().singleOrNull {
                it.isShowing && it.text?.startsWith("Target: ") == true
            } ?: return null
            val text = editor.text
            val label = target.text ?: return null
            // Read the editor last so a target getter that edits the draft cannot leave stale text.
            if (target.text != label || editor.text != text) return null
            if (!stillSelected() || !editor.isShowing || !target.isShowing ||
                !SwingUtilities.isDescendingFrom(editor, content) || !SwingUtilities.isDescendingFrom(target, content)) return null
            return DraftTrialSnapshot(inventory, row, text, label)
        }
    }
}

private fun components(root: Component): Sequence<Component> = sequence {
    yield(root)
    if (root is Container) for (child in root.components) yieldAll(components(child))
}
