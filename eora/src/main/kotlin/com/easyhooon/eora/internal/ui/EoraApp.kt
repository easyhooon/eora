package com.easyhooon.eora.internal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.easyhooon.eora.R
import com.easyhooon.eora.internal.EoraRuntime
import com.easyhooon.eora.internal.report.ArtifactKind
import com.easyhooon.eora.internal.report.ReportDraft
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class Screen { MENU, DRAFTS, EDITOR, DETAILS, REVIEW, RESULT }

/**
 * [openMenu] is set for shake: the capture is already taken, and the menu decides what to do with it, like the
 * reference QA tool. The manual trigger skips the menu and goes straight to the editor.
 */
@Composable
internal fun EoraApp(runtime: EoraRuntime, initialReportId: String?, openMenu: Boolean, showShakeNotice: Boolean, onClose: () -> Unit) {
    var reportId by rememberSaveable { mutableStateOf(initialReportId) }
    var screen by rememberSaveable {
        mutableStateOf(
            when {
                initialReportId == null -> Screen.DRAFTS
                openMenu -> Screen.MENU
                else -> null
            },
        )
    }
    var hasDraftsList by rememberSaveable { mutableStateOf(initialReportId == null) }
    var noticeVisible by rememberSaveable { mutableStateOf(showShakeNotice) }
    val snackbar = remember { SnackbarHostState() }

    val controller = reportId?.let { id -> remember(id) { runtime.controllerFor(id) } }
    if (reportId != null && controller == null) {
        // Expired or deleted meanwhile.
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val draft = controller?.draft?.collectAsState()?.value
    if (controller != null && screen == null) {
        screen = startScreenFor(draft, controller.hasOriginal())
    }
    val message = controller?.message?.collectAsState()?.value
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            controller.clearMessage()
        }
    }

    fun openDraft(id: String) {
        reportId = id
        screen = null
    }

    fun backToListOrClose() {
        if (hasDraftsList) {
            reportId = null
            screen = Screen.DRAFTS
        } else {
            onClose()
        }
    }

    // Nothing but the capture exists yet, so leaving the menu or the editor discards the report.
    fun discard() {
        controller?.deleteDraft()
        backToListOrClose()
    }

    fun leaveEditor() {
        if (openMenu) screen = Screen.MENU else discard()
    }

    BackHandler {
        when (screen) {
            Screen.MENU -> discard()
            Screen.EDITOR -> leaveEditor()
            Screen.DETAILS -> if (controller?.hasOriginal() == true) screen = Screen.EDITOR else backToListOrClose()
            Screen.REVIEW -> screen = Screen.DETAILS
            Screen.DRAFTS, Screen.RESULT, null -> if (screen == Screen.DRAFTS) onClose() else backToListOrClose()
        }
    }

    if (screen == Screen.MENU && controller != null) {
        MenuScreen(
            controller = controller,
            runtime = runtime,
            onFeedback = { screen = Screen.EDITOR },
            onDrafts = {
                controller.deleteDraft()
                reportId = null
                hasDraftsList = true
                screen = Screen.DRAFTS
            },
            onClose = ::discard,
        )
        ShakeNotice(runtime, noticeVisible) { noticeVisible = false }
        return
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(padding)
        when (screen) {
            Screen.DRAFTS -> DraftsScreen(runtime, modifier, onOpen = ::openDraft, onClose = onClose)
            Screen.EDITOR -> EditorScreen(
                controller!!,
                modifier,
                onBack = ::leaveEditor,
                onCancel = ::discard,
                onNext = { screen = Screen.DETAILS },
            )
            Screen.DETAILS -> DetailsScreen(
                controller!!,
                modifier,
                onBack = { if (controller.hasOriginal()) screen = Screen.EDITOR else backToListOrClose() },
                onNext = { screen = Screen.REVIEW },
            )
            Screen.REVIEW -> ReviewScreen(controller!!, modifier, onBack = { screen = Screen.DETAILS }, onSent = { screen = Screen.RESULT })
            Screen.RESULT -> ResultScreen(
                controller!!,
                modifier,
                onEdit = { screen = Screen.DETAILS },
                onDone = ::backToListOrClose,
                onDeleted = ::backToListOrClose,
            )
            Screen.MENU, null -> Unit
        }
    }

    ShakeNotice(runtime, noticeVisible) { noticeVisible = false }
}

@Composable
private fun ShakeNotice(runtime: EoraRuntime, visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return
    run {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.eora_notice_title)) },
            text = {
                Text(stringResource(R.string.eora_notice_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    runtime.isShakeNoticeAcknowledged = true
                    onDismiss()
                }) { Text(stringResource(R.string.eora_ok)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    runtime.isShakeNoticeAcknowledged = true
                    runtime.isShakeEnabled = false
                    onDismiss()
                }) { Text(stringResource(R.string.eora_turn_off_shake)) }
            },
        )
    }
}

private fun startScreenFor(draft: ReportDraft?, hasOriginal: Boolean): Screen = when {
    draft == null -> Screen.DRAFTS
    draft.hasTransferStarted -> Screen.RESULT
    draft.attachments.any { it.kind == ArtifactKind.SCREENSHOT } || !hasOriginal -> Screen.DETAILS
    else -> Screen.EDITOR
}

@Composable
private fun DraftsScreen(runtime: EoraRuntime, modifier: Modifier, onOpen: (String) -> Unit, onClose: () -> Unit) {
    // A draft left mid-send by a dead process shows as needing confirmation, as it will when opened.
    val drafts = remember { runtime.store.list().map { if (runtime.isSending(it.reportId)) it else it.recoverInterruptedTransfer() } }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.eora_drafts_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.eora_drafts_hint), style = MaterialTheme.typography.bodySmall)
        var shakeEnabled by remember { mutableStateOf(runtime.isShakeEnabled) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.eora_shake_to_open), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = shakeEnabled,
                onCheckedChange = {
                    runtime.isShakeEnabled = it
                    shakeEnabled = it
                },
            )
        }
        HorizontalDivider()
        if (drafts.isEmpty()) Text(stringResource(R.string.eora_no_drafts))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(drafts, key = { it.reportId }) { draft ->
                Card(onClick = { onOpen(draft.reportId) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(draft.createdIssue?.key ?: draft.description.title.ifBlank { stringResource(R.string.eora_no_issue_yet) }, style = MaterialTheme.typography.titleMedium)
                        Text("${stringResource(statusLabel(draft.status))} · ${localTime(draft.createdAtMs)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        TextButton(onClick = onClose) { Text(stringResource(R.string.eora_close)) }
    }
}

/** Device-local time for people; the report file keeps UTC so it reads the same everywhere. */
private fun localTime(epochMs: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
