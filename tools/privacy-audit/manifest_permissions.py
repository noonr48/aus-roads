#!/usr/bin/env python3
"""Check source permissions and explicit SDK references, not merged APK contents.

Only app/src/withNetwork/AndroidManifest.xml may opt into the current network
and foreground GPS permissions. Removal markers apply to their own XML node.
SDK references and HttpClient constructor ownership are source checks, not
runtime SDK presence, instance cardinality or merged-APK privacy certification.
"""

import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID_NAME = "{http://schemas.android.com/apk/res/android}name"
TOOLS_NODE = "{http://schemas.android.com/tools}node"
PERMISSION_TAGS = {"uses-permission", "uses-permission-sdk-23"}
NETWORK_MANIFEST = Path("app/src/withNetwork/AndroidManifest.xml")
SOURCE_EXCLUDED_DIRECTORIES = {"build", ".gradle", "test", "androidTest", "testFixtures"}
# Current shared pack client and four network-only singleton traffic providers.
# These are exact source owners, not filename/module substring exemptions.
HTTP_CLIENT_OWNERS = frozenset(map(Path, (
    "offline/pack-downloader/src/main/kotlin/au/com/ausroads/offline/download/network/HttpClientProvider.kt",
    "traffic/provider-sa/src/main/kotlin/au/com/ausroads/traffic/provider/sa/TrafficSaProvider.kt",
    "traffic/provider-nsw/src/main/kotlin/au/com/ausroads/traffic/provider/nsw/NswLiveTrafficProvider.kt",
    "traffic/provider-vic/src/main/kotlin/au/com/ausroads/traffic/provider/vic/VicRoadsProvider.kt",
    "traffic/provider-sa-outback/src/main/kotlin/au/com/ausroads/traffic/provider/sa/outback/SaOutbackProvider.kt",
)))
NETWORK_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    # Already declared by the network flavor for its location-type foreground
    # service; this is not ACCESS_BACKGROUND_LOCATION.
    "android.permission.FOREGROUND_SERVICE_LOCATION",
}


# Vendor namespaces also cover their Maven groups and plugin IDs. Google Play
# Services location stays allowed: only analytics/measurement references refuse.
SDK_REFERENCES = {
    "firebase": r"com\.google\.firebase",
    "crashlytics": (
        r"com\.crashlytics|com\.google\.firebase\.crashlytics|"
        r"com\.google\.firebase:firebase-crashlytics[\w-]*"
    ),
    "google-analytics": (
        r"com\.google\.android\.gms\.(?:analytics|measurement)|com\.google\.analytics|"
        r"com\.google\.android\.gms:play-services-(?:analytics|measurement)[\w-]*"
    ),
    "amplitude": r"com\.amplitude",
    "mixpanel": r"com\.mixpanel",
    "appsflyer": r"com\.appsflyer",
    "adjust": r"com\.adjust",
}
SDK_PATTERNS = {
    sdk: re.compile(rf"(?<![\w$.])(?:{reference})(?![\w$])", re.IGNORECASE)
    for sdk, reference in SDK_REFERENCES.items()
}
# A shared lexical boundary feeds comment masking and coordinate tokens.
# INC/DEC must be atomic: x++ / 2 is division, not a slashy opener.
SDK_CODE_TOKEN = re.compile(r"[A-Za-z_$][\w$]*|\d[\w]*(?:\.\d[\w]*)?|\+\+|--|[^\s]")
# Keywords are not Identifier/CapitalizedIdentifier in Groovy's slashy check.
# this, true, false and null remain operand tokens, unlike these keywords.
SDK_GROOVY_KEYWORDS = frozenset("""
    as def in trait threadsafe var abstract assert break case catch class const
    continue default do else enum extends final finally for if goto implements
    import instanceof interface native new package private protected public
    return static strictfp super switch synchronized throw throws transient try
    void volatile while boolean byte char short int long float double
""".split())


def refuse(classification, message, seam="manifest-permissions"):
    # No input paths, permission strings or parser exception payloads in metadata.
    print(
        f"[component=privacy-audit seam={seam} class={classification}] "
        f"{message}",
        file=sys.stderr,
    )


def raise_walk_error(error):
    raise error


