"""Publish one validated artifact set to BOTH hosts. Credentials stay in memory."""
import argparse, hashlib, json, os, pathlib, subprocess, sys, urllib.error, urllib.parse, urllib.request, uuid

def credential(host):
    key = 'GITHUB_TOKEN' if host == 'github.com' else 'GITEE_TOKEN'
    if os.getenv(key): return os.environ[key]
    env = dict(os.environ, GIT_TERMINAL_PROMPT='0', GCM_INTERACTIVE='never')
    result = subprocess.run(['git','credential','fill'],input=f'protocol=https\nhost={host}\n\n',text=True,capture_output=True,env=env,timeout=30)
    values = dict(s.split('=',1) for s in result.stdout.splitlines() if '=' in s)
    if values.get('password'): return values['password']
    raise RuntimeError(f'{host} authorization required: set {key} locally; do not place credentials in source or chat')

class Client:
    def __init__(self, platform, token):
        self.platform, self.token = platform, token
        self.base = 'https://api.github.com' if platform == 'github' else 'https://gitee.com/api/v5'
    def call(self, path, method='GET', body=None, raw=None, content_type=None):
        url = path if path.startswith('https://') else self.base+path
        allowed = ('https://api.github.com/', 'https://uploads.github.com/') if self.platform == 'github' else ('https://gitee.com/api/v5/',)
        assert url.startswith(allowed), 'Refuse to send authorization to a different host'
        data = raw if raw is not None else json.dumps(body).encode('utf-8') if body is not None else None
        headers = {'Authorization':'Bearer '+self.token, 'User-Agent':'DiaryRelease', 'Accept':'application/json'}
        if data is not None: headers['Content-Type'] = content_type or 'application/json'
        if self.platform == 'github': headers['X-GitHub-Api-Version'] = '2022-11-28'
        request = urllib.request.Request(url,data=data,headers=headers,method=method)
        try:
            with urllib.request.urlopen(request,timeout=240) as response:
                data = response.read(); return json.loads(data) if data else None
        except urllib.error.HTTPError as error:
            if error.code == 404 and method == 'GET': return None
            raise RuntimeError(self.platform+' HTTP '+str(error.code)) from None
    def upload(self, release, path, repo):
        if self.platform == 'github':
            url = release['upload_url'].split('{')[0]+'?name='+urllib.parse.quote(path.name)
            return self.call(url,'POST',raw=path.read_bytes(),content_type='application/octet-stream')
        boundary = 'Diary'+uuid.uuid4().hex
        payload = ('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="'+path.name+'"\r\nContent-Type: application/octet-stream\r\n\r\n').encode('utf-8')+path.read_bytes()+('\r\n--'+boundary+'--\r\n').encode()
        return self.call('/repos/'+repo+'/releases/'+str(release['id'])+'/attach_files','POST',raw=payload,content_type='multipart/form-data; boundary='+boundary)

def digest(path):
    with path.open('rb') as stream: return hashlib.file_digest(stream,'sha256').hexdigest()

def publish(platform, client, manifest, directory, notes, stable=False):
    repo = manifest[platform]
    profile = client.call('/user')
    assert profile and profile['login'].lower() == repo.split('/')[0].lower(), 'Unexpected account'
    info = client.call('/repos/'+repo)
    assert info, 'Create and initialize the named repository before publishing'
    assert not info.get('private',False), 'Expected the approved public release repository'
    base = '/repos/'+repo+'/releases'
    release = client.call(base+'/tags/'+urllib.parse.quote(manifest['tag'],safe=''))
    if release is None:
        release = client.call(base,'POST',{'tag_name':manifest['tag'],'target_commitish':info.get('default_branch') or 'master','name':'日记 '+manifest['version'],'body':notes,'prerelease':not stable})
    suffix = '/assets' if platform == 'github' else '/attach_files'
    assets = client.call(base+'/'+str(release['id'])+suffix+'?per_page=100') or []
    by_name = {a.get('name',a.get('filename','')):a for a in assets}
    file_names = [a['name'] for a in manifest['assets']]+['release-manifest.json','SHA256SUMS.txt']
    for name in file_names:
        path = directory/name
        if name in by_name:
            # Verify existing bytes before accepting a partial rerun. Never replace a published artifact.
            asset = by_name[name]
            url = asset.get('browser_download_url') or asset.get('download_url')
            assert url and urllib.parse.urlparse(url).scheme == 'https', 'Cannot verify existing attachment'
            with urllib.request.urlopen(url,timeout=240) as response:
                h=hashlib.sha256()
                while block:=response.read(1024*1024): h.update(block)
            assert h.hexdigest()==digest(path), 'Published asset differs; increment version: '+name
        else: client.upload(release,path,repo)
        print(platform+': verified '+name,flush=True)
    return release.get('html_url') or 'https://gitee.com/'+repo+'/releases/tag/'+manifest['tag']

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--assets',type=pathlib.Path,required=True)
    parser.add_argument('--notes',type=pathlib.Path,required=True)
    parser.add_argument('--platform',choices=['both','github','gitee'],default='both')
    parser.add_argument('--publish',action='store_true'); parser.add_argument('--stable',action='store_true')
    args=parser.parse_args(); directory=args.assets.resolve()
    manifest=json.loads((directory/'release-manifest.json').read_text('utf-8'))
    for asset in manifest['assets']:
        path=directory/asset['name']
        assert path.parent==directory and path.stat().st_size==asset['bytes'] and digest(path)==asset['sha256'], 'Artifact mismatch'
    notes=args.notes.read_text('utf-8')
    platforms=['github','gitee'] if args.platform=='both' else [args.platform]
    if not args.publish:
        print('Validated '+manifest['tag']+' for '+', '.join(platforms)+'. Use --publish to upload.'); return
    # Authenticate all selected destinations before making the first mutation.
    clients={p:Client(p,credential('github.com' if p=='github' else 'gitee.com')) for p in platforms}
    status={}
    status_file=directory/'release-status.json'
    if status_file.exists(): status=json.loads(status_file.read_text('utf-8'))
    for platform in platforms:
        status[platform]={'tag':manifest['tag'],'url':publish(platform,clients[platform],manifest,directory,notes,args.stable)}
        status_file.write_text(json.dumps(status,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(status[platform]['url'],flush=True)
    assert all(status.get(p,{}).get('tag')==manifest['tag'] for p in ['github','gitee']), 'Release is incomplete until BOTH destinations are published'
    print('Both platforms have this release.')

if __name__=='__main__':
    try: main()
    except Exception as error:
        print('Release incomplete: '+str(error),file=sys.stderr); sys.exit(1)
