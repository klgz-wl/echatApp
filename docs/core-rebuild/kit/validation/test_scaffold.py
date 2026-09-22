# -*- coding: utf-8 -*-
"""验证脚手架对既有源码、确认信息、部署差异和失败回滚的保护。"""
import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import core_scaffold as s


def answers(mode='new'):
    return {'project_name':'Orbit','app_namespace':'com.example.orbit','core_namespace':'com.example.foundation','model_prefix':'ORB_','storage_prefix':'orbit','app_module':'mobile','project_mode':mode,'prod_mode':'DEV_REUSE','retain_all_capabilities':True,'developer_confirmed':True}

class ScaffoldTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.quiet=contextlib.redirect_stdout(io.StringIO());self.quiet.__enter__()
    def tearDown(self):self.quiet.__exit__(None,None,None);self.temp.cleanup()
    def bundle(self, text='new'):
        bundle=self.root/('bundle-'+text);payload=bundle/'payload';payload.mkdir(parents=True)
        s.write_json(payload/s.PROJECT,answers())
        (payload/'core').mkdir();(payload/'core/value.kt').write_text(text)
        (payload/'.gitignore').write_text('build/\n')
        files=s.public_files(payload)
        s.write_json(bundle/'deployment.json',{'schema':1,'project':answers(),'files':files,'verification':{'passed':True,'refactored':True,'input_hash':s.tree_hash(files)}})
        return bundle
    def test_prefix_required(self):
        value=answers();value['model_prefix']=''
        with self.assertRaises(s.ScaffoldError):s.validate_answers(value)
    def test_confirmation_required(self):
        value=answers();value['developer_confirmed']=False
        with self.assertRaises(s.ScaffoldError):s.validate_answers(value)
    def test_namespace_keyword_rejected(self):
        value=answers();value['app_namespace']='com.when.app'
        with self.assertRaises(s.ScaffoldError):s.validate_answers(value)
    def test_formal_not_implicitly_selected(self):
        value=answers();value['prod_mode']='FORMAL'
        with self.assertRaises(s.ScaffoldError):s.validate_answers(value)
    def test_reserved_module_rejected(self):
        value=answers();value['app_module']='core'
        with self.assertRaises(s.ScaffoldError):s.validate_answers(value)
    def test_existing_mode_retained(self):self.assertEqual('existing',s.validate_answers(answers('existing'))['project_mode'])
    def test_configure_paths_keep_current_directory_meaning(self):
        output=self.root/'parameters/answers.local.json'
        replies=['1','Orbit','com.example.orbit','com.example.foundation','ORB_','orbit','mobile','../target','../private','','yes']
        with patch('builtins.input',side_effect=replies):s.configure(output)
        local=s.read_json(output)['_local']
        self.assertEqual(str(Path('../target').absolute()),local['target'])
        self.assertEqual(str(Path('../private').absolute()),local['private_signing'])
        self.assertEqual('',local['sdk'])
    def test_path_traversal_rejected(self):
        for name in ['../outside','/absolute','core/../../x','core\\x','core/./x']:
            with self.assertRaises(s.ScaffoldError):s.relative(name)
    def test_symlink_target_rejected(self):
        target=self.root/'target';target.mkdir();outside=self.root/'outside';outside.mkdir();(target/'core').symlink_to(outside)
        with self.assertRaises(s.ScaffoldError):s.deployment_plan(self.bundle(),target)
    def test_payload_hash_tampering_rejected(self):
        bundle=self.bundle();(bundle/'payload/core/value.kt').write_text('tampered')
        with self.assertRaises(s.ScaffoldError):s.bundle_files(bundle)
    def test_plan_does_not_write_target(self):
        target=self.root/'target';s.deployment_plan(self.bundle(),target);self.assertFalse(target.exists())
    def test_stale_plan_rejected(self):
        target=self.root/'target';bundle=self.bundle();plan=s.deployment_plan(bundle,target)
        (target/'core').mkdir(parents=True);(target/'core/value.kt').write_text('user-change')
        with self.assertRaises(s.ScaffoldError):s.apply_deployment(bundle,target,plan)
        self.assertEqual('user-change',(target/'core/value.kt').read_text())
    def test_apply_is_idempotent(self):
        target=self.root/'target';bundle=self.bundle();s.apply_deployment(bundle,target,s.deployment_plan(bundle,target))
        self.assertEqual([],s.deployment_plan(bundle,target)['actions'])
    def test_preserves_existing_ignore_and_unrelated_files(self):
        target=self.root/'target';target.mkdir();(target/'.gitignore').write_text('my-local/\n');(target/'user.txt').write_text('owned')
        bundle=self.bundle();s.apply_deployment(bundle,target,s.deployment_plan(bundle,target))
        self.assertIn('my-local/',(target/'.gitignore').read_text());self.assertIn('*.jks',(target/'.gitignore').read_text())
        self.assertEqual('owned',(target/'user.txt').read_text())
    def test_ignore_protects_signing_after_existing_negation(self):
        target=self.root/'target';target.mkdir()
        subprocess.run(['git','init','-q',str(target)],check=True)
        (target/'.gitignore').write_text('*.jks\n!config/shared-dev.jks\nconfig/*.local.properties\n!config/signing.local.properties\n')
        bundle=self.bundle();s.apply_deployment(bundle,target,s.deployment_plan(bundle,target))
        for name in ['config/shared-dev.jks','config/signing.local.properties']:
            result=subprocess.run(['git','check-ignore','--quiet',name],cwd=target)
            self.assertEqual(0,result.returncode,name)
        self.assertEqual([],s.deployment_plan(bundle,target)['actions'])
    def test_rollback_restores_original_and_removes_new_files(self):
        target=self.root/'target';(target/'core').mkdir(parents=True);(target/'core/value.kt').write_text('old')
        bundle=self.bundle();identifier=s.apply_deployment(bundle,target,s.deployment_plan(bundle,target))
        txn=target/s.STATE/'transactions'/identifier;s.restore(target,txn,s.read_json(txn/'journal.json'))
        self.assertEqual('old',(target/'core/value.kt').read_text());self.assertFalse((target/s.PROJECT).exists())
    def test_rollback_refuses_later_edit(self):
        target=self.root/'target';bundle=self.bundle();identifier=s.apply_deployment(bundle,target,s.deployment_plan(bundle,target))
        (target/'core/value.kt').write_text('later-edit');txn=target/s.STATE/'transactions'/identifier
        with self.assertRaises(s.ScaffoldError):s.restore(target,txn,s.read_json(txn/'journal.json'))
        self.assertEqual('later-edit',(target/'core/value.kt').read_text())
    def test_rollback_refuses_later_permission_change(self):
        target=self.root/'target';bundle=self.bundle();identifier=s.apply_deployment(bundle,target,s.deployment_plan(bundle,target))
        file=target/'core/value.kt';file.chmod(0o755)
        txn=target/s.STATE/'transactions'/identifier
        with self.assertRaises(s.ScaffoldError):s.restore(target,txn,s.read_json(txn/'journal.json'))
        self.assertTrue(file.stat().st_mode & 0o111)
    def test_rollback_validates_all_backups_before_writing(self):
        target=self.root/'target';(target/'core').mkdir(parents=True);(target/'core/value.kt').write_text('old')
        bundle=self.bundle();identifier=s.apply_deployment(bundle,target,s.deployment_plan(bundle,target))
        txn=target/s.STATE/'transactions'/identifier
        (txn/'before/core/value.kt').write_text('corrupt')
        snapshot=s.public_files(target)
        with self.assertRaises(s.ScaffoldError):s.restore(target,txn,s.read_json(txn/'journal.json'))
        self.assertEqual(snapshot,s.public_files(target))
    def test_failure_after_receipt_write_restores_transaction(self):
        target=self.root/'target';bundle=self.bundle();real=s.write_json;failed=[]
        def write(path,value):
            if Path(path).name=='journal.json' and value.get('status')=='applied' and not failed:
                failed.append(True);raise OSError('fixture journal failure')
            return real(path,value)
        with patch.object(s,'write_json',side_effect=write),self.assertRaises(OSError):
            s.apply_deployment(bundle,target,s.deployment_plan(bundle,target))
        self.assertFalse((target/s.RECEIPT).exists())
        self.assertFalse((target/s.PROJECT).exists())
        self.assertFalse((target/'core/value.kt').exists())
    def test_failed_copy_rolls_back(self):
        target=self.root/'target';(target/'core').mkdir(parents=True);(target/'core/value.kt').write_text('old')
        bundle=self.bundle();real=s.shutil.copy2
        def copy(src,dst,*a,**kw):
            if str(src).endswith('payload/core/value.kt'):raise OSError('fixture failure')
            return real(src,dst,*a,**kw)
        with patch.object(s.shutil,'copy2',side_effect=copy),self.assertRaises(OSError):s.apply_deployment(bundle,target,s.deployment_plan(bundle,target))
        self.assertEqual('old',(target/'core/value.kt').read_text());self.assertFalse((target/s.PROJECT).exists())
    def test_removes_only_obsolete_managed_files(self):
        target=self.root/'target';first=self.bundle('one');s.apply_deployment(first,target,s.deployment_plan(first,target))
        (target/'unrelated.kt').write_text('keep')
        second=self.bundle('two');(second/'payload/core/value.kt').unlink();(second/'payload/core/moved.kt').write_text('two')
        manifest=s.read_json(second/'deployment.json');manifest['files']=s.public_files(second/'payload');manifest['verification']['input_hash']=s.tree_hash(manifest['files']);s.write_json(second/'deployment.json',manifest)
        s.apply_deployment(second,target,s.deployment_plan(second,target))
        self.assertFalse((target/'core/value.kt').exists());self.assertTrue((target/'core/moved.kt').exists());self.assertTrue((target/'unrelated.kt').exists())
    def test_lock_rejects_parallel_apply(self):
        target=self.root/'target';target.mkdir()
        with s.project_lock(target),self.assertRaises(s.ScaffoldError):
            with s.project_lock(target):pass
    def test_public_snapshot_excludes_build_and_signing(self):
        root=self.root/'project';root.mkdir();s.write_json(root/s.PROJECT,answers())
        for name in ['core/build/classes/a','config/signing.local.properties','config/shared-dev.jks','local.properties','core/source.kt','core/.DS_Store']:
            path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('fixture')
        files=s.public_files(root)
        self.assertIn('core/source.kt',files);self.assertEqual(2,len(files))
    def test_pack_rejects_unverified_or_modified_inputs(self):
        root=self.bundle()/'payload';s.write_json(root/s.REPORT,{'passed':True,'refactored':True,'input_hash':'old'})
        with patch.object(s,'refactor_check'),self.assertRaises(s.ScaffoldError):s.pack(root,self.root/'packed')
    def test_init_refuses_nonempty_project(self):
        root=self.root/'existing';root.mkdir();(root/'user.kt').write_text('keep')
        with patch.object(s,'verify_kit'),self.assertRaises(s.ScaffoldError):s.initialize(self.root,root,answers())
        self.assertEqual('keep',(root/'user.kt').read_text())
    def test_new_git_repository_keeps_branch_remote_and_metadata(self):
        target=self.root/'new';target.mkdir()
        subprocess.run(['git','init','-q',str(target)],check=True)
        subprocess.run(['git','-C',str(target),'remote','add','origin','https://example.invalid/own.git'],check=True)
        before={p.relative_to(target/'.git').as_posix():p.read_bytes() for p in (target/'.git').rglob('*') if p.is_file()}
        kit=self.root/'kit';(kit/'manifests').mkdir(parents=True)
        s.write_json(kit/'manifests/baseline.json',{'baseline':'fixture'})
        def create(kit,stage,private):
            stage.mkdir();(stage/'.gitignore').write_text('build/\n')
        with patch.object(s,'verify_kit'),patch.object(s,'create',side_effect=create),patch.object(s,'customize'):
            s.initialize(kit,target,answers())
        after={p.relative_to(target/'.git').as_posix():p.read_bytes() for p in (target/'.git').rglob('*') if p.is_file()}
        self.assertEqual(before,after)
        self.assertIn(s.GIT_RULES,(target/'AGENTS.md').read_text())
        self.assertIn(s.GIT_RULES,(target/'docs/core-rebuild/开始重构.md').read_text())
        self.assertIn('AGENTS.md',s.public_files(target))
    def test_new_git_repository_with_other_files_is_rejected(self):
        target=self.root/'new';target.mkdir()
        subprocess.run(['git','init','-q',str(target)],check=True)
        (target/'README.md').write_text('user')
        with self.assertRaises(s.ScaffoldError):s.check_new_target(target)
        self.assertEqual('user',(target/'README.md').read_text())
    def test_new_rejects_symlink_or_invalid_git_marker(self):
        outside=self.root/'outside';outside.mkdir()
        target=self.root/'new';target.mkdir();(target/'.git').symlink_to(outside)
        with self.assertRaises(s.ScaffoldError):s.check_new_target(target)
        (target/'.git').unlink();(target/'.git').mkdir()
        with self.assertRaises(s.ScaffoldError):s.check_new_target(target)
        link=self.root/'link';link.symlink_to(outside)
        with self.assertRaises(s.ScaffoldError):s.check_new_target(link)
    def test_new_publish_failure_restores_empty_repository(self):
        target=self.root/'new';target.mkdir()
        subprocess.run(['git','init','-q',str(target)],check=True)
        stage=self.root/'stage';stage.mkdir();(stage/'a').write_text('a');(stage/'b').write_text('b')
        rename=Path.rename
        def fail(path,destination):
            if path==stage/'b':raise OSError('fixture publish failure')
            return rename(path,destination)
        with patch.object(Path,'rename',fail),self.assertRaises(OSError):s.publish_new_project(stage,target)
        self.assertEqual(['.git'],[p.name for p in target.iterdir()])
        self.assertEqual('a',(stage/'a').read_text());self.assertEqual('b',(stage/'b').read_text())
    def test_git_rules_preserve_existing_and_are_idempotent(self):
        (self.root/'AGENTS.md').write_text('# Existing rules\n用户原有规则\n')
        s.install_git_rules(self.root);first=(self.root/'AGENTS.md').read_text()
        s.install_git_rules(self.root)
        self.assertEqual(first,(self.root/'AGENTS.md').read_text())
        self.assertTrue(first.startswith('# Existing rules\n用户原有规则\n'))
        self.assertIn(s.GIT_RULES,first)
    def test_git_rules_reject_symlink(self):
        outside=self.root/'outside';outside.write_text('keep')
        (self.root/'AGENTS.md').symlink_to(outside)
        with self.assertRaises(s.ScaffoldError):s.install_git_rules(self.root)
        self.assertEqual('keep',outside.read_text())
    def test_customization_updates_package_and_module_without_identity_change(self):
        root=self.root/'project'
        for name,text in {'app/src/main/java/com/vexora/app/Test.kt':'package com.vexora.app\nimport com.vexora.core.Value', 'core/src/test/java/com/vexora/core/Test.kt':'package com.vexora.core\nval fixtures="../app/src/main/assets"','app/src/main/res/values/strings.xml':'<resources><string name="app_name">Old</string></resources>','settings.gradle.kts':'rootProject.name = "Vexora"\ninclude(":app")','config/app.properties':'app.namespace=com.vexora.app\ncore.namespace=com.vexora.core\n','app/build.gradle.kts':'val dev="yumo.achat.app"'}.items():
            path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text)
        custom=answers();custom['project_name']="Orbit's Demo"
        s.customize(root,custom)
        self.assertTrue((root/'mobile/src/main/java/com/example/orbit/Test.kt').is_file())
        self.assertIn('com.example.foundation',(root/'mobile/src/main/java/com/example/orbit/Test.kt').read_text())
        self.assertIn('yumo.achat.app',(root/'mobile/build.gradle.kts').read_text())
        self.assertIn('../mobile/',(root/'core/src/test/java/com/example/foundation/Test.kt').read_text())
        self.assertIn("Orbit\\'s Demo",(root/'mobile/src/main/res/values/strings.xml').read_text())
    def test_namespace_paths_do_not_cascade_or_overwrite(self):
        for label,app,core in [('nested','com.vexora.core.host','com.example.foundation'),('swap','com.vexora.core','com.vexora.app')]:
            with self.subTest(label=label):
                root=self.root/label
                files={'settings.gradle.kts':'rootProject.name = "Vexora"','config/app.properties':'app.namespace=com.vexora.app\ncore.namespace=com.vexora.core', 'app/src/main/res/values/strings.xml':'<resources><string name="app_name">Old</string></resources>', 'app/src/main/java/com/vexora/app/Same.kt':'package com.vexora.app\n// host', 'app/src/main/java/com/vexora/core/Same.kt':'package com.vexora.core\n// shared'}
                for name,text in files.items():
                    file=root/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(text)
                custom=answers();custom.update(app_namespace=app,core_namespace=core)
                s.customize(root,custom)
                for namespace,marker in [(app,'host'),(core,'shared')]:
                    text=(root/'mobile/src/main/java'/namespace.replace('.','/')/'Same.kt').read_text()
                    self.assertIn('package '+namespace,text);self.assertIn('// '+marker,text)
    def test_existing_mode_prepares_without_overwriting_build(self):
        target=self.root/'existing';target.mkdir();(target/'settings.gradle.kts').write_text('user settings');(target/'user.kt').write_text('user code');(target/'AGENTS.md').write_text('原有项目规则\n')
        def fake_init(kit,stage,values,*args):
            (stage/'docs/core-rebuild').mkdir(parents=True);(stage/'docs/core-rebuild/开始重构.md').write_text('fixture prompt')
            s.write_json(stage/s.PROJECT,values)
        with patch.object(s,'initialize',side_effect=fake_init),patch.object(s,'verify_kit'):
            s.prepare_existing(self.root,target,answers('existing'))
        self.assertEqual('user settings',(target/'settings.gradle.kts').read_text());self.assertEqual('user code',(target/'user.kt').read_text())
        self.assertIn('已有工程接入要求',(target/'docs/core-rebuild/开始重构.md').read_text())
        self.assertTrue((target/s.STATE/'prepared').is_dir())
        self.assertTrue((target/'AGENTS.md').read_text().startswith('原有项目规则\n'))
        self.assertIn(s.GIT_RULES,(target/'AGENTS.md').read_text())

