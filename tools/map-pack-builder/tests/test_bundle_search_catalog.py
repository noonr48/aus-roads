import importlib.util
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).parents[1] / 'scripts' / 'bundle-search-catalog.py'
spec = importlib.util.spec_from_file_location('bundle_search_catalog', SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class BundleCatalogTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.source = self.root / 'source.db'
        self.manifest = self.root / 'manifest.json'
        self.out = self.root / 'out'
        with closing(sqlite3.connect(self.source)) as db:
            # No affinity: wrong storage types must reach the producer rather than be coerced by the fixture.
            db.execute('CREATE TABLE search_meta(id INTEGER PRIMARY KEY,name,kind,class,lat,lon)')
            db.execute('INSERT INTO search_meta VALUES (1,?,?,?, ?,?)',
                       ('Real fixture cafe', 'poi', 'amenity=cafe', -34.92, 138.62))
            db.execute('CREATE TABLE road_cameras(lat REAL,lon REAL,maxspeed_kmh INTEGER,way_name TEXT)')
            db.execute('INSERT INTO road_cameras VALUES (-34.92,138.62,60,"Fixture")')
            db.commit()
        self.write_manifest()

    def tearDown(self):
        self.temporary.cleanup()

    def write_manifest(self):
        self.manifest.write_text(json.dumps({
            'components': {'search': {'sha256': module.sha256(self.source)}},
            'license': 'ODbL-1.0',
            'osmSource': {'provider': 'fixture', 'osmExtractDate': '2026-09-11T23:06:46Z'},
        }))

    def test_standard_tables_metadata_provenance_and_repeat_are_real(self):
        first = module.bundle(self.source, self.manifest, self.out, minimum_rows=1)
        with closing(sqlite3.connect(self.out / 'sa-search.db')) as db:
            self.assertEqual(db.execute('PRAGMA quick_check').fetchone()[0], 'ok')
            self.assertEqual(db.execute('SELECT name FROM search_meta').fetchone()[0], 'Real fixture cafe')
            self.assertEqual(db.execute('SELECT maxspeed_kmh FROM road_cameras').fetchone()[0], 60)
            self.assertEqual(db.execute("SELECT count(*) FROM sqlite_master WHERE sql LIKE '%VIRTUAL%'").fetchone()[0], 0)
        self.assertEqual(first['sha256'], module.sha256(self.out / 'sa-search.db'))
        self.assertEqual(first['sourceSha256'], module.sha256(self.source))
        self.assertEqual(first['osmSource'], json.loads(self.manifest.read_text())['osmSource'])
        self.assertEqual(json.loads((self.out / 'sa-search.json').read_text()), first)
        self.assertEqual(module.validate_catalog(self.out / 'sa-search.db', 1), (1, {'amenity=cafe': 1}))
        module.validate_pair(self.out / 'sa-search.db', self.out / 'sa-search.json')
        second = module.bundle(self.source, self.manifest, self.out, minimum_rows=1)
        self.assertEqual(first['sha256'], second['sha256'])

    def test_source_hash_kill_refuses_before_overwriting_previous_assets(self):
        module.bundle(self.source, self.manifest, self.out, minimum_rows=1)
        old = self.pair_bytes()
        self.source.write_bytes(b'corrupt')
        with self.assertRaisesRegex(ValueError, 'catalog-source.*contract-drift'):
            module.bundle(self.source, self.manifest, self.out, minimum_rows=1)
        self.assertEqual(old, self.pair_bytes())

    def test_optional_road_coordinates_refuse_before_overwriting_both_assets(self):
        with closing(sqlite3.connect(self.source)) as db:
            db.execute('CREATE TABLE road_speed(lat REAL,lon REAL,maxspeed_kmh INTEGER)')
            db.execute('INSERT INTO road_speed VALUES (-34.92,138.62,60)')
            db.commit()
        self.write_manifest()
        module.bundle(self.source, self.manifest, self.out, minimum_rows=1)
        old_db = (self.out / 'sa-search.db').read_bytes()
        old_metadata = (self.out / 'sa-search.json').read_bytes()
        for table in ('road_speed', 'road_cameras'):
            for latitude, longitude in ((None, 138.62), ('not-a-number', 138.62),
                                        (float('inf'), 138.62), (100.0, 138.62),
                                        (-34.92, 190.0), (-34.92, None)):
                with self.subTest(table=table, latitude=latitude, longitude=longitude):
                    with closing(sqlite3.connect(self.source)) as db:
                        for layer in ('road_speed', 'road_cameras'):
                            db.execute(f'UPDATE {layer} SET lat=-34.92,lon=138.62')
                        db.execute(f'UPDATE {table} SET lat=?,lon=?', (latitude, longitude))
                        db.commit()
                    self.write_manifest()  # isolate coordinates, not source-hash rejection
                    with self.assertRaisesRegex(ValueError, 'catalog-coordinates.*contract-drift'):
                        module.bundle(self.source, self.manifest, self.out, minimum_rows=1)
                    self.assertEqual(old_db, (self.out / 'sa-search.db').read_bytes())
                    self.assertEqual(old_metadata, (self.out / 'sa-search.json').read_bytes())

    def test_coordinates_and_minimum_coverage_are_not_silently_dropped(self):
        with self.assertRaisesRegex(ValueError, 'catalog-coverage'):
            module.bundle(self.source, self.manifest, self.out, minimum_rows=2)
        with closing(sqlite3.connect(self.source)) as db:
            db.execute('UPDATE search_meta SET lat=100')
            db.commit()
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, 'catalog-coordinates'):
            module.bundle(self.source, self.manifest, self.out, minimum_rows=1)

    def pair_bytes(self):
        return tuple((self.out / name).read_bytes() for name in ('sa-search.db', 'sa-search.json'))

    def prepare_replacement(self):
        module.bundle(self.source, self.manifest, self.out, minimum_rows=1)
        old = self.pair_bytes()
        with closing(sqlite3.connect(self.source)) as db:
            db.execute("UPDATE search_meta SET name='Replacement venue'")
            db.commit()
        self.write_manifest()
        return old

    def test_malformed_last_row_names_kinds_and_classes_refuse_before_publish(self):
        module.bundle(self.source, self.manifest, self.out, minimum_rows=1)
        old = self.pair_bytes()
        valid = ('Second fixture', 'poi', 'amenity=cafe', -34.92, 138.62)
        bad_fields = (
            (0, None), (0, ''), (0, ' \t\n'), (0, 12), (0, b'not text'),
            (1, None), (1, ''), (1, 12), (1, 'unknown'),
            (2, ''), (2, ' \t'), (2, 12), (2, b'not text'), (2, 'cafe'),
            (2, 'amenity='), (2, 'amenity=  '), (2, 'unknown=cafe'),
            (3, 'not-a-number'), (4, '138.62'), (3, b'not numeric'),
            (3, None), (4, None), (3, float('nan')), (4, float('inf')),
            (3, -90.01), (3, 90.01), (4, -180.01), (4, 180.01),
        )
        bad_rows = []
        for column, value in bad_fields:
            row = list(valid)
            row[column] = value
            bad_rows.append(row)
        for kind, klass in (('park', 'amenity=cafe'), ('water', 'park'),
                            ('suburb', 'amenity=cafe'), ('road', 'amenity=cafe')):
            bad_rows.append((valid[0], kind, klass, valid[3], valid[4]))
        for index, row in enumerate(bad_rows):
            with self.subTest(control=index):
                with closing(sqlite3.connect(self.source)) as db:
                    db.execute('DELETE FROM search_meta WHERE id=2')
                    db.execute('INSERT INTO search_meta VALUES (2,?,?,?,?,?)', row)
                    db.commit()
                self.write_manifest()
                with self.assertRaisesRegex(ValueError, 'catalog-(rows|coordinates).*contract-drift'):
                    module.bundle(self.source, self.manifest, self.out, minimum_rows=1)
                self.assertEqual(old, self.pair_bytes())
                module.validate_pair(self.out / 'sa-search.db', self.out / 'sa-search.json')

    def test_legacy_null_classes_and_free_form_values_keep_row_identity_without_optional_tables(self):
        rows = [
            (11, 'Null class', 'poi', None, -34.92, 138.62),
            (12, 'Mixed case', 'poi', 'amenity=Natural Rock Formation - views', -34.92, 138.62),
            (13, 'Semicolons', 'poi', 'shop=clothes;florist;houseware', -34.92, 138.62),
            (14, 'Legacy place', 'poi', 'place=city', -34.92, 138.62),
            (15, 'Park', 'park', 'park', -34.92, 138.62),
            (16, 'Water', 'water', 'water', -34.92, 138.62),
            (17, 'Town', 'suburb', 'town', -34.92, 138.62),
            (18, 'Road', 'road', 'residential', -34.92, 138.62),
            (19, 'South bound', 'poi', 'amenity=cafe', -90, -180),
            (20, 'North bound', 'poi', 'amenity=cafe', 90, 180),
        ]
        with closing(sqlite3.connect(self.source)) as db:
            db.execute('DELETE FROM search_meta')
            db.executemany('INSERT INTO search_meta VALUES (?,?,?,?,?,?)', rows)
            db.execute('DROP TABLE road_cameras')
            db.commit()
        self.write_manifest()
        metadata = module.bundle(self.source, self.manifest, self.out, minimum_rows=1)
        with closing(sqlite3.connect(self.out / 'sa-search.db')) as db:
            self.assertEqual(db.execute('SELECT * FROM search_meta ORDER BY id').fetchall(), rows)
            self.assertEqual(db.execute("SELECT name FROM sqlite_master WHERE name IN ('road_speed','road_cameras')").fetchall(), [])
        self.assertEqual(metadata['rows'], len(rows))
        module.validate_catalog(self.out / 'sa-search.db', 1)

    def test_optional_road_value_types_refuse_without_replacing_previous_pair(self):
        with closing(sqlite3.connect(self.source)) as db:
            db.execute('CREATE TABLE road_speed(lat REAL,lon REAL,maxspeed_kmh INTEGER)')
            db.execute('INSERT INTO road_speed VALUES (-34.92,138.62,60)')
            db.execute('UPDATE road_cameras SET maxspeed_kmh=NULL,way_name=NULL')
            db.commit()
        self.write_manifest()
        module.bundle(self.source, self.manifest, self.out, minimum_rows=1)
        old = self.pair_bytes()
        for table in ('road_speed', 'road_cameras'):
            for speed in ('fast', b'not numeric', 60.5, 0, -1):
                with self.subTest(table=table, speed=speed):
                    with closing(sqlite3.connect(self.source)) as db:
                        for layer in ('road_speed', 'road_cameras'):
                            db.execute(f'UPDATE {layer} SET maxspeed_kmh=60')
                        db.execute(f'UPDATE {table} SET maxspeed_kmh=?', (speed,))
                        db.commit()
                    self.write_manifest()
                    with self.assertRaisesRegex(ValueError, 'catalog-road-rows.*contract-drift'):
                        module.bundle(self.source, self.manifest, self.out, minimum_rows=1)
                    self.assertEqual(old, self.pair_bytes())
        with closing(sqlite3.connect(self.source)) as db:
            db.execute('UPDATE road_speed SET maxspeed_kmh=60')
            db.execute('UPDATE road_cameras SET maxspeed_kmh=60,way_name=?', (b'not text',))
            db.commit()
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, 'catalog-road-rows.*contract-drift'):
            module.bundle(self.source, self.manifest, self.out, minimum_rows=1)
        self.assertEqual(old, self.pair_bytes())

    def test_entire_completed_output_is_revalidated_before_publish(self):
        old = self.prepare_replacement()
        with closing(sqlite3.connect(self.source)) as db:
            db.execute("INSERT INTO search_meta VALUES (2,'Later row','poi','amenity=cafe',-34.92,138.62)")
            db.commit()
        self.write_manifest()
        actual_validate = module.validate_catalog

        def corrupt_completed_output(path, minimum_rows):
            with closing(sqlite3.connect(path)) as db:
                db.execute("UPDATE search_meta SET name='   ' WHERE id=2")
                db.commit()
            return actual_validate(path, minimum_rows)

        with patch.object(module, 'validate_catalog', side_effect=corrupt_completed_output):
            with self.assertRaisesRegex(ValueError, 'catalog-rows.*contract-drift'):
                module.bundle(self.source, self.manifest, self.out, minimum_rows=1)
        self.assertEqual(old, self.pair_bytes())

    def test_metadata_write_failure_preserves_previous_pair(self):
        old = self.prepare_replacement()
        with patch.object(module.Path, 'write_text', side_effect=OSError('injected metadata failure')):
            with self.assertRaises(OSError):
                module.bundle(self.source, self.manifest, self.out, minimum_rows=1)
        self.assertEqual(old, self.pair_bytes())
        module.validate_pair(self.out / 'sa-search.db', self.out / 'sa-search.json')

    def test_publication_failures_before_or_after_each_replace_restore_previous_pair(self):
        old = self.prepare_replacement()
        actual_replace = module.os.replace
        for ordinal in (1, 2):
            for after in (False, True):
                for error in (OSError, RuntimeError):
                    with self.subTest(replacement=ordinal, after=after, error=error.__name__):
                        calls = 0

                        def fail_once(source, destination):
                            nonlocal calls
                            calls += 1
                            if calls == ordinal:
                                if after:
                                    actual_replace(source, destination)
                                raise error('injected publication failure')
                            return actual_replace(source, destination)

                        with patch.object(module.os, 'replace', side_effect=fail_once):
                            with self.assertRaisesRegex(OSError, 'catalog-publication.*not-joined'):
                                module.bundle(self.source, self.manifest, self.out, minimum_rows=1)
                        self.assertEqual(old, self.pair_bytes())
                        module.validate_pair(self.out / 'sa-search.db', self.out / 'sa-search.json')
                        module.validate_catalog(self.out / 'sa-search.db', 1)
                        self.assertEqual(list(self.out.glob('.catalog-previous-*')), [])
        replacement = module.bundle(self.source, self.manifest, self.out, minimum_rows=1)
        self.assertNotEqual(old, self.pair_bytes())
        self.assertEqual(replacement['sha256'], module.sha256(self.out / 'sa-search.db'))

    def test_failed_first_publication_removes_both_destinations(self):
        actual_replace = module.os.replace
        for after in (False, True):
            calls = 0

            def fail_second(source, destination):
                nonlocal calls
                calls += 1
                if calls == 2:
                    if after:
                        actual_replace(source, destination)
                    raise OSError('injected second publication failure')
                return actual_replace(source, destination)

            with patch.object(module.os, 'replace', side_effect=fail_second):
                with self.assertRaisesRegex(OSError, 'catalog-publication.*not-joined'):
                    module.bundle(self.source, self.manifest, self.out, minimum_rows=1)
            self.assertFalse((self.out / 'sa-search.db').exists())
            self.assertFalse((self.out / 'sa-search.json').exists())
        module.bundle(self.source, self.manifest, self.out, minimum_rows=1)
        module.validate_pair(self.out / 'sa-search.db', self.out / 'sa-search.json')

    def test_backup_failure_leaves_previous_pair_untouched(self):
        old = self.prepare_replacement()
        with patch.object(module.shutil, 'copyfile', side_effect=OSError('injected backup failure')):
            with patch.object(module.os, 'replace') as publication:
                with self.assertRaises(OSError):
                    module.bundle(self.source, self.manifest, self.out, minimum_rows=1)
                publication.assert_not_called()
        self.assertEqual(old, self.pair_bytes())

    def test_persistent_rollback_failure_retains_previous_copies_and_fixed_alarm(self):
        old = self.prepare_replacement()
        actual_replace = module.os.replace
        calls = 0

        def fail_publication_and_rollback(source, destination):
            nonlocal calls
            calls += 1
            if calls in (2, 3):
                raise OSError('injected persistent I/O failure')
            return actual_replace(source, destination)

        with patch.object(module.os, 'replace', side_effect=fail_publication_and_rollback):
            with self.assertRaisesRegex(OSError, 'catalog-rollback.*wrong-path'):
                module.bundle(self.source, self.manifest, self.out, minimum_rows=1)
        recovery, = self.out.glob('.catalog-previous-*')
        self.assertEqual(tuple((recovery / name).read_bytes() for name in ('sa-search.db', 'sa-search.json')), old)
        # Rollback failure is not a successful publication; loader hash checks must still reject it.
        with self.assertRaisesRegex(ValueError, 'catalog-metadata.*contract-drift'):
            module.validate_pair(self.out / 'sa-search.db', self.out / 'sa-search.json')


if __name__ == '__main__':
    unittest.main()
