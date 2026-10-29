"""Read public DEX constants and native payloads without executing an APK."""
import hashlib
import re
import struct
import zipfile


def dex_strings(apk):
    values = set()
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r'classes\d*\.dex', name):
                continue
            data = archive.read(name)
            assert data[:4] == b'dex\n', 'Unsupported DEX format'
            count, table = struct.unpack_from('<II', data, 56)
            assert table + count * 4 <= len(data), 'Invalid DEX string table'
            for i in range(count):
                offset = struct.unpack_from('<I', data, table + i * 4)[0]
                # UTF-16 length is ULEB128; configuration values are ASCII MUTF-8.
                while data[offset] & 128:
                    offset += 1
                offset += 1
                end = data.find(b'\0', offset)
                assert end >= 0, 'Unterminated DEX string'
                values.add(data[offset:end].decode('utf-8', errors='replace'))
    return values


def native_payload(apk):
    result = {}
    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None, 'APK ZIP integrity failure'
        for name in archive.namelist():
            if not name.startswith('lib/') or not name.endswith('.so'):
                continue
            data = archive.read(name)
            assert name.split('/')[1] == 'armeabi-v7a', 'Unexpected ABI: ' + name
            assert data[:5] == b'\x7fELF\x01' and int.from_bytes(data[18:20], 'little') == 40, 'Not an ARM32 ELF: ' + name
            result[name] = hashlib.sha256(data).hexdigest()
    assert result, 'Native payload absent'
    return result


def certificate_sha256(report):
    # New apksigner versions may include an SDK range in the signer label.
    matches = {value.lower() for value in re.findall(r'certificate SHA-256 digest:\s*([a-fA-F0-9]{64})', report)}
    assert len(matches) == 1, 'Expected exactly one verified signing certificate'
    return matches.pop()
