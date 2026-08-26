package au.com.ausroads.ui.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards [resolveStartDestination]'s safe-degradation contract plus the
 * statically-checkable drift invariant between AusRoadsDestination.graphRoutes
 * and the composable(...) registrations hand-maintained inside
 * AusRoadsNavHost's NavHost builder.
 *
 * Nothing at compile time binds those registrations to [graphRoutes]: a
 * destination added to the sealed class without a registered screen (or the
 * reverse) previously drifted silently. These tests bind [graphRoutes] to the
 * mirrored list below — a mismatch between THOSE two artifacts fails here.
 * The NavHost composable(...) registrations themselves are outside any
 * assertion's reach: keeping that third artifact in lockstep is a review
 * responsibility, and genuine guard/registration drift still degrades safely
 * at runtime (the guard maps unknown roots to Map instead of crashing
 * NavHost construction).
 */
class ResolveStartDestinationTest {

    /**
     * Mirrors AusRoadsNavHost's composable(...) calls one-for-one (they reference
     * the same route constants). Update together with the NavHost builder.
     */
    private val navHostRegisteredRoutes = listOf(
        AusRoadsDestination.Map.route,
        AusRoadsDestination.Nearby.route,
        AusRoadsDestination.Pins.route,
        AusRoadsDestination.Settings.route,
        AusRoadsDestination.About.route,
    )

    // --- resolveStartDestination contract ----------------------------------

    @Test
    fun `registered tab routes pass through unchanged`() {
        AusRoadsDestination.bottomBarItems.forEach { destination ->
            assertThat(resolveStartDestination(destination.route))
                .isEqualTo(destination.route)
        }
    }

    @Test
    fun `registered non-tab route About passes through unchanged`() {
        // About IS registered (full-screen settings sub-page) though absent from
        // the bars: a restored back stack observing it must keep it, not get
        // silently relocated to Map.
        assertThat(resolveStartDestination(AusRoadsDestination.About.route))
            .isEqualTo(AusRoadsDestination.About.route)
    }

    @Test
    fun `unknown garbage route maps to Map`() {
        // Passing an unregistered route straight through crashes NavHost
        // construction; degradation to the root is mandatory.
        assertThat(resolveStartDestination("definitely-not-a-route"))
            .isEqualTo(AusRoadsDestination.Map.route)
    }

    @Test
    fun `blank route maps to Map`() {
        assertThat(resolveStartDestination("")).isEqualTo(AusRoadsDestination.Map.route)
        assertThat(resolveStartDestination("   ")).isEqualTo(AusRoadsDestination.Map.route)
    }

    @Test
    fun `null maps to Map`() {
        assertThat(resolveStartDestination(null)).isEqualTo(AusRoadsDestination.Map.route)
    }

    // --- drift invariants ---------------------------------------------------

    @Test
    fun `graphRoutes equals every bottom bar tab plus About`() {
        val expected = AusRoadsDestination.bottomBarItems.map { it.route }.toSet() +
            setOf(AusRoadsDestination.About.route)
        assertThat(AusRoadsDestination.graphRoutes).isEqualTo(expected)
    }

    @Test
    fun `graphRoutes size matches expected registered composable count`() {
        // Exactly one registered screen per graph route: 5 composables today.
        // Adding one side without the other shifts the count and fails here.
        assertThat(AusRoadsDestination.graphRoutes).hasSize(navHostRegisteredRoutes.size)
    }

    @Test
    fun `graphRoutes is exactly the NavHost-registered route set`() {
        assertThat(AusRoadsDestination.graphRoutes)
            .isEqualTo(navHostRegisteredRoutes.toSet())
    }

    @Test
    fun `graphRoutes entries are non-blank and pairwise distinct`() {
        val entries = AusRoadsDestination.graphRoutes.toList()
        entries.forEach { route ->
            assertThat(route).isNotEmpty()
        }
        // NavHost resolves by route string: two identical strings would make the
        // later composable(...) silently override the earlier one.
        assertThat(entries.distinct()).hasSize(entries.size)
    }

    @Test
    fun `every graphRoute round-trips through resolveStartDestination`() {
        // The registry mapping is consistent: anything advertised as reachable
        // must survive root-resolution unchanged.
        AusRoadsDestination.graphRoutes.forEach { route ->
            assertThat(resolveStartDestination(route)).isEqualTo(route)
        }
    }
}
