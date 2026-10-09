#!/usr/bin/env python3
"""lib-resources.py — 把每个带资源的依赖 AAR 先 link 成 aapt2 静态库（--static-lib）。

为什么必须有这一步：
    直接把依赖编译出的资源 zip 丢给应用的 `aapt2 link`，资源会被合并进应用的包
    （dev.pi.android.R），而库代码引用的是 androidx.xxx.R。运行时的结果就是
    `ClassNotFoundException: androidx.appcompat.R$drawable` —— 这是本机手搓 APK 时最容易
    踩、也最难从错误信息推出中间产物问题的坑。静态库保留各依赖的包名与符号引用，
    最终 link 才会为每个依赖生成对应的 R.java。

    另一个附带收获：`No package ID 7f found for resource ID 0x7f…` 这类错误也随之消失，
    因为它们来自“编译阶段就把跨库引用烘成数字 id”的路径。

用法:
    tools/lib-resources.py [--libs libs] [--out build/libres] [--android-jar PATH] [--min-sdk 26]

产出:
    <out>/<n>.zip          每个库的编译资源
    <out>/<lib>.apk        静态库（供最终 link 的 -R 使用）
    <out>/static.list      行分隔的静态库路径清单，build.sh 会读它
"""
from __future__ import annotations

import argparse
import json
import pathlib
import shlex
import subprocess
import sys


def sh(cmd: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True)


def fail(msg: str) -> "int":
    print(msg, file=sys.stderr)
    return 1


def topo_order(graph: dict[str, list[str]]) -> list[str]:
    """Kahn 排序；依赖先出列。有环时按剩余顺序补上（不追求严格语义，只求尽量先修根）。"""
    indeg = {n: 0 for n in graph}
    for n, deps in graph.items():
        for d in deps:
            if d in indeg:
                indeg[n] += 1
    ready = [n for n, d in indeg.items() if d == 0]
    ready.sort()
    order: list[str] = []
    while ready:
        n = ready.pop(0)
        order.append(n)
        for other, deps in graph.items():
            if n in deps:
                indeg[other] -= 1
                if indeg[other] == 0 and other not in order:
                    ready.append(other)
                    ready.sort()
    for n in graph:
        if n not in order:
            order.append(n)
    return order


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--libs", default="libs")
    ap.add_argument("--out", default="build/libres")
    ap.add_argument("--android-jar", default=None)
    ap.add_argument("--min-sdk", default="26")
    args = ap.parse_args()

    libs = pathlib.Path(args.libs).expanduser().resolve()
    out = pathlib.Path(args.out).expanduser().resolve()
    android_jar = args.android_jar or str(pathlib.Path.home() / "antdaily/android/android.jar")
    out.mkdir(parents=True, exist_ok=True)
    for stale in out.glob("*.apk"):
        stale.unlink()

    manifest = json.loads((libs / "manifest.json").read_text()) if (libs / "manifest.json").exists() else {}
    packages = json.loads((libs / "packages.json").read_text()) if (libs / "packages.json").exists() else {}
    graph = json.loads((libs / "graph.json").read_text()) if (libs / "graph.json").exists() else {}

    order = topo_order(graph) if graph else sorted(manifest)
    produced: dict[str, pathlib.Path] = {}

    for spec in order:
        entry = manifest.get(spec)
        if not entry:
            continue
        g, a, v = entry["group"], entry["artifact"], entry["version"]
        if f"{g}:{a}:{v}" != spec:
            continue
        base = libs / g.replace(".", "/") / a / v
        res = base / "res"
        aar = base / f"{a}.aar"
        # packages.json 的 key 可能是相对或绝对路径，两种都比一遍
        cand_keys = [str(aar), str(aar.relative_to(libs)), str(aar).replace(str(libs) + "/", "libs/")]
        pkg = next((packages[k] for k in cand_keys if k in packages), None)
        if not res.is_dir() or not pkg:
            continue

        zip_out = out / f"{len(produced)}.zip"
        r = sh(["aapt2", "compile", "--dir", str(res), "-o", str(zip_out)])
        if r.returncode != 0:
            print(f"  ! compile 失败 {spec}\n{r.stderr.strip()[:400]}", file=sys.stderr)
            continue

        # 合成最小 manifest：静态库只需要 package，避免把库的 uses-sdk/minSdk 带进这里
        mini = out / f"{len(produced)}-manifest.xml"
        mini.write_text(f'<?xml version="1.0" encoding="utf-8"?>\n<manifest package="{pkg}" />\n')

        # 依赖用 -I：只参与符号解析，不并入本库的资源表。
        # 若用 -R，依赖的资源会被合并进本库包名，core 里引用自己的资源反而找不到
        # （错误信息形如 resource id/normal (aka androidx.drawerlayout:id/normal) not found）。
        include_flags: list[str] = []
        for dep in graph.get(spec, []):
            dep_static = produced.get(dep)
            if dep_static and dep_static.exists():
                include_flags += ["-I", str(dep_static)]
        static = out / f"{len(produced)}-{pkg}.apk"
        cmd = ["aapt2", "link", "--static-lib", "-I", android_jar] + include_flags + [
               "--manifest", str(mini), "-R", str(zip_out),
               "--auto-add-overlay", "--min-sdk-version", args.min_sdk,
               "-o", str(static)]
        r = sh(cmd)
        if r.returncode != 0:
            tail = "\n".join(r.stderr.strip().splitlines()[-6:])
            print(f"  ! static link 失败 {spec} ({pkg})\n{tail}", file=sys.stderr)
            continue
        produced[spec] = static
        print(f"  ✓ {pkg}  ({spec})")

    (out / "static.list").write_text("\n".join(str(p) for p in produced.values()) + "\n")
    print(f"\n静态库 {len(produced)} 个 → {out}/static.list")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
