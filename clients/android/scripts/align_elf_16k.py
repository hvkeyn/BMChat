"""Rewrite PT_LOAD alignment of an ELF shared library to 16 KB pages.

Android 16 maps native libraries with 16 KB pages. Each loadable segment must
use p_align >= 16384 and p_offset % p_align == p_vaddr % p_align. Existing
BMChat libnative-utils.so binaries were linked with 4 KB pages; this inserts
file padding so the virtual addresses can stay unchanged.
"""

import struct
import sys
from pathlib import Path

ALIGN = 16384
PT_LOAD = 1
ELF_MAGIC = b"\x7fELF"


def align_elf(data: bytearray) -> bytearray:
    if data[:4] != ELF_MAGIC:
        raise ValueError("not an ELF")
    elf_class = data[4]
    if data[5] != 1:
        raise ValueError("only little-endian ELF is supported")
    if elf_class == 1:
        return _align(data, elf32=True)
    if elf_class == 2:
        return _align(data, elf32=False)
    raise ValueError(f"unsupported ELF class {elf_class}")


def _align(data: bytearray, elf32: bool) -> bytearray:
    loads = [index for index, phdr in enumerate(_phdrs(data, elf32)) if phdr["type"] == PT_LOAD]
    for index in loads:
        phdr = _phdrs(data, elf32)[index]
        pad = (phdr["vaddr"] - phdr["offset"]) % ALIGN
        if pad:
            data = _insert(data, phdr["offset"], pad, elf32)
        _set_load_align(data, index, elf32)
    _verify(data, elf32)
    return data


def _insert(data: bytearray, at: int, pad: int, elf32: bool) -> bytearray:
    data = data[:at] + bytearray(pad) + data[at:]
    if elf32:
        e_phoff = _u32(data, 28)
        e_shoff = _u32(data, 32)
        if e_phoff >= at:
            _p32(data, 28, e_phoff + pad)
        if e_shoff >= at:
            _p32(data, 32, e_shoff + pad)
        phoff, phentsize, phnum, shoff, shentsize, shnum = _tables32(data)
        for i in range(phnum):
            off = phoff + i * phentsize
            p_offset = _u32(data, off + 4)
            if p_offset >= at:
                _p32(data, off + 4, p_offset + pad)
        for i in range(shnum):
            off = shoff + i * shentsize
            sh_offset = _u32(data, off + 16)
            if sh_offset >= at:
                _p32(data, off + 16, sh_offset + pad)
    else:
        e_phoff = _u64(data, 32)
        e_shoff = _u64(data, 40)
        if e_phoff >= at:
            _p64(data, 32, e_phoff + pad)
        if e_shoff >= at:
            _p64(data, 40, e_shoff + pad)
        phoff, phentsize, phnum, shoff, shentsize, shnum = _tables64(data)
        for i in range(phnum):
            off = phoff + i * phentsize
            p_offset = _u64(data, off + 8)
            if p_offset >= at:
                _p64(data, off + 8, p_offset + pad)
        for i in range(shnum):
            off = shoff + i * shentsize
            sh_offset = _u64(data, off + 24)
            if sh_offset >= at:
                _p64(data, off + 24, sh_offset + pad)
    return data


def _set_load_align(data: bytearray, index: int, elf32: bool) -> None:
    if elf32:
        phoff, phentsize, _, _, _, _ = _tables32(data)
        off = phoff + index * phentsize + 28
        _p32(data, off, max(_u32(data, off), ALIGN))
    else:
        phoff, phentsize, _, _, _, _ = _tables64(data)
        off = phoff + index * phentsize + 48
        _p64(data, off, max(_u64(data, off), ALIGN))


def _verify(data: bytearray, elf32: bool) -> None:
    for phdr in _phdrs(data, elf32):
        if phdr["type"] != PT_LOAD:
            continue
        if phdr["align"] < ALIGN:
            raise ValueError(f"p_align still {phdr['align']}")
        if phdr["offset"] % phdr["align"] != phdr["vaddr"] % phdr["align"]:
            raise ValueError(
                f"congruence failed off={phdr['offset']:#x} vaddr={phdr['vaddr']:#x}"
            )
        if phdr["offset"] + phdr["filesz"] > len(data):
            raise ValueError("segment extends past end of file")
    shoff = _u32(data, 32) if elf32 else _u64(data, 40)
    shentsize = _u16(data, 46 if elf32 else 58)
    shnum = _u16(data, 48 if elf32 else 60)
    end = shoff + shentsize * shnum
    if shoff and end > len(data):
        raise ValueError("section headers extend past end of file")


def _phdrs(data: bytearray, elf32: bool):
    headers = []
    if elf32:
        phoff, phentsize, phnum, _, _, _ = _tables32(data)
        for i in range(phnum):
            off = phoff + i * phentsize
            headers.append(
                {
                    "type": _u32(data, off),
                    "offset": _u32(data, off + 4),
                    "vaddr": _u32(data, off + 8),
                    "filesz": _u32(data, off + 16),
                    "align": _u32(data, off + 28),
                }
            )
    else:
        phoff, phentsize, phnum, _, _, _ = _tables64(data)
        for i in range(phnum):
            off = phoff + i * phentsize
            headers.append(
                {
                    "type": _u32(data, off),
                    "offset": _u64(data, off + 8),
                    "vaddr": _u64(data, off + 16),
                    "filesz": _u64(data, off + 32),
                    "align": _u64(data, off + 48),
                }
            )
    return headers


def _tables32(data):
    return (
        _u32(data, 28),
        _u16(data, 42),
        _u16(data, 44),
        _u32(data, 32),
        _u16(data, 46),
        _u16(data, 48),
    )


def _tables64(data):
    return (
        _u64(data, 32),
        _u16(data, 54),
        _u16(data, 56),
        _u64(data, 40),
        _u16(data, 58),
        _u16(data, 60),
    )


def _u16(data, off):
    return struct.unpack_from("<H", data, off)[0]


def _u32(data, off):
    return struct.unpack_from("<I", data, off)[0]


def _u64(data, off):
    return struct.unpack_from("<Q", data, off)[0]


def _p32(data, off, value):
    struct.pack_into("<I", data, off, value)


def _p64(data, off, value):
    struct.pack_into("<Q", data, off, value)


def main(paths):
    for raw in paths:
        path = Path(raw)
        original = path.read_bytes()
        aligned = align_elf(bytearray(original))
        path.write_bytes(aligned)
        print(f"{path}: {len(original)} -> {len(aligned)}")


if __name__ == "__main__":
    main(sys.argv[1:])
