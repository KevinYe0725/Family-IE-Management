#!/bin/sh

set -eu

source_root=$(CDPATH= cd "$(dirname "$0")/.." && pwd -P)
temporary_base=${TMPDIR:-/tmp}
temporary_base=$(CDPATH= cd "$temporary_base" && pwd -P)
session_root=$(mktemp -d "$temporary_base/family-finance-unix-gates.XXXXXX")
gate_succeeded=0
case "$session_root" in
    "$temporary_base"/family-finance-unix-gates.*) ;;
    *)
        printf '%s\n' "Refusing to use an unexpected Unix gate root: $session_root" >&2
        exit 1
        ;;
esac

cleanup() {
    case "$session_root" in
        "$temporary_base"/family-finance-unix-gates.*)
            if [ "$gate_succeeded" -eq 1 ]; then
                rm -rf "$session_root"
            else
                printf '%s\n' "Synthetic failure evidence was retained under $session_root" >&2
            fi
            ;;
        *)
            printf '%s\n' "Refusing to clean an unexpected Unix gate root: $session_root" >&2
            ;;
    esac
}
trap cleanup 0
trap 'exit 130' INT
trap 'exit 143' TERM

fail() {
    printf '%s\n' "Unix startup gate failed: $*" >&2
    exit 1
}

assert_true() {
    if ! "$1"; then
        fail "$2"
    fi
}

assert_equal() {
    if [ "$1" != "$2" ]; then
        fail "$3 Expected '$1'; received '$2'."
    fi
}

