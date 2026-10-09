#!/usr/bin/env python3
"""axml-package.py — 从 AAR 里的二进制 AndroidManifest.xml 读出 `<manifest package="...">`。

为什么自己写：本机没有 apktool / ByteIO 依赖，而给每个 AndroidX 依赖做 `aapt2 link --static-lib`
时都必须提供正确的 package（否则库代码里的 androidx.xxx.R 解析不到，运行时直接
ClassNotFoundException）。AGX 的 package 不能靠 groupId 猜：core 与 core-ktx 同包。
用法:
    tools/axml-package.py <AndroidManifest.xml> [<更多 manifest>]        # 打印一行一个包名
    tools/axml-package.py --all --libs libs                              # 扫描并写 libs/packages.json
"""
from __future__ import annotations

import json
import pathlib
import struct
import sys
import zipfile

RES_STRING_POOL = 0x0001
RES_XML_START_NODE = 0x0100
RES_XML_START_ELEMENT = 0x0102


def read_string_pool(buf: bytes, off: int) -> list[str]:
    _typ, hdr, size, count, styles, flags, str_start, sty_start = struct.unpack_from("<HHIIIIII", buf, off)
    utf8 = bool(flags & (1 << 8))
    strings: list[str] = []
    for i in range(count):
        pos = struct.unpack_from("<I", buf, off + hdr + i * 4)[0]
        p = off + str_start + pos
        if utf8:
            # UTF-8 串：前缀有两个 uint16（长度均为字符数），这里 len16 是 UTF-16 长度，可作为字节上限
            n = struct.unpack_from("<H", buf, p)[0]
            q = p + 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 8) | struct.unpack_from("<H", buf, q)[0]
                q += 2
            strings.append(buf[q:q + n].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", buf, p)[0]
            q = p + 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 8) | struct.unpack_from("<H", buf, q)[0]
                q += 2
            raw = buf[q:q + n * 2]
            strings.append(raw.decode("utf-16-le", "replace"))
    return strings


def package_of(buf: bytes) -> str | None:
    if not buf.startswith(b"\x03\x00\x08\x00"):
        raise ValueError("不是 AXML（magic 应为 03 00 08 00）")
    total = struct.unpack_from("<I", buf, 4)[0]
    strings: list[str] = []
    off = 8
    while off + 8 <= min(total, len(buf)):
        typ, hdr, size = struct.unpack_from("<HHI", buf, off)
        if size == 0:
            break
        if typ == RES_STRING_POOL:
            strings = read_string_pool(buf, off)
        elif typ == RES_XML_START_ELEMENT:
            # ext: line, comment；随后 attribExt: ns, name, attrStart, attrSize, attrCount, idIndex, classIndex, styleIndex
            base = off + hdr + 8
            name_idx, attr_start, attr_size, attr_count = struct.unpack_from("<IHHH", buf, base + 4)
            attrs_base = off + hdr + attr_start
            for i in range(attr_count):
                a_off = attrs_base + i * attr_size
                a_ns, a_name, a_raw = struct.unpack_from("<III", buf, a_off)
                if strings[a_name] == "package" and 0 <= a_raw < len(strings):
                    return strings[a_raw]
            return None
        off += size
    return None


def package_of_any(buf: bytes) -> str | None:
    if buf.startswith(b"\x03\x00\x08\x00"):
        return package_of(buf)
    # AndroidX 的 AAR manifest 其实是未编译的文本 XML，直接取属性即可
    import re
    head = buf[: buf.find(b">") + 1].decode("utf-8", "replace") if buf.find(b">") > 0 else ""
    m = re.search(r'package="([^"]+)"', head) or re.search(r'package="([^"]+)"', buf.decode("utf-8", "replace"))
    return m.group(1) if m else None


def from_aar(aar: pathlib.Path) -> str | None:
    with zipfile.ZipFile(aar) as z:
        return package_of_any(z.read("AndroidManifest.xml"))


def main() -> int:
    args = sys.argv[1:]
    if "--all" in args:
        libs = pathlib.Path("libs")
        if "--libs" in args:
            libs = pathlib.Path(args[args.index("--libs") + 1])
        out: dict[str, str] = {}
        for m in sorted(libs.glob("**/*.aar")):
            try:
                pkg = from_aar(m)
            except Exception as e:
                print(f"  ! {m}: {e}", file=sys.stderr)
                continue
            if pkg:
                out[str(m)] = pkg
        (libs / "packages.json").write_text(json.dumps(out, indent=1, sort_keys=True) + "\n")
        print(f"已写 {len(out)} 个包名 → {libs / 'packages.json'}")
        return 0
    for path in args:
        p = pathlib.Path(path)
        print(from_aar(p) if p.suffix == ".aar" else package_of_any(p.read_bytes()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
