package net.portswigger.mcp.repeater

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities

class M0DraftCreationWitnessTest {
    private val marker = "draft-marker"

    private class Fixture {
        val tabs = JTabbedPane()
        val root = JPanel().apply { add(tabs) }
        val frame = JTabbedPane().apply { addTab("Repeater", root); addTab("Other", JPanel()) }
        val native = NativeRepeater { frame }
        fun add(caption: String): JPanel {
            val content = JPanel()
            tabs.addTab(caption, content)
            tabs.setTabComponentAt(tabs.tabCount - 1, JLabel(caption))
            return content
        }
        suspend fun inventory() = native.draftCreationInventory()
    }

    @Test
    fun `delayed marker matches only the new native draft across inventories and reorder`() = runBlocking {
        val f = Fixture()
        SwingUtilities.invokeAndWait { f.add("Duplicate"); f.add("Duplicate") }
        val witness = M0DraftCreationWitness(marker, f.inventory())
        assertEquals(M0DraftEvidence.WAITING, witness.observe(f.inventory()))
        lateinit var content: JPanel
        SwingUtilities.invokeAndWait { content = f.add(marker); f.add("Duplicate") }
        assertEquals(M0DraftEvidence.MARKER_OBSERVED, witness.observe(f.inventory()))
        SwingUtilities.invokeAndWait {
            val index = f.tabs.indexOfComponent(content)
            val header = f.tabs.getTabComponentAt(index)
            f.tabs.removeTabAt(index)
            f.tabs.insertTab(marker, null, content, null, 0)
            f.tabs.setTabComponentAt(0, header)
        }
        assertEquals(M0DraftEvidence.STABLE_MARKER, witness.observe(f.inventory()))
    }

    @Test
    fun `collision duplicates and pre-existing header or content are terminal refusals`() = runBlocking {
        for (scenario in listOf("collision", "duplicate", "rename", "reused-header", "reused-content")) {
            val f = Fixture()
            SwingUtilities.invokeAndWait { f.add(if (scenario == "collision") marker else "Existing") }
            val witness = M0DraftCreationWitness(marker, f.inventory())
            SwingUtilities.invokeAndWait {
                when (scenario) {
                    "duplicate" -> { f.add(marker); f.add(marker) }
                    "rename" -> f.tabs.setTitleAt(0, marker)
                    "reused-header" -> {
                        f.tabs.setTitleAt(0, marker)
                        f.tabs.setComponentAt(0, JPanel())
                    }
                    "reused-content" -> {
                        f.tabs.setTitleAt(0, marker)
                        f.tabs.setTabComponentAt(0, JLabel(marker))
                    }
                }
            }
            assertEquals(M0DraftEvidence.UNCONFIRMED, witness.observe(f.inventory()), scenario)
            SwingUtilities.invokeAndWait { f.tabs.removeAll(); f.add(marker) }
            assertEquals(M0DraftEvidence.UNCONFIRMED, witness.observe(f.inventory()), scenario)
        }
    }

    @Test
    fun `a marked draft cannot disappear or be replaced between observations`() = runBlocking {
        for (replace in listOf(false, true)) {
            val f = Fixture()
            val witness = M0DraftCreationWitness(marker, f.inventory())
            SwingUtilities.invokeAndWait { f.add(marker) }
            assertEquals(M0DraftEvidence.MARKER_OBSERVED, witness.observe(f.inventory()))
            SwingUtilities.invokeAndWait {
                f.tabs.removeAll()
                if (replace) f.add(marker)
            }
            assertEquals(M0DraftEvidence.UNCONFIRMED, witness.observe(f.inventory()))
        }
    }

    @Test
    fun `a replacement tab pane cannot supply a marker for the original inventory`() = runBlocking {
        val f = Fixture()
        val witness = M0DraftCreationWitness(marker, f.inventory())
        SwingUtilities.invokeAndWait {
            val replacement = JTabbedPane()
            replacement.addTab(marker, JPanel())
            replacement.setTabComponentAt(0, JLabel(marker))
            f.root.remove(f.tabs)
            f.root.add(replacement)
        }
        assertEquals(M0DraftEvidence.UNCONFIRMED, witness.observe(f.inventory()))
    }

    @Test
    fun `empty nested group stops inventory even when its caption could collide with the marker`() = runBlocking {
        val f = Fixture()
        SwingUtilities.invokeAndWait {
            f.tabs.addTab(marker, JTabbedPane())
            f.tabs.setTabComponentAt(0, JLabel(marker))
            f.add("Other")
            f.tabs.selectedIndex = 1
        }
        val failure = runCatching { f.inventory() }.exceptionOrNull()
        assertEquals("Nested Repeater groups are outside the draft witness layout", failure?.message)
        assertEquals(1, f.tabs.selectedIndex)
        assertEquals(marker, f.tabs.getTitleAt(0))
    }

    @Test
    fun `replaying one marker snapshot provides no stability credit before a genuinely newer capture`() = runBlocking {
        for (disappears in listOf(false, true)) {
            val f = Fixture()
            val baseline = f.inventory()
            val witness = M0DraftCreationWitness(marker, baseline)
            SwingUtilities.invokeAndWait { f.add(marker) }
            val first = f.inventory()
            assertEquals(M0DraftEvidence.MARKER_OBSERVED, witness.observe(first))
            assertEquals(M0DraftEvidence.STALE_CAPTURE, witness.observe(first))
            assertEquals(M0DraftEvidence.STALE_CAPTURE, witness.observe(baseline))
            if (disappears) SwingUtilities.invokeAndWait { f.tabs.removeAll() }
            assertEquals(
                if (disappears) M0DraftEvidence.UNCONFIRMED else M0DraftEvidence.STABLE_MARKER,
                witness.observe(f.inventory())
            )
        }
    }

    @Test
    fun `a capture delivered ahead of a missing disappearance cannot confirm a restored draft`() = runBlocking {
        val f = Fixture()
        val witness = M0DraftCreationWitness(marker, f.inventory())
        lateinit var content: JPanel
        lateinit var header: java.awt.Component
        SwingUtilities.invokeAndWait {
            content = f.add(marker)
            header = f.tabs.getTabComponentAt(0)
        }
        assertEquals(M0DraftEvidence.MARKER_OBSERVED, witness.observe(f.inventory()))

        SwingUtilities.invokeAndWait { f.tabs.removeTabAt(0) }
        val missing = f.inventory()
        SwingUtilities.invokeAndWait {
            f.tabs.addTab(marker, content)
            f.tabs.setTabComponentAt(0, header)
        }
        val restored = f.inventory()

        assertEquals(M0DraftEvidence.UNCONFIRMED, witness.observe(restored))
        assertEquals(M0DraftEvidence.UNCONFIRMED, witness.observe(missing))
        assertEquals(M0DraftEvidence.UNCONFIRMED, witness.observe(f.inventory()))
    }

    @Test
    fun `expiry and unavailable inventory refuse and capture never selects Repeater`() = runBlocking {
        val f = Fixture()
        var now = 0L
        val baseline = f.inventory()
        val expired = M0DraftCreationWitness(marker, baseline) { now }
        val unavailable = M0DraftCreationWitness(marker, baseline)
        now = 5_000
        SwingUtilities.invokeAndWait { f.add(marker) }
        assertEquals(M0DraftEvidence.UNCONFIRMED, expired.observe(f.inventory()))
        assertEquals(M0DraftEvidence.UNCONFIRMED, unavailable.observe(null))
        SwingUtilities.invokeAndWait { f.frame.selectedIndex = 1 }
        val failure = runCatching { f.inventory() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(1, f.frame.selectedIndex)
    }
}
