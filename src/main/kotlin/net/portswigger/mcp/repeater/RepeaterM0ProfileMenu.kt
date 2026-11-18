package net.portswigger.mcp.repeater

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.portswigger.mcp.Swing
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType
import java.awt.Component
import java.util.Locale
import javax.accessibility.AccessibleRole
import javax.accessibility.AccessibleState
import javax.swing.*

internal class M0ProfileMenuInvokerLease(val ownership: M0ProfileMenuOwnership, val stillValid: () -> Boolean)

@Serializable
internal data class M0ProfileMenuOwnership(val nativeTabIdentityValid: Boolean, val pairedOwnersValid: Boolean,
    val invokerWithinTab: Boolean, val invokerWithinRequest: Boolean, val invokerWithinResponse: Boolean,
    val capturedActionBarPresent: Boolean, val invokerWithinActionBar: Boolean, val invokerIsActionBar: Boolean)

/** A successful approval is scoped to this read and never prompts again. */
internal suspend fun approveM0ProfileMenuRead(config: McpConfig): () -> Boolean {
    fun policy() = config.requireDataAccessApproval to config.alwaysAllowRepeater
    val before = policy()
    check(DataAccessSecurity.checkDataAccessPermission(DataAccessType.REPEATER, config)) {
        "Repeater data access denied by Burp Suite."
    }
    val approved = policy()
    // A one-time approval leaves both flags unchanged. The real approval handler
    // can also grant persistent Repeater access while the prompt is open.
    val granted = approved == before || before == (true to false) && approved == (true to true)
    return { granted && policy() == approved }
}

/** Reads an already manually opened menu. It never opens, selects, hides or clears one. */
internal suspend fun inspectM0ProfileMenu(native: NativeRepeater, tabId: String,
    permission: () -> Boolean = { true },
    menuPath: () -> Array<MenuElement> = { MenuSelectionManager.defaultManager().selectedPath }): M0ProfileMenuObservation {
    if (!permission()) return unavailableProfileMenu(tabId, "PERMISSION_DENIED")
    val target = try {
        native.modelContextTarget(tabId)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        return unavailableProfileMenu(tabId, "NATIVE_TARGET_CAPTURE_UNCONFIRMED")
    }
    return inspectM0ProfileMenuTarget(target, permission, menuPath)
}

private fun unavailableProfileMenu(tabId: String, reason: String) =
    M0ProfileMenuObservation("unknown", reason, tabId, null, null, emptyList(), 0, 0, 0, false, false, false)

