"""Run Android integration tests against the checksum manifest APK, without rebuilding it.

Release candidates are promoted to the README download by copying identical validated bytes.

Requires Android platform-tools on PATH and an emulator/device with a writable adb home.
Reports actual stream sizes; a software or virtual camera does not measure phone optics/FPS.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'out/android-validation'
PACKAGE = 'com.prismtest.scope'


def adb(*arguments, timeout=120, check=True):
    return subprocess.run(['adb', *arguments], text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=timeout, check=check).stdout


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    published = json.loads((ROOT / 'downloads/latest-android.json').read_text())
    version = published['version_code']
    info = json.loads((ROOT / 'downloads' / published['build_info']).read_text())
    apk = ROOT / 'downloads' / published['apk']
    test_apk = ROOT / 'android/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk'
    report = {'status': 'running', 'version_code': version, 'apk_sha256': digest(apk),
              'test_apk_sha256': digest(test_apk), 'physical_camera_tested': False, 'runs': []}
    try:
        assert report['apk_sha256'] == info['apk_sha256'], 'Download APK hash mismatch'
        adb('wait-for-device', timeout=120)
        deadline = time.monotonic() + 180
        while adb('shell', 'getprop', 'sys.boot_completed', timeout=20).strip() != '1':
            if time.monotonic() >= deadline:
                raise RuntimeError('Android boot timed out')
            time.sleep(2)
        for command in [('wm', 'size', '720x1280'), ('wm', 'density', '280'),
                        ('settings', 'put', 'global', 'window_animation_scale', '0'),
                        ('settings', 'put', 'global', 'transition_animation_scale', '0'),
                        ('settings', 'put', 'global', 'animator_duration_scale', '0'),
                        ('settings', 'put', 'system', 'screen_off_timeout', '1800000'),
                        ('input', 'keyevent', '224'), ('wm', 'dismiss-keyguard')]:
            adb('shell', *command)
        adb('install', '-r', '-t', str(apk))
        adb('install', '-r', '-t', str(test_apk))
        installed_path = adb('shell', 'pm', 'path', PACKAGE).splitlines()[0].removeprefix('package:')
        installed_hash = adb('shell', 'sha256sum ' + shlex.quote(installed_path)).split()[0]
        assert installed_hash == report['apk_sha256'], 'Installed APK differs from the download'
        package_info = adb('shell', 'dumpsys', 'package', PACKAGE)
        assert re.search(rf'versionCode={version}\b', package_info), 'Wrong installed version'
        report['installed_apk_sha256'] = installed_hash
        for class_name, expected, permission in [('InspectionStorageTest', 7, 'revoke'),
                                                 ('CameraFocusTest', 2, 'grant'),
                                                 ('CameraQualityTest', 1, 'grant')]:
            adb('shell', 'pm', permission, PACKAGE, 'android.permission.CAMERA')
            output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                         PACKAGE + '.' + class_name,
                         PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner', timeout=300)
            (OUT / (class_name + '.log')).write_text(output)
            print(output, flush=True)
            passed = f'OK ({expected} test' in output and 'FAILURES!!!' not in output
            report['runs'].append({'class': class_name, 'expected_tests': expected, 'passed': passed})
            if not passed:
                raise RuntimeError(class_name + ' did not report the expected passing test count')
        sizes = adb('shell', 'logcat', '-d', '-s', 'PrismQualityTest:I', '*:S')
        (OUT / 'camera-streams.log').write_text(sizes)
        focus_log = adb('shell', 'logcat', '-d', '-s', 'PrismFocus:I', '*:S')
        (OUT / 'camera-focus.log').write_text(focus_log)
        report['camera_focus'] = focus_log.splitlines()
        report['camera_streams'] = re.findall(r'수신 \d+×\d+ · 저장 \d+×\d+ / 프리뷰 \d+×\d+ · 검사화면 \d+×\d+ · 분석 \d+fps', sizes)
        report['tests_passed'] = sum(run['expected_tests'] for run in report['runs'])
        report['status'] = 'passed'
        adb('pull', '/sdcard/Android/data/' + PACKAGE + '/files/result-viewer-test.png',
            str(OUT / 'result-viewer.png'), check=False)
    except BaseException as error:
        report.update(status='failed', error=str(error))
        raise
    finally:
        try:
            focus_log = adb('shell', 'logcat', '-d', '-s', 'PrismFocus:I', '*:S', timeout=15, check=False)
            (OUT / 'camera-focus.log').write_text(focus_log)
            report['camera_focus'] = focus_log.splitlines()
        except (subprocess.SubprocessError, OSError):
            pass
        if report.get('error'):
            diagnostics = [report['error']]
            if report.get('camera_focus'): diagnostics.append('\n'.join(report['camera_focus'][-6:]))
            for run in report['runs']:
                if not run['passed']:
                    diagnostics.append((OUT / (run['class'] + '.log')).read_text()[-6500:])
            try:
                diagnostics.append(adb('devices', '-l', timeout=15, check=False))
            except (subprocess.SubprocessError, OSError):
                pass
            emulator_log = OUT / 'emulator.log'
            if emulator_log.exists(): diagnostics.append(emulator_log.read_text(errors='replace')[-3500:])
            report['diagnostics'] = '\n'.join(diagnostics)
        (OUT / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        try:
            (OUT / 'android-crash.log').write_text(adb('shell', 'logcat', '-d', '-b', 'crash', timeout=20))
        except (subprocess.SubprocessError, OSError):
            pass
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
                summary.write('Published APK validation: **' + report['status'] + '**\n\n')
                summary.write('APK SHA-256: `' + report['apk_sha256'] + '`\n\n')
                summary.write('Passed Android tests: ' + str(report.get('tests_passed', 0)) + '\n')
                for run in report['runs']:
                    summary.write(f"\n- {run['class']}: {'passed' if run['passed'] else 'failed'} ({run['expected_tests']} tests)\n")
                    if not run['passed']:
                        log = OUT / (run['class'] + '.log')
                        summary.write('\n```text\n' + log.read_text()[-6500:] + '\n```\n')
                if report.get('error'):
                    summary.write('\nError: ' + report['error'] + '\n')
            message = (report.get('diagnostics') or
                       f"Passed {report.get('tests_passed', 0)} Android tests against APK SHA256 {report['apk_sha256']}\n" +
                       '\n'.join(report.get('camera_focus', [])[-6:]))
            message = message.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
            level = 'error' if report['status'] == 'failed' else 'notice'
            print(f'::{level} title=Published Android APK validation::{message}', flush=True)


if __name__ == '__main__':
    main()
