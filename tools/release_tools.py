#!/usr/bin/env python3
"""Local release preparation and APK gates. Never signs, tags, pushes or uploads."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "io.github.jnesew.plainpoddroid"
BUILD_TOOLS = "36.0.0"
VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:-rc\.[0-9]+)?")
PERMISSIONS = {"android.permission." + name for name in (
    "INTERNET", "ACCESS_NETWORK_STATE", "FOREGROUND_SERVICE",
    "FOREGROUND_SERVICE_MEDIA_PLAYBACK", "RECEIVE_BOOT_COMPLETED", "WAKE_LOCK")}
PERMISSIONS.add(PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
COMPONENTS = {
    PACKAGE + ".MainActivity": ("activity", True),
    PACKAGE + ".AboutActivity": ("activity", False),
    PACKAGE + ".ScheduledRefreshJobService": ("service", False),
    PACKAGE + ".AutomaticDownloadJobService": ("service", False),
    PACKAGE + ".PreparationJobService": ("service", False),
    PACKAGE + ".PreparationScheduleReceiver": ("receiver", False),
    PACKAGE + ".playback.PlaybackService": ("service", True),
    "androidx.media3.session.BluetoothValidationActivity": ("activity", True),
    "androidx.startup.InitializationProvider": ("provider", False),
}
LEGAL = {
    "LICENSE": "assets/legal/LICENSE",
    "THIRD_PARTY_NOTICES.md": "assets/legal/THIRD_PARTY_NOTICES.md",
    "LICENSES/Apache-2.0.txt": "assets/legal/licenses/Apache-2.0.txt",
}


class ReleaseError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise ReleaseError(message)


def run(args, *, cwd=ROOT, env=None, check=True):
    result = subprocess.run([str(a) for a in args], cwd=cwd, env=env,
                            capture_output=True, text=True)
    if check:
        require(result.returncode == 0,
                f"{Path(str(args[0])).name} failed:\n{result.stdout}{result.stderr}")
    return result


def digest(path):
    with Path(path).open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def source_version(root=ROOT):
    values = {}
    for line in (root / "gradle.properties").read_text().splitlines():
        if line.startswith(("plainPodDroidVersionName=", "plainPodDroidVersionCode=")):
            key, value = line.split("=", 1)
            require(key not in values, "Duplicate version property.")
            values[key] = value
    name = values.get("plainPodDroidVersionName", "")
    code = values.get("plainPodDroidVersionCode", "")
    require(VERSION.fullmatch(name), "Use a numeric stable or -rc.N versionName.")
    require(code.isdecimal() and 0 < int(code) < 2100000000, "Invalid versionCode.")
    return name, int(code)


def checkpoint(root=ROOT):
    require(not run(["git", "status", "--porcelain", "--untracked-files=all"],
                    cwd=root).stdout, "Commit task changes before preparing a release.")
    return run(["git", "rev-parse", "HEAD"], cwd=root).stdout.strip()


def sdk_path(value=None):
    value = value or os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    require(value, "Pass --sdk or set ANDROID_SDK_ROOT/ANDROID_HOME.")
    sdk = Path(value).expanduser().resolve(strict=True)
    for name in ("aapt2", "zipalign", "apksigner"):
        require(os.access(sdk / "build-tools" / BUILD_TOOLS / name, os.X_OK),
                f"Missing Build Tools {BUILD_TOOLS}: {name}")
    java = Path(os.environ["JAVA_HOME"]) / "bin/java" if os.environ.get("JAVA_HOME") else "java"
    info = run([java, "-version"])
    require(re.search(r'version "17(?:\.|")', info.stdout + info.stderr),
            "Use JDK 17 (set JAVA_HOME and PATH).")
    return sdk


def manifest_tree(text):
    """Parse aapt2's binary XML tree; validate the APK, not a source manifest."""
    stack, nodes = [], []
    for line in text.splitlines():
        depth = len(line) - len(line.lstrip())
        element = re.match(r"\s*E: ([^ ]+)", line)
        if element:
            while stack and stack[-1]["depth"] >= depth:
                stack.pop()
            node = {"tag": element[1], "depth": depth, "attrs": {}, "children": []}
            if stack:
                stack[-1]["children"].append(node)
            stack.append(node)
            nodes.append(node)
        attr = re.match(r"\s*A: (?:http://schemas.android.com/apk/res/android:)?"
                        r"([^=(]+)(?:\([^)]*\))?=(.*)", line)
        if attr:
            require(stack, "Malformed binary XML attributes.")
            stack[-1]["attrs"][attr[1]] = attr[2].split(" (Raw:")[0].strip().strip('"')
    require(nodes, "Empty binary XML.")
    return nodes


