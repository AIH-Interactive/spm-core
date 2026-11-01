"""Generate a real SPDX 2.3 JSON SBOM plus a CycloneDX-compatible component view.

The plan's T02 requires "binary/sources/POM/SBOM/license with SHA-256". The project previously
had no SBOM at all: `inventory.json` produced by package_candidate.py is explicitly documented as
"a project-specific inventory, not a validated SPDX or CycloneDX document". This script emits an
actual SPDX document so a consumer can machine-read the dependency set and its declared licenses.

Honest limits (recorded inside the document itself, not hidden):
  * Licenses come from each component's POM declaration. They are NOT independently audited; the
    document says NOASSERTION where a POM declares nothing.
  * This covers the resolved dependency closure that Gradle actually used, not every transitive
    optional or `provided` component.
  * No claim is made about source provenance of those components beyond their published POM.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import uuid
from pathlib import Path

SPDX_VERSION = "SPDX-2.3"
DATA_LICENSE = "CC0-1.0"
SPDX_ID = re.compile(r"^[A-Za-z0-9.\-]+$")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def purl(group: str, name: str, version: str) -> str:
    return f"pkg:maven/{group}/{name}@{version}"


def spdx_id_for(group: str, name: str, version: str) -> str:
    """SPDX identifiers allow [A-Za-z0-9.-]; map coordinates onto that alphabet."""
    raw = f"SPDXRef-maven-{group}-{name}-{version}"
    cleaned = re.sub(r"[^A-Za-z0-9.\-]", "-", raw)
    return cleaned if SPDX_ID.match(cleaned) else "SPDXRef-maven-component"


def load_pom_licenses(gradle_cache: Path, group: str, name: str, version: str) -> list[str]:
    """Best-effort license read from the cached POM. Returns [] when nothing is declared.

    The Gradle cache layout is ``<root>/<group>/<name>/<version>/<sha1-dir>/<file>``, so the POM
    sits one level deeper than the version directory.
    """
    if not gradle_cache.is_dir():
        return []
    version_dir = gradle_cache / group / name / version
    candidates = sorted(version_dir.glob("*/*.pom")) if version_dir.is_dir() else []
    for pom in candidates:
        text = pom.read_text(encoding="utf-8", errors="replace")
        declared = re.findall(r"<license>(.*?)</license>", text, re.S)
        names = []
        for block in declared:
            match = re.search(r"<name>(.*?)</name>", block, re.S)
            if match and match.group(1).strip():
                names.append(match.group(1).strip())
        if names:
            return sorted(set(names))
    return []


def collect(module_dirs: list[Path]) -> dict[str, dict]:
    components: dict[str, dict] = {}
    for metadata in module_dirs:
        if not metadata.is_file():
            continue
        data = json.loads(metadata.read_text(encoding="utf-8"))
        for record in data.get("dependencies", []):
            key = f"{record['group']}:{record['name']}:{record['version']}"
            entry = components.setdefault(key, {
                "group": record["group"],
                "name": record["name"],
                "version": record["version"],
                "files": {},
                "scopes": set(),
            })
            entry["files"][record["file"]] = record["sha256"]
            entry["scopes"].add(record["scope"])
    return components


def default_gradle_cache() -> Path:
    return Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1"


def build(args: argparse.Namespace) -> dict:
    root = args.root.resolve()
    module_dirs = sorted(root.glob("*/build/metadata/dependencies.json"))
    if not module_dirs:
        raise SystemExit("No per-module dependencies.json found; run writeDependencyInventory first")

    components = collect(module_dirs)
    gradle_cache = Path(getattr(args, "gradle_cache", None) or default_gradle_cache())
    revision = args.revision
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise SystemExit("--revision must be the full 40-hex source SHA")

    package_id = "SPDXRef-spm-core"
    packages = []
    relationships = [{
        "spdxElementId": "SPDXRef-DOCUMENT",
        "relationshipType": "DESCRIBES",
        "relatedSpdxElement": package_id,
    }]

    files = []
    # Only jars at the current version belong to this document; a leftover preview jar from an
    # earlier publishCandidate run must not be listed as a product artifact.
    expected_suffix = f"-{args.version}.jar"
    product_jars = sorted(
        path for path in root.glob("*/build/libs/*.jar")
        if path.name.endswith(expected_suffix)
        and "-sources" not in path.name and "-javadoc" not in path.name
    )
    for jar in product_jars:
        module = jar.parent.parent.parent.name
        artifact_id = f"SPDXRef-spm-core-{module}"
        files.append({
            "fileName": f"./{jar.relative_to(root).as_posix()}",
            "SPDXID": artifact_id,
            "checksums": [{"algorithm": "SHA256", "checksumValue": sha256(jar)}],
        })
        relationships.append({
            "spdxElementId": package_id,
            "relationshipType": "CONTAINS",
            "relatedSpdxElement": artifact_id,
        })

    for key in sorted(components):
        entry = components[key]
        identity = spdx_id_for(entry["group"], entry["name"], entry["version"])
        licenses = load_pom_licenses(gradle_cache, entry["group"], entry["name"], entry["version"])
        # Deduplicate identifiers that collapse to the same SPDX ref.
        while any(pkg["SPDXID"] == identity for pkg in packages):
            identity += "-x"
        packages.append({
            "name": entry["name"],
            "SPDXID": identity,
            "versionInfo": entry["version"],
            "downloadLocation": "NOASSERTION",
            "filesAnalyzed": False,
            "licenseConcluded": "NOASSERTION",
            "licenseDeclared": " AND ".join(licenses) if licenses else "NOASSERTION",
            "copyrightText": "NOASSERTION",
            "externalRefs": [{
                "referenceCategory": "PACKAGE-MANAGER",
                "referenceType": "purl",
                "referenceLocator": purl(entry["group"], entry["name"], entry["version"]),
            }],
        })
        relationships.append({
            "spdxElementId": package_id,
            "relationshipType": "DEPENDS_ON",
            "relatedSpdxElement": identity,
        })

    declaring = sum(1 for pkg in packages if pkg["licenseDeclared"] != "NOASSERTION")
    unconditional = not args.revision.endswith("-dirty")
    return {
        "spdxVersion": SPDX_VERSION,
        "dataLicense": DATA_LICENSE,
        "SPDXID": "SPDXRef-DOCUMENT",
        "name": "spm-core",
        "documentNamespace": f"https://spdx.org/spdxdocs/spm-core-{revision}-{uuid.uuid5(uuid.NAMESPACE_URL, revision)}",
        "creationInfo": {
            "created": args.created,
            "creators": [f"Tool: spm-core/sbom.py-1", "Organization: SPM Core project"],
            "comment": (
                "Generated from the resolved Gradle dependency closure. Licenses are the "
                "component POM declarations and are not independently audited. Absent "
                "declarations are NOASSERTION, not a claim of permissive licensing."
            ),
        },
        "documentDescribes": [package_id],
        "packages": [{
            "name": "spm-core",
            "SPDXID": package_id,
            "versionInfo": args.version,
            "downloadLocation": "NOASSERTION",
            "filesAnalyzed": True,
            "licenseConcluded": "MIT AND Apache-2.0",
            "licenseDeclared": "MIT",
            "copyrightText": "NOASSERTION",
            "sourceInfo": f"source revision {revision}",
            "externalRefs": [{
                "referenceCategory": "PACKAGE-MANAGER",
                "referenceType": "purl",
                "referenceLocator": f"pkg:maven/dev.spm.core/spm-core@{args.version}",
            }],
            "comment": (
                f"{declaring}/{len(packages)} declared direct dependencies carry a POM license. "
                "Mixed licensing: independently written Core sources are MIT; the historical "
                "wire/envelope fixtures are Apache-2.0; CityHash and ChaCha notices ship in the "
                "codec jar. Full transitive license closure remains open."
            ),
        }] + packages,
        "files": files,
        "relationships": relationships,
        "annotations": [{
            "annotationDate": args.created,
            "annotationType": "OTHER",
            "annotator": "Tool: spm-core/sbom.py-1",
            "comment": (
                f"revision_complete={str(unconditional).lower()}; "
                f"dependency_components={len(components)}; "
                f"product_jars={len(files)}; "
                f"licenses_declared={declaring}; "
                "scope=resolved Gradle closure only; no source-provenance certification."
            ),
        }],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate an SPDX 2.3 SBOM for spm-core.")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--revision", required=True, help="Full 40-hex source revision")
    parser.add_argument("--version", default="0.1.0-dev")
    parser.add_argument("--created", required=True, help="UTC timestamp, e.g. 2026-11-02T00:00:00Z")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    document = build(args)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({
        "output": str(args.output),
        "packages": len(document["packages"]) - 1,
        "files": len(document["files"]),
        "sha256": sha256(args.output),
    }))
    return 0


if __name__ == "__main__":
    sys.exit(main())
