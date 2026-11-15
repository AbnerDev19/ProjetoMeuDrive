#!/usr/bin/env python3
"""Review and reversibly disable selected third-party apps on an authorized Android.

This optional PC helper needs ADB/USB debugging. It never uninstalls apps, resets a
phone, obtains root, or disables system packages. Creating a plan does not modify
the phone. Edit the plan's selected list before applying it.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

PACKAGE = re.compile(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+\Z')
PROTECTED = {
    'com.meudrive', 'com.tailscale.ipn', 'com.android.packageinstaller',
    'com.google.android.packageinstaller', 'com.samsung.android.packageinstaller',
    'com.android.permissioncontroller', 'com.google.android.permissioncontroller',
    'com.sec.android.app.launcher', 'com.android.launcher3',
}


class Adb:
    def __init__(self, serial, executable='adb'):
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}', serial):
            raise ValueError('Serial ADB inválido')
        self.serial = serial
        self.executable = executable

    def run(self, *args):
        try:
            result = subprocess.run([self.executable, '-s', self.serial, *args],
                                    capture_output=True, text=True, timeout=30, check=False)
        except FileNotFoundError:
            raise ValueError('Instale Android Platform-Tools e adicione adb ao PATH') from None
        if result.returncode:
            raise ValueError('ADB recusou a operação. Confira o serial, a conexão USB e a autorização na tela do celular.')
        return result.stdout.strip()

    def ensure(self):
        if self.run('get-state') != 'device':
            raise ValueError('O celular indicado não está conectado e autorizado')

    def packages(self, *flags):
        return {line[8:] for line in self.run('shell', 'pm', 'list', 'packages', '--user', '0', *flags).splitlines()
                if line.startswith('package:') and PACKAGE.fullmatch(line[8:])}

    def snapshot(self):
        self.ensure()
        user = self.packages('-3')
        systems = self.packages('-s')
        enabled = self.packages('-e')
        disabled = self.packages('-d')
        launcher = self.run('shell', 'cmd', 'package', 'resolve-activity', '--brief', '--user', '0',
                            '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
        keyboard = self.run('shell', 'settings', '--user', '0', 'get', 'secure', 'default_input_method')
        current = set()
        for output, label in ((launcher, 'launcher'), (keyboard, 'teclado')):
            components = [line.strip().split('/')[0] for line in output.splitlines() if '/' in line]
            valid = [p for p in components if PACKAGE.fullmatch(p)]
            if not valid:
                raise ValueError(f'Não foi possível identificar o {label} atual; nenhuma mudança será feita')
            current.update(valid)
        protected = systems | current | PROTECTED
        protected.update(p for p in user if p.startswith('com.meudrive.') or 'packageinstaller' in p)
        return {'user': user, 'enabled': enabled, 'disabled': disabled, 'protected': protected}


def write_state(path, data, create=False):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if create:
        # Never overwrite an older undo journal, even if interrupted previously.
        with path.open('x', encoding='utf-8') as stream:
            json.dump(data, stream, indent=2, ensure_ascii=False)
            stream.flush(); os.fsync(stream.fileno())
        return
    fd, temporary = tempfile.mkstemp(prefix=path.name + '.', suffix='.tmp', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            json.dump(data, stream, indent=2, ensure_ascii=False)
            stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)


def load(path):
    value = json.loads(Path(path).read_text('utf-8'))
    if not isinstance(value, dict) or value.get('version') != 1:
        raise ValueError('Arquivo de plano/estado inválido')
    return value


def validate_list(value):
    if not isinstance(value, list) or not all(isinstance(p, str) and PACKAGE.fullmatch(p) for p in value):
        raise ValueError('A seleção precisa ser uma lista de nomes de pacotes Android válidos')
    if len(value) != len(set(value)):
        raise ValueError('Há pacotes repetidos na seleção')
    return value


def plan(adb, destination):
    snap = adb.snapshot()
    candidates = sorted(snap['user'] & snap['enabled'] - snap['disabled'] - snap['protected'])
    data = {'version': 1, 'serial': adb.serial, 'selected': [], 'candidates': candidates,
            'instructions': 'Copie para selected somente os pacotes de candidates que você deseja desativar. Não altere o serial. Faça backup deste plano e do arquivo de estado.'}
    write_state(destination, data, create=True)
    return data


def apply(adb, plan_path, state_path, confirm=input):
    data = load(plan_path)
    if data.get('serial') != adb.serial:
        raise ValueError('O plano pertence a outro serial. Nenhuma mudança será feita')
    selected = validate_list(data.get('selected'))
    candidates = validate_list(data.get('candidates'))
    if not selected or not set(selected) <= set(candidates):
        raise ValueError('Edite selected com os pacotes escolhidos dentre candidates antes de aplicar')
    snap = adb.snapshot()
    allowed = snap['user'] & snap['enabled'] - snap['disabled'] - snap['protected']
    if not set(selected) <= allowed:
        raise ValueError('Seleção contém app protegido, de sistema, ausente ou já desativado; gere um novo plano')
    if Path(state_path).exists():
        raise ValueError('O arquivo de restauração já existe. Preserve-o e escolha outro --state')
    print('Serão desativados no celular ' + adb.serial + ':\n' + '\n'.join(selected))
    if confirm('Digite DESATIVAR para aplicar, ou Enter para cancelar: ') != 'DESATIVAR':
        return False
    state = {'version': 1, 'serial': adb.serial, 'selected': selected, 'enabled_before': selected,
             'disabled_by_helper': [], 'pending': None, 'restored': []}
    write_state(state_path, state, create=True)
    for package in selected:
        # Recheck current launcher/keyboard and enabled status before each mutation.
        current = adb.snapshot()
        if package not in current['user'] & current['enabled'] - current['disabled'] - current['protected']:
            raise ValueError(f'{package} mudou desde a revisão; restaure usando {state_path}')
        state['pending'] = package
        write_state(state_path, state)
        adb.run('shell', 'pm', 'disable-user', '--user', '0', package)
        if package not in adb.packages('-d'):
            raise ValueError(f'Não foi possível confirmar {package}. Preserve o estado para restauração')
        state['disabled_by_helper'].append(package)
        state['pending'] = None
        write_state(state_path, state)
    return True


def restore(adb, state_path):
    state = load(state_path)
    if state.get('serial') != adb.serial:
        raise ValueError('O estado pertence a outro serial')
    selected = validate_list(state.get('selected'))
    enabled_before = validate_list(state.get('enabled_before'))
    disabled = validate_list(state.get('disabled_by_helper'))
    restored = validate_list(state.get('restored'))
    pending = state.get('pending')
    if pending is not None and (not isinstance(pending, str) or not PACKAGE.fullmatch(pending)):
        raise ValueError('Journal pendente inválido')
    targeted = list(dict.fromkeys(disabled + ([pending] if pending else [])))
    if not set(targeted) <= set(selected) & set(enabled_before) or not set(restored) <= set(selected) & set(enabled_before):
        raise ValueError('Journal de restauração inconsistente')
    adb.ensure()
    for package in targeted:
        if package in restored: continue
        current = adb.snapshot()
        if package not in current['user'] or package in current['protected']:
            raise ValueError(f'{package} ausente ou agora protegido. O journal foi preservado')
        # A pending entry was enabled before the attempted disable. If interrupted
        # after ADB succeeded, this reconciles the intended undo without touching
        # packages that were disabled before this helper ran.
        if package in current['disabled']:
            adb.run('shell', 'pm', 'enable', '--user', '0', package)
            if package not in adb.packages('-e'):
                raise ValueError(f'Não foi possível confirmar a restauração de {package}')
        state['restored'].append(package)
        if state['pending'] == package: state['pending'] = None
        write_state(state_path, state)
    return state


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--serial', required=True, help='Serial do A06 autorizado por USB (consulte adb devices)')
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--apply', metavar='PLANO.json')
    group.add_argument('--restore', metavar='ESTADO.json')
    parser.add_argument('--plan', default='dedicated-plan.json', help='Plano criado por padrão; nenhuma alteração no celular')
    parser.add_argument('--state', help='Arquivo de restauração ao aplicar; não sobrescreve estado existente')
    args = parser.parse_args()
    try:
        adb = Adb(args.serial)
        if args.restore:
            restore(adb, args.restore); print('Restauração concluída. O journal foi preservado.')
        elif args.apply:
            state_path = args.state or str(Path(args.apply).with_name(Path(args.apply).stem + '-state.json'))
            changed = apply(adb, args.apply, state_path)
            print('Alterações concluídas. Guarde para restaurar: ' + state_path if changed else 'Cancelado sem alterações.')
        else:
            result = plan(adb, args.plan)
            print(f'Plano criado: {args.plan}. {len(result["candidates"])} candidatos, nenhum selecionado e nenhum desativado.')
    except (ValueError, OSError, json.JSONDecodeError, subprocess.TimeoutExpired) as error:
        parser.exit(1, f'Operação interrompida: {error}\nGuarde o arquivo de estado; use --restore para desfazer mudanças já feitas.\n')
    except KeyboardInterrupt:
        parser.exit(130, '\nInterrompido. Guarde o estado e use --restore para desfazer mudanças já feitas.\n')


if __name__ == '__main__': main()