def one(nodes, tag):
    found = [n for n in nodes if n["tag"] == tag]
    require(len(found) == 1, f"Expected one {tag} element.")
    return found[0]["attrs"]


def verify_manifest(text, name, code):
    nodes = manifest_tree(text)
    manifest, app, uses = one(nodes, "manifest"), one(nodes, "application"), one(nodes, "uses-sdk")
    require(manifest.get("package") == PACKAGE, "Wrong release package.")
    require(manifest.get("versionName") == name and manifest.get("versionCode") == str(code),
            "APK version differs from the committed version.")
    require(uses.get("minSdkVersion") == "29" and uses.get("targetSdkVersion") == "36",
            "Unexpected SDK compatibility policy.")
    require(manifest.get("compileSdkVersion") == "37", "Unexpected compile SDK.")
    require(app.get("debuggable", "false") == "false", "Release must not be debuggable.")
    require(all(app.get(k) == "false" for k in
                ("allowBackup", "fullBackupContent", "usesCleartextTraffic")),
            "Backup/cleartext restrictions changed.")
    permissions = [n["attrs"].get("name") for n in nodes if n["tag"] == "uses-permission"]
    require(len(permissions) == len(set(permissions)) and set(permissions) == PERMISSIONS,
            "Release permission surface changed.")
    declarations = [n["attrs"] for n in nodes if n["tag"] == "permission"]
    require(len(declarations) == 1 and declarations[0].get("name") ==
            PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" and
            declarations[0].get("protectionLevel") == "0x00000002",
            "Internal receiver permission must remain signature-only.")
    components = [n for n in nodes if n["tag"] in ("activity", "service", "receiver", "provider", "activity-alias")]
    require(len(components) == len(COMPONENTS), "Release component surface changed.")
    names = [n["attrs"].get("name") for n in components]
    require(set(names) == set(COMPONENTS) and len(names) == len(set(names)),
            "Unexpected or duplicate component.")
    for node in components:
        attrs = node["attrs"]
        kind, exported = COMPONENTS[attrs["name"]]
        require(node["tag"] == kind and attrs.get("exported") == str(exported).lower(),
                "Component export policy changed: " + attrs["name"])
        if attrs["name"].endswith("JobService"):
            require(attrs.get("permission") == "android.permission.BIND_JOB_SERVICE",
                    "Job service lost its platform permission.")
        if attrs["name"].endswith("ScheduleReceiver"):
            require(attrs.get("enabled") == "false", "Preparation receiver must default disabled.")
        if attrs["name"].endswith("BluetoothValidationActivity"):
            require(attrs.get("permission") == "android.permission.BLUETOOTH_PRIVILEGED",
                    "Bluetooth activity lost its platform-only permission.")
    require(not any(n["tag"] == "uses-library" for n in nodes), "Test libraries in release.")
    return app


def resource_files(resources, resource_id):
    pattern = r"(?m)^    resource " + re.escape(resource_id.removeprefix("@")) + r" [^\n]+\n"
    match = re.search(pattern, resources)
    require(match, "Missing security XML resource.")
    block = resources[match.end():]
    block = re.split(r"(?m)^    resource ", block, maxsplit=1)[0]
    return re.findall(r"(?m)^\s+\(([^)]*)\) \(file\) (\S+) type=XML", block)


