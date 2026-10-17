#!/usr/bin/env python3
"""Connect AERA WLAN to the pinned Neo8 hardware loader, on demand only."""
from pathlib import Path
import re
import stat

HERE = Path(__file__).resolve().parent
ASSETS = {
    'system/bin/neo8-wifi-service.sh': 'neo8-wifi-service.sh',
    'system/etc/init/init.neo8-wifi.rc': 'init.neo8-wifi.rc',
}
FIRMWARE_DIRS = (
    '/tmp/aera-wifi/modem/image/', '/tmp/aera-wifi/modem/image/wcn7750/',
    '/tmp/aera-wifi/vendor/firmware/', '/tmp/aera-wifi/odm/firmware/',
    '/tmp/aera-wifi/odm/etc/wifi/',
)
BUILT_BINARIES = ('wpa_supplicant', 'wpa_cli', 'dhcptool')


def replace_once(source, old, new):
    if source.count(old) != 1:
        raise ValueError('Unexpected pinned Wi-Fi source: ' + old[:80])
    return source.replace(old, new, 1)


def make_loader(reference):
    source = reference['sbin/wifi-load-modules.sh']['data'].decode()
    begin = source.index('mount_partition() {')
    end = source.index('module_loaded() {')
    source = source[:begin] + (HERE / 'wifi/stock-mounts.sh').read_text() + '\n' + source[end:]
    source = replace_once(source, 'local module="${1%.ko}"',
        'local module="${1##*/}"\n    module="${module%.ko}"')
    source = replace_once(source, '    grep -q "^${module} " /proc/modules',
        '    # Some qcacld builds register as wlan despite the file basename.\n'
        '    if [ "$module" = qca_cld3_wcn7750 ]; then\n'
        '        grep -q "^wlan " /proc/modules && return 0\n'
        '    fi\n'
        '    grep -q "^${module} " /proc/modules')
    begin = source.index('find_module() {')
    end = source.index('dependency_line() {', begin)
    source = source[:begin] + '''find_module() {
    local requested="$1" relative base root path
    base="${requested##*/}"
    relative="${requested#*/lib/modules/}"
    # A modules.dep entry may use a nested relative path.
    case "$relative" in /*|../*|*/../*) relative="$base" ;; esac
    for root in /tmp/aera-wifi/vendor_dlkm/lib/modules \\
        /tmp/aera-wifi/system_dlkm/lib/modules /tmp/aera-wifi/vendor/lib/modules \\
        /tmp/aera-wifi/odm/lib/modules; do
        for path in "$root/$relative" "$root/$base"; do
            [ -f "$path" ] && { echo "$path"; return 0; }
        done
    done
    return 1
}

''' + source[end:]
    for name in ('vendor', 'vendor_dlkm', 'system_dlkm', 'odm', 'odm_dlkm'):
        source = source.replace(f'/{name}/lib/modules/', f'/tmp/aera-wifi/{name}/lib/modules/')
    # Match a complete basename, including modules.dep entries without '/'.
    source = replace_once(source, 'grep "/$base:" "$depfile"',
        'awk -v name="$base" \'{n=$1; sub(/^.*\\//,"",n); if(n==name ":") print}\' "$depfile"')
    source = replace_once(source, '[ -e "$SEEN_DIR/$base" ] && return 0',
        '[ -e "$SEEN_DIR/$base" ] && { echo "Module dependency cycle: $base"; return 1; }')
    source = replace_once(source, 'load_with_dependencies "$dep"\n',
        'load_with_dependencies "$dep" || return 1\n')
    # Mark only active recursion, so successful shared dependencies can recur.
    source = replace_once(source, '        echo "failed module: $path"\n        return 1\n    }\n}',
        '        echo "failed module: $path"\n        return 1\n    }\n    rm -f "$SEEN_DIR/$base"\n}')
    for module in ('cnss_plat_ipc_qmi_svc', 'icnss2', 'qca_cld3_wcn7750'):
        old = f'load_with_dependencies /tmp/aera-wifi/vendor/lib/modules/{module}.ko\n'
        source = replace_once(source, old, old.rstrip() + ' || exit 1\n')
    # The reference loader chmods Android's data directories; don't carry that
    # into AERA. Its supplicant uses an independent directory under /tmp.
    begin = source.index('    mkdir -p /data/vendor/wifi')
    end = source.index('    if ! /system/bin/toybox pidof android.hardware.wifi-service', begin)
    source = source[:begin] + source[end:]
    for binary in ('bin/cnss-daemon', 'bin/hw/cnss-daemon', 'bin/hw/android.hardware.wifi-service'):
        source = source.replace('/vendor/' + binary, '/tmp/aera-wifi/vendor/' + binary)
    source = source.replace('client=/sbin/neo8_wifi_hal_client', 'client=/system/bin/neo8_wifi_hal_client')
    source = replace_once(source, '    if [ ! -x "$client" ]; then',
        '    chmod 0755 "$client" || return 1\n    if [ ! -x "$client" ]; then')
    source = replace_once(source, 'LD_LIBRARY_PATH=/vendor/lib64:/system/lib64:/sbin/lib64',
        'LD_LIBRARY_PATH="$CNSS_LIBS"')
    aidl_path = 'LD_LIBRARY_PATH=/system/lib64:/system_ext/lib64:/vendor/lib64:/odm/lib64:/sbin/lib64'
    if source.count(aidl_path) != 2:
        raise ValueError('Expected separate AIDL HAL and client launches')
    source = source.replace(aidl_path, 'LD_LIBRARY_PATH="$WIFI_AIDL_LIBS"')
    source = replace_once(source, '\nstart_wifi_hal\n', '\nstart_wifi_hal || exit 1\n')
    return source


