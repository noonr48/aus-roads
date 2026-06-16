/*
 * How a traffic event should affect routing at query time. Lives in :core:model so
 * both :traffic:provider-api (which sets the effect on each event) and
 * :routing:engine-api (which applies it) can use it without depending on each other.
 */
package au.com.ausroads.core.model

sealed class RoutingEffect {
    data object None : RoutingEffect()
    data class DisplayOnly(val confidenceThreshold: Double = 0.5) : RoutingEffect()
    data class Penalty(val delayMinutes: Int) : RoutingEffect()
    data object Block : RoutingEffect()
}