def zip_payload(apk):
    require(Path(apk).stat().st_size <= 128 * 1024 * 1024, "APK exceeds inspection budget.")
    with zipfile.ZipFile(apk) as archive:
        entries = archive.infolist()
        names = [item.filename for item in entries]
        require(len(names) == len(set(names)) and len(names) <= 4096, "Ambiguous/oversized APK ZIP.")
        require(sum(item.file_size for item in entries) <= 128 * 1024 * 1024,
                "Expanded APK exceeds inspection budget.")
        require(all(item.file_size <= 64 * 1024 * 1024 for item in entries), "Oversized APK entry.")
        return {item.filename: archive.read(item) for item in entries}


def has_signing_block(apk):
    data = Path(apk).read_bytes()
    eocd = data.rfind(b"PK\x05\x06", max(0, len(data) - 65557))
    require(eocd >= 0 and eocd + 22 <= len(data), "Missing APK ZIP directory.")
    require(eocd + 22 + struct.unpack_from("<H", data, eocd + 20)[0] == len(data),
            "Invalid APK ZIP trailer.")
    offset = struct.unpack_from("<I", data, eocd + 16)[0]
    return data[max(0, offset - 16):offset] == b"APK Sig Block 42"


def verify_guava(usage):
    require(usage and Path(usage).is_file(), "R8 usage report is required for the pinned Guava gate.")
    text = Path(usage).read_text()
    match = re.search(r"(?m)^com.google.common.collect.CompactHashMap:\n((?:    .*\n)*)", text)
    require(match and "readObject(java.io.ObjectInputStream)" in match[1],
            "Pinned Guava deserialization path survives shrinking.")
    for name in ("CompactHashSet", "MapMakerInternalMap"):
        require("\ncom.google.common.collect." + name + "\n" in text,
                "Pinned Guava serialization class survives shrinking: " + name)


def normalize_certificate(value):
    value = value.replace(":", "").lower()
    require(re.fullmatch(r"[a-f0-9]{64}", value), "Expected a trusted certificate SHA-256.")
    return value


