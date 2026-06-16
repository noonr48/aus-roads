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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.tooling.preview.Preview
import au.com.ausroads.R
import au.com.ausroads.data.pins.Pin

// Deliberately avoids the traffic-severity hues (#4CAF50/#FF9800/#F44336/#9C27B0)
// and the user-location blue so a pin is never confused with a traffic marker or
// the position dot. No generic Material rainbow / purple.
private val PIN_COLORS = listOf(
    "#1B5E20", // green
    "#00838F", // cyan
    "#6D4C41", // brown
    "#C2185B", // magenta
    "#455A64", // slate
    "#283593", // indigo
)

@Composable
fun PinsScreen(
    modifier: Modifier = Modifier,
    viewModel: PinListViewModel = hiltViewModel(),
) {
    val pins by viewModel.pins.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var pinToEdit by remember { mutableStateOf<Pin?>(null) }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.pin_add))
            }
        },
    ) { padding ->
        if (pins.isEmpty()) {
            EmptyState(modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(pins, key = { it.id }) { pin ->
                    PinItem(
                        pin = pin,
                        onDelete = { viewModel.delete(pin) },
                        onEdit = { pinToEdit = pin },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddPinDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, lat, lon, color ->
                viewModel.addPinAt(longitude = lon, latitude = lat, name = name, color = color)
                showAddDialog = false
            },
        )
    }

    pinToEdit?.let { pin ->
        EditPinSheet(
            pin = pin,
            onDismiss = { pinToEdit = null },
            onConfirm = { newName, newColor ->
                viewModel.updatePin(pin, newName, newColor)
                pinToEdit = null
            },
            onDelete = {
                viewModel.delete(pin)
                pinToEdit = null
            },
        )
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
private fun PinItem(
    pin: Pin,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = pin.name
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
                    .background(Color(android.graphics.Color.parseColor(pin.color))),
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
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.pin_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ColorPickerRow(
    selectedColor: String,
    onColorSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        PIN_COLORS.forEach { colorHex ->
            val isSelected = colorHex == selectedColor
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(android.graphics.Color.parseColor(colorHex)))
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    .semantics { contentDescription = colorHex }
                    .clickable { onColorSelected(colorHex) },
            )
        }
    }
}

@Composable
private fun AddPinDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, lat: Double, lon: Double, color: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var latText by remember { mutableStateOf("-34.9285") }
    var lonText by remember { mutableStateOf("138.6007") }
    var selectedColor by remember { mutableStateOf("#1B5E20") }

    val latValid = latText.toDoubleOrNull() != null
    val lonValid = lonText.toDoubleOrNull() != null
    val canSubmit = name.isNotBlank() && latValid && lonValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pin_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.pin_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latText,
                        onValueChange = { latText = it },
                        label = { Text(stringResource(R.string.pin_latitude_label)) },
                        singleLine = true,
                        isError = !latValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = lonText,
                        onValueChange = { lonText = it },
                        label = { Text(stringResource(R.string.pin_longitude_label)) },
                        singleLine = true,
                        isError = !lonValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val lat = latText.toDoubleOrNull() ?: return@TextButton
                    val lon = lonText.toDoubleOrNull() ?: return@TextButton
                    onConfirm(name.trim(), lat, lon, selectedColor)
                },
                enabled = canSubmit,
            ) {
                Text(stringResource(R.string.pin_add_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditPinSheet(
    pin: Pin,
    onDismiss: () -> Unit,
    onConfirm: (newName: String, newColor: String) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(pin.name) }
    var selectedColor by remember { mutableStateOf(pin.color) }

    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.pin_color_label),
                style = MaterialTheme.typography.labelMedium,
            )
            ColorPickerRow(
                selectedColor = selectedColor,
                onColorSelected = { selectedColor = it },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDelete,
                ) {
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
                    onClick = { onConfirm(name.trim(), selectedColor) },
                    enabled = name.isNotBlank(),
                ) {
                    Text(stringResource(R.string.pin_rename_confirm))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PinsScreenPreview() {
    au.com.ausroads.ui.designsystem.AusRoadsTheme {
        PinsScreen()
    }
}
