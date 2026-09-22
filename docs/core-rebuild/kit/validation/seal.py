#!/usr/bin/env python3
"""生成普通包校验和及压缩包，私密附件始终保持独立目录。"""
import argparse
from pathlib import Path
import shutil
import sys
sys.dont_write_bytecode = True
from common import sha,write_json
from verify import verify_kit


def seal(kit):
    manifest=kit/'manifests/package-files.json'
    write_json(manifest,{p.relative_to(kit).as_posix():sha(p) for p in sorted(kit.rglob('*')) if p.is_file() and p!=manifest})
    verify_kit(kit)
    archive=Path(shutil.make_archive(str(kit),'zip',root_dir=kit.parent,base_dir=kit.name))
    archive.with_suffix('.zip.sha256').write_text(sha(archive)+'  '+archive.name+'\n')
    print('普通包压缩与校验和已生成；不包含私密签名附件。')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--kit',type=Path,default=Path(__file__).resolve().parents[1]);a=p.parse_args();seal(a.kit.resolve())
