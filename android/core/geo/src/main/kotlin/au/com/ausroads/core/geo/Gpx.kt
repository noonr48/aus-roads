/*
 * GPX 1.1 (topografix) serialise + parse using the JDK's javax.xml DOM. Pure JVM —
 * deliberately NOT android.util.Xml / XmlPullParser, so it runs in plain unit tests and
 * on any JVM. Schema: http://www.topografix.com/GPX/1/1
 */
package au.com.ausroads.core.geo

import kotlinx.datetime.Instant
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** A GPX `<wpt>` waypoint. Latitude/longitude are degrees (WGS84). */
data class GpxWaypoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double? = null,
    val time: Instant? = null,
    val name: String? = null,
)

/** A single `<trkpt>` track point. */
data class GpxTrackPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double? = null,
    val time: Instant? = null,
)

/** A `<trkseg>` — an ordered run of track points. */
data class GpxTrackSegment(
    val points: List<GpxTrackPoint>,
)

/** A `<trk>` — a named track made of one or more segments. */
data class GpxTrack(
    val name: String? = null,
    val segments: List<GpxTrackSegment>,
)

/** A whole GPX document: standalone waypoints plus tracks. */
data class GpxDocument(
    val waypoints: List<GpxWaypoint> = emptyList(),
    val tracks: List<GpxTrack> = emptyList(),
)

/**
 * GPX 1.1 reader/writer.
 *
 * [parse] throws [IllegalArgumentException] for malformed XML or a non-GPX root, or for
 * waypoints/track points missing the required `lat`/`lon` attributes.
 */
object Gpx {

    private const val GPX_NS = "http://www.topografix.com/GPX/1/1"
    private const val CREATOR = "aus-roads"

    // --- write ---------------------------------------------------------------

    /** Serialise [doc] to a GPX 1.1 XML string (UTF-8, indented). */
    fun write(doc: GpxDocument): String {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        val document = builder.newDocument()

        val gpx = document.createElementNS(GPX_NS, "gpx")
        gpx.setAttribute("version", "1.1")
        gpx.setAttribute("creator", CREATOR)
        document.appendChild(gpx)

        for (wpt in doc.waypoints) {
            gpx.appendChild(
                writePoint(
                    document, "wpt", wpt.latitude, wpt.longitude,
                    wpt.elevationMeters, wpt.time, wpt.name,
                ),
            )
        }

        for (track in doc.tracks) {
            val trk = document.createElementNS(GPX_NS, "trk")
            if (track.name != null) {
                trk.appendChild(textElement(document, "name", track.name))
            }
            for (segment in track.segments) {
                val trkseg = document.createElementNS(GPX_NS, "trkseg")
                for (pt in segment.points) {
                    trkseg.appendChild(
                        writePoint(document, "trkpt", pt.latitude, pt.longitude, pt.elevationMeters, pt.time, null),
                    )
                }
                trk.appendChild(trkseg)
            }
            gpx.appendChild(trk)
        }

        return serialize(document)
    }

    private fun writePoint(
        document: Document,
        tag: String,
        latitude: Double,
        longitude: Double,
        elevation: Double?,
        time: Instant?,
        name: String?,
    ): Element {
        val el = document.createElementNS(GPX_NS, tag)
        // GPX attributes are lat/lon (latitude/longitude). Use plain Double.toString
        // so a value round-trips exactly through Double parsing.
        el.setAttribute("lat", latitude.toString())
        el.setAttribute("lon", longitude.toString())
        if (elevation != null) {
            el.appendChild(textElement(document, "ele", elevation.toString()))
        }
        if (time != null) {
            el.appendChild(textElement(document, "time", time.toString()))
        }
        if (name != null) {
            el.appendChild(textElement(document, "name", name))
        }
        return el
    }

    private fun textElement(document: Document, tag: String, text: String): Element {
        val el = document.createElementNS(GPX_NS, tag)
        el.textContent = text
        return el
    }

    private fun serialize(document: Document): String {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        val writer = StringWriter()
        transformer.transform(DOMSource(document), StreamResult(writer))
        return writer.toString()
    }

    // --- parse ---------------------------------------------------------------