def verify_apk(apk, sdk, name, code, *, certificate=None, usage=None, root=ROOT):
    apk = Path(apk)
    tools = sdk / "build-tools" / BUILD_TOOLS
    tree = run([tools / "aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml", apk]).stdout
    app = verify_manifest(tree, name, code)
    resources = run([tools / "aapt2", "dump", "resources", apk]).stdout
    networks = resource_files(resources, app.get("networkSecurityConfig", "missing"))
    require({q for q, _ in networks} == {"", "v36"} and len(networks) == 2,
            "Network security XML variants changed.")
    for qualifier, filename in networks:
        nodes = manifest_tree(run([tools / "aapt2", "dump", "xmltree", "--file", filename, apk]).stdout)
        require(all(n["attrs"].get("cleartextTrafficPermitted") == "false"
                    for n in nodes if n["tag"] in ("base-config", "domain-config")),
                "Cleartext enabled in packaged security XML.")
        require(one(nodes, "certificates").get("src") == "system", "Custom production trust anchors.")
        transparency = [n for n in nodes if n["tag"] == "certificateTransparency"]
        require((len(transparency) == 1 and transparency[0]["attrs"].get("enabled") == "true")
                if qualifier == "v36" else not transparency, "Certificate Transparency policy changed.")
    backup = resource_files(resources, app.get("dataExtractionRules", "missing"))
    require(len(backup) == 1 and backup[0][0] == "", "Backup rules changed.")
    nodes = manifest_tree(run([tools / "aapt2", "dump", "xmltree", "--file", backup[0][1], apk]).stdout)
    for tag in ("cloud-backup", "device-transfer"):
        parents = [n for n in nodes if n["tag"] == tag]
        require(len(parents) == 1 and len(parents[0]["children"]) == 1 and
                parents[0]["children"][0]["tag"] == "exclude" and
                parents[0]["children"][0]["attrs"] == {"domain": "root", "path": "."},
                "Backup/transfer must exclude the complete root.")
    payload = zip_payload(apk)
    require(not any(n.startswith("lib/") or n.endswith(".so") for n in payload),
            "Unexpected native library.")
    for source, target in LEGAL.items():
        require(payload.get(target) == (root / source).read_bytes(), "Packaged legal bytes differ: " + source)
    require(not any(n.startswith("assets/") and any(s in n.lower() for s in
                (".private", "keystore", "signing", "screenshot-fixture")) for n in payload),
            "Private/test asset in release.")
    verify_guava(usage)
    dex = b"".join(value for key, value in payload.items() if re.fullmatch(r"classes\d*\.dex", key))
    require(dex, "No release DEX.")
    for name_ in ("CompactHashMap", "CompactHashSet", "MapMakerInternalMap"):
        require(("Lcom/google/common/collect/" + name_ + ";").encode() not in dex,
                "Original Guava serialization wire class remains in release.")
    run([tools / "zipalign", "-c", "-P", "16", "4", apk])
    verification = run([tools / "apksigner", "verify", "--verbose", "--print-certs",
                        "--min-sdk-version", "29", apk], check=False)
    if certificate is None:
        require(verification.returncode != 0 and not has_signing_block(apk) and
                not any(re.fullmatch(r"META-INF/[^/]+\.(SF|RSA|DSA|EC)", n, re.I) for n in payload),
                "Candidate must be genuinely unsigned, not signed or signature-corrupt.")
    else:
        certificate = normalize_certificate(certificate)
        for minimum, maximum, scheme in ((29, None, "v3"), (24, 27, "v2")):
            args = [tools / "apksigner", "verify", "--verbose", "--print-certs", "--min-sdk-version", minimum]
            if maximum:
                args += ["--max-sdk-version", maximum]
            result = run(args + [apk]).stdout
            require(f"Verified using {scheme} scheme (APK Signature Scheme {scheme}): true" in result and
                    "Number of signers: 1" in result, "Required single-signer v2/v3 signature did not verify.")
            found = re.search(r"(?m)^Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)$", result)
            require(found and found[1].lower() == certificate, "Signing certificate differs from trusted identity.")
            require("CN=Android Debug" not in result, "Debug signing identity is not a release identity.")
    return {"package": PACKAGE, "version_name": name, "version_code": code,
            "sha256": digest(apk), "size": apk.stat().st_size, "certificate_sha256": certificate,
            "security_gates": "passed", "guava_shrinkage_gate": "passed"}


def create_output(path):
    path = Path(path).expanduser()
    require(path.name not in ("", ".", ".."), "Choose a new output directory.")
    parent = path.parent.resolve(strict=True)
    output = parent / path.name
    try:
        output.mkdir(mode=0o700)
    except FileExistsError as error:
        raise ReleaseError("Output already exists; nothing was overwritten.") from error
    (output / "INCOMPLETE").write_text("Preparation is incomplete. Inspect logs; do not distribute.\n")
    return output


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def public_fdroid(commit, name, code, certificate=None):
    # YAML 1.2, generated from the exact prepared source rather than a moving tag.
    binary = (f"Binaries: https://github.com/jnesew/PlainPodDroid/releases/download/v%v/PlainPodDroid-%v.apk\n"
              f"AllowedAPKSigningKeys:\n  - {normalize_certificate(certificate)}\n") if certificate else ""
    return f"""Categories:
  - Multimedia
License: MIT
AuthorName: jnesew
SourceCode: https://github.com/jnesew/PlainPodDroid
IssueTracker: https://github.com/jnesew/PlainPodDroid/issues
Changelog: https://github.com/jnesew/PlainPodDroid/blob/main/CHANGELOG.md
RepoType: git
Repo: https://github.com/jnesew/PlainPodDroid.git
{binary}Builds:
  - versionName: '{name}'
    versionCode: {code}
    commit: {commit}
    subdir: app
    gradle:
      - yes
AutoUpdateMode: None
UpdateCheckMode: 'Tags ^v[0-9]+\\.[0-9]+\\.[0-9]+$'
UpdateCheckData: 'gradle.properties|plainPodDroidVersionCode=([0-9]+)|.|plainPodDroidVersionName=(.*)'
CurrentVersion: '{name}'
CurrentVersionCode: {code}
"""


