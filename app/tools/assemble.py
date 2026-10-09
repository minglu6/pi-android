#!/usr/bin/env python3
"""assemble.py — 把 aapt2 产出的 base.apk、d8 产出的 dex，以及 AAR 里的 assets / .so 合成未签名 APK。

单独成有点规模的 Python 而不是 shell 内联，是因为 AAR 侧的 assets 与 native 库合并
（libs/<abi>/*.so、jni/<abi>/*.so → lib/<abi>/）需要按 ABI 目录映射，zipfile 处理更稳。

用法:
    app/tools/assemble.py --base build/base.apk --dex build/dex --out build/unsigned.apk \\
                          [--libs libs] [--extra-assets assets]
"""
from __future__ import annotations

import argparse
import json
import pathlib
import shutil
import zipfile


def add_tree(zf: zipfile.ZipFile, root: pathlib.Path, prefix: str, compress: bool = True) -> int:
    """把目录下的文件写进 zip，prefix 是 APK 内的路径前缀（'' 表示根目录）。"""
    n = 0
    for p in sorted(root.rglob("*")):
        if not p.is_file():
            continue
        rel = p.relative_to(root).as_posix()
        arc = f"{prefix}{rel}" if prefix else rel
        # .so 与 resources.arsc 不能压缩（Android 直接从 APK 映射，历史要求），zipfile 用 STORED 处理 .so
        if rel.endswith(".so"):
            zf.write(p, arc, zipfile.ZIP_STORED)
        else:
            zf.write(p, arc, zipfile.ZIP_DEFLATED if compress else zipfile.ZIP_STORED)
        n += 1
    return n


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True, help="aapt2 link 产出的基础 APK")
    ap.add_argument("--dex", required=True, help="d8 输出目录（内含 classes*.dex）")
    ap.add_argument("--libs", default="libs", help="AAR 落地目录（含 manifest.json）")
    ap.add_argument("--extra-assets", default=None, help="应用自带的 assets 目录")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    base = pathlib.Path(args.base).expanduser().resolve()
    dex = pathlib.Path(args.dex).expanduser().resolve()
    libs = pathlib.Path(args.libs).expanduser().resolve()
    out = pathlib.Path(args.out).expanduser().resolve()
    out.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(base, out)

    counts = {"dex": 0, "assets": 0, "so": 0}
    with zipfile.ZipFile(out, "a", zipfile.ZIP_DEFLATED) as z:
        for d in sorted(dex.glob("classes*.dex")):
            z.write(d, d.name, zipfile.ZIP_DEFLATED)
            counts["dex"] += 1

        mpath = libs / "manifest.json"
        if mpath.exists():
            for spec, entry in sorted(json.loads(mpath.read_text()).items()):
                g, a, v = entry["group"], entry["artifact"], entry["version"]
                d = libs / g.replace(".", "/") / a / v
                aar = d / f"{a}.aar"
                if not aar.exists():
                    continue
                with zipfile.ZipFile(aar) as az:
                    names = az.namelist()
                    assets = [n for n in names if n.startswith("assets/")]
                    # 新 AndroidX AAR 用 jni/<abi>/，旧包（含 support 时代）用 libs/<abi>/
                    sos = [n for n in names if n.startswith(("jni/", "libs/")) and n.endswith(".so")]
                    if assets:
                        for n in assets:
                            z.writestr(n, az.read(n), zipfile.ZIP_DEFLATED)
                            counts["assets"] += 1
                    for n in sos:
                        parts = n.split("/")
                        abi = parts[1]
                        arc = f"lib/{abi}/{parts[-1]}"
                        z.writestr(arc, az.read(n), zipfile.ZIP_STORED)
                        counts["so"] += 1

        extra = args.extra_assets
        if extra:
            p = pathlib.Path(extra).expanduser().resolve()
            if p.is_dir():
                counts["own-assets"] = add_tree(z, p, "assets/")

    size_kb = out.stat().st_size // 1024
    print(f"组装完成: dex={counts['dex']} AAR-assets={counts['assets']} .so={counts['so']} → {out} ({size_kb} KiB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
