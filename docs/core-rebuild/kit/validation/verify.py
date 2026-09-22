#!/usr/bin/env python3
"""验证普通包独立性、来源/完整性和开发APK身份；输出不含秘密。"""
import argparse
import hashlib
import json
import re
from pathlib import Path
import subprocess
import zipfile
import sys
sys.dont_write_bytecode = True
from common import properties, sha


def verify_kit(kit):
    source=json.loads((kit/'manifests/sources.json').read_text())
    for item in source['files']:
        assert sha(kit/item['destination'])==item['sha256'], '参考文件与源清单不同：'+item['destination']
    models=json.loads((kit/'manifests/models.json').read_text())
    assert any(x['name']=='PendingEvent' and x['category']=='model' for x in models)
    assert any(x['name']=='QueuedPaymentEvent' and x['category']=='model' for x in models)
    errors=[]
    for path in kit.rglob('*'):
        if not path.is_file():continue
        assert path.suffix not in ('.jks','.keystore'), '普通包包含私钥'
        assert not path.name.endswith('.local.properties'), '普通包包含本地秘密配置'
        if path.suffix in ('.md','.py','.kt','.kts','.properties'):
            text=path.read_text()
            assert not re.search(r'/Users/[A-Za-z0-9._-]+/',text), '普通包保留源机路径：'+str(path.relative_to(kit))
            if path.suffix=='.md':
                for link in re.findall(r'\]\(([^)]+)\)',text):
                    if '://' in link or link.startswith('#'):continue
                    target=link.split('#')[0]
                    if not (path.parent/target).exists():errors.append((str(path.relative_to(kit)),link))
    assert not errors, '包内链接缺失：'+str(errors)
    manifest=kit/'manifests/package-files.json'
    if manifest.exists():
        expected=json.loads(manifest.read_text())
        actual={p.relative_to(kit).as_posix() for p in kit.rglob('*') if p.is_file() and p!=manifest}
        assert actual==set(expected),'交付文件集合发生变化'
        for path,digest in expected.items():assert sha(kit/path)==digest,'文件哈希不符：'+path
    print('普通包：来源哈希、独立链接、私密文件隔离及Model基础覆盖通过')


def verify_apk(kit,workspace,apk,sdk,variant,expected_config=None,app_module='app'):
    tools=sorted((sdk/'build-tools').iterdir(),key=lambda p:tuple(int(x) for x in re.findall(r'\d+',p.name)))[-1]
    def run(*args):return subprocess.check_output(args,text=True,stderr=subprocess.STDOUT)
    output=run(str(tools/'apksigner'),'verify','--print-certs',str(apk))
    expected=json.loads((kit/'manifests/dev-signature.json').read_text())['certificate_sha1']
    assert f'certificate SHA-1 digest: {expected}' in output,'APK不是匹配的sharedDev签名'
    badging=run(str(tools/'aapt2'),'dump','badging',str(apk))
    assert "package: name='yumo.achat.app'" in badging,'包名错误'
    if 'Release' in variant:assert 'application-debuggable' not in badging,'Release开启debuggable'
    secret=properties(workspace/'config/signing.local.properties')
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            assert not name.endswith(('.jks','.keystore','.local.properties')),'APK含私密文件'
            if name.endswith(('.dex','.xml','.arsc','.json','.txt')):
                data=archive.read(name)
                assert all(secret[k].encode(encoding) not in data for k in ['storePassword','keyPassword'] for encoding in ['utf-8','utf-16le']),'APK含签名密码'
    environment='prod' if variant.startswith('prod') else 'dev'
    kind='release' if variant.endswith('Release') else 'debug'
    expected_config=expected_config if expected_config is not None else properties(kit/'config/dev/effective.properties')
    resources=run(str(tools/'aapt2'),'dump','resources',str(apk))
    canonical={'kit.prod.mode':'DEV_REUSE'} | expected_config
    fingerprint=hashlib.sha256(''.join(f'{k}={v}\n' for k,v in sorted(canonical.items())).encode()).hexdigest()
    assert fingerprint in resources,'最终APK配置指纹不匹配'
    firebase=json.loads((kit/'config/dev/google-services.json').read_text())
    client=next(c for c in firebase['client'] if c['client_info']['android_client_info']['package_name']=='yumo.achat.app')
    assert client['client_info']['mobilesdk_app_id'] in resources,'最终Google Services资源不匹配'
    xml=run(str(tools/'aapt2'),'dump','xmltree',str(apk),'--file','AndroidManifest.xml')
    assert 'com.google.firebase.provider.FirebaseInitProvider' not in xml,'Firebase提前自动初始化'
    assert 'kit.configuration.fingerprint' in xml,'最终Manifest缺配置指纹'
    generated=workspace/f'{app_module}/build/generated/source/buildConfig/{environment}/{kind}'
    configs=list(generated.rglob('BuildConfig.java'))
    assert len(configs)==1,'缺少或重复生成BuildConfig'
    text=configs[0].read_text()
    for key,value in expected_config.items():
        if not key.startswith('build.'):continue
        _,typ,name=key.split('.',2)
        m=re.search(r'\b'+name+r'\s*=\s*(.+);',text)
        assert m,'生成字段缺失：'+name
        actual=json.loads(m[1]) if typ=='string' else m[1]
        assert actual==value,'生成字段不同：'+name
    print(f'{variant}：固定包名、sharedDev/Firebase证书、全部dev生成配置、release属性及秘密排除通过')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--kit',type=Path,default=Path(__file__).resolve().parents[1]);p.add_argument('--workspace',type=Path);p.add_argument('--apk',type=Path);p.add_argument('--sdk',type=Path);p.add_argument('--variant',default='devRelease');a=p.parse_args()
    verify_kit(a.kit.resolve())
    if a.apk:
        if not a.workspace or not a.sdk:p.error('--apk需要--workspace和--sdk')
        verify_apk(a.kit,a.workspace,a.apk,a.sdk,a.variant)