class PackContractTest(unittest.TestCase):
    setUp = ScaffoldTest.setUp
    tearDown = ScaffoldTest.tearDown
    bundle = ScaffoldTest.bundle
    def test_pack_requires_real_model_plan_and_matching_verification(self):
        project=self.bundle()/'payload'
        source=project/'core/src/main/Value.kt';source.parent.mkdir(parents=True);source.write_text('data class Value(val id: String, val ORB_desc: String = "")')
        baseline=[{'id':'fixture:Value','category':'model','constructor_fields':[{'name':'id'}]}]
        s.write_json(project/s.KIT/'manifests/models.json',baseline)
        row={'source_id':'fixture:Value','target_type':'fixture.Value','target_file':'core/src/main/Value.kt','extension_files':['core/src/main/Value.kt'], 'original_fields':[{'name':'id'}], 'extras':[{'name':'ORB_desc','serial_name':'ORB_desc','strategy':'join','sources':['id'],'separator':'|','null_format':'','max_length':64,'legacy_default':''}], 'structure_frozen':True}
        row.update({k:'fixture explicit strategy' for k in ['serialization','comparison','copy','recovery','retry','sensitive_sources']})
        s.write_json(project/'docs/core-rebuild/model-plan.json',{'prefix':'ORB_','developer_confirmed':True,'models':[row]})
        (project/'docs/core-rebuild/refactor-review.md').write_text('fixture review: '+('API mapping; serialization; recovery; ' * 8))
        s.write_json(project/s.REPORT,{'passed':True,'refactored':True,'input_hash':s.tree_hash(s.public_files(project))})
        output=self.root/'packed';s.pack(project,output)
        self.assertEqual(s.public_files(project),s.bundle_files(output))
    def test_refactor_check_rejects_baseline_empty_plan(self):
        project=self.bundle()/'payload'
        s.write_json(project/s.KIT/'manifests/models.json',[{'id':'fixture','category':'model','constructor_fields':[]}])
        s.write_json(project/'docs/core-rebuild/model-plan.json',{'prefix':'ORB_','developer_confirmed':True,'models':[]})
        with self.assertRaises(AssertionError):s.refactor_check(project)
    def test_missing_execution_bit_rejected(self):
        bundle=self.bundle();p=bundle/'payload/core/value.kt';p.chmod(0o755)
        with self.assertRaises(s.ScaffoldError):s.bundle_files(bundle)
    def test_deploy_repairs_execution_mode_even_when_bytes_match(self):
        target=self.root/'target';bundle=self.bundle();s.apply_deployment(bundle,target,s.deployment_plan(bundle,target))
        p=target/'core/value.kt';p.chmod(0o755)
        plan=s.deployment_plan(bundle,target)
        self.assertIn('core/value.kt',[a['path'] for a in plan['actions']])
        s.apply_deployment(bundle,target,plan)
        self.assertEqual(0,p.stat().st_mode & 0o111)
    def test_newer_deployment_prevents_older_rollback(self):
        target=self.root/'target';one=self.bundle('one');first=s.apply_deployment(one,target,s.deployment_plan(one,target))
        two=self.bundle('two');s.apply_deployment(two,target,s.deployment_plan(two,target))
        txn=target/s.STATE/'transactions'/first
        with self.assertRaises(s.ScaffoldError):s.restore(target,txn,s.read_json(txn/'journal.json'))

if __name__=='__main__':unittest.main()
