#!/usr/bin/env bash

set -euo pipefail

GRADLE_VERSION=9.6.0
GRADLE_SHA256=bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01

ANDROID_PLATFORM_ARCHIVE=platform-37.0_r02.zip
ANDROID_PLATFORM_SHA1=ed8ebf7f8822a4de5686d427f237d2fa30ff7410
ANDROID_PLATFORM_SHA256=840b23e827f96e64aea4c89a1194aac3dc5f6bad37edb231c5c795d890330e8d
ANDROID_PLATFORM_SIZE=67281901

ANDROID_BUILD_TOOLS_ARCHIVE=build-tools_r36_linux.zip
ANDROID_BUILD_TOOLS_SHA1=b0b6376977657e8ad9b969bacf4093601da2c6fb
ANDROID_BUILD_TOOLS_SHA256=5d9ac77fb6ff43d9da518a337b4fcf8f9097113df531d99ccefe80ef7ce8250b
ANDROID_BUILD_TOOLS_SIZE=63737259

ANDROID_PLATFORM_TOOLS_ARCHIVE=platform-tools_r37.0.1-linux.zip
ANDROID_PLATFORM_TOOLS_SHA1=477254aa5f903c15cf51001717bdf347fb6b53e0
ANDROID_PLATFORM_TOOLS_SHA256=d230f13842f60f782a8645f9c813f8f845bf36089ea7289f28c48f17979313f1
ANDROID_PLATFORM_TOOLS_SIZE=9054187

SCRIPT_DIRECTORY=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_DIRECTORY=$(CDPATH= cd -- "$SCRIPT_DIRECTORY/.." && pwd)
PROJECT_PARENT=$(dirname -- "$PROJECT_DIRECTORY")
TOOLCHAIN_ROOT=${PLAINPOD_TOOLCHAIN_ROOT:-"$PROJECT_PARENT/plainpoddroid-toolchains"}
DOWNLOAD_ROOT="$TOOLCHAIN_ROOT/downloads"
QUARANTINE_ROOT="$TOOLCHAIN_ROOT/quarantine"
COMPILER_ROOT=${PLAINPOD_COMPILER_ROOT:-/tmp/plainpoddroid-jdk17-compiler}

require_command() {
    local required_command=$1
    if ! command -v "$required_command" >/dev/null 2>&1; then
        printf 'Required command is unavailable: %s\n' "$required_command" >&2
        exit 1
    fi
}

for required_command in awk basename chmod cp curl date dirname find grep ln \
    mkdir mktemp mv readlink rmdir sed sha1sum sha256sum sort unzip wc; do
    require_command "$required_command"
done

case "$TOOLCHAIN_ROOT" in
    ""|/|"$PROJECT_DIRECTORY")
        printf 'Refusing unsafe toolchain root: %s\n' "$TOOLCHAIN_ROOT" >&2
        exit 1
        ;;
esac
case "$COMPILER_ROOT" in
    ""|/|"$PROJECT_DIRECTORY")
        printf 'Refusing unsafe compiler root: %s\n' "$COMPILER_ROOT" >&2
        exit 1
        ;;
esac

mkdir -p "$TOOLCHAIN_ROOT" "$DOWNLOAD_ROOT" "$QUARANTINE_ROOT"

quarantine_path() {
    local path=$1
    local timestamp
    local target
    if [ ! -e "$path" ] && [ ! -L "$path" ]; then
        return
    fi
    timestamp=$(date -u +%Y%m%dT%H%M%SZ)
    target="$QUARANTINE_ROOT/$(basename -- "$path").$timestamp.$$"
    printf 'Quarantining unexpected existing path: %s -> %s\n' "$path" "$target" >&2
    mv -- "$path" "$target"
}

verify_archive() {
    local archive=$1
    local algorithm=$2
    local expected_hash=$3
    local expected_size=$4
    local actual_size
    local actual_hash

    if [ ! -f "$archive" ]; then
        return 1
    fi

    if [ -n "$expected_size" ]; then
        actual_size=$(wc -c < "$archive" | awk '{print $1}')
        if [ "$actual_size" != "$expected_size" ]; then
            return 1
        fi
    fi

    case "$algorithm" in
        sha1) actual_hash=$(sha1sum "$archive" | awk '{print $1}') ;;
        sha256) actual_hash=$(sha256sum "$archive" | awk '{print $1}') ;;
        *) printf 'Unsupported hash algorithm: %s\n' "$algorithm" >&2; exit 1 ;;
    esac
    [ "$actual_hash" = "$expected_hash" ]
}

