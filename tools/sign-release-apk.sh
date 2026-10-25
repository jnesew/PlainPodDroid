#!/usr/bin/env bash
# Local, interactive APK signing. Never generates keys or changes Gradle signing.
set -euo pipefail
umask 077

fail() { printf 'Signing: %s\n' "$*" >&2; exit 1; }
usage() {
    cat <<'EOF'
Usage: tools/sign-release-apk.sh [--input APK] [--sdk SDK] --check-only
       tools/sign-release-apk.sh [--input APK] [--sdk SDK] \
         --keystore FILE --alias NAME --cert-sha256 FINGERPRINT --output APK

Input defaults to app/build/outputs/apk/release/app-release-unsigned.apk.
SDK defaults to ANDROID_SDK_ROOT, then ANDROID_HOME; requires Build Tools 36.0.0.
Signing requires a terminal, a private keystore outside this checkout, and its
expected certificate SHA-256 (64 hex digits, optional colons). Passwords are
prompted by apksigner. Existing output files are never overwritten.
--check-only checks the unsigned release APK and alignment without using a key.
Requires Linux, Bash 4+, GNU coreutils, Java 17, and the pinned Android SDK tools.
See docs/BUILDING.md for build requirements and signing-helper context.
EOF
}

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
input="$project_root/app/build/outputs/apk/release/app-release-unsigned.apk"
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
keystore= alias= fingerprint= output= check_only=false
declare -A seen=()
while (($#)); do
    case "$1" in
        --help|-h) usage; exit 0 ;;
        --check-only)
            [[ -z ${seen[$1]+x} ]] || fail "Duplicate option: $1"
            seen[$1]=1; check_only=true; shift ;;
        --input|--sdk|--keystore|--alias|--cert-sha256|--output)
            [[ -z ${seen[$1]+x} ]] || fail "Duplicate option: $1"
            (($# >= 2)) && [[ -n $2 && $2 != --* ]] || fail "Missing value for $1"
            seen[$1]=1
            case "$1" in
                --input) input=$2 ;; --sdk) sdk=$2 ;; --keystore) keystore=$2 ;;
                --alias) alias=$2 ;; --cert-sha256) fingerprint=$2 ;; --output) output=$2 ;;
            esac
            shift 2 ;;
        *) fail "Unknown option: $1 (use --help)" ;;
    esac
done

for command in realpath stat mktemp cp ln rm sha256sum sed grep tr dirname basename; do
    command -v "$command" >/dev/null || fail "Required command not found: $command"
done
[[ -n $sdk ]] || fail 'Set ANDROID_SDK_ROOT/ANDROID_HOME or pass --sdk.'
build_tools="$sdk/build-tools/36.0.0"
apksigner="$build_tools/apksigner"
zipalign="$build_tools/zipalign"
aapt2="$build_tools/aapt2"
for tool in "$apksigner" "$zipalign" "$aapt2"; do
    [[ -x $tool ]] || fail "Required SDK tool not found: $tool"
done
[[ -f $input && -r $input ]] || fail "Input APK is not a readable file: $input"
input=$(realpath -e -- "$input")

if $check_only; then
    [[ -z $keystore && -z $alias && -z $fingerprint && -z $output ]] ||
        fail '--check-only cannot be combined with signing options.'
    stage_parent=${TMPDIR:-/tmp}
