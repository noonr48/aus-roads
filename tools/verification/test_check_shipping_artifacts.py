"""Predicate controls, NOT shipping evidence: ZIP/SDK fixtures are deliberately synthetic.

Run: python3 -m unittest discover -s tools/verification -p test_check_shipping_artifacts.py -v
Then run check_shipping_artifacts.py against the FINAL four REAL signed APKs.
These tests neither invoke SDK tools nor create/use a signing key.
"""
from copy import deepcopy
import hashlib
import io
import json
from pathlib import Path
import sqlite3
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import warnings
import xml.etree.ElementTree as ET
import zipfile

import check_shipping_artifacts as gate


DEBUG_CERT = "33c2e3258c2139f19ad2718a0ac1c0bcd635bfa58ada38d26632fe712cad1079"
RELEASE_CERT = "4d79aed41e9c890a5e9edb5989cb4194bc2829ef1ae6f849f3c277c1c2ebd66e"
LIBRARIES = ("libandroidx.graphics.path.so", "libdatastore_shared_counter.so",
             "libmaplibre.so", "libvalhalla-wrapper.so")
GLYPHS = ("0-255", "256-511", "512-767", "768-1023", "8192-8447")
OFFLINE_BANS = ("INTERNET", "ACCESS_WIFI_STATE", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
                "ACCESS_BACKGROUND_LOCATION", "ACCESS_MEDIA_LOCATION", "FOREGROUND_SERVICE_LOCATION",
                "LOCATION_HARDWARE", "LOCATION_BYPASS", "ACCESS_FUTURE_LOCATION")
OFFLINE_PERMISSIONS = ("ACCESS_NETWORK_STATE", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_DATA_SYNC",
                       "RECEIVE_BOOT_COMPLETED", "WAKE_LOCK")
NETWORK_PERMISSIONS = ("INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "ACCESS_FINE_LOCATION",
                       "ACCESS_COARSE_LOCATION", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_LOCATION",
                       "FOREGROUND_SERVICE_DATA_SYNC", "POST_NOTIFICATIONS", "RECEIVE_BOOT_COMPLETED", "WAKE_LOCK")


def encoded(value):
    return (json.dumps(value, sort_keys=True) + "\n").encode()


def database_bytes(sql, rows=()):
    db = sqlite3.connect(":memory:")
    try:
        db.executescript(sql)
        if rows:
            db.executemany("INSERT INTO search_meta VALUES (?,?,?,?,?,?)", rows)
        db.commit()
        return db.serialize()
    finally:
        db.close()


def fixture_assets():
    catalog = database_bytes("""
        CREATE TABLE search_meta (id INTEGER PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL,
                                  class TEXT, lat REAL NOT NULL, lon REAL NOT NULL);
        CREATE INDEX search_class_geo ON search_meta(class,lat,lon);
        CREATE INDEX search_kind_geo ON search_meta(kind,lat,lon);
        CREATE TABLE road_speed (lat REAL,lon REAL,maxspeed_kmh INTEGER);
        INSERT INTO road_speed VALUES (-34.9,138.6,60);
        CREATE TABLE road_cameras (lat REAL,lon REAL,maxspeed_kmh INTEGER,way_name TEXT);
        INSERT INTO road_cameras VALUES (-34.9,138.6,60,'Fixture Road');
        PRAGMA user_version=1;
        """, ((1, "Fixture Fuel", "poi", "amenity=fuel", -34.9, 138.6),
              (2, "Fixture Reserve", "park", "park", -34.8, 138.7),
              (3, "Fixture Road", "road", "residential", -34.9, 138.6)))
    metadata = {
        "schema": 1, "rows": 3, "bytes": len(catalog), "sha256": hashlib.sha256(catalog).hexdigest(),
        "sourceSha256": "b" * 64, "scope": "South Australia", "license": "ODbL-1.0",
        "attribution": "© OpenStreetMap contributors", "classes": {"amenity=fuel": 1, "park": 1},
        "osmSource": {"provider": "geofabrik", "osmExtractDate": "2026-09-11T23:06:46Z",
                      "url": "https://download.geofabrik.de/australia-oceania/australia/south-australia-latest.osm.pbf"},
    }
    tiles = database_bytes("""
        CREATE TABLE tiles (zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB);
        INSERT INTO tiles VALUES (0,0,0,X'010203');
        CREATE TABLE metadata (name TEXT,value TEXT);
        INSERT INTO metadata VALUES ('format','pbf');
        """)
    style = {"version": 8, "sources": {"aus-roads-tiles": {
        "type": "vector", "url": "asset://maptile/adelaide-test-tiles.mbtiles"}},
        "sprite": "", "glyphs": "asset://glyphs/{fontstack}/{range}.pbf",
        "layers": [{"id": "label", "type": "symbol", "source": "aus-roads-tiles",
                    "layout": {"text-field": "Fixture", "text-font": ["Open Sans Regular"]}}]}
    return {"catalog/sa-search.db": catalog, "catalog/sa-search.json": encoded(metadata),
            "maptile/adelaide-test-tiles.mbtiles": tiles, "maptile/style.json": encoded(style),
            **{f"glyphs/Open Sans Regular/{part}.pbf": b"synthetic glyph predicate fixture" for part in GLYPHS}}


