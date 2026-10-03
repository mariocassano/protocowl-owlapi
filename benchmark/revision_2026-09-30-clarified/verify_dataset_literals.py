#!/usr/bin/env python3
"""Read-only byte verification using the professor's fixed-point clarification.

Offsets are bound to SHA-256 of the inspected files. Decode SVarInt first,
then restore negative whole parts by adding one and preserving their sign.
"""
from pathlib import Path
import hashlib
import json
import sys

name = "kb-owl-syntax-unraveled-depth-2-km-relevant-cardinalities-disjointness-constant-classes-value-nominals.ofn"
root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[3] / "dataset_onto"
functional = root / "functional" / (name + "_functional.owl")
lines = functional.read_text().splitlines()
cases = {
    "std": ("fbc3c4d569e9df12a615cbb85d4761dca2e61812978bac1c957d791f4fca0fd4",
            {"Benzoate": (154180, "-1.0"), "Azide": (152037, "-3.0"), "Boron": (157598, "2.04")}),
    "MIS_128": ("926758a281e6475048df3b1546d98bd260fdc9ef29ae658215d397cd02b3fb65",
                {"Benzoate": (181863, "-1.0"), "Azide": (175010, "-3.0"), "Boron": (195800, "2.04")}),
}
findings = []
for variant, (expected_hash, examples) in cases.items():
    file = root / "protocowl" / variant / (name + "_protocowl.owl")
    raw = file.read_bytes()
    actual_hash = hashlib.sha256(raw).hexdigest()
    if actual_hash != expected_hash:
        sys.exit(f"Different dataset revision: do not reuse these offsets for {file}. SHA256={actual_hash}")
    for subject, (offset, expected) in examples.items():
        header, encoded_whole, fraction = raw[offset:offset+3]
        assert header == 0x16 and encoded_whole < 128 and fraction < 128
        # Specification section 3: standard Zig-Zag, including -1 -> 1 and -2 -> 3.
        whole = (encoded_whole >> 1) ^ -(encoded_whole & 1)
        whole_text = "-" + str(abs(whole + 1)) if whole < 0 else str(whole)
        decoded = f"{whole_text}.{str(fraction)[::-1]}"
        line_number, line = next((n, line) for n, line in enumerate(lines, 1)
                                 if line.startswith(f"SubClassOf(<http://www.projecthalo.com/aura#{subject}>")
                                 and f'"{expected}"^^xsd:float' in line)
        findings.append({"variant": variant, "subject": subject, "functional_line": line_number,
                         "expected": expected, "offset_zero_based": offset,
                         "hex": raw[offset:offset+3].hex(" "), "decoded_with_clarification": decoded,
                         "matches": expected == decoded})
print(json.dumps(findings, indent=2))
sys.exit(0 if all(x["matches"] for x in findings) else 1)
