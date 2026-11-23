package io.github.nuke.oss.ui.hostsettings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.settings.HostContactList
import io.github.nuke.oss.app.shared.settings.HostContactsUnavailable
import io.github.nuke.oss.app.shared.settings.McpCall
import io.github.nuke.oss.app.shared.settings.McpConsole
import io.github.nuke.oss.app.shared.settings.McpInputException
import io.github.nuke.oss.app.shared.settings.McpLimits
import io.github.nuke.oss.app.shared.settings.McpListening
import io.github.nuke.oss.app.shared.settings.McpPermission
import io.github.nuke.oss.app.shared.settings.McpProblem
import io.github.nuke.oss.app.shared.settings.McpState
import io.github.nuke.oss.app.shared.settings.McpToken
import io.github.nuke.oss.app.shared.settings.McpTokenDraft
import io.github.nuke.oss.designsystem.CodeText
import io.github.nuke.oss.designsystem.NukeDialog
import io.github.nuke.oss.designsystem.NukeDialogAccentButton
import io.github.nuke.oss.designsystem.NukeDialogTextButton
import io.github.nuke.oss.designsystem.NukeDivider
import io.github.nuke.oss.designsystem.NukeNavRow
import io.github.nuke.oss.designsystem.NukeSettingGroup
import io.github.nuke.oss.designsystem.NukeSettingRow
import io.github.nuke.oss.designsystem.NukeSwitch
import io.github.nuke.oss.designsystem.NukeTextField
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val MCP_SERVER = "McpServer"

private enum class McpPage { MAIN, PORT, CALLS, TOKEN, CHATS, ISSUED }

// The token being made (no ID yet) or changed, with the limit as it is typed.
internal data class McpTokenEdit(val id: String?, val draft: McpTokenDraft, val limit: String = draft.limit.toString()) {
    // What keeps it from being saved, if anything; the host checks the same again.
    val problem: McpProblem?
        get() = when {
            draft.name.isBlank() || draft.name.trim().length > McpLimits.MAX_NAME_CHARS -> McpProblem.NAME
            limit.trim().toIntOrNull()?.takeIf { it in 1..McpLimits.MAX_REQUESTS } == null -> McpProblem.LIMIT
            draft.conversations.size > McpLimits.MAX_CONVERSATIONS -> McpProblem.TOO_MANY_CONVERSATIONS
            else -> null
        }

    // A token that may call nothing, or reach no chat, is valid and useless; the editor says so.
    val idle: Boolean get() = draft.permissions.isEmpty() || (!draft.allowAll && draft.conversations.isEmpty())

    fun checked(): McpTokenDraft = draft.copy(limit = limit.trim().toInt())

    companion object {
        fun of(token: McpToken) = McpTokenEdit(
            token.id, McpTokenDraft(token.name, token.enabled, token.permissions, token.allowAll, token.conversations, token.limit),
        )
    }
}

internal fun mcpProblemText(problem: McpProblem): Int = when (problem) {
    McpProblem.NAME -> R.string.host_mcp_problem_name
    McpProblem.LIMIT -> R.string.host_mcp_problem_limit
    McpProblem.PORT -> R.string.host_mcp_problem_port
    McpProblem.PORT_UNAVAILABLE -> R.string.host_mcp_problem_port_unavailable
    McpProblem.TOO_MANY_TOKENS -> R.string.host_mcp_problem_too_many_tokens
    McpProblem.TOO_MANY_CONVERSATIONS -> R.string.host_mcp_problem_too_many_chats
    McpProblem.NO_ACCOUNT -> R.string.host_mcp_no_account
    McpProblem.NO_TOKEN -> R.string.host_mcp_problem_no_token
    McpProblem.UNAVAILABLE -> R.string.host_mcp_problem_unavailable
}

