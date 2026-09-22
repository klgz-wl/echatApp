#!/usr/bin/env python3
"""装配可构建的基线验证宿主；不是自动替新项目选择前缀或执行重构。"""
import argparse
import os
from pathlib import Path
import re
import shutil
import sys
sys.dont_write_bytecode = True
from common import properties, write_properties


def create(kit, output, private=None):
    if output.exists():raise SystemExit('目标目录已存在，拒绝覆盖')
    output.mkdir(parents=True)
    ref=kit/'reference'
    for name in ['core','integration','gradle']:
        shutil.copytree(ref/name,output/name)
    for name in ['gradlew','gradlew.bat','gradle.properties','settings.gradle.kts']:
        shutil.copy2(ref/name,output/name)
    (output/'gradlew').chmod(0o755)
    root=(ref/'build.gradle.kts').read_text().split('// 使用 Android 官方转换器')[0]
    (output/'build.gradle.kts').write_text(root)
    shutil.copytree(kit/'templates/app',output/'app')
    (output/'app/proguard-rules.pro').write_text((ref/'app/proguard-rules.pro').read_text())
    config=output/'config';config.mkdir()
    common=properties(kit/'config/dev/app.properties')
    write_properties(config/'app.properties',common | {'kit.prod.mode':'DEV_REUSE'})
    for env in ['dev','prod']:
        shutil.copy2(kit/'config/dev/dev.properties',config/f'{env}.properties')
        folder=output/f'app/src/{env}';folder.mkdir(parents=True)
        shutil.copy2(kit/'config/dev/google-services.json',folder/'google-services.json')
    shutil.copy2(kit/'config/templates/dev-certificate.der',config/'dev-certificate.der')
    for name in ['signing.properties.example','signing-release.properties.example','prod-confirmation.properties.example']:
        shutil.copy2(kit/'templates'/name,config/name)
    host='app/src/main/java/com/vexora/app'
    for name in ['analytics/AnalyticsHub.kt','analytics/BusinessEventQueue.kt','analytics/ForegroundAnalytics.kt','analytics/FirebaseAnalyticsSink.kt','ui/login/DeviceRegionSource.kt','ui/login/RegionNetwork.kt','ui/login/StartupConfiguration.kt']:
        target=output/host/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(ref/host/name,target)
    di=(ref/host/'di/ConfigurationModule.kt').read_text()
    # 只移除品牌预览/页面配置，保留全部Core配置和真实仓库。
    blocks=re.split(r'(?=    @Provides)',di)
    excluded=['settingsConfiguration','profilePreview','generationOptions','mockTemplates']
    di=''.join(b for b in blocks if not any(re.search(r'fun\s+'+n+r'\b',b) for n in excluded))
    dest=output/host/'di/ConfigurationModule.kt';dest.parent.mkdir(parents=True);dest.write_text(di)
    manifest=(ref/'app/src/main/AndroidManifest.xml').read_text().replace('.VexoraApp','.HarnessApplication')
    manifest=manifest.replace('android:icon="@mipmap/ic_launcher"','').replace('@style/Theme.Vexora.Starting','@android:style/Theme.Material.Light.NoActionBar').replace('@style/Theme.Vexora','@android:style/Theme.Material.Light.NoActionBar')
    manifest=manifest.replace('</application>', '<meta-data android:name="kit.configuration.fingerprint" android:resource="@string/kit_configuration_fingerprint" /></application>')
    (output/'app/src/main/AndroidManifest.xml').write_text(manifest)
    shutil.copytree(ref/'app/src/main/assets',output/'app/src/main/assets')
    for name in ['res/xml/data_extraction_rules.xml']:
        target=output/'app/src/main'/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(ref/'app/src/main'/name,target)
    if private:
        for name in ['shared-dev.jks','signing.local.properties']:
            shutil.copy2(private/'config'/name,config/name);os.chmod(config/name,0o600)
    (output/'.gitignore').write_text('.gradle/\n.kotlin/\n**/build/\nlocal.properties\nconfig/*.local.properties\n*.jks\n*.keystore\n')
    print('基线验证宿主已装配；结构与Model重构尚未实施。')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--kit',type=Path,default=Path(__file__).resolve().parents[1]);p.add_argument('--output',type=Path,required=True);p.add_argument('--private-signing',type=Path);a=p.parse_args();create(a.kit.resolve(),a.output.resolve(),a.private_signing.resolve() if a.private_signing else None)
