package dev.ferride.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ferride.scm.CredentialPrompt
import dev.ferride.scm.InputBox
import dev.ferride.scm.QuickPick
import dev.ferride.scm.ScmState
import dev.ferride.scm.hostKey
import dev.ferride.theme.LocalWorkbenchColors

/**
 * Source Control's quick inputs and dialogs, drawn over the workbench like VS Code's: the branch quick pick,
 * input boxes (branch name, clone URL, identity), modal confirmations, the sign-in prompt and the progress
 * notification of a running clone / pull / push.
 */
@Composable
fun ScmOverlays(scm: ScmState, maxWidth: Dp, autoFocus: Boolean = true) {
    scm.operation?.let { OperationNotification(scm) }
    scm.quickPick?.let { pick ->
        QuickInputFrame(maxWidth, onDismiss = { scm.quickPick = null }) {
            QuickPickContent(pick, autoFocus) { scm.quickPick = null }
        }
        BackHandler { scm.quickPick = null }
    }
    scm.inputBox?.let { box ->
        QuickInputFrame(maxWidth, onDismiss = { scm.inputBox = null }) {
            InputBoxContent(box, autoFocus) { scm.inputBox = null }
        }
        BackHandler { scm.inputBox = null }
    }
    scm.confirm?.let { confirm ->
        val c = LocalWorkbenchColors.current
        AlertDialog(
            onDismissRequest = { scm.confirm = null },
            containerColor = c.quickInputBackground,
            title = { Text(confirm.message, color = c.inputForeground, fontSize = 16.sp) },
            text = if (confirm.detail != null) {
                { Text(confirm.detail, color = c.descriptionForeground, fontSize = 13.sp) }
            } else {
                null
            },
            confirmButton = {
                Row {
                    confirm.buttons.forEach { (label, action) ->
                        TextButton({
                            scm.confirm = null
                            action()
                        }) { Text(label) }
                    }
                }
            },
            dismissButton = {
                TextButton({ scm.confirm = null }) { Text(if (confirm.buttons.isEmpty()) "Close" else "Cancel") }
            },
        )
    }
    scm.credentialPrompt?.let { CredentialDialog(it) }
}

@Composable
private fun QuickInputFrame(maxWidth: Dp, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = LocalWorkbenchColors.current
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = SCRIM_ALPHA)).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClickLabel = "Close",
            onClick = onDismiss,
        ),
    )
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .width(minOf(600.dp, maxWidth - 16.dp))
                .shadow(16.dp, RoundedCornerShape(8.dp))
                .background(c.quickInputBackground, RoundedCornerShape(8.dp))
                .border(1.dp, c.panelBorder, RoundedCornerShape(8.dp))
                .padding(6.dp),
        ) { content() }
    }
}