def audit_manifest_permissions(project_root):
    if not project_root.is_dir():
        refuse("missing_project_root", "Project root must be an existing directory.")
        return 1
    android_root = project_root / "android"
    if not android_root.is_dir():
        refuse("missing_android_root", "Project root must contain an Android directory.")
        return 1

    found = 0
    failed = False
    try:
        for directory, subdirs, filenames in os.walk(android_root, onerror=raise_walk_error):
            subdirs[:] = sorted(name for name in subdirs if name not in {"build", ".gradle"})
            if "AndroidManifest.xml" not in filenames:
                continue
            found += 1
            manifest = Path(directory) / "AndroidManifest.xml"
            try:
                xml = ET.parse(manifest).getroot()
            except (ET.ParseError, ValueError, LookupError):
                refuse("malformed_xml", "Android manifest must be well-formed XML.")
                failed = True
                continue
            if xml.tag != "manifest":
                refuse("invalid_manifest", "Android manifest must have a manifest root element.")
                failed = True
                continue
            network_source = manifest.relative_to(android_root) == NETWORK_MANIFEST
            for permission in xml.iter():
                if permission.tag not in PERMISSION_TAGS:
                    continue
                name = permission.get(ANDROID_NAME)
                if not name or name != name.strip() or "${" in name:
                    refuse("invalid_permission", "Permission must have a literal Android name.")
                    failed = True
                    continue
                if permission.get(TOOLS_NODE) == "remove":
                    continue
                if name == "android.permission.INTERNET" or "LOCATION" in name:
                    if not network_source or name not in NETWORK_PERMISSIONS:
                        refuse(
                            "forbidden_permission",
                            "Unremoved INTERNET/LOCATION permission violates source flavor policy.",
                        )
                        failed = True
    except OSError:
        refuse("unreadable_input", "Android source manifests could not be fully read.")
        return 1
    if not found:
        refuse("missing_manifests", "Android source must contain at least one manifest.")
        return 1
    return int(failed)


def sdk_comment_end(text, start, nested):
    offset, depth = start + 2, 1
    while offset < len(text):
        if nested and text.startswith("/*", offset):
            depth += 1
            offset += 2
        elif text.startswith("*/", offset):
            offset += 2
            depth -= 1
            if not depth:
                return offset
        else:
            offset += 1
    raise ValueError("unterminated_comment")


def sdk_literal_start(text, offset, kotlin, groovy, *, slashy=False):
    if groovy and text.startswith("$/", offset):
        return ("/$", "$", True), 2
    if groovy and slashy and text[offset] == "/":
        return ("/", "\\/", True), 1
    if text[offset] not in ("'", '"'):
        return None
    quote = text[offset]
    width = 3 if text.startswith(quote * 3, offset) else 1
    escape = None if kotlin and width == 3 else "\\"
    return (quote * width, escape, quote == '"' and (kotlin or groovy)), width