assert_under_session_root() {
    candidate=$1
    case "$candidate" in
        "$session_root"/*) ;;
        *) fail "Refusing to access a scenario path outside the unique gate root: $candidate" ;;
    esac
}

require_source_file() {
    [ -f "$source_root/$1" ] || fail "Required source file is missing: $1"
}

require_source_file start-local.sh
require_source_file scripts/unix-startup-preflight.sh

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{ print tolower($1) }'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{ print tolower($1) }'
    else
        fail 'The certification gate requires sha256sum or shasum.'
    fi
}

new_scenario() {
    scenario=$session_root/$1
    assert_under_session_root "$scenario"
    mkdir -p "$scenario/scripts" "$scenario/.mvn/wrapper" "$scenario/tmp"
    cp "$source_root/start-local.sh" "$scenario/start-local.sh"
    cp "$source_root/scripts/unix-startup-preflight.sh" "$scenario/scripts/unix-startup-preflight.sh"
    cp "$source_root/.mvn/wrapper/maven-wrapper.jar" "$scenario/.mvn/wrapper/maven-wrapper.jar"
    cp "$source_root/.mvn/wrapper/maven-wrapper.properties" "$scenario/.mvn/wrapper/maven-wrapper.properties"
    cat >"$scenario/mvnw" <<'FAKE_WRAPPER'
#!/bin/sh
set -eu

if [ -n "${GATE_EXPECTED_CWD:-}" ] && [ "$(pwd -P)" != "$GATE_EXPECTED_CWD" ]; then
    exit 93
fi

output_file=
run_arguments=
for argument in "$@"; do
    case "$argument" in
        -Dmdep.outputFile=*) output_file=${argument#-Dmdep.outputFile=} ;;
        -Dspring-boot.run.arguments=*) run_arguments=${argument#-Dspring-boot.run.arguments=} ;;
    esac
done

case " $* " in
    *" dependency:build-classpath "*)
        [ -n "$output_file" ] || exit 91
        printf '%s\n' "${GATE_H2_JAR:?}" >"$output_file"
        exit 0
        ;;
esac

if [ -n "${GATE_LAUNCH_MARKER:-}" ]; then
    printf '%s\n' launched >"$GATE_LAUNCH_MARKER"
    exit "${GATE_LAUNCH_EXIT:-0}"
fi

[ -n "${GATE_APP_JAR:-}" ] || exit 92
if [ -n "$run_arguments" ]; then
    set -f
    # Gate-controlled arguments contain no whitespace-bearing values.
    set -- $run_arguments
    set +f
else
    set --
fi
exec java -jar "$GATE_APP_JAR" "$@"
FAKE_WRAPPER
    chmod +x "$scenario/start-local.sh" "$scenario/scripts/unix-startup-preflight.sh" "$scenario/mvnw"
    printf '%s\n' "$scenario"
}

count_completed_backups() {
    backup_root=$1
    count=0
    if [ -d "$backup_root" ]; then
        for candidate in "$backup_root"/*; do
            [ -d "$candidate" ] || continue
            case "$candidate" in *.partial) continue ;; esac
            [ -f "$candidate/RESTORE.txt" ] || continue
            count=$((count + 1))
        done
    fi
    printf '%s\n' "$count"
}

count_partial_backups() {
    backup_root=$1
    count=0
    if [ -d "$backup_root" ]; then
        for candidate in "$backup_root"/*.partial; do
            [ -d "$candidate" ] || continue
            count=$((count + 1))
        done
    fi
    printf '%s\n' "$count"
}

only_completed_backup() {
    backup_root=$1
    found=
    for candidate in "$backup_root"/*; do
        [ -d "$candidate" ] || continue
        case "$candidate" in *.partial) continue ;; esac
        [ -f "$candidate/RESTORE.txt" ] || continue
        [ -z "$found" ] || fail "More than one completed backup exists under $backup_root"
        found=$candidate
    done
    [ -n "$found" ] || fail "No completed backup exists under $backup_root"
    printf '%s\n' "$found"
}

new_stage_one_fixture() {
    scenario=$1
    database_base=$scenario/data/family-finance
    assert_under_session_root "$database_base"
    mkdir -p "$scenario/data"
    java -cp "$fixture_classpath" com.familyfinance.migration.StageOneDatabaseFixtureCli "$database_base" >/dev/null
    [ -s "$database_base.mv.db" ] || fail "The Stage 1 fixture is missing or empty in $scenario"
}

get_free_port() {
    python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'
}

wait_for_ready() {
    port=$1
    process_id=$2
    log_file=$3
    attempts=0
    while [ "$attempts" -lt 180 ]; do
        if curl -fsS --max-time 2 "http://127.0.0.1:$port/api/csrf" >/dev/null 2>&1; then
            return 0
        fi
        if ! kill -0 "$process_id" 2>/dev/null; then
            wait "$process_id" || true
            sed -n '1,240p' "$log_file" >&2
            fail "The application exited before readiness on port $port"
        fi
        attempts=$((attempts + 1))
        sleep 1
    done
    sed -n '1,240p' "$log_file" >&2
    fail "The application did not become ready on port $port"
}

stop_process() {
    process_id=$1
    if kill -0 "$process_id" 2>/dev/null; then
        kill "$process_id" 2>/dev/null || true
    fi
    attempts=0
    while kill -0 "$process_id" 2>/dev/null && [ "$attempts" -lt 30 ]; do
        attempts=$((attempts + 1))
        sleep 1
    done
    if kill -0 "$process_id" 2>/dev/null; then
        kill -KILL "$process_id" 2>/dev/null || true
    fi
    wait "$process_id" 2>/dev/null || true
}

start_application() {
    scenario=$1
    port=$2
    log_file=$3
    (
        cd "$scenario"
        exec env "TMPDIR=$scenario/tmp/" "GATE_H2_JAR=$h2_jar" "GATE_APP_JAR=$app_jar" \
            ./start-local.sh "-Dspring-boot.run.arguments=--server.port=$port"
    ) >"$log_file" 2>&1 &
    started_process_id=$!
}

verify_demo_login_and_ledger() {
    scenario=$1
    port=$2
    cookie_jar=$scenario/session.cookies
    csrf_json=$scenario/csrf.json
    login_json=$scenario/login.json
    ledger_json=$scenario/ledger.json
    curl -fsS --max-time 5 -c "$cookie_jar" "http://127.0.0.1:$port/api/csrf" >"$csrf_json"
    csrf_header=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["headerName"])' "$csrf_json")
    csrf_token=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["token"])' "$csrf_json")
    curl -fsS --max-time 10 -b "$cookie_jar" -c "$cookie_jar" \
        -H "$csrf_header: $csrf_token" -H 'Content-Type: application/x-www-form-urlencoded' \
        --data-urlencode 'username=demo' --data-urlencode 'password=demo1234' \
        "http://127.0.0.1:$port/api/auth/login" >"$login_json"
    identity=$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1]))["data"]; print(d["email"]+"/"+d["role"])' "$login_json")
    assert_equal 'demo@local.family/OWNER' "$identity" 'Restored demo login returned the wrong identity or role.'
    curl -fsS --max-time 10 -b "$cookie_jar" "http://127.0.0.1:$port/api/transactions" >"$ledger_json"
    ledger_count=$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))["data"]))' "$ledger_json")
    assert_equal 12 "$ledger_count" 'Restored Stage 1 fixture did not preserve all ledger rows.'
}

date_with_offset() {
    offset=$1
    if date -v+"${offset}"S '+%Y%m%d-%H%M%S' >/dev/null 2>&1; then
        date -v+"${offset}"S '+%Y%m%d-%H%M%S'
    else
        date -d "+$offset seconds" '+%Y%m%d-%H%M%S'
    fi
}

printf '%s\n' "Synthetic Unix gate root: $session_root"

test_classpath_file=$session_root/test-runtime-classpath.txt
(
    cd "$source_root"
    ./mvnw -q -DskipTests package test-compile dependency:build-classpath \
        -Dmdep.includeScope=test "-Dmdep.outputFile=$test_classpath_file"
)
[ -s "$test_classpath_file" ] || fail 'Could not resolve the test runtime classpath.'
fixture_classpath=$source_root/target/test-classes:$source_root/target/classes:$(cat "$test_classpath_file")
app_jar=$source_root/target/family-finance-0.0.1-SNAPSHOT.jar
[ -s "$app_jar" ] || fail 'The executable application jar was not built.'
h2_jar=$(tr ':' '\n' <"$test_classpath_file" | awk '/\/com\/h2database\/h2\/2[.]3[.]232\/h2-2[.]3[.]232[.]jar$/ { print }')
[ -n "$h2_jar" ] || fail 'The pinned H2 2.3.232 jar was not present in the test runtime classpath.'
assert_equal 1 "$(printf '%s\n' "$h2_jar" | awk 'NF { count++ } END { print count+0 }')" 'The gate resolved more than one pinned H2 jar.'

printf '%s\n' 'Gate 1/7: Java 17 and the project wrapper are mandatory.'
scenario=$(new_scenario prerequisites)
marker=$scenario/launched
fake_bin=$scenario/fake-java
mkdir "$fake_bin"
cat >"$fake_bin/java" <<'FAKE_JAVA'
#!/bin/sh
printf '%s\n' 'openjdk version "11.0.24"' >&2
exit 0
FAKE_JAVA
chmod +x "$fake_bin/java"
if (cd "$scenario" && TMPDIR=$scenario/tmp PATH=$fake_bin:$PATH GATE_H2_JAR=$h2_jar GATE_LAUNCH_MARKER=$marker ./start-local.sh >prerequisite.log 2>&1); then
    fail 'The launcher accepted Java 11.'
fi
[ ! -e "$marker" ] || fail 'The application launched with Java 11.'
rm "$scenario/mvnw"
if (cd "$scenario" && TMPDIR=$scenario/tmp GATE_H2_JAR=$h2_jar GATE_LAUNCH_MARKER=$marker ./start-local.sh >>prerequisite.log 2>&1); then
    fail 'The launcher accepted a missing project wrapper.'
fi
[ ! -e "$marker" ] || fail 'The application launched without the project wrapper.'

printf '%s\n' 'Gate 2/7: No database launches without creating a backup path.'
scenario=$(new_scenario no-data)
marker=$scenario/launched
(cd "$scenario" && TMPDIR=$scenario/tmp GATE_H2_JAR=$h2_jar GATE_LAUNCH_MARKER=$marker ./start-local.sh >no-data.log 2>&1)
[ -f "$marker" ] || fail 'The no-data scenario did not reach application launch.'
[ ! -e "$scenario/data-backups" ] || fail 'The no-data scenario created a backup path.'
[ ! -e "$scenario/data" ] || fail 'The no-data preflight created a production data path.'

scenario=$(new_scenario external-working-directory)
new_stage_one_fixture "$scenario"
marker=$scenario/launched
if ! (cd "$session_root" && TMPDIR=$scenario/tmp GATE_EXPECTED_CWD=$scenario GATE_H2_JAR=$h2_jar GATE_LAUNCH_MARKER=$marker "$scenario/start-local.sh" >"$scenario/external-cwd.log" 2>&1); then
    fail 'The launcher could not run its guarded preflight from outside the project directory.'
fi
[ -f "$marker" ] || fail 'External-directory invocation did not reach application launch.'
assert_equal 1 "$(count_completed_backups "$scenario/data-backups")" 'External-directory invocation did not create the required legacy backup.'

printf '%s\n' 'Gate 3/7: Inspection and H2 resolution failures retain partial evidence and prevent launch.'
scenario=$(new_scenario inspection-failure)
mkdir "$scenario/data"
printf '%s\n' 'not an H2 database' >"$scenario/data/family-finance.mv.db"
marker=$scenario/launched
if (cd "$scenario" && TMPDIR=$scenario/tmp GATE_H2_JAR=$h2_jar GATE_LAUNCH_MARKER=$marker ./start-local.sh >inspection.log 2>&1); then
    fail 'The launcher accepted a corrupt nonempty primary database.'
fi
[ ! -e "$marker" ] || fail 'The application launched after inspection failed.'
assert_equal 1 "$(count_partial_backups "$scenario/data-backups")" 'Inspection failure did not retain exactly one partial directory.'

scenario=$(new_scenario h2-resolution-failure)
mkdir "$scenario/data"
printf '%s\n' 'nonempty primary' >"$scenario/data/family-finance.mv.db"
marker=$scenario/launched
wrong_h2=$session_root/h2-2.4.240.jar
if (cd "$scenario" && TMPDIR=$scenario/tmp GATE_H2_JAR=$wrong_h2 GATE_LAUNCH_MARKER=$marker ./start-local.sh >h2-resolution.log 2>&1); then
    fail 'The launcher accepted a runtime classpath without exact H2 2.3.232.'
fi
[ ! -e "$marker" ] || fail 'The application launched after H2 resolution failed.'
assert_equal 1 "$(count_partial_backups "$scenario/data-backups")" 'H2 resolution failure did not retain exactly one partial directory.'

printf '%s\n' 'Gate 4/7: Copy and hash failures retain partial data and prevent launch.'
scenario=$(new_scenario copy-failure)
new_stage_one_fixture "$scenario"
marker=$scenario/launched
fake_copy_bin=$scenario/fake-copy
mkdir "$fake_copy_bin"
cat >"$fake_copy_bin/cp" <<'FAKE_COPY'
#!/bin/sh
exit 41
FAKE_COPY
chmod +x "$fake_copy_bin/cp"
if (cd "$scenario" && TMPDIR=$scenario/tmp PATH=$fake_copy_bin:$PATH GATE_H2_JAR=$h2_jar GATE_LAUNCH_MARKER=$marker ./start-local.sh >copy.log 2>&1); then
    fail 'The launcher accepted a failed companion copy.'
fi
[ ! -e "$marker" ] || fail 'The application launched after a companion copy failed.'
assert_equal 0 "$(count_completed_backups "$scenario/data-backups")" 'Copy failure published a completed backup.'
assert_equal 1 "$(count_partial_backups "$scenario/data-backups")" 'Copy failure did not retain exactly one partial directory.'

scenario=$(new_scenario hash-failure)
new_stage_one_fixture "$scenario"
marker=$scenario/launched
fake_hash_bin=$scenario/fake-hash
mkdir "$fake_hash_bin"
for hash_command in sha256sum shasum; do
    cat >"$fake_hash_bin/$hash_command" <<'FAKE_HASH'
#!/bin/sh
exit 42
FAKE_HASH
    chmod +x "$fake_hash_bin/$hash_command"
done
if (cd "$scenario" && TMPDIR=$scenario/tmp PATH=$fake_hash_bin:$PATH GATE_H2_JAR=$h2_jar GATE_LAUNCH_MARKER=$marker ./start-local.sh >hash.log 2>&1); then
    fail 'The launcher accepted a failed SHA-256 command.'
fi
[ ! -e "$marker" ] || fail 'The application launched after hashing failed.'
assert_equal 0 "$(count_completed_backups "$scenario/data-backups")" 'Hash failure published a completed backup.'
assert_equal 1 "$(count_partial_backups "$scenario/data-backups")" 'Hash failure did not retain exactly one partial directory.'

printf '%s\n' 'Gate 5/7: Legacy backup covers pristine primary, companions, manifest, and collision handling.'
scenario=$(new_scenario 'backup restore')
new_stage_one_fixture "$scenario"
printf '%s\n' 'synthetic companion' >"$scenario/data/family-finance.trace.db"
: >"$scenario/data/family-finance.empty.db"
primary_before=$(sha256_file "$scenario/data/family-finance.mv.db")
trace_before=$(sha256_file "$scenario/data/family-finance.trace.db")
empty_before=$(sha256_file "$scenario/data/family-finance.empty.db")
mkdir "$scenario/data-backups"
offset=0
while [ "$offset" -le 8 ]; do
    collision=$scenario/data-backups/$(date_with_offset "$offset")
    mkdir -p "$collision"
    printf '%s\n' 'must remain unchanged' >"$collision/collision-sentinel.txt"
    offset=$((offset + 1))
done
port=$(get_free_port)
application_log=$scenario/first-start.log
start_application "$scenario" "$port" "$application_log"
first_process_id=$started_process_id
wait_for_ready "$port" "$first_process_id" "$application_log"
[ -z "$(find "$scenario/tmp" -type f -print -quit)" ] || fail 'Successful preflight stranded a temporary file before application launch.'
completed=$(count_completed_backups "$scenario/data-backups")
assert_equal 1 "$completed" 'Legacy startup did not create exactly one completed backup.'
assert_equal 0 "$(count_partial_backups "$scenario/data-backups")" 'Successful backup left a partial directory.'
verified_backup=$(only_completed_backup "$scenario/data-backups")
case "$(basename "$verified_backup")" in
    ????????-??????-*) ;;
    *) fail 'Backup did not select a collision-safe suffixed destination.' ;;
esac
assert_equal "$primary_before" "$(sha256_file "$verified_backup/family-finance.mv.db")" 'Read-only preinspection changed the primary before backup.'
assert_equal "$trace_before" "$(sha256_file "$verified_backup/family-finance.trace.db")" 'Backup changed the trace companion.'
assert_equal "$empty_before" "$(sha256_file "$verified_backup/family-finance.empty.db")" 'Backup changed the zero-byte companion.'
for database_file in family-finance.mv.db family-finance.trace.db family-finance.empty.db; do
    database_hash=$(sha256_file "$verified_backup/$database_file")
    grep -F "$database_hash  $database_file" "$verified_backup/RESTORE.txt" >/dev/null ||
        fail "RESTORE.txt omitted the SHA-256 entry for $database_file"
done
for collision in "$scenario/data-backups"/*; do
    [ -f "$collision/collision-sentinel.txt" ] || continue
    assert_equal 'must remain unchanged' "$(sed -n '1p' "$collision/collision-sentinel.txt")" 'Collision handling overwrote a pre-existing destination.'
done
stop_process "$first_process_id"

printf '%s\n' 'Gate 6/7: A migrated database skips backup, and restore preserves login plus 12 rows.'
completed_before_restart=$(count_completed_backups "$scenario/data-backups")
restart_port=$(get_free_port)
restart_log=$scenario/restart.log
start_application "$scenario" "$restart_port" "$restart_log"
restart_process_id=$started_process_id
wait_for_ready "$restart_port" "$restart_process_id" "$restart_log"
stop_process "$restart_process_id"
assert_equal "$completed_before_restart" "$(count_completed_backups "$scenario/data-backups")" 'Already-migrated restart created another backup.'
assert_equal 0 "$(count_partial_backups "$scenario/data-backups")" 'Already-migrated restart left a partial directory.'
grep -F 'already has Flyway history; no migration backup is required' "$restart_log" >/dev/null ||
    fail 'Already-migrated restart did not take the explicit skip branch.'

mkdir "$scenario/migrated-before-restore"
for database_file in "$scenario/data"/family-finance.*.db; do
    [ -e "$database_file" ] || [ -h "$database_file" ] || continue
    mv "$database_file" "$scenario/migrated-before-restore/"
done
for database_file in "$verified_backup"/family-finance.*.db; do
    cp "$database_file" "$scenario/data/"
done
assert_equal "$primary_before" "$(sha256_file "$scenario/data/family-finance.mv.db")" 'Restore changed the primary database.'
assert_equal "$trace_before" "$(sha256_file "$scenario/data/family-finance.trace.db")" 'Restore changed the trace companion.'
assert_equal "$empty_before" "$(sha256_file "$scenario/data/family-finance.empty.db")" 'Restore changed the zero-byte companion.'
restore_port=$(get_free_port)
restore_log=$scenario/restored-start.log
start_application "$scenario" "$restore_port" "$restore_log"
restore_process_id=$started_process_id
wait_for_ready "$restore_port" "$restore_process_id" "$restore_log"
verify_demo_login_and_ledger "$scenario" "$restore_port"
stop_process "$restore_process_id"

printf '%s\n' 'Gate 7/7: Successful launch replaces the shell process and propagates application status.'
scenario=$(new_scenario foreground-exec)
marker=$scenario/launched
set +e
(cd "$scenario" && TMPDIR=$scenario/tmp GATE_H2_JAR=$h2_jar GATE_LAUNCH_MARKER=$marker GATE_LAUNCH_EXIT=23 ./start-local.sh >foreground.log 2>&1)
foreground_status=$?
set -e
assert_equal 23 "$foreground_status" 'The launcher did not propagate the foreground application status.'
[ -f "$marker" ] || fail 'The foreground application command was not invoked.'

printf '%s\n' 'All isolated macOS/Linux startup gates passed.'
gate_succeeded=1
