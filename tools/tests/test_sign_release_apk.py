#!/usr/bin/env python3
"""Key-free workflow tests. SDK doubles never perform cryptographic signing."""
import hashlib
import json
import os
from pathlib import Path
import pty
import select
import shutil
import subprocess
import tempfile
import time
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "sign-release-apk.sh"
FINGERPRINT = "ab" * 32
DRIVER = r'''#!/usr/bin/env python3
import json, os, pathlib, shutil, sys
config = json.loads(os.environ["SIGN_TEST_CONFIG"])
args = sys.argv[1:]
name = pathlib.Path(sys.argv[0]).name
with open(os.environ["SIGN_TEST_AUDIT"], "a") as audit:
    audit.write(json.dumps([name, args]) + "\n")
if name == "apksigner" and config.get("real_apksigner") and args[0] in ("version", "verify"):
    os.execv(config["real_apksigner"], [config["real_apksigner"], *args])
if name == "aapt2":
    if config.get("manifest_failure"): sys.exit(1)
    print("package: name='%s' versionCode='%s' versionName='0.1.0-dev'" %
          (config.get("package", "io.github.jnesew.plainpoddroid"), config.get("version", "1")))
    print("minSdkVersion:'%s'" % config.get("min_sdk", "29"))
    if config.get("debuggable"):
        print("application-debuggable")
        print("x" * 100000)  # Exercise early grep matches with pipefail enabled.
elif name == "zipalign":
    if config.get("alignment_failure"): sys.exit(1)
    if "-c" in args:
        if config.get("signed_alignment_failure") and pathlib.Path(args[-1]).name == "signed.apk":
            sys.exit(1)
    else:
        shutil.copyfile(args[-2], args[-1])
elif args[0] == "version":
    if config.get("signer_runtime_failure"): sys.exit(1)
    print("0.9")
elif args[0] == "sign":
    dest = pathlib.Path(args[args.index("--out") + 1])
    if config.get("signed_fixture"):
        shutil.copyfile(config["signed_fixture"], dest)
    else:
        dest.write_bytes(b"signed-fixture")
    race = config.get("race")
    if race == "file": pathlib.Path(config["output"]).write_bytes(b"concurrent-file")
    if race == "symlink": pathlib.Path(config["output"]).symlink_to(config["race_dir"], target_is_directory=True)
    if config.get("sign_failure"): sys.exit(1)
elif args[0] == "verify":
    content = pathlib.Path(args[-1]).read_bytes()
    if content == b"already-signed": sys.exit(0)
    v2_only = "--max-sdk-version" in args and args[args.index("--max-sdk-version") + 1] == "27"
    if content != b"signed-fixture" or config.get("verify_failure") or (v2_only and config.get("v2_verify_failure")):
        sys.exit(1)
    print("Verifies")
    # apksig skips v2 on API 29+ when v3 is present; false does not mean failure.
    print("Verified using v2 scheme (APK Signature Scheme v2): %s" %
          (config.get("v2", "true") if v2_only else "false"))
    print("Verified using v3 scheme (APK Signature Scheme v3): %s" %
          ("false" if v2_only else config.get("v3", "true")))
    print("Number of signers: %s" % config.get("v2_signers" if v2_only else "signers", "1"))
    print("Signer #1 certificate SHA-256 digest: " +
          config.get("v2_fingerprint" if v2_only else "fingerprint", "ab" * 32))
else: sys.exit(2)
'''


class SigningWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="plainpoddroid-sign-tests-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "checkout with spaces"
        (self.repo / "tools").mkdir(parents=True)
        self.script = self.repo / "tools/sign-release-apk.sh"
        shutil.copyfile(SCRIPT, self.script)
        self.input = self.repo / "app/build/outputs/apk/release/app-release-unsigned.apk"
        self.input.parent.mkdir(parents=True)
        self.input.write_bytes(b"unsigned-fixture")
        self.key = self.root / "private key.p12"
        self.key.write_bytes(b"NOT A KEY: SDK-double fixture only")
        self.key.chmod(0o600)
        self.output = self.root / "signed output.apk"
        self.sdk = self.root / "SDK with spaces"
        tools = self.sdk / "build-tools/36.0.0"
        tools.mkdir(parents=True)
        for name in ("aapt2", "apksigner", "zipalign"):
            tool = tools / name
            tool.write_text(DRIVER)
            tool.chmod(0o755)
        self.audit = self.root / "audit.jsonl"

    def run_script(self, *args, config=None, terminal=True):
        env = os.environ.copy()
        env.update(ANDROID_SDK_ROOT=str(self.sdk), ANDROID_HOME="/wrong/sdk",
                   SIGN_TEST_CONFIG=json.dumps(config or {}), SIGN_TEST_AUDIT=str(self.audit))
        command = ["bash", str(self.script), *map(str, args)]
        if not terminal:
            result = subprocess.run(command, env=env, cwd=self.root, capture_output=True,
                                    text=True, timeout=10, stdin=subprocess.DEVNULL)
            return result.returncode, result.stdout + result.stderr
        master, slave = pty.openpty()
        process = subprocess.Popen(command, env=env, cwd=self.root, stdin=slave,
                                   stdout=slave, stderr=slave)
        os.close(slave)
        chunks = []
        deadline = time.monotonic() + 10
        try:
            while time.monotonic() < deadline:
                if select.select([master], [], [], 0.1)[0]:
                    try:
                        data = os.read(master, 65536)
                    except OSError:
                        break
                    if not data:
                        break
                    chunks.append(data)
                elif process.poll() is not None:
                    break
            if process.poll() is None:
                process.wait(timeout=max(0.1, deadline - time.monotonic()))
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            os.close(master)
        return process.returncode, b"".join(chunks).decode()

    def signing_args(self, **overrides):
        values = dict(keystore=self.key, alias="plainpoddroid", cert_sha256=FINGERPRINT,
                      output=self.output)
        values.update(overrides)
        return [item for name, value in values.items()
                for item in ("--" + name.replace("_", "-"), str(value))]

    def calls(self):
        return [json.loads(line) for line in self.audit.read_text().splitlines()] if self.audit.exists() else []

    def assert_failure(self, message, *, config=None, terminal=True, args=None):
        code, output = self.run_script(*(self.signing_args() if args is None else args),
                                       config=config, terminal=terminal)
        self.assertNotEqual(0, code, output)
        self.assertIn(message, output)
        self.assertEqual(b"unsigned-fixture", self.input.read_bytes())
        self.assertFalse(list(self.root.glob(".plainpoddroid-sign.*")))
        return output

    def test_success_preserves_input_and_verifies_before_publication(self):
        code, output = self.run_script(*self.signing_args())
        self.assertEqual(0, code, output)
        self.assertEqual(b"signed-fixture", self.output.read_bytes())
        self.assertEqual(b"unsigned-fixture", self.input.read_bytes())
        self.assertIn(hashlib.sha256(b"signed-fixture").hexdigest(), output)
        self.assertFalse(list(self.root.glob(".plainpoddroid-sign.*")))
        calls = self.calls()
        sign = next(args for tool, args in calls if tool == "apksigner" and args[0] == "sign")
        for flag, value in [("--v1-signing-enabled", "false"), ("--v2-signing-enabled", "true"),
                            ("--v3-signing-enabled", "true"), ("--v4-signing-enabled", "false"),
                            ("--debuggable-apk-permitted", "false"), ("--alignment-preserved", "true")]:
            self.assertEqual(value, sign[sign.index(flag) + 1])
        self.assertNotIn("--ks-pass", sign)
        self.assertNotIn("--key-pass", sign)
        self.assertEqual("zipalign", calls[-1][0])
        self.assertIn("-c", calls[-1][1])
        verifies = [args for tool, args in calls if tool == "apksigner" and "--verbose" in args]
        self.assertEqual(2, len(verifies))
        self.assertEqual("29", verifies[0][verifies[0].index("--min-sdk-version") + 1])
        self.assertNotIn("--max-sdk-version", verifies[0])
        self.assertEqual("24", verifies[1][verifies[1].index("--min-sdk-version") + 1])
        self.assertEqual("27", verifies[1][verifies[1].index("--max-sdk-version") + 1])

    @unittest.skipUnless(os.environ.get("PLAINPOD_TEST_SIGNED_APK") and
                         os.environ.get("PLAINPOD_TEST_SIGNED_CERT_SHA256") and
                         os.environ.get("PLAINPOD_TEST_ANDROID_SDK_ROOT"),
                         "Set signed public fixture, certificate fingerprint and SDK for real verifier tests")
    def test_real_v2_v3_verifier_reports_and_tamper_rejection(self):
        # Only signing is doubled: copy an already signed public APK. Real
        # apksigner validates both signatures; no key is opened or generated.
        fixture = Path(os.environ["PLAINPOD_TEST_SIGNED_APK"])
        fingerprint = os.environ["PLAINPOD_TEST_SIGNED_CERT_SHA256"]
        signer = str(Path(os.environ["PLAINPOD_TEST_ANDROID_SDK_ROOT"]) / "build-tools/36.0.0/apksigner")
        for minimum, maximum, scheme in ((29, None, "v3"), (24, 27, "v2")):
            args = [signer, "verify", "--verbose", "--min-sdk-version", str(minimum)]
            if maximum is not None:
                args += ["--max-sdk-version", str(maximum)]
            report = subprocess.run([*args, str(fixture)], capture_output=True, text=True, check=True, timeout=30)
            self.assertIn("Verified using " + scheme + " scheme", report.stdout)
            if minimum == 29:
                self.assertIn("Verified using v2 scheme (APK Signature Scheme v2): false", report.stdout)
        config = dict(real_apksigner=signer, signed_fixture=str(fixture))
        before = hashlib.sha256(fixture.read_bytes()).hexdigest()
        code, output = self.run_script(*self.signing_args(cert_sha256=fingerprint), config=config)
        self.assertEqual(0, code, output)
        self.assertEqual(before, hashlib.sha256(self.output.read_bytes()).hexdigest())
        self.assertEqual(before, hashlib.sha256(fixture.read_bytes()).hexdigest())
        self.output.unlink()
        changed = bytearray(fixture.read_bytes())
        changed[len(changed) // 2] ^= 1
        tampered = self.root / "tampered.apk"
        tampered.write_bytes(changed)
        config["signed_fixture"] = str(tampered)
        code, output = self.run_script(*self.signing_args(cert_sha256=fingerprint), config=config)
        self.assertNotEqual(0, code, output)
        self.assertIn("signature verification failed", output)
        self.assertFalse(self.output.exists())
        self.assertFalse(list(self.root.glob(".plainpoddroid-sign.*")))

    def test_fingerprint_accepts_uppercase_colons(self):
        fingerprint = ":".join(["AB"] * 32)
        code, output = self.run_script(*self.signing_args(cert_sha256=fingerprint))
        self.assertEqual(0, code, output)

    def test_check_only_uses_no_key_or_sign_operation(self):
        code, output = self.run_script("--check-only", terminal=False)
        self.assertEqual(0, code, output)
        self.assertIn("preflight passed", output)
        self.assertFalse(self.output.exists())
        self.assertFalse(any(args[0] == "sign" for tool, args in self.calls() if tool == "apksigner"))

    def test_headless_signing_is_rejected(self):
        self.assert_failure("interactive terminal", terminal=False)
        self.assertFalse(self.calls())

    def test_bad_cli_and_missing_parameters(self):
        for args, message in [(["--password", "secret"], "Unknown option"),
                              (["--output"], "Missing value"),
                              (["--input", self.input, "--input", self.input], "Duplicate option"),
                              ([], "Signing requires"),
                              (["--check-only", "--alias", "plainpoddroid"], "cannot be combined")]:
            with self.subTest(args=args):
                self.assert_failure(message, args=args)

    def test_invalid_fingerprint(self):
        self.assert_failure("64 hex digits", args=self.signing_args(cert_sha256="xy" * 32))

    def test_key_inside_checkout_or_symlink_to_inside_is_rejected(self):
        inside = self.repo / "hidden.p12"
        shutil.copyfile(self.key, inside)
        inside.chmod(0o600)
        link = self.root / "outside-link.p12"
        link.symlink_to(inside)
        for key in (inside, link):
            with self.subTest(key=key):
                self.assert_failure("outside this checkout", args=self.signing_args(keystore=key))

    def test_shared_key_and_debug_alias_are_rejected(self):
        self.key.chmod(0o640)
        self.assert_failure("chmod 600")
        self.key.chmod(0o600)
        self.assert_failure("debug key", args=self.signing_args(alias="androiddebugkey"))

    def test_existing_output_is_preserved(self):
        self.output.write_bytes(b"existing-file")
        self.assert_failure("already exists")
        self.assertEqual(b"existing-file", self.output.read_bytes())

    def test_existing_directory_or_dangling_symlink_is_preserved(self):
        self.output.mkdir()
        self.assert_failure("already exists")
        self.output.rmdir()
        self.output.symlink_to(self.root / "absent.apk")
        self.assert_failure("already exists")
        self.assertTrue(self.output.is_symlink())

    def test_input_output_alias_is_preserved(self):
        self.assert_failure("already exists", args=self.signing_args(output=self.input))

    def test_wrong_package_api_version_and_debuggable_are_rejected(self):
        for config, message in [({"package": "io.github.jnesew.plainpoddroid.debug"}, "release package"),
                                ({"min_sdk": "28"}, "API 29"), ({"version": "0"}, "positive versionCode"),
                                ({"debuggable": True}, "debuggable APK"),
                                ({"manifest_failure": True}, "Cannot read")]:
            with self.subTest(config=config):
                self.assert_failure(message, config=config)
                self.assertFalse(self.output.exists())
        self.assertFalse(any(args[0] == "sign" for tool, args in self.calls() if tool == "apksigner"))

    def test_signed_input_is_rejected(self):
        self.input.write_bytes(b"already-signed")
        code, output = self.run_script(*self.signing_args())
        self.assertNotEqual(0, code, output)
        self.assertIn("already signed", output)
        self.assertEqual(b"already-signed", self.input.read_bytes())
        self.assertFalse(self.output.exists())

    def test_failed_signing_or_verification_never_publishes(self):
        for config, message in [({"signer_runtime_failure": True}, "signature tool cannot run"),
                                ({"alignment_failure": True}, "alignment failed"),
                                ({"sign_failure": True}, "signing failed"),
                                ({"verify_failure": True}, "signature verification failed"),
                                ({"v2_verify_failure": True}, "v2 signature verification failed"),
                                ({"fingerprint": "cd" * 32}, "expected fingerprint"),
                                ({"v2_fingerprint": "cd" * 32}, "expected fingerprint"),
                                ({"signers": "2"}, "exactly one"), ({"v2": "false"}, "v2 did not verify"),
                                ({"v2_signers": "2"}, "exactly one"),
                                ({"v3": "false"}, "v3 did not verify"),
                                ({"signed_alignment_failure": True}, "alignment verification failed")]:
            with self.subTest(config=config):
                self.assert_failure(message, config=config)
                self.assertFalse(self.output.exists())

    def test_output_race_never_overwrites_file(self):
        self.assert_failure("nothing was overwritten", config={"race": "file", "output": str(self.output)})
        self.assertEqual(b"concurrent-file", self.output.read_bytes())

    def test_output_race_never_follows_directory_symlink(self):
        race_dir = self.root / "race-target"
        race_dir.mkdir()
        self.assert_failure("nothing was overwritten", config={"race": "symlink", "output": str(self.output),
                                                               "race_dir": str(race_dir)})
        self.assertTrue(self.output.is_symlink())
        self.assertEqual([], list(race_dir.iterdir()))

    def test_sdk_override_and_missing_pinned_tools(self):
        code, output = self.run_script("--check-only", "--sdk", self.sdk, terminal=False)
        self.assertEqual(0, code, output)
        (self.sdk / "build-tools/36.0.0/apksigner").unlink()
        self.assert_failure("Required SDK tool not found")


@unittest.skipUnless(os.environ.get("PLAINPOD_TEST_ANDROID_SDK_ROOT") and
                     os.environ.get("PLAINPOD_TEST_UNSIGNED_APK"),
                     "Set PLAINPOD_TEST_ANDROID_SDK_ROOT and PLAINPOD_TEST_UNSIGNED_APK for real SDK preflight")
class RealSdkPreflightTest(unittest.TestCase):
    """Actual Android tools, unsigned fixtures only; never opens a key."""
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="plainpoddroid-real-sdk-tests-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.sdk = Path(os.environ["PLAINPOD_TEST_ANDROID_SDK_ROOT"])
        self.original = Path(os.environ["PLAINPOD_TEST_UNSIGNED_APK"])

    def preflight(self, apk):
        env = os.environ.copy()
        env["TMPDIR"] = str(self.root)
        result = subprocess.run(["bash", str(SCRIPT), "--check-only", "--sdk", str(self.sdk),
                                 "--input", str(apk)], env=env, capture_output=True, text=True, timeout=30)
        self.assertFalse(list(self.root.glob(".plainpoddroid-sign.*")))
        return result

    def fixture(self, *, debug=False, minimum=29):
        manifest = self.root / "AndroidManifest.xml"
        manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                            'package="io.github.jnesew.plainpoddroid" android:versionCode="1" '
                            'android:versionName="fixture"><application android:debuggable="' +
                            str(debug).lower() + '"/></manifest>')
        apk = self.root / "fixture.apk"
        subprocess.run([str(self.sdk / "build-tools/36.0.0/aapt2"), "link", "--manifest", str(manifest),
                        "--min-sdk-version", str(minimum), "--target-sdk-version", "36",
                        "-I", str(self.sdk / "platforms/android-37.0/android.jar"), "-o", str(apk)],
                       check=True, capture_output=True, text=True, timeout=30)
        return apk

    def test_existing_release_passes_without_modification(self):
        before = hashlib.sha256(self.original.read_bytes()).hexdigest()
        result = self.preflight(self.original)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(before, result.stdout)
        self.assertEqual(before, hashlib.sha256(self.original.read_bytes()).hexdigest())

    def test_real_debuggable_manifest_is_rejected(self):
        result = self.preflight(self.fixture(debug=True))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("debuggable APK", result.stderr)

    def test_real_incompatible_minimum_is_rejected(self):
        result = self.preflight(self.fixture(minimum=28))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("API 29", result.stderr)

    def test_corrupt_apk_is_rejected(self):
        apk = self.root / "corrupt.apk"
        apk.write_bytes(b"not a ZIP or Android manifest")
        result = self.preflight(apk)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Cannot read the APK manifest", result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