def sdk_source_tokens(text, *, nested_comments=False, groovy=False):
    # One iterative producer owns strings, active expressions and comments.
    # Interpolated strings become ordered, nonoverlapping opaque literal parts
    # and explicit owner boundaries; ordinary nested strings stay one literal.
    tokens, comments = [], []
    stack = [{"kind": "code", "depth": 0, "slashy": True, "delimiters": []}]
    offset = 0
    while offset < len(text):
        start = offset
        current = stack[-1]
        if current["kind"] == "literal":
            closing, escape, interpolated = current["frame"]
            if text.startswith(closing, offset):
                offset += len(closing)
                # Kotlin raw literals close with a terminal run's last quotes.
                if nested_comments and len(closing) == 3:
                    while offset < len(text) and text[offset] == closing[0]:
                        offset += 1
                kind = "literal_part" if current["split"] else "literal"
                start = current["part"]
                tokens.append((text[start:offset], start, offset, kind))
                stack.pop()
                stack[-1]["slashy"] = False  # StringLiteral/GStringEnd operand
                stack[-1]["previous"] = ""
            elif interpolated and text.startswith("${", offset):
                start = current["part"]
                if start < offset:
                    tokens.append((text[start:offset], start, offset, "literal_part"))
                tokens.append(("${", offset, offset + 2, "interpolation_open"))
                current["split"] = True
                stack.append({"kind": "code", "depth": 1, "slashy": True, "delimiters": []})
                offset += 2
            elif escape == "\\" and text[offset] == "\\":
                offset += 2
            elif escape == "\\/" and text.startswith("\\/", offset):
                offset += 2
            elif escape == "$" and text[offset:offset + 2] in ("$$", "$/"):
                offset += 2
            else:
                offset += 1
            continue
        if text.startswith("//", offset):
            offset = text.find("\n", offset)
            offset = len(text) if offset < 0 else offset
            comments.append((start, offset))
            tokens.append((text[start:offset], start, offset, "comment"))
            continue
        if text.startswith("/*", offset):
            offset = sdk_comment_end(text, offset, nested_comments)
            comments.append((start, offset))
            tokens.append((text[start:offset], start, offset, "comment"))
            continue
        first = sdk_literal_start(text, offset, nested_comments, groovy, slashy=current["slashy"])
        if first:
            stack.append({"kind": "literal", "frame": first[0], "part": start, "split": False})
            offset += first[1]
            continue
        # Escaped Groovy newlines are whitespace, not significant NL tokens.
        if groovy and (text.startswith("\\\r\n", offset) or text.startswith("\\\n", offset)):
            offset += 3 if text.startswith("\\\r\n", offset) else 2
            tokens.append((text[start:offset], start, offset, "continuation"))
            continue
        match = SDK_CODE_TOKEN.match(text, offset)
        if match is None:
            # Groovy NL is significant except beneath a top '(' (not TRY's
            # resource parens) or '['. A top '{' overrides outer parens.
            if groovy and text[offset] in "\r\n" and not (current["delimiters"] and current["delimiters"][-1]):
                current["slashy"] = True
                current["previous"] = ""
            offset += 1
            continue
        offset = match.end()
        value = text[start:offset]
        if current["depth"] and value == "}":
            current["depth"] -= 1
            if not current["depth"]:
                tokens.append((value, start, offset, "interpolation_close"))
                stack.pop()
                stack[-1]["part"] = offset
                continue
        elif current["depth"] and value == "{":
            current["depth"] += 1
        tokens.append((value, start, offset, "code"))
        if value in ("(", "[", "{"):
            # Store NL-hidden state at entry, before this token becomes previous.
            current["delimiters"].append(value == "[" or (value == "(" and current.get("previous") != "try"))
        elif value in (")", "]", "}") and current["delimiters"]:
            current["delimiters"].pop()
        current["previous"] = value
        # Groovy3's previous significant-token blacklist: identifiers, numbers,
        # this/boolean/null, closing delimiters and INC/DEC forbid slashy opening.
        current["slashy"] = not (
            value in {"this", "true", "false", "null", ")", "]", "}", "++", "--"}
            or value[0].isdigit()
            or ((value[0].isalpha() or value[0] in "_$") and value not in SDK_GROOVY_KEYWORDS)
        )
    if len(stack) != 1:
        raise ValueError("unterminated_literal")
    return tokens, comments


def sdk_reference_text(text, *, comments=None, nested_comments=False, groovy=False):
    if comments is None:
        _, comments = sdk_source_tokens(text, nested_comments=nested_comments, groovy=groovy)
    # Both top-level and interpolation-code comments are masked; literals and
    # exact character/line positions remain available to explicit SDK detection.
    value = list(text)
    for start, end in comments:
        value[start:end] = ["\n" if char == "\n" else " " for char in text[start:end]]
    return "".join(value)


def sdk_literal_value(value):
    if value.startswith("$/") and value.endswith("/$"):
        return value[2:-2]
    if len(value) >= 2 and value.startswith("/") and value.endswith("/"):
        return value[1:-1]
    if len(value) >= 2 and value[0] in ("'", '"') and value[-1] == value[0]:
        width = 3 if value.startswith(value[0] * 3) else 1
        return value[width:-width]
    return None