else
    [[ -n $keystore && -n $alias && -n $fingerprint && -n $output ]] ||
        fail 'Signing requires --keystore, --alias, --cert-sha256 and --output.'
    [[ $alias =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]*$ ]] || fail 'Unsupported keystore alias.'
    [[ $alias != androiddebugkey ]] || fail 'Do not use the Android debug key for release signing.'
    fingerprint=$(printf '%s' "$fingerprint" | tr -d ':' | tr '[:upper:]' '[:lower:]')
    [[ $fingerprint =~ ^[0-9a-f]{64}$ ]] || fail 'Certificate SHA-256 must contain exactly 64 hex digits.'
    [[ -f $keystore && -r $keystore ]] || fail 'Keystore is not a readable file.'
    keystore=$(realpath -e -- "$keystore")
    case "$keystore" in "$project_root"/*) fail 'Keep the keystore outside this checkout.' ;; esac
    key_mode=$(stat -c '%a' -- "$keystore")
    (( (8#$key_mode & 077) == 0 )) || fail 'Keystore must not be accessible to group/others; use chmod 600.'
    [[ $output == *.apk ]] || fail 'Output must end in .apk.'
    [[ ! -e $output && ! -L $output ]] || fail 'Output already exists; choose a new filename.'
    stage_parent=$(realpath -e -- "$(dirname -- "$output")")
    [[ -d $stage_parent && -w $stage_parent ]] || fail 'Output directory must exist and be writable.'
    output="$stage_parent/$(basename -- "$output")"
    [[ $output != "$input" ]] || fail 'Input and output must differ.'
    [[ -t 0 && -t 1 ]] || fail 'Run signing in an interactive terminal; passwords must be prompted securely.'
fi

stage=$(mktemp -d "$stage_parent/.plainpoddroid-sign.XXXXXXXX")
trap 'rm -rf -- "$stage"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# Inspect and sign the same private snapshot; never modify the input APK.
cp -- "$input" "$stage/input.apk"
"$apksigner" version >/dev/null || fail 'SDK signature tool cannot run; check Java and SDK installation.'
badging=$("$aapt2" dump badging "$stage/input.apk") || fail 'Cannot read the APK manifest.'
package=$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
version_code=$(printf '%s\n' "$badging" | sed -n "s/^package: .* versionCode='\([0-9]*\)'.*/\1/p")
[[ $package == io.github.jnesew.plainpoddroid ]] || fail 'Input must use the PlainPodDroid release package ID.'
[[ $version_code =~ ^[1-9][0-9]*$ ]] || fail 'Input must have a positive versionCode.'
grep -qx "minSdkVersion:'29'" <<< "$badging" || fail 'Expected minimum Android API 29.'
if grep -q '^application-debuggable' <<< "$badging"; then
    fail 'Refusing a debuggable APK.'
fi
if "$apksigner" verify --min-sdk-version 29 "$stage/input.apk" >/dev/null 2>&1; then
    fail 'Input is already signed; rebuild the unsigned release APK.'
fi
"$zipalign" -P 16 4 "$stage/input.apk" "$stage/aligned.apk" || fail 'APK alignment failed.'
"$zipalign" -c -P 16 4 "$stage/aligned.apk" || fail 'Aligned APK verification failed.'
if $check_only; then
    printf 'Unsigned release APK preflight passed: package=%s versionCode=%s minSdk=29\n' "$package" "$version_code"
    sha256sum -- "$stage/input.apk" | sed 's/ .*$/  (input APK)/'
    printf 'No key was opened and no signed APK was created.\n'
    exit 0
fi

printf 'Signing %s versionCode=%s; enter passwords at the SDK prompts.\n' "$package" "$version_code"
"$apksigner" sign --ks "$keystore" --ks-key-alias "$alias" \
    --min-sdk-version 29 --debuggable-apk-permitted false \
    --v1-signing-enabled false --v2-signing-enabled true \
    --v3-signing-enabled true --v4-signing-enabled false \
    --alignment-preserved true --in "$stage/aligned.apk" --out "$stage/signed.apk" || fail 'APK signing failed.'
signed_info=$("$apksigner" verify --verbose --print-certs --min-sdk-version 29 "$stage/signed.apk") ||
    fail 'Signed APK signature verification failed.'
grep -qx 'Verified using v3 scheme (APK Signature Scheme v3): true' <<< "$signed_info" ||
    fail 'APK Signature Scheme v3 did not verify.'
check_signer() {
    local report=$1 actual
    grep -qx 'Number of signers: 1' <<< "$report" || fail 'Expected exactly one signer.'
    actual=$(printf '%s\n' "$report" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' |
        tr -d ':' | tr '[:upper:]' '[:lower:]')
    [[ $actual == "$fingerprint" ]] || fail 'Signing certificate SHA-256 differs from the expected fingerprint.'
}
check_signer "$signed_info"
# API 29+ verification prefers v3 and skips v2. Audit v2 separately in the
# pre-v3 API range; this does not change the APK's API 29 installation floor.
v2_info=$("$apksigner" verify --verbose --print-certs --min-sdk-version 24 \
    --max-sdk-version 27 "$stage/signed.apk") || fail 'Signed APK v2 signature verification failed.'
grep -qx 'Verified using v2 scheme (APK Signature Scheme v2): true' <<< "$v2_info" ||
    fail 'APK Signature Scheme v2 did not verify.'
check_signer "$v2_info"
"$zipalign" -c -P 16 4 "$stage/signed.apk" || fail 'Signed APK alignment verification failed.'
artifact_hash=$(sha256sum -- "$stage/signed.apk")
artifact_hash=${artifact_hash%% *}
# Same-filesystem hard link publishes atomically without clobbering a racing
# file, directory, or symlink. -T prevents following a destination directory.
ln -T -- "$stage/signed.apk" "$output" || fail 'Output appeared during signing; nothing was overwritten.'
printf 'Signed and verified: %s\nCertificate SHA-256: %s\nAPK SHA-256: %s\n' "$output" "$fingerprint" "$artifact_hash"
