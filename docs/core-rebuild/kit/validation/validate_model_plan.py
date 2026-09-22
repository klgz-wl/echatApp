#!/usr/bin/env python3
"""校验开发者确认后的Model计划；不生成字段，不代替实际Kotlin/协议测试。"""
import argparse
import json
import re
from pathlib import Path

IDENTIFIER = re.compile(r'[A-Za-z_][A-Za-z0-9_]*\Z')
SENSITIVE = re.compile(r'token|password|secret|credential|key|url|originaljson', re.I)
CHECKS = {'serialization', 'comparison', 'copy', 'recovery', 'retry', 'sensitive_sources'}
KEYWORDS = {'as','break','class','continue','do','else','false','for','fun','if','in','interface','is','null','object','package','return','super','this','throw','true','try','typealias','typeof','val','var','when','while','_'}


def validate(plan, baseline):
    prefix = plan.get('prefix', '')
    assert IDENTIFIER.fullmatch(prefix), '前缀必须先确认并符合保守Kotlin标识符规则'
    assert plan.get('developer_confirmed') is True, '必须由开发者确认参数后才能生成改造计划'
    expected = {x['id']: x for x in baseline if x['category'] == 'model'}
    schema = plan.get('schema', 1)
    assert schema in {1, 2}, '不支持的Model计划格式'
    rows = plan.get('models', [])
    ids = [row['source_id'] for row in rows]
    assert len(ids) == len(set(ids)), 'Model条目重复'
    targets = [row.get('target_type') for row in rows]
    assert all(targets) and len(targets) == len(set(targets)), '目标完整类名缺失或重复'
    if schema == 1:
        assert set(expected) <= set(ids), '基线Model存在遗漏'
    else:
        # 源模型与目标模型分开登记，允许多对一和一对多，不能借重命名丢失能力。
        mappings = plan.get('baseline_mapping', [])
        source_ids = [item.get('source_id') for item in mappings]
        assert len(source_ids) == len(set(source_ids)) and set(source_ids) == set(expected), '基线映射必须完整且不重复'
        for item in mappings:
            mapped = item.get('target_types', [])
            assert isinstance(mapped, list) and mapped and len(mapped) == len(set(mapped)) and set(mapped) <= set(targets), '基线映射必须指向已登记的实际目标Model'
            for key in ['reason', 'field_mapping']:
                assert isinstance(item.get(key), str) and item[key].strip(), '缺少拆合说明或原字段去向：'+key
        for row in rows:
            if row['source_id'] in expected:
                assert any(item['source_id'] == row['source_id'] and row['target_type'] in item['target_types'] for item in mappings), '目标Model与其基线映射不一致'
    for row in rows:
        source_id = row['source_id']
        assert source_id in expected or source_id.startswith('new:'), '新增Model使用new:完整类名记录'
        original = row.get('original_fields', [])
        names = {x['name'] for x in original}
        serials = {x.get('serial_name', x['name']) for x in original}
        if schema == 1 and source_id in expected:
            assert {x['name'] for x in expected[source_id]['constructor_fields']} <= names, '原字段未完整登记'
        extras = row.get('extras', [])
        assert 1 <= len(extras) <= 5, '每Model需要1–5个新增字段'
        seen_names, seen_serials = set(names), set(serials)
        for extra in extras:
            name, serial = extra['name'], extra['serial_name']
            assert IDENTIFIER.fullmatch(name) and name.startswith(prefix), '新增属性名不符合前缀'
            assert name not in KEYWORDS, '新增属性名不能是Kotlin保留字'
            assert IDENTIFIER.fullmatch(serial) and serial.startswith(prefix), '序列化名不符合前缀'
            assert name not in seen_names and serial not in seen_serials, '新增属性或序列化名冲突'
            seen_names.add(name); seen_serials.add(serial)
            assert extra.get('strategy') in {'join', 'random', 'default'}, '字段取值策略缺失'
            assert 1 <= extra.get('max_length', 0) <= 4096, '需给出有限字段长度'
            assert 'legacy_default' in extra, '缺少旧记录默认值'
            if extra['strategy'] == 'join':
                sources = extra.get('sources', [])
                assert sources and set(sources) <= names, 'join只能引用已登记的原字段'
                assert not any(SENSITIVE.search(x) for x in sources), '潜在敏感join源需要改为独立值'
                assert 'separator' in extra and 'null_format' in extra, 'join必须明确分隔与null规则'
        for name in CHECKS:
            assert isinstance(row.get(name), str) and row[name].strip(), '缺少逐模型说明：'+name
        assert row.get('structure_frozen') is True, '字段结构尚未冻结'
    return len(rows)

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--plan', type=Path, required=True)
    p.add_argument('--baseline', type=Path, default=Path(__file__).resolve().parents[1]/'manifests/models.json')
    args = p.parse_args()
    count = validate(json.loads(args.plan.read_text()), json.loads(args.baseline.read_text()))
    print(f'Model计划静态检查通过：{count}项；实际编码和业务语义仍需逐项测试。')
