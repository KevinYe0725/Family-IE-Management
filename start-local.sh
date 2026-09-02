#!/bin/sh

set -eu

project_root=$(CDPATH= cd "$(dirname "$0")" && pwd -P)
preflight_script=$project_root/scripts/unix-startup-preflight.sh
maven_wrapper=$project_root/mvnw

if [ ! -f "$preflight_script" ]; then
    printf '%s\n' "[family-finance] Startup safety helper is missing: $preflight_script" >&2
    exit 1
fi

# shellcheck source=scripts/unix-startup-preflight.sh
. "$preflight_script"

cd "$project_root"
ff_require_maven_wrapper "$maven_wrapper"
ff_require_java_17
ff_prepare_local_database "$project_root" "$maven_wrapper"
ff_cleanup_temporary_files
trap - 0

printf '%s\n' '[family-finance] Starting Spring Boot at http://127.0.0.1:8080'
printf '%s\n' '[family-finance] Press Ctrl+C in this terminal to stop the application.'
exec "$maven_wrapper" spring-boot:run "$@"
