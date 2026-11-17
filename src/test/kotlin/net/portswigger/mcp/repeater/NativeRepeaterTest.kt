package net.portswigger.mcp.repeater

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import javax.swing.*

class NativeRepeaterTest {
    @Test
    fun `M0 edit changes only identified editor without sending or changing target`() = runBlocking {
        val f = Fixture()
        val other = f.add("same", "/baseline")
        f.add("same", "/working")
        val before = f.native.read(f.native.tabs().last().tabId)
        val replacement = "POST /changed?q=1 HTTP/1.1\r\nHost: document-only.test\r\nX-Test: one\r\nX-Test: two\r\n\r\n{\"value\":\"é\"}"
        val after = f.native.investigateEdit(before.tabId, before.fingerprint, replacement)
        assertEquals(replacement, after.request)
        assertEquals("example.test", after.targetHostname)
        assertEquals(443, after.targetPort)
        assertTrue(other.text.startsWith("GET /baseline"))
        assertNotEquals(before.fingerprint, after.fingerprint)
        assertEquals(0, f.sends)
        // This is a synthetic document assertion, not native Burp model acceptance.
    }

    @Test
    fun `M0 stale edit and protocol change refuse before mutation`() = runBlocking {
        val f = Fixture()
        val editor = f.add("one")
        val before = f.native.read(f.native.tabs().single().tabId)
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            f.native.investigateEdit(before.tabId, "stale", before.request.replace("/one", "/changed"))
        } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            f.native.investigateEdit(before.tabId, before.fingerprint, before.request.replace("HTTP/1.1", "HTTP/2"))
        } }
        assertEquals(before.request, editor.text)
        assertEquals(0, f.sends)
    }

    @Test
    fun `M0 edit rejects busy read only and oversized documents`() = runBlocking {
        val f = Fixture()
        val editor = f.add("one")
        val before = f.native.read(f.native.tabs().single().tabId)
        val panel = f.tabs.getComponentAt(0) as JPanel
        val cancel = panel.components.filterIsInstance<JButton>().single { it.text == "Cancel" }
        cancel.isEnabled = true
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            f.native.investigateEdit(before.tabId, before.fingerprint, before.request)
        } }
        cancel.isEnabled = false
        editor.isEditable = false
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            f.native.investigateEdit(before.tabId, before.fingerprint, before.request)
        } }
        editor.isEditable = true
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            f.native.investigateEdit(before.tabId, before.fingerprint, before.request + "é".repeat(131072))
        } }
        assertEquals(before.request, editor.text)
        assertEquals(0, f.sends)
    }

    @Test
    fun `M0 diagnostics omit arbitrary labels and editor documents`() = runBlocking {
        val f = Fixture()
        f.add("sensitive tab name")
        val entry = f.native.tabs().single()
        val panel = f.tabs.getComponentAt(0) as JPanel
        panel.add(JLabel("secret-token-value"))
        val controls = f.native.investigateControls(entry.tabId)
        assertTrue(controls.any { it.controlText == "Send" })
        assertFalse(controls.toString().contains("secret-token-value"))
        assertFalse(controls.toString().contains("example.test"))
        assertFalse(controls.toString().contains("GET /one"))
    }

    private class Fixture {
        val tabs = JTabbedPane()
        val suite = JTabbedPane().apply {
            addTab("Proxy", JPanel())
            addTab("Repeater", JPanel(BorderLayout()).apply { add(tabs) })
        }
        val native = NativeRepeater { suite }
        var sends = 0
        fun add(name: String, path: String = "/one", owner: JTabbedPane = tabs): JTextArea {
            val request = JTextArea("GET $path HTTP/1.1\r\nHost: example.test\r\n\r\n")
            val panel = JPanel().apply {
                add(JLabel("Target: https://example.test"))
                add(request)
                add(JTextArea("").apply { isEditable = false })
                add(JButton("Cancel").apply { isEnabled = false })
                add(JButton("Send").apply { addActionListener { sends++ } })
            }
            owner.addTab(name, panel)
            owner.setTabComponentAt(owner.tabCount - 1, JLabel(name))
            return request
        }
    }

    @Test
    fun `duplicate names have distinct stable ids and send only selected request`() = runBlocking {
        val f = Fixture()
        f.add("same", "/one")
        f.add("same", "/two")
        val tabs = f.native.tabs()
        assertEquals(2, tabs.size)
        assertNotEquals(tabs[0].tabId, tabs[1].tabId)
        assertEquals(tabs, f.native.tabs())
        val selected = f.native.read(tabs[1].tabId)
        assertTrue(selected.request.startsWith("GET /two "))
        f.native.send(selected.tabId, selected.fingerprint)
        assertEquals(1, f.sends)
        assertEquals(1, f.tabs.selectedIndex)
    }

    @Test
    fun `closed tab is rejected even if same name is recreated`() = runBlocking {
        val f = Fixture()
        f.add("same")
        val tab = f.native.tabs().single()
        val snapshot = f.native.read(tab.tabId)
        f.tabs.removeTabAt(0)
        f.add("same")
        assertThrows(IllegalStateException::class.java) {
            runBlocking { f.native.send(tab.tabId, snapshot.fingerprint) }
        }
        assertEquals(0, f.sends)
    }

    @Test
    fun `request changed after read is rejected before dispatch`() = runBlocking {
        val f = Fixture()
        val editor = f.add("one")
        val snapshot = f.native.read(f.native.tabs().single().tabId)
        editor.text = editor.text.replace("/one", "/changed")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { f.native.send(snapshot.tabId, snapshot.fingerprint) }
        }
        assertEquals(0, f.sends)
    }

    @Test
    fun `target changed after read is rejected`() = runBlocking {
        val f = Fixture()
        f.add("one")
        val snapshot = f.native.read(f.native.tabs().single().tabId)
        val panel = f.tabs.getComponentAt(0) as JPanel
        panel.components.filterIsInstance<JLabel>().single().text = "Target: https://different.test"
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { f.native.send(snapshot.tabId, snapshot.fingerprint) }
        }
        assertEquals(0, f.sends)
    }

    @Test
    fun `groups retain path and select the requested child`() = runBlocking {
        val f = Fixture()
        f.add("outside")
        val children = JTabbedPane()
        f.tabs.addTab("group", children)
        f.tabs.setTabComponentAt(1, JLabel("group"))
        f.add("inside", "/inside", children)
        val entry = f.native.tabs().single { it.name == "inside" }
        assertEquals(listOf("group"), entry.groupPath)
        val snapshot = f.native.read(entry.tabId)
        f.native.send(entry.tabId, snapshot.fingerprint)
        assertEquals(1, f.tabs.selectedIndex)
        assertEquals(1, f.sends)
    }

    @Test
    fun `flat group headers are listed without selecting them and cannot be sent`() = runBlocking {
        val f = Fixture()
        f.tabs.addTab("group", null)
        f.tabs.setTabComponentAt(0, JLabel("group"))
        f.add("member")
        f.tabs.selectedIndex = 1
        var changes = 0
        f.tabs.addChangeListener { changes++ }
        val entries = f.native.tabs()
        assertEquals(0, changes)
        val header = entries.single { it.kind == "group" }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { f.native.send(header.tabId, "unused") }
        }
        assertEquals(1, f.tabs.selectedIndex)
        assertEquals(0, f.sends)
        assertNull(entries.single { it.kind == "request" }.groupPath)
    }

    @Test
    fun `reading a known tab does not reset its pooled response by cycling other tabs`() = runBlocking {
        val f = Fixture()
        f.add("one")
        f.add("two")
        val entry = f.native.tabs().first()
        f.tabs.selectedIndex = 0
        val response = (f.tabs.getComponentAt(0) as JPanel).components.filterIsInstance<JTextArea>()
            .single { !it.isEditable }
        response.text = "HTTP/1.1 200 OK\r\n\r\nexisting"
        f.tabs.addChangeListener { response.text = "" }
        assertEquals("HTTP/1.1 200 OK\r\n\r\nexisting", f.native.read(entry.tabId).response)
    }

    @Test
    fun `known tab reactivates Repeater when a different main tool is selected`() = runBlocking {
        val f = Fixture()
        f.add("one")
        val entry = f.native.tabs().single()
        f.suite.selectedIndex = 0
        f.native.read(entry.tabId)
        assertEquals(1, f.suite.selectedIndex)
    }

    @Test
    fun `busy or ambiguous send control never dispatches`() = runBlocking {
        val f = Fixture()
        f.add("one")
        val snapshot = f.native.read(f.native.tabs().single().tabId)
        val panel = f.tabs.getComponentAt(0) as JPanel
        panel.add(JButton("Send").apply { addActionListener { f.sends++ } })
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { f.native.send(snapshot.tabId, snapshot.fingerprint) }
        }
        assertEquals(0, f.sends)
        panel.remove(panel.componentCount - 1)
        panel.components.filterIsInstance<JButton>().single { it.text == "Cancel" }.isEnabled = true
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { f.native.send(snapshot.tabId, snapshot.fingerprint) }
        }
        assertEquals(0, f.sends)
    }
}
