package au.com.ausroads.ui.nearby

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.app.Instrumentation
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.com.ausroads.MainActivity
import au.com.ausroads.R
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import java.util.Locale
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android URI/intent boundary; controlled receiver, never an external app or owner data. */
@RunWith(AndroidJUnit4::class)
class LocationShareInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun guestOnly() { check(Build.MODEL.startsWith("sdk_gphone")) }

    @Test fun actualEssentialsShareRecordsAcceptedAndRejectedIntentDispatch() {
        compose.onAllNodesWithText(context.getString(R.string.nav_explore)).onLast().performClick()
        compose.onNodeWithText(context.getString(R.string.explore_essentials)).performClick()
        val description = context.getString(R.string.nearby_share_location)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intents = CopyOnWriteArrayList<Intent>()
        val receiver = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                intents += intent
                return Instrumentation.ActivityResult(0, null)
            }
        }
        val rejecting = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_CHOOSER)
                    throw ActivityNotFoundException("controlled guest receiver")
                return null
            }
        }
        val blocker = Instrumentation.ActivityMonitor(IntentFilter(Intent.ACTION_CHOOSER),
            Instrumentation.ActivityResult(0, null), true)
        val witness = File(context.noBackupFilesDir, "places-health.json")
        try {
            // A second blocking monitor prevents publication even if an SDK ever
            // stops invoking the callback contract under test.
            instrumentation.addMonitor(receiver)
            instrumentation.addMonitor(blocker)
            val acceptedAt = System.currentTimeMillis()
            compose.onAllNodesWithContentDescription(description).onFirst().performClick()
            compose.waitUntil(5_000) { intents.isNotEmpty() }
            awaitShareWitness(witness, "last_success_ms", acceptedAt)
            @Suppress("DEPRECATION")
            val intent = requireNotNull(intents.single().getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            assertThat(intent.data?.scheme).isEqualTo("geo")

            instrumentation.removeMonitor(receiver)
            instrumentation.removeMonitor(blocker)
            instrumentation.addMonitor(rejecting)
            instrumentation.addMonitor(blocker)
            val rejectedAt = System.currentTimeMillis()
            compose.onAllNodesWithContentDescription(description).onFirst().performClick()
            awaitShareWitness(witness, "last_failure_ms", rejectedAt)
            val row = JSONObject(witness.readText()).getJSONObject("LOCATION_SHARE")
            assertThat(row.getString("last_error")).isEqualTo("seam_failed")
            assertThat(row.toString()).doesNotContain("Hospital")
            assertThat(row.toString()).doesNotContain("geo:")
            compose.onNodeWithText(context.getString(R.string.nearby_emergency_title)).assertExists()
        } finally {
            instrumentation.removeMonitor(receiver)
            instrumentation.removeMonitor(rejecting)
            instrumentation.removeMonitor(blocker)
        }
    }

    private fun awaitShareWitness(file: File, field: String, since: Long) {
        compose.waitUntil(5_000) {
            runCatching { JSONObject(file.readText()).getJSONObject("LOCATION_SHARE").optLong(field) >= since }
                .getOrDefault(false)
        }
    }

    @Test fun venueDelimitersAndUnicodeStayLiteralInsideOneGeoQuery() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val receiver = Receiver(context)
            val label = "Keith & District Hospital #1 (Café) 50%?+"
            val dispatched = share(receiver, label)
            val chooser = requireNotNull(receiver.started)
            assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
            @Suppress("DEPRECATION")
            val target = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            assertThat(target.action).isEqualTo(Intent.ACTION_VIEW)
            val uri = requireNotNull(target.data)
            val coords = "-36.096725,140.345277"
            assertThat(uri.scheme).isEqualTo("geo")
            assertThat(uri.encodedSchemeSpecificPart)
                .isEqualTo("$coords?q=${Uri.encode("$coords($label)")}")
            assertThat(Uri.decode(uri.encodedSchemeSpecificPart.substringAfter("?q=")))
                .isEqualTo("$coords($label)")
            assertThat(dispatched).isEqualTo(true)
        } finally { Locale.setDefault(previous) }
    }

    @Test fun missingMapReceiverIsAnExplicitRejectedDispatch() {
        val receiver = Receiver(context, ActivityNotFoundException("controlled guest receiver"))
        assertThat(share(receiver, null)).isEqualTo(false)
        assertThat(receiver.started).isNull()
    }

    @Test fun deniedMapReceiverIsAnExplicitRejectedDispatch() {
        val receiver = Receiver(context, SecurityException("controlled guest receiver"))
        assertThat(share(receiver, "Hospital")).isEqualTo(false)
        assertThat(receiver.started).isNull()
    }

    private fun share(receiver: Context, label: String?): Any? {
        var outcome: Any? = null
        // Retains the same existing private dispatcher for the before/after control.
        // No source replacement, fake screen or real third-party publication occurs.
        val method = Class.forName("au.com.ausroads.ui.nearby.NearbyScreenKt")
            .getDeclaredMethod("shareLocation", Context::class.java,
                Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, String::class.java)
            .apply { isAccessible = true }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            outcome = method.invoke(null, receiver, -36.096725, 140.3452774, label)
        }
        return outcome
    }

    private class Receiver(context: Context, private val failure: RuntimeException? = null) :
        ContextWrapper(context) {
        var started: Intent? = null
        override fun startActivity(intent: Intent) {
            failure?.let { throw it }
            started = intent
        }
    }
}
