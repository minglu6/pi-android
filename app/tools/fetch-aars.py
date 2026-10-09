#!/usr/bin/env python3
"""fetch-aars.py — 从 Google Maven 按 POM 传递依赖下载 AndroidX / Material AAR。

本机没有 gradle / Android SDK，APK 由 aapt2 + javac + d8 + apksigner 手工构建，
因此由这个脚本代替 gradle 做「传递依赖解析 + 版本择优 + 落地 + 拆包」。

两阶段：先把整个 POM 图读出来（不下载构件），对每个 group:artifact 取最高版本，
再只下载胜出版本 —— 避免拉到 135 个含多版本重复项的构件树。

用法:
    app/tools/fetch-aars.py --root androidx.appcompat:appcompat:1.7.0 \\
                            --root com.google.android.material:material:1.12.0

产出:
    app/libs/<group-path>/<artifact>/<version>/<artifact>.aar
    app/libs/<group-path>/<artifact>/<version>/classes.jar   （供 javac 直接当 classpath）
    app/libs/<group-path>/<artifact>/<version>/res/          （供 aapt2 compile）
    app/libs/manifest.json                                   （sha256 审计清单）
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
import urllib.request
import zipfile
import time
import xml.etree.ElementTree as ET

REPOS = ["https://dl.google.com/dl/android/maven2", "https://repo1.maven.org/maven2"]
MAVEN = REPOS[0]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
SKIP_SCOPES = {"test", "provided", "system", "import"}


def fetch(url: str, attempts: int = 4) -> bytes | None:
    req = urllib.request.Request(url, headers={"User-Agent": "fetch-aars/1.0"})
    for i in range(attempts):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            raise
        except (urllib.error.URLError, TimeoutError, OSError):
            if i == attempts - 1:
                raise
            time.sleep(1.5 * (i + 1))
    return None


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def vkey(v: str) -> tuple:
    """让 1.13.1 > 1.7.0 成立（纯字符串比较会得出反的）。"""
    out = []
    for seg in v.replace("-", ".").split("."):
        digits, rest = "", ""
        for ch in seg:
            if ch.isdigit() and not rest:
                digits += ch
            else:
                rest += ch
        out.append((int(digits) if digits else 0, rest))
    return tuple(out)


class Resolver:
    def __init__(self, out: pathlib.Path, force: bool = False):
        self.out = out
        self.force = force
        self.manifest_path = out / "manifest.json"
        self.manifest: dict = json.loads(self.manifest_path.read_text()) if self.manifest_path.exists() else {}
        self.versions: dict[str, set[str]] = {}      # group:artifact -> {版本...}
        self.edges: dict[str, set[str]] = {}         # g:a:v -> {g:a:v...}
        self.pom_cache: dict[str, bytes] = {}
        self.repos: dict[str, str | None] = {}
        self.winners: dict[str, str] = {}            # g:a -> v
        self.done: dict[str, dict] = {}

    # ---------- 阶段一：读 POM 图 ----------
    def pom(self, g: str, a: str, v: str) -> ET.Element | None:
        key = f"{g}:{a}:{v}"
        if key in self.pom_cache:
            return self.pom_cache[key]
        raw = None
        self.repos.setdefault(key, None)
        for repo in REPOS:
            raw = fetch(f"{repo}/{g.replace('.', '/')}/{a}/{v}/{a}-{v}.pom")
            if raw is not None:
                self.repos[key] = repo
                break
        self.pom_cache[key] = None if raw is None else ET.fromstring(raw)
        return self.pom_cache[key]

    def probe(self, spec: str) -> None:
        if spec in self.edges:
            return
        g, a, v = spec.split(":")
        self.versions.setdefault(f"{g}:{a}", set()).add(v)
        self.edges[spec] = set()
        root = self.pom(g, a, v)
        if root is None:
            print(f"  ! 无 POM，按叶子处理: {spec}", file=sys.stderr)
            return
        # AndroidX 的 groupId / version 常在 <parent>，依赖块可能在父 POM 里也要合并
        parent = root.find("m:parent", NS)
        if parent is not None:
            pg = (parent.findtext("m:groupId", namespaces=NS) or "").strip()
            pa = (parent.findtext("m:artifactId", namespaces=NS) or "").strip()
            pv = (parent.findtext("m:version", namespaces=NS) or "").strip()
            proot = self.pom(pg, pa, pv)
            if proot is not None:
                for d in self.deps_of(proot):
                    self.record(spec, d)
        for d in self.deps_of(root):
            self.record(spec, d)

    def deps_of(self, root: ET.Element) -> list[tuple[str, str, str]]:
        out = []
        for dep in root.findall("m:dependencies/m:dependency", NS):
            g = (dep.findtext("m:groupId", namespaces=NS) or "").strip()
            a = (dep.findtext("m:artifactId", namespaces=NS) or "").strip()
            v = (dep.findtext("m:version", namespaces=NS) or "").strip().strip("$[]{}(),/\\ ")
            scope = (dep.findtext("m:scope", namespaces=NS) or "compile").strip()
            optional = (dep.findtext("m:optional", namespaces=NS) or "false").strip()
            if not g or not a or not v or scope in SKIP_SCOPES or optional == "true":
                continue
            out.append((g, a, v))
        return out

    def record(self, src: str, dep: tuple[str, str, str]) -> None:
        g, a, v = dep
        self.edges[src].add(f"{g}:{a}:{v}")
        self.versions.setdefault(f"{g}:{a}", set()).add(v)
        self.probe(f"{g}:{a}:{v}")

    # ---------- 阶段二：择优 ----------
    def pick(self) -> None:
        self.winners = {ga: max(vs, key=vkey) for ga, vs in self.versions.items()}

    def wanted(self, roots: list[str], excluded: set[str]) -> set[str]:
        """从 root 出发，按择优后的版本做一遍 DFS，得到最终构件集合。"""
        out: set[str] = set()
        stack = [self.canonical(s) for s in roots]
        while stack:
            spec = stack.pop()
            if spec in out:
                continue
            out.add(spec)
            for tgt in self.edges.get(spec, set()):
                ga = ":".join(tgt.split(":")[:2])
                if ga in excluded:
                    continue
                stack.append(self.canonical(tgt))
        return out

    def canonical(self, spec: str) -> str:
        g, a, _v = spec.split(":")
        return f"{g}:{a}:{self.winners[f'{g}:{a}']}"

    # ---------- 阶段三：落地 ----------
    def download(self, spec: str) -> None:
        g, a, v = spec.split(":")
        preferred = self.repos.get(spec)
        bases = ([f"{preferred}/{g.replace('.', '/')}/{a}/{v}/{a}-{v}"] if preferred else []) + \
                 [f"{r}/{g.replace('.', '/')}/{a}/{v}/{a}-{v}" for r in REPOS]
        dest = self.out / g.replace(".", "/") / a / v
        dest.mkdir(parents=True, exist_ok=True)
        entry: dict = {"group": g, "artifact": a, "version": v}
        cached = self.manifest.get(spec, {})
        for ext in ("aar", "jar"):
            fname = f"{a}.{ext}"
            target = dest / fname
            if target.exists() and not self.force and cached.get(fname):
                entry[fname] = cached[fname]
                self.done[spec] = entry
                self.unpack(target, dest)
                return
            data = None
            for b in bases:
                data = fetch(f"{b}.{ext}")
                if data is not None:
                    break
            if data is None:
                continue
            target.write_bytes(data)
            entry[fname] = {"sha256": sha256(data), "bytes": len(data)}
            self.done[spec] = entry
            self.unpack(target, dest)
            print(f"  ✓ {spec} [{ext}] {len(data) // 1024} KiB")
            return
        print(f"  ! 既无 AAR 也无 JAR: {spec}", file=sys.stderr)

    def unpack(self, target: pathlib.Path, dest: pathlib.Path) -> None:
        if target.suffix != ".aar":
            return
        try:
            with zipfile.ZipFile(target) as z:
                names = z.namelist()
                if "classes.jar" in names and not (dest / "classes.jar").exists():
                    (dest / "classes.jar").write_bytes(z.read("classes.jar"))
                members = [n for n in names if n.startswith("res/")]
                if members and not (dest / "res").exists():
                    z.extractall(dest, members=members)
                # R.txt 里记着该库自己的资源名清单：用来在最终 link 后补出 androidx.xxx.R
                if "R.txt" in names and not (dest / "R.txt").exists():
                    (dest / "R.txt").write_bytes(z.read("R.txt"))
        except zipfile.BadZipFile as e:
            print(f"  ! 解压失败 {target}: {e}", file=sys.stderr)

    def save(self) -> None:
        merged = dict(self.manifest)
        merged.update(self.done)
        self.manifest_path.write_text(json.dumps(merged, indent=1, sort_keys=True) + "\n")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", action="append", required=True, help="group:artifact:version，可多次")
    ap.add_argument("--out", default="libs", help="落地目录（默认 app/libs）")
    ap.add_argument("--exclude", action="append", default=[],
                    help="group:artifact，覆盖合并到其它构件、一起打包会撞类的包（如 kotlin-stdlib-jdk7/8）")
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()

    out = pathlib.Path(args.out).expanduser().resolve()
    out.mkdir(parents=True, exist_ok=True)
    roots = []
    for spec in args.root:
        if spec.count(":") != 2:
            print(f"--root 需 group:artifact:version，得到 {spec!r}", file=sys.stderr)
            return 2
        roots.append(spec)

    r = Resolver(out, args.force)
    print("① 读取 POM 依赖图")
    for spec in roots:
        r.probe(spec)
    print(f"   节点 {len(r.edges)} 个，涉及构件 {len(r.versions)} 个")
    print("② 版本择优（同 group:artifact 取最高）")
    r.pick()
    excluded = set(args.exclude)
    final = sorted(r.wanted(roots, excluded), key=lambda s: vkey(s.split(":")[2]))
    # 导出依赖图（拓扑排序用：给每个依赖做 static-lib link 时必须先 link 它的依赖）
    graph = {s: sorted({r.canonical(t) for t in r.edges.get(s, set())
                        if ":".join(t.split(":")[:2]) not in excluded})
             for s in final}
    (out / "graph.json").write_text(json.dumps(graph, indent=1, sort_keys=True) + "\n")
    print(f"   最终 {len(final)} 个构件")
    print("③ 下载与拆包")
    for spec in final:
        r.download(spec)
    # 被排除的构件若上一轮已落地，清掉目录，避免 build.sh 的 classpath 又把它捡回来
    for spec in list(r.manifest):
        g, a = r.manifest[spec]["group"], r.manifest[spec]["artifact"]
        if f"{g}:{a}" in excluded:
            r.manifest.pop(spec)
            import shutil
            d = r.out / g.replace(".", "/") / a
            if d.exists():
                shutil.rmtree(d)
                print(f"  - 已移除被排除构件 {spec}")
    r.save()
    print(f"\n共 {len(r.done)} 个构件 → {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
