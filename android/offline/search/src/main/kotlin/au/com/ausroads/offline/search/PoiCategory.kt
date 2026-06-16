package au.com.ausroads.offline.search

/**
 * A user-facing point-of-interest category, mapped to the raw OSM `class` tag
 * value(s) stored in `search_meta.class` for the offline search pack.
 *
 * Each category may span more than one OSM tag (e.g. [HOSPITAL] covers both
 * `amenity=hospital` and `amenity=clinic`). [classValues] is the exact set of
 * `class` strings used to filter rows in
 * [SearchRepository.browseByCategory] / [SearchRepository.nearestByCategory].
 *
 * The strings are matched verbatim against the pack data, so they must stay in
 * sync with the pack builder's tag emission (the `key=value` form, e.g.
 * `amenity=fuel`). This type is pure data — no I/O, no platform dependencies.
 */
enum class PoiCategory(val classValues: List<String>) {
    /** Fuel / petrol stations (`amenity=fuel`). */
    FUEL(listOf("amenity=fuel")),

    /** Electric-vehicle charging stations (`amenity=charging_station`). */
    EV_CHARGING(listOf("amenity=charging_station")),

    /** Hospitals and clinics (`amenity=hospital`, `amenity=clinic`). */
    HOSPITAL(listOf("amenity=hospital", "amenity=clinic")),

    /** Pharmacies / chemists (`amenity=pharmacy`). */
    PHARMACY(listOf("amenity=pharmacy")),

    /** Police stations (`amenity=police`). */
    POLICE(listOf("amenity=police")),

    /** Fire stations (`amenity=fire_station`). */
    FIRE_STATION(listOf("amenity=fire_station")),

    /** Public toilets (`amenity=toilets`). */
    TOILETS(listOf("amenity=toilets")),

    /** Drinking-water points (`amenity=drinking_water`). */
    DRINKING_WATER(listOf("amenity=drinking_water")),

    /** Camp and caravan sites (`tourism=camp_site`, `tourism=caravan_site`). */
    CAMPING(listOf("tourism=camp_site", "tourism=caravan_site")),

    /** Supermarkets (`shop=supermarket`). */
    SUPERMARKET(listOf("shop=supermarket")),
}
