#!/bin/sh

ff_partial_destination=
ff_java_command=
ff_hash_program=
ff_hash_mode=
ff_classpath_file=
ff_h2_matches_file=
ff_inspection_output=
ff_status_lines=
ff_hash_output_file=
ff_backup_lock_directory=
ff_backup_lock_owner_file=
ff_backup_lock_token=
ff_backup_lock_owned=0

ff_step() {
    printf '%s\n' "[family-finance] $*"
}

ff_fail() {
    printf '%s\n' "[family-finance] $*" >&2
    if [ -n "$ff_partial_destination" ] && [ -d "$ff_partial_destination" ]; then
        printf '%s\n' "[family-finance] Incomplete backup evidence was kept at $ff_partial_destination; the application was not started." >&2
    fi
    exit 1
}

ff_cleanup_temporary_files() {
    ff_cleanup_temporary_file "$ff_classpath_file"
    ff_cleanup_temporary_file "$ff_h2_matches_file"
    ff_cleanup_temporary_file "$ff_inspection_output"
    ff_cleanup_temporary_file "$ff_status_lines"
    ff_cleanup_temporary_file "$ff_hash_output_file"
}

ff_cleanup_startup_resources() {
    ff_cleanup_temporary_files
    ff_cleanup_owned_backup_lock
}

ff_owner_marker_is_exact() {
    ff_marker_path=$1
    ff_expected_token=$2
    if [ ! -f "$ff_marker_path" ] || [ -L "$ff_marker_path" ] || [ ! -r "$ff_marker_path" ]; then
        return 1
    fi
    printf '%s\n' "$ff_expected_token" | cmp -s - "$ff_marker_path"
}

ff_cleanup_owned_backup_lock() {
    [ "$ff_backup_lock_owned" -eq 1 ] || return 0
    if ! ff_owner_marker_is_exact "$ff_backup_lock_owner_file" "$ff_backup_lock_token"; then
        printf '%s\n' "[family-finance] Backup lock ownership marker is missing, unreadable, replaced, or changed; the unknown lock was left at $ff_backup_lock_directory" >&2
        return 0
    fi
    if ! rm -f "$ff_backup_lock_owner_file" || ! rmdir "$ff_backup_lock_directory"; then
        printf '%s\n' "[family-finance] Could not release the owned backup lock at $ff_backup_lock_directory; inspect it before retrying." >&2
        return 0
    fi
    ff_backup_lock_owned=0
}

ff_cleanup_temporary_file() {
    ff_cleanup_candidate=$1
    [ -n "$ff_cleanup_candidate" ] || return 0
    case "$ff_cleanup_candidate" in
        "${TMPDIR:-/tmp}"/family-finance-startup-*) rm -f "$ff_cleanup_candidate" ;;
    esac
}

ff_new_temporary_file() {
    ff_new_temp_result=$(mktemp "${TMPDIR:-/tmp}/family-finance-startup-XXXXXX") ||
        ff_fail 'Could not create a temporary file for the migration safety check.'
}

ff_require_maven_wrapper() {
    ff_required_wrapper=$1
    if [ ! -f "$ff_required_wrapper" ] || [ ! -x "$ff_required_wrapper" ]; then
        ff_fail "The executable project Maven Wrapper is missing: $ff_required_wrapper"
    fi
}

ff_require_java_17() {
    ff_java_command=$(command -v java 2>/dev/null || true)
    [ -n "$ff_java_command" ] ||
        ff_fail 'Java was not found. Install a Java 17 or newer JDK, then reopen the terminal.'

    ff_java_version_output=$("$ff_java_command" -version 2>&1) ||
        ff_fail 'Could not run java -version. Install a Java 17 or newer JDK.'
    ff_java_version=$(printf '%s\n' "$ff_java_version_output" |
        sed -n '1{s/.*version "\([^"]*\)".*/\1/p;}')
    [ -n "$ff_java_version" ] ||
        ff_fail 'Could not read the installed Java version.'

    case "$ff_java_version" in
        1.*)
            ff_java_remainder=${ff_java_version#1.}
            ff_java_major=${ff_java_remainder%%.*}
            ;;
        *) ff_java_major=${ff_java_version%%.*} ;;
    esac
    case "$ff_java_major" in
        ''|*[!0-9]*) ff_fail "Could not read the installed Java major version from: $ff_java_version" ;;
    esac
    if [ "$ff_java_major" -lt 17 ]; then
        ff_fail "Java $ff_java_major is too old. Install Java 17 or newer."
    fi
    ff_step "Java $ff_java_major detected."
}