def sdk_toml_references(document):
    # tomllib resolves quoted/dotted keys, escapes and rich version tables.
    # Only combine group/name in one library mapping, never sibling aliases.
    libraries = document.get("libraries")
    found = set()
    pending = [(document, False)]
    while pending:
        value, library_record = pending.pop()
        if isinstance(value, str):
            for sdk, pattern in SDK_PATTERNS.items():
                if pattern.search(value):
                    found.add(sdk)
        elif isinstance(value, dict):
            group, name = value.get("group"), value.get("name")
            if library_record and isinstance(group, str) and isinstance(name, str):
                pending.append((f"{group}:{name}", False))
            for key, child in value.items():
                pending.append((key, False))
                pending.append((child, library_record or value is libraries))
        elif isinstance(value, list):
            pending.extend((child, library_record) for child in value)
    return found


def sdk_toml_reference_lines(text, tomllib):
    found = sdk_toml_references(tomllib.loads(text))
    if not found:
        return {}
    # Complete declarations can only add references. Search parser-owned ends
    # with the same public parser/policy, not a second grammar or every prefix.
    text = text.replace("\r\n", "\n")
    try:
        from toml_locations import statement_ends
        ends = statement_ends(text)
        lines = {}
        for sdk in found:
            low, high = 0, len(ends) - 1
            while low < high:
                middle = (low + high) // 2
                if sdk in sdk_toml_references(tomllib.loads(text[:ends[middle]])):
                    high = middle
                else:
                    low = middle + 1
            if sdk not in sdk_toml_references(tomllib.loads(text[:ends[low]])):
                raise RuntimeError("TOML reference locations unresolved")
            lines[sdk] = text.count("\n", 0, ends[low]) + 1
        return lines
    except tomllib.TOMLDecodeError:
        # Full input was valid above: a later decode fault is the location seam,
        # not malformed user source. Caller emits fixed, payload-free metadata.
        raise RuntimeError("TOML reference location parser failed") from None


def sdk_static_argument(tokens):
    # Strip only grouping that encloses the WHOLE expression, never a call's
    # parentheses or an operator/receiver suffix following a literal.
    while len(tokens) > 2 and tokens[0][0] == "(" and tokens[-1][0] == ")":
        depth = 0
        for index, token in enumerate(tokens):
            if token[3] == "code" and token[0] in ("(", ")"):
                depth += 1 if token[0] == "(" else -1
                if depth == 0:
                    break
        if index != len(tokens) - 1:
            break
        tokens = tokens[1:-1]
    return sdk_literal_value(tokens[0][0]) if len(tokens) == 1 and tokens[0][3] == "literal" else None


def sdk_annotation_arguments(tokens, opening):
    # Qualified and use-site annotations have the same inactive member role.
    index = opening - 1
    while index >= 0 and tokens[index][3] == "code" and (
        tokens[index][0] == "." or re.fullmatch(r"[A-Za-z_$][\w$]*", tokens[index][0])
    ):
        index -= 1
    return index >= 0 and (tokens[index][0] == "@" or (
        tokens[index][0] == ":" and index >= 2 and tokens[index - 2][0] == "@"
    ))


def sdk_gradle_reference_lines(text, *, tokens=None):
    # Kotlin direct call/mapOf arguments only. The lexer owns literal/comment
    # boundaries; each delimiter owns its direct comma-separated expressions.
    # Groovy uses its CST instead, and Java has no named-argument coordinates.
    if tokens is None:
        tokens, _ = sdk_source_tokens(text, nested_comments=True)
    tokens = [token for token in tokens if token[3] not in ("comment", "continuation")]
    owners, matches = [], {}
    closers = {"(": ")", "[": "]", "{": "}", "${": "}"}
    for index, (value, _, _, kind) in enumerate(tokens):
        if kind == "interpolation_open" or (kind == "code" and value in ("(", "[", "{")):
            owners.append((value, index, index + 1, []))
        elif owners and kind == "code" and value == ",":
            delimiter, opening, start, arguments = owners[-1]
            arguments.append((start, index))
            owners[-1] = (delimiter, opening, index + 1, arguments)
        elif owners and (kind == "interpolation_close" or (kind == "code" and value in (")", "]", "}"))):
            delimiter, opening, start, arguments = owners.pop()
            if closers[delimiter] != value:
                raise ValueError("coordinate_owner_mismatch")
            if delimiter != "(" or sdk_annotation_arguments(tokens, opening):
                continue
            arguments.append((start, index))
            fields = {}
            for begin, end in arguments:
                argument = tokens[begin:end]
                if len(argument) < 3 or argument[1][3] != "code" or argument[1][0] not in ("=", "to"):
                    continue
                key = sdk_literal_value(argument[0][0]) if argument[0][3] == "literal" else argument[0][0]
                if key in ("group", "name"):
                    # A computed last field cannot borrow an earlier value.
                    fields[key] = (sdk_static_argument(argument[2:]), argument[0][1])
            if all(key in fields and fields[key][0] is not None for key in ("group", "name")):
                coordinate = fields["group"][0] + ":" + fields["name"][0]
                if SDK_PATTERNS["google-analytics"].search(coordinate):
                    line = text.count("\n", 0, min(fields["group"][1], fields["name"][1])) + 1
                    matches["google-analytics"] = min(matches.get("google-analytics", line), line)
    return matches