// The MCP server's own dialog: where it listens, the access tokens with what each may do and reach, and the tool calls
// it answered. Unlike a feature's options, every change here is made in the host at once and needs no restart; only
// the feature switch does. A token's text is shown once, right after it is made or given a new secret.
@Composable
internal fun McpServerDialog(console: McpConsole, loadContacts: suspend () -> HostContactList, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(McpPage.MAIN) }
    var state by remember { mutableStateOf<McpState?>(null) }
    var account by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf<McpTokenEdit?>(null) }
    var issued by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmLan by remember { mutableStateOf(false) }
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // The lists that show what the server did are read again whenever they come into view.
    LaunchedEffect(page) {
        if (page == McpPage.MAIN || page == McpPage.CALLS) {
            state = withContext(Dispatchers.IO) { console.state() }
            account = withContext(Dispatchers.IO) { console.account() }
        }
    }
    LaunchedEffect(Unit) {
        // Names only decorate the record of calls; without them it shows conversation IDs.
        names = try {
            loadContacts().let { list -> list.friends.associate { it.conversationId to it.name } + list.groups.associate { it.conversationId to it.name } }
        } catch (_: HostContactsUnavailable) {
            emptyMap()
        }
    }

    // One change at a time. A refusal by the host stays on the page it happened on, as its notice.
    fun change(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        notice = null
        scope.launch {
            try {
                action()
            } catch (error: McpInputException) {
                notice = context.getString(mcpProblemText(error.problem), error.message.orEmpty())
            } finally {
                busy = false
            }
        }
    }
    fun open(next: McpPage) {
        notice = null
        page = next
    }

    fun setLan(enabled: Boolean) = change { state = withContext(Dispatchers.IO) { console.setLan(enabled) } }

    val current = state
    val editing = edit
    when {
        page == McpPage.PORT && current != null -> PortDialog(current.port, notice, busy, onBack = { open(McpPage.MAIN) }) { port ->
            change {
                state = withContext(Dispatchers.IO) { console.setPort(port) }
                open(McpPage.MAIN)
            }
        }
        page == McpPage.CALLS && current != null -> CallsDialog(current.calls, names) { open(McpPage.MAIN) }
        page == McpPage.CHATS && editing != null -> ConversationPickerDialog(
            title = stringResource(R.string.host_mcp_chosen_chats),
            initial = editing.draft.conversations,
            load = loadContacts,
            onDismiss = { page = McpPage.TOKEN },
        ) { chosen ->
            edit = editing.copy(draft = editing.draft.copy(conversations = chosen))
            page = McpPage.TOKEN
        }
        page == McpPage.TOKEN && editing != null -> TokenDialog(
            edit = editing,
            notice = notice,
            busy = busy,
            onEdit = { edit = it },
            onChats = { page = McpPage.CHATS },
            onBack = { open(McpPage.MAIN) },
            onSave = {
                change {
                    val draft = editing.checked()
                    if (editing.id == null) {
                        val made = withContext(Dispatchers.IO) { console.create(draft) }
                        state = made.state
                        issued = made.text
                        open(McpPage.ISSUED)
                    } else {
                        state = withContext(Dispatchers.IO) { console.update(editing.id, draft) }
                        open(McpPage.MAIN)
                    }
                }
            },
            onRenew = { id ->
                change {
                    val made = withContext(Dispatchers.IO) { console.renew(id) }
                    state = made.state
                    issued = made.text
                    open(McpPage.ISSUED)
                }
            },
            onRevoke = { id ->
                change {
                    state = withContext(Dispatchers.IO) { console.revoke(id) }
                    open(McpPage.MAIN)
                }
            },
        )
        page == McpPage.ISSUED && issued != null -> IssuedDialog(issued.orEmpty(), current?.port ?: McpLimits.DEFAULT_PORT) {
            issued = null
            open(McpPage.MAIN)
        }
        else -> NukeDialog(
            visible = true,
            onDismissRequest = onDismiss,
            title = stringResource(R.string.host_feature_mcp_server),
            modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 560.dp),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            actions = { NukeDialogTextButton(stringResource(R.string.host_packet_close), onDismiss, Modifier.weight(1f)) },
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OptionsBodyText(stringResource(R.string.host_mcp_notice))
                if (current == null) {
                    OptionsBodyText(stringResource(R.string.host_contacts_loading))
                } else {
                    when (val listening = current.listening) {
                        is McpListening.On -> if (current.lan) {
                            OptionsBodyText(stringResource(R.string.host_mcp_listening_lan, listening.port))
                            if (current.addresses.isEmpty()) {
                                OptionsBodyText(stringResource(R.string.host_mcp_no_address), warning = true)
                            } else {
                                val urls = current.addresses.joinToString { "http://$it:${listening.port}/mcp" }
                                SelectionContainer { OptionsBodyText(stringResource(R.string.host_mcp_connect_lan, urls)) }
                            }
                        } else {
                            OptionsBodyText(stringResource(R.string.host_mcp_listening, listening.port))
                            SelectionContainer { OptionsBodyText(stringResource(R.string.host_mcp_connect, listening.port)) }
                        }
                        McpListening.Off -> OptionsBodyText(stringResource(R.string.host_mcp_off), warning = true)
                        is McpListening.Failed -> OptionsBodyText(stringResource(R.string.host_mcp_failed, listening.reason), warning = true)
                    }
                    NukeSettingGroup(title = stringResource(R.string.host_mcp_server_group)) {
                        NukeNavRow(title = stringResource(R.string.host_mcp_port), statusText = current.port.toString(), onClick = { open(McpPage.PORT) })
                        NukeDivider()
                        // Opening the server to a network asks first; closing it does not.
                        SwitchRow(stringResource(R.string.host_mcp_lan), stringResource(R.string.host_mcp_lan_description), current.lan) { on ->
                            if (on) confirmLan = true else setLan(false)
                        }
                        NukeDivider()
                        NukeNavRow(title = stringResource(R.string.host_mcp_calls), statusText = current.calls.size.toString(), onClick = { open(McpPage.CALLS) })
                    }
                    NukeSettingGroup(title = stringResource(R.string.host_mcp_tokens)) {
                        for (token in current.tokens) {
                            NukeNavRow(
                                title = token.name,
                                subtitle = tokenSummary(token, account, current.lastUsed[token.id]),
                                statusText = if (token.enabled) null else stringResource(R.string.host_mcp_token_disabled),
                                onClick = {
                                    edit = McpTokenEdit.of(token)
                                    open(McpPage.TOKEN)
                                },
                            )
                            NukeDivider()
                        }
                        NukeNavRow(
                            title = stringResource(R.string.host_mcp_new_token),
                            enabled = account != null && current.tokens.size < McpLimits.MAX_TOKENS,
                            onClick = {
                                edit = McpTokenEdit(null, McpTokenDraft(name = ""))
                                open(McpPage.TOKEN)
                            },
                        )
                    }
                    if (account == null) OptionsBodyText(stringResource(R.string.host_mcp_no_account), warning = true)
                    notice?.let { OptionsBodyText(it, warning = true) }
                }
            }
        }
    }
    if (confirmLan && page == McpPage.MAIN) {
        NukeDialog(
            onDismissRequest = { confirmLan = false },
            title = stringResource(R.string.host_mcp_lan),
            contentText = stringResource(R.string.host_mcp_lan_message),
            confirmButtonText = stringResource(R.string.host_mcp_confirm),
            dismissButtonText = stringResource(R.string.home_cancel),
            onConfirm = {
                confirmLan = false
                setLan(true)
            },
        )
    }
}

