package com.byemaxx.fastadb

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource

@Composable
fun FastAdbRoute(viewModel: FastAdbViewModel) {
    val state by viewModel.uiState.collectAsState()
    FastAdbScreen(
        state = state,
        onCommandChange = viewModel::onCommandInputChanged,
        onSendCommand = viewModel::sendCustomCommand,
        onQuickAction = viewModel::sendQuickAction,
        onRefresh = viewModel::refreshConnectedDevices,
        onClearTerminal = viewModel::clearTerminal
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FastAdbScreen(
    state: FastAdbUiState,
    onCommandChange: (String) -> Unit,
    onSendCommand: () -> Unit,
    onQuickAction: (QuickAction) -> Unit,
    onRefresh: () -> Unit,
    onClearTerminal: () -> Unit
) {
    val terminalState = rememberLazyListState()
    var controlsExpanded = rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.terminal.lastOrNull()?.id) {
        val lastIndex = state.terminal.lastIndex
        if (lastIndex >= 0) {
            terminalState.animateScrollToItem(lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "FASTADB OTG Console",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                actions = {
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = !state.busy
                    ) {
                        Text(stringResource(R.string.action_refresh))
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.28f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HeroCard(
                    state = state,
                    controlsExpanded = controlsExpanded.value,
                    onToggleControls = { controlsExpanded.value = !controlsExpanded.value }
                )
                AnimatedVisibility(visible = controlsExpanded.value) {
                    ControlPanelCard(
                        state = state,
                        onQuickAction = onQuickAction
                    )
                }
                TerminalCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    state = state,
                    listState = terminalState,
                    onCommandChange = onCommandChange,
                    onSendCommand = onSendCommand,
                    onClearTerminal = onClearTerminal
                )
            }
        }
    }
}

@Composable
private fun HeroCard(
    state: FastAdbUiState,
    controlsExpanded: Boolean,
    onToggleControls: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.deviceLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = state.deviceIdentifiers,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                StatusBadge(mode = state.mode)
            }
            Text(
                text = state.status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onToggleControls,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Text(
                        stringResource(
                            if (controlsExpanded) {
                                R.string.action_collapse_controls
                            } else {
                                R.string.action_expand_controls
                            }
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(mode: DeviceMode) {
    val colors = when (mode) {
        DeviceMode.Adb -> Color(0xFF0B7A75) to Color(0xFFE2FFFA)
        DeviceMode.Fastboot -> Color(0xFFB84A12) to Color(0xFFFFE9DB)
        DeviceMode.PermissionRequired -> Color(0xFF8A6700) to Color(0xFFFFF1BD)
        DeviceMode.Unsupported -> Color(0xFF7A1E2B) to Color(0xFFFFE3E7)
        DeviceMode.Disconnected -> Color(0xFF3B4856) to Color(0xFFE8EDF3)
    }
    Surface(
        color = colors.second,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = stringResource(mode.labelRes),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = colors.first,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ControlPanelCard(
    state: FastAdbUiState,
    onQuickAction: (QuickAction) -> Unit
) {
    val adbEnabled = state.mode == DeviceMode.Adb && !state.busy
    val fastbootEnabled = state.mode == DeviceMode.Fastboot && !state.busy

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.info.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(end = 4.dp)
                ) {
                    items(state.info, key = { "${it.label}:${it.value}" }) { fact ->
                        InfoChip(fact = fact)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onQuickAction(QuickAction.RebootBootloader) },
                    enabled = adbEnabled
                ) {
                    Text("adb reboot bootloader")
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onQuickAction(QuickAction.SetSelinuxPermissiveThenContinue) },
                    enabled = fastbootEnabled
                ) {
                    Text("set selinux permissive + fastboot continue")
                }
            }
        }
    }
}

@Composable
private fun InfoChip(fact: DeviceFact) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = fact.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.74f)
            )
            Text(
                text = fact.value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TerminalCard(
    modifier: Modifier,
    state: FastAdbUiState,
    listState: LazyListState,
    onCommandChange: (String) -> Unit,
    onSendCommand: () -> Unit,
    onClearTerminal: () -> Unit
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF11161D)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Terminal",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF5F7FA)
                    )
                }
                OutlinedButton(
                    onClick = onClearTerminal,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFE3E8EF)
                    )
                ) {
                    Text("Clear")
                }
            }
            SelectionContainer(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF0A0F14))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(
                        items = state.terminal,
                        key = { it.id }
                    ) { line ->
                        val color = when (line.kind) {
                            TerminalKind.Command -> Color(0xFFFFC857)
                            TerminalKind.Output -> Color(0xFFE8EEF6)
                            TerminalKind.Error -> Color(0xFFFF8A80)
                            TerminalKind.System -> Color(0xFF6FD3C3)
                        }
                        Text(
                            text = line.text,
                            color = color,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.commandInput,
                    onValueChange = onCommandChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    enabled = !state.busy,
                    placeholder = {
                        Text(
                            when (state.mode) {
                                DeviceMode.Adb -> "adb shell getprop ro.product.model"
                                DeviceMode.Fastboot -> "fastboot getvar product"
                                else -> "Connect a device first"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
                Button(
                    onClick = onSendCommand,
                    enabled = !state.busy &&
                        state.commandInput.isNotBlank() &&
                        state.mode != DeviceMode.Disconnected &&
                        state.mode != DeviceMode.Unsupported &&
                        state.mode != DeviceMode.PermissionRequired
                ) {
                    Text(if (state.busy) "..." else "Send")
                }
            }
        }
    }
}