internal suspend fun inspectM0ProfileMenuTarget(target: M0ContextTarget, permission: () -> Boolean = { true },
    menuPath: () -> Array<MenuElement> = { MenuSelectionManager.defaultManager().selectedPath }): M0ProfileMenuObservation =
    withContext(Dispatchers.Swing) {
        val context = currentCoroutineContext()
        var ownership: M0ProfileMenuOwnership? = null
        var opener: M0ProfileMenuOpener? = null
        var count = 0
        var unknown = 0
        var lazy = 0
        var capped = false
        val nodes = mutableListOf<M0ProfileMenuNode>()
        fun result(status: String, reason: String, stable: Boolean = false) = M0ProfileMenuObservation(
            status, reason, target.tabId, ownership, opener.takeIf { stable }, if (stable) nodes.toList() else emptyList(),
            count, unknown, lazy, capped, status == "observed_discovery_only",
            stable && hasProfileSignature(nodes))
        try {
            context.ensureActive()
            if (!permission()) return@withContext result("unknown", "PERMISSION_DENIED")
            val initialPath = menuPath()
            if (initialPath.size > 16) return@withContext result("unknown", "MENU_PATH_LIMIT")
            val path = initialPath.copyOf()
            val root = path.firstOrNull() as? JPopupMenu
                ?: return@withContext result("unknown", "NO_ROOT_POPUP_PATH")
            val invoker = root.invoker ?: return@withContext result("unknown", "POPUP_INVOKER_UNAVAILABLE")
            if (!invoker.isShowing) return@withContext result("unknown", "POPUP_INVOKER_NOT_SHOWING")
            val lease = target.captureProfileMenuInvoker?.invoke(invoker)
                ?: return@withContext result("unknown", "INVOKER_BINDING_UNAVAILABLE")
            ownership = lease.ownership
            val owner = lease.ownership
            if (!owner.nativeTabIdentityValid || !owner.pairedOwnersValid || !owner.invokerWithinTab ||
                owner.invokerWithinRequest || owner.invokerWithinResponse || !owner.capturedActionBarPresent ||
                !owner.invokerWithinActionBar || !lease.stillValid())
                return@withContext result("unknown", "NATIVE_INVOKER_OWNERSHIP_UNCONFIRMED")
            fun samePath(now: Array<MenuElement>) = now.size == path.size && now.indices.all { now[it] === path[it] }
            val openPopups = path.filterIsInstance<JPopupMenu>()
            fun ownedVisiblePath() = openPopups.all { it.isVisible && it.isShowing } &&
                path.asList().zipWithNext().all { (parent, child) ->
                    when (parent) {
                        is JPopupMenu -> child is JMenuItem && child.parent === parent
                        // Unlike getPopupMenu(), JMenu.getSubElements() reports
                        // only an already-materialized popup.
                        is JMenu -> child is JPopupMenu && parent.subElements.singleOrNull() === child &&
                            child.invoker === parent
                        else -> false
                    }
                }
            fun guard() {
                context.ensureActive()
                if (!permission()) throw MenuStop("PERMISSION_CHANGED")
                if (!samePath(menuPath()) || root.invoker !== invoker || !lease.stillValid() || !ownedVisiblePath() ||
                    !invoker.isShowing || !samePath(menuPath()) || root.invoker !== invoker)
                    throw MenuStop("MENU_OR_NATIVE_OWNER_CHANGED")
            }
            fun <T> read(get: () -> T): T { guard(); val value = get(); guard(); return value }
            if (!ownedVisiblePath() || openPopups.distinct().size != openPopups.size)
                return@withContext result("unknown", "SELECTED_PATH_NOT_OWNED_BY_POPUP")
            opener = openerMetadata(invoker, ::guard)
            val validations = mutableListOf<() -> Boolean>()
            var restoreLabelCount = 0
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Component, Boolean>())
            fun walk(popup: JPopupMenu, parentKeys: List<String>, depth: Int) {
                if (depth > 6) { capped = true; return }
                val children = read { popup.components }
                if (children.size > 128 - count) capped = true
                val frozenChildren = children.take((128 - count).coerceAtLeast(0))
                val originalCount = children.size
                validations += { read { popup.components }.let { now -> now.size == originalCount &&
                    frozenChildren.indices.all { now[it] === frozenChildren[it] } } }
                for (child in children.take((128 - count).coerceAtLeast(0))) {
                    guard()
                    if (count >= 128) { capped = true; break }
                    count++
                    if (!seen.add(child)) throw MenuStop("CYCLIC_OR_SHARED_MENU_COMPONENT")
                    if (read { child.parent !== popup }) throw MenuStop("MENU_CHILD_OWNER_UNCONFIRMED")
                    if (child is JSeparator) continue
                    val text = read { menuText(child) }
                    val restoreLabel = isRestoreLabel(text)
                    if (restoreLabel) restoreLabelCount++
                    val restoreLeaf = restoreLabel && popup === root &&
                        (child is JButton || child is JMenuItem && child !is JMenu &&
                            child !is JCheckBoxMenuItem && child !is JRadioButtonMenuItem)
                    val restoreVisible = restoreLeaf && read { child.isVisible && child.isShowing }
                    val restoreName = if (restoreVisible) read { child.accessibleContext?.accessibleName } else null
                    val restore = restoreVisible && (restoreName.isNullOrBlank() || isRestoreLabel(restoreName))
                    val label = if (restore) RESTORE_KEY else menuKey(text)
                    val kind = publicKind(child)
                    val enabled = read { child.isEnabled }
                    val selected = read { (child as? AbstractButton)?.isSelected }
                    val checked = read { itemState(child, AccessibleState.CHECKED) }
                    val accessibleSelected = read { itemState(child, AccessibleState.SELECTED) }
                    if (label == null || !restore && kind !in setOf("MENU", "CHECKBOX", "RADIO", "ITEM") ||
                        label in VALUE_KEYS && kind !in setOf("CHECKBOX", "RADIO")) unknown++
                    if (label != null) {
                        nodes += if (restore) M0ProfileMenuNode(label, parentKeys, kind, enabled, null, null, null,
                            "visible_restore_action_enabled_state")
                        else M0ProfileMenuNode(label, parentKeys, kind, enabled, selected, checked, accessibleSelected,
                            if (kind in setOf("CHECKBOX", "RADIO")) "public_check_or_radio_model" else "navigation_or_custom_semantics_unverified")
                    }
                    validations += {
                        read { child.parent === popup } && read {
                            val current = menuText(child)
                            if (restoreLabel) current == text else !isRestoreLabel(current) && menuKey(current) == label
                        } &&
                            (!restoreLeaf || read { (child.isVisible && child.isShowing) == restoreVisible }) &&
                            (!restoreVisible || read { child.accessibleContext?.accessibleName == restoreName }) &&
                            read { child.isEnabled == enabled } && read { (child as? AbstractButton)?.isSelected == selected } &&
                            read { itemState(child, AccessibleState.CHECKED) == checked } &&
                            read { itemState(child, AccessibleState.SELECTED) == accessibleSelected }
                    }
                    if (child is JMenu) {
                        // JMenu popup and child enumeration accessors can allocate.
                        // Only use a popup already present on the selected path.
                        val index = path.indexOfFirst { it === child }
                        val submenu = if (index >= 0) path.getOrNull(index + 1) as? JPopupMenu else null
                        if (submenu == null) lazy++
                        else {
                            if (read { submenu.componentCount == 0 }) lazy++
                            walk(submenu, (parentKeys + (label ?: "UNKNOWN_PARENT")).take(6), depth + 1)
                        }
                    }
                }
            }
            walk(root, emptyList(), 0)
            if (openerMetadata(invoker, ::guard) != opener) return@withContext result("unknown", "OPENER_ATTRIBUTES_CHANGED")
            if (!validations.all { it() }) return@withContext result("unknown", "MENU_STATE_CHANGED_DURING_READ")
            guard()
            if (restoreLabelCount > 1) return@withContext result("unknown", "AMBIGUOUS_MENU_STATE")
            if (capped) nodes.removeAll { it.key == RESTORE_KEY }
            if (ambiguousMenu(nodes)) return@withContext result("unknown", "AMBIGUOUS_MENU_STATE")
            val complete = !capped && lazy == 0 && unknown == 0 && hasProfileSignature(nodes) &&
                nodes.count { it.parentKeys == listOf("FOLLOW_REDIRECTIONS") && it.key in REDIRECT_KEYS && it.selectedModel == true } == 1
            result(if (complete) "observed_discovery_only" else "unknown",
                if (complete) "PUBLIC_MENU_STATE_OBSERVED_NOT_EFFECTIVE_PROFILE_PROOF" else "INCOMPLETE_CUSTOM_LAZY_OR_UNKNOWN_MENU_STATE", true)
        } catch (cancel: CancellationException) { throw cancel }
        catch (stop: MenuStop) { result("unknown", stop.code) }
        catch (_: Exception) { result("unknown", "PUBLIC_MENU_ACCESS_UNAVAILABLE") }
    }

