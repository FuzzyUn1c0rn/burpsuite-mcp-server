package net.portswigger.mcp.repeater

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.ToolType
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.ui.contextmenu.ContextMenuEvent
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider
import java.awt.Component
import java.awt.Container
import java.security.MessageDigest
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
            else -> listOf(JMenuItem("Create one synthetic M0 draft (local only)").apply { addActionListener { start() } })
        }
    }

    private fun start() {
        check(SwingUtilities.isEventDispatchThread())
        if (used || unloaded.get()) return
        used = true
        val marker = "M0-draft-${UUID.randomUUID()}"
        try {
            val before = inventory()
            if (before.drafts.none { it.content != null } || before.drafts.any { it.caption == marker }) {
                log("UNCONFIRMED baseline_not_usable")
                return
            }
            val witness = M0DraftCreationWitness(marker, before)
            var firstMarkerSequence = 0L
            val request = HttpRequest.httpRequest(HttpService.httpService("127.0.0.1", 18180, false), REQUEST)
            if (unloaded.get()) {
                finish("UNCONFIRMED canceled")
                return
            }
            // This Montoya method opens a draft in Repeater. This harness has no Send action.
            api.repeater().sendToRepeater(request, marker)
            log("STARTED caption=$marker baselineRows=${before.drafts.size}")
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
                            val comparison = compareVisibleDraft(draft, capture)
                            if (unloaded.get()) {
                                cancel()
                                return@Timer
                            }
                            finish("STABLE_MARKER caption=$marker firstMarkerCapture=$firstMarkerSequence " +
                                "secondMarkerCapture=${capture.sequence} baselineIdentitiesPreserved=true $comparison")
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
        check(suite.selectedIndex == index && suite.getComponentAt(index) === root && firstTabPanes(root).single() === pane)
        check(pane.tabCount == rows.size && rows.indices.all { row ->
            pane.getTitleAt(row) == rows[row].caption && pane.getTabComponentAt(row) === rows[row].header &&
                pane.getComponentAt(row) === rows[row].content
        })
        return M0DraftInventory(source, ++sequence, frame, root, pane, rows)
    }

    /** Optional observations from the already showing draft. No view or tab switch. */
    private fun compareVisibleDraft(draft: M0DraftObservation, inventory: M0DraftInventory): String {
        val content = checkNotNull(draft.content)
        val pane = inventory.tabPane
        val index = pane.selectedIndex
        if (index < 0 || pane.getTabComponentAt(index) !== draft.header || pane.selectedComponent !== content)
            return "editorHashEqual=unknown destinationEqual=unknown"
        val editor = components(content).filterIsInstance<JTextArea>().singleOrNull {
            it.isShowing && it.isEditable && REQUEST_START.containsMatchIn(it.text)
        }
        val target = components(content).filterIsInstance<JLabel>().singleOrNull {
            it.isShowing && it.text?.startsWith("Target: ") == true
        }
        val editorEqual = editor?.text?.let { hash(it).contentEquals(hash(REQUEST)) }
        val destinationEqual = target?.text?.let { it == TARGET }
        if (pane.selectedIndex != index || pane.getTabComponentAt(index) !== draft.header ||
            pane.selectedComponent !== content) return "editorHashEqual=unknown destinationEqual=unknown"
        return "editorHashEqual=${editorEqual ?: "unknown"} destinationEqual=${destinationEqual ?: "unknown"}"
    }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private fun components(root: Component): Sequence<Component> = sequence {
        yield(root)
        if (root is Container) for (child in root.components) yieldAll(components(child))
    }

    private fun firstTabPanes(root: Component): List<JTabbedPane> = when (root) {
        is JTabbedPane -> listOf(root)
        is Container -> root.components.flatMap(::firstTabPanes)
        else -> emptyList()
    }

    private companion object {
        const val REQUEST = "GET /m0-draft-witness HTTP/1.1\r\nHost: 127.0.0.1:18180\r\n\r\n"
        const val TARGET = "Target: http://127.0.0.1:18180"
        val REQUEST_START = Regex("\\A[^\\r\\n]+ HTTP/1\\.1(?:\\r?\\n|$)")
    }
}
