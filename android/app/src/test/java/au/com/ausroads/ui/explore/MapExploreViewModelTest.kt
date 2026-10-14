package au.com.ausroads.ui.explore

import androidx.lifecycle.SavedStateHandle
import au.com.ausroads.di.PlacesHealth
import au.com.ausroads.di.PlacesSeam
import au.com.ausroads.offline.search.SearchResult
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class MapExploreViewModelTest {
    @Test
    fun `map area survives state recreation with named coordinates`() {
        val saved = SavedStateHandle()
        val health = mockk<PlacesHealth>(relaxed = true)
        val first = MapExploreViewModel(saved, health)
        first.updateCenter(-35.0, 138.8)
        val restored = MapExploreViewModel(saved, health)
        assertThat(restored.reference.value.latitude).isEqualTo(-35.0)
        assertThat(restored.reference.value.longitude).isEqualTo(138.8)
    }

    @Test
    fun `selection is one shot and stale acknowledgement cannot eat a newer selection`() {
        val health = mockk<PlacesHealth>(relaxed = true)
        val vm = MapExploreViewModel(SavedStateHandle(), health)
        val old = SearchResult("Old", "poi", "amenity=cafe", -34.92, 138.62)
        val current = old.copy(name = "New")
        vm.openOnMap(old)
        vm.openOnMap(current)
        vm.consumeSelection(old)
        assertThat(vm.selection.value).isEqualTo(current)
        vm.consumeSelection(current)
        assertThat(vm.selection.value).isNull()
        verify(exactly = 1) { health.success(PlacesSeam.MAP_SELECTION) }
    }
}
