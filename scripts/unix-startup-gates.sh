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

database_hash_snapshot() {
    database_hash_directory=$1
    for database_hash_file in "$database_hash_directory"/family-finance.*.db; do
        [ -f "$database_hash_file" ] || continue
        printf '%s  %s\n' "$(sha256_file "$database_hash_file")" "${database_hash_file##*/}"
    done | sort
}

new_scenario() {
    scenario=$session_root/$1
    assert_under_session_root "$scenario"
    mkdir -p "$scenario/scripts" "$scenario/.mvn/wrapper" "$scenario/tmp" \
        "$scenario/src/main/resources/db/migration"
    cp "$source_root/start-local.sh" "$scenario/start-local.sh"
    cp "$source_root/scripts/unix-startup-preflight.sh" "$scenario/scripts/unix-startup-preflight.sh"
    cp "$source_root/.mvn/wrapper/maven-wrapper.jar" "$scenario/.mvn/wrapper/maven-wrapper.jar"
    cp "$source_root/.mvn/wrapper/maven-wrapper.properties" "$scenario/.mvn/wrapper/maven-wrapper.properties"
    cp "$source_root"/src/main/resources/db/migration/V*.sql \
        "$scenario/src/main/resources/db/migration/"
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

migration_fixture() {
    migration_fixture_scenario=$1
    migration_fixture_action=$2
    migration_fixture_database_base=$migration_fixture_scenario/data/family-finance
    assert_under_session_root "$migration_fixture_database_base"
    java -cp "$fixture_classpath" com.familyfinance.migration.MigrationStateFixtureCli \
        "$migration_fixture_database_base" "$migration_fixture_action" >/dev/null 2>&1 ||
        fail "Could not prepare migration state '$migration_fixture_action' in $migration_fixture_scenario"
}

migration_history_snapshot() {
    migration_snapshot_scenario=$1
    migration_snapshot_database_base=$migration_snapshot_scenario/data/family-finance
    java -cp "$fixture_classpath" com.familyfinance.migration.MigrationStateFixtureCli \
        "$migration_snapshot_database_base" print-history-snapshot 2>/dev/null
}

run_refused_history_state() {
    refused_state_name=$1
    refused_state_make_action=$2
    refused_state_assert_action=$3
    refused_state_message=$4
    refused_state_scenario=$(new_scenario "history-$refused_state_name")
    new_stage_one_fixture "$refused_state_scenario"
    migration_fixture "$refused_state_scenario" migrate-to-7
    migration_fixture "$refused_state_scenario" "$refused_state_make_action"
    refused_state_history_before=$(migration_history_snapshot "$refused_state_scenario")
    refused_state_files_before=$(database_hash_snapshot "$refused_state_scenario/data")
    refused_state_marker=$refused_state_scenario/launched
    set +e
    (
        cd "$refused_state_scenario"
        TMPDIR=$refused_state_scenario/tmp GATE_H2_JAR=$h2_jar \
            GATE_LAUNCH_MARKER=$refused_state_marker ./start-local.sh
    ) >"$refused_state_scenario/refused.log" 2>&1
    refused_state_status=$?
    set -e
    [ "$refused_state_status" -ne 0 ] || fail "The launcher accepted $refused_state_name history."
    [ ! -e "$refused_state_marker" ] || fail "The application launched with $refused_state_name history."
    assert_equal 1 "$(count_completed_backups "$refused_state_scenario/data-backups")" \
        "$refused_state_name history did not receive the documented verified state backup."
    assert_equal 0 "$(count_partial_backups "$refused_state_scenario/data-backups")" \
        "$refused_state_name refusal left partial backup evidence."
    grep -F "$refused_state_message" "$refused_state_scenario/refused.log" >/dev/null ||
        fail "$refused_state_name refusal omitted its explicit diagnostic."
    refused_state_files_after=$(database_hash_snapshot "$refused_state_scenario/data")
    refused_state_history_after=$(migration_history_snapshot "$refused_state_scenario")
    assert_equal "$refused_state_files_before" "$refused_state_files_after" \
        "$refused_state_name refusal changed the complete database companion hash snapshot."
    assert_equal "$refused_state_history_before" "$refused_state_history_after" \
        "$refused_state_name refusal changed the complete Flyway history snapshot."
    migration_fixture "$refused_state_scenario" "$refused_state_assert_action"
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

wait_for_file() {
    awaited_file=$1
    description=$2
    attempts=0
    while [ "$attempts" -lt 30 ]; do
        [ -f "$awaited_file" ] && return 0
        attempts=$((attempts + 1))
        sleep 1
    done
    fail "$description was not created within 30 seconds."
}

run_owner_marker_tamper() {
    tamper_name=$1
    scenario=$(new_scenario "owner-marker-$tamper_name")
    new_stage_one_fixture "$scenario"
    real_copy=$(command -v cp)
    blocking_copy_bin=$scenario/blocking-copy
    copy_ready=$scenario/copy.ready
    copy_release=$scenario/copy.release
    mkdir "$blocking_copy_bin"
    cat >"$blocking_copy_bin/cp" <<'OWNER_MARKER_BLOCKING_COPY'
#!/bin/sh
set -eu
: >"$GATE_COPY_READY"
attempts=0
while [ ! -f "$GATE_COPY_RELEASE" ] && [ "$attempts" -lt 30 ]; do
    attempts=$((attempts + 1))
    sleep 1
done
[ -f "$GATE_COPY_RELEASE" ] || exit 49
exec "$GATE_REAL_CP" "$@"
OWNER_MARKER_BLOCKING_COPY
    chmod +x "$blocking_copy_bin/cp"
    marker=$scenario/launched
    (
        cd "$scenario"
        PATH=$blocking_copy_bin:$PATH GATE_REAL_CP=$real_copy \
            GATE_COPY_READY=$copy_ready GATE_COPY_RELEASE=$copy_release \
            TMPDIR=$scenario/tmp GATE_H2_JAR=$h2_jar GATE_LAUNCH_MARKER=$marker \
            ./start-local.sh
    ) >"$scenario/owner-marker-$tamper_name.log" 2>&1 &
    preflight_pid=$!
    wait_for_file "$copy_ready" "The $tamper_name owner-marker copy-ready marker"
    lock_directory=$scenario/data-backups/.family-finance-backup.lock
    owner_marker=$lock_directory/OWNER.txt
    [ -f "$owner_marker" ] || fail "The $tamper_name scenario did not create a regular owner marker."
    owner_token=$(sed -n '1p' "$owner_marker")

    case "$tamper_name" in
        appended)
            printf '%s\n' 'unexpected-appended-owner-bytes' >>"$owner_marker"
            ;;
        no-newline)
            printf '%s' "$owner_token" >"$owner_marker"
            ;;
        symlink)
            replacement=$scenario/replacement-owner.txt
            printf '%s\n' "$owner_token" >"$replacement"
            mv "$owner_marker" "$scenario/original-owner.txt"
            ln -s "$replacement" "$owner_marker"
            ;;
        unreadable)
            chmod 000 "$owner_marker"
            if [ -r "$owner_marker" ]; then
                chmod 600 "$owner_marker"
                : >"$copy_release"
                wait "$preflight_pid"
                printf '%s\n' 'Unreadable owner-marker check skipped: this effective user can read mode 000 files.'
                return 0
            fi
            ;;
        *) fail "Unknown owner-marker tamper scenario: $tamper_name" ;;
    esac

    : >"$copy_release"
    set +e
    wait "$preflight_pid"
    preflight_status=$?
    set -e
    [ "$preflight_status" -ne 0 ] || fail "The launcher accepted the $tamper_name owner marker."
    [ ! -e "$marker" ] || fail "The application launched with the $tamper_name owner marker."
    [ -d "$lock_directory" ] || fail "The $tamper_name owner marker did not preserve its unknown lock directory."
    if [ "$tamper_name" = symlink ]; then
        [ -L "$owner_marker" ] || fail 'The replaced owner-marker symlink was removed.'
    else
        [ -f "$owner_marker" ] || fail "The $tamper_name owner marker was removed."
    fi
    if [ "$tamper_name" = unreadable ]; then
        chmod 600 "$owner_marker"
    fi
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