@Composable
private fun tokenSummary(token: McpToken, account: String?, lastUsed: Long?): String {
    val permissions = token.permissions.sorted().map { stringResource(permissionLabel(it).first) }
        .ifEmpty { listOf(stringResource(R.string.host_mcp_no_permission)) }.joinToString()
    val chats = if (token.allowAll) stringResource(R.string.host_mcp_all_chats) else stringResource(R.string.host_mcp_chats_count, token.conversations.size)
    return buildList {
        add(stringResource(R.string.host_mcp_token_summary, permissions, chats, token.limit))
        if (account != null && token.account != account) add(stringResource(R.string.host_mcp_token_other_account))
        if (lastUsed != null) add(stringResource(R.string.host_mcp_token_last_used, time(lastUsed)))
    }.joinToString("\n")
}

private fun permissionLabel(permission: McpPermission): Pair<Int, Int> = when (permission) {
    McpPermission.READ_CONTACTS -> R.string.host_mcp_permission_read_contacts to R.string.host_mcp_permission_read_contacts_description
    McpPermission.SEND_TEXT -> R.string.host_mcp_permission_send_text to R.string.host_mcp_permission_send_text_description
    McpPermission.SEND_IMAGE -> R.string.host_mcp_permission_send_image to R.string.host_mcp_permission_send_image_description
    McpPermission.CLAIM_REWARDS -> R.string.host_mcp_permission_claim_rewards to R.string.host_mcp_permission_claim_rewards_description
    McpPermission.MARK_READ -> R.string.host_mcp_permission_mark_read to R.string.host_mcp_permission_mark_read_description
    McpPermission.SEND_PROTOCOL -> R.string.host_mcp_permission_send_protocol to R.string.host_mcp_permission_send_protocol_description
}

