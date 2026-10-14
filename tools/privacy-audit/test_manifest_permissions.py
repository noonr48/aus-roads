"""Real run.sh positive/negative controls; all mutations stay in temporary roots.

Run from the repository: python3 -m unittest discover -s tools/privacy-audit
-p 'test_manifest_permissions.py' -v
"""

import os
import shutil
import subprocess
import tempfile
import unittest
from itertools import permutations
from pathlib import Path

AUDIT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = AUDIT_DIR.parents[1]
RUN_SCRIPT = AUDIT_DIR / "run.sh"
MAIN = "app/src/main/AndroidManifest.xml"
OFFLINE = "app/src/offline/AndroidManifest.xml"
NETWORK = "app/src/withNetwork/AndroidManifest.xml"
SOURCE_MANIFESTS = (
    MAIN, OFFLINE, NETWORK,
    "app/src/debug/AndroidManifest.xml",
    "offline/pack-downloader/src/main/AndroidManifest.xml",
)
LOCATION_PERMISSIONS = (
    "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
    "ACCESS_BACKGROUND_LOCATION", "ACCESS_MEDIA_LOCATION",
)
SENSITIVE_PERMISSIONS = ("INTERNET", *LOCATION_PERMISSIONS, "FOREGROUND_SERVICE_LOCATION")
CURRENT_NETWORK_PERMISSIONS = (
    "INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE",
    "FOREGROUND_SERVICE_DATA_SYNC", "POST_NOTIFICATIONS",
    "ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION",
    "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_LOCATION",
)
PERMISSION_TAGS = ("uses-permission", "uses-permission-sdk-23")
SDK_CASES = (
    ("firebase", "com.google.firebase.analytics.FirebaseAnalytics", "com.google.firebase:firebase-analytics"),
    ("crashlytics", "com.crashlytics.android.Crashlytics", "com.crashlytics.sdk.android:crashlytics"),
    ("google-analytics", "com.google.android.gms.analytics.GoogleAnalytics", "com.google.android.gms:play-services-analytics"),
    ("amplitude", "com.amplitude.api.Amplitude", "com.amplitude:android-sdk"),
    ("mixpanel", "com.mixpanel.android.mpmetrics.MixpanelAPI", "com.mixpanel.android:mixpanel-android"),
    ("appsflyer", "com.appsflyer.AppsFlyerLib", "com.appsflyer:af-android-sdk"),
    ("adjust", "com.adjust.sdk.Adjust", "com.adjust.sdk:adjust-android"),
)

TOML_COORDINATES = tuple((sdk, coordinate) for sdk, _, coordinate in SDK_CASES) + (
    ("google-analytics", "com.google.android.gms:play-services-measurement-api"),
)
HTTP_OWNERS = (
    "offline/pack-downloader/src/main/kotlin/au/com/ausroads/offline/download/network/HttpClientProvider.kt",
    "traffic/provider-sa/src/main/kotlin/au/com/ausroads/traffic/provider/sa/TrafficSaProvider.kt",
    "traffic/provider-nsw/src/main/kotlin/au/com/ausroads/traffic/provider/nsw/NswLiveTrafficProvider.kt",
    "traffic/provider-vic/src/main/kotlin/au/com/ausroads/traffic/provider/vic/VicRoadsProvider.kt",
    "traffic/provider-sa-outback/src/main/kotlin/au/com/ausroads/traffic/provider/sa/outback/SaOutbackProvider.kt",
)


def permission(name, removed=False, tag="uses-permission"):
    removal = ' tools:node="remove"' if removed else ""
    return f'<{tag} android:name="android.permission.{name}"{removal} />'


def manifest(*nodes):
    return (
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    xmlns:tools="http://schemas.android.com/tools">\n'
        + "\n".join(nodes) + "\n</manifest>\n"
    )


def multiline_manifest(*nodes):
    # Different prefixes and quotes are the same Android/tools namespaces.
    body = "\n".join(nodes).replace(" android:", "\n    a:").replace(" tools:", "\n    merger:")
    return (
        "<manifest xmlns:a='http://schemas.android.com/apk/res/android'\n"
        "    xmlns:merger='http://schemas.android.com/tools'>\n"
        + body.replace('"', "'") + "\n</manifest>\n"
    )


class PrivacyAuditManifestEntrypointTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="aus-roads-privacy-")
        self.addCleanup(self.temporary.cleanup)
        self.workspace = Path(self.temporary.name)
        self.root = self.workspace / "fixture project"
        self.make_project(self.root)

    def write_manifest(self, relative, content, root=None):
        path = (root if root is not None else self.root) / "android" / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def make_project(self, root):
        self.write_manifest(MAIN, manifest(*(permission(name, True) for name in LOCATION_PERMISSIONS)), root)
        self.write_manifest(OFFLINE, manifest(*(permission(name, True) for name in (
            "INTERNET", "ACCESS_WIFI_STATE", *LOCATION_PERMISSIONS,
        ))), root)
        self.write_manifest(NETWORK, manifest(*(permission(name) for name in CURRENT_NETWORK_PERMISSIONS)), root)
        self.write_manifest(SOURCE_MANIFESTS[3], manifest("<application />"), root)
        self.write_manifest(SOURCE_MANIFESTS[4], manifest(permission("FOREGROUND_SERVICE_DATA_SYNC")), root)

    def run_audit(self, root=None, arguments=None, script=RUN_SCRIPT, env=None):
        command = ["bash", str(script)]
        command.extend(arguments if arguments is not None else [
            "--project-root", str(root if root is not None else self.root),
        ])
        return subprocess.run(
            command, cwd=self.workspace, capture_output=True, text=True,
            env=env, timeout=20, check=False,
        )

    def assert_accepted(self, result):
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode, 0, output)
        self.assertIn("Errors: 0", output)
        self.assertIn("Manifest permissions respect source flavor policy", output)
        self.assertIn("Checking HttpClient instances...", output)
        self.assertIn("Checking for analytics/crash SDKs...", output)
        self.assertIn("Warnings: 0", output)
        self.assertNotIn("class=", output)

    def assert_refused(self, result, classification):
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode, 1, output)
        self.assertIn(
            f"[component=privacy-audit seam=manifest-permissions class={classification}]",
            output,
        )
        self.assertNotIn("PASSED", output)
        self.assertNotIn(str(self.workspace), output)

    def assert_sdk_refused(self, result, sdk, relative):
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode, 1, output)
        self.assertIn("Manifest permissions respect source flavor policy", output)
        self.assertIn("[component=privacy-audit seam=sdk-references class=forbidden_sdk_reference]", output)
        self.assertIn(f"forbidden SDK reference ({sdk}) at {relative}:", output)
        self.assertNotIn("PASSED", output)
        self.assertNotIn("Traceback", output)
        self.assertNotIn(str(self.workspace), output)

    def assert_http_refused(self, result, classification="forbidden_httpclient"):
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode, 1, output)
        self.assertIn(f"[component=privacy-audit seam=httpclient-ownership class={classification}]", output)
        self.assertIn("seam=httpclient-ownership class=checker_failed", output)
        for private in ("private-parser-payload", "Traceback", str(self.workspace), "PASSED",
                        "HttpClient constructor sites respect reviewed source ownership"):
            self.assertNotIn(private, output)

    def assert_sdk_error(self, result, classification, errors=1):
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode, 1, output)
        self.assertIn("Manifest permissions respect source flavor policy", output)
        self.assertIn(f"[component=privacy-audit seam=sdk-references class={classification}]", output)
        self.assertIn("seam=sdk-references class=checker_failed", output)
        self.assertIn(f"Errors: {errors}", output)
        for private in ("private-parser-payload", "Traceback", str(self.workspace), "PASSED",
                        "No forbidden SDK references in source/dependency declarations"):
            self.assertNotIn(private, output)

    def test_current_shaped_flavors_are_accepted(self):
        self.assert_accepted(self.run_audit())

    def test_actual_current_source_manifests_are_accepted(self):
        for relative in SOURCE_MANIFESTS:
            self.write_manifest(relative, (PROJECT_ROOT / "android" / relative).read_text(encoding="utf-8"))
        self.assert_accepted(self.run_audit())

    def test_actual_current_repository_source_audit_is_accepted(self):
        # Original no-argument entrypoint, not a manifest-only copy or helper call.
        self.assert_accepted(self.run_audit(arguments=[]))

    def test_offline_rejects_each_sensitive_permission_on_both_tags(self):
        for tag in PERMISSION_TAGS:
            for name in (*SENSITIVE_PERMISSIONS, "LOCATION_HARDWARE"):
                with self.subTest(tag=tag, permission=name):
                    self.write_manifest(OFFLINE, manifest(permission(name, tag=tag)))
                    self.assert_refused(self.run_audit(), "forbidden_permission")

    def test_network_rejects_background_media_and_other_location_on_both_tags(self):
        for tag in PERMISSION_TAGS:
            for name in ("ACCESS_BACKGROUND_LOCATION", "ACCESS_MEDIA_LOCATION", "LOCATION_HARDWARE"):
                with self.subTest(tag=tag, permission=name):
                    self.write_manifest(NETWORK, manifest(
                        *(permission(allowed) for allowed in CURRENT_NETWORK_PERMISSIONS),
                        permission(name, tag=tag),
                    ))
                    self.assert_refused(self.run_audit(), "forbidden_permission")

    def test_removals_and_network_foreground_grants_work_on_both_tags(self):
        for tag in PERMISSION_TAGS:
            with self.subTest(tag=tag):
                self.write_manifest(OFFLINE, manifest(
                    *(permission(name, True, tag) for name in (*SENSITIVE_PERMISSIONS, "LOCATION_HARDWARE")),
                ))
                self.write_manifest(NETWORK, manifest(
                    *(permission(name, tag=tag) for name in CURRENT_NETWORK_PERMISSIONS),
                ))
                self.assert_accepted(self.run_audit())

    def test_mixed_removed_and_unremoved_permissions_are_independent(self):
        for tag in PERMISSION_TAGS:
            for removed, active in (
                ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"),
                ("ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION"),
                ("ACCESS_FINE_LOCATION", "INTERNET"),
                ("INTERNET", "INTERNET"),
            ):
                with self.subTest(tag=tag, removed=removed, active=active):
                    self.write_manifest(OFFLINE, manifest(
                        permission(removed, True, tag), permission(active, tag=tag),
                    ))
                    self.assert_refused(self.run_audit(), "forbidden_permission")

    def test_same_line_or_different_tag_removal_cannot_mask_active_permission(self):
        for active in ("INTERNET", "ACCESS_COARSE_LOCATION"):
            with self.subTest(active=active):
                self.write_manifest(OFFLINE, manifest(
                    permission("ACCESS_FINE_LOCATION", True, "uses-permission-sdk-23")
                    + permission(active),
                ))
                self.assert_refused(self.run_audit(), "forbidden_permission")

    def test_multiline_xml_and_namespace_aliases_are_accepted(self):
        self.write_manifest(OFFLINE, multiline_manifest(
            *(permission(name, True) for name in SENSITIVE_PERMISSIONS),
        ))
        self.write_manifest(NETWORK, multiline_manifest(
            *(permission(name) for name in CURRENT_NETWORK_PERMISSIONS),
        ))
        self.assert_accepted(self.run_audit())

    def test_multiline_active_permission_is_refused(self):
        for tag in PERMISSION_TAGS:
            with self.subTest(tag=tag):
                self.write_manifest(OFFLINE, multiline_manifest(
                    permission("ACCESS_FINE_LOCATION", True), permission("INTERNET", tag=tag),
                ))
                self.assert_refused(self.run_audit(), "forbidden_permission")

    def test_remove_attribute_in_wrong_namespace_is_not_a_removal(self):
        self.write_manifest(OFFLINE, manifest(permission("ACCESS_COARSE_LOCATION", True)).replace(
            "http://schemas.android.com/tools", "urn:not-android-tools",
        ))
        self.assert_refused(self.run_audit(), "forbidden_permission")

    def test_comments_are_not_permission_declarations(self):
        self.write_manifest(MAIN, manifest(
            "<!-- android.permission.INTERNET android.permission.ACCESS_FINE_LOCATION -->",
        ))
        self.assert_accepted(self.run_audit())

    def test_network_allowance_requires_exact_app_flavor_manifest(self):
        for relative in (
            MAIN, SOURCE_MANIFESTS[3], SOURCE_MANIFESTS[4],
            "app/src/withNetworkDebug/AndroidManifest.xml",
            "feature/other/src/withNetwork/AndroidManifest.xml",
            "app/src/offline/withNetwork/AndroidManifest.xml",
        ):
            with self.subTest(path=relative):
                path = self.root / "android" / relative
                old_content = path.read_text(encoding="utf-8") if path.exists() else None
                try:
                    self.write_manifest(relative, manifest(
                        *(permission(name) for name in ("INTERNET", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION")),
                    ))
                    result = self.run_audit()
                finally:
                    if old_content is None:
                        path.unlink()
                    else:
                        path.write_text(old_content, encoding="utf-8")
                self.assert_refused(result, "forbidden_permission")

    def test_network_word_in_project_root_does_not_allow_offline_permissions(self):
        root = self.workspace / "withNetwork-project"
        self.make_project(root)
        self.write_manifest(OFFLINE, manifest(permission("INTERNET")), root)
        self.assert_refused(self.run_audit(root), "forbidden_permission")

    def test_malformed_xml_refuses_without_parser_payload(self):
        for content in (
            "<manifest><private-parser-payload",
            "<manifest><uses-permission android:name='android.permission.INTERNET' /></manifest>",
            "<manifest><application></manifest>",
        ):
            with self.subTest(content=content):
                self.write_manifest(MAIN, content)
                result = self.run_audit()
                self.assert_refused(result, "malformed_xml")
                self.assertNotIn("private-parser-payload", result.stderr)
                self.assertNotIn("Traceback", result.stderr)

    def test_invalid_manifest_or_permission_refuses(self):
        self.write_manifest(MAIN, "<application />")
        self.assert_refused(self.run_audit(), "invalid_manifest")
        for tag in PERMISSION_TAGS:
            for attributes in ("", 'name="android.permission.INTERNET"', 'android:name="${permission}"'):
                with self.subTest(tag=tag, attributes=attributes):
                    self.write_manifest(MAIN, manifest(f"<{tag} {attributes} />"))
                    self.assert_refused(self.run_audit(), "invalid_permission")

    def test_missing_declared_project_root_refuses(self):
        self.assert_refused(self.run_audit(self.workspace / "absent-project"), "missing_project_root")

    def test_declared_project_root_must_be_a_directory(self):
        path = self.workspace / "not-a-directory"
        path.write_text("fixture", encoding="utf-8")
        self.assert_refused(self.run_audit(path), "missing_project_root")

    def test_missing_android_directory_refuses(self):
        root = self.workspace / "missing-android"
        root.mkdir()
        self.assert_refused(self.run_audit(root), "missing_android_root")

    def test_no_source_manifests_refuses_even_when_generated_manifest_exists(self):
        root = self.workspace / "empty-project"
        self.write_manifest("app/build/generated/AndroidManifest.xml", manifest(), root)
        self.assert_refused(self.run_audit(root), "missing_manifests")

    def test_generated_manifests_remain_excluded(self):
        for relative in (
            "app/build/generated/AndroidManifest.xml",
            ".gradle/cache/AndroidManifest.xml",
        ):
            self.write_manifest(relative, "<not-well-formed")
        self.assert_accepted(self.run_audit())

    def test_default_root_is_script_repository_not_working_directory(self):
        copied_dir = self.root / "tools" / "privacy-audit"
        copied_dir.mkdir(parents=True)
        for name in ("run.sh", "manifest_permissions.py"):
            shutil.copy2(AUDIT_DIR / name, copied_dir / name)
        self.assert_accepted(self.run_audit(arguments=[], script=copied_dir / "run.sh"))

    def test_bad_root_option_refuses_without_falling_back_to_repository(self):
        for arguments in (
            ["--project-root"], ["--project-root", ""],
            ["--wrong-option", str(self.root)],
            ["--project-root", str(self.root), "extra"],
        ):
            with self.subTest(arguments=arguments):
                self.assert_refused(self.run_audit(arguments=arguments), "invalid_arguments")

    def test_original_httpclient_check_remains_active(self):
        for filename in ("One.kt", "Two.kt"):
            self.write_manifest(f"app/src/main/kotlin/{filename}", "val client = HttpClient()\n")
        result = self.run_audit()
        self.assert_http_refused(result)
        self.assertIn("Manifest permissions respect source flavor policy", result.stdout)
        self.assertEqual(result.stderr.count("class=forbidden_httpclient]"), 2)

    def test_http_actual_five_constructor_owners_are_accepted(self):
        for relative in HTTP_OWNERS:
            self.write_manifest(relative, (PROJECT_ROOT / "android" / relative).read_text(encoding="utf-8"))
        self.assert_accepted(self.run_audit())
        relative = "app/src/main/kotlin/Leak.kt"
        path = self.write_manifest(relative, "val client = HttpClient()\n")
        try:
            self.assert_http_refused(self.run_audit())
        finally:
            path.unlink()

    def test_http_duplicate_sites_and_downloadmodule_are_refused(self):
        for relative in ("app/src/main/kotlin/One.kt", "app/src/main/kotlin/DownloadModule.kt"):
            for separator in ("; ", "\n"):
                with self.subTest(path=relative, separator=separator):
                    path = self.write_manifest(relative, "val first = HttpClient()" + separator + "val second = HttpClient()\n")
                    try:
                        self.assert_http_refused(self.run_audit())
                    finally:
                        path.unlink()

    def test_http_every_reviewed_owner_allows_at_most_one_site(self):
        for relative in HTTP_OWNERS:
            with self.subTest(path=relative):
                path = self.write_manifest(relative, "val first = HttpClient()\nval second = HttpClient()\n")
                try:
                    result = self.run_audit()
                    self.assert_http_refused(result, "duplicate_httpclient")
                    self.assertIn(f"at {relative}:2", result.stderr)
                finally:
                    path.unlink()

    def test_http_owner_exceptions_are_exact_paths_not_names_or_modules(self):
        relative_paths = [str(Path(owner).with_name("Leak.kt")) for owner in HTTP_OWNERS]
        relative_paths += [
            "app/src/main/kotlin/HttpClientProviderExtra.kt",
            "traffic/provider-sa-extra/src/main/kotlin/Leak.kt",
            "offline/pack-downloader/src/main/kotlin/au/com/ausroads/offline/download/di/DownloadModule.kt",
        ]
        for relative in relative_paths:
            with self.subTest(path=relative):
                path = self.write_manifest(relative, "val client = HttpClient()\n")
                try:
                    self.assert_http_refused(self.run_audit())
                finally:
                    path.unlink()

    def test_http_whitespace_qualified_and_comment_separated_calls_are_refused(self):
        relative = "app/src/main/kotlin/Calls.kt"
        for expression in ("HttpClient ()", "HttpClient\n()", "io.ktor.client.HttpClient()",
                           "HttpClient /* gap */ ()", "HttpClient // gap\n ()"):
            with self.subTest(expression=expression):
                self.write_manifest(relative, "val client = " + expression + "\n")
                result = self.run_audit()
                self.assert_http_refused(result)
                self.assertIn(f"at {relative}:1", result.stderr)

    def test_http_declarations_suffixes_literals_and_nested_comments_are_accepted(self):
        for filename in ("One.kt", "Two.kt"):
            self.write_manifest(f"app/src/main/kotlin/{filename}", (
                "fun createHttpClient() = factory()\nfun provideHttpClient() = createHttpClient()\n"
                "val shared = HttpClientProvider.createHttpClient()\n"
                'val label = "HttpClient()"\nval raw = """HttpClient()"""\n'
                "/* outer /* inner */ HttpClient() */\n// HttpClient()\n"
            ))
        self.assert_accepted(self.run_audit())

    def test_http_declaration_forms_do_not_mask_active_body_calls(self):
        relative = "app/src/main/kotlin/Declarations.kt"
        for declaration in ("fun <T> HttpClient(value: T) = value",
                            "fun Any.HttpClient() = Unit",
                            "fun ((Int) -> Unit).HttpClient() = Unit"):
            with self.subTest(declaration=declaration):
                self.write_manifest(relative, declaration + "\n")
                self.assert_accepted(self.run_audit())
                self.write_manifest(relative, declaration + "\nval active = io.ktor.client.HttpClient()\n")
                result = self.run_audit()
                self.assert_http_refused(result)
                self.assertIn(f"at {relative}:2", result.stderr)
        for expression in ("fun <T> create(value: T) = HttpClient()",
                           "fun Any.create() { val client = HttpClient() }",
                           "fun create(client: Any = HttpClient()) = client"):
            with self.subTest(expression=expression):
                self.write_manifest(relative, expression + "\n")
                self.assert_http_refused(self.run_audit())

    def test_http_interpolation_is_active_but_literal_and_comment_twins_are_not(self):
        relative = "app/src/main/kotlin/Template.kt"
        for opening, closing in (('"', '"'), ('"""', '"""')):
            for expression in ("HttpClient()", "io.ktor.client.HttpClient /* gap */ ()"):
                with self.subTest(opening=opening, expression=expression):
                    self.write_manifest(relative, "val label = " + opening + "${" + expression + "}" + closing + "\n")
                    self.assert_http_refused(self.run_audit())
                    self.write_manifest(relative, "val label = " + opening + expression + closing + "\n")
                    self.assert_accepted(self.run_audit())
                    self.write_manifest(relative, "val label = " + opening + "${/* " + expression + " */ 1}" + closing + "\n")
                    self.assert_accepted(self.run_audit())

    def test_http_source_exclusions_are_exact_directories(self):
        root = self.workspace / "source-build-test-project"
        self.make_project(root)
        for directory in ("app/build/generated", ".gradle/cache", "app/src/test",
                          "app/src/androidTest", "app/src/testFixtures"):
            self.write_manifest(f"{directory}/Leak.kt", "private-parser-payload = HttpClient()\n", root)
        self.assert_accepted(self.run_audit(root))
        for relative in ("build-logic/convention/Leak.kt",
                         "feature/latest/src/main/kotlin/example/building/Leak.kt"):
            with self.subTest(path=relative):
                path = self.write_manifest(relative, "val client = HttpClient()\n", root)
                try:
                    self.assert_http_refused(self.run_audit(root))
                finally:
                    path.unlink()

    def test_http_utf8_and_unterminated_source_fail_closed(self):
        relative = "app/src/main/kotlin/Bad.kt"
        path = self.write_manifest(relative, "")
        path.write_bytes(b"\xffprivate-parser-payload")
        self.assert_http_refused(self.run_audit(), "unreadable_input")
        for source in ('val label = "private-parser-payload\n', "/* private-parser-payload\n"):
            with self.subTest(source=source):
                path.write_text(source, encoding="utf-8")
                self.assert_http_refused(self.run_audit(), "source_parser_failed")

    def test_http_walk_read_and_parser_faults_name_the_actual_seam(self):
        relative = "app/src/main/kotlin/Healthy.kt"
        self.write_manifest(relative, "val healthy = 1\n")
        hooks = self.workspace / "http-hooks"
        hooks.mkdir()
        environment = dict(os.environ, PYTHONPATH=str(hooks), PYTHONNOUSERSITE="1")
        faults = (
            ("unreadable_input", "import os\ndef broken(*args, **kwargs):\n    raise OSError('private-parser-payload')\nos.walk = broken\n"),
            ("unreadable_input", "from pathlib import Path\ndef broken(*args, **kwargs):\n    raise OSError('private-parser-payload')\nPath.read_text = broken\n"),
            ("source_parser_failed", "import re\noriginal = re.compile\nclass Broken:\n    def match(self, *args):\n        raise RuntimeError('private-parser-payload')\ndef compile(pattern, *args, **kwargs):\n    return Broken() if isinstance(pattern, str) and pattern.startswith('[A-Za-z_$]') else original(pattern, *args, **kwargs)\nre.compile = compile\n"),
        )
        for classification, body in faults:
            with self.subTest(classification=classification, body=body):
                hook = "import sys\nif '--httpclient-ownership' in sys.argv:\n" + "".join("    " + line + "\n" for line in body.splitlines())
                (hooks / "sitecustomize.py").write_text(hook, encoding="utf-8")
                self.assert_http_refused(self.run_audit(env=environment), classification)

    def test_original_analytics_check_remains_active(self):
        relative = "app/src/main/kotlin/Sdk.kt"
        self.write_manifest(relative, "import com.google.firebase.analytics.FirebaseAnalytics\n")
        self.assert_sdk_refused(self.run_audit(), "firebase", relative)

    def test_ordinary_adjust_documentation_and_identifiers_are_accepted(self):
        self.write_manifest("app/src/main/kotlin/Adjustments.kt", (
            "/** lets the user adjust name + colour, and persists only on Save. */\n"
            'val adjust = "adjust"\nfun adjustName(name: String) = name\n'
            '// adjust the amplitude before saving the pin\n'
        ))
        self.assert_accepted(self.run_audit())

    def test_bare_vendor_labels_and_nearby_namespaces_are_accepted(self):
        labels = "\n".join(f'val {sdk.replace("-", "_")} = "{sdk}"' for sdk, _, _ in SDK_CASES)
        self.write_manifest("app/src/main/kotlin/Labels.kt", labels + (
            '\nval widget = "com.adjustable.Widget"\n'
            'val local = "example.com.adjust.sdk.Adjust"\n'
        ))
        self.write_manifest("app/build.gradle.kts", (
            'dependencies { implementation("com.google.android.gms:play-services-location:21.3.0") }\n'
        ))
        self.assert_accepted(self.run_audit())

    def test_sdk_imports_are_refused_for_all_vendors(self):
        for sdk, qualified_class, _ in SDK_CASES:
            for extension in ("kt", "java"):
                with self.subTest(sdk=sdk, extension=extension):
                    relative = f"app/src/main/java/example/Sdk.{extension}"
                    path = self.write_manifest(relative, f"import {qualified_class};\n")
                    result = self.run_audit()
                    path.unlink()
                    self.assert_sdk_refused(result, sdk, relative)

    def test_sdk_namespace_uses_are_refused_for_all_vendors(self):
        relative = "app/src/main/kotlin/Sdk.kt"
        for sdk, qualified_class, _ in SDK_CASES:
            with self.subTest(sdk=sdk):
                self.write_manifest(relative, f"val sdkClass = {qualified_class}::class\n")
                self.assert_sdk_refused(self.run_audit(), sdk, relative)

    def test_sdk_gradle_coordinates_are_refused_for_all_vendors(self):
        for sdk, _, coordinate in SDK_CASES:
            for relative in ("app/build.gradle", "app/build.gradle.kts"):
                with self.subTest(sdk=sdk, path=relative):
                    path = self.write_manifest(relative, f'dependencies {{ implementation("{coordinate}:1.0") }}\n')
                    result = self.run_audit()
                    path.unlink()
                    self.assert_sdk_refused(result, sdk, relative)

    def test_sdk_toml_module_coordinates_are_refused_for_all_vendors(self):
        relative = "gradle/libs.versions.toml"
        for sdk, _, coordinate in SDK_CASES:
            with self.subTest(sdk=sdk):
                self.write_manifest(relative, f'[libraries]\nsdk = {{ module = "{coordinate}", version = "1.0" }}\n')
                self.assert_sdk_refused(self.run_audit(), sdk, relative)

    def test_sdk_group_name_coordinates_are_refused_for_all_vendors(self):
        for sdk, _, coordinate in SDK_CASES:
            group, name = coordinate.split(":")
            for relative in ("app/build.gradle", "app/build.gradle.kts", "gradle/libs.versions.toml"):
                for reverse in (False, True):
                    with self.subTest(sdk=sdk, path=relative, reverse=reverse):
                        separator = ":" if relative.endswith(".gradle") else "="
                        fields = [f'group {separator} "{group}"', f'name {separator} "{name}"']
                        if reverse:
                            fields.reverse()
                        declaration = (", " if relative.endswith(".toml") else ",\n").join(fields)
                        content = (
                            f'[libraries]\nsdk = {{ {declaration}, version = "1.0" }}\n'
                            if relative.endswith(".toml") else
                            f'dependencies {{ implementation({declaration}, version {separator} "1.0") }}\n'
                        )
                        path = self.write_manifest(relative, content)
                        result = self.run_audit()
                        path.unlink()
                        self.assert_sdk_refused(result, sdk, relative)

    def test_google_split_coordinate_field_permutations_are_refused(self):
        for dependency in ("play-services-analytics", "play-services-measurement-api"):
            for relative in ("app/build.gradle", "app/build.gradle.kts", "gradle/libs.versions.toml"):
                separator = ":" if relative.endswith(".gradle") else "="
                fields = {
                    "group": f'group {separator} "com.google.android.gms"',
                    "name": f'name {separator} "{dependency}"',
                    "version": f'version {separator} "18.0.0"',
                }
                for order in permutations(fields):
                    with self.subTest(dependency=dependency, path=relative, order=order):
                        declaration = ", ".join(fields[key] for key in order)
                        content = (
                            f'[libraries]\nsdk = {{ {declaration} }}\n'
                            if relative.endswith(".toml") else
                            f'dependencies {{\n    implementation({declaration})\n}}\n'
                        )
                        path = self.write_manifest(relative, content)
                        try:
                            result = self.run_audit()
                        finally:
                            path.unlink()
                        self.assert_sdk_refused(result, "google-analytics", relative)
                        self.assertIn(f"{relative}:2", result.stdout + result.stderr)

    def test_google_split_coordinate_spacing_comments_and_version_refs_are_refused(self):
        for dependency in ("play-services-analytics", "play-services-measurement-api"):
            for relative in ("app/build.gradle", "app/build.gradle.kts", "gradle/libs.versions.toml"):
                toml = relative.endswith(".toml")
                separator = ":" if relative.endswith(".gradle") else "="
                quote = '"' if relative.endswith(".kts") else "'"
                version = (
                    'version . ref\t= "sdk"' if toml else
                    'version : sdkVersion' if relative.endswith(".gradle") else
                    'version = libs.versions.sdk.get()'
                )
                fields = {
                    "group": f"group\t{separator}\t{quote}com.google.android.gms{quote}",
                    "name": f"name\t{separator}\t{quote}{dependency}{quote}",
                    "version": version,
                }
                for order in permutations(fields):
                    with self.subTest(dependency=dependency, path=relative, order=order):
                        joiner = ",\t" if toml else ", /* ignored } ), */\n        // ignored field gap\n        "
                        declaration = joiner.join(fields[key] for key in order)
                        content = (
                            f'# coordinate follows\n[libraries]\nsdk = {{ {declaration} }} # ignored }} ),\n'
                            if toml else
                            f'// coordinate follows\ndependencies {{\n    implementation(\n        {declaration}\n    )\n}}\n'
                        )
                        first_coordinate_key = next(key for key in order if key != "version")
                        line = content.count("\n", 0, content.index(fields[first_coordinate_key])) + 1
                        path = self.write_manifest(relative, content)
                        try:
                            result = self.run_audit()
                        finally:
                            path.unlink()
                        self.assert_sdk_refused(result, "google-analytics", relative)
                        self.assertIn(f"{relative}:{line}", result.stdout + result.stderr)
                        self.assertNotIn("ignored field gap", result.stdout + result.stderr)

    def test_google_location_and_unrelated_coordinate_records_remain_allowed(self):
        for dependency in ("play-services-analytics", "play-services-measurement-api"):
            for relative in ("app/build.gradle", "app/build.gradle.kts", "gradle/libs.versions.toml"):
                toml = relative.endswith(".toml")
                separator = ":" if relative.endswith(".gradle") else "="
                version = 'version.ref = "gps"' if toml else f'version {separator} "21.3.0"'
                location_fields = {
                    "group": f'group {separator} "com.google.android.gms"',
                    "name": f'name {separator} "play-services-location"',
                    "version": version,
                }
                unrelated_fields = {
                    "group": f'group {separator} "example.test"',
                    "name": f'name {separator} "{dependency}"',
                    "version": version,
                }
                for order in permutations(location_fields):
                    location = ", ".join(location_fields[key] for key in order)
                    unrelated = ", ".join(unrelated_fields[key] for key in order)
                    records = (
                        [f'location = {{ {location} }}', f'other = {{ {unrelated} }}']
                        if toml else
                        [f'implementation({location})', f'implementation({unrelated})']
                    )
                    for reverse_records in (False, True):
                        with self.subTest(dependency=dependency, path=relative, order=order, reverse=reverse_records):
                            body = "\n".join(reversed(records) if reverse_records else records)
                            content = f'[libraries]\n{body}\n' if toml else f'dependencies {{\n{body}\n}}\n'
                            path = self.write_manifest(relative, content)
                            try:
                                result = self.run_audit()
                            finally:
                                path.unlink()
                            self.assert_accepted(result)

    def test_google_optional_fields_and_nested_values_are_refused(self):
        for relative, separator in (("app/build.gradle", ":"), ("app/build.gradle.kts", "=")):
            for dependency in ("play-services-analytics", "play-services-measurement-api"):
                for reverse in (False, True):
                    for key, value in (("ext", '"aar"'), ("classifier", "null"),
                                       ("configuration", 'f("), }", nested("x", "y"))')):
                        with self.subTest(path=relative, dependency=dependency, reverse=reverse, key=key):
                            fields = [f'group {separator} "com.google.android.gms"',
                                      f'name {separator} "{dependency}"']
                            if reverse:
                                fields.reverse()
                            fields.insert(1, f"{key} {separator} {value}")
                            path = self.write_manifest(relative, (
                                'dependencies { add("implementation", create(' + ', '.join(fields) + ')) }\n'
                            ))
                            try:
                                self.assert_sdk_refused(self.run_audit(), "google-analytics", relative)
                            finally:
                                path.unlink()

    def test_google_nested_owners_and_field_looking_strings_are_accepted(self):
        for relative, separator in (("app/build.gradle", ":"), ("app/build.gradle.kts", "=")):
            declarations = (
                f'create(group {separator} "com.google.android.gms", ext {separator} helper(name {separator} "play-services-analytics"))',
                f'create(name {separator} "play-services-analytics", ext {separator} helper(group {separator} "com.google.android.gms"))',
                f'create(group {separator} "com.google.android.gms", name {separator} "play-services-location"); '
                f'create(name {separator} "play-services-analytics", group {separator} "example.test")',
                'println("group = \'com.google.android.gms\', name = \'play-services-analytics\'")',
            )
            for declaration in declarations:
                with self.subTest(path=relative, declaration=declaration):
                    path = self.write_manifest(relative, f"dependencies {{ {declaration} }}\n")
                    try:
                        self.assert_accepted(self.run_audit())
                    finally:
                        path.unlink()

    def test_google_parenthesis_free_records_are_isolated(self):
        relative = "app/build.gradle"
        self.write_manifest(relative, (
            'dependencies {\n implementation group: "com.google.android.gms",\n'
            ' ext: "aar", name: "play-services-analytics"\n}\n'
        ))
        self.assert_sdk_refused(self.run_audit(), "google-analytics", relative)
        self.write_manifest(relative, (
            'dependencies {\n implementation group: "com.google.android.gms", name: "play-services-location"\n'
            ' implementation group: "example.test", name: "play-services-analytics"\n}\n'
        ))
        self.assert_accepted(self.run_audit())

    def test_google_parenthesis_free_field_value_newline_is_refused(self):
        relative = "app/build.gradle"
        for first, second in (("group", "name"), ("name", "group")):
            fields = {"group": "com.google.android.gms", "name": "play-services-analytics"}
            with self.subTest(first=first):
                self.write_manifest(relative, (
                    f'dependencies {{\n implementation {first}:\n "{fields[first]}", '
                    f'ext: "aar", {second}: "{fields[second]}"\n}}\n'
                ))
                result = self.run_audit()
                self.assert_sdk_refused(result, "google-analytics", relative)
                self.assertIn(f"at {relative}:2\n", result.stderr)

    def test_kotlin_coordinates_require_whole_static_direct_arguments(self):
        group, name = "com.google.android.gms", "play-services-analytics"
        for relative in ("app/build.gradle.kts", "app/src/main/kotlin/Declarations.kt"):
            expressions = (
                (f'create(group = "{group}".substringBefore("."), name = "{name}")', False),
                (f'create(group = "{group}", name = "{name}".substringBefore("-"))', False),
                (f'create(group = ("{group}" + suffix), name = "{name}")', False),
                (f'create(group = "${{prefix}}{group}", name = "{name}")', False),
                (f'create(group = "{group}", name = helper(name = "{name}"))', False),
                (f'create(group = (("{group}")), version = nested("x", "y"), name = ("{name}"))', True),
                (f'create(group = "{group}", name = "play-services-location")', False),
                (f'create(group = "{group}", name = "{name}")', True),
                (f'mapOf("group" to "{group}", "name" to "{name}")', True),
                (f'mapOf("group" to "{group}", "name" to "{name}", "name" to "play-services-location")', False),
                (f'mapOf("group" to "{group}", "name" to "{name}", "group" to compute())', False),
                (f'object {{ val group = "{group}"; val name = "{name}" }}', False),
                (f'fun declare(group: String = "{group}", name: String = "{name}") {{}}', False),
            )
            for expression, forbidden in expressions:
                with self.subTest(path=relative, expression=expression):
                    path = self.write_manifest(relative, expression + "\n")
                    try:
                        result = self.run_audit()
                        if forbidden:
                            self.assert_sdk_refused(result, "google-analytics", relative)
                            self.assertIn(f"at {relative}:1\n", result.stderr)
                        else:
                            self.assert_accepted(result)
                    finally:
                        path.unlink()

    def test_annotations_are_not_dependency_named_arguments(self):
        group, name = "com.google.android.gms", "play-services-analytics"
        for relative in ("app/src/main/java/Metadata.java", "app/src/main/kotlin/Metadata.kt"):
            for annotation in ("Metadata", "example.Metadata", "field:Metadata", "get:example.Metadata"):
                if relative.endswith(".java") and ":" in annotation:
                    continue
                with self.subTest(path=relative, annotation=annotation):
                    path = self.write_manifest(relative, f'@{annotation}(group = "{group}", name = "{name}")\n')
                    try:
                        self.assert_accepted(self.run_audit())
                        # Ignoring member pairs must not hide explicit SDKs.
                        path.write_text(f'@{annotation}(value = "com.adjust.sdk.Adjust")\n', encoding="utf-8")
                        self.assert_sdk_refused(self.run_audit(), "adjust", relative)
                    finally:
                        path.unlink()

    def test_google_mixed_split_and_direct_reference_reports_earliest_line(self):
        relative = "app/build.gradle.kts"
        split = 'implementation(group = "com.google.android.gms", name = "play-services-analytics")'
        direct = 'implementation("com.google.android.gms:play-services-analytics:18.0.0")'
        for first, second in ((split, direct), (direct, split)):
            with self.subTest(first=first):
                self.write_manifest(relative, f"dependencies {{\n {first}\n {second}\n}}\n")
                result = self.run_audit()
                self.assert_sdk_refused(result, "google-analytics", relative)
                self.assertIn(f"at {relative}:2\n", result.stderr)
                self.assertEqual(result.stderr.count("forbidden SDK reference (google-analytics)"), 1)

    def test_google_quoted_keys_and_raw_literal_coordinates_are_refused(self):
        declarations = (
            ("app/build.gradle", "implementation(['group': '{group}', 'name': '{name}', 'version': '18.0.0'])"),
            ("app/build.gradle.kts", 'implementation(group = """{group}""", name = """{name}""", version = "18.0.0")'),
        )
        for relative, declaration in declarations:
            for name in ("play-services-analytics", "play-services-measurement-api", "play-services-location"):
                with self.subTest(path=relative, name=name):
                    body = declaration.format(group="com.google.android.gms", name=name)
                    path = self.write_manifest(relative, f"dependencies {{ {body} }}\n")
                    try:
                        result = self.run_audit()
                        if name == "play-services-location":
                            self.assert_accepted(result)
                        else:
                            self.assert_sdk_refused(result, "google-analytics", relative)
                    finally:
                        path.unlink()

    def test_nested_kotlin_comments_preserve_active_references_and_strings(self):
        relative = "app/src/main/kotlin/Documentation.kt"
        comment = '/* outer\n /* inner */\n import com.adjust.sdk.Adjust\n*/\n'
        self.write_manifest(relative, comment)
        self.assert_accepted(self.run_audit())
        self.write_manifest(relative, comment + 'import com.adjust.sdk.Adjust\n')
        result = self.run_audit()
        self.assert_sdk_refused(result, "adjust", relative)
        self.assertIn(f"at {relative}:5\n", result.stderr)
        self.write_manifest(relative, 'val documentation = "/* outer /* inner */ */"\nimport com.adjust.sdk.Adjust\n')
        result = self.run_audit()
        self.assert_sdk_refused(result, "adjust", relative)
        self.assertIn(f"at {relative}:2\n", result.stderr)

    def test_nested_comment_tokenization_resumes_at_actual_outer_end(self):
        relative = "app/src/main/kotlin/Documentation.kt"
        self.write_manifest(relative, (
            '/* /* nested */ The quote character is ". */\n'
            '// import com.adjust.sdk.Adjust\nval label = "example"\n'
        ))
        self.assert_accepted(self.run_audit())
        self.write_manifest(relative, (
            '/* /* nested */ The quote character is ". */\n'
            'import com.adjust.sdk.Adjust\nval label = "example"\n'
        ))
        result = self.run_audit()
        self.assert_sdk_refused(result, "adjust", relative)
        self.assertIn(f"at {relative}:2\n", result.stderr)

    def test_non_kotlin_block_comments_keep_first_closer_semantics(self):
        for relative in ("app/src/main/java/Documentation.java", "app/build.gradle"):
            with self.subTest(path=relative):
                path = self.write_manifest(relative, '/* outer /* inner */\nimport com.adjust.sdk.Adjust;\n')
                try:
                    result = self.run_audit()
                    self.assert_sdk_refused(result, "adjust", relative)
                    self.assertIn(f"at {relative}:2\n", result.stderr)
                finally:
                    path.unlink()

    def test_groovy_dollar_slashy_url_cannot_hide_a_later_sdk_coordinate(self):
        relative = "app/build.gradle"
        forbidden = 'dependencies { implementation("com.adjust.sdk:adjust-android:1.0") }'
        for literal, gap in (('$/https://example.test/$', '; '),
                             ('"https://example.test"', '; '),
                             ('$/https://example.test/$', '\n')):
            with self.subTest(literal=literal, gap=gap):
                self.write_manifest(relative, f'def homepage = {literal}{gap}{forbidden}\n')
                result = self.run_audit()
                self.assert_sdk_refused(result, "adjust", relative)
                self.assertIn(f"at {relative}:{1 if gap == '; ' else 2}\n", result.stderr)
        self.write_manifest(relative, 'def homepage = $/https://example.test/$\n')
        self.assert_accepted(self.run_audit())

    def test_nested_kotlin_string_template_cannot_hide_a_later_sdk_namespace(self):
        relative = "app/src/main/kotlin/Endpoint.kt"
        forbidden = 'val sdk = com.adjust.sdk.Adjust::class'
        template = '"${java.net.URI("https://example.test")}"'
        for literal, gap in ((template, '; '), ('"https://example.test"', '; '), (template, '\n')):
            with self.subTest(literal=literal, gap=gap):
                self.write_manifest(relative, f'val endpoint = {literal}{gap}{forbidden}\n')
                result = self.run_audit()
                self.assert_sdk_refused(result, "adjust", relative)
                self.assertIn(f"at {relative}:{1 if gap == '; ' else 2}\n", result.stderr)
        self.write_manifest(relative, f'val endpoint = {template}\n')
        self.assert_accepted(self.run_audit())

    def test_interpolation_comments_are_inactive_without_hiding_following_references(self):
        relative = "app/src/main/kotlin/Endpoint.kt"
        comments = (
            '/* com.adjust.sdk.Adjust */',
            '/* outer /* inner */ com.adjust.sdk.Adjust */',
            '// com.adjust.sdk.Adjust\n',
        )
        for comment in comments:
            with self.subTest(comment=comment):
                body = 'val endpoint = "${ ' + comment + ' java.net.URI("https://example.test")}"\n'
                self.write_manifest(relative, body)
                self.assert_accepted(self.run_audit())
                self.write_manifest(relative, body + 'val sdk = com.adjust.sdk.Adjust::class\n')
                result = self.run_audit()
                self.assert_sdk_refused(result, "adjust", relative)
                self.assertIn(f"at {relative}:{body.count(chr(10)) + 1}\n", result.stderr)

    def test_shared_literal_boundaries_preserve_reflection_escapes_and_owners(self):
        cases = (
            ("app/src/main/kotlin/Endpoint.kt", 'val label = "${wrap("${java.net.URI("https://example.test")}")}"; Class.forName("com.adjust.sdk.Adjust")\n', "adjust"),
            ("app/build.gradle", 'def label = $/https://example.test $/$$ literal/$; implementation("com.adjust.sdk:adjust-android:1.0")\n', "adjust"),
            ("app/build.gradle", 'def label = $/https://${new URI("https://example.test")}/$; implementation("com.adjust.sdk:adjust-android:1.0")\n', "adjust"),
            ("app/build.gradle", 'dependencies { implementation(group: $/com.google.android.gms/$, name: $/play-services-analytics/$) }\n', "google-analytics"),
            ("app/build.gradle", 'def label = $/group: "com.google.android.gms", name: "play-services-analytics"/$\n', None),
            ("app/build.gradle.kts", 'dependencies { create(group = "com.google.android.gms", ext = "${f(name = "play-services-analytics")}", name = "play-services-location") }\n', None),
            ("app/src/main/kotlin/Endpoint.kt", 'val ratio = (a / b) / c; val endpoint = "${java.net.URI("https://example.test")}"\n', None),
        )
        for relative, body, sdk in cases:
            with self.subTest(path=relative, body=body):
                path = self.write_manifest(relative, body)
                try:
                    result = self.run_audit()
                    if sdk is None:
                        self.assert_accepted(result)
                    else:
                        self.assert_sdk_refused(result, sdk, relative)
                        self.assertIn(f"at {relative}:1\n", result.stderr)
                finally:
                    path.unlink()

    def test_active_lexical_stream_preserves_literal_and_coordinate_owners(self):
        group, name = "com.google.android.gms", "play-services-analytics"
        groovy = f"dependencies.add('implementation', [group: '{group}', name: '{name}'])"
        kotlin = f'create(group = "{group}", name = "{name}")'
        rows = []
        for relative, expression, opening, closing in (
            ("app/build.gradle", groovy, '"', '"'),
            ("app/build.gradle", groovy, '"""', '"""'),
            ("app/build.gradle", groovy, '$/', '/$'),
            ("app/build.gradle", groovy, '/', '/'),
            ("app/build.gradle.kts", kotlin, '"', '"'),
            ("app/build.gradle.kts", kotlin, '"""', '"""'),
        ):
            body = 'def label = ' if relative.endswith(".gradle") else 'val label = '
            body += opening + '${' + expression + '}' + closing + '\n'
            rows.extend(((relative, body, "google-analytics", 1),
                         (relative, body.replace(name, "play-services-location"), None, 1)))
        rows.extend((
            ("app/build.gradle", 'println "${"${' + groovy + '}"}"\n', "google-analytics", 1),
            ("app/build.gradle.kts", 'val label = "${wrap("${' + kotlin + '}")}"\n', "google-analytics", 1),
            ("app/build.gradle", 'println "${/* ' + groovy + ' */ 1}"\n', None, 1),
            ("app/build.gradle", 'def text = /group: "' + group + '", name: "' + name + '"/\n', None, 1),
            ("app/build.gradle", 'println "${[group: \'' + group + '\']}; ${[name: \'' + name + '\']}"\n', None, 1),
            ("app/build.gradle", 'dependencies { create(group: "' + group + '", ext: "${f(name: \'' + name + '\')}", name: "play-services-location") }\n', None, 1),
            ("app/build.gradle", 'dependencies { create(group: "' + group + '", ext: "${f(name: \'' + name + '\')}", name: "' + name + '") }\n', "google-analytics", 1),
            ("app/build.gradle", 'println """${\n dependencies.add(\'implementation\', [\n group: \'' + group + '\',\n name: \'' + name + '\'])\n}"""\n', "google-analytics", 3),
        ))
        for relative, body, sdk, line in rows:
            with self.subTest(path=relative, body=body):
                path = self.write_manifest(relative, body)
                try:
                    result = self.run_audit()
                    if sdk is None:
                        self.assert_accepted(result)
                    else:
                        self.assert_sdk_refused(result, sdk, relative)
                        self.assertIn(f"at {relative}:{line}\n", result.stderr)
                finally:
                    path.unlink()

    def test_groovy_slashy_context_does_not_swallow_division(self):
        relative = "app/build.gradle"
        for name in ("play-services-analytics", "play-services-location"):
            self.write_manifest(relative, 'dependencies { implementation(group: /com.google.android.gms/, name: /' + name + '/) }\n')
            result = self.run_audit()
            if name == "play-services-location":
                self.assert_accepted(result)
            else:
                self.assert_sdk_refused(result, "google-analytics", relative)
                self.assertIn(f"at {relative}:1\n", result.stderr)
        for expression in ('a / b / c', '12 / 3 / 2', '1.5 / 0.5', 'x++ / 2',
                           'x-- / 2', '(a + b) / 2', 'values[0] / 2',
                           '"label" / 2', '"${f(\'x\')}" / 2'):
            with self.subTest(expression=expression):
                self.write_manifest(relative, 'def ratio = ' + expression + '\n')
                self.assert_accepted(self.run_audit())
        for body in ('def quote = /"/\n', 'def f() { return /"/ }\n',
                     'def value = 1\n/"/\n', 'value++; def quote = /"/\n'):
            with self.subTest(body=body):
                self.write_manifest(relative, body)
                self.assert_accepted(self.run_audit())
        self.write_manifest(relative, 'def text = /private-parser-payload\n')
        self.assert_sdk_error(self.run_audit(), "source_parser_failed")

    def test_recognized_coordinate_fields_are_atomic_across_comment_gaps(self):
        group, name = 'com.google.android.gms', 'play-services-analytics'
        rows = (
            ('group-gap', f'dependencies {{\n implementation group /*\n*/ : "{group}", name: "{name}"\n}}\n', True),
            ('name-gap', f'dependencies {{\n implementation group: "{group}", name /*\n*/ : "{name}"\n}}\n', True),
            ('quoted-reverse', f"dependencies {{\n implementation 'name' /*\n*/ : '{name}', 'group': '{group}'\n}}\n", True),
            ('same-line-comment', f'dependencies {{\n implementation group /* doc */ : "{group}", name: "{name}"\n}}\n', True),
            ('colon-before-comment', f'dependencies {{\n implementation group: /*\n*/ "{group}", name: "{name}"\n}}\n', True),
            ('parenthesized-comment', f'dependencies {{\n implementation(group /*\n*/ : "{group}", name: "{name}")\n}}\n', True),
            ('escaped-newline', 'dependencies {\n implementation group \\\n : "' + group + '", name: "' + name + '"\n}\n', True),
            ('gps-twin', f'dependencies {{\n implementation group /*\n*/ : "{group}", name: "play-services-location"\n}}\n', False),
            ('real-statement-boundary', f'dependencies {{\n implementation group: "{group}", name: "play-services-location"\n /* documentation\n */\n implementation group: "example.test", name: "{name}"\n}}\n', False),
        )
        for label, body, forbidden in rows:
            with self.subTest(control=label):
                self.write_manifest('app/build.gradle', body)
                result = self.run_audit()
                if forbidden:
                    self.assert_sdk_refused(result, 'google-analytics', 'app/build.gradle')
                    self.assertIn('at app/build.gradle:2\n', result.stderr)
                else:
                    self.assert_accepted(result)

    def test_optional_named_fields_preserve_coordinates_across_lexical_trivia(self):
        relative = 'app/build.gradle'
        for first, second in (('group', 'name'), ('name', 'group')):
            for key, value in (('ext', '"aar"'), ('classifier', 'null'),
                               ('configuration', 'f("x", nested("y"))')):
                for quote in ('', "'"):
                    for gap in (' /*\n*/ ', ' /*\r\n*/ ', ' ' + chr(92) + '\n '):
                        for forbidden, dependency in ((True, 'play-services-analytics'),
                                                       (False, 'play-services-location')):
                            with self.subTest(first=first, key=key, quoted=bool(quote),
                                              gap=gap, forbidden=forbidden):
                                fields = {'group': 'com.google.android.gms', 'name': dependency}
                                self.write_manifest(relative, (
                                    f'dependencies {{\n implementation {first}: "{fields[first]}", '
                                    f'{quote}{key}{quote}{gap}: {value}, {second}: "{fields[second]}"\n}}\n'
                                ))
                                result = self.run_audit()
                                if forbidden:
                                    self.assert_sdk_refused(result, 'google-analytics', relative)
                                    self.assertIn(f'at {relative}:2\n', result.stderr)
                                else:
                                    self.assert_accepted(result)

    def test_trivia_spans_keep_actual_statements_and_nested_coordinate_owners(self):
        relative = 'app/build.gradle'
        group, analytics, gps = 'com.google.android.gms', 'play-services-analytics', 'play-services-location'
        for forbidden, second_group in ((False, 'example.test'), (True, group)):
            with self.subTest(second_group=second_group):
                self.write_manifest(relative, (
                    f'dependencies {{\n implementation group: "{group}", name: "{gps}" // '
                    + chr(92) + '\n' + f' implementation name: "{analytics}", group: "{second_group}"\n}}\n'
                ))
                result = self.run_audit()
                if forbidden:
                    self.assert_sdk_refused(result, 'google-analytics', relative)
                    self.assertIn(f'at {relative}:3\n', result.stderr)
                else:
                    self.assert_accepted(result)
        for body, forbidden, line in (
            (f'dependencies {{ create(group: "{group}", ext /*\n*/ : helper(name: "{analytics}"), name: "{gps}") }}\n', False, 1),
            (f'dependencies {{ create(name: "{analytics}", ext /*\n*/ : helper(group: "{group}")) }}\n', False, 1),
            (f'dependencies {{\n create(group: "example.test", ext /*\n*/ : helper(group: "{group}", name: "{analytics}"), name: "{gps}")\n}}\n', True, 3),
        ):
            with self.subTest(body=body):
                self.write_manifest(relative, body)
                result = self.run_audit()
                if forbidden:
                    self.assert_sdk_refused(result, 'google-analytics', relative)
                    self.assertIn(f'at {relative}:{line}\n', result.stderr)
                else:
                    self.assert_accepted(result)

    def test_unterminated_source_literals_and_comments_fail_closed_without_payload(self):
        relative = "app/src/main/kotlin/Endpoint.kt"
        for body in ('val label = "private-parser-payload\n', '/* private-parser-payload\n',
                     'val label = "${java.net.URI("https://example.test")\n'):
            with self.subTest(body=body):
                self.write_manifest(relative, body)
                self.assert_sdk_error(self.run_audit(), "source_parser_failed", errors=2)

    def test_nested_split_coordinate_reports_earliest_key_location(self):
        relative = "app/build.gradle.kts"
        self.write_manifest(relative, (
            'dependencies {\n implementation(\n group = "com.google.android.gms",\n'
            ' version = create(group = "com.google.android.gms", name = "play-services-analytics", version = "18.0.0").version,\n'
            ' name = "play-services-analytics")\n}\n'
        ))
        result = self.run_audit()
        self.assert_sdk_refused(result, "google-analytics", relative)
        self.assertIn(f"at {relative}:3\n", result.stderr)
        self.assertEqual(result.stderr.count("forbidden SDK reference (google-analytics)"), 1)

    def test_toml_rich_inline_field_permutations_are_refused(self):
        relative = "gradle/libs.versions.toml"
        for sdk, coordinate in TOML_COORDINATES:
            group, name = coordinate.split(":")
            fields = (
                f'"group"\t=\t"{group}"',
                f"'name' = '{name}'",
                'version = { require = "18.0.0", reject = ["18.0.1"] }',
            )
            for order in permutations(fields):
                with self.subTest(sdk=sdk, name=name, order=order):
                    self.write_manifest(relative, (
                        '# rich coordinate follows\n[libraries]\n'
                        + 'sdk = { ' + ',\t'.join(order) + ' } # ignored } ),\n'
                    ))
                    result = self.run_audit()
                    self.assert_sdk_refused(result, sdk, relative)
                    self.assertIn(f"at {relative}:3\n", result.stderr)

    def test_toml_library_tables_dotted_and_quoted_keys_are_refused(self):
        relative = "gradle/libs.versions.toml"
        for sdk, coordinate in TOML_COORDINATES:
            group, name = coordinate.split(":")
            declarations = (
                (f'[libraries.sdk]\ngroup = "{group}"\nversion.ref = "sdk"\nname = "{name}"\n', 4),
                (f'[ "libraries" . "sdk.with.dot" ]\n\'name\' = \'{name}\'\n'
                 f'"version" . "ref" = "sdk"\n\'group\' = \'{group}\' # ignored\n', 4),
                (f'libraries."sdk.with.dot".group = "{group}"\n'
                 'libraries."sdk.with.dot".version.ref = "sdk"\n'
                 f'libraries."sdk.with.dot".name = "{name}"\n', 3),
                (f'[libraries]\n"sdk.with.dot".group = "{group}"\n'
                 '"sdk.with.dot".version.ref = "sdk"\n'
                 f'"sdk.with.dot".name = "{name}"\n', 4),
                (f'[libraries.sdk]\nname = "{name}"\ngroup = "{group}"\n'
                 '[libraries.sdk.version]\nrequire = "18.0.0"\nreject = ["18.0.1"]\n', 3),
                ('[libraries.sdk.version]\nrequire = "18.0.0"\n[libraries.sdk]\n'
                 f'name = "{name}"\ngroup = "{group}"\n', 5),
                ('libraries = { "sdk.with.dot" = { '
                 f'group = "{group}", version = {{ require = "18.0.0" }}, name = "{name}"'
                 ' } }\n', 1),
            )
            for content, line in declarations:
                with self.subTest(sdk=sdk, name=name, content=content):
                    self.write_manifest(relative, content)
                    result = self.run_audit()
                    self.assert_sdk_refused(result, sdk, relative)
                    if sdk == "google-analytics":
                        self.assertIn(f"at {relative}:{line}\n", result.stderr)

    def test_toml_table_field_order_comments_and_spacing_are_refused(self):
        relative = "gradle/libs.versions.toml"
        for name in ("play-services-analytics", "play-services-measurement-api"):
            fields = {
                "group": '"group"\t= "com.google.android.gms" # coordinate group',
                "name": f"'name' = '{name}' # coordinate name",
                "version": 'version . ref\t= "sdk" # rich ref',
            }
            for order in permutations(fields):
                with self.subTest(name=name, order=order):
                    content = '# preceding comment\n[libraries.sdk]\n' + '\n'.join(fields[key] for key in order) + '\n'
                    self.write_manifest(relative, content)
                    result = self.run_audit()
                    self.assert_sdk_refused(result, "google-analytics", relative)
                    line = 3 + max(order.index("group"), order.index("name"))
                    self.assertIn(f"at {relative}:{line}\n", result.stderr)

    def test_toml_decoded_module_and_shorthand_coordinates_are_refused(self):
        relative = "gradle/libs.versions.toml"
        for sdk, coordinate in TOML_COORDINATES:
            escaped = ''.join(f'\\u{ord(char):04x}' for char in coordinate)
            declarations = (
                (f'[libraries]\nsdk = {{ "module" = "{escaped}", version = "1.0" }}\n', 2),
                (f'[libraries]\nsdk = "{escaped}:1.0"\n', 2),
                ('[libraries.sdk]\nmodule = """\\\n'
                 f'    {escaped}"""\nversion.ref = "sdk"\n', 3),
            )
            for content, line in declarations:
                with self.subTest(sdk=sdk, coordinate=coordinate, content=content):
                    self.write_manifest(relative, content)
                    result = self.run_audit()
                    self.assert_sdk_refused(result, sdk, relative)
                    self.assertIn(f"at {relative}:{line}\n", result.stderr)

    def test_toml_decoded_group_name_and_key_strings_are_refused(self):
        relative = "gradle/libs.versions.toml"
        for sdk, coordinate in TOML_COORDINATES:
            group, name = (''.join(f'\\u{ord(char):04x}' for char in part) for part in coordinate.split(":"))
            with self.subTest(sdk=sdk, coordinate=coordinate):
                self.write_manifest(relative, (
                    '["lib\\u0072aries"."quoted.alias"]\n'
                    f'"gr\\u006fup" = "{group}"\n'
                    '"version"."ref" = "sdk"\n'
                    f'"na\\u006de" = "{name}"\n'
                ))
                result = self.run_audit()
                self.assert_sdk_refused(result, sdk, relative)
                if sdk == "google-analytics":
                    self.assertIn(f"at {relative}:4\n", result.stderr)

    def test_toml_diagnostics_do_not_borrow_other_records_or_comments(self):
        relative = "gradle/libs.versions.toml"
        for name in ("play-services-analytics", "play-services-measurement-api"):
            with self.subTest(name=name):
                self.write_manifest(relative, (
                    f'# com.google.android.gms:{name}\n'
                    '[libraries.location]\ngroup = "com.google.android.gms"\nname = "play-services-location"\n'
                    '[libraries.unrelated]\n'
                    f'name = "{name}"\ngroup = "example.test"\n'
                    '[libraries.forbidden]\n'
                    f'name = "{name}"\n# rich version table follows\ngroup = "com.google.android.gms"\n'
                    '[libraries.forbidden.version]\nprefer = "18.0.0"\n'
                ))
                result = self.run_audit()
                self.assert_sdk_refused(result, "google-analytics", relative)
                self.assertIn(f"at {relative}:11\n", result.stderr)
                self.assertEqual(result.stderr.count("forbidden SDK reference (google-analytics)"), 1)

    def test_toml_rich_location_and_unrelated_records_are_accepted(self):
        relative = "gradle/libs.versions.toml"
        for name in ("play-services-analytics", "play-services-measurement-api"):
            literal = ''.join(f'\\u{ord(char):04x}' for char in f'com.google.android.gms:{name}')
            declarations = (
                ('[libraries]\nlocation = { group = "com.google.android.gms", '
                 'version = { require = "21.3.0" }, name = "play-services-location" }\n'
                 f'other = {{ name = "{name}", version = {{ require = "18.0.0" }}, group = "example.test" }}\n'),
                (f'[libraries.other]\nname = "{name}"\nversion.ref = "sdk"\ngroup = "example.test"\n'
                 '[libraries.location]\ngroup = "com.google.android.gms"\nversion.ref = "gps"\nname = "play-services-location"\n'),
                ('[libraries]\nlocation.group = "com.google.android.gms"\nlocation.version.ref = "gps"\n'
                 'location.name = "play-services-location"\nother.group = "example.test"\n'
                 f'other.name = "{name}"\n'),
                ('[libraries.group_only]\ngroup = "com.google.android.gms"\n'
                 f'[libraries.name_only]\nname = "{name}"\n'),
                ('[libraries.parent]\ngroup = "com.google.android.gms"\n'
                 f'[libraries.parent.child]\nname = "{name}"\n'),
                (f'libraries = {{ group = "com.google.android.gms", name = "{name}" }}\n'),
                (f'[metadata]\ngroup = "com.google.android.gms"\nname = "{name}"\n'),
                (f'[libraries]\nliteral = {{ module = \'{literal}\' }}\n'),
                ('\n'.join(f'# sdk = {{ module = "{coordinate}" }}' for _, coordinate in TOML_COORDINATES) + '\n'),
            )
            for content in declarations:
                with self.subTest(name=name, content=content):
                    self.write_manifest(relative, content)
                    self.assert_accepted(self.run_audit())

    def test_toml_malformed_input_is_refused_without_parser_payload(self):
        for relative in ("gradle/libs.versions.toml", "config/source.toml"):
            for content in (
                '[libraries]\nsdk = { group = "com.google.android.gms", version = { require = "private-parser-payload" }\n',
                '[libraries."private-parser-payload"]\nmodule = "example.test:library"\n[libraries."private-parser-payload"]\n',
                '[libraries]\n"private-parser-payload" = "unterminated\n',
            ):
                with self.subTest(path=relative, content=content):
                    path = self.write_manifest(relative, content)
                    try:
                        self.assert_sdk_error(self.run_audit(), "malformed_toml")
                    finally:
                        path.unlink()

    def test_toml_parser_import_and_runtime_failures_refuse_through_dispatcher(self):
        relative = "gradle/libs.versions.toml"
        self.write_manifest(relative, '[libraries]\nlocation = { module = "com.google.android.gms:play-services-location" }\n')
        self.assert_accepted(self.run_audit())
        hooks = self.workspace / "parser-hooks"
        hooks.mkdir()
        environment = dict(os.environ)
        environment["PYTHONPATH"] = str(hooks)
        environment["PYTHONNOUSERSITE"] = "1"
        faults = (
            ("toml_parser_unavailable", 'import sys\nsys.modules["tomllib"] = None\n'),
            ("toml_parser_failed", (
                'import builtins\noriginal_import = builtins.__import__\n'
                'def broken_import(name, *args, **kwargs):\n'
                '    if name == "tomllib":\n        raise RuntimeError("private-parser-payload")\n'
                '    return original_import(name, *args, **kwargs)\n'
                'builtins.__import__ = broken_import\n'
            )),
            ("toml_parser_failed", (
                'import tomllib\ndef broken_parser(text):\n'
                '    raise RuntimeError("private-parser-payload")\n'
                'tomllib.loads = broken_parser\n'
            )),
            ("toml_parser_failed", (
                'import tomllib\noriginal = tomllib.loads\ncalls = 0\n'
                'def broken_location_pass(text):\n    global calls\n    calls += 1\n'
                '    if calls > 1:\n        raise RuntimeError("private-parser-payload")\n'
                '    return original(text)\n'
                'tomllib.loads = broken_location_pass\n'
            )),
            ("toml_parser_failed", (
                'import tomllib\noriginal = tomllib.loads\ncalls = 0\n'
                'def broken_location_decode(text):\n    global calls\n    calls += 1\n'
                '    if calls > 1:\n        raise tomllib.TOMLDecodeError("private-parser-payload", "x", 0)\n'
                '    return original(text)\n'
                'tomllib.loads = broken_location_decode\n'
            )),
        )
        for classification, hook in faults:
            with self.subTest(classification=classification, hook=hook):
                self.write_manifest(relative, '[libraries.sdk]\ngroup = "com.google.android.gms"\nname = "play-services-analytics"\n')
                (hooks / "sitecustomize.py").write_text(hook, encoding="utf-8")
                self.assert_sdk_error(self.run_audit(env=environment), classification)

    def test_late_large_toml_catalog_retains_exact_location_and_deadline(self):
        relative = "gradle/libs.versions.toml"
        prefix = '[libraries]\n' + ''.join(
            f'lib{i} = {{ module = "example.test:library{i}", version = "1.0" }}\n' for i in range(4096)
        )
        for name in ("play-services-analytics", "play-services-location"):
            with self.subTest(name=name):
                self.write_manifest(relative, prefix + f'late = {{ group = "com.google.android.gms", name = "{name}" }}\n')
                result = self.run_audit()  # Original timeout=20, never increased.
                if name == "play-services-location":
                    self.assert_accepted(result)
                else:
                    self.assert_sdk_refused(result, "google-analytics", relative)
                    self.assertIn(f"at {relative}:4098\n", result.stderr)

    def test_toml_location_adapter_faults_refuse_after_valid_full_parse(self):
        relative = "gradle/libs.versions.toml"
        self.write_manifest(relative, '[libraries.sdk]\ngroup="com.google.android.gms"\nname="play-services-analytics"\n')
        hooks = self.workspace / "location-hooks"
        hooks.mkdir()
        environment = dict(os.environ, PYTHONPATH=str(hooks), PYTHONNOUSERSITE="1")
        faults = (
            'import sys\nsys.modules["toml_locations"] = None\n',
            '_parser.loads = lambda text: {}\n',
            '_parser.key_value_rule = lambda *args: 0\n',
            '_parser.key_value_rule = lambda *args: len(args[0]) + 1\n',
        )
        for fault in faults:
            with self.subTest(fault=fault):
                hook = (
                    'import tomllib\nfrom tomllib import _parser\noriginal = tomllib.loads\ncalls = 0\n'
                    'def change_after_full(text):\n    global calls\n    calls += 1\n    result = original(text)\n'
                    '    if calls == 1:\n' + ''.join('        ' + line + '\n' for line in fault.splitlines()) +
                    '    return result\ntomllib.loads = change_after_full\n'
                )
                (hooks / "sitecustomize.py").write_text(hook, encoding="utf-8")
                self.assert_sdk_error(self.run_audit(env=environment), "toml_parser_failed")

    def test_toml_statement_adapter_keeps_global_rules_and_complete_prefixes(self):
        import tomllib
        from tomllib import _parser
        from toml_locations import statement_ends
        names = ("loads", "key_value_rule", "create_dict_rule", "create_list_rule")
        original = tuple(getattr(_parser, name) for name in names)
        text = ('[[libraries.sdk]]\ngroup="example.test"\nname="play-services-analytics"\n'
                '[[libraries.sdk]]\ngroup="com.google.android.gms"\nname="play-services-analytics"\n'
                '[metadata]\nvalues=[\n"plain",\n"com.adjust.sdk:adjust-android",\n]\n')
        ends = statement_ends(text)
        self.assertEqual(len(ends), 8)
        for end in ends:
            tomllib.loads(text[:end])
        self.assertEqual(tuple(getattr(_parser, name) for name in names), original)
        relative = "gradle/libs.versions.toml"
        self.write_manifest(relative, text.replace("\n", "\r\n"))
        result = self.run_audit()
        self.assert_sdk_refused(result, "google-analytics", relative)
        self.assertIn(f"at {relative}:6\n", result.stderr)
        self.assertIn(f"forbidden SDK reference (adjust) at {relative}:11\n", result.stderr)

    def test_sdk_reflection_classes_are_refused_for_all_vendors(self):
        relative = "app/src/main/kotlin/Reflection.kt"
        for sdk, qualified_class, _ in SDK_CASES:
            for quote in ('"', '"""'):
                with self.subTest(sdk=sdk, quote=quote):
                    self.write_manifest(relative, (
                        'val url = "https://example.test"\n// load the explicit class below\n'
                        f"val sdkClass = Class.forName({quote}{qualified_class}{quote})\n"
                    ))
                    result = self.run_audit()
                    self.assert_sdk_refused(result, sdk, relative)
                    self.assertIn(f"{relative}:3", result.stdout + result.stderr)

    def test_modern_crashlytics_plugin_references_are_refused(self):
        for relative, content in (
            ("build.gradle.kts", 'plugins { id("com.google.firebase.crashlytics") version "1.0" }\n'),
            ("gradle/libs.versions.toml", '[plugins]\ncrash = { id = "com.google.firebase.crashlytics", version = "1.0" }\n'),
        ):
            with self.subTest(path=relative):
                path = self.write_manifest(relative, content)
                result = self.run_audit()
                path.unlink()
                self.assert_sdk_refused(result, "crashlytics", relative)

    def test_generated_and_test_sdk_inputs_are_excluded(self):
        references = "\n".join(f"import {qualified_class}" for _, qualified_class, _ in SDK_CASES)
        for directory in ("app/build/generated", ".gradle/cache", "app/src/test", "app/src/androidTest", "app/src/testFixtures"):
            self.write_manifest(f"{directory}/Sdk.kt", references)
            self.write_manifest(f"{directory}/build.gradle.kts", 'implementation("com.adjust.sdk:adjust-android:1.0")\n')
        self.assert_accepted(self.run_audit())

    def test_sdk_source_directories_are_not_filtered_by_substring(self):
        root = self.workspace / "source-build-test.gradle-project"
        self.make_project(root)
        for relative, content in (
            ("build-logic/convention/build.gradle.kts", 'implementation("com.adjust.sdk:adjust-android:1.0")\n'),
            ("feature/latest/src/main/java/example/building/Latest.kt", "import com.adjust.sdk.Adjust\n"),
        ):
            with self.subTest(path=relative):
                path = self.write_manifest(relative, content, root)
                result = self.run_audit(root)
                path.unlink()
                self.assert_sdk_refused(result, "adjust", relative)

    def test_sdk_comments_are_not_active_references(self):
        imports = "\n".join(f"import {qualified_class}" for _, qualified_class, _ in SDK_CASES)
        self.write_manifest("app/src/main/kotlin/Documentation.kt", f"/**\n{imports}\n*/\n// {imports.replace(chr(10), ' ')}\n")
        self.write_manifest("app/build.gradle.kts", "\n".join(f'// implementation("{coordinate}:1.0")' for _, _, coordinate in SDK_CASES))
        self.write_manifest("gradle/libs.versions.toml", "\n".join(f'# sdk = {{ module = "{coordinate}" }}' for _, _, coordinate in SDK_CASES))
        self.assert_accepted(self.run_audit())

    def test_sdk_scan_read_error_is_refused_not_reported_clean(self):
        path = self.write_manifest("gradle/libs.versions.toml", "")
        path.write_bytes(b"\xff")
        result = self.run_audit()
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode, 1, output)
        self.assertIn("[component=privacy-audit seam=sdk-references class=unreadable_input]", output)
        self.assertNotIn("PASSED", output)
        self.assertNotIn("Traceback", output)


if __name__ == "__main__":
    unittest.main()