printf '%s\n' 'Gate 1/14: Java 17 and the project wrapper are mandatory.'
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

scenario=$(new_scenario 'java executable path')
spaced_java_bin=$scenario/java-bin
mkdir "$spaced_java_bin"
cat >"$spaced_java_bin/java" <<'SPACED_JAVA'
#!/bin/sh
printf '%s\n' 'openjdk version "17.0.18"' >&2
exit 0
SPACED_JAVA
chmod +x "$spaced_java_bin/java"
spaced_java_parent=$scenario/java\ tools
mkdir "$spaced_java_parent"
mv "$spaced_java_bin" "$spaced_java_parent/bin"
marker=$scenario/launched
if ! (cd "$scenario" && TMPDIR=$scenario/tmp PATH="$spaced_java_parent/bin:$PATH" GATE_H2_JAR=$h2_jar GATE_LAUNCH_MARKER=$marker ./start-local.sh >java-path.log 2>&1); then
    fail 'The launcher could not invoke a valid Java 17 executable whose path contains spaces.'
fi
[ -f "$marker" ] || fail 'The spaced-path Java 17 scenario did not reach application launch.'

printf '%s\n' 'Gate 2/14: No database launches without creating a backup path.'
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

printf '%s\n' 'Gate 3/14: Inspection and H2 resolution failures retain partial evidence and prevent launch.'
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