@Composable
private fun QuickField(
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    autoFocus: Boolean,
    password: Boolean = false,
    onDone: () -> Unit,
) {
    val c = LocalWorkbenchColors.current
    val focus = remember { FocusRequester() }
    Box(
        Modifier.fillMaxWidth()
            .background(c.inputBackground, RoundedCornerShape(4.dp))
            .border(1.dp, c.focusBorder, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, color = c.descriptionForeground, fontSize = 14.sp, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = c.inputForeground, fontSize = 14.sp),
            cursorBrush = SolidColor(c.editorCursor),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                keyboardType = if (password) KeyboardType.Password else KeyboardType.Uri,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun QuickPickContent(pick: QuickPick, autoFocus: Boolean, close: () -> Unit) {
    val c = LocalWorkbenchColors.current
    val items = pick.filtered()
    QuickField(pick.query, pick.placeholder, { pick.query = it }, autoFocus) { items.firstOrNull()?.run?.invoke() }
    LazyColumn(Modifier.heightIn(max = 440.dp).padding(top = 4.dp)) {
        itemsIndexed(items) { i, item ->
            if (item.section != null) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(1.dp).background(c.panelBorder))
                    Text(item.section, color = c.descriptionForeground, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth()
                    .background(if (i == 0) c.listActiveSelectionBackground else c.quickInputBackground, RoundedCornerShape(4.dp))
                    .clickable {
                        close()
                        item.run()
                    }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = codicon(item.icon)
                if (icon != null) {
                    Icon(icon, null, tint = c.inputForeground, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.label, color = c.inputForeground, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        item.description?.let {
                            Spacer(Modifier.width(8.dp))
                            Text(it, color = c.descriptionForeground, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    item.detail?.let {
                        Text(it, color = c.descriptionForeground, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun InputBoxContent(box: InputBox, autoFocus: Boolean, close: () -> Unit) {
    val c = LocalWorkbenchColors.current
    val error = box.value.takeIf { it.isNotEmpty() }?.let(box.validate)
    fun accept() {
        val v = box.value
        if (box.validate(v) != null) return
        close()
        box.onAccept(v)
    }
    QuickField(box.value, box.placeholder, { box.value = it }, autoFocus) { accept() }
    Text(
        error ?: "${box.prompt} (Press 'Enter' to confirm or 'Escape' to cancel)",
        color = if (error != null) c.errorForeground else c.descriptionForeground,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

@Composable
private fun CredentialDialog(prompt: CredentialPrompt) {
    val c = LocalWorkbenchColors.current
    val host = hostKey(prompt.url).substringAfter("://")
    AlertDialog(
        onDismissRequest = { prompt.cancel() },
        containerColor = c.quickInputBackground,
        title = { Text("Sign in to $host", color = c.inputForeground, fontSize = 16.sp) },
        text = {
            Column {
                Text(
                    "Git needs a username and a personal access token for ${prompt.url}",
                    color = c.descriptionForeground,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(10.dp))
                QuickField(prompt.username, "Username", { prompt.username = it }, autoFocus = true) {}
                Spacer(Modifier.height(8.dp))
                QuickField(prompt.token, "Personal access token", { prompt.token = it }, autoFocus = false, password = true) {
                    if (prompt.token.isNotBlank()) prompt.submit()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        prompt.remember,
                        { prompt.remember = it },
                        colors = CheckboxDefaults.colors(checkedColor = c.focusBorder),
                    )
                    Text("Remember on this device", color = c.inputForeground, fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton({ prompt.submit() }, enabled = prompt.token.isNotBlank()) { Text("Sign in") } },
        dismissButton = { TextButton({ prompt.cancel() }) { Text("Cancel") } },
    )
}

/** VS Code's progress notification (bottom right): title, git's phase, a bar, and Cancel. */
@Composable
private fun OperationNotification(scm: ScmState) {
    val c = LocalWorkbenchColors.current
    val op = scm.operation ?: return
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(bottom = 80.dp, end = 8.dp, start = 8.dp), contentAlignment = Alignment.BottomEnd) {
        Column(
            Modifier.width(360.dp)
                .shadow(8.dp, RoundedCornerShape(6.dp))
                .background(c.quickInputBackground, RoundedCornerShape(6.dp))
                .border(1.dp, c.panelBorder, RoundedCornerShape(6.dp)),
        ) {
            val total = op.total
            if (total != null && total > 0) {
                LinearProgressIndicator(
                    progress = { (op.current.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = c.focusBorder,
                    trackColor = Color.Transparent,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = c.focusBorder, trackColor = Color.Transparent)
            }
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(op.title, color = c.inputForeground, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val phase = op.phase
                    if (phase != null) {
                        val pct = op.total?.takeIf { it > 0 }?.let { " ${(op.current * 100 / it).coerceIn(0, 100)}%" } ?: ""
                        Text("$phase$pct", color = c.descriptionForeground, fontSize = 12.sp, maxLines = 1)
                    }
                }
                if (op.cancel != null) TextButton({ scm.cancelOperation() }) { Text("Cancel") }
            }
        }
    }
}
