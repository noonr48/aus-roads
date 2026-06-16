package au.com.ausroads.data.pack

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import org.junit.Test

class InstalledPackDaoTest {

    private val daoClass = InstalledPackDao::class.java

    @Test
    fun `interface has observeAll method returning Flow`() {
        val method = daoClass.declaredMethods.firstOrNull { it.name == "observeAll" }
        assertThat(method).isNotNull()
        assertThat(method!!.returnType).isEqualTo(Flow::class.java)
    }

    @Test
    fun `interface has observeByRegion method`() {
        val method = daoClass.declaredMethods.firstOrNull { it.name == "observeByRegion" }
        assertThat(method).isNotNull()
        assertThat(method!!.returnType).isEqualTo(Flow::class.java)
    }

    @Test
    fun `interface has findByRegion method`() {
        val method = daoClass.declaredMethods.firstOrNull { it.name == "findByRegion" }
        assertThat(method).isNotNull()
    }

    @Test
    fun `interface has upsert method`() {
        val method = daoClass.declaredMethods.firstOrNull { it.name == "upsert" }
        assertThat(method).isNotNull()
        // Suspend functions return Object at JVM level (Continuation-based)
    }

    @Test
    fun `interface has deleteForRegion method`() {
        val method = daoClass.declaredMethods.firstOrNull { it.name == "deleteForRegion" }
        assertThat(method).isNotNull()
    }
}
