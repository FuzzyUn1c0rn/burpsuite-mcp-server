package net.portswigger.mcp.repeater

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import javax.swing.*

class NativeRepeaterTest {
    private class NullHexFixture {
        val f = Fixture()
        val selector = JTabbedPane().apply {
            addTab("Pretty", null)
            addTab("Raw", null)
            addTab("Hex", null)
            selectedIndex = 2
        }
        val table = JTable(2, 2)
        val tableHolder = JPanel().apply { add(table) }
        val header = JPanel().apply { add(JLabel("Request")); add(selector) }
        val region = JPanel(BorderLayout()).apply { add(header, BorderLayout.NORTH); add(tableHolder) }
        val responseTable = JTable(2, 2)
        val responseLabel = JLabel("Response")
        val responseSelector = JTabbedPane().apply {
            addTab("Pretty", null); addTab("Raw", null); addTab("Hex", null); selectedIndex = 2
        }
        val responseRegion = JPanel().apply { add(responseLabel); add(responseSelector); add(responseTable) }
        lateinit var id: String
        lateinit var panel: JPanel
        suspend fun prepare() {
            f.add("target")
            id = f.native.tabs().single().tabId
            panel = f.tabs.getComponentAt(0) as JPanel
            panel.add(region)
            panel.add(responseRegion)
        }
    }

    @Test
    fun `null-page Hex binds only the pre-captured unique Request table without selecting views`() = runBlocking {
        val fixture = NullHexFixture().apply { prepare() }
        val target = fixture.f.native.modelContextTarget(fixture.id)
        SwingUtilities.invokeAndWait {
            assertTrue(target.ownsSource(fixture.table))
            assertFalse(target.ownsSource(fixture.responseTable))
            val detail = checkNotNull(target.describeSource).invoke(fixture.table)
            assertEquals("captured_request_hex_table", detail.bindingMode)
            assertEquals(true, detail.requestRegionIdentityValid)
            assertEquals(true, detail.hexTableIdentityValid)
            assertEquals(true, detail.eventSourceWithinCapturedHexTable)
            assertNull(detail.selectedViewComponentClass)
            assertEquals(2, fixture.selector.selectedIndex)
        }
        assertEquals(0, fixture.f.sends)
    }

    @Test
    fun `null-page Hex refuses multiple tables Response or Inspector regions before arming`() = runBlocking {
        for (scenario in listOf("multiple", "response", "inspector", "missing_response", "hidden_response", "duplicate_response", "hidden_table",
            "missing_request", "hidden_request", "duplicate_request", "response_selector_missing", "response_selector_hidden")) {
            val fixture = NullHexFixture().apply { prepare() }
            when (scenario) {
                "multiple" -> fixture.tableHolder.add(JTable(1, 1))
                "response" -> fixture.tableHolder.add(JLabel("Response"))
                "inspector" -> fixture.tableHolder.add(JLabel("Inspector"))
                "missing_response" -> fixture.responseLabel.text = "Other"
                "hidden_response" -> fixture.responseRegion.isVisible = false
                "duplicate_response" -> fixture.panel.add(JLabel("Response"))
                "hidden_table" -> fixture.table.isVisible = false
                "missing_request" -> fixture.header.components.filterIsInstance<JLabel>().single().text = "Other"
                "hidden_request" -> fixture.header.components.filterIsInstance<JLabel>().single().isVisible = false
                "duplicate_request" -> fixture.panel.add(JLabel("Request"))
                "response_selector_missing" -> fixture.responseSelector.removeAll()
                "response_selector_hidden" -> fixture.responseSelector.isVisible = false
            }
            val expectedFailure = if (scenario in setOf("multiple", "hidden_table", "response_selector_missing", "response_selector_hidden"))
                IllegalStateException::class.java else IllegalArgumentException::class.java
            assertThrows(expectedFailure, { runBlocking {
                fixture.f.native.modelContextTarget(fixture.id)
            } }, scenario)
            assertEquals(0, fixture.f.sends)
        }
    }

