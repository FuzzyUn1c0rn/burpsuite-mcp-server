package net.portswigger.mcp.repeater

import kotlinx.coroutines.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.*
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

class RepeaterM0ProfileMenuTest {
    private class ShowingPopup : JPopupMenu() {
        var showing = true
        override fun isShowing() = showing
        override fun isVisible() = showing
    }
    private class HookCheckBox(text: String) : JCheckBoxMenuItem(text, true) {
        var textHook: (() -> Unit)? = null
        var selectedReads = 0
        override fun getText(): String? { textHook?.invoke(); return super.getText() }
        override fun isSelected(): Boolean { selectedReads++; return super.isSelected() }
    }
    private class HookGear : JButton("Settings") {
        var tooltipHook: (() -> Unit)? = null
        override fun isShowing() = isVisible
        override fun getToolTipText(): String? {
            tooltipHook?.invoke()
            return super.getToolTipText()
        }
    }
    private class RestoreItem(text: String) : JMenuItem(text) {
        override fun isShowing() = isVisible
    }
    private class PassiveMenu(text: String) : JMenu(text) {
        fun materializeForTest() = super.getPopupMenu()
        override fun getPopupMenu(): JPopupMenu = error("Inspection must not call getPopupMenu")
    }

    private val cleanupPopups = mutableListOf<() -> Unit>()

    @AfterEach
    fun releaseHeadlessPopups() {
        SwingUtilities.invokeAndWait { cleanupPopups.asReversed().forEach { it() } }
    }

    private fun showOwnedPopup(menu: PassiveMenu): JPopupMenu {
        check(GraphicsEnvironment.isHeadless()) {
            "The popup fixture requires a headless test JVM. Run Gradle test or set -Djava.awt.headless=true on the test JVM."
        }
        val popup = menu.materializeForTest()
        SwingUtilities.invokeAndWait {
            // A displayable headless parent lets the real JMenu-owned popup
            // report showing without creating a window.
            val host = JPanel()
            host.addNotify()
            host.add(popup)
            popup.isVisible = true
            cleanupPopups += {
                popup.isVisible = false
                host.removeNotify()
            }
        }
        assertSame(popup, menu.subElements.single())
        assertTrue(popup.isVisible && popup.isShowing)
        return popup
    }