def prepare(args):
    name, code = source_version()
    commit = checkpoint()
    sdk = sdk_path(args.sdk)
    env = os.environ.copy()
    env.update(ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk))
    local = ROOT / "local.properties"
    if local.exists():
        match = re.search(r"(?m)^sdk\.dir=(.*)$", local.read_text())
        require(not match or Path(match[1]).resolve() == sdk, "local.properties selects a different SDK.")
    notes = ROOT / "docs/release-notes" / f"{name}.md"
    require(notes.is_file(), "Add release notes for the committed version.")
    output = create_output(args.output)
    tasks = ["clean", "testDebugUnitTest", "compileDebugAndroidTestJavaWithJavac",
             "lintDebug", "assembleDebug", "lintRelease", "assembleRelease", "assembleDebugAndroidTest"]
    commands = [
        [sys.executable, "tools/tests/test_catalog_sql.py"],
        [sys.executable, "-m", "unittest", "discover", "-s", "tools/tests", "-p", "test_release*.py"],
        [sys.executable, "tools/verify-metadata.py"],
        ["./gradlew", "--no-daemon", "--no-build-cache", "--no-configuration-cache"] +
        (["--offline"] if args.offline else []) + tasks,
    ]
    env.update(PLAINPOD_TEST_ANDROID_SDK_ROOT=str(sdk),
               PLAINPOD_TEST_UNSIGNED_APK=str(ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk"))
    commands.append([sys.executable, "tools/tests/test_sign_release_apk.py"])
    for index, command in enumerate(commands, 1):
        print(f"Release gate {index}/{len(commands)}: {Path(command[0]).name}", flush=True)
        result = run(command, env=env, check=False)
        (output / f"gate-{index}.log").write_text(result.stdout + result.stderr)
        require(result.returncode == 0, f"Release gate failed; inspect {output}/gate-{index}.log")
    apk = ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk"
    usage = ROOT / "app/build/outputs/mapping/release/usage.txt"
    shutil.copyfile(apk, output / "app-unsigned.apk")
    shutil.copyfile(usage, output / "r8-usage.txt")
    report = verify_apk(output / "app-unsigned.apk", sdk, name, code, usage=output / "r8-usage.txt")
    require(checkpoint() == commit, "Source changed while building; candidate is incomplete.")
    shutil.copyfile(notes, output / "RELEASE_NOTES.md")
    for source in LEGAL:
        target = output / source
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / source, target)
    provenance = {"schema": 1, "state": "unsigned-candidate", "source_commit": commit,
                  "tag": f"v{name}", "prerelease": "-rc." in name, "apk": report,
                  "built_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  "toolchain": {"jdk": 17, "gradle": "9.6.0", "agp": "9.4.0",
                                "compile_sdk": 37, "build_tools": BUILD_TOOLS},
                  "input_hashes": {p: digest(ROOT / p) for p in (
                      "gradle/verification-metadata.xml", "app/gradle.lockfile",
                      "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
                      "third-party/runtime-dependencies.json")}}
    write_json(output / "PROVENANCE.json", provenance)
    (output / "INCOMPLETE").unlink()
    (output / "READY").write_text("Verified unsigned candidate. Sign app-unsigned.apk locally; do not publish it.\n")
    print(f"Unsigned candidate ready: {output}")


