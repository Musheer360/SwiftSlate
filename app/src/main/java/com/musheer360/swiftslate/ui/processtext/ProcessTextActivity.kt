package com.musheer360.swiftslate.ui.processtext

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.model.Command
import com.musheer360.swiftslate.ui.components.SlateItemCard
import com.musheer360.swiftslate.ui.theme.SwiftSlateTheme

/**
 * Handles ACTION_PROCESS_TEXT: the entry point Android offers in the text-selection popup.
 * A one-shot dialog activity — no overlay, no new permissions, gone as soon as it finishes.
 *
 * Owns everything Activity-scoped (clipboard, setResult/finish); the request policy lives in
 * [ProcessTextViewModel] and the pure modules behind it.
 */
class ProcessTextActivity : ComponentActivity() {

    private var resultDelivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // PROCESS_TEXT activities are always launched fresh with their Intent, so the selection
        // itself never needs saving across process death.
        val parsed = ProcessTextInput.parseSelection(
            rawText = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT),
            readOnlyExtra = intent?.extras
                ?.takeIf { it.containsKey(Intent.EXTRA_PROCESS_TEXT_READONLY) }
                ?.getBoolean(Intent.EXTRA_PROCESS_TEXT_READONLY)
        )
        val selection = parsed.getOrElse { e ->
            // Finish with a short toast rather than showing an empty sheet.
            val message = when ((e as? RejectedSelectionException)?.rejection) {
                Rejection.TooLong -> getString(R.string.error_input_too_long)
                else -> getString(R.string.process_text_no_selection)
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            SwiftSlateTheme {
                ProcessTextSheet(
                    viewModel = viewModel(factory = factoryFor(application, selection)),
                    onInsert = { text -> replaceAndFinish(selection.text, text) },
                    onCopy = { text -> copyAndFinish(text) },
                    onDismiss = { finish() }
                )
            }
        }
    }

    /**
     * Hands the result back for the host to substitute into the selection. Whether it actually
     * does is the host's choice — Copy is always offered as the manual fallback.
     */
    private fun replaceAndFinish(original: String, replacement: String) {
        if (resultDelivered) return
        resultDelivered = true
        ProcessTextReplacementBridge.prepare(
            original = original,
            replacement = replacement,
            sourcePackage = callingPackage,
            now = SystemClock.elapsedRealtime()
        )
        setResult(
            RESULT_OK,
            Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, replacement)
        )
        finish()
    }

    private fun copyAndFinish(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("SwiftSlate", text))
        Toast.makeText(this, getString(R.string.process_text_copied), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun factoryFor(app: Application, selection: Selection) = viewModelFactory {
        initializer { ProcessTextViewModel(app, selection) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProcessTextSheet(
    viewModel: ProcessTextViewModel,
    onInsert: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.process_text_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            when (val s = state) {
                is UiState.CommandList -> CommandRows(s.commands) { viewModel.run(it) }
                is UiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = s.command.trigger,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is UiState.Preview -> {
                    Text(
                        text = s.result,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (s.canInsert) {
                            Button(onClick = { onInsert(s.result) }) {
                                Text(stringResource(R.string.process_text_replace))
                            }
                        }
                        // Always offered, editable or not: it is also the recovery path when a
                        // host silently declines the replacement.
                        OutlinedButton(onClick = { onCopy(s.result) }) {
                            Text(stringResource(R.string.process_text_copy))
                        }
                        TextButton(onClick = { viewModel.backToCommands() }) {
                            Text(stringResource(R.string.process_text_back))
                        }
                    }
                }
                is UiState.Error -> {
                    Text(
                        text = s.message,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Offered only for failures a re-run could actually fix.
                        s.retry?.let { command ->
                            Button(onClick = { viewModel.run(command) }) {
                                Text(stringResource(R.string.process_text_retry))
                            }
                        }
                        TextButton(onClick = { viewModel.backToCommands() }) {
                            Text(stringResource(R.string.process_text_back))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandRows(commands: List<Command>, onPick: (Command) -> Unit) {
    if (commands.isEmpty()) {
        Text(
            text = stringResource(R.string.process_text_no_commands),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Column(
        modifier = Modifier
            .heightIn(max = 380.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        commands.forEach { command ->
            // 48dp minimum touch target (Material3).
            SlateItemCard(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable { onPick(command) }
            ) {
                Text(
                    text = command.trigger,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
