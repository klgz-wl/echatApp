#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Core新项目脚手架：问答、初始化、检查、构建、重构交付与可回滚部署（Python3.9+）。"""
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tempfile
import uuid
import xml.etree.ElementTree as ET
sys.dont_write_bytecode = True
from common import properties, write_properties, write_json, sha
from create_workspace import create
from verify import verify_kit, verify_apk
from validate_model_plan import validate as validate_models, KEYWORDS

STATE = '.core-scaffold'
PROJECT = STATE + '/project.json'
RECEIPT = STATE + '/installed.json'
KIT = 'docs/core-rebuild/kit'
REPORT = STATE + '/verification.json'
IGNORE = '\n.DS_Store\n# Core脚手架的本机状态、日志、备份和签名\n.core-scaffold/transactions/\n.core-scaffold/prepared/\n.core-scaffold/lock\n.core-scaffold/installed.json\n.core-scaffold/verification.json\n.core-scaffold/verify.log\n*.local.json\nlocal.properties\nconfig/*.local.properties\n*.jks\n*.keystore\n*.p12\n*.pfx\n'
SECRET_SUFFIXES = ('.jks', '.keystore', '.p12', '.pfx', '.pem', '.key', '.local.properties')
STORAGE_KEYS = ['STORAGE_NAME','DATABASE_NAME','BILLING_STORAGE_NAME','GENERATION_STORAGE_DIRECTORY','PAYMENT_STORAGE_NAME','ANALYTICS_STORAGE_NAME']

GIT_RULES = """## 每次任务必须 git commit and push（Core脚手架规则）

1. 每次完成一个明确的开发、修复、重构、配置或文档任务后，自动执行中文 git commit，并 git push，无需重复询问。纯问答或没有文件变更时不制造空提交。
2. 开始和提交前检查 git status；只按明确路径暂存本任务文件，禁止 git add . 或 git add -A，禁止提交签名、密码、本机配置和构建缓存。
3. 提交前完成适用验证；纯文档检查内容、引用及 git diff --check。验证失败先修复，外部条件阻塞时如实记录，不伪称通过。
4. 推送当前分支已配置的 upstream；无 upstream 时推送 origin 的同名分支并建立跟踪。禁止 force push、改写已有提交或复制参考仓库的 .git/远程。
5. 无远程时保留本地提交并报告。网络、鉴权或推送失败时保留提交，报告原因，不把本地提交当作已推送；远程领先时正常整合并重新验证。
6. 保留目标已有 Git 仓库、分支和远程配置，不伪造作者或 Co-Authored-By。完成报告列出提交哈希、远程分支、变更与验证结果。
"""


def install_git_rules(root):
    path = safe_file(root, 'AGENTS.md')
    original = path.read_text() if path.exists() else '# 项目规则\n\n沟通、文档、代码注释和提交说明使用中文。\n'
    if GIT_RULES not in original:
        path.write_text(original.rstrip()+'\n\n'+GIT_RULES)


def check_new_target(target):
    require(not target.is_symlink(), '新项目目录不能是符号链接')
    if not target.exists(): return
    require(target.is_dir(), '目标必须是目录')
    entries = list(target.iterdir())
    require(all(p.name == '.git' for p in entries), '目标须为空或仅有.git；已有Android工程使用existing模式')
    if entries:
        marker = target/'.git'
        require(not marker.is_symlink() and (marker.is_dir() or marker.is_file()), '无效的Git仓库标记')
        result = subprocess.run(['git', '-C', str(target), 'rev-parse', '--show-toplevel'], capture_output=True, text=True)
        require(result.returncode == 0 and Path(result.stdout.strip()).resolve() == target.resolve(), '目标.git不是有效的本地工作仓库')


def publish_new_project(stage, target):
    # 发布前重查，保留目标目录和.git；失败仅撤回本次已移入的文件。
    check_new_target(target)
    if not target.exists():
        stage.rename(target)
        return
    require(not (stage/'.git').exists(), '装配产物不得包含Git元数据')
    moved = []
    try:
        for source in sorted(stage.iterdir()):
            destination = target/source.name
            require(not destination.exists() and not destination.is_symlink(), '发布位置已被占用：'+source.name)
            source.rename(destination)
            moved.append(source.name)
    except BaseException:
        for name in reversed(moved):
            (target/name).rename(stage/name)
        raise

class ScaffoldError(Exception): pass

def require(condition, message):
    if not condition: raise ScaffoldError(message)

def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))

def relative(name):
    require(isinstance(name, str) and bool(name), '文件路径不能为空')
    p = PurePosixPath(name)
    require(not p.is_absolute() and '..' not in p.parts and '\\' not in name and ':' not in name and name == p.as_posix(), '文件路径必须为规范相对路径')
    require(not any(x in ('.git','.gradle','.kotlin','__pycache__') for x in p.parts), '禁止操作缓存或Git内部文件')
    return p

def safe_file(root, name):
    p = root / relative(name)
    for part in [p, *p.parents]:
        if part == root.parent: break
        require(not part.is_symlink(), '拒绝通过符号链接读写：'+name)
    require(not p.exists() or p.is_file(), '文件位置被目录占用：'+name)
    return p

def digest(path): return sha(path) if path.is_file() else None

