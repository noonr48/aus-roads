package au.com.ausroads.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import au.com.ausroads.R

internal sealed interface MapLoadState {
    data object Loading : MapLoadState
    data object Ready : MapLoadState
    data object Failed : MapLoadState
}

@Composable
internal fun MapLoadOverlay(
    failed: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(24.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!failed) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            }
            Text(
                text = stringResource(if (failed) R.string.map_load_failed else R.string.map_loading),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            if (failed) {
                Button(onClick = onOpenSettings) {
                    Text(stringResource(R.string.map_manage_pack))
                }
            }
        }
    }
}

@Composable
internal fun HoldToPinHint(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = Color(0xE6263238),
        shadowElevation = 3.dp,
    ) {
        Text(
            text = stringResource(R.string.map_hold_to_pin),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