fetch_archive() {
    local url=$1
    local archive=$2
    local algorithm=$3
    local expected_hash=$4
    local expected_size=$5
    local partial="$archive.part"

    if verify_archive "$archive" "$algorithm" "$expected_hash" "$expected_size"; then
        printf 'Verified cached archive: %s\n' "$(basename -- "$archive")"
        return
    fi

    if [ -e "$archive" ]; then
        quarantine_path "$archive"
    fi

    if verify_archive "$partial" "$algorithm" "$expected_hash" "$expected_size"; then
        printf 'Promoting verified completed partial: %s\n' "$(basename -- "$partial")"
        mv -- "$partial" "$archive"
        return
    fi

    printf 'Downloading %s\n' "$url"
    curl --fail --location --progress-bar --retry 5 --retry-delay 2 --retry-all-errors \
        --continue-at - --output "$partial" "$url"

    if ! verify_archive "$partial" "$algorithm" "$expected_hash" "$expected_size"; then
        printf 'Downloaded archive failed %s verification: %s\n' \
            "$algorithm" "$partial" >&2
        exit 1
    fi
    mv -- "$partial" "$archive"
}

single_archive_root() {
    local archive=$1
    local roots
    local root_count
    roots=$(unzip -Z1 "$archive" | awk -F/ 'NF > 1 && $1 != "" {print $1}' | sort -u)
    root_count=$(printf '%s\n' "$roots" | awk 'NF {count++} END {print count + 0}')
    if [ "$root_count" != 1 ]; then
        printf 'Expected one top-level directory in %s; found %s\n' \
            "$archive" "$root_count" >&2
        exit 1
    fi
    printf '%s\n' "$roots"
}

install_archive() {
    local archive=$1
    local destination=$2
    local required_relative_path=$3
    local expected_marker=$4
    local accepted_legacy_marker=${5:-}
    local marker="$destination/.plainpod-archive-hash"
    local installed_marker
    local stage
    local archive_root
    local extracted

    if [ -f "$required_relative_path" ] && [ -f "$marker" ]; then
        installed_marker=$(sed -n '1p' "$marker")
        if [ "$installed_marker" = "$expected_marker" ] || \
            { [ -n "$accepted_legacy_marker" ] && \
                [ "$installed_marker" = "$accepted_legacy_marker" ]; }; then
            printf 'Verified installed tool: %s\n' "$destination"
            return
        fi
    fi

    stage=$(mktemp -d "$TOOLCHAIN_ROOT/.extract.XXXXXX")
    archive_root=$(single_archive_root "$archive")
    unzip -q "$archive" -d "$stage"
    extracted="$stage/$archive_root"
    if [ ! -e "$extracted" ]; then
        printf 'Archive root was not extracted: %s\n' "$extracted" >&2
        exit 1
    fi

    quarantine_path "$destination"
    mkdir -p "$(dirname -- "$destination")"
    mv -- "$extracted" "$destination"
    printf '%s\n' "$expected_marker" > "$marker"
    rmdir "$stage"

    if [ ! -f "$required_relative_path" ]; then
        printf 'Installed tool is missing required file: %s\n' \
            "$required_relative_path" >&2
        exit 1
    fi
}

install_compiler_overlay() {
    local system_java
    local resolved_java
    local system_java_home
    local overlay="$COMPILER_ROOT"
    local java_version
    local expected_marker
    local marker="$overlay/.plainpod-system-jdk"
    local stage
    local compiler_quarantine

    system_java=$(command -v java)
    resolved_java=$(readlink -f "$system_java")
    system_java_home=$(dirname -- "$(dirname -- "$resolved_java")")

    java_version=$($system_java -version 2>&1 | sed -n '1p')
    case "$java_version" in
        *'version "17.'*) ;;
        *)
            printf 'A Java 17 runtime is required; found: %s\n' "$java_version" >&2
            exit 1
            ;;
    esac
    if ! "$system_java" --list-modules | grep -q '^jdk.compiler@17\.'; then
        printf 'The Java 17 runtime does not contain the jdk.compiler module.\n' >&2
        exit 1
    fi
    if ! "$system_java" --list-modules | grep -q '^jdk.jlink@17\.'; then
        printf 'The Java 17 runtime does not contain the jdk.jlink module.\n' >&2
        exit 1
    fi

    expected_marker="$system_java_home|$java_version"
    if [ -x "$overlay/bin/java" ] && [ -x "$overlay/bin/javac" ] && \
        [ -x "$overlay/bin/jlink" ] && [ -x "$overlay/bin/jmod" ] && \
        [ -f "$marker" ] && [ "$(sed -n '1p' "$marker")" = "$expected_marker" ] && \
        "$overlay/bin/javac" -version >/dev/null 2>&1 && \
        "$overlay/bin/jlink" --version >/dev/null 2>&1 && \
        "$overlay/bin/jmod" --version >/dev/null 2>&1; then
        printf 'Verified compiler overlay: %s\n' "$overlay"
        return
    fi

    stage=$(mktemp -d /tmp/plainpoddroid-jdk17.XXXXXX)
    cp -a -- "$system_java_home/." "$stage/"
    if [ ! -x "$stage/bin/javac" ]; then
        printf '%s\n' \
            '#!/bin/sh' \
            'set -eu' \
            'JAVA_DIRECTORY=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)' \
            'exec "$JAVA_DIRECTORY/java" -m jdk.compiler/com.sun.tools.javac.Main "$@"' \
            > "$stage/bin/javac"
        chmod 0755 "$stage/bin/javac"
    fi
    if [ ! -x "$stage/bin/jlink" ]; then
        printf '%s\n' \
            '#!/bin/sh' \
            'set -eu' \
            'JAVA_DIRECTORY=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)' \
            'exec "$JAVA_DIRECTORY/java" -m jdk.jlink/jdk.tools.jlink.internal.Main "$@"' \
            > "$stage/bin/jlink"
        chmod 0755 "$stage/bin/jlink"
    fi
    if [ ! -x "$stage/bin/jmod" ]; then
        printf '%s\n' \
            '#!/bin/sh' \
            'set -eu' \
            'JAVA_DIRECTORY=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)' \
            'exec "$JAVA_DIRECTORY/java" -m jdk.jlink/jdk.tools.jmod.Main "$@"' \
            > "$stage/bin/jmod"
        chmod 0755 "$stage/bin/jmod"
    fi
    printf '%s\n' "$expected_marker" > "$stage/.plainpod-system-jdk"

    "$stage/bin/java" -version >/dev/null 2>&1
    "$stage/bin/javac" -version >/dev/null 2>&1
    "$stage/bin/jlink" --version >/dev/null 2>&1
    "$stage/bin/jmod" --version >/dev/null 2>&1
    if [ -e "$overlay" ] || [ -L "$overlay" ]; then
        compiler_quarantine="$overlay.quarantine.$(date -u +%Y%m%dT%H%M%SZ).$$"
        printf 'Quarantining unexpected compiler path: %s -> %s\n' \
            "$overlay" "$compiler_quarantine" >&2
        mv -- "$overlay" "$compiler_quarantine"
    fi
    mv -- "$stage" "$overlay"
    printf 'Installed compiler overlay: %s\n' "$overlay"
}