def dev_endpoints(gradle):
    match=re.search(r'"dev" to mapOf\((.*?)\)',gradle.read_text(),re.S)
    require(match is not None,'缺少约定的Gradle dev地址表')
    return dict(re.findall(r'"([A-Z_]+)" to "([^"\n]*)"',match[1]))

def private_name(name):
    return name.endswith(SECRET_SUFFIXES) or name.endswith('.local.json') or PurePosixPath(name).name == 'local.properties' or PurePosixPath(name).name.startswith('.env')

def public_files(root):
    """部署边界：完整Gradle输入/源模块/接入文档；不收录产物、私密配置和业务Git信息。"""
    result = {}
    app = read_json(root/PROJECT)['app_module']
    roots = [app, 'core', 'integration', 'gradle', 'config', 'docs/core-rebuild']
    singles = ['build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew','gradlew.bat','.gitignore','AGENTS.md',PROJECT]
    paths = [root/x for x in singles]
    for name in roots:
        folder = root/name
        if folder.exists(): paths.extend(folder.rglob('*'))
    for path in sorted(set(paths)):
        name = path.relative_to(root).as_posix()
        if any(p in ('build','.gradle','.kotlin','__pycache__','.git','.DS_Store') for p in Path(name).parts) or private_name(name): continue
        require(not path.is_symlink(), '部署输入不能包含符号链接：'+name)
        if path.is_file():
            safe_file(root,name)
            result[name] = {'sha256':sha(path), 'executable':bool(path.stat().st_mode & 0o111)}
    return result

def tree_hash(files):
    return hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()

