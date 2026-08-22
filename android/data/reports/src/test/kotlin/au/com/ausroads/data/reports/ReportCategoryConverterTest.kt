package au.com.ausroads.data.reports

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/*
 * Category TypeConverter test: every enum value must survive a String
 * round-trip, and an unknown stored name must fail loudly (valueOf throws)
 * rather than silently corrupting a report row.
 */
class ReportCategoryConverterTest {

    private val converters = ReportCategoryConverters()

    @Test
    fun `every category round-trips through its name string`() {
        for (category in ReportCategory.entries) {
            val stored = converters.fromCategory(category)
            val restored = converters.toCategory(stored)

            assertThat(stored).isEqualTo(category.name)
            assertThat(restored).isEqualTo(category)
        }
    }

    @Test
    fun `stored names match the spec vocabulary exactly`() {
        assertThat(ReportCategory.entries.map { it.name }).containsExactly(
            "SHOP",
            "ACCIDENT",
            "MOBILE_SPEED_CAMERA",
            "HAZARD",
        ).inOrder()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown stored name fails loudly instead of silently corrupting a row`() {
        converters.toCategory("NOT_A_REAL_CATEGORY")
    }
}