@Composable
private fun TokenDialog(
    edit: McpTokenEdit,
    notice: String?,
    busy: Boolean,
    onEdit: (McpTokenEdit) -> Unit,
    onChats: () -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onRenew: (String) -> Unit,
    onRevoke: (String) -> Unit,
) {
    val draft = edit.draft
    // Both end the secret in use, so each asks first: true for a new secret, false for revoking.
    var confirm by remember { mutableStateOf<Boolean?>(null) }
    fun set(next: McpTokenDraft) = onEdit(edit.copy(draft = next))
    NukeDialog(
        visible = true,
        onDismissRequest = onBack,
        title = stringResource(if (edit.id == null) R.string.host_mcp_new_token else R.string.host_mcp_edit_token),
        modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 560.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        actions = {
            NukeDialogTextButton(stringResource(R.string.host_streak_back), onBack, Modifier.weight(1f))
            NukeDialogAccentButton(stringResource(R.string.home_save), onSave, Modifier.weight(1f), enabled = !busy && edit.problem == null)
        },
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NukeSettingGroup(title = stringResource(R.string.host_mcp_token_name)) {
                NukeTextField(
                    value = draft.name,
                    onValueChange = { set(draft.copy(name = it.take(McpLimits.MAX_NAME_CHARS))) },
                    modifier = Modifier.padding(12.dp),
                    placeholder = stringResource(R.string.host_mcp_token_name_hint),
                )
            }
            NukeSettingGroup(title = stringResource(R.string.host_mcp_permissions)) {
                SwitchRow(stringResource(R.string.host_mcp_token_enabled), null, draft.enabled) { set(draft.copy(enabled = it)) }
                for (permission in McpPermission.entries) {
                    val (title, description) = permissionLabel(permission)
                    NukeDivider()
                    SwitchRow(stringResource(title), stringResource(description), permission in draft.permissions) { on ->
                        set(draft.copy(permissions = if (on) draft.permissions + permission else draft.permissions - permission))
                    }
                }
            }
            NukeSettingGroup(title = stringResource(R.string.host_mcp_chats)) {
                SwitchRow(stringResource(R.string.host_mcp_allow_all), stringResource(R.string.host_mcp_allow_all_description), draft.allowAll) {
                    set(draft.copy(allowAll = it))
                }
                if (!draft.allowAll) {
                    NukeDivider()
                    NukeNavRow(title = stringResource(R.string.host_mcp_chosen_chats), statusText = draft.conversations.size.toString(), onClick = onChats)
                }
            }
            NukeSettingGroup(title = stringResource(R.string.host_mcp_limit)) {
                NukeTextField(
                    value = edit.limit,
                    onValueChange = { text -> onEdit(edit.copy(limit = text.filter(Char::isDigit).take(MAX_LIMIT_CHARS))) },
                    modifier = Modifier.padding(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            if (edit.id != null) {
                NukeSettingGroup {
                    NukeNavRow(title = stringResource(R.string.host_mcp_renew), enabled = !busy, onClick = { confirm = true })
                    NukeDivider()
                    NukeNavRow(title = stringResource(R.string.host_mcp_revoke), enabled = !busy, onClick = { confirm = false })
                }
            }
            edit.problem?.let { OptionsBodyText(stringResource(mcpProblemText(it)), warning = true) }
            if (edit.problem == null && edit.idle) OptionsBodyText(stringResource(R.string.host_mcp_token_idle))
            if (McpPermission.SEND_PROTOCOL in draft.permissions && !draft.allowAll) {
                OptionsBodyText(stringResource(R.string.host_mcp_protocol_needs_all), warning = true)
            }
            if (McpPermission.MARK_READ in draft.permissions && !draft.allowAll) {
                OptionsBodyText(stringResource(R.string.host_mcp_read_needs_all), warning = true)
            }
            notice?.let { OptionsBodyText(it, warning = true) }
        }
    }
    val asked = confirm
    val id = edit.id
    if (asked != null && id != null) {
        NukeDialog(
            onDismissRequest = { confirm = null },
            title = stringResource(if (asked) R.string.host_mcp_renew else R.string.host_mcp_revoke),
            contentText = if (asked) stringResource(R.string.host_mcp_renew_message) else stringResource(R.string.host_mcp_revoke_message, draft.name),
            confirmButtonText = stringResource(R.string.host_mcp_confirm),
            dismissButtonText = stringResource(R.string.home_cancel),
            onConfirm = {
                confirm = null
                if (asked) onRenew(id) else onRevoke(id)
            },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) = NukeSettingRow(
    title = title,
    subtitle = subtitle,
    onClick = { onChange(!checked) },
    role = Role.Switch,
    modifier = Modifier.semantics { toggleableState = ToggleableState(checked) },
    // The row carries the toggle semantics; the switch only mirrors the value.
    trailingContent = { NukeSwitch(checked = checked, onCheckChanged = null) },
)

// Leaving by a tap outside or by Back would lose the only copy, so this one closes by its button alone.
@Composable
private fun IssuedDialog(token: String, port: Int, onDone: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    NukeDialog(
        visible = true,
        onDismissRequest = {},
        title = stringResource(R.string.host_mcp_issued_title),
        modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 560.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false),
        actions = {
            NukeDialogTextButton(stringResource(R.string.host_mcp_copy), {
                copySecret(context, token)
                copied = true
            }, Modifier.weight(1f))
            NukeDialogAccentButton(stringResource(R.string.host_mcp_done), onDone, Modifier.weight(1f))
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionsBodyText(stringResource(R.string.host_mcp_issued_notice), warning = true)
            SelectionContainer { CodeText(token) }
            OptionsBodyText(stringResource(R.string.host_mcp_connect, port))
            if (copied) OptionsBodyText(stringResource(R.string.host_mcp_copied))
        }
    }
}

// Marked sensitive so the system's clipboard preview does not show the token.
private fun copySecret(context: Context, token: String) {
    val clip = ClipData.newPlainText("MCP token", token)
    val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"
    clip.description.extras = PersistableBundle().apply { putBoolean(key, true) }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

@Composable
private fun PortDialog(port: Int, notice: String?, busy: Boolean, onBack: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(port.toString()) }
    val parsed = text.toIntOrNull()?.takeIf { it in McpLimits.MIN_PORT..McpLimits.MAX_PORT }
    // The same port can be saved again: that is how a server that could not take it tries once more.
    OptionsDialog(
        title = stringResource(R.string.host_mcp_port),
        onDismiss = onBack,
        onSave = { parsed?.let(onSave) },
        saveEnabled = parsed != null && !busy,
        hint = stringResource(R.string.host_mcp_port_hint),
    ) {
        NukeTextField(
            value = text,
            onValueChange = { text = it.filter(Char::isDigit).take(MAX_PORT_CHARS) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        if (parsed == null) OptionsBodyText(stringResource(R.string.host_mcp_problem_port), warning = true)
        notice?.let { OptionsBodyText(it, warning = true) }
    }
}

@Composable
private fun CallsDialog(calls: List<McpCall>, names: Map<String, String>, onBack: () -> Unit) = NukeDialog(
    visible = true,
    onDismissRequest = onBack,
    title = stringResource(R.string.host_mcp_calls),
    modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 560.dp),
    properties = DialogProperties(usePlatformDefaultWidth = false),
    actions = { NukeDialogTextButton(stringResource(R.string.host_streak_back), onBack, Modifier.weight(1f)) },
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OptionsBodyText(stringResource(R.string.host_mcp_calls_hint))
        if (calls.isEmpty()) {
            OptionsBodyText(stringResource(R.string.host_mcp_no_calls))
        } else {
            val done = stringResource(R.string.host_mcp_call_ok)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(calls) { call ->
                    NukeSettingGroup {
                        NukeSettingRow(
                            title = call.tool,
                            subtitle = listOfNotNull(time(call.at), call.tokenName, call.target?.let { names[it] ?: it }).joinToString(" · "),
                            supportingContent = { OptionsBodyText(if (call.ok) done else call.code, warning = !call.ok) },
                        )
                    }
                }
            }
        }
    }
}

private fun time(at: Long): String = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)

private val TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
private const val MAX_LIMIT_CHARS = 4
private const val MAX_PORT_CHARS = 5