def httpclient_declaration(tokens, index):
    # Scan only the current declaration header. Balance receiver-type parens,
    # but stop at an unmatched argument opener or a body/default-value boundary:
    # calls inside a function's parameters/body must remain active.
    closing = []
    for value, _, _, kind in reversed(tokens[:index]):
        if kind != "code":
            if not closing:
                return False
            continue
        if value in (")", "]"):
            closing.append(")" if value == ")" else "]")
        elif value in ("(", "["):
            if not closing or closing.pop() != (")" if value == "(" else "]"):
                return False
        elif not closing:
            if value in {"=", "{", "}", ";"}:
                return False
            if value in {"fun", "class", "interface"}:
                return True
    return False


def audit_httpclient_ownership(project_root):
    android_root = project_root / "android"
    if not android_root.is_dir():
        refuse("missing_android_root", "HttpClient ownership audit requires Android sources.", "httpclient-ownership")
        return 1
    failures = 0

    def fail(classification, message):
        nonlocal failures
        failures += 1
        if failures <= 5:
            refuse(classification, message, "httpclient-ownership")

    try:
        for directory, subdirs, filenames in os.walk(android_root, onerror=raise_walk_error):
            subdirs[:] = sorted(name for name in subdirs if name not in SOURCE_EXCLUDED_DIRECTORIES)
            for filename in sorted(filenames):
                source = Path(directory) / filename
                if source.suffix not in {".kt", ".kts", ".java"}:
                    continue
                text = source.read_text(encoding="utf-8")
                try:
                    tokens, _ = sdk_source_tokens(text, nested_comments=source.suffix in {".kt", ".kts"})
                    tokens = [token for token in tokens if token[3] != "comment"]
                    sites = [start for index, (value, start, _, kind) in enumerate(tokens[:-1])
                             if kind == "code" and value == "HttpClient"
                             and tokens[index + 1][0] == "(" and tokens[index + 1][3] == "code"
                             and not httpclient_declaration(tokens, index)]
                except Exception:
                    fail("source_parser_failed", "HttpClient source literals/comments could not be parsed.")
                    continue
                relative = source.relative_to(android_root)
                allowed = 1 if relative in HTTP_CLIENT_OWNERS else 0
                if len(sites) > allowed:
                    line = text.count("\n", 0, sites[allowed]) + 1
                    classification = "duplicate_httpclient" if allowed else "forbidden_httpclient"
                    fail(classification, f"Unexpected HttpClient constructor site at {relative}:{line}")
    except (OSError, UnicodeError):
        fail("unreadable_input", "HttpClient ownership audit could not fully read Android sources.")
    if failures > 5:
        refuse("suppressed_failures", f"{failures - 5} additional HTTP source failures suppressed.", "httpclient-ownership")
    return int(bool(failures))


