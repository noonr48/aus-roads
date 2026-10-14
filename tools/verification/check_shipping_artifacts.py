#!/usr/bin/env python3
"""SC8 shipping gate: all four real, same-signer, arm64 APKs; Python 3.11+ stdlib.

Run after the final shipping build, not an x86 emulator build. SDK readback is
mandatory: ZIP/test fixtures alone cannot establish signing or manifest quality.
No downloads, signing, key creation, installation or source mutation occur here.
"""
import argparse
from collections import Counter
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import sqlite3
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
import zlib


SOURCE_ROOT = Path(__file__).resolve().parents[2]
VARIANTS = (("offline", "debug"), ("offline", "release"),
            ("withNetwork", "debug"), ("withNetwork", "release"))
CERTIFICATES = {
    "debug": "33c2e3258c2139f19ad2718a0ac1c0bcd635bfa58ada38d26632fe712cad1079",
    "release": "4d79aed41e9c890a5e9edb5989cb4194bc2829ef1ae6f849f3c277c1c2ebd66e",
}
# Observed in both actual merged-native outputs, not inferred from Maven names.
JNI_LIBRARIES = frozenset({"libandroidx.graphics.path.so", "libdatastore_shared_counter.so",
                           "libmaplibre.so", "libvalhalla-wrapper.so"})
CATALOG_DB = "catalog/sa-search.db"
CATALOG_JSON = "catalog/sa-search.json"
TILES = "maptile/adelaide-test-tiles.mbtiles"
STYLE = "maptile/style.json"
GLYPH_RANGES = ("0-255", "256-511", "512-767", "768-1023", "8192-8447")
REQUIRED_ASSETS = frozenset({CATALOG_DB, CATALOG_JSON, TILES, STYLE} |
                          {f"glyphs/Open Sans Regular/{part}.pbf" for part in GLYPH_RANGES})
ANDROID = "{http://schemas.android.com/apk/res/android}"
PERMISSION = "android.permission."
OFFLINE_FORBIDDEN = frozenset(PERMISSION + name for name in (
    "INTERNET", "ACCESS_WIFI_STATE", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
    "ACCESS_BACKGROUND_LOCATION", "ACCESS_MEDIA_LOCATION", "FOREGROUND_SERVICE_LOCATION"))
NETWORK_REQUIRED = frozenset(PERMISSION + name for name in (
    "INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "ACCESS_FINE_LOCATION",
    "ACCESS_COARSE_LOCATION", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_LOCATION",
    "FOREGROUND_SERVICE_DATA_SYNC", "POST_NOTIFICATIONS"))