def package_release(args):
    candidate = Path(args.candidate).resolve(strict=True)
    require((candidate / "READY").is_file() and not (candidate / "INCOMPLETE").exists(),
            "Candidate did not complete preparation.")
    provenance = json.loads((candidate / "PROVENANCE.json").read_text())
    require(provenance.get("schema") == 1 and provenance.get("state") == "unsigned-candidate",
            "Unsupported candidate provenance.")
    name, code = source_version()
    require(provenance["source_commit"] == checkpoint() and
            provenance["apk"]["version_name"] == name and provenance["apk"]["version_code"] == code,
            "Use the candidate's exact clean source checkpoint/version.")
    require(set(provenance["input_hashes"]) == {
        "gradle/verification-metadata.xml", "app/gradle.lockfile", "gradle/wrapper/gradle-wrapper.jar",
        "gradle/wrapper/gradle-wrapper.properties", "third-party/runtime-dependencies.json"},
        "Incomplete candidate input inventory.")
    for path, expected in provenance["input_hashes"].items():
        require(path in {"gradle/verification-metadata.xml", "app/gradle.lockfile",
                         "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
                         "third-party/runtime-dependencies.json"} and digest(ROOT / path) == expected,
                "Candidate build inputs changed.")
    unsigned = candidate / "app-unsigned.apk"
    require(digest(unsigned) == provenance["apk"]["sha256"], "Unsigned candidate hash changed.")
    sdk = sdk_path(args.sdk)
    certificate = normalize_certificate(args.cert_sha256)
    output = create_output(args.output)
    filename = f"PlainPodDroid-{name}.apk"
    signed = output / filename
    shutil.copyfile(args.apk, signed)
    # Verify the exact private snapshot that will become the public asset.
    report = verify_apk(signed, sdk, name, code, certificate=certificate,
                        usage=candidate / "r8-usage.txt")
    expected_payload = zip_payload(unsigned)
    require(digest(unsigned) == provenance["apk"]["sha256"], "Unsigned candidate changed during inspection.")
    require(expected_payload == zip_payload(signed),
            "Signed APK payload differs from the verified unsigned candidate.")
    for source in (*LEGAL, "RELEASE_NOTES.md"):
        target = output / source
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(candidate / source, target)
    # Public metadata comes from the same clean source, not mutable candidate notes.
    require((output / "RELEASE_NOTES.md").read_bytes() ==
            (ROOT / "docs/release-notes" / f"{name}.md").read_bytes(), "Candidate notes changed.")
    for source in LEGAL:
        require((output / source).read_bytes() == (ROOT / source).read_bytes(), "Candidate legal bytes changed.")
    fdroid = output / "fdroid/metadata" / f"{PACKAGE}.yml"
    fdroid.parent.mkdir(parents=True)
    fdroid.write_text(public_fdroid(provenance["source_commit"], name, code, certificate))
    receipt = {**provenance, "state": "signed-release-packet", "unsigned_sha256": provenance["apk"]["sha256"],
               "apk": report, "asset": filename}
    write_json(output / "PROVENANCE.json", receipt)
    files = sorted(p for p in output.rglob("*") if p.is_file() and p.name != "INCOMPLETE")
    (output / "SHA256SUMS").write_text("".join(f"{digest(p)}  {p.relative_to(output).as_posix()}\n" for p in files))
    (output / "INCOMPLETE").unlink()
    print(f"Verified signed packet ready: {output}\nPublication remains an explicit separate action.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    verify = commands.add_parser("verify-apk")
    verify.add_argument("--apk", required=True)
    verify.add_argument("--sdk")
    verify.add_argument("--usage", required=True)
    verify.add_argument("--cert-sha256")
    prepare_parser = commands.add_parser("prepare")
    prepare_parser.add_argument("--output", required=True)
    prepare_parser.add_argument("--sdk")
    prepare_parser.add_argument("--offline", action="store_true")
    package_parser = commands.add_parser("package")
    for option in ("candidate", "apk", "cert-sha256", "output"):
        package_parser.add_argument("--" + option, required=True)
    package_parser.add_argument("--sdk")
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            prepare(args)
        elif args.command == "package":
            package_release(args)
        else:
            name, code = source_version()
            print(json.dumps(verify_apk(args.apk, sdk_path(args.sdk), name, code,
                                       certificate=args.cert_sha256, usage=args.usage), indent=2))
    except (ReleaseError, OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print(f"Release: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
