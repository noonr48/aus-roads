#!/usr/bin/env python3
"""Derive the app's FTS-free offline catalog from a hash-verified real SA pack.

No network, synthetic places or owner data. Preserves optional road layers;
only standard SQLite tables/indexes are emitted for Android system SQLite.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile


CATALOG_KINDS = {'poi', 'park', 'road', 'water', 'suburb'}
POI_CLASS_KEYS = {'amenity', 'tourism', 'shop', 'leisure'}
PLACE_CLASSES = {'suburb', 'city', 'town', 'village', 'locality', 'neighbourhood', 'hamlet', 'quarter'}
OPTIONAL_TABLES = {
    'road_speed': ('lat REAL, lon REAL, maxspeed_kmh INTEGER', 'lat,lon,maxspeed_kmh'),
    'road_cameras': ('lat REAL, lon REAL, maxspeed_kmh INTEGER, way_name TEXT',
                     'lat,lon,maxspeed_kmh,way_name'),
}


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def validate_coordinates(latitude, longitude):
    if not (isinstance(latitude, (int, float)) and isinstance(longitude, (int, float)) and
            math.isfinite(latitude) and -90 <= latitude <= 90 and
            math.isfinite(longitude) and -180 <= longitude <= 180):
        raise ValueError('component=places seam=catalog-coordinates class=contract-drift')


def valid_catalog_class(kind, value):
    if value is None:  # legacy schema permits an unknown class
        return True
    if not isinstance(value, str) or not value.strip():
        return False
    if kind == 'poi':
        key, separator, tag = value.partition('=')
        # Preserve free-form OSM values and identity; legacy readers also support place tags.
        return bool(separator and ((key in POI_CLASS_KEYS and tag.strip()) or
                                   (key == 'place' and tag in PLACE_CLASSES)))
    if kind == 'park':
        return value in {'park', 'leisure=park'}
    if kind == 'water':
        return value in {'water', 'natural=water'}
    if kind == 'suburb':
        return value.removeprefix('place=') in PLACE_CLASSES
    return kind == 'road' and '=' not in value


def validate_search_row(row):
    identity, name, kind, klass, latitude, longitude = row
    if (type(identity) is not int or not isinstance(name, str) or not name.strip() or
            not isinstance(kind, str) or kind not in CATALOG_KINDS or
            not valid_catalog_class(kind, klass)):
        raise ValueError('component=places seam=catalog-rows class=contract-drift')
    validate_coordinates(latitude, longitude)


def validate_road_row(table, row):
    validate_coordinates(row[0], row[1])
    speed = row[2]
    if speed is not None and (type(speed) is not int or speed <= 0):
        raise ValueError('component=places seam=catalog-road-rows class=contract-drift')
    if table == 'road_cameras' and row[3] is not None and not isinstance(row[3], str):
        raise ValueError('component=places seam=catalog-road-rows class=contract-drift')


def validate_catalog(path, minimum_rows=50_000):
    """Read the completed output independently, including every optional row, before publication."""
    database = sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro', uri=True)
    try:
        if database.execute('PRAGMA quick_check').fetchall() != [('ok',)]:
            raise ValueError('component=places seam=catalog-output class=contract-drift')
        count = 0
        for row in database.execute('SELECT id,name,kind,class,lat,lon FROM search_meta'):
            validate_search_row(row)
            count += 1
        if count < max(1, minimum_rows):
            raise ValueError('component=places seam=catalog-coverage class=contract-drift')
        tables = {row[0] for row in database.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        for table, (_, columns) in OPTIONAL_TABLES.items():
            if table in tables:
                for row in database.execute(f'SELECT {columns} FROM {table}'):
                    validate_road_row(table, row)
        classes = dict(database.execute("SELECT coalesce(class,''),count(*) FROM search_meta "
                                        "WHERE kind IN ('poi','park') GROUP BY class"))
        return count, classes
    finally:
        database.close()


def validate_pair(database, metadata_path, expected=None):
    metadata = json.loads(Path(metadata_path).read_text())
    if (not isinstance(metadata, dict) or metadata.get('schema') != 1 or
            type(metadata.get('rows')) is not int or metadata['rows'] < 1 or
            type(metadata.get('bytes')) is not int or metadata['bytes'] != Path(database).stat().st_size or
            metadata.get('sha256') != sha256(database) or
            (expected is not None and metadata != expected)):
        raise ValueError('component=places seam=catalog-metadata class=contract-drift')


def publish_pair(candidate, metadata_candidate, output):
    """Exception safety for one producer, NOT multi-file power-loss/concurrent-reader atomicity.

    Loaders must still verify the metadata hash. Persistent rollback I/O failure keeps
    the previous copies in .catalog-previous-* rather than deleting the recovery data.
    """
    destinations = (output / 'sa-search.db', output / 'sa-search.json')
    present = tuple(path.exists() for path in destinations)
    if any(present) and not all(present):
        raise ValueError('component=places seam=catalog-publication class=contract-drift')
    if all(present):
        validate_pair(*destinations)
    recovery = Path(tempfile.mkdtemp(prefix='.catalog-previous-', dir=output))
    retain_recovery = False
    try:
        if all(present):
            for destination in destinations:
                shutil.copyfile(destination, recovery / destination.name)
        try:
            os.replace(candidate, destinations[0])
            os.replace(metadata_candidate, destinations[1])
        except Exception:
            try:
                for destination, existed in zip(destinations, present):
                    if existed:
                        os.replace(recovery / destination.name, destination)
                    else:
                        destination.unlink(missing_ok=True)
            except Exception:
                retain_recovery = True
                raise OSError('component=places seam=catalog-rollback class=wrong-path') from None
            raise OSError('component=places seam=catalog-publication class=not-joined') from None
    finally:
        if not retain_recovery:
            shutil.rmtree(recovery, ignore_errors=True)


def bundle(source, manifest_path, output, minimum_rows=50_000):
    source, output = Path(source).resolve(), Path(output).resolve()
    manifest = json.loads(Path(manifest_path).read_text())
    source_hash = sha256(source)
    if source_hash != manifest['components']['search']['sha256']:
        raise ValueError('component=places seam=catalog-source class=contract-drift: source hash differs')
    if manifest['license'] != 'ODbL-1.0':
        raise ValueError('component=places seam=catalog-license class=contract-drift')
    original = sqlite3.connect(source.as_uri() + '?mode=ro', uri=True)
    try:
        if original.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
            raise ValueError('component=places seam=catalog-source class=contract-drift: corrupt database')
        count = original.execute('SELECT count(*) FROM search_meta').fetchone()[0]
        if count < minimum_rows:
            raise ValueError('component=places seam=catalog-coverage class=contract-drift: insufficient rows')
        output.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.catalog-build-', dir=output) as temporary:
            candidate = Path(temporary) / 'sa-search.db'
            derived = sqlite3.connect(candidate)
            try:
                derived.execute('CREATE TABLE search_meta (id INTEGER PRIMARY KEY, name TEXT NOT NULL, '
                                'kind TEXT NOT NULL, class TEXT, lat REAL NOT NULL, lon REAL NOT NULL)')
                for row in original.execute('SELECT id,name,kind,class,lat,lon FROM search_meta ORDER BY id'):
                    validate_search_row(row)
                    derived.execute('INSERT INTO search_meta VALUES (?,?,?,?,?,?)', row)
                derived.execute('CREATE INDEX search_class_geo ON search_meta(class,lat,lon)')
                derived.execute('CREATE INDEX search_kind_geo ON search_meta(kind,lat,lon)')
                tables = {row[0] for row in original.execute("SELECT name FROM sqlite_master WHERE type='table'")}
                for table, (schema, columns) in OPTIONAL_TABLES.items():
                    if table in tables:
                        derived.execute(f'CREATE TABLE {table} ({schema})')
                        rows = original.execute(f'SELECT {columns} FROM {table} ORDER BY lat,lon')
                        placeholders = ','.join('?' for _ in columns.split(','))
                        for row in rows:
                            validate_road_row(table, row)
                            derived.execute(f'INSERT INTO {table} VALUES ({placeholders})', row)
                        derived.execute(f'CREATE INDEX {table}_geo ON {table}(lat,lon)')
                derived.execute('PRAGMA user_version=1')
                derived.commit()
                derived.execute('VACUUM')
            finally:
                derived.close()
            output_count, classes = validate_catalog(candidate, minimum_rows)
            if output_count != count:
                raise ValueError('component=places seam=catalog-coverage class=contract-drift')
            metadata = {
                'schema': 1, 'rows': count, 'bytes': candidate.stat().st_size,
                'sha256': sha256(candidate), 'sourceSha256': source_hash,
                'scope': 'South Australia', 'license': 'ODbL-1.0',
                'attribution': '© OpenStreetMap contributors',
                'osmSource': manifest['osmSource'], 'classes': classes,
            }
            meta_candidate = Path(temporary) / 'sa-search.json'
            meta_candidate.write_text(json.dumps(metadata, indent=2, sort_keys=True) + '\n')
            # Reproducible public data only; preserve the previous pair on ordinary publication faults.
            validate_pair(candidate, meta_candidate, expected=metadata)
            publish_pair(candidate, meta_candidate, output)
            return metadata
    finally:
        original.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()
    metadata = bundle(args.source, args.manifest, args.out)
    print(json.dumps({key: metadata[key] for key in ('rows', 'bytes', 'sha256', 'sourceSha256', 'scope')}))


if __name__ == '__main__':
    main()
