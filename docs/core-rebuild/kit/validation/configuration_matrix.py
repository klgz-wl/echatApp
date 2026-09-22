#!/usr/bin/env python3
"""在已装配的隔离宿主演练配置状态切换；始终还原文件，不触及原工程/正式密钥。"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid
sys.dont_write_bytecode = True
from common import properties, write_properties, write_json


def matrix(root, report):
    assert (root/'app/src/main/java/com/vexora/app/HarnessApplication.kt').exists(), '只允许对交付宿主运行'
    paths = ['config/app.properties', 'config/dev.properties', 'config/prod.properties', 'app/build.gradle.kts',
             'app/src/prod/google-services.json', 'config/signing-release.local.properties', 'config/prod-confirmation.local.properties']
    saved = {name: (root/name).read_bytes() if (root/name).exists() else None for name in paths}
    assert saved['config/signing-release.local.properties'] is None, '隔离目录已有正式签名配置，拒绝演练'
    cert = root/'config/fixture-release.jks'
    assert not cert.exists(), '离线夹具证书已存在，拒绝覆盖'
    results = []
    def run(label, task, expected=True, message=None):
        value = subprocess.run(['./gradlew', task, '--console=plain'], cwd=root, capture_output=True, text=True)
        passed = (value.returncode == 0) == expected and (message is None or message in value.stdout + value.stderr)
        results.append({'case': label, 'task': task, 'expected_success': expected, 'actual_exit': value.returncode, 'passed': passed})
        write_json(report, results)
        print(label + ('：通过' if passed else '：失败，请在隔离宿主复现'), flush=True)
        assert passed, '配置演练失败：'+label
    try:
        run('临时prod拒绝正式发布', ':app:verifyFormalRelease', False, 'DEV_REUSE临时状态禁止正式发布')
        config = properties(root/'config/app.properties'); config['kit.prod.mode'] = 'FORMAL'
        write_properties(root/'config/app.properties', config)
        (root/'config/prod.properties').unlink(); (root/'app/src/prod/google-services.json').unlink()
        run('正式资料全缺时dev仍可构建', ':app:assembleDevDebug')
        run('正式资料缺失拒绝发布', ':app:verifyFormalRelease', False)
        (root/'config/prod.properties').write_bytes(saved['config/prod.properties'])
        (root/'app/src/prod/google-services.json').write_bytes(saved['app/src/prod/google-services.json'])
        run('正式占位包名被拒绝', ':app:verifyFormalRelease', False, '必须确认正式包名')
        gradle = saved['app/build.gradle.kts'].decode().replace('REPLACE_PROD_APPLICATION_ID', 'com.example.corefixture')
        for name, value in [('CORE_BASE_URL','https://api.example.invalid/api/v1/'), ('PAYMENT_BASE_URL','https://api.example.invalid/payment/v1/'), ('CORE_STREAM_URL','wss://api.example.invalid'), ('CORE_CDN_URL','https://cdn.example.invalid'), ('REGION_LOOKUP_URL','https://region.example.invalid/')]:
            gradle = gradle.replace('"'+name+'" to ""', '"'+name+'" to "'+value+'"')
        (root/'app/build.gradle.kts').write_text(gradle)
        run('正式Firebase错配被拒绝', ':app:verifyFormalRelease', False, 'Firebase与当前包名不匹配')
        firebase = json.loads(saved['app/src/prod/google-services.json'])
        for client in firebase['client']: client['client_info']['android_client_info']['package_name'] = 'com.example.corefixture'
        write_json(root/'app/src/prod/google-services.json', firebase)
        write_properties(root/'config/prod-confirmation.local.properties', {'confirmed':'true'})
        # 先复用sharedDev验证门禁，再换离线临时证书；不读取当前项目正式签名。
        write_properties(root/'config/signing-release.local.properties', properties(root/'config/signing.local.properties'))
        run('sharedDev证书不得冒充正式签名', ':app:verifyFormalRelease', False, '正式签名不能使用sharedDev证书')
        password = 'fixture-'+uuid.uuid4().hex
        environment = os.environ | {'KIT_FIXTURE_PASSWORD': password}
        generated = subprocess.run(['keytool','-genkeypair','-keystore',str(cert),'-storetype','PKCS12','-storepass:env','KIT_FIXTURE_PASSWORD','-keypass:env','KIT_FIXTURE_PASSWORD','-alias','fixture','-keyalg','RSA','-keysize','2048','-validity','2','-dname','CN=Offline Fixture'], env=environment, capture_output=True)
        assert generated.returncode == 0, '离线证书夹具生成失败'
        cert.chmod(0o600)
        write_properties(root/'config/signing-release.local.properties', {'storeFile':'config/fixture-release.jks','storePassword':password,'keyAlias':'fixture','keyPassword':password})
        (root/'config/signing-release.local.properties').chmod(0o600)
        run('离线正式配置夹具通过结构与签名检查', ':app:verifyFormalRelease')
        dev = properties(root/'config/dev.properties'); dev['build.boolean.ENABLE_APPSFLYER'] = 'invalid-fixture'
        write_properties(root/'config/dev.properties', dev)
        run('非法布尔值被拒绝', ':app:verifyDevConfiguration', False, '布尔配置无效')
        assert (root/'config/app.properties').exists()
    finally:
        for name, data in saved.items():
            path = root/name
            if data is None: path.unlink(missing_ok=True)
            else: path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
        cert.unlink(missing_ok=True)
    assert all((root/name).read_bytes() == saved[name] for name in paths if saved[name] is not None), '未还原原始配置'
    print('配置演练完成并已还原；离线夹具通过不代表正式平台身份或真实生产包已验证。')

if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--workspace',type=Path,required=True);p.add_argument('--report',type=Path,required=True)
    a=p.parse_args();matrix(a.workspace.resolve(),a.report.resolve())
