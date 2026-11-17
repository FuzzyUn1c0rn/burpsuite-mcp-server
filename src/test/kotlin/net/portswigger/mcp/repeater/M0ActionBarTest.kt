package net.portswigger.mcp.repeater

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Component
import javax.swing.*

class M0ActionBarTest {
    private class Fixture {
        val root = JPanel()
        val request = JPanel()
        val response = JPanel()
        val bar = JPanel()
        val send = JButton("Send")
        val target = JLabel("Target: https://example.test:8443")
        val cancel = JButton("Cancel").apply { isEnabled = false }
        // Headless stand-in for effective showing, including ancestor visibility.
        val showing: (Component) -> Boolean = { c ->
            val path = generateSequence(c) { if (it === root) null else it.parent }.toList()
            path.lastOrNull() === root && path.all { it.isVisible }
        }
        init {
            root.add(request); root.add(response); root.add(bar)
            bar.add(send); bar.add(target); bar.add(cancel)
            listOf(root, request, response, bar, send, target, cancel).forEach { it.setSize(100, 30) }
        }
        fun capture() = M0ActionBar.capture(root, request, response, showing)
    }

    @Test
    fun `positive bar excludes editor Cancel and cached hidden controls and binds destination`() {
        val f = Fixture()
        f.request.add(JButton("Cancel"))
        f.response.add(JButton("Cancel"))
        f.root.add(JPanel().apply { isVisible = false; add(JButton("Send")); add(JLabel("Target: http://private.test")) })
        val guard = checkNotNull(f.capture())
        assertTrue(guard.ready())
        assertTrue(guard.matchesService("EXAMPLE.TEST", 8443, true))
        assertFalse(guard.matchesService("example.test", 443, true))
        assertFalse(guard.matchesService("example.test", 8443, false))
        assertFalse(guard.matchesService("different.test", 8443, true))
    }

    @Test
    fun `label Send requires frozen usable wrapper and follows simulated busy transitions`() {
        val f = Fixture()
        f.bar.remove(f.send)
        val label = JLabel("Send").apply { setSize(40, 20) }
        val wrapper = JPanel().apply {
            add(label); setSize(100, 30)
            addMouseListener(object : java.awt.event.MouseAdapter() {})
        }
        f.bar.add(wrapper)
        val guard = checkNotNull(f.capture())
        assertTrue(guard.ready())
        f.cancel.isEnabled = true
        assertFalse(guard.ready())
        f.cancel.isEnabled = false
        assertTrue(guard.ready())
        wrapper.removeMouseListener(wrapper.mouseListeners.single())
        assertFalse(guard.ready())
        assertNull(f.capture())
    }

    @Test
    fun `unprovable or ambiguous bar never admitted`() {
        for (scenario in listOf("whole_leaf", "nested_request", "contains_request", "duplicate_send", "duplicate_target",
            "duplicate_cancel", "enabled_cancel", "disabled_send", "hidden_send", "missing_cancel", "zero_size", "invalid_target", "duplicate_bar")) {
            val f = Fixture()
            when (scenario) {
                "whole_leaf" -> f.root.add(f.target)
                "nested_request" -> f.request.add(f.bar)
                "contains_request" -> f.bar.add(f.request)
                "duplicate_send" -> f.bar.add(JButton("Send"))
                "duplicate_target" -> f.bar.add(JLabel(f.target.text))
                "duplicate_cancel" -> f.bar.add(JButton("Cancel"))
                "enabled_cancel" -> f.cancel.isEnabled = true
                "disabled_send" -> f.send.isEnabled = false
                "hidden_send" -> f.send.isVisible = false
                "missing_cancel" -> f.bar.remove(f.cancel)
                "zero_size" -> f.send.setSize(0, 0)
                "invalid_target" -> f.target.text = "Target: https://example.test:8443/path?secret"
                "duplicate_bar" -> f.root.add(JPanel().apply { add(JButton("Send")); add(JLabel(f.target.text)); add(JButton("Cancel")) })
            }
            assertNull(f.capture(), scenario)
        }
    }

    @Test
    fun `captured controls and paths fail closed on change or pooling`() {
        for (scenario in listOf("mode", "target", "busy", "send_disabled", "hidden_bar", "cancel_hidden", "replacement",
            "send_reparent", "bar_reparent", "other_tab", "duplicate_send", "duplicate_cancel")) {
            val f = Fixture()
            val guard = checkNotNull(f.capture())
            when (scenario) {
                "mode" -> f.send.text = "Send group"
                "target" -> f.target.text = "Target: https://different.test:8443"
                "busy" -> f.cancel.isEnabled = true
                "send_disabled" -> f.send.isEnabled = false
                "hidden_bar" -> f.bar.isVisible = false
                "cancel_hidden" -> f.cancel.isVisible = false
                "replacement" -> { f.bar.remove(f.cancel); f.bar.add(JButton("Cancel").apply { isEnabled = false; setSize(100, 30) }) }
                "send_reparent" -> f.bar.add(JPanel().apply { add(f.send) })
                "bar_reparent" -> f.root.add(JPanel().apply { add(f.bar) })
                "other_tab" -> JPanel().add(f.bar)
                "duplicate_send" -> f.root.add(JButton("Send"))
                "duplicate_cancel" -> f.bar.add(JButton("Cancel"))
            }
            assertFalse(guard.ready(), scenario)
        }
    }
}
