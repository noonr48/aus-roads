/*
 * RoutingEffect is a sealed class. Equality semantics matter because the traffic
 * pipeline dedupes by event equality and the routing engine matches by effect. Data
 * classes' structural equality is what we want; this test pins it down.
 */
package au.com.ausroads.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RoutingEffectTest {

    @Test
    fun `None equals None`() {
        assertThat(RoutingEffect.None).isEqualTo(RoutingEffect.None)
    }

    @Test
    fun `Block equals Block`() {
        assertThat(RoutingEffect.Block).isEqualTo(RoutingEffect.Block)
    }

    @Test
    fun `Penalty with same delay is equal`() {
        assertThat(RoutingEffect.Penalty(5)).isEqualTo(RoutingEffect.Penalty(5))
    }

    @Test
    fun `Penalty with different delay is not equal`() {
        assertThat(RoutingEffect.Penalty(5)).isNotEqualTo(RoutingEffect.Penalty(10))
    }

    @Test
    fun `Block is not equal to None`() {
        assertThat(RoutingEffect.Block).isNotEqualTo(RoutingEffect.None)
    }

    @Test
    fun `None is not equal to Penalty`() {
        assertThat(RoutingEffect.None).isNotEqualTo(RoutingEffect.Penalty(0))
    }

    @Test
    fun `DisplayOnly with same threshold is equal`() {
        assertThat(RoutingEffect.DisplayOnly(0.5))
            .isEqualTo(RoutingEffect.DisplayOnly(0.5))
    }

    @Test
    fun `DisplayOnly with different threshold is not equal`() {
        assertThat(RoutingEffect.DisplayOnly(0.5))
            .isNotEqualTo(RoutingEffect.DisplayOnly(0.7))
    }

    @Test
    fun `Penalty has correct hashCode for equal instances`() {
        assertThat(RoutingEffect.Penalty(5).hashCode())
            .isEqualTo(RoutingEffect.Penalty(5).hashCode())
    }
}
