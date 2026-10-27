"""Filter native libraries while preserving ZIP metadata; fail on missing ABIs."""
import sys
import zipfile

source, target, architecture = sys.argv[1:]
allowed = {"armeabi-v7a", "arm64-v8a"} if architecture == "universal" else {architecture}
if not allowed <= {"armeabi-v7a", "arm64-v8a"}:
    raise ValueError("Unsupported ABI")
found = set()
with zipfile.ZipFile(source) as src, zipfile.ZipFile(target, "w") as dst:
    for entry in src.infolist():
        name = entry.filename
        if name.startswith("lib/"):
            abi = name.split("/")[1]
            if abi not in allowed:
                continue
            if name.endswith(".so"):
                found.add(abi)
        if name.startswith("META-INF/") and name.upper().endswith((".SF", ".RSA", ".DSA", "MANIFEST.MF")):
            continue
        dst.writestr(entry, src.read(entry))
if found != allowed:
    raise ValueError(f"Missing native libraries: expected {allowed}, found {found}")