printf '%s\n' 'Gate 4/14: Copy and hash failures retain partial data and prevent launch.'
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

printf '%s\n' 'Gate 5/14: A publish-time destination collision preserves nested partial evidence and prevents launch.'
scenario=$(new_scenario publish-time-collision)
new_stage_one_fixture "$scenario"
real_move=$(command -v mv)
delayed_move_bin=$scenario/delayed-move
publish_ready=$scenario/publish.ready
watcher_ready=$scenario/watcher.ready
destination_file=$scenario/collision-destination.txt
mkdir "$delayed_move_bin"
cat >"$delayed_move_bin/mv" <<'DELAYED_MOVE'
#!/bin/sh
set -eu
: >"$GATE_PUBLISH_READY"
attempts=0
while [ ! -f "$GATE_WATCHER_READY" ] && [ "$attempts" -lt 30 ]; do
    attempts=$((attempts + 1))
    sleep 1
done
[ -f "$GATE_WATCHER_READY" ] || exit 45
exec "$GATE_REAL_MV" "$@"
DELAYED_MOVE
chmod +x "$delayed_move_bin/mv"
(
    attempts=0
    while [ ! -f "$publish_ready" ] && [ "$attempts" -lt 30 ]; do
        attempts=$((attempts + 1))
        sleep 1
    done
    [ -f "$publish_ready" ] || exit 46
    [ -f "$scenario/data-backups/.family-finance-backup.lock/OWNER.txt" ] || exit 48
    collision_partial=
    for candidate in "$scenario/data-backups"/*.partial; do
        [ -d "$candidate" ] || continue
        collision_partial=$candidate
        break
    done
    [ -n "$collision_partial" ] || exit 47
    collision_destination=${collision_partial%.partial}
    mkdir "$collision_destination"
    printf '%s\n' 'external collision sentinel' >"$collision_destination/collision-sentinel.txt"
    printf '%s\n' "$collision_destination" >"$destination_file"
    : >"$watcher_ready"
) &
watcher_pid=$!
marker=$scenario/launched
set +e
(
    cd "$scenario"
    PATH=$delayed_move_bin:$PATH GATE_REAL_MV=$real_move \
        GATE_PUBLISH_READY=$publish_ready GATE_WATCHER_READY=$watcher_ready \
        TMPDIR=$scenario/tmp GATE_H2_JAR=$h2_jar GATE_LAUNCH_MARKER=$marker \
        ./start-local.sh
) >"$scenario/publish-collision.log" 2>&1
publish_collision_status=$?
set -e
wait "$watcher_pid"
collision_destination=$(sed -n '1p' "$destination_file")
collision_partial_name=$(basename "$collision_destination").partial
[ "$publish_collision_status" -ne 0 ] || fail 'The launcher accepted a destination that appeared between precheck and publication.'
[ ! -e "$marker" ] || fail 'The application launched after a publish-time destination collision.'
assert_equal 0 "$(count_completed_backups "$scenario/data-backups")" 'A publish-time collision was falsely reported as a completed backup.'
[ -d "$collision_destination/$collision_partial_name" ] || fail 'The nested partial backup was not retained at its actual post-move location.'
assert_equal 'external collision sentinel' "$(sed -n '1p' "$collision_destination/collision-sentinel.txt")" 'Publication overwrote the external collision sentinel.'
grep -F "$collision_destination/$collision_partial_name" "$scenario/publish-collision.log" >/dev/null ||
    fail 'The publish-time collision did not report the retained nested partial path.'

printf '%s\n' 'Gate 6/14: Active and unknown stale locks fail closed without competing publication.'
scenario=$(new_scenario concurrent-preflights)
new_stage_one_fixture "$scenario"
real_copy=$(command -v cp)
blocking_copy_bin=$scenario/blocking-copy
copy_claim=$scenario/copy.claim
copy_ready=$scenario/copy.ready
copy_release=$scenario/copy.release
mkdir "$blocking_copy_bin"
cat >"$blocking_copy_bin/cp" <<'BLOCKING_COPY'
#!/bin/sh
set -eu
if mkdir "$GATE_COPY_CLAIM" 2>/dev/null; then
    : >"$GATE_COPY_READY"
    attempts=0
    while [ ! -f "$GATE_COPY_RELEASE" ] && [ "$attempts" -lt 30 ]; do
        attempts=$((attempts + 1))
        sleep 1
    done
    [ -f "$GATE_COPY_RELEASE" ] || exit 44
fi
exec "$GATE_REAL_CP" "$@"
BLOCKING_COPY
chmod +x "$blocking_copy_bin/cp"
first_marker=$scenario/first-launched
second_marker=$scenario/second-launched
(
    cd "$scenario"
    PATH=$blocking_copy_bin:$PATH GATE_REAL_CP=$real_copy GATE_COPY_CLAIM=$copy_claim \
        GATE_COPY_READY=$copy_ready GATE_COPY_RELEASE=$copy_release \
        TMPDIR=$scenario/tmp GATE_H2_JAR=$h2_jar GATE_LAUNCH_MARKER=$first_marker \
        ./start-local.sh
) >"$scenario/first-preflight.log" 2>&1 &
first_preflight_pid=$!
wait_for_file "$copy_ready" 'The first preflight copy-ready marker'
active_lock=$scenario/data-backups/.family-finance-backup.lock
[ -f "$active_lock/OWNER.txt" ] || fail 'The first preflight did not hold an owned backup lock while copying.'
active_lock_owner=$(sed -n '1p' "$active_lock/OWNER.txt")
set +e
(
    cd "$scenario"
    PATH=$blocking_copy_bin:$PATH GATE_REAL_CP=$real_copy GATE_COPY_CLAIM=$copy_claim \
        GATE_COPY_READY=$copy_ready GATE_COPY_RELEASE=$copy_release \
        TMPDIR=$scenario/tmp GATE_H2_JAR=$h2_jar GATE_LAUNCH_MARKER=$second_marker \
        ./start-local.sh
) >"$scenario/second-preflight.log" 2>&1
second_preflight_status=$?
set -e
[ -f "$active_lock/OWNER.txt" ] || fail 'The concurrent preflight removed the active owner lock.'
assert_equal "$active_lock_owner" "$(sed -n '1p' "$active_lock/OWNER.txt")" 'The concurrent preflight changed active lock ownership.'
: >"$copy_release"
wait "$first_preflight_pid"
[ "$second_preflight_status" -ne 0 ] || fail 'A second preflight ran concurrently instead of failing closed on the active backup lock.'
[ -f "$first_marker" ] || fail 'The lock-owning preflight did not reach application launch.'
[ ! -e "$second_marker" ] || fail 'The concurrent preflight reached application launch.'
assert_equal 1 "$(count_completed_backups "$scenario/data-backups")" 'Concurrent preflights published more than one completed backup.'
assert_equal 0 "$(count_partial_backups "$scenario/data-backups")" 'Concurrent preflights left a partial backup after the owner completed.'
[ ! -e "$active_lock" ] || fail 'The lock-owning preflight did not release its lock after publication.'

scenario=$(new_scenario unknown-stale-lock)
new_stage_one_fixture "$scenario"
unknown_lock=$scenario/data-backups/.family-finance-backup.lock
mkdir -p "$unknown_lock"
printf '%s\n' 'unknown-owner-must-remain' >"$unknown_lock/OWNER.txt"
marker=$scenario/launched
set +e
(
    cd "$scenario"
    TMPDIR=$scenario/tmp GATE_H2_JAR=$h2_jar GATE_LAUNCH_MARKER=$marker ./start-local.sh
) >"$scenario/unknown-stale-lock.log" 2>&1
unknown_lock_status=$?
set -e
[ "$unknown_lock_status" -ne 0 ] || fail 'The launcher accepted an unknown stale backup lock.'
[ ! -e "$marker" ] || fail 'The application launched while an unknown backup lock existed.'
assert_equal 'unknown-owner-must-remain' "$(sed -n '1p' "$unknown_lock/OWNER.txt")" 'The launcher deleted or changed an unknown backup lock.'
assert_equal 0 "$(count_partial_backups "$scenario/data-backups")" 'The stale-lock refusal created a partial backup.'

printf '%s\n' 'Gate 7/14: Owner-marker corruption is preserved by normal release and EXIT cleanup.'
run_owner_marker_tamper appended
run_owner_marker_tamper no-newline
run_owner_marker_tamper symlink
run_owner_marker_tamper unreadable

printf '%s\n' 'Gate 8/14: Legacy backup covers pristine primary, companions, manifest, and collision handling.'
scenario=$(new_scenario 'backup restore')
backup_restore_scenario=$scenario
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

printf '%s\n' 'Gate 9/14: A V7 database is backed up before its pending V8 migration.'
pending_scenario=$(new_scenario v7-pending-migration)
new_stage_one_fixture "$pending_scenario"
migration_fixture "$pending_scenario" migrate-to-7
pending_marker=$pending_scenario/launched
(
    cd "$pending_scenario"
    TMPDIR=$pending_scenario/tmp GATE_H2_JAR=$h2_jar GATE_LAUNCH_MARKER=$pending_marker \
        ./start-local.sh
) >"$pending_scenario/pending.log" 2>&1
[ -f "$pending_marker" ] || fail 'The V7 pending-migration scenario did not reach application launch.'
assert_equal 1 "$(count_completed_backups "$pending_scenario/data-backups")" \
    'The V7 pending-migration database did not receive exactly one verified backup.'
grep -F 'behind repository migration V8' "$pending_scenario/pending.log" >/dev/null ||
    fail 'The V7 pending-migration branch was not reported explicitly.'

printf '%s\n' 'Gate 10/14: Current V8 skips migration backup.'
scenario=$backup_restore_scenario
completed_before_restart=$(count_completed_backups "$scenario/data-backups")
restart_port=$(get_free_port)
restart_log=$scenario/restart.log
start_application "$scenario" "$restart_port" "$restart_log"
restart_process_id=$started_process_id
wait_for_ready "$restart_port" "$restart_process_id" "$restart_log"
stop_process "$restart_process_id"
assert_equal "$completed_before_restart" "$(count_completed_backups "$scenario/data-backups")" 'Already-migrated restart created another backup.'
assert_equal 0 "$(count_partial_backups "$scenario/data-backups")" 'Already-migrated restart left a partial directory.'
grep -F 'is current at repository migration V8; no migration backup is required' "$restart_log" >/dev/null ||
    fail 'Current V8 restart did not take the explicit skip branch.'

printf '%s\n' 'Gate 11/14: Restored legacy backup preserves login plus 12 rows.'
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

printf '%s\n' 'Gate 12/14: Failed V7 backs up and refuses, then repaired V7 retries without stale guards.'
failed_scenario=$(new_scenario failed-v7-recovery)
new_stage_one_fixture "$failed_scenario"
migration_fixture "$failed_scenario" migrate-to-6
migration_fixture "$failed_scenario" fail-v7-budget
failed_marker=$failed_scenario/launched
set +e
(
    cd "$failed_scenario"
    TMPDIR=$failed_scenario/tmp GATE_H2_JAR=$h2_jar GATE_LAUNCH_MARKER=$failed_marker \
        ./start-local.sh
) >"$failed_scenario/failed.log" 2>&1
failed_status=$?
set -e
[ "$failed_status" -ne 0 ] || fail 'The launcher accepted a failed V7 history row.'
[ ! -e "$failed_marker" ] || fail 'The application launched with failed V7 history.'
assert_equal 1 "$(count_completed_backups "$failed_scenario/data-backups")" \
    'Failed V7 history did not receive exactly one verified state backup.'
grep -F 'repair the invalid data, then run Flyway repair' "$failed_scenario/failed.log" >/dev/null ||
    fail 'Failed V7 refusal omitted data-repair and Flyway-repair remediation.'

migration_fixture "$failed_scenario" repair-v7-budget
recovery_port=$(get_free_port)
recovery_log=$failed_scenario/recovery.log
start_application "$failed_scenario" "$recovery_port" "$recovery_log"
recovery_process_id=$started_process_id
wait_for_ready "$recovery_port" "$recovery_process_id" "$recovery_log"
stop_process "$recovery_process_id"
migration_fixture "$failed_scenario" assert-version-8
assert_equal 2 "$(count_completed_backups "$failed_scenario/data-backups")" \
    'Repaired V6 state did not receive a fresh verified migration retry backup.'
assert_equal 0 "$(count_partial_backups "$failed_scenario/data-backups")" \
    'Repaired V7 retry left partial backup evidence.'

printf '%s\n' 'Gate 13/14: Future and ambiguous histories back up, refuse launch, and remain unchanged.'
run_refused_history_state FUTURE make-future-history assert-future-history \
    'migration newer than repository V8'
run_refused_history_state AMBIGUOUS make-ambiguous-history assert-ambiguous-history \
    'Flyway history is ambiguous'

printf '%s\n' 'Gate 14/14: Successful launch replaces the shell process and propagates application status.'
scenario=$(new_scenario foreground-exec)
marker=$scenario/launched
set +e
(cd "$scenario" && TMPDIR=$scenario/tmp GATE_H2_JAR=$h2_jar GATE_LAUNCH_MARKER=$marker GATE_LAUNCH_EXIT=23 ./start-local.sh >foreground.log 2>&1)
foreground_status=$?
set -e
assert_equal 23 "$foreground_status" 'The launcher did not propagate the foreground application status.'
[ -f "$marker" ] || fail 'The foreground application command was not invoked.'

printf '%s\n' 'All 14 isolated macOS/Linux startup gates passed.'
gate_succeeded=1
