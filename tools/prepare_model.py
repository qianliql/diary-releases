"""Package already downloaded, verified Whisper ONNX files. No network or model in APK."""
import hashlib, json, pathlib, sys, zipfile
root = pathlib.Path(__file__).resolve().parents[1]
source = pathlib.Path(sys.argv[1]).resolve()
output = pathlib.Path(sys.argv[2]).resolve()
expected = {
    "encoder.onnx": "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434",
    "decoder.onnx": "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925",
}
def digest(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()
for name, sha in expected.items():
    assert digest(source / name) == sha, f"Checksum mismatch: {name}"
files = []
for name in ["encoder.onnx", "decoder.onnx", "tokens.txt", "LICENSE.txt"]:
    path = source / name
    files.append({"name": name, "sha256": digest(path), "size": path.stat().st_size})
metadata = {"version": "whisper-tiny-int8-v1", "engine": "sherpa-onnx", "language": "zh", "license": "MIT", "source": "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny", "files": files}
(source / "model.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding='utf-8')
output.mkdir(parents=True, exist_ok=True)
package = output / 'whisper-tiny-int8-v1.zip'
with zipfile.ZipFile(package, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for name in [item['name'] for item in files] + ['model.json']:
        info = zipfile.ZipInfo(name, (2026, 10, 15, 0, 0, 0)); info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(info, (source / name).read_bytes())
catalog = {
    "version": "whisper-tiny-int8-v1", "name": "Whisper tiny · 普通话离线转写",
    "size": package.stat().st_size, "unpackedSize": sum((source / name).stat().st_size for name in [item['name'] for item in files] + ['model.json']),
    "sha256": digest(package),
    "url": "https://github.com/qianliql/diary-releases/releases/download/models-v1/whisper-tiny-int8-v1.zip",
    "license": "MIT · OpenAI Whisper；Apache-2.0 · sherpa-onnx",
}
body = json.dumps(catalog, ensure_ascii=False, indent=2)
(output / 'model-catalog.json').write_text(body, encoding='utf-8')
(root / 'app/src/main/assets/model-catalog.json').write_text(body, encoding='utf-8')
print(body)