    @Test
    fun `captured Hex owner rejects region table selector or label replacement and reparenting`() = runBlocking {
        for (scenario in listOf("region_reparent", "table_reparent", "table_replace", "selector_replace", "label", "extra_table",
            "response_reparent", "response_hidden", "response_replace")) {
            val fixture = NullHexFixture().apply { prepare() }
            val target = fixture.f.native.modelContextTarget(fixture.id)
            SwingUtilities.invokeAndWait {
                when (scenario) {
                    "region_reparent" -> fixture.panel.add(JPanel().apply { add(fixture.region) })
                    "table_reparent" -> fixture.region.add(JPanel().apply { add(fixture.table) }, BorderLayout.SOUTH)
                    "table_replace" -> { fixture.tableHolder.remove(fixture.table); fixture.tableHolder.add(JTable(2, 2)) }
                    "selector_replace" -> { fixture.header.remove(fixture.selector); fixture.header.add(JTabbedPane()) }
                    "label" -> fixture.header.components.filterIsInstance<JLabel>().single().text = "Other"
                    "extra_table" -> fixture.tableHolder.add(JTable(1, 1))
                    "response_reparent" -> fixture.panel.add(JPanel().apply { add(fixture.responseRegion) })
                    "response_hidden" -> fixture.responseRegion.isVisible = false
                    "response_replace" -> {
                        fixture.responseRegion.remove(fixture.responseLabel)
                        fixture.responseRegion.add(JLabel("Response"))
                    }
                }
                assertFalse(target.ownsSource(fixture.table), scenario)
                assertFalse(target.ownsSource(fixture.responseTable), scenario)
            }
            assertEquals(0, fixture.f.sends)
        }
    }

