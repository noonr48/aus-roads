@file:Suppress("LargeClass")

package au.com.ausroads.ui.pins

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.tooling.preview.Preview
import au.com.ausroads.R
import au.com.ausroads.data.pins.Pin

@Composable
fun PinsScreen(
    modifier: Modifier = Modifier,
    viewModel: PinListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var isAddingPin by remember { mutableStateOf(false) }
    var addPinError by remember { mutableStateOf<String?>(null) }
    val pinSaveFailedMessage = stringResource(R.string.map_pin_save_failed)
    var pinToEdit by remember { mutableStateOf<Pin?>(null) }
    var editMutation by remember { mutableStateOf<PinEditMutation?>(null) }
    var editPinError by remember { mutableStateOf<String?>(null) }
    var listDeletingPinId by remember { mutableStateOf<Long?>(null) }
    var listDeleteErrorPin by remember { mutableStateOf<Pin?>(null) }
    var deleteConfirmation by remember { mutableStateOf<PinDeleteRequest?>(null) }

    val deletePinFromList: (Pin) -> Unit = { pin ->
        listDeletingPinId = pin.id
        listDeleteErrorPin = null
        viewModel.delete(
            pin = pin,
            onDeleted = { listDeletingPinId = null },
            onError = {
                listDeletingPinId = null
                listDeleteErrorPin = pin
            },
        )
    }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.pin_add))
            }
        },
    ) { padding ->
        when (val state = uiState) {
            PinListUiState.Loading -> {
                PinsReadState(
                    failed = false,
                    onRetry = viewModel::retrySavedPins,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }

            PinListUiState.ReadError -> {
                PinsReadState(
                    failed = true,
                    onRetry = viewModel::retrySavedPins,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }

            is PinListUiState.Content -> {
                if (state.pins.isEmpty()) {
                    EmptyState(modifier = Modifier.fillMaxSize().padding(padding))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listDeleteErrorPin?.let { failedPin ->
                            item(key = "pin-delete-error") {
                                PinMutationError(
                                    message = stringResource(
                                        R.string.pin_delete_failed,
                                        failedPin.name,
                                    ),
                                    onRetry = { deletePinFromList(failedPin) },
                                )
                            }
                        }
                        items(state.pins, key = { it.id }) { pin ->
                            PinItem(
                                pin = pin,
                                isDeleting = listDeletingPinId == pin.id,
                                onDelete = {
                                    deleteConfirmation = PinDeleteRequest(
                                        pin = pin,
                                        fromEditor = false,
                                    )
                                },
                                onEdit = {
                                    editMutation = null
                                    editPinError = null
                                    pinToEdit = pin
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddPinDialog(
            isSaving = isAddingPin,
            error = addPinError,
            onDismiss = {
                if (!isAddingPin) {
                    showAddDialog = false
                    addPinError = null
                }
            },
            onConfirm = { name, lat, lon, color ->
                isAddingPin = true
                addPinError = null
                viewModel.addPinAt(
                    longitude = lon,
                    latitude = lat,
                    name = name,
                    color = color,
                    onSaved = {
                        isAddingPin = false
                        showAddDialog = false
                    },
                    onError = {
                        isAddingPin = false
                        addPinError = pinSaveFailedMessage
                    },
                )
            },
        )
    }

    pinToEdit?.let { pin ->
        val updateFailureMessage = stringResource(R.string.pin_update_failed, pin.name)
        EditPinSheet(
            pin = pin,
            mutation = editMutation,
            error = editPinError,
            actions = EditPinActions(
                onDismiss = {
                    if (editMutation == null) {
                        editPinError = null
                        pinToEdit = null
                    }
                },
                onConfirm = { newName, newColor ->
                    editMutation = PinEditMutation.UPDATE
                    editPinError = null
                    viewModel.updatePin(
                        pin = pin,
                        newName = newName,
                        newColor = newColor,
                        onUpdated = {
                            editMutation = null
                            pinToEdit = null
                        },
                        onError = {
                            editMutation = null
                            editPinError = updateFailureMessage
                        },
                    )
                },
                onDelete = {
                    deleteConfirmation = PinDeleteRequest(pin = pin, fromEditor = true)
                },
            ),
        )
    }

    deleteConfirmation?.let { request ->
        val deleteFailureMessage = stringResource(R.string.pin_delete_failed, request.pin.name)
        AlertDialog(
            onDismissRequest = { deleteConfirmation = null },
            title = {
                Text(stringResource(R.string.pin_delete_confirm_title, request.pin.name))
            },
            text = { Text(stringResource(R.string.pin_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirmation = null
                        if (request.fromEditor) {
                            editMutation = PinEditMutation.DELETE
                            editPinError = null
                            viewModel.delete(
                                pin = request.pin,
                                onDeleted = {
                                    editMutation = null
                                    pinToEdit = null
                                },
                                onError = {
                                    editMutation = null
                                    editPinError = deleteFailureMessage
                                },
                            )
                        } else {
                            deletePinFromList(request.pin)
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.pin_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmation = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private enum class PinEditMutation {
    UPDATE,
    DELETE,
}

private data class PinDeleteRequest(
    val pin: Pin,
    val fromEditor: Boolean,
)

private data class EditPinActions(
    val onDismiss: () -> Unit,
    val onConfirm: (newName: String, newColor: String) -> Unit,
    val onDelete: () -> Unit,
)

@Composable
private fun PinMutationError(
    message: String,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.pin_try_again))
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.pins_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.pins_empty),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
        )
    }
}

@Composable
private fun PinsReadState(
    failed: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (failed) {
            Text(
                text = stringResource(R.string.pins_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.pins_load_failed),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
            )
            TextButton(
                onClick = onRetry,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.pins_retry_load))
            }
        } else {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.pins_loading),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun PinItem(
    pin: Pin,
    isDeleting: Boolean,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    val editDescription = stringResource(R.string.pin_edit, pin.name)
    val deleteDescription = stringResource(R.string.pin_delete_named, pin.name)
    val pinColor = remember(pin.color) {
        runCatching { Color(android.graphics.Color.parseColor(pin.color)) }
            .getOrDefault(Color.Gray)
    }
    Card(
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = editDescription
        }.clickable { onEdit() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(pinColor),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = pin.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "(%.4f, %.4f)".format(pin.lat, pin.lon),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete, enabled = !isDeleting) {
                if (isDeleting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = deleteDescription,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorPickerRow(
    selectedColor: String,
    onColorSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        PIN_COLOR_CHOICES.forEach { choice ->
            val isSelected = choice.hex == selectedColor
            val colorName = stringResource(choice.nameRes)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(android.graphics.Color.parseColor(choice.hex)))
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onColorSelected(choice.hex) },
                    )
                    .semantics { contentDescription = colorName },
            )
        }
    }
}

@Composable
private fun AddPinDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, lat: Double, lon: Double, color: String) -> Unit,
    isSaving: Boolean,
    error: String?,
) {
    var name by remember { mutableStateOf("") }
    var latText by remember { mutableStateOf("-34.9285") }
    var lonText by remember { mutableStateOf("138.6007") }
    var selectedColor by remember { mutableStateOf(PIN_COLOR_CHOICES.first().hex) }
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    var nameFocused by remember { mutableStateOf(false) }
    var nameBlurred by remember { mutableStateOf(false) }
    var latitudeFocused by remember { mutableStateOf(false) }
    var latitudeBlurred by remember { mutableStateOf(false) }
    var longitudeFocused by remember { mutableStateOf(false) }
    var longitudeBlurred by remember { mutableStateOf(false) }

    val latValid = isValidLatitude(latText.toDoubleOrNull())
    val lonValid = isValidLongitude(lonText.toDoubleOrNull())
    val canSubmit = name.isNotBlank() && latValid && lonValid
    val showNameError = nameBlurred && name.isBlank()
    val showLatitudeError = latitudeBlurred && !latValid
    val showLongitudeError = longitudeBlurred && !lonValid
    val hasUnsavedChanges = name.isNotBlank() ||
        latText != "-34.9285" ||
        lonText != "138.6007" ||
        selectedColor != PIN_COLOR_CHOICES.first().hex
    val requestDismiss: () -> Unit = {
        if (!isSaving) {
            if (hasUnsavedChanges) showDiscardConfirmation = true else onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = requestDismiss,
        title = { Text(stringResource(R.string.pin_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.pin_name_label)) },
                    singleLine = true,
                    isError = showNameError,
                    supportingText = if (showNameError) {
                        { Text(stringResource(R.string.pin_name_required)) }
                    } else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            if (nameFocused && !focusState.isFocused) nameBlurred = true
                            nameFocused = focusState.isFocused
                        },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latText,
                        onValueChange = { latText = it },
                        label = { Text(stringResource(R.string.pin_latitude_label)) },
                        singleLine = true,
                        isError = showLatitudeError,
                        supportingText = if (showLatitudeError) {
                            { Text(stringResource(R.string.pin_latitude_range)) }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { focusState ->
                                if (latitudeFocused && !focusState.isFocused) latitudeBlurred = true
                                latitudeFocused = focusState.isFocused
                            },
                    )
                    OutlinedTextField(
                        value = lonText,
                        onValueChange = { lonText = it },
                        label = { Text(stringResource(R.string.pin_longitude_label)) },
                        singleLine = true,
                        isError = showLongitudeError,
                        supportingText = if (showLongitudeError) {
                            { Text(stringResource(R.string.pin_longitude_range)) }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { focusState ->
                                if (longitudeFocused && !focusState.isFocused) longitudeBlurred = true
                                longitudeFocused = focusState.isFocused
                            },
                    )
                }
                Text(
                    text = stringResource(R.string.pin_color_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                ColorPickerRow(
                    selectedColor = selectedColor,
                    onColorSelected = { selectedColor = it },
                )
                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val lat = latText.toDoubleOrNull() ?: return@TextButton
                    val lon = lonText.toDoubleOrNull() ?: return@TextButton
                    onConfirm(name.trim(), lat, lon, selectedColor)
                },
                enabled = canSubmit && !isSaving,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.pin_add_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = requestDismiss, enabled = !isSaving) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
    if (showDiscardConfirmation) {
        DiscardPinDraftDialog(
            onKeepEditing = { showDiscardConfirmation = false },
            onDiscard = onDismiss,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditPinSheet(
    pin: Pin,
    mutation: PinEditMutation?,
    error: String?,
    actions: EditPinActions,
) {
    var name by remember { mutableStateOf(pin.name) }
    var selectedColor by remember { mutableStateOf(pin.color) }
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    var nameFocused by remember { mutableStateOf(false) }
    var nameBlurred by remember { mutableStateOf(false) }

    val isUpdating = mutation == PinEditMutation.UPDATE
    val isDeleting = mutation == PinEditMutation.DELETE
    val isMutating = mutation != null
    val currentMutation by rememberUpdatedState(mutation)
    val hasUnsavedChanges = name != pin.name || selectedColor != pin.color
    val currentHasUnsavedChanges by rememberUpdatedState(hasUnsavedChanges)
    val showNameError = nameBlurred && name.isBlank()
    val sheetState = rememberModalBottomSheetState(
        confirmValueChange = { target ->
            when {
                target != SheetValue.Hidden -> true
                currentMutation != null -> false
                currentHasUnsavedChanges -> {
                    showDiscardConfirmation = true
                    false
                }
                else -> true
            }
        },
    )
    val requestDismiss: () -> Unit = {
        if (!isMutating) {
            if (hasUnsavedChanges) showDiscardConfirmation = true else actions.onDismiss()
        }
    }

    LaunchedEffect(showDiscardConfirmation) {
        if (!showDiscardConfirmation) {
            sheetState.show()
        }
    }

    ModalBottomSheet(
        onDismissRequest = requestDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.pin_edit_title),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.pin_name_label)) },
                singleLine = true,
                enabled = !isMutating,
                isError = showNameError,
                supportingText = if (showNameError) {
                    { Text(stringResource(R.string.pin_name_required)) }
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (nameFocused && !focusState.isFocused) nameBlurred = true
                        nameFocused = focusState.isFocused
                    },
            )
            Text(
                text = stringResource(R.string.pin_color_label),
                style = MaterialTheme.typography.labelMedium,
            )
            ColorPickerRow(
                selectedColor = selectedColor,
                onColorSelected = { selectedColor = it },
                enabled = !isMutating,
            )
            error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = actions.onDelete,
                    enabled = !isMutating,
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(R.string.pin_delete),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                TextButton(
                    onClick = { actions.onConfirm(name.trim(), selectedColor) },
                    enabled = name.isNotBlank() && !isMutating,
                ) {
                    if (isUpdating) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.size(4.dp))
                    }
                    Text(stringResource(R.string.pin_rename_confirm))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
    if (showDiscardConfirmation) {
        DiscardPinDraftDialog(
            onKeepEditing = { showDiscardConfirmation = false },
            onDiscard = actions.onDismiss,
        )
    }
}

@Composable
private fun DiscardPinDraftDialog(
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(R.string.pin_discard_confirm_title)) },
        text = { Text(stringResource(R.string.pin_discard_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text(
                    text = stringResource(R.string.pin_discard),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) {
                Text(stringResource(R.string.pin_keep_editing))
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun PinsScreenPreview() {
    au.com.ausroads.ui.designsystem.AusRoadsTheme {
        PinsScreen()
    }
}
