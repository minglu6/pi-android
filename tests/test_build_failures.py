"""Exercise the actual build script with fake tool failures (no Android SDK needed)."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class BuildFailures(unittest.TestCase):
    def run_build(self, fail):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            app = base / 'app'
            app.mkdir()
            shutil.copy(ROOT / 'app/build.sh', app / 'build.sh')
            shutil.copytree(ROOT / 'app/tools', app / 'tools')
            (app / 'libs/dependency/res').mkdir(parents=True)
            (app / 'res').mkdir()
            (app / 'src').mkdir()
            (app / 'libs/packages.json').write_text('{}')
            (app / 'AndroidManifest.xml').write_text('<manifest package="dev.pi.android"/>')
            jar = base / 'android.jar'
            jar.touch()
            tools = base / 'bin'
            tools.mkdir()
            script = '''#!/usr/bin/env python3
import os, pathlib, sys
name = pathlib.Path(sys.argv[0]).name
with open(os.environ['CALLS'], 'a') as out: out.write(name + '\\n')
if name == 'aapt2':
    args = sys.argv[1:]
    if os.environ['FAIL'] == 'resources' and 'compile' in args and 'dependency' in ' '.join(args):
        print('fixture resource error', file=sys.stderr)
        sys.exit(2)
    if '-o' in args:
        p = pathlib.Path(args[args.index('-o')+1]); p.parent.mkdir(parents=True, exist_ok=True); p.touch()
    if '--java' in args:
        p = pathlib.Path(args[args.index('--java')+1]) / 'dev/pi/android/R.java'
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text('package dev.pi.android; public final class R {}')
if name == 'javac':
    print('fixture javac error', file=sys.stderr)
    sys.exit(3)
'''
            for name in ['aapt2', 'javac', 'd8', 'apksigner', 'keytool']:
                p = tools / name
                p.write_text(script)
                p.chmod(0o755)
            calls = base / 'calls'
            env = dict(os.environ, PATH=str(tools)+os.pathsep+os.environ['PATH'],
                       ANDROID_JAR=str(jar), CALLS=str(calls), FAIL=fail)
            result = subprocess.run(['bash', str(app / 'build.sh'), '--only-apk'],
                                    env=env, capture_output=True, text=True, timeout=30)
            return result, calls.read_text().splitlines()

    def test_javac_failure_stops_before_dex_and_signing(self):
        result, calls = self.run_build('javac')
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn('fixture javac error', result.stderr)
        self.assertNotIn('d8', calls)
        self.assertNotIn('apksigner', calls)

    def test_dependency_resource_failure_is_not_ignored(self):
        result, calls = self.run_build('resources')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('fixture resource error', result.stderr)
        self.assertNotIn('javac', calls)
        self.assertNotIn('d8', calls)


if __name__ == '__main__':
    unittest.main()