    private inner class Fixture {
        val gear = HookGear().apply {
            toolTipText = "PRIVATE_TOOLTIP"; accessibleContext.accessibleName = "Repeater settings"
            accessibleContext.accessibleDescription = "PRIVATE_DESCRIPTION"; actionCommand = "PRIVATE_COMMAND"
        }
        val root = ShowingPopup().apply { invoker = gear }
        val update = HookCheckBox("Update Content-Length")
        val follow = PassiveMenu("Follow redirections")
        val redirectPopup = showOwnedPopup(follow)
        val never = JRadioButtonMenuItem("Never", true).apply { isEnabled = false }
        val always = JRadioButtonMenuItem("Always", false)
        val restore = RestoreItem("Restore global default").apply { isEnabled = false }
        var owned = true
        var permitted = true
        var mutations = 0
        var path: Array<MenuElement> = arrayOf(root, follow, redirectPopup)
        val target = M0ContextTarget("tab", "Hex", captureProfileMenuInvoker = { invoker ->
            val inside = invoker === gear
            M0ProfileMenuInvokerLease(M0ProfileMenuOwnership(true, true, inside, false, false, true, inside, false)) {
                owned && invoker === gear
            }
        }) { false }
        init {
            root.add(update); root.add(follow); redirectPopup.add(never); redirectPopup.add(always)
            root.add(restore)
            root.addPopupMenuListener(object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) { mutations++ }
                override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) { mutations++ }
                override fun popupMenuCanceled(e: PopupMenuEvent) { mutations++ }
            })
            listOf(update, follow, never, always, restore).forEach {
                it.addActionListener { mutations++ }; it.model.addChangeListener { mutations++ }
            }
            update.selectedReads = 0
        }
        suspend fun read() = inspectM0ProfileMenuTarget(target, { permitted }, { path })
    }

    @Test fun `singular and plural visible restore leaves report only their enabled state`() = runBlocking {
        for (caption in listOf("Restore global default", "Restore global defaults")) {
            for (enabled in listOf(false, true)) {
                val f = Fixture()
                f.restore.text = caption
                f.restore.isEnabled = enabled
                val mutationsBeforeRead = f.mutations
                val result = f.read()
                val node = result.recognizedNodes.single { it.key == "RESTORE_GLOBAL_DEFAULT" }
                assertEquals(enabled, node.enabled)
                assertTrue(node.parentKeys.isEmpty())
                assertEquals("visible_restore_action_enabled_state", node.selectionMeaning)
                assertNull(node.selectedModel)
                assertFalse(result.inheritanceVerified || result.effectiveProfileVerified)
                assertEquals(mutationsBeforeRead, f.mutations)
            }
        }
    }

    @Test fun `restore state stays unknown for misleading or unstable candidates`() = runBlocking {
        for (scenario in listOf("suffix", "hidden", "nested", "label", "duplicate", "contradiction", "wrong-owner", "stale")) {
            val f = Fixture()
            f.restore.text = "Restore global defaults"
            when (scenario) {
                "suffix" -> f.restore.text += " extra"
                "hidden" -> f.restore.isVisible = false
                "nested" -> f.redirectPopup.add(f.restore)
                "label" -> {
                    f.root.remove(f.restore)
                    f.root.add(JLabel("Restore global defaults"))
                }
                "duplicate" -> f.root.add(RestoreItem("Restore global default"))
                "contradiction" -> f.restore.accessibleContext.accessibleName = "Another action"
                "wrong-owner" -> f.root.invoker = HookGear()
                "stale" -> {
                    var reads = 0
                    f.gear.tooltipHook = { if (++reads == 2) f.restore.isEnabled = true }
                }
            }
            val result = f.read()
            assertEquals("unknown", result.status, scenario)
            assertTrue(result.recognizedNodes.none { it.key == "RESTORE_GLOBAL_DEFAULT" }, scenario)
            assertFalse(result.inheritanceVerified || result.menuMutationAttempted, scenario)
        }
    }

    @Test fun `popup with only an invoker pointer cannot stand in for the menus owned popup`() = runBlocking {
        val f = Fixture()
        val borrowed = ShowingPopup().apply {
            invoker = f.follow
            add(JRadioButtonMenuItem("Never", true))
        }
        f.path = arrayOf(f.root, f.follow, borrowed)
        val result = f.read()
        assertEquals("SELECTED_PATH_NOT_OWNED_BY_POPUP", result.reason)
        assertTrue(result.recognizedNodes.isEmpty())
        assertSame(f.redirectPopup, f.follow.subElements.single())

        val unopened = PassiveMenu("Follow redirections")
        f.root.remove(f.follow)
        f.root.add(unopened)
        borrowed.invoker = unopened
        f.path = arrayOf(f.root, unopened, borrowed)
        assertEquals("SELECTED_PATH_NOT_OWNED_BY_POPUP", f.read().reason)
        assertTrue(unopened.subElements.isEmpty())
    }

    @Test fun `showing popup does not authorize an inspection from a hidden opener`() = runBlocking {
        val f = Fixture()
        f.gear.isVisible = false
        val result = f.read()
        assertTrue(f.root.isShowing)
        assertEquals("POPUP_INVOKER_NOT_SHOWING", result.reason)
        assertTrue(result.recognizedNodes.isEmpty())
        assertNull(result.opener)
    }

    @Test fun `owned full public tree emits exact checked state and semantic metadata without any menu mutation`() = runBlocking {
        val f = Fixture()
        f.path = arrayOf(f.root, f.follow, f.redirectPopup, f.never)
        val result = f.read()
        assertEquals("observed_discovery_only", result.status)
        assertTrue(result.completePublicTreeObserved && result.recognizedProfileMenuSignature)
        val never = result.recognizedNodes.single { it.key == "NEVER" }
        assertFalse(never.enabled)
        assertEquals(true, never.selectedModel)
        assertEquals("RADIO", never.publicKind)
        assertEquals(listOf("FOLLOW_REDIRECTIONS"), never.parentKeys)
        assertEquals(false, result.recognizedNodes.single { it.key == "ALWAYS" }.selectedModel)
        assertFalse(result.effectiveProfileVerified || result.inheritanceVerified || result.automaticOpenerSupported || result.menuMutationAttempted)
        assertEquals("SETTINGS_SEMANTIC_CANDIDATE", result.opener?.accessibleNameSemantic)
        val json = Json.encodeToString(result)
        assertFalse(json.contains("PRIVATE_"))
        assertEquals(0, f.mutations)
        assertSame(f.gear, f.root.invoker)
        assertTrue(f.root.isVisible)
    }

    @Test fun `same-caption foreign invoker or unrelated menu path refuses without exposing nodes`() = runBlocking {
        for (scenario in listOf("foreign", "path", "absent", "binding")) {
            val f = Fixture()
            when (scenario) {
                "foreign" -> f.root.invoker = JButton("Settings")
                "path" -> f.path = arrayOf(f.root, JMenuItem("Never"))
                "absent" -> f.path = emptyArray()
                "binding" -> f.owned = false
            }
            val result = f.read()
            assertEquals("unknown", result.status, scenario)
            assertTrue(result.recognizedNodes.isEmpty(), scenario)
            assertFalse(result.effectiveProfileVerified, scenario)
            assertEquals(0, f.mutations, scenario)
        }
    }

    @Test fun `unknown custom plain-value and lazy submenu states stay unknown without selecting or opening`() = runBlocking {
        for (scenario in listOf("unknown", "custom", "lazy", "plain_checkbox", "suffix")) {
            val f = Fixture()
            when (scenario) {
                "unknown" -> f.root.add(JMenuItem("PRIVATE_RULE_NAME"))
                "custom" -> f.root.add(JLabel("Always"))
                "lazy" -> f.path = arrayOf(f.root)
                "plain_checkbox" -> { f.root.remove(f.update); f.root.add(JMenuItem("Update Content-Length")) }
                "suffix" -> f.root.add(JCheckBoxMenuItem("Always PRIVATE_SUFFIX"))
            }
            val result = f.read()
            assertEquals("unknown", result.status, scenario)
            assertFalse(result.completePublicTreeObserved, scenario)
            assertFalse(Json.encodeToString(result).contains("PRIVATE_"), scenario)
            assertEquals(0, f.mutations, scenario)
        }
    }

    @Test fun `getter reentrant ownership permission or popup takeover stops before later state getter`() = runBlocking {
        for (scenario in listOf("owner", "permission", "invoker", "path", "root_hidden", "submenu_hidden", "opener_hidden")) {
            val f = Fixture()
            f.update.textHook = { when (scenario) {
                "owner" -> f.owned = false
                "permission" -> f.permitted = false
                "invoker" -> f.root.invoker = JButton("Settings")
                "path" -> f.path = arrayOf(ShowingPopup().apply { invoker = f.gear })
                "root_hidden" -> f.root.showing = false
                "submenu_hidden" -> f.redirectPopup.isVisible = false
                "opener_hidden" -> f.gear.isVisible = false
            } }
            val result = f.read()
            assertEquals("unknown", result.status, scenario)
            assertTrue(result.recognizedNodes.isEmpty(), scenario)
            assertEquals(0, f.update.selectedReads, scenario)
            assertEquals(0, f.mutations, scenario)
        }
    }

    @Test fun `changing recognized state during final read returns unknown without partial profile`() = runBlocking {
        val f = Fixture()
        var reads = 0
        f.update.textHook = { if (++reads == 2) f.always.isSelected = true }
        val result = f.read()
        assertEquals("unknown", result.status)
        assertTrue(result.recognizedNodes.isEmpty())
        assertEquals("MENU_STATE_CHANGED_DURING_READ", result.reason)
        assertFalse(result.effectiveProfileVerified)
    }

    @Test fun `large and deep public trees are bounded and unverified`() = runBlocking {
        for (deep in listOf(false, true)) {
            val f = Fixture()
            if (!deep) repeat(180) { f.root.add(JCheckBoxMenuItem("Normalize line endings")) }
            else {
                var parent = f.redirectPopup
                repeat(6) {
                    val menu = PassiveMenu("Follow redirections")
                    parent.add(menu)
                    parent = showOwnedPopup(menu)
                    f.path += arrayOf<MenuElement>(menu, parent)
                }
                parent.add(JRadioButtonMenuItem("Never"))
            }
            val result = f.read()
            assertEquals("unknown", result.status)
            assertTrue(result.capped)
            assertTrue(result.visitedNodeCount <= 128)
            assertTrue(result.recognizedNodes.size <= 128)
            assertTrue(result.recognizedNodes.all { it.parentKeys.size <= 6 })
            assertFalse(result.completePublicTreeObserved)
            assertEquals(0, f.mutations)
        }
    }

    @Test fun `late opener accessor changes to earlier menu state invalidate the observation`() = runBlocking {
        val f = Fixture()
        var reads = 0
        f.gear.tooltipHook = { if (++reads == 2) f.never.isSelected = false }
        val result = f.read()
        assertEquals("MENU_STATE_CHANGED_DURING_READ", result.reason)
        assertTrue(result.recognizedNodes.isEmpty())
        assertNull(result.opener)
    }

    @Test fun `duplicate signatures and missing or competing redirect selections are ambiguous`() = runBlocking {
        for (scenario in listOf("duplicate", "none", "two")) {
            val f = Fixture()
            when (scenario) {
                "duplicate" -> f.root.add(JCheckBoxMenuItem("Update Content-Length"))
                "none" -> f.never.isSelected = false
                "two" -> f.always.isSelected = true
            }
            val result = f.read()
            assertEquals("AMBIGUOUS_MENU_STATE", result.reason, scenario)
            assertTrue(result.recognizedNodes.isEmpty(), scenario)
            assertFalse(result.completePublicTreeObserved || result.recognizedProfileMenuSignature, scenario)
        }
    }

    @Test fun `native capture failure is sanitized and cancellation propagates`() = runBlocking {
        val native = mockk<NativeRepeater>()
        coEvery { native.modelContextTarget("tab") } throws IllegalStateException("PRIVATE_NATIVE_EXCEPTION")
        val result = inspectM0ProfileMenu(native, "tab")
        assertEquals("NATIVE_TARGET_CAPTURE_UNCONFIRMED", result.reason)
        assertFalse(Json.encodeToString(result).contains("PRIVATE_"))
        coEvery { native.modelContextTarget("tab") } throws CancellationException()
        val failure = runCatching { inspectM0ProfileMenu(native, "tab") }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }

    @Test fun `public accessor failures never disclose exception or infer safe defaults`() = runBlocking {
        val f = Fixture()
        f.update.textHook = { error("PRIVATE_ACCESSOR_EXCEPTION") }
        val result = f.read()
        assertEquals("unknown", result.status)
        assertEquals("PUBLIC_MENU_ACCESS_UNAVAILABLE", result.reason)
        assertTrue(result.recognizedNodes.isEmpty())
        assertFalse(Json.encodeToString(result).contains("PRIVATE_"))
        assertEquals(0, f.mutations)
    }

    @Test fun `cancellation during public getter stops subsequent reads and performs no cleanup mutation`() = runBlocking {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        f.update.textHook = { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
        val call = async(Dispatchers.Default) { f.read() }
        try { entered.await(); call.cancel() } finally { release.countDown() }
        call.join()
        assertEquals(0, f.update.selectedReads)
        assertEquals(0, f.mutations)
        assertTrue(f.root.isVisible)
    }
}
