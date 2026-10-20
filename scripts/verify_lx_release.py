"""Fail closed before signing/publishing an LX release; optionally inspect real APKs."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--tag')
parser.add_argument('--apks', type=Path)
parser.add_argument('--aapt')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
manifest = json.loads((root / 'updates/lx.json').read_text(encoding='utf-8-sig'))
config = (root / 'app/build.gradle.kts').read_text(encoding='utf-8-sig')
version = re.search(r'versionName\s*=\s*"([^"]+)"', config).group(1)
code = int(re.search(r'versionCode\s*=\s*(\d+)', config).group(1))
assert re.fullmatch(r'\d+\.\d+\.\d+-lx\.\d+', version), version
assert manifest['versionName'] == version and manifest['versionCode'] == code
assert manifest['domesticUrl'] == ''
assert manifest['githubUrl'] == f'https://github.com/lecoix/mica-music/releases/tag/lx-v{version}'
assert 'else "com.mica.music.lx"' in config
assert 'lecoix.github.io/mica-music/update.json' not in config
assert not (root / '.github/workflows/android-release.yml').exists(), 'Stable publisher must not run on LX branch'
if args.tag:
    assert args.tag == f'lx-v{version}', args.tag
if args.apks:
    assert args.aapt, '--aapt is required'
    apks = sorted(args.apks.glob('*.apk'))
    assert len(apks) == 3, apks
    for apk in apks:
        badging = subprocess.check_output([args.aapt, 'dump', 'badging', str(apk)], text=True, encoding='utf-8')
        assert f"package: name='com.mica.music.lx' versionCode='{code}' versionName='{version}'" in badging, apk
        assert "application-label:'Mica LX'" in badging, apk
        xml = subprocess.check_output([args.aapt, 'dump', 'xmltree', str(apk), 'AndroidManifest.xml'], text=True, encoding='utf-8')
        for line in xml.splitlines():
            if 'android:authorities' in line:
                assert 'com.mica.music.lx.' in line, line
        with zipfile.ZipFile(apk) as archive:
            names = set(archive.namelist())
            abis = {name.split('/')[1] for name in names if name.startswith('lib/') and name.endswith('.so')}
            assert abis <= {'arm64-v8a', 'armeabi-v7a'} and abis
            for abi in abis:
                for lib in ('libquickjs-android-wrapper.so', 'libffmpegJNI.so', 'libtaglib.so', 'libsylvakru_usb_exclusive.so', 'libusb-1.0.so'):
                    assert f'lib/{abi}/{lib}' in names, (apk, abi, lib)
        print(f'Verified {apk.name}')
print(f'LX identity verified: com.mica.music.lx {version} ({code})')