ff_select_hash_program() {
    if command -v sha256sum >/dev/null 2>&1; then
        ff_hash_program=$(command -v sha256sum)
        ff_hash_mode=sha256sum
    elif command -v shasum >/dev/null 2>&1; then
        ff_hash_program=$(command -v shasum)
        ff_hash_mode=shasum
    elif command -v openssl >/dev/null 2>&1; then
        ff_hash_program=$(command -v openssl)
        ff_hash_mode=openssl
    else
        ff_fail 'SHA-256 verification requires sha256sum, shasum, or openssl.'
    fi
}

ff_sha256_file() {
    ff_hash_path=$1
    ff_hash_output_file=$2
    case "$ff_hash_mode" in
        sha256sum)
            "$ff_hash_program" "$ff_hash_path" >"$ff_hash_output_file" 2>&1 ||
                ff_fail "Could not calculate SHA-256 for $ff_hash_path."
            ff_hash=$(awk 'NR == 1 { print tolower($1) }' "$ff_hash_output_file")
            ;;
        shasum)
            "$ff_hash_program" -a 256 "$ff_hash_path" >"$ff_hash_output_file" 2>&1 ||
                ff_fail "Could not calculate SHA-256 for $ff_hash_path."
            ff_hash=$(awk 'NR == 1 { print tolower($1) }' "$ff_hash_output_file")
            ;;
        openssl)
            "$ff_hash_program" dgst -sha256 "$ff_hash_path" >"$ff_hash_output_file" 2>&1 ||
                ff_fail "Could not calculate SHA-256 for $ff_hash_path."
            ff_hash=$(awk 'NR == 1 { print tolower($NF) }' "$ff_hash_output_file")
            ;;
        *) ff_fail 'No SHA-256 implementation was selected.' ;;
    esac
    if [ "${#ff_hash}" -ne 64 ]; then
        ff_fail "SHA-256 returned an invalid digest for $ff_hash_path."
    fi
    case "$ff_hash" in
        *[!0-9a-f]*) ff_fail "SHA-256 returned an invalid digest for $ff_hash_path." ;;
    esac
    printf '%s\n' "$ff_hash"
}

ff_prepare_backup_root() {
    ff_backup_root=$1
    ff_backup_root_created=0
    if [ ! -e "$ff_backup_root" ]; then
        mkdir "$ff_backup_root" ||
            ff_fail "Could not create the migration backup root: $ff_backup_root"
        ff_backup_root_created=1
    elif [ ! -d "$ff_backup_root" ]; then
        ff_fail "The migration backup root is not a directory: $ff_backup_root"
    fi
}

ff_acquire_backup_lock() {
    ff_backup_root=$1
    ff_backup_lock_directory=$ff_backup_root/.family-finance-backup.lock
    ff_backup_lock_owner_file=$ff_backup_lock_directory/OWNER.txt
    ff_backup_lock_token="pid=$$;started=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    if ! mkdir "$ff_backup_lock_directory" 2>/dev/null; then
        ff_fail "Another backup preflight is active, or an unknown stale lock exists at $ff_backup_lock_directory. Startup was refused; inspect the lock before removing it."
    fi
    ff_backup_lock_owned=1
    if ! printf '%s\n' "$ff_backup_lock_token" >"$ff_backup_lock_owner_file"; then
        rmdir "$ff_backup_lock_directory" 2>/dev/null || true
        ff_backup_lock_owned=0
        ff_fail 'Could not record ownership of the migration backup lock.'
    fi
}

