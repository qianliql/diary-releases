"""Create public artifacts from an already signed APK; exclude all local credentials."""
import argparse, hashlib, json, pathlib, re, shutil, zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
EXCLUDE_DIRS = {'.git', '.gradle', '.kotlin', '.idea', 'build', '__pycache__'}
EXCLUDE_FILES = {'local.properties', 'signing.properties', 'release-status.json'}
SECRET_SUFFIXES = {'.p12', '.pfx', '.jks', '.keystore', '.token'}

def source_files():
    for file in sorted(ROOT.rglob('*')):
        relative = file.relative_to(ROOT)
        if file.is_file() and not EXCLUDE_DIRS.intersection(relative.parts) and file.name not in EXCLUDE_FILES and file.suffix.lower() not in SECRET_SUFFIXES:
            yield file, relative.as_posix()

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    version = re.search(r'versionName\s*=\s*"([0-9.]+)"', (ROOT/'app/build.gradle.kts').read_text('utf-8')).group(1)
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    apk = output/f'diary-{version}-arm64.apk'
    if args.apk.resolve() != apk: shutil.copyfile(args.apk, apk)
    with zipfile.ZipFile(apk) as archive:
        assert not any(n.endswith('.onnx') for n in archive.namelist()), 'Base APK contains optional weights'
    source = output/f'diary-{version}-source.zip'
    with zipfile.ZipFile(source, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for file, name in source_files(): archive.write(file, 'diary/'+name)
    files = [apk, source]
    # GitHub normalizes non-ASCII asset names; use stable ASCII names on both hosts.
    for source_name, public_name in [('使用说明.md','USER-GUIDE.md'),('测试报告.md','TEST-REPORT.md')]:
        if (output/source_name).exists():
            shutil.copyfile(output/source_name,output/public_name); files.append(output/public_name)
    manifest = {'version':version, 'tag':'v'+version, 'github':'qianliql/diary-releases', 'gitee':'qianlihuo/diary', 'assets':[{'name':p.name, 'bytes':p.stat().st_size, 'sha256':sha(p)} for p in files]}
    (output/'release-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    files.append(output/'release-manifest.json')
    (output/'SHA256SUMS.txt').write_text(''.join(sha(p)+'  '+p.name+'\n' for p in files),encoding='utf-8')
    print('Prepared '+version+': '+', '.join(p.name for p in files))

if __name__ == '__main__': main()
