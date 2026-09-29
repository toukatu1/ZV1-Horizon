"""Static gate only; this does not prove stabilization on an Android device."""
import hashlib
import struct
import sys
from pathlib import Path

p=Path(sys.argv[1])
data=p.read_bytes()
assert data[:6] == b'\x7fELF\x02\x01', 'Expected 64-bit little-endian ELF'
assert struct.unpack_from('<H',data,18)[0] == 183, 'Expected AArch64'
for marker in [b'horizon=0%',b'smoothness=']:
    assert marker in data, 'Missing v15 marker: '+repr(marker)
print('PASS v15 native markers, AArch64 ELF:',hashlib.sha256(data).hexdigest())