ff_release_backup_lock() {
    [ "$ff_backup_lock_owned" -eq 1 ] || return 0
    ff_owner_marker_is_exact "$ff_backup_lock_owner_file" "$ff_backup_lock_token" ||
        ff_fail "Backup lock ownership marker is missing, unreadable, replaced, or changed at $ff_backup_lock_directory. Startup was refused and the unknown lock was preserved."
    rm -f "$ff_backup_lock_owner_file" ||
        ff_fail "Could not remove the owned backup lock marker at $ff_backup_lock_owner_file. Startup was refused."
    rmdir "$ff_backup_lock_directory" ||
        ff_fail "Could not release the owned backup lock at $ff_backup_lock_directory. Startup was refused; inspect it before retrying."
    ff_backup_lock_owned=0
    ff_backup_lock_directory=
    ff_backup_lock_owner_file=
    ff_backup_lock_token=
}

ff_reserve_partial_backup() {
    ff_backup_root=$1

    ff_backup_timestamp=$(date '+%Y%m%d-%H%M%S') ||
        ff_fail 'Could not create a timestamp for the migration backup.'
    ff_backup_attempt=0
    while [ "$ff_backup_attempt" -lt 10000 ]; do
        if [ "$ff_backup_attempt" -eq 0 ]; then
            ff_backup_suffix=
        else
            ff_backup_suffix=-$ff_backup_attempt
        fi
        ff_backup_destination=$ff_backup_root/$ff_backup_timestamp$ff_backup_suffix
        ff_candidate_partial=$ff_backup_destination.partial
        if { [ -e "$ff_backup_destination" ] || [ -h "$ff_backup_destination" ]; } ||
            { [ -e "$ff_candidate_partial" ] || [ -h "$ff_candidate_partial" ]; }; then
            ff_backup_attempt=$((ff_backup_attempt + 1))
            continue
        fi
        if mkdir "$ff_candidate_partial" 2>/dev/null; then
            ff_partial_destination=$ff_candidate_partial
            return 0
        fi
        ff_backup_attempt=$((ff_backup_attempt + 1))
    done
    ff_fail 'Could not reserve a collision-safe migration backup directory.'
}

ff_resolve_h2_jar() {
    ff_wrapper=$1
    ff_classpath_file=$2
    ff_h2_matches_file=$3
    ff_step 'Resolving the project runtime H2 version for the migration safety check...'
    "$ff_wrapper" -q dependency:build-classpath -Dmdep.includeScope=runtime \
        "-Dmdep.outputFile=$ff_classpath_file" ||
        ff_fail 'Could not resolve the project runtime H2 dependency. The database was left unchanged.'
    [ -s "$ff_classpath_file" ] ||
        ff_fail 'The project runtime classpath was empty. The database was left unchanged.'

    tr ':' '\n' <"$ff_classpath_file" |
        awk '/\/com\/h2database\/h2\/2[.]3[.]232\/h2-2[.]3[.]232[.]jar$/ { print }' >"$ff_h2_matches_file" ||
        ff_fail 'Could not parse the project runtime classpath.'
    ff_h2_count=$(awk 'NF { count++ } END { print count+0 }' "$ff_h2_matches_file")
    [ "$ff_h2_count" -eq 1 ] ||
        ff_fail 'Could not resolve exactly one project runtime h2-2.3.232.jar. The database was left unchanged.'
    ff_h2_jar=$(sed -n '1p' "$ff_h2_matches_file")
    [ -f "$ff_h2_jar" ] ||
        ff_fail 'The resolved project runtime h2-2.3.232.jar does not exist. The database was left unchanged.'
}

