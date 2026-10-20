package com.easyhooon.eora.internal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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

internal enum class Screen { DRAFTS, EDITOR, DETAILS, REVIEW, RESULT }

@Composable
internal fun EoraApp(runtime: EoraRuntime, initialReportId: String?, showShakeNotice: Boolean, onClose: () -> Unit) {
    var reportId by rememberSaveable { mutableStateOf(initialReportId) }
    var screen by rememberSaveable { mutableStateOf<Screen?>(if (initialReportId == null) Screen.DRAFTS else null) }
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
        if (initialReportId == null) {
            reportId = null
            screen = Screen.DRAFTS
        } else {
            onClose()
        }
    }

    BackHandler {
        when (screen) {
            Screen.DETAILS -> if (controller?.hasOriginal() == true) screen = Screen.EDITOR else backToListOrClose()
            Screen.REVIEW -> screen = Screen.DETAILS
            else -> backToListOrClose()
        }
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
                onCancel = {
                    controller.deleteDraft()
                    backToListOrClose()
                },
                onNext = { screen = Screen.DETAILS },
            )
            Screen.DETAILS -> DetailsScreen(controller!!, modifier, onNext = { screen = Screen.REVIEW })
            Screen.REVIEW -> ReviewScreen(controller!!, modifier, onSent = { screen = Screen.RESULT })
            Screen.RESULT -> ResultScreen(
                controller!!,
                modifier,
                onDone = ::backToListOrClose,
                onDeleted = ::backToListOrClose,
            )
            null -> Unit
        }
    }

    if (noticeVisible) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.eora_notice_title)) },
            text = {
                Text(stringResource(R.string.eora_notice_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    runtime.isShakeNoticeAcknowledged = true
                    noticeVisible = false
                }) { Text(stringResource(R.string.eora_ok)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    runtime.isShakeNoticeAcknowledged = true
                    runtime.isShakeEnabled = false
                    noticeVisible = false
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
    val drafts = remember { runtime.store.list() }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.eora_drafts_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.eora_drafts_hint), style = MaterialTheme.typography.bodySmall)
        if (drafts.isEmpty()) Text(stringResource(R.string.eora_no_drafts))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(drafts, key = { it.reportId }) { draft ->
                Card(onClick = { onOpen(draft.reportId) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(draft.targetIssue?.key ?: stringResource(R.string.eora_no_issue_yet), style = MaterialTheme.typography.titleMedium)
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
