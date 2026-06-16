/*
 * Bbox validation contract. The Bbox is the only spatial type that crosses module
 * boundaries (traffic providers, map pack, routing), so its invariants are load-bearing.
 */
package au.com.ausroads.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BboxTest {

    @Test
    fun `accepts a valid bbox`() {
        val bbox = Bbox(west = 138.4, south = -35.2, east = 139.0, north = -34.6)

        assertThat(bbox.west).isEqualTo(138.4)
        assertThat(bbox.south).isEqualTo(-35.2)
        assertThat(bbox.east).isEqualTo(139.0)
        assertThat(bbox.north).isEqualTo(-34.6)
    }

    @Test
    fun `accepts a zero-area bbox (west == east and south == north)`() {
        val bbox = Bbox(west = 138.6, south = -34.9, east = 138.6, north = -34.9)

        assertThat(bbox.west).isEqualTo(bbox.east)
        assertThat(bbox.south).isEqualTo(bbox.north)
    }

    @Test
    fun `accepts the global bbox extremes`() {
        val bbox = Bbox(west = -180.0, south = -90.0, east = 180.0, north = 90.0)

        assertThat(bbox.west).isEqualTo(-180.0)
        assertThat(bbox.south).isEqualTo(-90.0)
        assertThat(bbox.east).isEqualTo(180.0)
        assertThat(bbox.north).isEqualTo(90.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects west greater than east`() {
        Bbox(west = 10.0, south = -1.0, east = 5.0, north = 1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects south greater than north`() {
        Bbox(west = 0.0, south = 5.0, east = 1.0, north = 1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects west out of range above 180`() {
        Bbox(west = 180.5, south = 0.0, east = 181.0, north = 1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects west out of range below -180`() {
        Bbox(west = -181.0, south = 0.0, east = 0.0, north = 1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects east out of range`() {
        Bbox(west = 0.0, south = 0.0, east = 200.0, north = 1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects north out of range above 90`() {
        Bbox(west = 0.0, south = 0.0, east = 1.0, north = 91.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects south out of range below -90`() {
        Bbox(west = 0.0, south = -91.0, east = 1.0, north = 0.0)
    }

    @Test
    fun `error messages name the offending field`() {
        val ex = runCatching {
            Bbox(west = 200.0, south = 0.0, east = 1.0, north = 1.0)
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ex).hasMessageThat().contains("west")
    }
}