def elf_header(machine=183, word_size=2):
    header = bytearray(64)
    header[:7] = b"\x7fELF" + bytes((word_size, 1, 1))
    struct.pack_into("<H", header, 18, machine)
    return bytes(header)


def zip_bytes(assets, removed=(), replacements=None, extra=()):
    # No valid binary manifest, DEX, native code or signature is fabricated here.
    entries = {"AndroidManifest.xml": b"SDK readback fixture only", "classes.dex": b"SDK readback fixture only",
               **{f"assets/{name}": data for name, data in assets.items()},
               **{f"lib/arm64-v8a/{name}": elf_header() for name in LIBRARIES}}
    for name in removed:
        entries.pop(name)
    entries.update(replacements or {})
    output = io.BytesIO()
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)  # Intentional duplicate-ZIP negative control.
        with zipfile.ZipFile(output, "w") as apk:
            for name, data in (*entries.items(), *extra):
                apk.writestr(name, data)
    return output.getvalue()


def fixture_manifest(flavor="offline", build="debug", extra_permissions=()):
    debug = build == "debug"
    root = ET.Element("manifest", {"package": "au.com.ausroads.debug" if debug else "au.com.ausroads",
                                   gate.ANDROID + "versionName": "1.2-debug" if debug else "1.2",
                                   gate.ANDROID + "versionCode": "4"})
    names = OFFLINE_PERMISSIONS if flavor == "offline" else NETWORK_PERMISSIONS
    for name in names:
        ET.SubElement(root, "uses-permission", {gate.ANDROID + "name": "android.permission." + name})
    ET.SubElement(root, "uses-permission", {gate.ANDROID + "name":
                  root.get("package") + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"})
    for name in extra_permissions:
        ET.SubElement(root, "uses-permission", {gate.ANDROID + "name": "android.permission." + name})
    application = ET.SubElement(root, "application")
    if flavor == "withNetwork":
        ET.SubElement(application, "service", {gate.ANDROID + "name": "au.com.ausroads.navigation.NavigationForegroundService",
                                              gate.ANDROID + "foregroundServiceType": "location", gate.ANDROID + "exported": "false"})
    download = ET.SubElement(application, "service", {
        gate.ANDROID + "name": "androidx.work.impl.foreground.SystemForegroundService",
        gate.ANDROID + "exported": "false",
        gate.ANDROID + "directBootAware": "false",
        gate.ANDROID + "enabled": "@ref/0x7f040001"})
    if flavor == "withNetwork":
        download.set(gate.ANDROID + "foregroundServiceType", "dataSync")
    return root


def manifest_readback(root):
    xml = ET.tostring(root, encoding="unicode")
    permissions = "\n".join(node.get(gate.ANDROID + "name") for node in root
                            if node.tag.startswith("uses-permission")) + "\n"
    return xml, permissions


def signer_readback(build):
    return "Verifies\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: " + (
        DEBUG_CERT if build == "debug" else RELEASE_CERT) + "\n"


DEX_READBACK = "C d 1 2 100 au.com.ausroads.AusRoadsApp\nC d 1 2 100 au.com.ausroads.MainActivity\n"
RESOURCE_READBACK = "    resource 0x7f040001 bool/enable_system_foreground_service_default\n      () true\n"


class ShippingPredicatesTest(unittest.TestCase):
    def setUp(self):
        self.assets = fixture_assets()

    def refuses(self, seam, function, *args, **kwargs):
        with self.assertRaises(gate.GateError) as caught:
            function(*args, **kwargs)
        self.assertEqual(str(caught.exception).split()[0], "component=shipping")
        self.assertIn("seam=" + seam + " ", str(caught.exception))
        return str(caught.exception)

    def check_zip(self, data, source=None):
        with zipfile.ZipFile(io.BytesIO(data)) as apk:
            gate.check_zip(apk, self.assets if source is None else source)

    def test_positive_catalog_tiles_and_zip_controls(self):
        metadata = gate.check_catalog_pair(self.assets["catalog/sa-search.json"], self.assets["catalog/sa-search.db"])
        self.assertEqual(metadata["rows"], 3)
        self.check_zip(zip_bytes(self.assets))
        # Dependency-owned profiles do not shadow the source asset namespaces.
        self.check_zip(zip_bytes(self.assets, extra=(("assets/dexopt/baseline.prof", b"fixture"),)))

    def test_each_required_asset_missing_refuses_including_packaged_json(self):
        self.assertEqual(len(self.assets), 9)
        for name in self.assets:
            with self.subTest(asset=name):
                self.refuses("packaged-assets", self.check_zip,
                             zip_bytes(self.assets, removed=("assets/" + name,)))

    def test_each_required_asset_stale_refuses_even_when_db_hash_still_matches(self):
        for name in self.assets:
            with self.subTest(asset=name):
                self.refuses("asset-identity", self.check_zip, zip_bytes(
                    self.assets, replacements={"assets/" + name: self.assets[name] + b"changed"}))
        self.refuses("asset-identity", self.check_zip, zip_bytes(
            self.assets, extra=(("assets/catalog/orphan.db", b"orphan"),)))

    def test_duplicate_assets_libraries_and_unrelated_zip_members_refuse(self):
        for name in ("assets/catalog/sa-search.json", "assets/catalog/sa-search.json/",
                     "assets/maptile/style.json", "lib/arm64-v8a/libmaplibre.so", "classes.dex"):
            with self.subTest(entry=name):
                self.assertIn("class=duplicate", self.refuses("zip-entries", self.check_zip,
                              zip_bytes(self.assets, extra=((name, b"duplicate"),))))

    def test_missing_abi_missing_each_jni_and_other_abi_refuse(self):
        self.refuses("abi", self.check_zip, zip_bytes(self.assets, removed=tuple(
            "lib/arm64-v8a/" + name for name in LIBRARIES)))
        for name in LIBRARIES:
            with self.subTest(library=name):
                self.refuses("jni-libraries", self.check_zip,
                             zip_bytes(self.assets, removed=("lib/arm64-v8a/" + name,)))
        for abi in ("x86", "x86_64", "armeabi-v7a"):
            with self.subTest(abi=abi):
                self.refuses("abi", self.check_zip, zip_bytes(self.assets,
                             extra=((f"lib/{abi}/libmaplibre.so", elf_header()),)))

    def test_empty_wrong_machine_or_word_size_library_is_not_arm64_proof(self):
        for invalid in (b"", b"not an ELF", elf_header(machine=62), elf_header(word_size=1)):
            with self.subTest(header=invalid[:6]):
                self.refuses("jni-architecture", self.check_zip, zip_bytes(
                    self.assets, replacements={"lib/arm64-v8a/libvalhalla-wrapper.so": invalid}))

    def test_path_escape_zip_and_missing_manifest_or_dex_refuse(self):
        for name in ("assets/../escape", "/absolute", "assets\\catalog\\file", "assets//file"):
            with self.subTest(path=name):
                self.refuses("zip-entries", self.check_zip, zip_bytes(self.assets, extra=((name, b"bad"),)))
        for name in ("AndroidManifest.xml", "classes.dex"):
            with self.subTest(entry=name):
                self.refuses("apk-shape", self.check_zip, zip_bytes(self.assets, removed=(name,)))

    def test_packaged_metadata_db_counts_classes_size_and_hash_are_independently_checked(self):
        original = json.loads(self.assets["catalog/sa-search.json"])
        for field, value, seam in (("rows", 4, "catalog-counts"), ("classes", {"amenity=fuel": 3}, "catalog-counts"),
                                   ("bytes", original["bytes"] + 1, "catalog-pair"), ("sha256", "0" * 64, "catalog-pair")):
            with self.subTest(field=field):
                bad = dict(original, **{field: value})
                self.refuses(seam, gate.check_catalog_pair, encoded(bad), self.assets["catalog/sa-search.db"])
                # Matching corrupt local and packaged JSON still cannot weaken the predicate.
                corrupt = dict(self.assets, **{"catalog/sa-search.json": encoded(bad)})
                self.refuses(seam, self.check_zip, zip_bytes(corrupt), corrupt)

    def test_malformed_metadata_types_duplicate_keys_nonfinite_values_and_provenance_refuse(self):
        original = json.loads(self.assets["catalog/sa-search.json"])
        for field, value in (("schema", True), ("schema", 2), ("rows", True), ("rows", "3"),
                             ("bytes", -1), ("sha256", "not-a-digest"), ("sourceSha256", None),
                             ("classes", {"park": True}), ("classes", [])):
            with self.subTest(field=field, value=value):
                self.refuses("catalog-metadata", gate.check_catalog_pair,
                             encoded(dict(original, **{field: value})), self.assets["catalog/sa-search.db"])
        for value in (b"[]", b"{}", b"{broken", b"\xff", b'{"schema":1,"schema":1}', b'{"schema":NaN}'):
            with self.subTest(json=value):
                self.refuses("catalog-metadata", gate.check_catalog_pair, value, self.assets["catalog/sa-search.db"])
        for field, value in (("license", "unknown"), ("scope", "other"), ("attribution", "")):
            self.refuses("catalog-provenance", gate.check_catalog_pair,
                         encoded(dict(original, **{field: value})), self.assets["catalog/sa-search.db"])
        for field, value in (("provider", "other"), ("url", "http://other"), ("osmExtractDate", "2026-02-30T00:00:00Z")):
            metadata = deepcopy(original)
            metadata["osmSource"][field] = value
            self.refuses("catalog-provenance", gate.check_catalog_pair, encoded(metadata), self.assets["catalog/sa-search.db"])

    def test_tiles_empty_payload_and_malformed_metadata_are_not_presence_proof(self):
        self.refuses("tiles-database", gate.check_tiles, b"not SQLite")
        for sql, seam in (("DELETE FROM tiles", "tiles-content"),
                          ("UPDATE tiles SET tile_data=X''", "tiles-content"),
                          ("UPDATE metadata SET value=NULL", "tiles-metadata"),
                          ("INSERT INTO metadata VALUES ('format','pbf')", "tiles-metadata")):
            with self.subTest(sql=sql):
                db = sqlite3.connect(":memory:")
                try:
                    db.deserialize(self.assets["maptile/adelaide-test-tiles.mbtiles"])
                    db.execute(sql)
                    db.commit()
                    self.refuses(seam, gate.check_tiles, db.serialize())
                finally:
                    db.close()

    def test_self_consistent_database_corruption_schema_and_invalid_last_rows_refuse(self):
        def check_database(data):
            metadata = json.loads(self.assets["catalog/sa-search.json"])
            metadata.update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
            gate.check_catalog_pair(encoded(metadata), data)
        self.refuses("catalog-database", check_database, b"not SQLite")
        for sql, seam in (("DROP INDEX search_class_geo", "catalog-schema"),
                          ("PRAGMA user_version=2", "catalog-schema"),
                          ("""ALTER TABLE search_meta RENAME TO old;
                              CREATE TABLE search_meta (id INTEGER,name TEXT,kind TEXT,class TEXT,lat REAL,lon REAL);
                              INSERT INTO search_meta SELECT * FROM old;
                              DROP TABLE old;
                              CREATE INDEX search_class_geo ON search_meta(class,lat,lon);
                              CREATE INDEX search_kind_geo ON search_meta(kind,lat,lon);""", "catalog-schema"),
                          ("UPDATE search_meta SET lat=999 WHERE id=3", "catalog-rows"),
                          ("UPDATE search_meta SET class='amenity=fuel' WHERE id=3", "catalog-rows"),
                          ("UPDATE search_meta SET name=' ' WHERE id=3", "catalog-rows"),
                          ("UPDATE road_cameras SET lon='bad'", "catalog-road-rows")):
            with self.subTest(sql=sql):
                db = sqlite3.connect(":memory:")
                try:
                    db.deserialize(self.assets["catalog/sa-search.db"])
                    db.executescript(sql)
                    db.commit()
                    self.refuses(seam, check_database, db.serialize())
                finally:
                    db.close()

    def test_style_references_empty_sprite_and_declared_sprite_dependency(self):
        gate.check_style(self.assets["maptile/style.json"], self.assets)  # Actual source intentionally declares no sprite.
        original = json.loads(self.assets["maptile/style.json"])
        for field, value, seam in (("version", True, "style-metadata"), ("glyphs", "https://remote", "style-glyphs"),
                                   ("sprite", "asset://sprites/fixture", "style-sprites")):
            self.refuses(seam, gate.check_style, encoded(dict(original, **{field: value})), self.assets)
        bad = deepcopy(original)
        bad["sources"]["aus-roads-tiles"]["url"] = "asset://missing.mbtiles"
        self.refuses("style-source", gate.check_style, encoded(bad), self.assets)
        bad = deepcopy(original)
        bad["layers"][0]["layout"]["text-font"] = ["Missing Font"]
        self.refuses("style-glyphs", gate.check_style, encoded(bad), self.assets)
        for source in ([], {}, None, 1):
            bad = deepcopy(original)
            bad["layers"][0]["source"] = source
            self.refuses("style-layers", gate.check_style, encoded(bad), self.assets)
        sprite_style = dict(original, sprite="asset://sprites/fixture")
        sprite_assets = dict(self.assets)
        for density in ("", "@2x"):
            sprite_assets[f"sprites/fixture{density}.json"] = b"{}"
            sprite_assets[f"sprites/fixture{density}.png"] = b"\x89PNG\r\n\x1a\nfixture signature only"
        gate.check_style(encoded(sprite_style), sprite_assets)
        del sprite_assets["sprites/fixture@2x.png"]
        self.refuses("style-sprites", gate.check_style, encoded(sprite_style), sprite_assets)

    def test_all_four_identity_and_certificate_positive_controls_pin_owner_constants(self):
        self.assertEqual(gate.CERTIFICATES, {"debug": DEBUG_CERT, "release": RELEASE_CERT})
        for flavor in ("offline", "withNetwork"):
            for build in ("debug", "release"):
                with self.subTest(flavor=flavor, build=build):
                    gate.check_manifest(*manifest_readback(fixture_manifest(flavor, build)), flavor, build)
                    gate.check_certificate(signer_readback(build), build)
        root = fixture_manifest("withNetwork")
        list(root.find("application"))[0].set(gate.ANDROID + "foregroundServiceType", "0x00000008")
        list(root.find("application"))[1].set(gate.ANDROID + "foregroundServiceType", "1")
        gate.check_manifest(*manifest_readback(root), "withNetwork", "debug")
        gate.check_certificate(signer_readback("debug").replace(DEBUG_CERT, DEBUG_CERT.upper()), "debug")

    def test_package_version_and_code_mismatch_missing_or_unsigned_cert_refuse(self):
        for build in ("debug", "release"):
            for attribute, wrong in (("package", "au.com.other"), (gate.ANDROID + "versionName", "1.1"),
                                     (gate.ANDROID + "versionCode", "5")):
                root = fixture_manifest(build=build)
                root.set(attribute, wrong)
                self.refuses("identity", gate.check_manifest, *manifest_readback(root), "offline", build)
            for text in ("", "DOES NOT VERIFY", signer_readback(build).replace(
                DEBUG_CERT if build == "debug" else RELEASE_CERT, "0" * 64),
                signer_readback(build) + "Signer #2 certificate SHA-256 digest: " + DEBUG_CERT + "\n"):
                self.refuses("signing-continuity", gate.check_certificate, text, build)

    def test_complete_offline_bans_include_sdk23_and_expired_declarations(self):
        for permission in OFFLINE_BANS:
            for tag in ("uses-permission", "uses-permission-sdk-23"):
                with self.subTest(permission=permission, tag=tag):
                    root = fixture_manifest(extra_permissions=(permission,))
                    node = list(root)[-2]
                    node.tag = tag
                    node.set(gate.ANDROID + "maxSdkVersion", "22")
                    self.refuses("offline-privacy", gate.check_manifest, *manifest_readback(root), "offline", "debug")

    def test_offline_location_service_named_numeric_or_union_and_fused_refuse(self):
        for service_name, service_type in (("some.Service", "location"), ("some.Service", "dataSync|location"),
                                           ("some.Service", "0x00000008"),
                                           ("au.com.ausroads.navigation.NavigationForegroundService", ""),
                                           ("com.google.android.gms.location.FusedProvider", "")):
            root = fixture_manifest()
            ET.SubElement(root.find("application"), "service", {gate.ANDROID + "name": service_name,
                                                               gate.ANDROID + "foregroundServiceType": service_type})
            self.refuses("offline-privacy", gate.check_manifest, *manifest_readback(root), "offline", "debug")
        gate.check_dex_readback(DEX_READBACK, "offline")
        gate.check_dex_readback(DEX_READBACK + "com.google.android.gms.location.FusedLocationProviderClient", "withNetwork")
        for descriptor in ("com.google.android.gms.location", "com/google/android/gms/location/FusedLocationProviderClient;",
                           "au.com.ausroads.di.FusedNavigationLocationSource"):
            self.refuses("offline-fused-provider", gate.check_dex_readback, DEX_READBACK + descriptor, "offline")
        self.refuses("dex-readback", gate.check_dex_readback, "", "offline")

    def test_each_current_network_permission_is_required_and_background_never_allowed(self):
        for name in NETWORK_PERMISSIONS:
            with self.subTest(permission=name):
                root = fixture_manifest("withNetwork")
                for node in list(root):
                    if node.get(gate.ANDROID + "name") == "android.permission." + name:
                        root.remove(node)
                self.refuses("network-permissions", gate.check_manifest, *manifest_readback(root), "withNetwork", "debug")
        for banned in ("ACCESS_BACKGROUND_LOCATION", "ACCESS_MEDIA_LOCATION", "LOCATION_HARDWARE",
                       "LOCATION_BYPASS", "ACCESS_FUTURE_LOCATION"):
            for tag in ("uses-permission", "uses-permission-sdk-23"):
                with self.subTest(permission=banned, tag=tag):
                    root = fixture_manifest("withNetwork", extra_permissions=(banned,))
                    node = list(root)[-2]
                    node.tag = tag
                    node.set(gate.ANDROID + "maxSdkVersion", "22")
                    self.refuses("network-permissions", gate.check_manifest,
                                 *manifest_readback(root), "withNetwork", "debug")
        root = fixture_manifest("withNetwork")
        list(root)[0].set(gate.ANDROID + "maxSdkVersion", "34")
        self.refuses("network-permissions", gate.check_manifest, *manifest_readback(root), "withNetwork", "debug")

    def test_network_foreground_services_and_sdk_permission_parity_are_not_optional(self):
        for index, seam in ((0, "navigation-service"), (1, "download-service")):
            root = fixture_manifest("withNetwork")
            application = root.find("application")
            application.remove(list(application)[index])
            self.refuses(seam, gate.check_manifest, *manifest_readback(root), "withNetwork", "debug")
        root = fixture_manifest("withNetwork")
        list(root.find("application"))[0].set(gate.ANDROID + "exported", "true")
        self.refuses("navigation-service", gate.check_manifest, *manifest_readback(root), "withNetwork", "debug")
        xml, permissions = manifest_readback(fixture_manifest())
        self.refuses("permission-readback", gate.check_manifest, xml, "", "offline", "debug")
        self.refuses("permission-readback", gate.check_manifest, xml, permissions + "unexpected SDK header\n", "offline", "debug")
        self.refuses("permission-readback", gate.check_manifest, xml, permissions * 2, "offline", "debug")
        self.refuses("manifest-readback", gate.check_manifest, "<broken", permissions, "offline", "debug")

    def test_download_service_and_permission_expansion_refuse_for_both_builds(self):
        for flavor in ("offline", "withNetwork"):
            for build in ("debug", "release"):
                for attribute, value in (("exported", "true"), ("foregroundServiceType", "dataSync|mediaPlayback"),
                                         ("foregroundServiceType", "0x00000003"), ("foregroundServiceType", "mediaPlayback")):
                    with self.subTest(flavor=flavor, build=build, attribute=attribute, value=value):
                        root = fixture_manifest(flavor, build)
                        list(root.find("application"))[-1].set(gate.ANDROID + attribute, value)
                        self.refuses("download-service", gate.check_manifest, *manifest_readback(root), flavor, build)
                root = fixture_manifest(flavor, build)
                application = root.find("application")
                application.append(deepcopy(list(application)[-1]))
                self.refuses("download-service", gate.check_manifest, *manifest_readback(root), flavor, build)
                root = fixture_manifest(flavor, build, extra_permissions=("RECORD_AUDIO",))
                seam = "offline-permissions" if flavor == "offline" else "network-permissions"
                self.refuses(seam, gate.check_manifest, *manifest_readback(root), flavor, build)
        root = fixture_manifest()
        list(root.find("application"))[-1].set(gate.ANDROID + "foregroundServiceType", "dataSync")
        self.refuses("download-service", gate.check_manifest, *manifest_readback(root), "offline", "debug")

    def test_downloader_original_boot_and_enabled_defaults_cannot_be_dropped_or_changed(self):
        for flavor in ("offline", "withNetwork"):
            for attribute, value in (("directBootAware", "true"), ("directBootAware", None),
                                     ("enabled", "false"), ("enabled", None)):
                with self.subTest(flavor=flavor, attribute=attribute, value=value):
                    root = fixture_manifest(flavor)
                    service = list(root.find("application"))[-1]
                    if value is None:
                        service.attrib.pop(gate.ANDROID + attribute)
                    else:
                        service.set(gate.ANDROID + attribute, value)
                    self.refuses("download-service", gate.check_manifest, *manifest_readback(root), flavor, "debug")

    def test_compiled_enabled_reference_names_the_original_true_workmanager_resource(self):
        xml, _ = manifest_readback(fixture_manifest())
        gate.check_download_resource(xml, RESOURCE_READBACK)
        for value in ("", RESOURCE_READBACK * 2, RESOURCE_READBACK.replace("true", "false"),
                      RESOURCE_READBACK.replace("()", "(v23)"), RESOURCE_READBACK.replace("0x7f040001", "0x7f040002"),
                      RESOURCE_READBACK.replace("enable_system_foreground_service_default", "another_bool")):
            with self.subTest(resources=value):
                self.refuses("download-defaults", gate.check_download_resource, xml, value)

    def test_mocked_sdk_seam_is_invoked_not_replaced_by_zip_identity(self):
        tools = gate.Tools(Path("apksigner"), Path("apkanalyzer"), {"JAVA_HOME": "fixture"})
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "fixture.apk"
            path.write_bytes(zip_bytes(self.assets))
            for flavor, build in (("offline", "debug"), ("offline", "release"),
                                  ("withNetwork", "debug"), ("withNetwork", "release")):
                xml, permissions = manifest_readback(fixture_manifest(flavor, build))
                with patch.object(gate, "sdk_readback", side_effect=(signer_readback(build), xml, permissions, RESOURCE_READBACK, DEX_READBACK)) as readback:
                    result = gate.check_one(path, flavor, build, self.assets, tools)
                    self.assertEqual(result["certificate"], DEBUG_CERT if build == "debug" else RELEASE_CERT)
                    self.assertEqual([call.args[1][:-1] for call in readback.call_args_list],
                                     [["verify", "--verbose", "--print-certs"], ["manifest", "print"],
                                      ["manifest", "permissions"], ["dump", "resources"], ["dex", "packages"]])
            xml, permissions = manifest_readback(fixture_manifest())
            with patch.object(gate, "sdk_readback", side_effect=(signer_readback("debug"), xml, permissions, RESOURCE_READBACK,
                                                                 DEX_READBACK + "FusedLocationProviderClient")):
                self.refuses("offline-fused-provider", gate.check_one, path, "offline", "debug", self.assets, tools)
            with patch.object(gate, "sha256_file", side_effect=("before", "after")), patch.object(
                    gate, "sdk_readback", side_effect=(signer_readback("debug"), xml, permissions, RESOURCE_READBACK, DEX_READBACK)):
                self.refuses("artifact-freshness", gate.check_one, path, "offline", "debug", self.assets, tools)

    def test_failed_sdk_command_is_fixed_refusal_not_private_output_or_empty_success(self):
        failure = subprocess.CalledProcessError(1, ["fixture"], output="private payload", stderr="private detail")
        with patch.object(gate.subprocess, "run", side_effect=failure) as run:
            message = self.refuses("sdk-readback", gate.sdk_readback, Path("fixture"), ["manifest", "print", "fixture.apk"], {})
            self.assertNotIn("private", message)
            self.assertEqual(run.call_args.kwargs["timeout"], 120)
            self.assertTrue(run.call_args.kwargs["check"])
            self.assertNotIn("shell", run.call_args.kwargs)

    def test_source_inventory_four_canonical_paths_and_final_freshness_are_required(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = root / "android/app/src/main/assets"
            for name, data in self.assets.items():
                destination = directory / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
            self.assertEqual(gate.load_source_assets(root), self.assets)
            glyph = directory / "glyphs/Open Sans Regular/8192-8447.pbf"
            glyph.unlink()
            self.refuses("asset-source", gate.load_source_assets, root)
            glyph.write_bytes(self.assets["glyphs/Open Sans Regular/8192-8447.pbf"])
            variants = (("offline", "debug"), ("offline", "release"),
                        ("withNetwork", "debug"), ("withNetwork", "release"))
            paths = [root / "apks" / flavor / build / f"app-{flavor}-{build}.apk" for flavor, build in variants]
            for path in paths[:3]:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(zip_bytes(self.assets))
            with patch.object(gate, "check_one") as check_one:
                self.refuses("artifact-input", gate.check_all, root, root / "apks", None)
                check_one.assert_not_called()  # Three artifacts must never yield four-artifact PASS.
            paths[3].parent.mkdir(parents=True, exist_ok=True)
            paths[3].write_bytes(zip_bytes(self.assets))
            # This controls aggregation/freshness only, not signatures or real APK quality.
            def observed_hash(path, flavor, build, assets, tools):
                return {"sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
            with patch.object(gate, "check_one", side_effect=observed_hash) as check_one:
                self.assertEqual(len(gate.check_all(root, root / "apks", None)), 4)
                self.assertEqual([call.args[1:3] for call in check_one.call_args_list], list(variants))
            def artifact_changes_late(path, flavor, build, assets, tools):
                result = observed_hash(path, flavor, build, assets, tools)
                if (flavor, build) == ("withNetwork", "release"):
                    paths[0].write_bytes(b"changed after first artifact checked")
                return result
            with patch.object(gate, "check_one", side_effect=artifact_changes_late):
                self.refuses("artifact-freshness", gate.check_all, root, root / "apks", None)
            def source_changes_late(path, flavor, build, assets, tools):
                if (flavor, build) == ("withNetwork", "release"):
                    glyph.write_bytes(b"changed after initial source snapshot")
                return observed_hash(path, flavor, build, assets, tools)
            with patch.object(gate, "check_one", side_effect=source_changes_late):
                self.refuses("source-freshness", gate.check_all, root, root / "apks", None)

    def test_tool_paths_must_be_present_and_executable_without_running_them(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(gate.subprocess, "run") as run:
            root = Path(temporary)
            signer = root / "sdk/build-tools/35.0.0/apksigner"
            analyzer = root / "sdk/cmdline-tools/latest/bin/apkanalyzer"
            java = root / "jdk/bin/java"
            for tool in (signer, signer.with_name("aapt2"), analyzer, java):
                tool.parent.mkdir(parents=True, exist_ok=True)
                tool.write_text("fixture path only; never execute\n")
                tool.chmod(0o700)
            tools = gate.resolve_tools(root / "sdk", root / "jdk")
            self.assertEqual(tools.apksigner, signer)
            self.assertEqual(tools.apkanalyzer, analyzer)
            self.assertEqual(tools.env["JAVA_HOME"], str((root / "jdk").resolve()))
            analyzer.chmod(0o600)
            self.refuses("dependencies", gate.resolve_tools, root / "sdk", root / "jdk")
            java.unlink()
            self.refuses("dependencies", gate.resolve_tools, root / "sdk", root / "jdk")
            run.assert_not_called()

    def test_public_cli_malformed_arguments_refuse_without_echo_or_sdk_execution(self):
        for arguments in (("--unknown-option", "PAYLOAD_SENTINEL"), ("--source-root",),
                          ("--sdk-root",), ("--build-tools",), ("--java-home", "PRIVATE_SENTINEL", "--unknown")):
            with self.subTest(arguments=arguments), patch.object(gate.subprocess, "run") as run:
                out, err = io.StringIO(), io.StringIO()
                with patch("sys.stdout", out), patch("sys.stderr", err):
                    status = gate.main(arguments)
                self.assertEqual(status, 1)
                self.assertEqual(out.getvalue(), "")
                self.assertEqual(err.getvalue(), "component=shipping seam=arguments class=invalid-input\n")
                run.assert_not_called()

    def test_real_cli_malformed_refusal_and_help_remain_distinct(self):
        for arguments in (("--unknown-option", "PAYLOAD_SENTINEL"), ("--source-root",)):
            with self.subTest(arguments=arguments):
                result = subprocess.run([sys.executable, "-B", gate.__file__, *arguments],
                                        capture_output=True, text=True, timeout=10, check=False)
                self.assertEqual(result.returncode, 1)
                self.assertEqual(result.stdout, "")
                self.assertEqual(result.stderr, "component=shipping seam=arguments class=invalid-input\n")
        help_result = subprocess.run([sys.executable, "-B", gate.__file__, "--help"],
                                     capture_output=True, text=True, timeout=10, check=False)
        self.assertEqual(help_result.returncode, 0)
        self.assertIn("usage:", help_result.stdout)
        self.assertEqual(help_result.stderr, "")

    def test_public_cli_refuses_missing_tool_dependencies_without_success_marker(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(gate.subprocess, "run") as run:
            out, err = io.StringIO(), io.StringIO()
            with patch("sys.stdout", out), patch("sys.stderr", err):
                status = gate.main(["--source-root", temporary, "--sdk-root", temporary, "--java-home", temporary])
            self.assertEqual(status, 1)
            self.assertEqual(out.getvalue(), "")
            self.assertEqual(err.getvalue(), "component=shipping seam=dependencies class=missing-dependency\n")
            run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
