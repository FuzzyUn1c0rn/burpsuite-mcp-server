package net.portswigger.mcp.repeater

import java.awt.Component
import javax.swing.JTabbedPane

internal data class M0DraftObservation(val caption: String, val header: Component, val content: Component?)

internal class M0DraftInventory(
    val source: Any,
    val sequence: Long,
    val frame: Component,
    val repeaterRoot: Component,
    val tabPane: JTabbedPane,
    val drafts: List<M0DraftObservation>
)

internal enum class M0DraftEvidence { WAITING, STALE_CAPTURE, MARKER_OBSERVED, STABLE_MARKER, UNCONFIRMED }

/**
 * A bounded observation of one marker, from an inventory captured before a
 * hypothetical create. This does not create a tab or confirm its final name or
 * contents. Consume observations serially and discard the witness when finished.
 */
internal class M0DraftCreationWitness(
    private val marker: String,
    private val before: M0DraftInventory,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val started = nowMillis()
    private var rejected = marker.isBlank() || before.drafts.any { it.caption == marker }
    private var lastSequence = before.sequence
    private var candidate: M0DraftObservation? = null

    fun observe(current: M0DraftInventory?): M0DraftEvidence {
        if (rejected || nowMillis() - started >= 5_000 || current == null || current.source !== before.source ||
            current.frame !== before.frame ||
            current.repeaterRoot !== before.repeaterRoot || current.tabPane !== before.tabPane) return reject()
        if (current.sequence <= lastSequence) return M0DraftEvidence.STALE_CAPTURE
        lastSequence = current.sequence

        val marked = current.drafts.filter { it.caption == marker }
        val previous = candidate
        if (marked.isEmpty() && previous == null) return M0DraftEvidence.WAITING
        val match = marked.singleOrNull() ?: return reject()
        val content = match.content ?: return reject()
        if (before.drafts.any { it.header === match.header || it.content === content }) return reject()
        if (current.drafts.count { it.header === match.header } != 1 ||
            current.drafts.count { it.content === content } != 1) return reject()
        if (previous != null && (previous.header !== match.header || previous.content !== content)) return reject()

        candidate = match
        return if (previous == null) M0DraftEvidence.MARKER_OBSERVED else M0DraftEvidence.STABLE_MARKER
    }

    private fun reject(): M0DraftEvidence {
        rejected = true
        candidate = null
        return M0DraftEvidence.UNCONFIRMED
    }
}