# LOCATION is a declaration-name constraint, including platform-only or expired
# declarations; grantability is not an exemption. Existing SDK/service checks
# remain independent preservation constraints. Fixtures live in the sibling test.
NETWORK_LOCATION_ALLOWED = frozenset(PERMISSION + name for name in (
    "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "FOREGROUND_SERVICE_LOCATION"))
# Exact declarations observed in all four merged app outputs. The standalone
# downloader's service type must not expand the offline app's existing posture.
COMMON_PERMISSIONS = frozenset(PERMISSION + name for name in (
    "ACCESS_NETWORK_STATE", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_DATA_SYNC",
    "RECEIVE_BOOT_COMPLETED", "WAKE_LOCK"))
DOWNLOAD_SERVICE = "androidx.work.impl.foreground.SystemForegroundService"
NAVIGATION_SERVICE = "au.com.ausroads.navigation.NavigationForegroundService"
FUSED = re.compile(r"com[./]google[./]android[./]gms[./]location\b|FusedLocationProvider|FusedNavigationLocationSource")
SHA256 = re.compile(r"[0-9a-f]{64}")
PLACE_CLASSES = {"suburb", "city", "town", "village", "locality", "neighbourhood", "hamlet", "quarter"}


class GateError(ValueError):
    """Only fixed component/seam/class metadata reaches the CLI refusal sink."""
    def __init__(self, seam, fault="contract-drift"):
        super().__init__(f"component=shipping seam={seam} class={fault}")


def require(condition, seam, fault="contract-drift"):
    if not condition:
        raise GateError(seam, fault)


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def reject_constant(_):
    raise ValueError("non-finite JSON value")


def read_json(data, seam):
    try:
        return json.loads(data.decode("utf-8"), object_pairs_hook=unique_object,
                          parse_constant=reject_constant)
    except (UnicodeError, ValueError, RecursionError):
        raise GateError(seam) from None


@contextmanager
def sqlite_asset(data, seam):
    # In-memory readback avoids extracting executable APK paths or writing a DB.
    require(hasattr(sqlite3.Connection, "deserialize"), "dependencies", "missing-dependency")
    require(data.startswith(b"SQLite format 3\x00"), seam)
    database = sqlite3.connect(":memory:")
    try:
        database.deserialize(data)
        database.execute("PRAGMA query_only=ON")
        require(database.execute("PRAGMA quick_check").fetchall() == [("ok",)], seam)
        yield database
    except sqlite3.Error:
        raise GateError(seam) from None
    finally:
        database.close()


def valid_class(kind, value):
    if value is None:  # Same legacy null/free-form OSM contract as producer/app.
        return True
    if not isinstance(value, str) or not value.strip():
        return False
    if kind == "poi":
        key, separator, tag = value.partition("=")
        return bool(separator and ((key in {"amenity", "tourism", "shop", "leisure"} and tag.strip()) or
                                   (key == "place" and tag in PLACE_CLASSES)))
    if kind == "park":
        return value in {"park", "leisure=park"}
    if kind == "water":
        return value in {"water", "natural=water"}
    if kind == "suburb":
        return value.removeprefix("place=") in PLACE_CLASSES
    return kind == "road" and "=" not in value


def coordinates(lat, lon):
    return all(type(value) in (int, float) and math.isfinite(value) and -limit <= value <= limit
               for value, limit in ((lat, 90), (lon, 180)))


def check_catalog_pair(metadata_bytes, data):
    metadata = read_json(metadata_bytes, "catalog-metadata")
    require(isinstance(metadata, dict) and set(metadata) == {
        "schema", "rows", "bytes", "sha256", "sourceSha256", "scope", "license",
        "attribution", "osmSource", "classes"}, "catalog-metadata")
    require(type(metadata["schema"]) is int and metadata["schema"] == 1 and
            all(type(metadata[key]) is int and metadata[key] > 0 for key in ("rows", "bytes")) and
            all(isinstance(metadata[key], str) and SHA256.fullmatch(metadata[key])
                for key in ("sha256", "sourceSha256")), "catalog-metadata")
    require(metadata["scope"] == "South Australia" and metadata["license"] == "ODbL-1.0" and
            metadata["attribution"] == "© OpenStreetMap contributors", "catalog-provenance")
    osm = metadata["osmSource"]
    require(isinstance(osm, dict) and set(osm) == {"provider", "url", "osmExtractDate"} and
            osm["provider"] == "geofabrik" and osm["url"] ==
            "https://download.geofabrik.de/australia-oceania/australia/south-australia-latest.osm.pbf" and
            isinstance(osm["osmExtractDate"], str), "catalog-provenance")
    try:
        datetime.strptime(osm["osmExtractDate"], "%Y-%m-%dT%H:%M:%SZ")
    except ValueError:
        raise GateError("catalog-provenance") from None
    classes = metadata["classes"]
    require(isinstance(classes, dict) and all(type(count) is int and count > 0
                                             for count in classes.values()), "catalog-metadata")
    require(metadata["bytes"] == len(data) and metadata["sha256"] == hashlib.sha256(data).hexdigest(),
            "catalog-pair")
    with sqlite_asset(data, "catalog-database") as db:
        require(db.execute("PRAGMA user_version").fetchone() == (1,), "catalog-schema")
        require(db.execute("SELECT type FROM sqlite_master WHERE name='search_meta'").fetchone() == ("table",),
                "catalog-schema")
        columns = [(row[1], row[2].upper(), row[3], row[5]) for row in db.execute("PRAGMA table_info(search_meta)")]
        require(columns == [("id", "INTEGER", 0, 1), ("name", "TEXT", 1, 0), ("kind", "TEXT", 1, 0),
                            ("class", "TEXT", 0, 0), ("lat", "REAL", 1, 0), ("lon", "REAL", 1, 0)], "catalog-schema")
        require(not db.execute("SELECT 1 FROM sqlite_master WHERE sql LIKE '%VIRTUAL TABLE%'").fetchone(),
                "catalog-schema")
        for index, expected in (("search_class_geo", ["class", "lat", "lon"]),
                                ("search_kind_geo", ["kind", "lat", "lon"])):
            require([row[2] for row in db.execute(f"PRAGMA index_info({index})")] == expected,
                    "catalog-schema")
        count, histogram = 0, Counter()
        for identity, name, kind, klass, lat, lon in db.execute("SELECT id,name,kind,class,lat,lon FROM search_meta"):
            require(type(identity) is int and isinstance(name, str) and name.strip() and
                    kind in {"poi", "park", "road", "water", "suburb"} and valid_class(kind, klass) and
                    coordinates(lat, lon), "catalog-rows")
            count += 1
            if kind in {"poi", "park"}:
                histogram[klass if klass is not None else ""] += 1
        require(count == metadata["rows"] and dict(histogram) == classes, "catalog-counts")
        tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        for table, columns in (("road_speed", "lat,lon,maxspeed_kmh"),
                               ("road_cameras", "lat,lon,maxspeed_kmh,way_name")):
            if table in tables:
                for row in db.execute(f"SELECT {columns} FROM {table}"):
                    require(coordinates(*row[:2]) and (row[2] is None or
                            (type(row[2]) is int and row[2] > 0)) and
                            (len(row) == 3 or row[3] is None or isinstance(row[3], str)), "catalog-road-rows")
    return metadata


def check_tiles(data):
    with sqlite_asset(data, "tiles-database") as db:
        require(db.execute("SELECT count(*) FROM tiles").fetchone()[0] > 0 and
                not db.execute("SELECT 1 FROM tiles WHERE typeof(tile_data)!='blob' OR length(tile_data)=0 LIMIT 1").fetchone(),
                "tiles-content")
        metadata = db.execute("SELECT name,value FROM metadata").fetchall()
        require(metadata and all(isinstance(name, str) and name.strip() and
                                 isinstance(value, str) and value.strip() for name, value in metadata) and
                len({name for name, _ in metadata}) == len(metadata), "tiles-metadata")


def asset_path(uri):
    require(isinstance(uri, str) and uri.startswith("asset://"), "style-assets")
    path = uri.removeprefix("asset://")
    require(path and not path.startswith("/") and "\\" not in path and
            all(part not in {"", ".", ".."} for part in path.split("/")), "style-assets")
    return path


def check_style(data, assets):
    style = read_json(data, "style-metadata")
    require(isinstance(style, dict) and type(style.get("version")) is int and style["version"] == 8 and
            isinstance(style.get("sources"), dict) and style["sources"] and
            isinstance(style.get("layers"), list) and style["layers"], "style-metadata")
    require(style["sources"].get("aus-roads-tiles") == {"type": "vector", "url": "asset://" + TILES},
            "style-source")
    for source in style["sources"].values():
        require(isinstance(source, dict) and asset_path(source.get("url")) in assets, "style-assets")
    require(style.get("glyphs") == "asset://glyphs/{fontstack}/{range}.pbf", "style-glyphs")
    ids = set()
    for layer in style["layers"]:
        require(isinstance(layer, dict) and isinstance(layer.get("id"), str) and layer["id"] and
                layer["id"] not in ids, "style-layers")
        ids.add(layer["id"])
        require("source" not in layer or (isinstance(layer["source"], str) and
                layer["source"] in style["sources"]), "style-layers")
        layout = layer.get("layout", {})
        require(isinstance(layout, dict), "style-layers")
        if "text-field" in layout:
            fonts = layout.get("text-font")
            require(isinstance(fonts, list) and fonts and all(isinstance(font, str) and font for font in fonts),
                    "style-glyphs")
            require(f"glyphs/{','.join(fonts)}/0-255.pbf" in assets, "style-glyphs")
    sprite = style.get("sprite")
    require(isinstance(sprite, str), "style-sprites")
    # Current style deliberately has sprite=""; do not invent a sprite dependency.
    if sprite:
        prefix = asset_path(sprite)
        for density in ("", "@2x"):
            require(prefix + density + ".json" in assets and prefix + density + ".png" in assets,
                    "style-sprites", "missing")
            require(isinstance(read_json(assets[prefix + density + ".json"], "style-sprites"), dict) and
                    assets[prefix + density + ".png"].startswith(b"\x89PNG\r\n\x1a\n"), "style-sprites")


def load_source_assets(root):
    directory = Path(root) / "android/app/src/main/assets"
    require(directory.is_dir() and not directory.is_symlink(), "asset-source", "missing-dependency")
    assets = {}
    for path in sorted(directory.rglob("*")):
        require(not path.is_symlink(), "asset-source")
        if path.is_file():
            assets[path.relative_to(directory).as_posix()] = path.read_bytes()
    require(REQUIRED_ASSETS <= assets.keys() and all(assets.values()), "asset-source", "missing")
    check_catalog_pair(assets[CATALOG_JSON], assets[CATALOG_DB])
    check_tiles(assets[TILES])
    check_style(assets[STYLE], assets)
    return assets


def check_zip(apk, source_assets):
    infos = apk.infolist()
    names = [info.filename.removesuffix("/") if info.is_dir() else info.filename for info in infos]
    require(len(names) == len(set(names)), "zip-entries", "duplicate")
    for info in infos:
        name = info.filename.removesuffix("/") if info.is_dir() else info.filename
        require(name and not name.startswith("/") and "\\" not in name and
                PurePosixPath(name).as_posix() == name and ".." not in name.split("/") and
                (info.external_attr >> 16) & 0o170000 != 0o120000 and not info.flag_bits & 1,
                "zip-entries")
    files = {info.filename for info in infos if not info.is_dir()}
    require("AndroidManifest.xml" in files and "classes.dex" in files, "apk-shape", "missing")
    native = {name for name in files if name.startswith("lib/")}
    require(native and all(len(name.split("/")) == 3 and name.endswith(".so") for name in native) and
            {name.split("/")[1] for name in native} == {"arm64-v8a"}, "abi")
    require({name.split("/")[2] for name in native} == JNI_LIBRARIES, "jni-libraries", "missing")
    for name in sorted(native):
        header = apk.read(name)[:64]
        require(len(header) == 64 and header[:6] == b"\x7fELF\x02\x01" and
                struct.unpack_from("<H", header, 18)[0] == 183, "jni-architecture")
    packaged = {name.removeprefix("assets/"): apk.read(name)
                for name in files if name.startswith("assets/")}
    require(REQUIRED_ASSETS <= source_assets.keys() and source_assets.keys() <= packaged.keys(), "packaged-assets", "missing")
    require(all(packaged[name] == data for name, data in source_assets.items()), "asset-identity")
    # Dependency assets (e.g. dexopt profiles) may exist, but cannot shadow our namespaces.
    namespaces = {name.split("/")[0] for name in source_assets}
    require({name for name in packaged if name.split("/")[0] in namespaces} == set(source_assets), "asset-identity")
    check_catalog_pair(packaged[CATALOG_JSON], packaged[CATALOG_DB])
    check_tiles(packaged[TILES])
    check_style(packaged[STYLE], packaged)


def expected_identity(build):
    require(build in CERTIFICATES, "variant")
    return ("au.com.ausroads" + (".debug" if build == "debug" else ""),
            "1.2" + ("-debug" if build == "debug" else ""), "4")


def parse_permissions(text):
    permissions = text.splitlines()
    require(all(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.]*\.[A-Za-z0-9_]+", line.strip()) for line in permissions),
            "permission-readback")
    names = [line.strip() for line in permissions]
    require(len(names) == len(set(names)), "permission-readback", "duplicate")
    return set(names)


def has_service_type(service, name, mask, *, exact=False):
    value = service.get(ANDROID + "foregroundServiceType", "")
    if re.fullmatch(r"(?:0x[0-9a-fA-F]+|[0-9]+)", value):
        numeric = int(value, 16 if value.startswith("0x") else 10)
        return numeric == mask if exact else bool(numeric & mask)
    return value == name if exact else name in value.split("|")


def check_manifest(xml, permissions_text, flavor, build):
    require(flavor in {"offline", "withNetwork"}, "variant")
    try:
        require("<!DOCTYPE" not in xml and "<!ENTITY" not in xml, "manifest-readback")
        manifest = ET.fromstring(xml)
    except (ET.ParseError, ValueError):
        raise GateError("manifest-readback") from None
    require(manifest.tag == "manifest" and len(manifest.findall("application")) == 1, "manifest-readback")
    identity = (manifest.get("package"), manifest.get(ANDROID + "versionName"), manifest.get(ANDROID + "versionCode"))
    require(identity == expected_identity(build), "identity")
    requested = [node for node in manifest if node.tag in {"uses-permission", "uses-permission-sdk-23"}]
    names = [node.get(ANDROID + "name") for node in requested]
    require(all(isinstance(name, str) and name for name in names) and len(names) == len(set(names)), "permissions")
    permissions = parse_permissions(permissions_text)
    require(permissions == set(names), "permission-readback")
    services = manifest.findall("application/service")
    common = COMMON_PERMISSIONS | {identity[0] + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"}
    if flavor == "offline":
        require(not permissions & OFFLINE_FORBIDDEN and not any("LOCATION" in name for name in permissions) and
                not any(has_service_type(service, "location", 8) or service.get(ANDROID + "name") == NAVIGATION_SERVICE
                        for service in services) and not FUSED.search(xml), "offline-privacy")
        require(permissions == common, "offline-permissions")
    else:
        require(permissions == NETWORK_REQUIRED | common and not any(
            "LOCATION" in name and name not in NETWORK_LOCATION_ALLOWED for name in permissions), "network-permissions")
        for node in requested:
            if node.get(ANDROID + "name") in NETWORK_REQUIRED:
                maximum = node.get(ANDROID + "maxSdkVersion")
                require(maximum is None or (maximum.isdecimal() and int(maximum) >= 35), "network-permissions")
        navigation = [service for service in services if service.get(ANDROID + "name") == NAVIGATION_SERVICE]
        require(len(navigation) == 1 and has_service_type(navigation[0], "location", 8) and
                navigation[0].get(ANDROID + "exported") == "false", "navigation-service")
    downloads = [service for service in services if service.get(ANDROID + "name") == DOWNLOAD_SERVICE]
    require(len(downloads) == 1 and downloads[0].get(ANDROID + "exported") == "false" and
            downloads[0].get(ANDROID + "directBootAware") == "false" and
            re.fullmatch(r"@ref/0x[0-9a-fA-F]{8}", downloads[0].get(ANDROID + "enabled", "")) is not None and
            (has_service_type(downloads[0], "dataSync", 1, exact=True) if flavor == "withNetwork" else
             downloads[0].get(ANDROID + "foregroundServiceType") is None), "download-service")


def check_download_resource(xml, resources):
    # Match the actual compiled reference to the original WorkManager bool name
    # and default value, not an assumed stable AAPT resource ID or source string.
    entries = re.findall(r"^\s*resource (0x[0-9a-fA-F]{8}) bool/enable_system_foreground_service_default\n"
                         r"((?:(?!\s*resource\s)[^\n]*\n)*)", resources, re.MULTILINE)
    require(len(entries) == 1, "download-defaults")
    identity, body = entries[0]
    values = re.findall(r"^\s*\(([^)]*)\)\s+(true|false)\s*$", body, re.MULTILINE)
    require(values == [("", "true")], "download-defaults")
    services = ET.fromstring(xml).findall("application/service")
    service = next(s for s in services if s.get(ANDROID + "name") == DOWNLOAD_SERVICE)
    require(service.get(ANDROID + "enabled") == "@ref/" + identity, "download-defaults")


def check_certificate(text, build):
    require(build in CERTIFICATES, "variant")
    certificates = re.findall(r"^Signer #(\d+) certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$", text, re.MULTILINE)
    require([(number, digest.lower()) for number, digest in certificates] == [("1", CERTIFICATES[build])],
            "signing-continuity")


def check_dex_readback(text, flavor):
    require("au.com.ausroads.AusRoadsApp" in text and "au.com.ausroads.MainActivity" in text, "dex-readback")
    if flavor == "offline":
        require(not FUSED.search(text), "offline-fused-provider")


@dataclass(frozen=True)
class Tools:
    apksigner: Path
    apkanalyzer: Path
    env: dict


def resolve_tools(sdk_root, java_home, build_tools="35.0.0"):
    sdk_root, java_home = Path(sdk_root), Path(java_home)
    signer = sdk_root / "build-tools" / build_tools / "apksigner"
    analyzer = sdk_root / "cmdline-tools/latest/bin/apkanalyzer"
    require(all(path.is_file() and os.access(path, os.X_OK) for path in
                (signer, signer.with_name("aapt2"), analyzer, java_home / "bin/java")), "dependencies", "missing-dependency")
    return Tools(signer, analyzer, dict(os.environ, JAVA_HOME=str(java_home.resolve())))


def sdk_readback(tool, arguments, env):
    try:
        return subprocess.run([str(tool), *map(str, arguments)], env=env, check=True,
                              text=True, capture_output=True, timeout=120).stdout
    except (OSError, subprocess.SubprocessError, UnicodeError):
        raise GateError("sdk-readback", "not-joined") from None


def check_one(path, flavor, build, source_assets, tools):
    require((flavor, build) in VARIANTS, "variant")
    path = Path(path)
    require(path.is_file() and not path.is_symlink(), "artifact-input", "missing")
    before = sha256_file(path)
    try:
        with zipfile.ZipFile(path) as apk:
            check_zip(apk, source_assets)
    except (zipfile.BadZipFile, RuntimeError, EOFError, NotImplementedError, zlib.error):
        raise GateError("zip-readback") from None
    check_certificate(sdk_readback(tools.apksigner, ["verify", "--verbose", "--print-certs", path], tools.env), build)
    xml = sdk_readback(tools.apkanalyzer, ["manifest", "print", path], tools.env)
    permissions = sdk_readback(tools.apkanalyzer, ["manifest", "permissions", path], tools.env)
    check_manifest(xml, permissions, flavor, build)
    resources = sdk_readback(tools.apksigner.with_name("aapt2"), ["dump", "resources", path], tools.env)
    check_download_resource(xml, resources)
    # SDK lists definitions AND references: not merely grepping ZIP filenames or source Gradle.
    check_dex_readback(sdk_readback(tools.apkanalyzer, ["dex", "packages", path], tools.env), flavor)
    require(before == sha256_file(path), "artifact-freshness")
    package, version, code = expected_identity(build)
    return {"flavor": flavor, "build": build, "apk": path.name, "sha256": before,
            "package": package, "version": version, "versionCode": code,
            "certificate": CERTIFICATES[build], "abi": "arm64-v8a"}


def check_all(source_root, apk_root, tools):
    assets = load_source_assets(source_root)
    paths = [Path(apk_root) / flavor / build / f"app-{flavor}-{build}.apk" for flavor, build in VARIANTS]
    require(all(path.is_file() and not path.is_symlink() for path in paths), "artifact-input", "missing")
    results = [check_one(path, flavor, build, assets, tools)
               for path, (flavor, build) in zip(paths, VARIANTS)]
    require(assets == load_source_assets(source_root), "source-freshness")
    require(all(sha256_file(path) == result["sha256"] for path, result in zip(paths, results)),
            "artifact-freshness")
    return results


class ShippingArgumentParser(argparse.ArgumentParser):
    def error(self, _message):
        raise GateError("arguments", "invalid-input")


def main(argv=None):
    parser = ShippingArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=SOURCE_ROOT)
    parser.add_argument("--apk-root", type=Path, help="Defaults to source-root/android/app/build/outputs/apk")
    parser.add_argument("--sdk-root", type=Path, default=Path(os.environ.get("ANDROID_SDK_ROOT") or
                        os.environ.get("ANDROID_HOME") or Path.home() / "Android/Sdk"))
    parser.add_argument("--java-home", type=Path, default=Path(os.environ.get("JAVA_HOME") or Path.home() / "tools/jdk17"))
    parser.add_argument("--build-tools", default="35.0.0")
    try:
        args = parser.parse_args(argv)
        tools = resolve_tools(args.sdk_root, args.java_home, args.build_tools)
        results = check_all(args.source_root, args.apk_root or args.source_root / "android/app/build/outputs/apk", tools)
    except GateError as failure:
        print(str(failure), file=sys.stderr)
        return 1
    except (OSError, ValueError):
        print("component=shipping seam=artifact-readback class=not-joined", file=sys.stderr)
        return 1
    for result in results:
        print("APK_PASS " + json.dumps(result, sort_keys=True), flush=True)
    print("ARTIFACTS_ALL_PASS 4 real signed arm64 APKs; packaged assets/catalog, identity, continuity and flavor privacy checked", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