private class MenuStop(val code: String) : RuntimeException()

private val REDIRECT_KEYS = setOf("NEVER", "ALWAYS", "ON_SITE_ONLY", "IN_SCOPE_ONLY")
private val EXCLUSIVE_KEYS = listOf(REDIRECT_KEYS, setOf("HTTP1", "HTTP1_1", "HTTP2"),
    setOf("SEND_CURRENT_TAB", "SEND_GROUP_SINGLE_CONNECTION", "SEND_GROUP_SEPARATE_CONNECTIONS", "SEND_GROUP_PARALLEL"))

private fun hasProfileSignature(nodes: List<M0ProfileMenuNode>) =
    nodes.count { it.parentKeys.isEmpty() && it.key == "UPDATE_CONTENT_LENGTH" && it.publicKind == "CHECKBOX" } == 1 &&
        nodes.count { it.parentKeys.isEmpty() && it.key == "FOLLOW_REDIRECTIONS" && it.publicKind == "MENU" } == 1

private fun ambiguousMenu(nodes: List<M0ProfileMenuNode>) =
    nodes.distinctBy { it.parentKeys to it.key }.size != nodes.size ||
        nodes.groupBy { it.parentKeys }.values.any { siblings ->
            EXCLUSIVE_KEYS.any { keys ->
                val options = siblings.filter { it.key in keys }
                options.isNotEmpty() && options.count { it.selectedModel == true } != 1
            }
        }

private fun itemState(item: Component, state: AccessibleState): Boolean? =
    if (item is JCheckBoxMenuItem || item is JRadioButtonMenuItem)
        item.accessibleContext?.accessibleStateSet?.contains(state) else null

private fun publicKind(c: Component): String = when (c) {
    is JMenu -> "MENU"; is JCheckBoxMenuItem -> "CHECKBOX"; is JRadioButtonMenuItem -> "RADIO"
    is JMenuItem -> "ITEM"; is AbstractButton -> "BUTTON"; is JLabel -> "LABEL"
    is JPopupMenu -> "POPUP"; is JComponent -> "JCOMPONENT"; else -> "COMPONENT"
}

private fun openerMetadata(c: Component, guard: () -> Unit): M0ProfileMenuOpener {
    fun <T> read(get: () -> T): T { guard(); val value = get(); guard(); return value }
    val role = read { c.accessibleContext?.accessibleRole }
    return M0ProfileMenuOpener(publicKind(c), when (role) {
        AccessibleRole.PUSH_BUTTON -> "PUSH_BUTTON"; AccessibleRole.TOGGLE_BUTTON -> "TOGGLE_BUTTON"
        AccessibleRole.LABEL -> "LABEL"; AccessibleRole.PANEL -> "PANEL"; else -> "OTHER_OR_UNAVAILABLE"
    }, read { openerKey((c as? AbstractButton)?.text ?: (c as? JLabel)?.text) },
        read { openerKey(c.accessibleContext?.accessibleName) }, read { openerKey(c.accessibleContext?.accessibleDescription) },
        read { openerKey((c as? JComponent)?.toolTipText) }, read { openerKey((c as? AbstractButton)?.actionCommand) },
        read { c.isEnabled }, read { c.isShowing })
}