ff_inspect_flyway_history() {
    ff_database_base=$1
    ff_inspection_output=$2
    ff_status_lines=$3
    ff_jdbc_url=jdbc:h2:file:$ff_database_base\;IFEXISTS=TRUE\;ACCESS_MODE_DATA=r
    ff_history_sql="select case when exists (select 1 from information_schema.tables where table_schema = 'PUBLIC' and table_name = 'flyway_schema_history') then 'FLYWAY_HISTORY_PRESENT' else 'FLYWAY_HISTORY_ABSENT' end as HISTORY_STATUS"
    "$ff_java_command" -cp "$ff_h2_jar" org.h2.tools.Shell \
        -url "$ff_jdbc_url" -user sa -password '' -sql "$ff_history_sql" \
        >"$ff_inspection_output" 2>&1 ||
        ff_fail 'Could not inspect the existing H2 database for Flyway history. The database was left unchanged.'

    awk '{
        sub(/\r$/, "")
        sub(/^[[:space:]]*/, "")
        sub(/[[:space:]]*$/, "")
        if ($0 == "FLYWAY_HISTORY_PRESENT" || $0 == "FLYWAY_HISTORY_ABSENT") print
    }' "$ff_inspection_output" >"$ff_status_lines" ||
        ff_fail 'Could not parse the Flyway-history inspection result.'
    ff_status_count=$(awk 'NF { count++ } END { print count+0 }' "$ff_status_lines")
    [ "$ff_status_count" -eq 1 ] ||
        ff_fail 'H2 inspection did not return exactly one unambiguous Flyway-history status.'
    ff_flyway_status=$(sed -n '1p' "$ff_status_lines")
}

ff_discard_empty_partial_for_migrated_database() {
    rmdir "$ff_partial_destination" ||
        ff_fail 'Could not remove the empty migration-check reservation for an already-migrated database.'
    ff_partial_destination=
}

