"""交付规则的负向测试，使用小型夹具，不使用真实配置值。"""
import copy
import unittest
from validate_model_plan import validate

class ModelPlanTest(unittest.TestCase):
    def setUp(self):
        self.baseline = [{'id': 'fixture:User', 'category': 'model', 'constructor_fields': [{'name': 'id'}, {'name': 'token'}]}]
        self.plan = {'prefix': 'TEST_', 'developer_confirmed': True, 'models': [{
            'source_id': 'fixture:User', 'target_type': 'fixture.User',
            'original_fields': [{'name': 'id'}, {'name': 'token'}],
            'extras': [{'name': 'TEST_desc', 'serial_name': 'TEST_desc', 'strategy': 'join', 'sources': ['id'], 'separator': '|', 'null_format': '', 'max_length': 64, 'legacy_default': ''}],
            **{key: 'fixture-explicit-policy' for key in ['serialization', 'comparison', 'copy', 'recovery', 'retry', 'sensitive_sources']}, 'structure_frozen': True}]}
    def rejects(self, change):
        plan = copy.deepcopy(self.plan); change(plan)
        with self.assertRaises(AssertionError): validate(plan, self.baseline)
    def test_valid_plan(self): self.assertEqual(1, validate(self.plan, self.baseline))
    def test_developer_confirmation_required(self): self.rejects(lambda p: p.update(developer_confirmed=False))
    def test_missing_prefix(self): self.rejects(lambda p: p.update(prefix=''))
    def test_missing_model(self): self.rejects(lambda p: p.update(models=[]))
    def test_extra_count(self): self.rejects(lambda p: p['models'][0].update(extras=[]))
    def test_duplicate_fields(self): self.rejects(lambda p: p['models'][0]['extras'].append(copy.deepcopy(p['models'][0]['extras'][0])))
    def test_sensitive_source(self): self.rejects(lambda p: p['models'][0]['extras'][0].update(sources=['token']))
    def test_extra_chaining(self): self.rejects(lambda p: p['models'][0]['extras'][0].update(sources=['TEST_desc']))
    def test_unfrozen_structure(self): self.rejects(lambda p: p['models'][0].update(structure_frozen=False))
    def test_original_serial_collision(self):
        self.rejects(lambda p: p['models'][0]['original_fields'][0].update(serial_name='TEST_desc'))
    def test_legacy_default_required(self): self.rejects(lambda p: p['models'][0]['extras'][0].pop('legacy_default'))
    def schema_two(self):
        self.plan.update(schema=2,baseline_mapping=[{'source_id':'fixture:User','target_types':['fixture.User'],'reason':'保留值模型','field_mapping':'id与token保持原语义'}])
    def test_split_maps_to_two_real_models(self):
        self.schema_two()
        second=copy.deepcopy(self.plan['models'][0]);second.update(source_id='new:fixture.Details',target_type='fixture.Details')
        self.plan['models'].append(second)
        self.plan['baseline_mapping'][0]['target_types'].append('fixture.Details')
        self.assertEqual(2,validate(self.plan,self.baseline))
    def test_merge_keeps_one_extension_set_and_all_sources(self):
        self.schema_two()
        self.baseline.append({'id':'fixture:Account','category':'model','constructor_fields':[{'name':'name'}]})
        self.plan['baseline_mapping'].append({'source_id':'fixture:Account','target_types':['fixture.User'],'reason':'账号值归并','field_mapping':'name映射到合并对象name'})
        self.plan['models'][0]['original_fields'].append({'name':'name'})
        self.assertEqual(1,validate(self.plan,self.baseline))
    def test_mapping_cannot_drop_original_model(self):
        self.schema_two();self.rejects(lambda p:p.update(baseline_mapping=[]))
    def test_mapping_cannot_point_to_absent_model(self):
        self.schema_two();self.rejects(lambda p:p['baseline_mapping'][0].update(target_types=['absent.Model']))
    def test_mapping_requires_original_field_destination(self):
        self.schema_two();self.rejects(lambda p:p['baseline_mapping'][0].update(field_mapping=''))
    def test_mapping_cannot_repeat_source(self):
        self.schema_two();self.rejects(lambda p:p['baseline_mapping'].append(copy.deepcopy(p['baseline_mapping'][0])))
    def test_unknown_plan_schema_rejected(self):self.rejects(lambda p:p.update(schema=99))

if __name__ == '__main__': unittest.main()
