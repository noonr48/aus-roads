"""Canonical dispatcher crossing for grammar-owned Groovy coordinate records."""

import os
import unittest

import test_manifest_permissions as privacy_tests

GROUP = "com.google.android.gms"
ANALYTICS = "play-services-analytics"
GPS = "play-services-location"
RELATIVE = "app/build.gradle"


class GroovyCoordinateEntrypointTests(unittest.TestCase):
    def setUp(self):
        # Reuse fixture/assertions without inheriting (and rerunning) its81 tests.
        self.fixture = privacy_tests.PrivacyAuditManifestEntrypointTests("runTest")
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)

    def check_source(self, source, *, forbidden=False, line=1, environment=None):
        self.fixture.write_manifest(RELATIVE, source)
        result = self.fixture.run_audit(env=environment)
        if forbidden:
            self.fixture.assert_sdk_refused(result, "google-analytics", RELATIVE)
            self.assertIn(f"at {RELATIVE}:{line}\n", result.stderr)
        else:
            self.fixture.assert_accepted(result)
        return result

    def test_continued_expression_records_cross_original_dispatcher(self):
        for expression in ('2 *\n 3', 'true ?\n "aar" : "jar"', '"a" +\n "ar"'):
            for first, second in (("group", "name"), ("name", "group")):
                for forbidden, name in ((True, ANALYTICS), (False, GPS)):
                    with self.subTest(expression=expression, first=first, forbidden=forbidden):
                        fields = {"group": GROUP, "name": name}
                        self.check_source(
                            f'dependencies {{\n implementation {first}: "{fields[first]}", ext: {expression}, '
                            f'{second}: "{fields[second]}"\n}}\n', forbidden=forbidden, line=2)

    def test_computed_values_property_assignments_and_annotation_members_are_not_static_records(self):
        for value in (f'"{GROUP}"()', f'("{GROUP}")()', f'(("{GROUP}"))()', f'"{GROUP}".toString()'):
            with self.subTest(value=value):
                self.check_source(f'[group: {value}, name: "{ANALYTICS}"]\n')
        self.check_source(f'def holder = [:]\ndependencies {{ implementation group: "{GROUP}", '
                          f'ext: holder.name = "{ANALYTICS}", name: "{GPS}" }}\n')
        self.check_source(f'@interface A {{ String group(); String name() }}\n'
                          f'@A(group="{GROUP}", name="{ANALYTICS}") class NotAMap {{}}\n')

    def test_grouping_decoded_literals_defaults_and_static_main_preserve_source_records(self):
        cases = (
            (f'[group: ((("{GROUP}"))), name: (("{ANALYTICS}"))]\n', 1),
            ('["gr\\u006fup": "com\\u002egoogle.android.gms", name: "' + ANALYTICS + '"]\n', 1),
            (f'def method(value = [\n group: "{GROUP}", name: "{ANALYTICS}"]) {{ value }}\n', 2),
            (f'def loose = 1\nstatic void main(String[] args) {{\n [group:"{GROUP}", name:"{ANALYTICS}"]\n}}\n', 3),
        )
        for source, line in cases:
            with self.subTest(source=source):
                self.check_source(source, forbidden=True, line=line)

    def test_duplicate_keys_are_owned_by_the_final_direct_field(self):
        for suffix in (f'name: "{GPS}"', 'name: variable', 'name: "' + ANALYTICS + '"()'):
            with self.subTest(suffix=suffix):
                self.check_source(f'[group: "{GROUP}", name: "{ANALYTICS}", {suffix}]\n')
        self.check_source(f'[group: "{GROUP}", name: "{GPS}", name: "{ANALYTICS}"]\n', forbidden=True)
        self.check_source(f'[group: "{GROUP}", name: "{GPS}", '
                          f'ext: [group: "{GROUP}", name: "{ANALYTICS}"]]\n', forbidden=True)

    def test_malformed_suffix_publishes_no_partial_reference_or_private_payload(self):
        result = self.fixture.run_audit()  # Healthy no-Groovy fixture before killing the seam.
        self.fixture.assert_accepted(result)
        for source in (f'[group: "{GROUP}", name: "{ANALYTICS}"])\n',
                       'def label = "private-parser-payload\n', '/* private-parser-payload\n'):
            with self.subTest(source=source):
                self.fixture.write_manifest(RELATIVE, source)
                result = self.fixture.run_audit()
                self.fixture.assert_sdk_error(result, "source_parser_failed")
                self.assertNotIn("forbidden SDK reference", result.stderr)

    def test_missing_pinned_equipment_fails_closed_through_dispatcher(self):
        empty = self.fixture.workspace / "empty-gradle-home"
        empty.mkdir()
        self.fixture.write_manifest(RELATIVE, f'[group: "{GROUP}", name: "{GPS}"]\n')
        result = self.fixture.run_audit(env=dict(os.environ, GRADLE_USER_HOME=str(empty)))
        self.fixture.assert_sdk_error(result, "groovy_parser_unavailable")

    def test_child_exit_protocol_and_actual_deadline_failures_name_the_join(self):
        self.fixture.write_manifest(RELATIVE, f'[group: "{GROUP}", name: "{GPS}"]\n')
        self.fixture.assert_accepted(self.fixture.run_audit())
        hooks = self.fixture.workspace / "groovy-hooks"
        hooks.mkdir()
        environment = dict(os.environ, PYTHONPATH=str(hooks), PYTHONNOUSERSITE="1")
        prelude = (
            "import sys\nif '--sdk-references' in sys.argv:\n"
            "    import subprocess\n    original = subprocess.run\n"
            "    def run(command, *args, **kwargs):\n"
            "        if isinstance(command, list) and command[-1] == 'GroovyCoordinateRecords':\n"
        )
        faults = (
            ("groovy_parser_failed", "return subprocess.CompletedProcess(command, 7, '', 'private-parser-payload')"),
            ("groovy_parser_protocol", "return subprocess.CompletedProcess(command, 0, 'GROOVY_RECORDS\\t1\\t3.0.22\\nGROOVY_RECORDS_END\\t0\\n', '')"),
            ("groovy_parser_protocol", "return subprocess.CompletedProcess(command, 0, 'GROOVY_RECORDS\\t1\\t3.0.22\\n0\\tgrammar_parsed\\t[true]\\nGROOVY_RECORDS_END\\t1\\n', '')"),
            ("groovy_parser_timeout", "return original([sys.executable, '-c', 'import time; time.sleep(60)'], *args, **kwargs)"),
        )
        for classification, action in faults:
            with self.subTest(classification=classification, action=action):
                hook = prelude + "            " + action + "\n        return original(command, *args, **kwargs)\n    subprocess.run = run\n"
                (hooks / "sitecustomize.py").write_text(hook)
                result = self.fixture.run_audit(env=environment)
                self.fixture.assert_sdk_error(result, classification)
        (hooks / "sitecustomize.py").unlink()
        self.fixture.assert_accepted(self.fixture.run_audit(env=environment))


if __name__ == "__main__":
    unittest.main()