def validate_answers(value):
    required = ['project_name','app_namespace','core_namespace','model_prefix','storage_prefix','app_module']
    require(all(isinstance(value.get(k),str) and value[k].strip() == value[k] and value[k] for k in required), '项目名称/namespace/前缀/模块信息不完整')
    require(value.get('developer_confirmed') is True, '请先运行configure并明确确认，或填写developer_confirmed=true')
    require(value.get('prod_mode') == 'DEV_REUSE', '初始化使用已确认的DEV_REUSE；正式配置在生成后按指南替换')
    require(value.get('retain_all_capabilities') is True, '当前脚手架部署完整Core；裁剪请在初始化后单独review')
    for key in ['app_namespace','core_namespace']:
        parts = value[key].split('.')
        require(len(parts)>1 and all(re.fullmatch(r'[a-z][a-z0-9_]*',x) and x not in KEYWORDS for x in parts), key+'不是有效namespace')
    require(value['app_namespace'] != value['core_namespace'], 'app和core的namespace必须独立')
    require(re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*',value['model_prefix']) is not None, 'Model前缀不合法')
    require(re.fullmatch(r'[a-z][a-z0-9_]*',value['storage_prefix']) is not None, '存储前缀只使用小写字母、数字、下划线')
    require(re.fullmatch(r'[a-z][a-z0-9_-]*',value['app_module']) is not None and value['app_module'] not in {'core','integration','config','gradle','docs','build','tools'}, '宿主模块名不合法或与保留目录冲突')
    require(len(value['project_name']) <= 80 and not any(ord(c)<32 for c in value['project_name']), '项目名称过长或含控制字符')
    require('"' not in value['project_name'] and '$' not in value['project_name'] and '\\' not in value['project_name'], '项目名称不支持引号、反斜线和美元符号')
    mode=value.get('project_mode','new')
    require(mode in {'new','existing'},'project_mode只能是new或existing')
    return {k:value[k] for k in required+['prod_mode','retain_all_capabilities','developer_confirmed']} | {'project_mode':mode}

def configure(output):
    require(not output.exists(), '参数文件已存在，拒绝覆盖')
    prompts = [('project_name','新项目名称'),('app_namespace','app namespace（例如 com.example.app）'),('core_namespace','core namespace（例如 com.example.core）'),('model_prefix','Model新增字段前缀（必须明确填写，无默认值）'),('storage_prefix','本地存储前缀'),('app_module','宿主模块名 [app]')]
    mode=input('项目模式：1新建 / 2接入已有工程：').strip()
    require(mode in {'1','2'},'请选择1或2')
    answers = {k:input(label+'：').strip() for k,label in prompts}
    answers['project_mode']='new' if mode=='1' else 'existing'
    answers['app_module'] = answers['app_module'] or 'app'
    answers.update(prod_mode='DEV_REUSE',retain_all_capabilities=True,developer_confirmed=True)
    answers['_local']={
        'target':input('新建/已有项目的目标目录：').strip(),
        'private_signing':input('独立private-dev-signing附件目录（可留空稍后装配）：').strip(),
        'sdk':input('Android SDK目录（可留空使用环境变量）：').strip(),
    }
    require(bool(answers['_local']['target']),'目标目录不能为空')
    # 问答路径相对运行目录；保存绝对路径，避免参数文件放到父目录后含义改变。
    answers['_local'] = {k:str(Path(v).expanduser().absolute()) if v else '' for k,v in answers['_local'].items()}
    validate_answers(answers)
    print(json.dumps(answers,ensure_ascii=False,indent=2))
    print('dev固定yumo.achat.app；prod先完整复用dev；保留全部Core能力。签名另从本地附件装配。')
    print('初始版本、阈值、协议/联系配置沿用交付基线；项目名称、namespace和存储前缀按本表替换，正式品牌/服务配置由后续AI任务确认。')
    require(input('确认上述参数并授权初始化？输入 yes：').strip().lower() == 'yes', '未确认，不写文件')
    write_json(output,answers)
    print('参数已保存：'+str(output))

def verify_private(kit, private):
    manifest = read_json(private/'manifest.json')
    for item in manifest['files']:
        path = safe_file(private,item['path'])
        require(digest(path) == item['sha256'], '私密附件完整性错误')
    signing = properties(private/'config/signing.local.properties')
    require(signing.get('storeFile') == 'config/shared-dev.jks', '附件必须使用相对sharedDev路径')
    require(all(signing.get(k) for k in ['storePassword','keyAlias','keyPassword']), '私密附件签名配置不全')
    require(sha(private/'config/shared-dev.jks') == read_json(kit/'manifests/dev-signature.json')['keystore_sha256'], '私密证书与普通包不匹配')

def customize(root, answers):
    replacements = {'com.vexora.app':answers['app_namespace'],'com.zorv.core':answers['core_namespace']}
    pattern = re.compile(r'(?:com\.vexora\.app|com\.zorv\.core)(?![A-Za-z0-9_])')
    for path in sorted(root.rglob('*')):
        if not path.is_file() or path.suffix not in {'.kt','.kts','.pro','.xml','.properties'}: continue
        text = pattern.sub(lambda m:replacements[m[0]],path.read_text())
        if answers['app_module'] != 'app':
            text = text.replace('\":app\"','\":'+answers['app_module']+'\"').replace('../app/', '../'+answers['app_module']+'/')
        path.write_text(text)
    # 一次匹配原包路径；先暂存所有待迁移文件，支持包路径互换且不覆盖源码。
    path_pattern = re.compile(r'/(?:com/vexora/app|com/zorv/core)(?=/)')
    moves = []
    for path in sorted(root.rglob('*.kt')):
        name = path.relative_to(root).as_posix()
        name = path_pattern.sub(lambda m:'/'+replacements[m[0][1:].replace('/','.')].replace('.','/'),name,count=1)
        if path != root/name: moves.append((path,root/name))
    sources = {p for p,_ in moves}
    require(len({p for _,p in moves}) == len(moves), 'namespace迁移产生重复文件路径')
    require(all(not target.exists() or target in sources for _,target in moves), 'namespace迁移目标已有文件')
    with tempfile.TemporaryDirectory(prefix='.namespace-',dir=root) as temp:
        for index,(path,_) in enumerate(moves): path.rename(Path(temp)/str(index))
        for index,(_,target) in enumerate(moves):
            target.parent.mkdir(parents=True,exist_ok=True)
            (Path(temp)/str(index)).rename(target)
    p = root/'settings.gradle.kts'
    p.write_text(p.read_text().replace('rootProject.name = "Vexora"','rootProject.name = '+json.dumps(answers['project_name'],ensure_ascii=False)))
    values = properties(root/'config/app.properties')
    for key in STORAGE_KEYS:
        values['build.string.'+key] = answers['storage_prefix']+'_'+key.lower()
    write_properties(root/'config/app.properties',values)
    strings = root/'app/src/main/res/values/strings.xml'
    label=answers['project_name'].replace("'", "\\'")
    if label.startswith(('@','?')): label='\\'+label
    xml = ET.parse(strings); xml.getroot().find("string[@name='app_name']").text = label
    xml.write(strings,encoding='utf-8',xml_declaration=True)
    if answers['app_module'] != 'app': (root/'app').rename(root/answers['app_module'])


def initialize(kit, target, answers, private=None, sdk=None, dry_run=False):
    answers = validate_answers(answers)
    verify_kit(kit)
    check_new_target(target)
    if private: verify_private(kit,private)
    if sdk: require((sdk/'platform-tools').is_dir(), 'Android SDK目录无效')
    print(json.dumps({'target':str(target),'project':answers,'dev_identity':'yumo.achat.app','signing':bool(private),'action':'初始化完整工程和AI重构任务'},ensure_ascii=False,indent=2))
    if dry_run: return
    target.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.core-init-',dir=target.parent) as temp:
        stage = Path(temp)/'project'
        create(kit,stage,private)
        customize(stage,answers)
        shutil.copytree(kit,stage/KIT)
        meta = answers | {'schema':1,'phase':'BASELINE_PENDING_REFACTOR','kit_baseline':read_json(kit/'manifests/baseline.json')['baseline']}
        write_json(stage/PROJECT,meta)
        (stage/'.gitignore').write_text((stage/'.gitignore').read_text()+IGNORE)
        if sdk: write_properties(stage/'local.properties',{'sdk.dir':str(sdk)})
        handoff = stage/'docs/core-rebuild/开始重构.md'
        handoff.write_text('# 新项目Core重构任务\n\n开发者已确认参数：\n\n```json\n'+json.dumps(answers,ensure_ascii=False,indent=2)+'\n```\n\n当前工程为已接入的基线，尚未完成职责重划分或Model扩展。请读取kit/prompts/完整重建提示词.md、kit/templates/职责与接入映射.md和kit/manifests/models.json。按确认前缀对全部自有Model设计1–5字段，生成一次后冻结；保留序列化、旧数据、equals/hash/copy/重试语义。不要把namespace替换当作结构重构。\n\n实施并维护本目录model-plan.json；其中每项增加target_file（相对工程路径）及extension_files（字段/serializer实现相对路径列表）。保留基线source_id，新增模型使用new:标识。完成本目录refactor-review.md，记录职责/公共API映射、完整宿主接入、逐Model编码与兼容测试、未验收平台条件。随后运行脚手架verify --refactored；通过后pack/deploy。私密签名不加入材料包。\n')
        handoff.write_text(handoff.read_text()+'\n上述参数已由开发者在脚手架中明确确认，不重复询问前缀等已知项。先检查项目规则和资料，再直接分阶段实施、测试及修复。正式prod资料缺失不阻塞dev开发。只有新增业务选择或真实冲突需要开发者决策时才补充提问。\n\n初始版本/公共阈值/协议/联系值继承基线，发布前确认品牌与正式资料。dev服务身份固定不变。本机签名路径不写入此提示词；在config中读取。全程区分基线接入、实际重构、构建验证及真实平台验收，不能把仅有空台账或复制源码标为完成。\n')
        handoff.write_text(handoff.read_text()+'''\n## 必须执行到完成\n\n按配置/网络/会话→地区/归因/匿名profile→四端埋点→目录/上传/生成/作品→钱包/Play/双支付→宿主恢复的顺序实施，始终保留独立core。多参数改Model后纳入同一字段台账。文案用资源，环境配置外置，Core不依赖app BuildConfig。遵守目标AGENTS及提交规则，不修改材料包中的只读基线来伪造验证通过。\n\n完成真实源码与宿主接入后，在目标工程根执行：\n\n```sh\npython3 docs/core-rebuild/kit/validation/core_scaffold.py doctor --project .\npython3 docs/core-rebuild/kit/validation/core_scaffold.py verify --project . --refactored\npython3 docs/core-rebuild/kit/validation/core_scaffold.py pack --project . --output ../core-refactored-bundle\n```\n\n失败先修复并重跑；输出目录已存在时选择新目录，不覆盖旧交付。最终输出源码/API映射、Model台账、完整接入清单、真实命令结果、部署包路径和未验证项；没有真实条件时不声称支付/生成/归因平台验收。只有实际重构与当前源码验证完成，才标记工作完成。\n''')
        handoff.write_text(handoff.read_text()+'\n## 台账格式与已确认入口\n\n完整提示词、首次问答和阶段零均复用本文件及.core-scaffold/project.json中的已有确认，不重复等待。目标model-plan.json采用schema=2：models登记每个实际目标类型，baseline_mapping完整记录基线到目标的拆分/合并和原字段去向；新增类型使用new:完整类名。具体JSON格式和语义验收见kit/templates/Model审查与改造方法.md。模板自有状态、参数与值快照也纳入模型范围；reference和manifest保持只读。签名/设备缺失只阻塞对应验证，继续独立实现。\n')
        write_json(stage/'docs/core-rebuild/model-plan.json',{'schema':2,'prefix':answers['model_prefix'],'developer_confirmed':True,'models':[],'baseline_mapping':[],'status':'待AI逐项实现；不能通过refactored校验'})
        install_git_rules(stage)
        handoff.write_text(handoff.read_text()+'\n'+GIT_RULES)
        write_json(stage/RECEIPT,{'schema':1,'files':public_files(stage)})
        publish_new_project(stage,target)
    print('初始化完成。下一步doctor/verify检查基线，再将docs/core-rebuild/开始重构.md交给AI。')


def prepare_existing(kit, target, answers, private=None, sdk=None, dry_run=False):
    """先提供完整可编译参照与精确AI任务，已有宿主的语义合并由AI完成。"""
    answers=validate_answers(answers)
    require(target.is_dir(),'已有项目目录不存在')
    require((target/'settings.gradle.kts').is_file() or (target/'settings.gradle').is_file(),'目标不是Android/Gradle工程')
    require(not (target/STATE).exists() and not (target/'docs/core-rebuild').exists(),'已有接入工作区，拒绝覆盖；请继续此前的开始重构.md任务')
    require(not target.is_symlink() and not (target/'docs').is_symlink(),'目标/文档目录不能是符号链接')
    safe_file(target,'.gitignore')
    safe_file(target,'AGENTS.md')
    verify_kit(kit)
    if private: verify_private(kit,private)
    inventory={}
    for name in ['settings.gradle.kts','settings.gradle','build.gradle.kts','build.gradle','gradle/libs.versions.toml',answers['app_module']+'/build.gradle.kts',answers['app_module']+'/build.gradle']:
        file=safe_file(target,name)
        if file.is_file():inventory[name]=sha(file)
    print('已有工程模式：新增接入材料、独立基线工作区，追加.gitignore与AGENTS.md任务规则；不替换宿主/Gradle/业务文件。')
    if dry_run: return
    with tempfile.TemporaryDirectory(prefix='.core-existing-',dir=target.parent) as temp:
        prepared=Path(temp)/'prepared'
        initialize(kit,prepared,answers,private,sdk)
        prompt=prepared/'docs/core-rebuild/开始重构.md'
        prompt.write_text(prompt.read_text()+'\n## 已有工程接入要求\n\n本任务目标为已有Android工程，独立参考工作区位于工程根的.core-scaffold/prepared。先读取现有AGENTS、页面架构、Gradle/依赖版本和初始化顺序，保留已有页面、导航、资源、模块、签名及业务。不要把prepared中的app目录或settings文件整体覆盖到当前工程。\n\n请以已确认参数完成真实重构并语义合并：模块include和仓库/plugin版本；类型化配置/外置dev与临时prod；Hilt绑定；Application、RESUMED Activity和地区前置门禁；AppsFlyer/Referrer及Firebase/数数/backend四端；共享生成状态和支付恢复/网页桥接。冲突先形成具体方案，不静默删除已有功能。存在旧core时逐项迁移接口/调用点，完成后移除被替代源码，不能让旧/新两套实现并存而重入初始化。\n\n本脚手架验证协议为Kotlin DSL、独立core、两个integration统计模块、environment dev/prod和Debug/Release；宿主模块名按参数。现有Groovy或不同矩阵先评估并明确迁移映射，再适配验证入口；不把初始化参照构建通过当作目标集成通过。签名只从prepared/config的私密文件复用到目标config，保留忽略规则。完成后在目标根运行doctor与verify --refactored，而不是只验证prepared。\n\n初始工程构建文件哈希（用于评估合并变更，不含配置值）：\n\n```json\n'+json.dumps(inventory,ensure_ascii=False,indent=2)+'\n```\n')
        # 全部准备成功后发布材料；失败清理本次新增目录，不触碰已有业务。
        old_ignore=(target/'.gitignore').read_bytes() if (target/'.gitignore').is_file() else None
        old_rules=(target/'AGENTS.md').read_bytes() if (target/'AGENTS.md').is_file() else None
        try:
            (target/STATE).mkdir(mode=0o700)
            shutil.copytree(prepared/'docs/core-rebuild',target/'docs/core-rebuild')
            meta=read_json(prepared/PROJECT) | {'phase':'EXISTING_PENDING_INTEGRATION'}
            write_json(target/PROJECT,meta)
            prepared.rename(target/STATE/'prepared')
            (target/'.gitignore').write_text((old_ignore.decode() if old_ignore is not None else '')+IGNORE)
            install_git_rules(target)
        except BaseException:
            if old_rules is not None:(target/'AGENTS.md').write_bytes(old_rules)
            else:(target/'AGENTS.md').unlink(missing_ok=True)
            if old_ignore is not None:(target/'.gitignore').write_bytes(old_ignore)
            else:(target/'.gitignore').unlink(missing_ok=True)
            shutil.rmtree(target/STATE,ignore_errors=True)
            shutil.rmtree(target/'docs/core-rebuild',ignore_errors=True)
            raise
    print('已有工程接入任务已生成：docs/core-rebuild/开始重构.md；目标业务尚待AI整合。')


def doctor(root):
    meta = read_json(root/PROJECT); validate_answers(meta)
    app = meta['app_module']; kit = root/KIT
    verify_kit(kit)
    for name in ['settings.gradle.kts','gradlew',app+'/build.gradle.kts','core/build.gradle.kts',app+'/src/main/AndroidManifest.xml','config/dev.properties','config/prod.properties','config/dev-certificate.der']:
        require(safe_file(root,name).is_file(),'缺少接入文件：'+name)
    common = properties(root/'config/app.properties')
    require(common.get('app.namespace') == meta['app_namespace'] and common.get('core.namespace') == meta['core_namespace'], 'namespace与确认信息不一致')
    mode = common.get('kit.prod.mode'); require(mode in {'DEV_REUSE','FORMAL'}, '环境模式无效')
    fixed = properties(kit/'config/dev/dev.properties')
    actual = properties(root/'config/dev.properties')
    require(actual == fixed, '固定dev SDK/开关配置发生变化，请单独review后更新交付依据')
    require(sha(root/app/'src/dev/google-services.json') == sha(kit/'config/dev/google-services.json'), 'dev Firebase与交付包不一致')
    require(dev_endpoints(root/app/'build.gradle.kts') == dev_endpoints(kit/'templates/app/build.gradle.kts'), 'dev地址发生变化')
    if mode == 'DEV_REUSE':
        require(properties(root/'config/prod.properties') == actual, '临时prod必须完整复用dev')
        require(sha(root/app/'src/prod/google-services.json') == sha(root/app/'src/dev/google-services.json'), '临时prod Firebase不同')
    require((root/'config/signing.local.properties').is_file() and (root/'config/shared-dev.jks').is_file(),'缺dev签名附件；运行deploy/签名装配或按README放入config')
    require(sha(root/'config/shared-dev.jks') == read_json(kit/'manifests/dev-signature.json')['keystore_sha256'], 'dev证书文件不匹配')
    sdk_value = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or properties(root/'local.properties').get('sdk.dir')
    require(bool(sdk_value) and (Path(sdk_value)/'platform-tools').is_dir(), '请设置Android SDK或local.properties')
    require(shutil.which('java') is not None or bool(os.environ.get('JAVA_HOME')), '缺JDK，设置JAVA_HOME为JDK17')
    print('接入文件/dev配置/签名文件/SDK检查通过；签名密码与DI由Gradle构建核验。')
    return meta,Path(sdk_value)

def refactor_check(root):
    meta = read_json(root/PROJECT)
    plan = read_json(root/'docs/core-rebuild/model-plan.json')
    require(plan.get('prefix') == meta['model_prefix'], 'Model计划前缀与确认值不一致')
    validate_models(plan,read_json(root/KIT/'manifests/models.json'))
    def production(name):
        parts=relative(name).parts
        require(parts[0] in {'core','integration',meta['app_module']} and '/src/main/' in '/'+name, 'Model实现必须指向目标生产源码，不能指向参考材料或测试夹具')
        return safe_file(root,name)
    for row in plan['models']:
        require(production(row.get('target_file','')).is_file(),'目标Model源码缺失')
        files = row.get('extension_files',[])
        require(bool(files), '需登记扩展字段与serializer的实现文件')
        text = '\n'.join(production(name).read_text() for name in files)
        for extra in row['extras']:
            require(re.search(r'\b'+re.escape(extra['name'])+r'\b',text) is not None,'目标实现中缺少计划字段')
            require(extra['serial_name'] in text,'目标实现中缺少序列化字段名')
    review = root/'docs/core-rebuild/refactor-review.md'
    require(review.is_file() and len(review.read_text().strip()) >= 100,'缺职责/公共API与接入审查记录')
    print('重构计划与源码存在性检查通过；实际语义由对应测试及人工review保证。')


def verify_project(root, refactored=False):
    meta,sdk = doctor(root)
    if refactored: refactor_check(root)
    before = public_files(root); app = meta['app_module']
    tasks = [':core:testReleaseUnitTest',':integration:analytics-appsflyer:testReleaseUnitTest',f':{app}:testDevReleaseUnitTest',f':{app}:assembleDevDebug',f':{app}:assembleDevRelease',f':{app}:lintDevRelease']
    if properties(root/'config/app.properties')['kit.prod.mode'] == 'DEV_REUSE': tasks.append(f':{app}:assembleProdDebug')
    log = root/STATE/'verify.log'; log.parent.mkdir(exist_ok=True)
    report = {'passed':False,'refactored':refactored,'input_hash':tree_hash(before),'tasks':tasks,'device_platform_verified':False}
    write_json(root/REPORT,report)
    print('开始Gradle验证，日志保存在.core-scaffold/verify.log',flush=True)
    with log.open('w') as out:
        log.chmod(0o600)
        result = subprocess.run([str(root/'gradlew'),*tasks,'--console=plain'],cwd=root,stdout=out,stderr=subprocess.STDOUT)
    require(result.returncode == 0,'Gradle失败，查看本地verify.log修复后重试；未标记通过')
    require(public_files(root) == before, '验证期间源码发生变化，必须重新验证')
    expected = properties(root/'config/app.properties') | properties(root/'config/dev.properties') | {'applicationId':'yumo.achat.app'}
    # 地址由Gradle表维护；实际生成与APK资源另行比对。
    expected.update({'build.string.'+k:v for k,v in dev_endpoints(root/app/'build.gradle.kts').items()})
    variants=[('devDebug','dev/debug'),('devRelease','dev/release')]
    if properties(root/'config/app.properties')['kit.prod.mode'] == 'DEV_REUSE': variants.append(('prodDebug','prod/debug'))
    for variant,folder in variants:
        verify_apk(root/KIT,root,root/app/'build/outputs/apk'/folder/f'{app}-{folder.replace("/","-")}.apk',sdk,variant,expected_config=expected,app_module=app)
    report['passed'] = True
    write_json(root/REPORT,report)
    print('构建、单元测试、Lint及dev APK身份检查通过；未执行真实平台或付费操作。')


def pack(root, output):
    require(not output.exists(),'输出目录已存在')
    refactor_check(root)
    report = read_json(root/REPORT)
    files = public_files(root)
    require(report.get('passed') is True and report.get('refactored') is True and report.get('input_hash') == tree_hash(files), '需要当前源码的verify --refactored成功记录；旧构建不能用于交付')
    secrets = properties(root/'config/signing.local.properties')
    for name in files:
        data = (root/name).read_bytes()
        for key in ['storePassword','keyPassword']:
            if secrets.get(key): require(secrets[key].encode() not in data,'公开部署输入包含签名密码：'+name)
    output.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.core-pack-',dir=output.parent) as temp:
        stage=Path(temp)/'bundle';stage.mkdir()
        for name in files:
            target=stage/'payload'/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(root/name,target)
        write_json(stage/'deployment.json',{'schema':1,'project':read_json(root/PROJECT),'files':files,'verification':report})
        stage.rename(output)
    print('重构部署包已生成，不含私密签名；部署前先运行deploy生成差异计划。')


def bundle_files(bundle):
    manifest = read_json(bundle/'deployment.json')
    require(manifest.get('schema') == 1,'不支持的部署包格式')
    validate_answers(manifest['project'])
    files = manifest['files']
    for name,item in files.items():
        require(not private_name(name), '部署包不得携带私密文件')
        require(not name.startswith(STATE+'/') or name == PROJECT, '部署包包含不允许的内部状态')
        require(digest(safe_file(bundle/'payload',name)) == item['sha256'],'部署文件哈希不同：'+name)
        require(bool((bundle/'payload'/name).stat().st_mode & 0o111) == item.get('executable'), '部署文件执行权限不匹配：'+name)
    expected = manifest['verification']
    require(expected.get('passed') is True and expected.get('refactored') is True and expected.get('input_hash') == tree_hash(files), '部署包缺少匹配的重构验证结果')
    require(read_json(bundle/'payload'/PROJECT) == manifest['project'],'工程元信息与部署清单不一致')
    return files


def merge_ignore(original, supplied):
    # 最后追加保护块；不能去重掉已有反向规则之后必须再次出现的忽略规则。
    if original.endswith(IGNORE): original=original[:-len(IGNORE)]
    if supplied.endswith(IGNORE): supplied=supplied[:-len(IGNORE)]
    lines=original.splitlines()
    for line in supplied.splitlines():
        if line and line not in lines: lines.append(line)
    return '\n'.join(lines).rstrip('\n')+IGNORE


def deployment_plan(bundle, target):
    files = bundle_files(bundle)
    receipt=safe_file(target,RECEIPT)
    previous = read_json(receipt).get('files',{}) if receipt.is_file() else {}
    actions=[]
    for name in sorted(set(files)|set(previous)):
        require(not private_name(name) and (not name.startswith(STATE+'/') or name==PROJECT), '部署记录包含不允许管理的文件')
        path = safe_file(target,name); current = digest(path); new = files.get(name,{}).get('sha256')
        before_executable=bool(path.stat().st_mode & 0o111) if path.is_file() else None
        after_executable=files.get(name,{}).get('executable')
        content = None
        if name == '.gitignore' and name in files:
            original = path.read_text() if path.exists() else ''
            supplied = (bundle/'payload'/name).read_text()
            content = merge_ignore(original,supplied)
            new = hashlib.sha256(content.encode()).hexdigest()
        if current == new and before_executable == after_executable: continue
        action={'path':name,'before':current,'after':new,'before_executable':before_executable,'after_executable':after_executable,'operation':'delete' if new is None else ('create' if current is None else 'replace')}
        if content is not None: action['content']=content
        actions.append(action)
    return {'schema':1,'bundle_hash':sha(bundle/'deployment.json'),'target':str(target),'actions':actions,'conflicts':[x['path'] for x in actions if x['before'] is not None]}

@contextlib.contextmanager
def project_lock(root):
    folder=root/STATE
    require(not folder.is_symlink(),'状态目录不能是符号链接')
    folder.mkdir(parents=True,exist_ok=True)
    require(not (folder/'transactions').is_symlink(),'备份目录不能是符号链接')
    lock=folder/'lock'
    try: fd=os.open(lock,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    except FileExistsError: raise ScaffoldError('已有脚手架操作或遗留lock；检查事务后再清理lock')
    os.close(fd)
    try: yield
    finally: lock.unlink(missing_ok=True)

def apply_deployment(bundle, target, approved):
    require(approved == deployment_plan(bundle,target), '差异计划已过期或被修改，请重新生成并review')
    target.mkdir(parents=True,exist_ok=True)
    with project_lock(target):
        require(approved == deployment_plan(bundle,target),'取得锁后目标已变化')
        if not approved['actions']:
            print('目标已与部署包一致，无需重复写入。')
            return None
        identifier=uuid.uuid4().hex
        txn=target/STATE/'transactions'/identifier;txn.mkdir(parents=True,mode=0o700)
        actions=approved['actions']
        # receipt也纳入事务，确保失败或回滚后文件所有权一致。
        receipt=safe_file(target,RECEIPT)
        journal={'id':identifier,'status':'prepared','actions':actions,'receipt_before':digest(receipt)}
        if receipt.exists(): shutil.copy2(receipt,txn/'receipt.before')
        for item in actions:
            if item['before'] is not None:
                backup=txn/'before'/item['path'];backup.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(target/item['path'],backup)
        write_json(txn/'journal.json',journal)
        try:
            for item in actions:
                dst=safe_file(target,item['path'])
                require(digest(dst)==item['before'],'部署期间目标被修改：'+item['path'])
                require((bool(dst.stat().st_mode & 0o111) if dst.exists() else None)==item['before_executable'],'部署期间文件权限被修改：'+item['path'])
                if item['after'] is None: dst.unlink(missing_ok=True)
                else:
                    dst.parent.mkdir(parents=True,exist_ok=True)
                    temporary=dst.with_name(dst.name+'.core-scaffold-tmp-'+identifier)
                    try:
                        if 'content' in item: temporary.write_text(item['content'])
                        else: shutil.copy2(bundle/'payload'/item['path'],temporary)
                        os.replace(temporary,dst)
                    finally: temporary.unlink(missing_ok=True)
            installed=bundle_files(bundle).copy()
            if (target/'.gitignore').is_file(): installed['.gitignore']={'sha256':sha(target/'.gitignore'),'executable':False}
            receipt_pending=txn/'receipt.after'
            write_json(receipt_pending,{'schema':1,'files':installed,'transaction':identifier})
            journal['receipt_after']=sha(receipt_pending)
            write_json(txn/'journal.json',journal)
            os.replace(receipt_pending,receipt)
            journal['status']='applied';write_json(txn/'journal.json',journal)
        except BaseException:
            restore(target,txn,journal,check=True)
            raise
    print('部署完成，事务ID：'+identifier+'；请在目标项目重新装配签名并运行verify --refactored。')
    return identifier


def restore(root, txn, journal, check=True):
    # 全量校验备份后再写目标，避免损坏备份导致半次回滚。
    for item in journal['actions']:
        if item['before'] is not None:
            backup=safe_file(txn,'before/'+item['path'])
            require(digest(backup)==item['before'],'回滚备份已损坏：'+item['path'])
            require(bool(backup.stat().st_mode & 0o111)==item['before_executable'],'回滚备份权限已变化：'+item['path'])
    receipt=safe_file(root,RECEIPT)
    receipt_backup=safe_file(txn,'receipt.before')
    require(digest(receipt_backup)==journal.get('receipt_before'),'部署清单备份已损坏')
    if check:
        for item in journal['actions']:
            path=safe_file(root,item['path'])
            current=(digest(path),bool(path.stat().st_mode & 0o111) if path.exists() else None)
            require(current in {(item['before'],item['before_executable']),(item['after'],item['after_executable'])},'部署后文件或权限已被修改，拒绝覆盖回滚：'+item['path'])
        require(digest(receipt) in {journal.get('receipt_before'),journal.get('receipt_after')},'部署清单已变化，先回滚较新的事务')
    for item in reversed(journal['actions']):
        path=safe_file(root,item['path'])
        if item['before'] is None: path.unlink(missing_ok=True)
        else:
            path.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(txn/'before'/item['path'],path)
    if receipt_backup.exists(): shutil.copy2(receipt_backup,receipt)
    else: receipt.unlink(missing_ok=True)
    journal['status']='rolled_back';write_json(txn/'journal.json',journal)


def install_signing(root, private):
    verify_private(root/KIT,private)
    for name in ['shared-dev.jks','signing.local.properties']:
        target=safe_file(root,'config/'+name);source=private/'config'/name
        require(not target.exists() or sha(target)==sha(source),'已有签名不同，拒绝覆盖')
    for name in ['shared-dev.jks','signing.local.properties']:
        target=root/'config'/name;target.parent.mkdir(exist_ok=True);shutil.copy2(private/'config'/name,target);target.chmod(0o600)
    print('固定dev签名已装配。')


def main():
    require(sys.flags.optimize == 0,'请使用普通python3运行，不能用-O跳过材料/Model断言校验')
    parser=argparse.ArgumentParser(description=__doc__)
    sub=parser.add_subparsers(dest='command',required=True)
    p=sub.add_parser('configure',help='交互提问并保存已确认参数');p.add_argument('--output',type=Path,required=True)
    p=sub.add_parser('init',help='按参数创建新工程或准备已有工程接入任务');p.add_argument('--kit',type=Path,default=Path(__file__).resolve().parents[1]);p.add_argument('--config',type=Path,required=True);p.add_argument('--target',type=Path);p.add_argument('--private-signing',type=Path);p.add_argument('--sdk',type=Path);p.add_argument('--dry-run',action='store_true')
    for command in ['doctor','verify','pack','signing','rollback']:
        p=sub.add_parser(command);p.add_argument('--project',type=Path,required=True)
        if command=='verify':p.add_argument('--refactored',action='store_true')
        if command=='pack':p.add_argument('--output',type=Path,required=True)
        if command=='signing':p.add_argument('--private-signing',type=Path,required=True)
        if command=='rollback':p.add_argument('--transaction',required=True)
    p=sub.add_parser('deploy',help='先输出差异计划，再apply已review计划');p.add_argument('--bundle',type=Path,required=True);p.add_argument('--target',type=Path,required=True);p.add_argument('--plan',type=Path,required=True);p.add_argument('--apply',action='store_true')
    args=parser.parse_args()
    for key,value in vars(args).items():
        if isinstance(value,Path):setattr(args,key,value.expanduser().absolute())
    if args.command=='configure': configure(args.output)
    elif args.command=='init':
        answers=read_json(args.config)
        for key in ['target','private_signing','sdk']:
            value=answers.get('_local',{}).get(key)
            if getattr(args,key) is None and value:
                path=Path(value).expanduser()
                setattr(args,key,(path if path.is_absolute() else args.config.parent/path).absolute())
        require(args.target is not None,'参数_local.target或--target必须填写目标目录')
        action=prepare_existing if answers.get('project_mode')=='existing' else initialize
        action(args.kit,args.target,answers,args.private_signing,args.sdk,args.dry_run)
    elif args.command=='doctor': doctor(args.project)
    elif args.command=='verify': verify_project(args.project,args.refactored)
    elif args.command=='pack': pack(args.project,args.output)
    elif args.command=='signing': install_signing(args.project,args.private_signing)
    elif args.command=='deploy':
        if args.apply: apply_deployment(args.bundle,args.target,read_json(args.plan))
        else:
            require(not args.plan.exists(),'计划文件已存在，请选择新路径')
            plan=deployment_plan(args.bundle,args.target);write_json(args.plan,plan)
            print(f'计划已写入：{args.plan}；变更{len(plan["actions"])}文件，其中覆盖/删除{len(plan["conflicts"])}文件。review后用--apply。')
    elif args.command=='rollback':
        require(re.fullmatch(r'[a-f0-9]{32}',args.transaction) is not None,'事务ID格式错误')
        txn=args.project/STATE/'transactions'/args.transaction
        with project_lock(args.project): restore(args.project,txn,read_json(txn/'journal.json'))
        print('事务已回滚。')

if __name__=='__main__':
    try: main()
    except (ScaffoldError,AssertionError,ValueError,KeyError,FileNotFoundError) as error:
        print('脚手架停止：'+str(error),file=sys.stderr);sys.exit(2)
