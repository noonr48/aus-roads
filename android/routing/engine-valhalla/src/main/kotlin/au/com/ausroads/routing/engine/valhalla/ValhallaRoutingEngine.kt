package au.com.ausroads.routing.engine.valhalla

import android.content.Context
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.core.model.RoutingEffect
import au.com.ausroads.routing.engine.CostingProfile
import au.com.ausroads.routing.engine.Maneuver
import au.com.ausroads.routing.engine.RouteOptions
import au.com.ausroads.routing.engine.RouteRequest
import au.com.ausroads.routing.engine.RouteResult
import au.com.ausroads.routing.engine.RoutingEngine
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.valhalla.api.models.AutoCostingOptions
import com.valhalla.api.models.CostingModel
import com.valhalla.api.models.CostingOptions
import com.valhalla.api.models.DirectionsOptions
import com.valhalla.api.models.DirectionsOptions.DirectionsType
import com.valhalla.api.models.RouteLeg
import com.valhalla.api.models.RouteManeuver
import com.valhalla.api.models.RouteRequest as ValhallaRouteRequest
import com.valhalla.api.models.RouteResponseTrip
import com.valhalla.api.models.RoutingWaypoint
import com.valhalla.config.models.ValhallaConfig
import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.ValhallaResponse
import com.valhalla.valhalla.config.ValhallaConfigManager
import com.valhalla.valhalla.files.ValhallaFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ValhallaRoutingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : RoutingEngine {

    // Written on the background routing-init thread (AusRoadsApp.onCreate), read
    // from route-computation coroutines on other threads. @Volatile makes the
    // initialised engine visible across threads (ARM has a weak memory model).
    @Volatile private var valhalla: Valhalla? = null
    @Volatile private var tilePath: String? = null

    /**
     * Initialize with the path to valhalla_tiles.tar.
     * Called when the routing pack component is available.
     */
    fun initialize(tarPath: String) {
        if (tilePath == tarPath && valhalla != null) return
        tilePath = tarPath

        // ValhallaConfigManager serializes the Kotlin ValhallaConfig via this Moshi.
        // Without KotlinJsonAdapterFactory, Moshi cannot reflectively serialize Kotlin
        // classes and writeConfig() throws, so initialize() must register it.
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val config = buildConfig(tarPath)
        val configFile = ValhallaFile(context, "valhalla.json", context.filesDir)
        val configManager = ValhallaConfigManager(context, configFile, moshi)
        configManager.writeConfig(config)
        valhalla = Valhalla(context, config, configManager, moshi)
    }

    /**
     * Build a complete Valhalla config with the given tile_extract.
     *
     * Hand-building the config (e.g. `ValhallaConfig().copy(mjolnir = ...)`) omits
     * nodes Valhalla requires at route time — it fails with errors like
     * "No such node (mjolnir)" or "No such node (service_limits.isochrone.max_contours)".
     * valhalla-models ships `ValhallaConfigBuilder`, whose default carries every
     * required node fully populated. Reach it via reflection and override only the
     * tile_extract.
     *
     * The class lives in the JVM default (unnamed) package in the pinned
     * valhalla-models 0.0.9, which Kotlin cannot import from a packaged file. NOTE:
     * it moves to `com.valhalla.config.ValhallaConfigBuilder` in valhalla-models
     * 0.2.0+ — if the dependency is ever bumped, update [CONFIG_BUILDER_CLASS_NAME].
     * (A try-FQN-first/fallback scheme was tried and rejected: a stray
     * com.valhalla.config builder on the classpath produced a config the 0.0.9
     * native lib could not parse.)
     */
    private fun buildConfig(tarPath: String): ValhallaConfig {
        val builderClass = Class.forName(CONFIG_BUILDER_CLASS_NAME)
        var builder = builderClass.getDeclaredConstructor().newInstance()
        builder = builderClass.getMethod("withTileExtract", String::class.java).invoke(builder, tarPath)
        return builderClass.getMethod("build").invoke(builder) as ValhallaConfig
    }

    override fun isReady(): Boolean = valhalla != null && tilePath != null

    override suspend fun computeRoute(request: RouteRequest): RouteResult =
        withContext(Dispatchers.IO) {
            val engine = valhalla
                ?: throw IllegalStateException("Routing engine not initialized; call initialize() first")

            val valhallaRequest = buildValhallaRequest(request)
            val response = engine.route(valhallaRequest)
            parseResponse(response)
        }

    private fun buildValhallaRequest(request: RouteRequest): ValhallaRouteRequest {
        val locations = mutableListOf<RoutingWaypoint>()
        locations.add(RoutingWaypoint(request.origin.latitude, request.origin.longitude))
        request.via.forEach { waypoint ->
            locations.add(RoutingWaypoint(waypoint.latitude, waypoint.longitude))
        }
        locations.add(RoutingWaypoint(request.destination.latitude, request.destination.longitude))

        val costing = when (request.costingProfile) {
            CostingProfile.AUTO -> CostingModel.auto
            CostingProfile.MOTORCYCLE -> CostingModel.motorcycle
            CostingProfile.TRUCK -> CostingModel.truck
            CostingProfile.BICYCLE -> CostingModel.bicycle
            CostingProfile.PEDESTRIAN -> CostingModel.pedestrian
        }

        val autoOptions = buildAutoCostingOptions(request.options)
        val avoidLocations = mutableListOf<RoutingWaypoint>()

        request.penalties.forEach { penalty ->
            when (penalty.effect) {
                is RoutingEffect.Block -> {
                    // Hard avoid — add all geometry points as avoid locations
                    penalty.geometry.forEach { point ->
                        avoidLocations.add(RoutingWaypoint(point.latitude, point.longitude))
                    }
                }
                is RoutingEffect.Penalty -> {
                    // Soft avoid — Valhalla mobile doesn't support per-geometry penalties.
                    // Use avoidLocations as best-effort: router finds alternatives but
                    // won't fail if no alternative exists.
                    penalty.geometry.forEach { point ->
                        avoidLocations.add(RoutingWaypoint(point.latitude, point.longitude))
                    }
                }
                else -> {} // None, DisplayOnly — no routing effect
            }
        }

        val costingOptions = CostingOptions(
            auto = autoOptions,
        )

        val avoidPolygons: List<List<List<Double>>> = request.avoidPolygons.map { polygon ->
            polygon.map { point ->
                listOf(point.longitude, point.latitude)
            }
        }

        val directionsOptions = DirectionsOptions(
            directionsType = DirectionsType.instructions,
        )

        return ValhallaRouteRequest(
            locations = locations,
            costing = costing,
            costingOptions = costingOptions,
            avoidLocations = avoidLocations,
            avoidPolygons = avoidPolygons,
            directionsOptions = directionsOptions,
        )
    }

    private fun parseResponse(response: ValhallaResponse): RouteResult {
        val routeResponse = when (response) {
            is ValhallaResponse.Json -> response.jsonResponse
            is ValhallaResponse.Osrm -> throw IllegalStateException("OSRM response format not supported")
            else -> throw IllegalStateException("Unexpected Valhalla response type: $response")
        }

        val trip: RouteResponseTrip = routeResponse.trip
        val summary = trip.summary
        val distanceMeters = summary.length.toInt()
        val durationSeconds = summary.time.toInt()

        val geometry = mutableListOf<GeoPoint>()
        val maneuvers = mutableListOf<Maneuver>()

        trip.legs.forEach { leg: RouteLeg ->
            geometry.addAll(decodePolyline(leg.shape))

            leg.maneuvers.forEach { m: RouteManeuver ->
                maneuvers.add(
                    Maneuver(
                        instruction = m.instruction,
                        lengthMeters = m.length.toInt(),
                        durationSeconds = m.time.toInt(),
                        beginShapeIndex = m.beginShapeIndex,
                        streetName = m.streetNames?.firstOrNull(),
                        maneuverType = m.type.toString(),
                    )
                )
            }
        }

        return RouteResult(
            distanceMeters = distanceMeters,
            durationSeconds = durationSeconds,
            geometry = geometry,
            maneuvers = maneuvers,
            warnings = emptyList(),
        )
    }

    companion object {
        // Default/unnamed package in the pinned valhalla-models 0.0.9.
        // Moves to "com.valhalla.config.ValhallaConfigBuilder" in 0.2.0+.
        private const val CONFIG_BUILDER_CLASS_NAME = "ValhallaConfigBuilder"

        /**
         * Translate user [RouteOptions] into Valhalla auto-costing options.
         *
         * Each Valhalla `use*` factor ranges 0.0 (avoid) … 1.0 (prefer); leaving a
         * factor `null` keeps Valhalla's built-in default. We only set a factor when
         * the user opted to avoid that way type:
         *  - [RouteOptions.avoidTolls]    -> useTolls  = 0.0
         *  - [RouteOptions.avoidFerries]  -> useFerry  = 0.0
         *  - [RouteOptions.avoidUnsealed] -> useTracks = 0.0  (no 4WD/sealed boolean
         *    exists in the model; avoiding `tracks` is the closest approximation)
         *
         * Pure and side-effect-free so it can be unit-tested in isolation.
         */
        internal fun buildAutoCostingOptions(options: RouteOptions): AutoCostingOptions =
            AutoCostingOptions(
                useTolls = if (options.avoidTolls) 0.0 else null,
                useFerry = if (options.avoidFerries) 0.0 else null,
                useTracks = if (options.avoidUnsealed) 0.0 else null,
            )

        /**
         * Decode a Valhalla polyline string (precision 6) to GeoPoints.
         */
        internal fun decodePolyline(encoded: String): List<GeoPoint> {
            val points = mutableListOf<GeoPoint>()
            var lat = 0
            var lon = 0
            var index = 0

            while (index < encoded.length) {
                var b: Int
                var shift = 0
                var result = 0
                do {
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

                shift = 0
                result = 0
                do {
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1

                points.add(GeoPoint(longitude = lon / 1e6, latitude = lat / 1e6))
            }

            return points
        }
    }
}