def expected_files(reference):
    files = {p: (HERE / 'wifi' / n).read_bytes() for p, n in ASSETS.items()}
    files['system/bin/neo8-wifi-loader.sh'] = make_loader(reference).encode()
    files['system/bin/neo8_wifi_hal_client'] = reference['sbin/neo8_wifi_hal_client']['data']
    return files


def configure_wifi(device, reference):
    device = Path(device)
    files = expected_files(reference)
    for path, data in files.items():
        target = device / 'prebuilt' / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        target.chmod(0o755 if path.startswith('system/bin/') else 0o644)
    ueventd = device / 'recovery/root/system/etc/ueventd.rc'
    source = ueventd.read_text()
    lines = source.splitlines()
    indexes = [i for i, line in enumerate(lines) if line.startswith('firmware_directories ')]
    if len(indexes) != 1:
        raise ValueError('Unexpected Neo8 firmware search configuration')
    lines[indexes[0]] += ' ' + ' '.join(FIRMWARE_DIRS)
    ueventd.write_text('\n'.join(lines) + '\n')
    # The newly built AERA supplicant owns its matching VINTF fragment.
    manifest = 'vendor/etc/vintf/manifest/android.hardware.wifi.supplicant.xml'
    (device / 'prebuilt' / manifest).unlink(missing_ok=True)
    mk = device / 'device.mk'
    source = replace_once(mk.read_text(),
        '$(DEVICE_PATH)/recovery.fstab:recovery/root/system/etc/recovery.fstab \\\n'
        '    $(DEVICE_PATH)/prebuilt/sbin/' + manifest + ':recovery/root/' + manifest,
        '$(DEVICE_PATH)/recovery.fstab:recovery/root/system/etc/recovery.fstab')
    mk.write_text(source)
    return {'files': sorted(files), 'stock_mount_root': '/tmp/aera-wifi',
            'firmware_directories': list(FIRMWARE_DIRS)}


def validate_wifi_image(entries, reference):
    expected = expected_files(reference)
    for path, data in expected.items():
        entry = entries.get(path)
        if not entry or not stat.S_ISREG(entry['mode']) or entry['data'] != data:
            raise ValueError('Missing or mismatched Wi-Fi file: ' + path)
    for name in BUILT_BINARIES:
        path = 'system/bin/' + name
        entry = entries.get(path)
        if (not entry or not stat.S_ISREG(entry['mode']) or not entry['mode'] & 0o111
                or not entry['data'].startswith(b'\x7fELF')):
            raise ValueError('Missing executable Wi-Fi ELF: ' + path)
    for path in FIRMWARE_DIRS:
        if path.encode() not in entries['system/etc/ueventd.rc']['data']:
            raise ValueError('Missing Wi-Fi firmware search path: ' + path)
    if b'WLAN: triggering supplicant bring-up' not in entries['system/bin/recovery']['data']:
        raise ValueError('Recovery ELF lacks compiled WLAN backend')
    services = sum(len(re.findall(rb'^service wpa_supplicant\s', e['data'], re.M))
        for p, e in entries.items() if p.endswith('.rc') and
        p.startswith(('system/etc/init/', 'vendor/etc/init/', 'vendor/odm/etc/init/', 'init.')))
    if services != 1:
        raise ValueError('Expected exactly one active wpa_supplicant service')
    return {'wlan_backend': True, 'on_demand_service': True, 'stock_mount_root': '/tmp/aera-wifi'}