    /**
     * Parse a GPX 1.1 XML string into a [GpxDocument].
     *
     * @throws IllegalArgumentException if [xml] is not well-formed, the root is not
     *   `<gpx>`, or a waypoint/track point lacks the required `lat`/`lon` attributes.
     */
    fun parse(xml: String): GpxDocument {
        val document = parseDom(xml)
        val root = document.documentElement
            ?: throw IllegalArgumentException("GPX has no root element")
        if (root.localName != "gpx" && root.tagName != "gpx") {
            throw IllegalArgumentException("not a GPX document: root is <${root.tagName}>")
        }

        val waypoints = mutableListOf<GpxWaypoint>()
        val tracks = mutableListOf<GpxTrack>()

        for (child in childElements(root)) {
            when (localNameOf(child)) {
                "wpt" -> waypoints += parseWaypoint(child)
                "trk" -> tracks += parseTrack(child)
                else -> Unit // ignore unknown top-level elements (metadata, rte, …)
            }
        }
        return GpxDocument(waypoints = waypoints, tracks = tracks)
    }

    private fun parseDom(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        // Harden the parser: no DOCTYPE / external entities (defensive, also avoids XXE).
        runCatching {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val builder = factory.newDocumentBuilder()
        // Throw on parse errors rather than letting the default handler print to stderr.
        builder.setErrorHandler(ThrowingErrorHandler)
        return try {
            builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        } catch (e: SAXException) {
            throw IllegalArgumentException("malformed GPX XML: ${e.message}", e)
        } catch (e: java.io.IOException) {
            throw IllegalArgumentException("could not read GPX XML: ${e.message}", e)
        }
    }

    /** Re-throws every parser severity so malformed input surfaces as an exception. */
    private object ThrowingErrorHandler : ErrorHandler {
        override fun warning(exception: SAXParseException) = throw exception
        override fun error(exception: SAXParseException) = throw exception
        override fun fatalError(exception: SAXParseException) = throw exception
    }

    private fun parseWaypoint(el: Element): GpxWaypoint {
        val lat = requiredDoubleAttr(el, "lat")
        val lon = requiredDoubleAttr(el, "lon")
        return GpxWaypoint(
            latitude = lat,
            longitude = lon,
            elevationMeters = childDouble(el, "ele"),
            time = childInstant(el, "time"),
            name = childText(el, "name"),
        )
    }

    private fun parseTrack(el: Element): GpxTrack {
        val name = childText(el, "name")
        val segments = mutableListOf<GpxTrackSegment>()
        for (segEl in childElements(el)) {
            if (localNameOf(segEl) == "trkseg") {
                val points = mutableListOf<GpxTrackPoint>()
                for (ptEl in childElements(segEl)) {
                    if (localNameOf(ptEl) == "trkpt") {
                        points += parseTrackPoint(ptEl)
                    }
                }
                segments += GpxTrackSegment(points = points)
            }
        }
        return GpxTrack(name = name, segments = segments)
    }

    private fun parseTrackPoint(el: Element): GpxTrackPoint {
        val lat = requiredDoubleAttr(el, "lat")
        val lon = requiredDoubleAttr(el, "lon")
        return GpxTrackPoint(
            latitude = lat,
            longitude = lon,
            elevationMeters = childDouble(el, "ele"),
            time = childInstant(el, "time"),
        )
    }

    // --- DOM helpers ---------------------------------------------------------

    private fun requiredDoubleAttr(el: Element, attr: String): Double {
        if (!el.hasAttribute(attr)) {
            throw IllegalArgumentException("<${localNameOf(el)}> missing required '$attr' attribute")
        }
        val raw = el.getAttribute(attr)
        return raw.toDoubleOrNull()
            ?: throw IllegalArgumentException("<${localNameOf(el)}> '$attr' is not a number: '$raw'")
    }

    private fun childText(parent: Element, name: String): String? {
        val child = firstChildElement(parent, name) ?: return null
        val text = child.textContent?.trim()
        return if (text.isNullOrEmpty()) null else text
    }

    private fun childDouble(parent: Element, name: String): Double? {
        val text = childText(parent, name) ?: return null
        return text.toDoubleOrNull()
            ?: throw IllegalArgumentException("<$name> is not a number: '$text'")
    }

    private fun childInstant(parent: Element, name: String): Instant? {
        val text = childText(parent, name) ?: return null
        return try {
            Instant.parse(text)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("<$name> is not an ISO-8601 instant: '$text'", e)
        }
    }

    private fun firstChildElement(parent: Element, name: String): Element? =
        childElements(parent).firstOrNull { localNameOf(it) == name }

    private fun childElements(parent: Element): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                result += node as Element
            }
        }
        return result
    }

    private fun localNameOf(el: Element): String = el.localName ?: el.tagName
}