def audit_sdk_references(project_root):
    android_root = project_root / "android"
    if not android_root.is_dir():
        refuse("missing_android_root", "SDK reference audit requires Android sources.", "sdk-references")
        return 1
    try:
        import tomllib
    except ImportError:
        refuse("toml_parser_unavailable", "SDK reference audit requires stdlib tomllib.", "sdk-references")
        return 1
    except Exception:
        refuse("toml_parser_failed", "SDK TOML parser could not be initialized.", "sdk-references")
        return 1
    failed = False
    try:
        sources = []
        for directory, subdirs, filenames in os.walk(android_root, onerror=raise_walk_error):
            # Exact directory names, never path substrings: build-logic and
            # build.gradle(.kts) are source inputs, not generated build output.
            subdirs[:] = sorted(name for name in subdirs if name not in SOURCE_EXCLUDED_DIRECTORIES)
            for filename in sorted(filenames):
                source = Path(directory) / filename
                if source.suffix in {".kt", ".kts", ".java", ".gradle", ".toml"}:
                    sources.append((source, source.read_text(encoding="utf-8")))
        groovy_inputs = [(source, text) for source, text in sources if source.suffix == ".gradle"]
        groovy = {}
        if groovy_inputs:
            try:
                from groovy_records import coordinate_records, GroovyRecordError
            except Exception:
                refuse("groovy_parser_unavailable", "Pinned Groovy record parser could not be loaded.", "sdk-references")
                return 1
            try:
                rows = coordinate_records([text for _, text in groovy_inputs])
                groovy = dict(zip((source for source, _ in groovy_inputs), rows))
            except GroovyRecordError as error:
                refuse(error.classification, "Pinned Groovy record batch refused.", "sdk-references")
                return 1
            except Exception:
                refuse("groovy_parser_failed", "Pinned Groovy record batch failed.", "sdk-references")
                return 1
        for source, text in sources:
                if source.suffix == ".toml":
                    try:
                        matches = sdk_toml_reference_lines(text, tomllib)
                    except tomllib.TOMLDecodeError:
                        refuse("malformed_toml", "SDK TOML source must be well-formed.", "sdk-references")
                        failed = True
                        continue
                    except Exception:
                        # Parser/runtime faults must not become a clean audit or
                        # expose exception messages containing private source.
                        refuse("toml_parser_failed", "SDK TOML source could not be parsed.", "sdk-references")
                        failed = True
                        continue
                else:
                    try:
                        tokens, comments = sdk_source_tokens(text, nested_comments=source.suffix in {".kt", ".kts"},
                                                             groovy=source.suffix == ".gradle")
                        text = sdk_reference_text(text, comments=comments)
                        if source.suffix == ".gradle":
                            status, lines = groovy[source]
                            if status != "grammar_parsed":
                                raise ValueError("groovy_record_parse_failed")
                            matches = {"google-analytics": min(lines)} if lines else {}
                        else:
                            matches = sdk_gradle_reference_lines(text, tokens=tokens) if source.suffix in {".kt", ".kts"} else {}
                    except Exception:
                        refuse("source_parser_failed", "SDK source literals/comments could not be parsed.", "sdk-references")
                        failed = True
                        continue
                    for sdk, pattern in SDK_PATTERNS.items():
                        match = pattern.search(text)
                        if match:
                            line = text.count("\n", 0, match.start()) + 1
                            matches[sdk] = min(matches.get(sdk, line), line)
                for sdk in SDK_PATTERNS:
                    if sdk in matches:
                        line = matches[sdk]
                        refuse(
                            "forbidden_sdk_reference",
                            f"forbidden SDK reference ({sdk}) at {source.relative_to(android_root)}:{line}",
                            "sdk-references",
                        )
                        failed = True
    except (OSError, UnicodeError):
        refuse("unreadable_input", "SDK reference audit could not fully read Android sources.", "sdk-references")
        return 1
    return int(failed)


def main():
    args = sys.argv[1:]
    mode = args.pop(0) if args and args[0] in {"--sdk-references", "--httpclient-ownership"} else None
    if not args:
        project_root = Path(__file__).resolve().parents[2]
    elif len(args) == 2 and args[0] == "--project-root" and args[1]:
        project_root = Path(args[1])
    else:
        refuse("invalid_arguments", "Usage: manifest_permissions.py [--sdk-references|--httpclient-ownership] [--project-root PATH]")
        return 1
    audit = {"--sdk-references": audit_sdk_references, "--httpclient-ownership": audit_httpclient_ownership}
    return audit.get(mode, audit_manifest_permissions)(project_root)


if __name__ == "__main__":
    sys.exit(main())