private fun openerKey(text: String?): String? = when (text?.trim()?.lowercase(Locale.ROOT)) {
    "settings", "repeater settings", "tab settings", "tab-specific settings" -> "SETTINGS_SEMANTIC_CANDIDATE"
    else -> null
}

private fun menuKey(text: String?): String? = MENU_KEYS[text?.trim()?.lowercase(Locale.ROOT)]
private fun menuText(child: Component): String? = (child as? AbstractButton)?.text ?: (child as? JLabel)?.text
private const val RESTORE_KEY = "RESTORE_GLOBAL_DEFAULT"
private fun isRestoreLabel(text: String?) = when (text?.trim()?.lowercase(Locale.ROOT)) {
    "restore global default", "restore global defaults" -> true
    else -> false
}
private val VALUE_KEYS = setOf("UPDATE_CONTENT_LENGTH", "NORMALIZE_LINE_ENDINGS", "NEVER", "ALWAYS", "ON_SITE_ONLY",
    "IN_SCOPE_ONLY", "PROCESS_REDIRECT_COOKIES", "HTTP2_ALPN_OVERRIDE", "HTTP1_CONNECTION_REUSE", "HTTP2_CONNECTION_REUSE",
    "STRIP_HTTP2_CONNECTION", "HTTP1", "HTTP1_1", "HTTP2", "SEND_CURRENT_TAB", "SEND_GROUP_SINGLE_CONNECTION",
    "SEND_GROUP_SEPARATE_CONNECTIONS", "SEND_GROUP_PARALLEL")
private val MENU_KEYS = mapOf(
    "update content-length" to "UPDATE_CONTENT_LENGTH", "normalize line endings" to "NORMALIZE_LINE_ENDINGS",
    "normalize http/1 line endings" to "NORMALIZE_LINE_ENDINGS", "follow redirections" to "FOLLOW_REDIRECTIONS",
    "never" to "NEVER", "always" to "ALWAYS", "on-site only" to "ON_SITE_ONLY", "in-scope only" to "IN_SCOPE_ONLY",
    "process cookies in redirections" to "PROCESS_REDIRECT_COOKIES",
    "allow http/2 alpn override" to "HTTP2_ALPN_OVERRIDE", "enable http/1 connection reuse" to "HTTP1_CONNECTION_REUSE",
    "enable http/2 connection reuse" to "HTTP2_CONNECTION_REUSE", "strip connection header over http/2" to "STRIP_HTTP2_CONNECTION",
    "remove connection header over http/2" to "STRIP_HTTP2_CONNECTION", "http/1" to "HTTP1", "http/1.1" to "HTTP1_1", "http/2" to "HTTP2",
    "send (current tab)" to "SEND_CURRENT_TAB", "send group in sequence (single connection)" to "SEND_GROUP_SINGLE_CONNECTION",
    "send group in sequence (separate connections)" to "SEND_GROUP_SEPARATE_CONNECTIONS", "send group in parallel" to "SEND_GROUP_PARALLEL"
)

@Serializable
internal data class M0ProfileMenuOpener(val publicKind: String, val accessibleRole: String,
    val textSemantic: String?, val accessibleNameSemantic: String?, val descriptionSemantic: String?,
    val tooltipSemantic: String?, val actionCommandSemantic: String?, val enabled: Boolean, val showing: Boolean)

@Serializable
internal data class M0ProfileMenuNode(val key: String, val parentKeys: List<String>, val publicKind: String,
    val enabled: Boolean, val selectedModel: Boolean?, val accessibleChecked: Boolean?, val accessibleSelected: Boolean?,
    val selectionMeaning: String)

@Serializable
internal data class M0ProfileMenuObservation(val status: String, val reason: String, val tabId: String,
    val ownership: M0ProfileMenuOwnership?, val opener: M0ProfileMenuOpener?, val recognizedNodes: List<M0ProfileMenuNode>,
    val visitedNodeCount: Int, val unknownNodeCount: Int, val emptyOrLazySubmenuCount: Int, val capped: Boolean,
    val completePublicTreeObserved: Boolean, val recognizedProfileMenuSignature: Boolean,
    val effectiveProfileVerified: Boolean = false, val inheritanceVerified: Boolean = false,
    val automaticOpenerSupported: Boolean = false, val menuMutationAttempted: Boolean = false)
