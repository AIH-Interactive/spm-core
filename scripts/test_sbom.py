"""Regressions for the SPDX SBOM generator.

These run without Gradle: each test builds a synthetic module tree, so the document shape and its
honesty rules are checked even on a machine that has never run `writeDependencyInventory`.
"""

from __future__ import annotations

import json
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path

import sbom


REVISION = "a" * 40


def make_module(root: Path, module: str, dependencies: list[dict]) -> Path:
    metadata = root / module / "build" / "metadata" / "dependencies.json"
    metadata.parent.mkdir(parents=True, exist_ok=True)
    metadata.write_text(json.dumps({"project": module, "dependencies": dependencies}), encoding="utf-8")
    return metadata


def make_jar(root: Path, module: str, version: str = "0.1.0-dev", suffix: str = "") -> Path:
    libs = root / module / "build" / "libs"
    libs.mkdir(parents=True, exist_ok=True)
    jar = libs / f"{module}-{version}{suffix}.jar"
    jar.write_bytes(f"{module}-payload".encode())
    return jar


def make_pom(root: Path, cache: Path, group: str, name: str, version: str, license_name: str | None) -> None:
    directory = cache / group / name / version / "deadbeef"
    directory.mkdir(parents=True, exist_ok=True)
    license_block = f"<licenses><license><name>{license_name}</name></license></licenses>" if license_name else ""
    (directory / f"{name}-{version}.pom").write_text(
        f"<project><groupId>{group}</groupId>{license_block}</project>", encoding="utf-8")


def build_document(root: Path, cache: Path, revision: str = REVISION, version: str = "0.1.0-dev") -> dict:
    args = Namespace(root=root, revision=revision, version=version,
                     created="2026-11-02T00:00:00Z", output=root / "sbom.json",
                     gradle_cache=cache)
    return sbom.build(args)


class SbomDocumentTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name) / "core"
        self.cache = Path(self.temporary.name) / "gradle" / "caches" / "modules-2" / "files-2.1"
        self.root.mkdir(parents=True)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def dependency(self, group: str, name: str, version: str, scope: str = "runtimeClasspath") -> dict:
        return {"group": group, "name": name, "version": version,
                "file": f"{name}-{version}.jar", "sha256": "b" * 64, "scope": scope}

    def test_document_declares_spdx_23_and_describes_the_product(self) -> None:
        make_module(self.root, "api", [self.dependency("com.example", "lib", "1.0")])
        make_jar(self.root, "api")
        document = build_document(self.root, self.cache)
        self.assertEqual("SPDX-2.3", document["spdxVersion"])
        self.assertEqual("CC0-1.0", document["dataLicense"])
        self.assertIn("SPDXRef-spm-core", document["documentDescribes"])
        self.assertTrue(document["documentNamespace"].startswith("https://spdx.org/spdxdocs/"))

    def test_every_dependency_becomes_a_package_with_a_purl(self) -> None:
        make_module(self.root, "api", [self.dependency("com.example", "lib", "1.0")])
        make_jar(self.root, "api")
        document = build_document(self.root, self.cache)
        dependencies = [p for p in document["packages"] if p["SPDXID"] != "SPDXRef-spm-core"]
        self.assertEqual(1, len(dependencies))
        self.assertEqual("pkg:maven/com.example/lib@1.0",
                         dependencies[0]["externalRefs"][0]["referenceLocator"])

    def test_a_missing_license_declaration_is_noassertion_not_a_guess(self) -> None:
        make_module(self.root, "api", [self.dependency("com.example", "lib", "1.0")])
        make_jar(self.root, "api")
        document = build_document(self.root, self.cache)
        dependency = [p for p in document["packages"] if p["SPDXID"] != "SPDXRef-spm-core"][0]
        self.assertEqual("NOASSERTION", dependency["licenseDeclared"],
                         "an absent POM license must never be reported as a permissive license")

    def test_a_pom_license_is_read_from_the_real_cache_layout(self) -> None:
        make_module(self.root, "api", [self.dependency("com.example", "lib", "1.0")])
        make_jar(self.root, "api")
        make_pom(self.root, self.cache, "com.example", "lib", "1.0", "Apache-2.0")
        document = build_document(self.root, self.cache)
        dependency = [p for p in document["packages"] if p["SPDXID"] != "SPDXRef-spm-core"][0]
        self.assertEqual("Apache-2.0", dependency["licenseDeclared"])

    def test_product_jars_are_hashed_and_contained(self) -> None:
        make_module(self.root, "api", [])
        jar = make_jar(self.root, "api")
        document = build_document(self.root, self.cache)
        contained = [r for r in document["relationships"] if r["relationshipType"] == "CONTAINS"]
        self.assertEqual(1, len(contained))
        listed = document["files"][0]
        self.assertEqual(sbom.sha256(jar), listed["checksums"][0]["checksumValue"])
        self.assertTrue(listed["fileName"].startswith("./api/build/libs/"))

    def test_a_stale_preview_jar_from_an_earlier_version_is_excluded(self) -> None:
        make_module(self.root, "api", [])
        make_jar(self.root, "api")
        make_jar(self.root, "api", version="0.1.0-preview.0123456789ab")
        document = build_document(self.root, self.cache)
        self.assertEqual(1, len(document["files"]), "only the current version belongs in the document")
        self.assertNotIn("preview", document["files"][0]["fileName"])

    def test_sources_and_javadoc_jars_are_not_counted_as_products(self) -> None:
        make_module(self.root, "api", [])
        make_jar(self.root, "api")
        make_jar(self.root, "api", suffix="-sources")
        make_jar(self.root, "api", suffix="-javadoc")
        document = build_document(self.root, self.cache)
        self.assertEqual(1, len(document["files"]))

    def test_shared_dependencies_appear_once_across_modules(self) -> None:
        make_module(self.root, "api", [self.dependency("com.example", "lib", "1.0")])
        make_module(self.root, "codec", [self.dependency("com.example", "lib", "1.0", "testRuntimeClasspath")])
        make_jar(self.root, "api")
        make_jar(self.root, "codec")
        document = build_document(self.root, self.cache)
        dependencies = [p for p in document["packages"] if p["SPDXID"] != "SPDXRef-spm-core"]
        self.assertEqual(1, len(dependencies), "the same GAV must not be duplicated")

    def test_missing_inventory_is_an_explicit_error(self) -> None:
        with self.assertRaises(SystemExit) as raised:
            build_document(self.root, self.cache)
        self.assertIn("dependencies.json", str(raised.exception))

    def test_a_revision_that_is_not_a_full_sha_is_refused(self) -> None:
        make_module(self.root, "api", [])
        make_jar(self.root, "api")
        with self.assertRaises(SystemExit) as raised:
            build_document(self.root, self.cache, revision="abc123")
        self.assertIn("40-hex", str(raised.exception))

    def test_the_annotation_records_scope_limits_honestly(self) -> None:
        make_module(self.root, "api", [self.dependency("com.example", "lib", "1.0")])
        make_jar(self.root, "api")
        document = build_document(self.root, self.cache)
        comment = document["annotations"][0]["comment"]
        self.assertIn("scope=resolved Gradle closure only", comment)
        self.assertIn("no source-provenance certification", comment)
        self.assertIn("licenses_declared=", comment)

    def test_identifiers_are_spdx_safe(self) -> None:
        make_module(self.root, "api", [self.dependency("com.example.sub", "weird_name", "1.0")])
        make_jar(self.root, "api")
        document = build_document(self.root, self.cache)
        for package in document["packages"]:
            self.assertRegex(package["SPDXID"], r"^[A-Za-z0-9.\-]+$",
                             f"SPDX identifiers may not contain other characters: {package['SPDXID']}")


if __name__ == "__main__":
    unittest.main()
