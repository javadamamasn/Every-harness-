package com.harnesshub.app.ui.screens.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.harnesshub.app.R
import com.harnesshub.app.termux.TermuxDetector
import com.harnesshub.termux.TermuxGateway

/**
 * The Termux setup wizard (M2). Steps marked ACTION carry the exact command to run
 * inside Termux; the app itself drives what Termux exposes (RUN_COMMAND) once steps
 * 1–3 are green. The bridge livestate (reachability/health) integrates at M3 with
 * the discovery engine, so those steps stay honest until then.
 */
@Composable
fun WizardScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var revision by remember { mutableStateOf(0) }
    val detected = remember(revision) { TermuxDetector.detect(context) }
    val steps = remember(revision) { TermuxGateway.computeSteps(TermuxDetector.state(detected)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.wizard_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.wizard_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { revision += 1 }) {
            Text(stringResource(R.string.wizard_recheck))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(steps) { step ->
                Surface(
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stepLabel(step.step), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Text(
                                when (step.status) {
                                    TermuxGateway.Status.DONE -> stringResource(R.string.wizard_status_done)
                                    TermuxGateway.Status.ACTION -> stringResource(R.string.wizard_status_action)
                                    TermuxGateway.Status.PENDING -> stringResource(R.string.wizard_status_pending)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = when (step.status) {
                                    TermuxGateway.Status.DONE -> MaterialTheme.colorScheme.primary
                                    TermuxGateway.Status.ACTION -> MaterialTheme.colorScheme.tertiary
                                    TermuxGateway.Status.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        step.command?.let { command ->
                            Text(
                                command,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(command)) }) {
                                Text(stringResource(R.string.wizard_copy))
                            }
                        }
                        if (step.step == TermuxGateway.WizardStep.INSTALL_TERMUX && step.status != TermuxGateway.Status.DONE) {
                            Text(
                                stringResource(R.string.wizard_hint_termux),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (step.step == TermuxGateway.WizardStep.ENABLE_EXTERNAL_APPS && step.status != TermuxGateway.Status.DONE) {
                            Text(
                                stringResource(R.string.wizard_hint_external_apps),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun stepLabel(step: TermuxGateway.WizardStep): String = stringResource(
    when (step) {
        TermuxGateway.WizardStep.INSTALL_TERMUX -> R.string.wizard_step_install_termux
        TermuxGateway.WizardStep.GRANT_PERMISSION -> R.string.wizard_step_grant_permission
        TermuxGateway.WizardStep.ENABLE_EXTERNAL_APPS -> R.string.wizard_step_enable_external_apps
        TermuxGateway.WizardStep.INSTALL_BRIDGE -> R.string.wizard_step_install_bridge
        TermuxGateway.WizardStep.START_BRIDGE -> R.string.wizard_step_start_bridge
        TermuxGateway.WizardStep.WAKE_LOCK -> R.string.wizard_step_wake_lock
        TermuxGateway.WizardStep.HEALTH_TEST -> R.string.wizard_step_health
    },
)
