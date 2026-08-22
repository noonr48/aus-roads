package au.com.ausroads.data.reports

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import org.junit.Test

/*
 * Interface-shape test in the InstalledPackDaoTest style (:data:pack): pins'
 * suite has no in-memory Room harness, so the DAO/repository contract is
 * pinned by reflection here instead of spinning an instrumented database.
 */
class CommunityReportDaoTest {

    private val daoClass = CommunityReportDao::class.java

    @Test
    fun `interface has observeAll method returning Flow`() {
        val method = daoClass.declaredMethods.firstOrNull { it.name == "observeAll" }
        assertThat(method).isNotNull()
        assertThat(method!!.returnType).isEqualTo(Flow::class.java)
    }

    @Test
    fun `interface has findById method`() {
        assertThat(daoClass.declaredMethods.any { it.name == "findById" }).isTrue()
    }

    @Test
    fun `interface has insert method`() {
        assertThat(daoClass.declaredMethods.any { it.name == "insert" }).isTrue()
        // Suspend functions return Object at JVM level (Continuation-based)
    }

    @Test
    fun `interface has update method`() {
        assertThat(daoClass.declaredMethods.any { it.name == "update" }).isTrue()
    }

    @Test
    fun `interface has delete method`() {
        assertThat(daoClass.declaredMethods.any { it.name == "delete" }).isTrue()
    }

    @Test
    fun `interface has deleteExpired method`() {
        assertThat(daoClass.declaredMethods.any { it.name == "deleteExpired" }).isTrue()
    }
}
