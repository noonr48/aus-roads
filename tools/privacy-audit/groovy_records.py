"""One pinned, bounded CST batch; source is data, never a Groovy program."""

import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

JAR_NAME = "groovy-3.0.22.jar"
JAR_SHA256 = "c92c92c4b9b183f9981ba7399f36592e5e3ad6f4cdac7101b5a22cc17998d13f"
BUDGET_SECONDS = 12
HELPER = Path(__file__).with_name("GroovyCoordinateRecords.java")


class GroovyRecordError(RuntimeError):
    def __init__(self, classification):
        self.classification = classification
        super().__init__(classification)


def equipment():
    home = Path.home()
    gradle = Path(os.environ.get("GRADLE_USER_HOME", home / ".gradle"))
    candidates = sorted((gradle / "wrapper/dists/gradle-8.10.2-bin").glob("*/gradle-8.10.2/lib/" + JAR_NAME))
    jar = next((path for path in candidates if path.is_file() and not path.is_symlink()
                and hashlib.sha256(path.read_bytes()).hexdigest() == JAR_SHA256), None)
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        java = Path(java_home) / "bin/java"
    elif (home / "tools/jdk17/bin/java").is_file():
        java = home / "tools/jdk17/bin/java"
    else:
        java = Path(shutil.which("java") or "/nonexistent/java")
    javac = java.with_name("javac")
    if jar is None or not java.is_file() or not javac.is_file() or not HELPER.is_file():
        raise GroovyRecordError("groovy_parser_unavailable")
    # No inherited agents, classpath, Groovy startup config or option injection.
    environment = {"HOME": str(home), "PATH": str(java.parent) + os.pathsep + "/usr/bin:/bin", "LANG": "C.UTF-8"}
    return java, javac, jar, environment


def run_checked(command, *, deadline, environment, cwd, payload=None):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise GroovyRecordError("groovy_parser_timeout")
    try:
        result = subprocess.run(command, input=payload, capture_output=True, text=True,
                                env=environment, cwd=cwd, timeout=remaining, check=False)
    except subprocess.TimeoutExpired as error:
        raise GroovyRecordError("groovy_parser_timeout") from error
    except (OSError, UnicodeError, subprocess.SubprocessError) as error:
        raise GroovyRecordError("groovy_parser_failed") from error
    if result.returncode != 0:
        raise GroovyRecordError("groovy_parser_failed")
    return result.stdout


def parse_response(output, texts):
    rows = output.splitlines()
    if len(rows) != len(texts) + 2 or rows[0] != "GROOVY_RECORDS\t1\t3.0.22" or rows[-1] != f"GROOVY_RECORDS_END\t{len(texts)}":
        raise GroovyRecordError("groovy_parser_protocol")
    result = []
    for index, (row, text) in enumerate(zip(rows[1:-1], texts)):
        parts = row.split("\t")
        if len(parts) != 3 or parts[0] != str(index) or parts[1] not in {"grammar_parsed", "syntax_error", "parser_failed"}:
            raise GroovyRecordError("groovy_parser_protocol")
        try:
            lines = json.loads(parts[2])
        except (ValueError, TypeError) as error:
            raise GroovyRecordError("groovy_parser_protocol") from error
        if (not isinstance(lines, list) or any(type(line) is not int or not 1 <= line <= text.count("\n") + 1 for line in lines)
                or lines != sorted(set(lines)) or (parts[1] != "grammar_parsed" and lines)):
            raise GroovyRecordError("groovy_parser_protocol")
        result.append((parts[1], lines))
    return result


def coordinate_records(texts):
    if not texts:
        return []
    deadline = time.monotonic() + BUDGET_SECONDS
    try:
        java, javac, jar, environment = equipment()
        with tempfile.TemporaryDirectory(prefix="ausroads-groovy-records-") as directory:
            # Inspect pinned JDK metadata without forwarding private option or
            # compiler/exception payloads to the audit's output.
            release = java.parent.parent / "release"
            if not re.search(r'^JAVA_VERSION="17(?:\.|\")', release.read_text(), re.MULTILINE):
                raise GroovyRecordError("groovy_parser_unavailable")
            run_checked([str(javac), "-J-Xmx256m", "-J-XX:ActiveProcessorCount=1", "--release", "17",
                         "-cp", str(jar), "-d", directory, str(HELPER)],
                        deadline=deadline, environment=environment, cwd=directory)
            payload = "".join(f"{index}\t{base64.b64encode(text.encode('utf-8')).decode('ascii')}\n"
                              for index, text in enumerate(texts))
            output = run_checked([str(java), "-Xmx256m", "-XX:ActiveProcessorCount=1", "-cp",
                                  directory + os.pathsep + str(jar), "GroovyCoordinateRecords"],
                                 payload=payload, deadline=deadline, environment=environment, cwd=directory)
        return parse_response(output, texts)
    except GroovyRecordError:
        raise
    except Exception as error:
        # Fixed classification only: source and compiler payloads never leak.
        raise GroovyRecordError("groovy_parser_failed") from error
