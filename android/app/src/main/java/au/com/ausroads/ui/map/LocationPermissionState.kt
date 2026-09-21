package au.com.ausroads.ui.map

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Both permissions must be requested together on Android 12 and newer. */
internal val ForegroundLocationPermissions = arrayOf(
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.ACCESS_FINE_LOCATION,
)

internal data class ForegroundLocationPermissionState(
    val isDeclared: Boolean,
    val isGranted: Boolean,
    val isPreciseGranted: Boolean,
    val foregroundNeedsSettings: Boolean,
    val preciseNeedsSettings: Boolean,
    val request: () -> Unit,
)

/**
 * One permission seam shared by Map and Nearby.
 *
 * Approximate location is sufficient for showing the user and browsing nearby
 * places. The state is refreshed on resume so a grant or revocation made in
 * Android Settings is reflected without restarting the app.
 */
@Composable
internal fun rememberForegroundLocationPermissionState(
    context: Context,
    onGranted: () -> Unit = {},
    onDenied: () -> Unit = {},
): ForegroundLocationPermissionState {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnGranted by rememberUpdatedState(onGranted)
    val currentOnDenied by rememberUpdatedState(onDenied)
    val isDeclared = remember(context) { context.isForegroundLocationDeclared() }
    val permissionPreferences = remember(context) {
        context.getSharedPreferences(PERMISSION_PREFERENCES, Context.MODE_PRIVATE)
    }
    var hasRequested by remember(context) {
        mutableStateOf(permissionPreferences.getBoolean(HAS_REQUESTED_LOCATION, false))
    }
    var isGranted by remember(context, isDeclared) {
        mutableStateOf(isDeclared && context.hasForegroundLocationPermission())
    }
    var isPreciseGranted by remember(context, isDeclared) {
        mutableStateOf(isDeclared && context.hasPreciseLocationPermission())
    }
    var foregroundNeedsSettings by remember { mutableStateOf(false) }
    var preciseNeedsSettings by remember { mutableStateOf(false) }

    fun refreshPermissionState() {
        isGranted = isDeclared && context.hasForegroundLocationPermission()
        isPreciseGranted = isDeclared && context.hasPreciseLocationPermission()
        val activity = context.findActivity()
        foregroundNeedsSettings = hasRequested && !isGranted && activity != null &&
            ForegroundLocationPermissions.none { permission ->
                ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            }
        preciseNeedsSettings = hasRequested && isGranted && !isPreciseGranted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasRequested = true
        permissionPreferences.edit().putBoolean(HAS_REQUESTED_LOCATION, true).apply()
        isGranted = grants.hasForegroundLocationGrant() || context.hasForegroundLocationPermission()
        isPreciseGranted = grants.hasPreciseLocationGrant() || context.hasPreciseLocationPermission()
        refreshPermissionState()
        if (isGranted) currentOnGranted() else currentOnDenied()
    }

    DisposableEffect(context, lifecycleOwner, isDeclared) {
        refreshPermissionState()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissionState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return ForegroundLocationPermissionState(
        isDeclared = isDeclared,
        isGranted = isGranted,
        isPreciseGranted = isPreciseGranted,
        foregroundNeedsSettings = foregroundNeedsSettings,
        preciseNeedsSettings = preciseNeedsSettings,
        request = {
            if (isDeclared) {
                if (foregroundNeedsSettings || (isGranted && preciseNeedsSettings)) {
                    context.openApplicationSettings()
                } else {
                    hasRequested = true
                    permissionPreferences.edit().putBoolean(HAS_REQUESTED_LOCATION, true).apply()
                    launcher.launch(ForegroundLocationPermissions)
                }
            }
        },
    )
}

internal fun Context.hasForegroundLocationPermission(): Boolean =
    ForegroundLocationPermissions.any { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

internal fun Context.hasPreciseLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

internal fun Context.isForegroundLocationDeclared(): Boolean = runCatching {
    packageManager
        .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions
        ?.any { it in ForegroundLocationPermissions } == true
}.getOrDefault(false)

internal fun Map<String, Boolean>.hasForegroundLocationGrant(): Boolean =
    ForegroundLocationPermissions.any { this[it] == true }

internal fun Map<String, Boolean>.hasPreciseLocationGrant(): Boolean =
    this[Manifest.permission.ACCESS_FINE_LOCATION] == true

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openApplicationSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private const val PERMISSION_PREFERENCES = "location-permission-state"
private const val HAS_REQUESTED_LOCATION = "has-requested-location"
