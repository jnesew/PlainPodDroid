import argparse
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

MODULE = Path(__file__).resolve().parents[1] / "release_tools.py"
spec = importlib.util.spec_from_file_location("release_tools", MODULE)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


def manifest():
    lines = ["E: manifest",
             f'  A: package="{release.PACKAGE}"',
             '  A: versionName="1.0.0-rc.1"', "  A: versionCode=2", "  A: compileSdkVersion=37",
             "  E: uses-sdk", "    A: minSdkVersion=29", "    A: targetSdkVersion=36"]
    for permission in sorted(release.PERMISSIONS):
        lines += ["  E: uses-permission", f'    A: name="{permission}"']
    lines += ["  E: permission", f'    A: name="{release.PACKAGE}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"',
              "    A: protectionLevel=0x00000002", "  E: application",
              "    A: allowBackup=false", "    A: fullBackupContent=false", "    A: usesCleartextTraffic=false",
              "    A: networkSecurityConfig=@0x7f010001", "    A: dataExtractionRules=@0x7f010002"]
    for name, (kind, exported) in release.COMPONENTS.items():
        lines += [f"    E: {kind}", f'      A: name="{name}"',
                  f"      A: exported={str(exported).lower()}"]
        if name.endswith("JobService"):
            lines += ['      A: permission="android.permission.BIND_JOB_SERVICE"']
        if name.endswith("BluetoothValidationActivity"):
            lines += ['      A: permission="android.permission.BLUETOOTH_PRIVILEGED"']
        if name.endswith("ScheduleReceiver"):
            lines += ["      A: enabled=false"]
    return "\n".join(lines)


class ReleaseBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def zip(self, path, value=b"payload"):
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("classes.dex", value)
        return path

    def test_current_permission_and_component_contract_is_accepted(self):
        self.assertEqual("@0x7f010001",
                         release.verify_manifest(manifest(), "1.0.0-rc.1", 2)["networkSecurityConfig"])

    def test_permission_expansion_is_rejected(self):
        text = manifest().replace("android.permission.WAKE_LOCK", "android.permission.READ_CONTACTS")
        with self.assertRaisesRegex(release.ReleaseError, "permission surface"):
            release.verify_manifest(text, "1.0.0-rc.1", 2)

    def test_component_export_changes_are_rejected(self):
        text = manifest().replace("exported=false", "exported=true", 1)
        with self.assertRaisesRegex(release.ReleaseError, "export policy"):
            release.verify_manifest(text, "1.0.0-rc.1", 2)

    def test_unexpected_activity_is_rejected(self):
        with self.assertRaisesRegex(release.ReleaseError, "component surface"):
            release.verify_manifest(manifest() + '\n    E: activity\n      A: name="evil.Activity"',
                                    "1.0.0-rc.1", 2)

    def test_backup_cleartext_and_debuggable_changes_fail_closed(self):
        for setting in ("allowBackup", "fullBackupContent", "usesCleartextTraffic"):
            with self.subTest(setting=setting), self.assertRaises(release.ReleaseError):
                release.verify_manifest(manifest().replace(setting + "=false", setting + "=true"),
                                        "1.0.0-rc.1", 2)
        text = manifest().replace("  E: application", "  E: application\n    A: debuggable=true")
        with self.assertRaisesRegex(release.ReleaseError, "debuggable"):
            release.verify_manifest(text, "1.0.0-rc.1", 2)

    def test_version_or_sdk_mismatch_is_rejected(self):
        for before, after in (("versionCode=2", "versionCode=3"), ("minSdkVersion=29", "minSdkVersion=28"),
                              ("targetSdkVersion=36", "targetSdkVersion=37"),
                              ("compileSdkVersion=37", "compileSdkVersion=38")):
            with self.subTest(before=before), self.assertRaises(release.ReleaseError):
                release.verify_manifest(manifest().replace(before, after), "1.0.0-rc.1", 2)

    def test_missing_bluetooth_or_job_permission_is_rejected(self):
        for permission in ("BLUETOOTH_PRIVILEGED", "BIND_JOB_SERVICE"):
            with self.subTest(permission=permission), self.assertRaises(release.ReleaseError):
                release.verify_manifest(manifest().replace(permission, "INTERNET"), "1.0.0-rc.1", 2)

    def test_duplicate_zip_entries_are_rejected(self):
        path = self.zip(self.root / "duplicate.apk")
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            with zipfile.ZipFile(path, "a") as archive:
                archive.writestr("classes.dex", b"second")
        with self.assertRaisesRegex(release.ReleaseError, "Ambiguous"):
            release.zip_payload(path)

    def test_payload_comparison_detects_changes(self):
        first = self.zip(self.root / "first.apk")
        second = self.zip(self.root / "second.apk")
        self.assertEqual(release.zip_payload(first), release.zip_payload(second))
        self.zip(second, b"changed")
        self.assertNotEqual(release.zip_payload(first), release.zip_payload(second))

    def test_unsigned_zip_has_no_signing_block(self):
        self.assertFalse(release.has_signing_block(self.zip(self.root / "unsigned.apk")))

    def test_invalid_signature_block_is_not_treated_as_unsigned(self):
        path = self.zip(self.root / "corrupt-signed.apk")
        data = path.read_bytes()
        eocd = data.rfind(b"PK\x05\x06")
        offset = struct.unpack_from("<I", data, eocd + 16)[0]
        block = b"malformed APK signing block" + b"APK Sig Block 42"
        changed = bytearray(data[:offset] + block + data[offset:])
        struct.pack_into("<I", changed, eocd + len(block) + 16, offset + len(block))
        path.write_bytes(changed)
        self.assertTrue(release.has_signing_block(path))

    def test_zip_trailing_garbage_is_rejected(self):
        path = self.zip(self.root / "trailing.apk")
        path.write_bytes(path.read_bytes() + b"unexpected")
        with self.assertRaisesRegex(release.ReleaseError, "trailer"):
            release.has_signing_block(path)

    def test_existing_output_and_symlink_are_preserved(self):
        existing = self.root / "existing"; existing.mkdir()
        (existing / "keep").write_text("keep")
        for path in (existing, self.root / "link"):
            if path.name == "link": path.symlink_to(existing, target_is_directory=True)
            with self.assertRaisesRegex(release.ReleaseError, "already exists"):
                release.create_output(path)
        self.assertEqual("keep", (existing / "keep").read_text())

    def test_new_output_is_private_and_explicitly_incomplete(self):
        output = release.create_output(self.root / "new")
        self.assertTrue((output / "INCOMPLETE").is_file())
        self.assertEqual(0o700, output.stat().st_mode & 0o777)

    def test_dirty_source_is_rejected(self):
        with patch.object(release, "run", return_value=subprocess.CompletedProcess([], 0, " M file\n", "")):
            with self.assertRaisesRegex(release.ReleaseError, "Commit task changes"):
                release.checkpoint(self.root)

    def test_bad_version_names_and_duplicate_properties_are_rejected(self):
        path = self.root / "gradle.properties"
        for text in ("plainPodDroidVersionName=../../escape\nplainPodDroidVersionCode=2\n",
                     "plainPodDroidVersionName=1.0.0\nplainPodDroidVersionCode=0\n",
                     "plainPodDroidVersionName=1.0.0\nplainPodDroidVersionCode=2\nplainPodDroidVersionCode=3\n"):
            path.write_text(text)
            with self.assertRaises(release.ReleaseError): release.source_version(self.root)

    def test_guava_gate_rejects_missing_or_retained_deserialization_path(self):
        path = self.root / "usage.txt"; path.write_text("com.google.common.collect.CompactHashMap\n")
        with self.assertRaisesRegex(release.ReleaseError, "survives shrinking"):
            release.verify_guava(path)

    def test_guava_gate_accepts_recorded_removal(self):
        path = self.root / "usage.txt"
        path.write_text("com.google.common.collect.CompactHashMap:\n"
                        "    private void readObject(java.io.ObjectInputStream)\n"
                        "com.google.common.collect.CompactHashSet\n"
                        "com.google.common.collect.MapMakerInternalMap\n")
        release.verify_guava(path)

    def test_trusted_certificate_requires_full_sha256(self):
        expected = "ab" * 32
        self.assertEqual(expected, release.normalize_certificate(":".join(["AB"] * 32)))
        with self.assertRaises(release.ReleaseError): release.normalize_certificate("1234")

    def test_resource_variants_are_scoped_to_the_requested_id(self):
        resources = ("    resource 0x7f010001 xml/network_security_config\n"
                     "      () (file) res/net.xml type=XML\n"
                     "      (v36) (file) res/net36.xml type=XML\n"
                     "    resource 0x7f010002 xml/other\n"
                     "      () (file) res/other.xml type=XML\n")
        self.assertEqual([("", "res/net.xml"), ("v36", "res/net36.xml")],
                         release.resource_files(resources, "@0x7f010001"))

    def test_fdroid_recipe_pins_commit_and_real_version(self):
        text = release.public_fdroid("a" * 40, "1.0.0", 3)
        self.assertIn("commit: " + "a" * 40, text)
        self.assertIn("versionCode: 3", text)
        self.assertNotIn("AllowedAPKSigningKeys", text)

    def package_fixture(self):
        source = self.root / "source"; source.mkdir()
        candidate = self.root / "candidate"; candidate.mkdir()
        (candidate / "READY").write_text("ready")
        for path in release.LEGAL:
            for folder in (source, candidate):
                target = folder / path; target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("legal " + path)
        notes = source / "docs/release-notes/1.0.0-rc.1.md"; notes.parent.mkdir(parents=True)
        notes.write_text("notes")
        (candidate / "RELEASE_NOTES.md").write_text("notes")
        (candidate / "r8-usage.txt").write_text("usage")
        unsigned = self.zip(candidate / "app-unsigned.apk")
        signed = self.zip(self.root / "signed.apk")
        inputs = ("gradle/verification-metadata.xml", "app/gradle.lockfile",
                  "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
                  "third-party/runtime-dependencies.json")
        for name in inputs:
            target = source / name; target.parent.mkdir(parents=True, exist_ok=True); target.write_text(name)
        provenance = {"schema": 1, "state": "unsigned-candidate", "source_commit": "a" * 40,
                      "apk": {"version_name": "1.0.0-rc.1", "version_code": 2, "sha256": release.digest(unsigned)},
                      "input_hashes": {name: release.digest(source / name) for name in inputs}}
        release.write_json(candidate / "PROVENANCE.json", provenance)
        args = argparse.Namespace(candidate=candidate, apk=signed, cert_sha256="ab" * 32,
                                  output=self.root / "packet", sdk=None)
        return source, candidate, args

    def package_patches(self, source):
        import contextlib
        stack = contextlib.ExitStack()
        stack.enter_context(patch.object(release, "ROOT", source))
        stack.enter_context(patch.object(release, "source_version", return_value=("1.0.0-rc.1", 2)))
        stack.enter_context(patch.object(release, "checkpoint", return_value="a" * 40))
        stack.enter_context(patch.object(release, "sdk_path", return_value=self.root / "sdk"))
        return stack

    def test_packet_preserves_verified_snapshot_and_records_actual_certificate(self):
        source, candidate, args = self.package_fixture()
        def verify(snapshot, *unused, **keywords):
            # Simulate another process changing the caller's file after snapshotting.
            self.zip(args.apk, b"changed after snapshot")
            self.assertEqual(b"payload", release.zip_payload(snapshot)["classes.dex"])
            return {"sha256": release.digest(snapshot), "certificate_sha256": keywords["certificate"]}
        with self.package_patches(source), patch.object(release, "verify_apk", side_effect=verify):
            release.package_release(args)
        self.assertFalse((args.output / "INCOMPLETE").exists())
        self.assertIn("PlainPodDroid-1.0.0-rc.1.apk", (args.output / "SHA256SUMS").read_text())
        recipe = (args.output / "fdroid/metadata" / f"{release.PACKAGE}.yml").read_text()
        self.assertIn("  - " + "ab" * 32, recipe)

    def test_signed_payload_change_does_not_create_distributable_packet(self):
        source, candidate, args = self.package_fixture()
        self.zip(args.apk, b"different")
        with self.package_patches(source), patch.object(release, "verify_apk", return_value={}):
            with self.assertRaisesRegex(release.ReleaseError, "payload differs"):
                release.package_release(args)
        self.assertTrue((args.output / "INCOMPLETE").is_file())
        self.assertFalse((args.output / "SHA256SUMS").exists())

    def test_unsigned_candidate_tampering_is_rejected_before_output_creation(self):
        source, candidate, args = self.package_fixture()
        self.zip(candidate / "app-unsigned.apk", b"tampered")
        with self.package_patches(source):
            with self.assertRaisesRegex(release.ReleaseError, "hash changed"):
                release.package_release(args)
        self.assertFalse(args.output.exists())

    def test_candidate_from_another_source_commit_is_rejected(self):
        source, candidate, args = self.package_fixture()
        with self.package_patches(source), patch.object(release, "checkpoint", return_value="b" * 40):
            with self.assertRaisesRegex(release.ReleaseError, "exact clean source"):
                release.package_release(args)

    def test_changed_candidate_notes_remain_incomplete(self):
        source, candidate, args = self.package_fixture()
        (candidate / "RELEASE_NOTES.md").write_text("replaced notes")
        with self.package_patches(source), patch.object(release, "verify_apk", return_value={}):
            with self.assertRaisesRegex(release.ReleaseError, "notes changed"):
                release.package_release(args)
        self.assertFalse((args.output / "SHA256SUMS").exists())

    def test_failed_build_gate_retains_logs_without_ready_marker(self):
        source = self.root / "source"; source.mkdir()
        (source / "docs/release-notes").mkdir(parents=True)
        (source / "docs/release-notes/1.0.0-rc.1.md").write_text("notes")
        args = argparse.Namespace(output=self.root / "failed-candidate", sdk=None, offline=True)
        with self.package_patches(source), patch.object(
                release, "run", return_value=subprocess.CompletedProcess([], 1, "", "gate failure")):
            with self.assertRaisesRegex(release.ReleaseError, "gate failed"):
                release.prepare(args)
        self.assertTrue((args.output / "INCOMPLETE").exists())
        self.assertEqual("gate failure", (args.output / "gate-1.log").read_text())
        self.assertFalse((args.output / "READY").exists())

    def test_release_layout_defaults_to_private_home_area_without_creating_it(self):
        with patch.dict(release.os.environ, {}, clear=True), patch.object(Path, "home", return_value=self.root):
            paths = release.release_layout("1.0.0-rc.1", "a" * 40)
        self.assertEqual(self.root / "PlainPodDroid-releases/1.0.0-rc.1/aaaaaaaaaaaa/candidate",
                         paths["candidate"])
        self.assertFalse(paths["root"].exists())

    def test_explicit_release_root_precedes_environment_and_separates_sources(self):
        with patch.dict(release.os.environ, {"PLAINPOD_RELEASE_ROOT": str(self.root / "environment")}):
            environment = release.release_layout("1.0.0", "a" * 40)
            first = release.release_layout("1.0.0", "a" * 40, self.root / "chosen")
            second = release.release_layout("1.0.0", "b" * 40, self.root / "chosen")
            next_version = release.release_layout("1.0.1", "a" * 40, self.root / "chosen")
        self.assertEqual(self.root / "environment", environment["root"])
        self.assertEqual(self.root / "chosen", first["root"])
        self.assertEqual(3, len({first["base"], second["base"], next_version["base"]}))

    def test_release_layout_rejects_untrusted_path_components(self):
        for name, commit in (("../../escape", "a" * 40), ("1.0.0", "../escape")):
            with self.assertRaisesRegex(release.ReleaseError, "valid version and source"):
                release.release_layout(name, commit, self.root / "releases")

    def test_default_parents_are_private_and_existing_artifacts_are_preserved(self):
        paths = release.release_layout("1.0.0", "a" * 40, self.root / "releases")
        path = release.release_output(None, paths, "candidate")
        for directory in (paths["root"], paths["base"].parent, paths["base"]):
            self.assertEqual(0o700, directory.stat().st_mode & 0o777)
        self.assertFalse(path.exists())
        release.create_output(path)
        (path / "keep").write_text("existing candidate")
        with self.assertRaisesRegex(release.ReleaseError, "already exists"):
            release.create_output(release.release_output(None, paths, "candidate"))
        self.assertEqual("existing candidate", (path / "keep").read_text())

    def test_default_parent_symlink_is_not_followed(self):
        paths = release.release_layout("1.0.0", "a" * 40, self.root / "releases")
        paths["root"].mkdir()
        other = self.root / "other"; other.mkdir()
        paths["base"].parent.symlink_to(other, target_is_directory=True)
        with self.assertRaisesRegex(release.ReleaseError, "regular directory"):
            release.release_output(None, paths, "candidate")
        self.assertEqual([], list(other.iterdir()))

    def test_explicit_output_keeps_existing_parent_creation_contract(self):
        paths = release.release_layout("1.0.0", "a" * 40, self.root / "unused")
        chosen = self.root / "missing/chosen"
        self.assertEqual(chosen, release.release_output(chosen, paths, "packet"))
        self.assertFalse(paths["root"].exists())
        self.assertFalse(chosen.parent.exists())

    def test_default_prepare_retains_failed_gate_evidence(self):
        source = self.root / "source"; source.mkdir()
        (source / "docs/release-notes").mkdir(parents=True)
        (source / "docs/release-notes/1.0.0-rc.1.md").write_text("notes")
        args = argparse.Namespace(output=None, release_root=self.root / "releases", sdk=None, offline=True)
        paths = release.release_layout("1.0.0-rc.1", "a" * 40, args.release_root)
        with self.package_patches(source), patch.object(
                release, "run", return_value=subprocess.CompletedProcess([], 1, "", "failed gate")):
            with self.assertRaisesRegex(release.ReleaseError, "gate failed"):
                release.prepare(args)
        self.assertTrue((paths["candidate"] / "INCOMPLETE").is_file())
        self.assertFalse((paths["candidate"] / "READY").exists())

    def test_package_uses_automatic_inputs_and_does_not_replace_existing_packet(self):
        source, candidate, args = self.package_fixture()
        args.release_root = self.root / "releases"
        paths = release.release_layout("1.0.0-rc.1", "a" * 40, args.release_root)
        candidate.rename(release.release_output(None, paths, "candidate"))
        args.apk.rename(release.release_output(None, paths, "signed"))
        args.candidate = args.apk = args.output = None
        with self.package_patches(source), patch.object(release, "verify_apk", return_value={}):
            release.package_release(args)
            original = (paths["packet"] / "SHA256SUMS").read_bytes()
            with self.assertRaisesRegex(release.ReleaseError, "already exists"):
                release.package_release(args)
        self.assertEqual(original, (paths["packet"] / "SHA256SUMS").read_bytes())

    def sign_options(self, candidate):
        return argparse.Namespace(candidate=candidate, output=None, release_root=self.root / "releases",
                                  sdk=None, keystore=str(self.root / "inert-key-file"),
                                  alias="release", cert_sha256="ab" * 32)

    def test_prepared_sign_uses_verified_snapshot_and_inherits_terminal(self):
        source, candidate, unused = self.package_fixture()
        args = self.sign_options(candidate)
        paths = release.release_layout("1.0.0-rc.1", "a" * 40, args.release_root)
        def verify(snapshot, *unused, **keywords):
            self.assertEqual(b"payload", release.zip_payload(snapshot)["classes.dex"])
            self.zip(candidate / "app-unsigned.apk", b"concurrent candidate edit")
        def helper(command, **keywords):
            self.assertEqual({"cwd": source}, keywords)  # No password/input/capture arguments.
            snapshot = Path(command[command.index("--input") + 1])
            self.assertNotEqual(candidate / "app-unsigned.apk", snapshot)
            self.assertEqual(b"payload", release.zip_payload(snapshot)["classes.dex"])
            self.assertEqual(str(paths["signed"]), command[command.index("--output") + 1])
            self.assertEqual(args.keystore, command[command.index("--keystore") + 1])
            self.assertEqual("ab" * 32, command[command.index("--cert-sha256") + 1])
            return subprocess.CompletedProcess(command, 0)
        with self.package_patches(source), patch.object(release.sys.stdin, "isatty", return_value=True), \
                patch.object(release.sys.stdout, "isatty", return_value=True), \
                patch.object(release, "verify_apk", side_effect=verify), \
                patch.object(release.subprocess, "run", side_effect=helper):
            release.sign_prepared(args)

    def test_prepared_sign_rejects_headless_invocation_before_helper_or_output(self):
        source, candidate, unused = self.package_fixture()
        args = self.sign_options(candidate)
        with self.package_patches(source), patch.object(release.sys.stdin, "isatty", return_value=False), \
                patch.object(release.subprocess, "run") as helper:
            with self.assertRaisesRegex(release.ReleaseError, "interactive terminal"):
                release.sign_prepared(args)
            helper.assert_not_called()
        self.assertFalse(args.release_root.exists())

    def test_prepared_sign_rejects_tampered_candidate_before_helper(self):
        source, candidate, unused = self.package_fixture()
        self.zip(candidate / "app-unsigned.apk", b"tampered")
        with self.package_patches(source), patch.object(release.subprocess, "run") as helper:
            with self.assertRaisesRegex(release.ReleaseError, "hash changed"):
                release.sign_prepared(self.sign_options(candidate))
            helper.assert_not_called()

    def test_prepared_sign_preserves_existing_signed_output(self):
        source, candidate, unused = self.package_fixture()
        args = self.sign_options(candidate)
        paths = release.release_layout("1.0.0-rc.1", "a" * 40, args.release_root)
        output = release.release_output(None, paths, "signed"); output.write_bytes(b"existing APK")
        with self.package_patches(source), patch.object(release.sys.stdin, "isatty", return_value=True), \
                patch.object(release.sys.stdout, "isatty", return_value=True), \
                patch.object(release.subprocess, "run") as helper:
            with self.assertRaisesRegex(release.ReleaseError, "already exists"):
                release.sign_prepared(args)
            helper.assert_not_called()
        self.assertEqual(b"existing APK", output.read_bytes())

    def test_prepared_sign_reports_helper_failure_and_removes_snapshot(self):
        source, candidate, unused = self.package_fixture()
        snapshots = []
        def helper(command, **keywords):
            snapshots.append(Path(command[command.index("--input") + 1]))
            return subprocess.CompletedProcess(command, 1)
        with self.package_patches(source), patch.object(release.sys.stdin, "isatty", return_value=True), \
                patch.object(release.sys.stdout, "isatty", return_value=True), \
                patch.object(release, "verify_apk", return_value={}), \
                patch.object(release.subprocess, "run", side_effect=helper):
            with self.assertRaisesRegex(release.ReleaseError, "helper failed"):
                release.sign_prepared(self.sign_options(candidate))
        self.assertTrue(snapshots)
        self.assertFalse(snapshots[0].parent.exists())


if __name__ == "__main__":
    unittest.main()