GRADLE_ARCHIVE="$DOWNLOAD_ROOT/gradle-$GRADLE_VERSION-bin.zip"
PLATFORM_ARCHIVE="$DOWNLOAD_ROOT/$ANDROID_PLATFORM_ARCHIVE"
BUILD_TOOLS_ARCHIVE="$DOWNLOAD_ROOT/$ANDROID_BUILD_TOOLS_ARCHIVE"
PLATFORM_TOOLS_ARCHIVE="$DOWNLOAD_ROOT/$ANDROID_PLATFORM_TOOLS_ARCHIVE"

fetch_archive \
    "https://downloads.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" \
    "$GRADLE_ARCHIVE" sha256 "$GRADLE_SHA256" ""
fetch_archive \
    "https://dl.google.com/android/repository/$ANDROID_PLATFORM_ARCHIVE" \
    "$PLATFORM_ARCHIVE" sha256 "$ANDROID_PLATFORM_SHA256" "$ANDROID_PLATFORM_SIZE"
fetch_archive \
    "https://dl.google.com/android/repository/$ANDROID_BUILD_TOOLS_ARCHIVE" \
    "$BUILD_TOOLS_ARCHIVE" sha256 "$ANDROID_BUILD_TOOLS_SHA256" "$ANDROID_BUILD_TOOLS_SIZE"
fetch_archive \
    "https://dl.google.com/android/repository/$ANDROID_PLATFORM_TOOLS_ARCHIVE" \
    "$PLATFORM_TOOLS_ARCHIVE" sha256 "$ANDROID_PLATFORM_TOOLS_SHA256" "$ANDROID_PLATFORM_TOOLS_SIZE"

install_archive "$GRADLE_ARCHIVE" "$TOOLCHAIN_ROOT/gradle-$GRADLE_VERSION" \
    "$TOOLCHAIN_ROOT/gradle-$GRADLE_VERSION/bin/gradle" "$GRADLE_SHA256"
install_archive "$PLATFORM_ARCHIVE" "$TOOLCHAIN_ROOT/android-sdk/platforms/android-37.0" \
    "$TOOLCHAIN_ROOT/android-sdk/platforms/android-37.0/android.jar" \
    "$ANDROID_PLATFORM_SHA256" "$ANDROID_PLATFORM_SHA1"
install_archive "$BUILD_TOOLS_ARCHIVE" "$TOOLCHAIN_ROOT/android-sdk/build-tools/36.0.0" \
    "$TOOLCHAIN_ROOT/android-sdk/build-tools/36.0.0/aapt2" \
    "$ANDROID_BUILD_TOOLS_SHA256" "$ANDROID_BUILD_TOOLS_SHA1"
install_archive "$PLATFORM_TOOLS_ARCHIVE" "$TOOLCHAIN_ROOT/android-sdk/platform-tools" \
    "$TOOLCHAIN_ROOT/android-sdk/platform-tools/adb" \
    "$ANDROID_PLATFORM_TOOLS_SHA256" "$ANDROID_PLATFORM_TOOLS_SHA1"
install_compiler_overlay

printf '\nToolchain ready. Use these task-scoped variables:\n'
printf '  JAVA_HOME=%s\n' "$COMPILER_ROOT"
printf '  ANDROID_SDK_ROOT=%s\n' "$TOOLCHAIN_ROOT/android-sdk"
printf '  PLAINPOD_GRADLE=%s\n' "$TOOLCHAIN_ROOT/gradle-$GRADLE_VERSION/bin/gradle"
