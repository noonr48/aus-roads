#!/usr/bin/env bash
# Contract and failure tests for generation-atomic bundled asset publication.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PUBLISHER="$HERE/../scripts/publish-bundled-demo-assets.sh"
WORK="$(mktemp -d)"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

OLD="$WORK/maptile"
NEXT="$WORK/next"
mkdir -p "$OLD" "$NEXT"
printf 'style-v1\n' > "$OLD/style.json"

make_generation() {
    local directory="$1"
    local marker="$2"
    sqlite3 "$directory/adelaide-test-tiles.mbtiles" <<SQL
CREATE TABLE metadata (name TEXT, value TEXT);
INSERT INTO metadata VALUES ('bounds', '138.4,-35.2,139,-34.6');
CREATE TABLE marker (value TEXT);
INSERT INTO marker VALUES ('$marker');
SQL
    sqlite3 "$directory/adelaide-test-search-v1.db" <<SQL
CREATE TABLE search_meta (id INTEGER PRIMARY KEY, name TEXT, kind TEXT, class TEXT, lat REAL, lon REAL);
INSERT INTO search_meta VALUES (1, '$marker', 'poi', 'shop=supermarket', -34.9, 138.6);
SQL
    sha256sum "$directory/adelaide-test-tiles.mbtiles" | cut -d' ' -f1 \
        > "$directory/adelaide-test-tiles.mbtiles.sha256"
    sha256sum "$directory/adelaide-test-search-v1.db" | cut -d' ' -f1 \
        > "$directory/adelaide-test-search-v1.db.sha256"
}

make_generation "$OLD" old
make_generation "$NEXT" next
bash "$PUBLISHER" "$NEXT" "$OLD"
[[ "$(sqlite3 "$OLD/adelaide-test-tiles.mbtiles" 'SELECT value FROM marker;')" == "next" ]]
[[ "$(sqlite3 "$OLD/adelaide-test-search-v1.db" 'SELECT name FROM search_meta;')" == "next" ]]
[[ "$(<"$OLD/style.json")" == "style-v1" ]]
[[ "$(<"$OLD/adelaide-test-tiles.mbtiles.sha256")" == "$(sha256sum "$OLD/adelaide-test-tiles.mbtiles" | cut -d' ' -f1)" ]]
[[ "$(<"$OLD/adelaide-test-search-v1.db.sha256")" == "$(sha256sum "$OLD/adelaide-test-search-v1.db" | cut -d' ' -f1)" ]]

# A bad candidate must fail before the atomic exchange and leave every live byte alone.
BEFORE="$(find "$OLD" -type f -printf '%P\0' | sort -z | xargs -0 -I{} sha256sum "$OLD/{}")"
printf '%064d\n' 0 > "$NEXT/adelaide-test-tiles.mbtiles.sha256"
if bash "$PUBLISHER" "$NEXT" "$OLD" >/dev/null 2>&1; then
    echo "expected mismatched generation to be rejected" >&2
    exit 1
fi
AFTER="$(find "$OLD" -type f -printf '%P\0' | sort -z | xargs -0 -I{} sha256sum "$OLD/{}")"
[[ "$BEFORE" == "$AFTER" ]]

echo "bundled demo atomic publish test: PASS"
