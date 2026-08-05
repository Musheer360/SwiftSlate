package com.musheer360.swiftslate.ui.processtext

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.model.Command
import com.musheer360.swiftslate.ui.components.SlateCard
import com.musheer360.swiftslate.ui.components.SlateItemCard
import com.musheer360.swiftslate.ui.components.SlateToast
import com.musheer360.swiftslate.ui.components.SlateToastTokens
import com.musheer360.swiftslate.ui.theme.SwiftSlateTheme
import kotlinx.coroutines.delay

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
        val selection = parsed.getOrNull()
        // Rendered in the sheet rather than as a toast: the app draws all of its own transient
        // UI, and an unusable selection is worth an explicit dismiss rather than a message that
        // disappears on its own.
        val rejectionMessage = if (selection == null) {
            when ((parsed.exceptionOrNull() as? RejectedSelectionException)?.rejection) {
                Rejection.TooLong -> getString(R.string.error_input_too_long)
                else -> getString(R.string.process_text_no_selection)
            }
        } else {
            null
        }

        setContent {
            SwiftSlateTheme {
                ProcessTextRoot(
                    selection = selection,
                    rejectionMessage = rejectionMessage,
                    factory = { app, sel -> viewModelFactory { initializer { ProcessTextViewModel(app, sel) } } },
                    application = application,
                    onInsert = { original, text -> replaceAndFinish(original, text) },
                    onCopy = { text -> copyToClipboard(text) },
                    onFinish = { finish() }
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

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("SwiftSlate", text))
    }
}

/** Long enough to read, short enough not to feel like a stall before the sheet closes. */
private const val CONFIRMATION_VISIBLE_MS = 900L

/**
 * Owns the copy-confirmation state and hands it to the sheet. Split out of the Activity so the
 * whole surface is previewable and the Activity keeps only the things that need a Context.
 */
@Composable
private fun ProcessTextRoot(
    selection: Selection?,
    rejectionMessage: String?,
    application: Application,
    factory: (Application, Selection) -> androidx.lifecycle.ViewModelProvider.Factory,
    onInsert: (String, String) -> Unit,
    onCopy: (String) -> Unit,
    onFinish: () -> Unit
) {
    val copiedMessage = stringResource(R.string.process_text_copied)
    var confirmation by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(confirmation) {
        if (confirmation != null) {
            delay(CONFIRMATION_VISIBLE_MS)
            onFinish()
        }
    }

    ProcessTextSheet(
        selection = selection,
        rejectionMessage = rejectionMessage,
        application = application,
        factory = factory,
        confirmation = confirmation,
        onInsert = onInsert,
        onCopy = { text ->
            onCopy(text)
            confirmation = copiedMessage
        },
        onDismiss = onFinish
    )
}

@Composable
private fun ProcessTextSheet(
    selection: Selection?,
    rejectionMessage: String?,
    application: Application,
    factory: (Application, Selection) -> androidx.lifecycle.ViewModelProvider.Factory,
    confirmation: String?,
    onInsert: (String, String) -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    // Built before the sheet so the command list is known at first composition; opening at the
    // height of an empty list and growing afterwards is what made the entrance stutter.
    val viewModel: ProcessTextViewModel? = selection?.let { viewModel(factory = factory(application, it)) }
    val state: UiState? = viewModel?.uiState?.collectAsState()?.value
    if (selection != null && state is UiState.Initializing) return

    SlateBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                // Picker -> loading -> result are different heights; animate between them with
                // the same 250ms the rest of the app uses instead of snapping.
                .animateContentSize(tween(ANIM_MS))
        ) {
            Text(
                text = stringResource(R.string.process_text_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (selection == null) {
                FatalMessage(message = rejectionMessage.orEmpty(), onDismiss = onDismiss)
                return@Column
            }

            when (val s = state) {
                null, is UiState.Initializing -> Unit
                is UiState.CommandList -> CommandRows(s.commands) { viewModel.run(it) }
                is UiState.Loading -> SlateCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = s.command.trigger,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is UiState.Preview -> {
                    ResultCard(result = s.result)
                    if (confirmation != null) {
                        // Inside the sheet on purpose: ModalBottomSheet owns its own window, so
                        // anything drawn in the Activity's window sits behind it and would never
                        // be seen at the bottom of the screen.
                        SlateToast(
                            message = confirmation,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                        return@Column
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (s.canInsert) {
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onInsert(selection.text, s.result)
                                },
                                shape = SlateButtonShape,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(R.string.process_text_replace))
                            }
                        }
                        // Same filled look as Replace, not the secondary TextButton style: all
                        // three read as one row of equal, equally-weighted actions rather than
                        // one primary action plus two lesser ones.
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onCopy(s.result)
                            },
                            shape = SlateButtonShape,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) {
                            Text(stringResource(R.string.process_text_copy))
                        }
                        Button(
                            onClick = { viewModel.backToCommands() },
                            shape = SlateButtonShape,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) {
                            Text(stringResource(R.string.process_text_back))
                        }
                    }
                }
                is UiState.Error -> {
                    SlateCard {
                        Text(
                            text = s.message,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        // Offered only for failures a re-run could actually fix.
                        s.retry?.let { command ->
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.run(command)
                                },
                                shape = SlateButtonShape,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(R.string.process_text_retry))
                            }
                        }
                        TextButton(
                            onClick = { viewModel.backToCommands() },
                            shape = SlateButtonShape,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text(stringResource(R.string.process_text_back))
                        }
                    }
                }
            }
        }
    }
}

/** Nothing usable arrived in the Intent, so the only action left is to close. */
@Composable
private fun FatalMessage(message: String, onDismiss: () -> Unit) {
    SlateCard {
        Text(
            text = message,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
    ) {
        TextButton(
            onClick = onDismiss,
            shape = SlateButtonShape,
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            // Reuses an existing label that is already translated in all 39 locales, rather than
            // adding a new string that would ship English-only to everyone else.
            Text(stringResource(R.string.commands_cancel))
        }
    }
}

@Composable
private fun ResultCard(result: String) {
    SlateCard {
        Text(
            text = result,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState())
        )
    }
}

@Composable
private fun CommandRows(commands: List<Command>, onPick: (Command) -> Unit) {
    if (commands.isEmpty()) {
        SlateCard {
            Text(
                text = stringResource(R.string.process_text_no_commands),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val haptic = LocalHapticFeedback.current
    // Tiles inside one enclosing card, the same grouping and LazyColumn setup the Commands
    // screen uses (fillMaxSize/clip/spacedBy(8.dp), no overscroll override) so the two look and
    // feel identical, including the same stretch/bounce glow at the ends of the list.
    SlateCard {
        LazyColumn(
            modifier = Modifier
                .heightIn(max = 360.dp)
                .clip(RoundedCornerShape(8.dp)),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(commands, key = { it.trigger }) { command ->
                // 48dp minimum touch target (Material3).
                SlateItemCard(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPick(command)
                        }
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
}

/** Corner radius every other button in the app uses. */
private val SlateButtonShape = RoundedCornerShape(10.dp)

private const val ANIM_MS = 250