ff_copy_verified_backup() {
    ff_data_directory=$1
    ff_primary_hash_before_inspection=$2
    ff_hash_output_file=$3
    ff_manifest=$ff_partial_destination/RESTORE.txt
    {
        printf '%s\n' 'Family Finance pre-migration H2 backup'
        printf 'Created: %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
        printf '%s\n' 'Restore only while the application is stopped: copy every database file in this folder back to data/.'
        printf '%s\n' 'SHA256  File'
    } >"$ff_manifest" || ff_fail 'Could not create RESTORE.txt in the partial backup.'

    ff_copied_primary=0
    for ff_source_file in "$ff_data_directory"/family-finance.*.db; do
        if [ ! -e "$ff_source_file" ] && [ ! -h "$ff_source_file" ]; then
            continue
        fi
        ff_file_name=${ff_source_file##*/}
        case "$ff_file_name" in
            *'
'*) ff_fail 'Database companion names containing a newline cannot be represented safely in RESTORE.txt.' ;;
        esac
        ff_source_hash_before=$(ff_sha256_file "$ff_source_file" "$ff_hash_output_file")
        ff_copy_file=$ff_partial_destination/$ff_file_name
        cp "$ff_source_file" "$ff_copy_file" ||
            ff_fail "Could not copy database companion $ff_file_name."
        ff_source_hash_after=$(ff_sha256_file "$ff_source_file" "$ff_hash_output_file")
        ff_copy_hash=$(ff_sha256_file "$ff_copy_file" "$ff_hash_output_file")
        if [ "$ff_source_hash_before" != "$ff_source_hash_after" ]; then
            ff_fail "Database companion $ff_file_name changed while it was being backed up."
        fi
        if [ "$ff_source_hash_before" != "$ff_copy_hash" ]; then
            ff_fail "SHA-256 verification failed for database companion $ff_file_name."
        fi
        if [ "$ff_file_name" = family-finance.mv.db ]; then
            ff_copied_primary=1
            if [ "$ff_copy_hash" != "$ff_primary_hash_before_inspection" ]; then
                ff_fail 'The primary database changed between read-only inspection and backup verification.'
            fi
        fi
        printf '%s  %s\n' "$ff_copy_hash" "$ff_file_name" >>"$ff_manifest" ||
            ff_fail "Could not record database companion $ff_file_name in RESTORE.txt."
    done
    [ "$ff_copied_primary" -eq 1 ] ||
        ff_fail 'The nonempty primary database disappeared before backup completed.'

    if [ -e "$ff_backup_destination" ] || [ -h "$ff_backup_destination" ]; then
        ff_fail "The completed backup destination appeared during backup: $ff_backup_destination"
    fi
    ff_original_partial_destination=$ff_partial_destination
    ff_partial_name=${ff_original_partial_destination##*/}
    mv "$ff_original_partial_destination" "$ff_backup_destination" ||
        ff_fail 'Could not atomically publish the verified migration backup.'

    ff_expected_nested_partial=$ff_backup_destination/$ff_partial_name
    ff_nested_partial=
    if [ -d "$ff_expected_nested_partial" ] || [ -h "$ff_expected_nested_partial" ]; then
        ff_nested_partial=$ff_expected_nested_partial
        ff_partial_destination=$ff_expected_nested_partial
    else
        for ff_nested_candidate in "$ff_backup_destination"/*.partial; do
            if [ -d "$ff_nested_candidate" ] || [ -h "$ff_nested_candidate" ]; then
                ff_nested_partial=$ff_nested_candidate
                break
            fi
        done
    fi
    if [ -n "$ff_nested_partial" ]; then
        ff_fail "Atomic backup publication was obstructed by an unexpected destination. The completed destination was not accepted."
    fi
    if [ -e "$ff_original_partial_destination" ] || [ -h "$ff_original_partial_destination" ] ||
        [ ! -d "$ff_backup_destination" ] || [ ! -f "$ff_backup_destination/RESTORE.txt" ]; then
        ff_fail 'The published backup did not have the required final root shape. Startup was refused.'
    fi
    ff_partial_destination=
    ff_step "Created a verified pre-migration backup at $ff_backup_destination"
}

ff_prepare_local_database() {
    ff_project_root=$1
    ff_wrapper=$2
    ff_data_directory=$ff_project_root/data
    ff_primary_database=$ff_data_directory/family-finance.mv.db
    ff_backup_root=$ff_project_root/data-backups

    if [ ! -s "$ff_primary_database" ]; then
        return 0
    fi

    ff_prepare_backup_root "$ff_backup_root"
    ff_acquire_backup_lock "$ff_backup_root"
    trap ff_cleanup_startup_resources 0
    trap 'exit 130' INT
    trap 'exit 143' TERM
    ff_reserve_partial_backup "$ff_backup_root"
    ff_select_hash_program
    ff_new_temporary_file
    ff_classpath_file=$ff_new_temp_result
    ff_new_temporary_file
    ff_h2_matches_file=$ff_new_temp_result
    ff_new_temporary_file
    ff_inspection_output=$ff_new_temp_result
    ff_new_temporary_file
    ff_status_lines=$ff_new_temp_result
    ff_new_temporary_file
    ff_hash_output_file=$ff_new_temp_result
    ff_primary_hash_before=$(ff_sha256_file "$ff_primary_database" "$ff_hash_output_file")
    ff_resolve_h2_jar "$ff_wrapper" "$ff_classpath_file" "$ff_h2_matches_file"
    ff_inspect_flyway_history "$ff_project_root/data/family-finance" \
        "$ff_inspection_output" "$ff_status_lines"
    ff_primary_hash_after=$(ff_sha256_file "$ff_primary_database" "$ff_hash_output_file")
    if [ "$ff_primary_hash_before" != "$ff_primary_hash_after" ]; then
        ff_fail 'Read-only Flyway-history inspection changed the primary database; startup was refused.'
    fi

    if [ "$ff_flyway_status" = FLYWAY_HISTORY_PRESENT ]; then
        ff_discard_empty_partial_for_migrated_database
        ff_release_backup_lock
        if [ "$ff_backup_root_created" -eq 1 ]; then
            rmdir "$ff_backup_root" 2>/dev/null || true
        fi
        ff_step 'Existing database already has Flyway history; no migration backup is required.'
        return 0
    fi
    [ "$ff_flyway_status" = FLYWAY_HISTORY_ABSENT ] ||
        ff_fail 'Flyway-history inspection returned an unsupported status.'

    ff_copy_verified_backup "$ff_data_directory" "$ff_primary_hash_before" "$ff_hash_output_file"
    ff_release_backup_lock
}
