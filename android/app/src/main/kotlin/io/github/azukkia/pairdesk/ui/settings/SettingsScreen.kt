package io.github.azukkia.pairdesk.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.azukkia.pairdesk.R
import io.github.azukkia.pairdesk.data.NetworkMode
import io.github.azukkia.pairdesk.data.SettingsStore
import io.github.azukkia.pairdesk.ui.UiText
import io.github.azukkia.pairdesk.ui.components.NetworkTexts
import io.github.azukkia.pairdesk.ui.components.SectionCard
import io.github.azukkia.pairdesk.ui.components.StatusDot
import io.github.azukkia.pairdesk.ui.text

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "identity") { IdentitySection(vm) }
                item(key = "incoming") { IncomingSection(vm) }
                item(key = "permanent") { PermanentPasswordSection(vm) }
                item(key = "network") { NetworkSection(vm) }
                item(key = "about") { AboutSection(vm) }
            }
        }
    }
}

@Composable
private fun IdentitySection(vm: SettingsViewModel) {
    val settings by vm.appSettings.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(settings.displayName) }
    val focus = LocalFocusManager.current
    SectionCard(title = stringResource(R.string.settings_identity), icon = null) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(SettingsStore.MAX_NAME) },
            label = { Text(stringResource(R.string.settings_display_name)) },
            placeholder = { Text(vm.defaultName) },
            supportingText = { Text(stringResource(R.string.settings_display_name_hint, vm.defaultName)) },
            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                vm.setDisplayName(name)
                focus.clearFocus()
            }),
            modifier = Modifier
                .fillMaxWidth()
                // Saved when the field loses the focus too.
                .onFocusChanged { if (!it.isFocused && name.trim() != settings.displayName) vm.setDisplayName(name) },
        )
    }
}

@Composable
private fun IncomingSection(vm: SettingsViewModel) {
    val settings by vm.appSettings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SectionCard(title = stringResource(R.string.settings_incoming), icon = R.drawable.ic_shield) {
        SwitchRow(
            title = stringResource(R.string.settings_allow_incoming),
            description = stringResource(R.string.settings_allow_incoming_desc),
            checked = settings.allowIncoming,
            onChange = vm::setAllowIncoming,
        )
        SwitchRow(
            title = stringResource(R.string.settings_allow_control),
            description = stringResource(R.string.settings_allow_control_desc),
            checked = settings.allowControl,
            enabled = settings.allowIncoming,
            onChange = vm::setAllowControl,
        )
        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
        ) {
            Icon(painterResource(R.drawable.ic_accessibility), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_open_accessibility))
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun PermanentPasswordSection(vm: SettingsViewModel) {
    val settings by vm.appSettings.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    SectionCard(
        title = stringResource(R.string.settings_permanent_title),
        icon = null,
        subtitle = stringResource(R.string.settings_permanent_desc),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (settings.hasPermanentPassword) R.string.settings_permanent_enabled else R.string.settings_permanent_disabled),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { editing = true }, enabled = !vm.savingPassword) {
                Text(stringResource(if (settings.hasPermanentPassword) R.string.settings_permanent_change else R.string.settings_permanent_set))
            }
            if (settings.hasPermanentPassword) {
                OutlinedButton(
                    onClick = { vm.savePermanentPassword(null) { result -> error = result?.res } },
                    enabled = !vm.savingPassword,
                ) { Text(stringResource(R.string.common_remove)) }
            }
            if (vm.savingPassword) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
    if (editing) {
        PermanentPasswordDialog(
            saving = vm.savingPassword,
            onDismiss = { editing = false },
            onSave = { password ->
                error = null
                vm.savePermanentPassword(password) { result ->
                    error = result?.res
                    editing = false
                }
            },
        )
    }
}

@Composable
private fun PermanentPasswordDialog(saving: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<UiText?>(null) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.settings_permanent_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        problem = null
                    },
                    label = { Text(stringResource(R.string.settings_permanent_new)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = {
                        confirmation = it
                        problem = null
                    },
                    label = { Text(stringResource(R.string.settings_permanent_confirm)) },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = problem?.let { p -> { Text(p.text()) } },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                )
                if (saving) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_permanent_saving), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    problem = PermanentPasswordRules.validate(password, confirmation)
                    if (problem == null) onSave(password)
                },
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun NetworkSection(vm: SettingsViewModel) {
    val settings by vm.appSettings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(settings.networkMode) }
    val focus = LocalFocusManager.current
    SectionCard(title = stringResource(R.string.settings_network), icon = null) {
        ModeOption(
            selected = mode == NetworkMode.PUBLIC,
            title = stringResource(R.string.settings_network_public),
            description = stringResource(R.string.settings_network_public_desc),
            onSelect = {
                mode = NetworkMode.PUBLIC
                vm.usePublicRelays()
            },
        )
        ModeOption(
            selected = mode == NetworkMode.SERVER,
            title = stringResource(R.string.settings_network_server),
            description = stringResource(R.string.settings_network_server_desc),
            onSelect = { mode = NetworkMode.SERVER },
        )
        if (mode == NetworkMode.SERVER) {
            OutlinedTextField(
                value = vm.serverUrl,
                onValueChange = vm::onServerUrlChange,
                label = { Text(stringResource(R.string.settings_server_url)) },
                placeholder = { Text("wss://pairdesk.example.com/ws") },
                singleLine = true,
                isError = vm.serverUrlError,
                supportingText = if (vm.serverUrlError) ({ Text(stringResource(R.string.settings_server_url_invalid)) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (vm.applyServer()) focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
            val applied = settings.networkMode == NetworkMode.SERVER && settings.serverUrl == vm.serverUrl.trim()
            Button(onClick = { if (vm.applyServer()) focus.clearFocus() }, enabled = !applied) {
                Text(stringResource(R.string.settings_apply))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(status.state)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(NetworkTexts.label(status).text(), style = MaterialTheme.typography.bodyMedium)
                Text(
                    NetworkTexts.detail(status).text(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ModeOption(selected: Boolean, title: String, description: String, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AboutSection(vm: SettingsViewModel) {
    SectionCard(title = stringResource(R.string.settings_about), icon = null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_version, vm.version), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
