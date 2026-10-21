#!/usr/bin/env python3
"""Fail CI when release R8 removes a Java class registered by native WebRTC JNI."""
import re
import struct
import sys
import zipfile
from pathlib import Path

REQUIRED = {
    "Lorg/webrtc/PeerConnectionFactory;",
    "Lorg/webrtc/PeerConnection;",
    "Lorg/webrtc/EglBase;",
    "Lorg/webrtc/Camera2Enumerator;",
    "Lorg/jni_zero/JniZero;",
}

def read_uleb128(data: bytes, offset: int) -> int:
    for _ in range(5):
        byte = data[offset]
        offset += 1
        if not (byte & 0x80):
            return offset
    raise ValueError("invalid DEX uleb128 string length")

def dex_classes(data: bytes) -> set[str]:
    if not data.startswith(b"dex\n"):
        raise ValueError("Not a DEX file")
    string_count, string_offset = struct.unpack_from("<II", data, 0x38)
    type_count, type_offset = struct.unpack_from("<II", data, 0x40)
    class_count, class_offset = struct.unpack_from("<II", data, 0x60)
    strings = []
    for i in range(string_count):
        (offset,) = struct.unpack_from("<I", data, string_offset + i * 4)
        start = read_uleb128(data, offset)
        strings.append(data[start : data.index(b"\0", start)].decode("utf-8"))
    types = [strings[struct.unpack_from("<I", data, type_offset + i * 4)[0]] for i in range(type_count)]
    return {
        types[struct.unpack_from("<I", data, class_offset + i * 32)[0]]
        for i in range(class_count)
    }

def apk_classes(path: Path) -> set[str]:
    with zipfile.ZipFile(path) as apk:
        dex_files = [name for name in apk.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
        if not dex_files:
            raise ValueError(f"{path}: no DEX files")
        classes = set()
        for name in dex_files:
            classes.update(dex_classes(apk.read(name)))
        return classes

def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: verify_webrtc_jni_dex.py <camera.apk> <viewer.apk>", file=sys.stderr)
        return 2
    errors = 0
    for filename in sys.argv[1:]:
        path = Path(filename)
        classes = apk_classes(path)
        missing = sorted(REQUIRED - classes)
        if missing:
            print(f"FAIL {path}: missing JNI class definitions: {', '.join(missing)}", file=sys.stderr)
            errors += 1
        else:
            print(f"PASS {path}: {len(REQUIRED)} WebRTC JNI classes are real DEX class definitions")
    return 1 if errors else 0

if __name__ == "__main__":
    sys.exit(main())
