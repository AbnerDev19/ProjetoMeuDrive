import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('dedicate', Path(__file__).with_name('dedicate-device.py'))
dedicate = importlib.util.module_from_spec(spec); spec.loader.exec_module(dedicate)


class FakeAdb:
    serial = 'A06-TEST'
    def __init__(self):
        self.user = {'com.example.game', 'com.example.alreadyoff', 'com.meudrive', 'com.tailscale.ipn', 'com.example.keyboard', 'com.example.launcher'}
        self.enabled = self.user - {'com.example.alreadyoff'}
        self.disabled = {'com.example.alreadyoff'}
        self.protected = {'com.meudrive', 'com.tailscale.ipn', 'com.example.keyboard', 'com.example.launcher', 'com.android.settings'}
        self.operations = []
        self.interrupt_after_disable = False
    def ensure(self): pass
    def snapshot(self):
        return {'user': set(self.user), 'enabled': set(self.enabled), 'disabled': set(self.disabled), 'protected': set(self.protected)}
    def packages(self, flag):
        return set(self.disabled if flag == '-d' else self.enabled)
    def run(self, *args):
        self.operations.append(args)
        package = args[-1]
        if args[2] == 'disable-user':
            self.enabled.discard(package); self.disabled.add(package)
            if self.interrupt_after_disable:
                raise KeyboardInterrupt()
        elif args[2] == 'enable':
            self.disabled.discard(package); self.enabled.add(package)
        return ''


class DedicateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.plan_path = self.root/'plan.json'
        self.state_path = self.root/'state.json'
        self.adb = FakeAdb()
    def tearDown(self): self.temp.cleanup()
    def chosen_plan(self, packages):
        data = dedicate.plan(self.adb, self.plan_path)
        data['selected'] = packages
        self.plan_path.write_text(json.dumps(data))
        return data
    def test_plan_only_lists_safe_candidates_and_never_modifies_phone(self):
        data = dedicate.plan(self.adb, self.plan_path)
        self.assertEqual(data['selected'], [])
        self.assertEqual(data['candidates'], ['com.example.game'])
        self.assertEqual(self.adb.operations, [])
    def test_empty_selection_cancel_and_wrong_serial_do_not_mutate(self):
        self.chosen_plan([])
        with self.assertRaises(ValueError): dedicate.apply(self.adb, self.plan_path, self.state_path)
        data=json.loads(self.plan_path.read_text());data['selected']=['com.example.game'];self.plan_path.write_text(json.dumps(data))
        self.assertFalse(dedicate.apply(self.adb,self.plan_path,self.state_path,confirm=lambda _: 'no'))
        self.assertFalse(self.state_path.exists())
        data['serial']='OTHER';self.plan_path.write_text(json.dumps(data))
        with self.assertRaises(ValueError): dedicate.apply(self.adb,self.plan_path,self.state_path,confirm=lambda _:'DESATIVAR')
        self.assertEqual(self.adb.operations, [])
    def test_tampered_system_protected_or_pre_disabled_selection_is_rejected(self):
        for package in ('com.android.settings','com.meudrive','com.tailscale.ipn','com.example.keyboard','com.example.launcher','com.example.alreadyoff'):
            with self.subTest(package=package):
                data={'version':1,'serial':self.adb.serial,'selected':[package],'candidates':[package]}
                self.plan_path.write_text(json.dumps(data))
                with self.assertRaises(ValueError): dedicate.apply(self.adb,self.plan_path,self.state_path,confirm=lambda _:'DESATIVAR')
        self.assertEqual(self.adb.operations, [])
    def test_apply_and_restore_preserve_pre_disabled_apps_and_are_idempotent(self):
        self.chosen_plan(['com.example.game'])
        self.assertTrue(dedicate.apply(self.adb,self.plan_path,self.state_path,confirm=lambda _:'DESATIVAR'))
        state=dedicate.load(self.state_path)
        self.assertEqual(state['disabled_by_helper'],['com.example.game']);self.assertIsNone(state['pending'])
        self.assertIn('com.example.game',self.adb.disabled)
        dedicate.restore(self.adb,self.state_path);dedicate.restore(self.adb,self.state_path)
        self.assertIn('com.example.game',self.adb.enabled)
        self.assertIn('com.example.alreadyoff',self.adb.disabled)
        self.assertEqual([op[2] for op in self.adb.operations],['disable-user','enable'])
    def test_crash_journal_recovers_uncertain_disable_without_touching_others(self):
        self.chosen_plan(['com.example.game']);self.adb.interrupt_after_disable=True
        with self.assertRaises(KeyboardInterrupt): dedicate.apply(self.adb,self.plan_path,self.state_path,confirm=lambda _:'DESATIVAR')
        state=dedicate.load(self.state_path)
        self.assertEqual(state['disabled_by_helper'],[]);self.assertEqual(state['pending'],'com.example.game')
        self.adb.interrupt_after_disable=False
        dedicate.restore(self.adb,self.state_path);dedicate.restore(self.adb,self.state_path)
        self.assertIn('com.example.game',self.adb.enabled)
        self.assertIn('com.example.alreadyoff',self.adb.disabled)
    def test_restore_before_adb_started_is_a_noop_and_state_cannot_target_another_device(self):
        self.chosen_plan(['com.example.game'])
        state={'version':1,'serial':self.adb.serial,'selected':['com.example.game'],'enabled_before':['com.example.game'],'disabled_by_helper':[],'pending':'com.example.game','restored':[]}
        dedicate.write_state(self.state_path,state,create=True)
        dedicate.restore(self.adb,self.state_path);dedicate.restore(self.adb,self.state_path)
        self.assertEqual(self.adb.operations,[])
        state['serial']='other';self.state_path.write_text(json.dumps(state))
        with self.assertRaises(ValueError): dedicate.restore(self.adb,self.state_path)
    def test_existing_state_is_never_overwritten(self):
        self.chosen_plan(['com.example.game']);self.state_path.write_text('keep')
        with self.assertRaises(ValueError): dedicate.apply(self.adb,self.plan_path,self.state_path,confirm=lambda _:'DESATIVAR')
        self.assertEqual(self.state_path.read_text(),'keep');self.assertEqual(self.adb.operations,[])
    def test_snapshot_protects_resolved_launcher_keyboard_and_system_apps(self):
        outputs = ['device', 'package:com.example.game\npackage:com.example.keyboard\npackage:com.example.launcher',
                   'package:com.android.settings', 'package:com.example.game\npackage:com.example.keyboard\npackage:com.example.launcher',
                   '', 'priority=0\ncom.example.launcher/.Home', 'com.example.keyboard/.InputService']
        with patch.object(dedicate.subprocess, 'run', side_effect=[Mock(returncode=0, stdout=x) for x in outputs]) as run:
            snap = dedicate.Adb('A06-TEST').snapshot()
        self.assertTrue({'com.example.launcher', 'com.example.keyboard', 'com.android.settings', 'com.meudrive', 'com.tailscale.ipn'} <= snap['protected'])
        for call in run.call_args_list:
            self.assertEqual(call.args[0][:3], ['adb', '-s', 'A06-TEST'])
        self.assertIn('--user', run.call_args_list[1].args[0])

    def test_every_adb_command_selects_explicit_serial_and_unauthorized_is_rejected(self):
        with patch.object(dedicate.subprocess,'run',return_value=Mock(returncode=0,stdout='device')) as run:
            dedicate.Adb('A06-TEST').ensure()
            self.assertEqual(run.call_args.args[0],['adb','-s','A06-TEST','get-state'])
        with patch.object(dedicate.subprocess,'run',return_value=Mock(returncode=1,stdout='',stderr='unauthorized')):
            with self.assertRaises(ValueError): dedicate.Adb('A06-TEST').ensure()
        for serial in ('','-x','abc xyz','x; reboot'):
            with self.assertRaises(ValueError): dedicate.Adb(serial)

if __name__ == '__main__': unittest.main()