    @Test
    fun `model context requires already-selected native tool and tab without activating either`() = runBlocking {
        val fixture = NullHexFixture().apply { prepare() }
        fixture.f.add("neighbor")
        for (scenario in listOf("suite", "leaf")) {
            SwingUtilities.invokeAndWait {
                fixture.f.suite.selectedIndex = if (scenario == "suite") 0 else 1
                fixture.f.tabs.selectedIndex = if (scenario == "leaf") 1 else 0
            }
            val suiteIndex = fixture.f.suite.selectedIndex
            val tabIndex = fixture.f.tabs.selectedIndex
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                fixture.f.native.modelContextTarget(fixture.id)
            } }
            assertEquals(suiteIndex, fixture.f.suite.selectedIndex)
            assertEquals(tabIndex, fixture.f.tabs.selectedIndex)
        }
        assertEquals(0, fixture.f.sends)
    }

    @Test
    fun `model context target binds exact request view and never response or switched views`() = runBlocking {
        val f = Fixture()
        f.add("target")
        val entry = f.native.tabs().single()
        val requestSource = JTextArea()
        val responseSource = JTextArea()
        responseSource.name = "PRIVATE_COMPONENT_NAME"
        responseSource.text = "PRIVATE_DOCUMENT"
        val views = JTabbedPane().apply {
            addTab("Pretty", JPanel())
            addTab("Raw", JPanel())
            addTab("Hex", requestSource)
            selectedIndex = 2
        }
        val panel = f.tabs.getComponentAt(0) as JPanel
        panel.add(JPanel(BorderLayout()).apply { add(JLabel("Request"), BorderLayout.NORTH); add(views) })
        val responseViews = JTabbedPane().apply {
            addTab("Pretty", JPanel()); addTab("Raw", responseSource); addTab("Hex", JPanel()); selectedIndex = 1
        }
        panel.add(JPanel(BorderLayout()).apply { add(JLabel("Response"), BorderLayout.NORTH); add(responseViews) })
        val target = f.native.modelContextTarget(entry.tabId)
        assertEquals("Hex", target.view)
        SwingUtilities.invokeAndWait {
            assertTrue(target.ownsSource(requestSource))
            assertFalse(target.ownsSource(responseSource))
            val ownership = checkNotNull(target.describeSource).invoke(responseSource)
            assertEquals(true, ownership.nativeTabPathIdentityValid)
            assertEquals(true, ownership.viewIndexMatches)
            assertEquals(true, ownership.viewComponentMatches)
            assertEquals(true, ownership.viewTitleMatches)
            assertEquals(true, ownership.eventSourceWithinTab)
            assertEquals(false, ownership.eventSourceWithinRequestPane)
            assertEquals(false, ownership.eventSourceWithinView)
            assertTrue(ownership.eventSourceAncestorClasses.size <= 8)
            assertFalse(ownership.toString().contains("PRIVATE_"))
            assertEquals(2, views.selectedIndex)
            views.selectedIndex = 1
            assertFalse(target.ownsSource(requestSource))
            assertEquals(false, checkNotNull(target.describeSource).invoke(requestSource).viewIndexMatches)
        }
        assertEquals(0, f.sends)
    }

    @Test
    fun `suite and group activation callbacks cannot replace captured target identity`() = runBlocking {
        for (boundary in listOf("suite", "group")) {
            for (mutation in listOf("remove", "header", "content", "reparent")) {
                for (action in listOf("read", "edit", "send")) {
                    val f = Fixture()
                    f.add("baseline")
                    val leafPane = if (boundary == "group") JTabbedPane().also {
                        f.tabs.addTab("group", it)
                        f.tabs.setTabComponentAt(1, JLabel("group"))
                    } else f.tabs
                    val targetIndex = if (boundary == "group") 0 else 1
                    val original = f.add("target", owner = leafPane)
                    val neighbor = f.add("neighbor", owner = leafPane)
                    val holder = JTabbedPane()
                    val replacement = f.add("replacement", owner = holder)
                    val replacementPanel = holder.getComponentAt(0)
                    val before = f.native.read(f.native.tabs().single { it.name == "target" }.tabId)
                    SwingUtilities.invokeAndWait {
                        val owner = if (boundary == "suite") f.suite else f.tabs
                        owner.selectedIndex = 0
                        var changed = false
                        owner.addChangeListener {
                            if (!changed && owner.selectedIndex == 1) {
                                changed = true
                                when (mutation) {
                                    "remove" -> leafPane.removeTabAt(targetIndex)
                                    "header" -> leafPane.setTabComponentAt(targetIndex, JLabel("replacement"))
                                    "content" -> leafPane.setComponentAt(targetIndex, replacementPanel)
                                    "reparent" -> JPanel().add(leafPane)
                                }
                            }
                        }
                    }
                    assertThrows(IllegalArgumentException::class.java, { runBlocking {
                        when (action) {
                            "read" -> f.native.read(before.tabId)
                            "edit" -> f.native.investigateEdit(before.tabId, before.fingerprint,
                                before.request.replace("/one", "/REPLACED-TAB"))
                            "send" -> f.native.send(before.tabId, before.fingerprint)
                        }
                    } }, "$boundary/$mutation during $action")
                    assertEquals(before.request, original.text)
                    assertEquals(before.request, neighbor.text)
                    assertEquals(before.request, replacement.text)
                    assertEquals(0, f.sends)
                }
            }
        }
    }

    @Test
    fun `read edit and send refuse replaced header content or reparented pane after selection`() = runBlocking {
        for (mutation in listOf("remove", "header", "content", "reparent")) {
            for (action in listOf("read", "edit", "send")) {
                val f = Fixture()
                f.add("baseline")
                val original = f.add("target")
                val neighbor = f.add("neighbor")
                val before = f.native.read(f.native.tabs()[1].tabId)
                SwingUtilities.invokeAndWait {
                    f.tabs.selectedIndex = 0
                    var changed = false
                    f.tabs.addChangeListener {
                        if (!changed && f.tabs.selectedIndex == 1) {
                            changed = true
                            when (mutation) {
                                "remove" -> f.tabs.removeTabAt(1)
                                "header" -> f.tabs.setTabComponentAt(1, JLabel("replacement"))
                                "content" -> f.tabs.setComponentAt(1, JPanel())
                                "reparent" -> JPanel().add(f.tabs)
                            }
                        }
                    }
                }
                assertThrows(IllegalArgumentException::class.java, { runBlocking {
                    when (action) {
                        "read" -> f.native.read(before.tabId)
                        "edit" -> f.native.investigateEdit(before.tabId, before.fingerprint,
                            before.request.replace("/one", "/WRONG-TAB"))
                        "send" -> f.native.send(before.tabId, before.fingerprint)
                    }
                } }, "$mutation during $action")
                assertEquals(before.request, original.text, "$mutation during $action mutated original")
                assertEquals(before.request, neighbor.text, "$mutation during $action mutated neighbor")
                assertEquals(0, f.sends, "$mutation during $action dispatched")
            }
        }
    }

    @Test
    fun `tab removed by selection listener never redirects an edit to identical neighbor`() = runBlocking {
        val f = Fixture()
        f.add("baseline")
        f.add("target")
        val neighbor = f.add("neighbor")
        val before = f.native.read(f.native.tabs()[1].tabId)
        f.removeTargetOnSelection()
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            f.native.investigateEdit(before.tabId, before.fingerprint, before.request.replace("/one", "/WRONG-TAB"))
        } }
        assertEquals(before.request, neighbor.text)
        assertEquals(0, f.sends)
    }

    @Test
    fun `tab removed by selection listener never redirects send to identical neighbor`() = runBlocking {
        val f = Fixture()
        f.add("baseline")
        f.add("target")
        f.add("neighbor")
        val before = f.native.read(f.native.tabs()[1].tabId)
        f.removeTargetOnSelection()
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            f.native.send(before.tabId, before.fingerprint)
        } }
        assertEquals(0, f.sends)
    }

    @Test
    fun `identity rechecked after beforeClick callback without sending neighbor`() = runBlocking {
        val f = Fixture()
        f.add("target")
        f.add("neighbor")
        val before = f.native.read(f.native.tabs().first().tabId)
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            f.native.send(before.tabId, before.fingerprint) { f.tabs.removeTabAt(0) }
        } }
        assertEquals(0, f.sends)
    }

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
        panel.add(JLabel("Send group SECRET_SUFFIX"))
        val controls = f.native.investigateControls(entry.tabId)
        assertTrue(controls.any { it.controlText == "Send" })
        assertFalse(controls.toString().contains("secret-token-value"))
        assertFalse(controls.toString().contains("SECRET_SUFFIX"))
        assertFalse(controls.toString().contains("example.test"))
        assertFalse(controls.toString().contains("GET /one"))
    }

    @Test
    fun `M0 diagnostics expose only public accessibility states and known selected editor view`() = runBlocking {
        val f = Fixture()
        f.add("one")
        val entry = f.native.tabs().single()
        val panel = f.tabs.getComponentAt(0) as JPanel
        val protocol = JToggleButton("HTTP/2").apply { isSelected = true }
        val views = JTabbedPane().apply {
            addTab("Pretty", JPanel())
            addTab("Raw", JPanel())
            selectedIndex = 1
        }
        panel.add(protocol)
        panel.add(views)
        val controls = f.native.investigateControls(entry.tabId)
        val observed = controls.single { it.controlText == "HTTP/2" }
        assertEquals(true, observed.selected)
        assertTrue(observed.accessibleStates.orEmpty().any { it in setOf("selected", "checked") })
        assertTrue(controls.any { it.selectedEditorView == "Raw" })
        assertEquals(1, views.selectedIndex)
        assertTrue(protocol.isSelected)
        assertEquals(0, f.sends)
    }

    private class Fixture {
        val tabs = JTabbedPane()
        val suite = JTabbedPane().apply {
            addTab("Proxy", JPanel())
            addTab("Repeater", JPanel(BorderLayout()).apply { add(tabs) })
        }
        val native = NativeRepeater { suite }
        var sends = 0
        fun removeTargetOnSelection() {
            SwingUtilities.invokeAndWait {
                tabs.selectedIndex = 0
                var removed = false
                tabs.addChangeListener {
                    if (!removed && tabs.selectedIndex == 1) {
                        removed = true
                        tabs.removeTabAt(1)
                    }
                }
            }
        }
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
