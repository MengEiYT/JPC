#!/usr/bin/env python3
"""Writes a minimal bootable ISO 9660 (El Torito, 1.44 MB floppy emulation) around a floppy image.
Only for testing the CD-ROM boot path; no ISO tools are installed in this workspace.
Usage: make_eltorito_iso.py FLOPPY_IMG OUT_ISO"""
import struct
import sys

floppy = open(sys.argv[1], "rb").read()
assert len(floppy) == 1474560, "needs a 1.44 MB floppy image"
SEC = 2048


def both32(v):
    return struct.pack("<I", v) + struct.pack(">I", v)


def both16(v):
    return struct.pack("<H", v) + struct.pack(">H", v)


def dirrec(lba, length, flags, ident):
    idb = ident
    rec = struct.pack("<BB", 0, 0) + both32(lba) + both32(length)
    rec += bytes([126, 10, 21, 0, 0, 0, 0])             # 2026-10-21 00:00:00 UTC
    rec += struct.pack("<BBB", flags, 0, 0) + both16(1)
    rec += struct.pack("<B", len(idb)) + idb
    if len(rec) % 2:
        rec += b"\0"
    return struct.pack("<B", len(rec)) + rec[1:]


LBA_PVD, LBA_BOOTREC, LBA_TERM = 16, 17, 18
LBA_PT_L, LBA_PT_M, LBA_ROOT, LBA_CAT, LBA_IMG = 19, 20, 21, 22, 23
img_sectors = (len(floppy) + SEC - 1) // SEC
total = LBA_IMG + img_sectors

iso = bytearray(total * SEC)


def put(lba, data):
    iso[lba * SEC: lba * SEC + len(data)] = data


# root directory: ".", "..", BOOT.IMG;1
root = dirrec(LBA_ROOT, SEC, 2, b"\x00") + dirrec(LBA_ROOT, SEC, 2, b"\x01") + \
       dirrec(LBA_IMG, len(floppy), 0, b"BOOT.IMG;1")
put(LBA_ROOT, root)

# path tables (one entry: the root)
pt_l = struct.pack("<BBIH", 1, 0, LBA_ROOT, 1) + b"\x00\x00"
pt_m = struct.pack(">BBIH", 1, 0, LBA_ROOT, 1) + b"\x00\x00"
put(LBA_PT_L, pt_l)
put(LBA_PT_M, pt_m)

# primary volume descriptor
pvd = bytearray(SEC)
pvd[0] = 1
pvd[1:6] = b"CD001"
pvd[6] = 1
pvd[8:40] = b"JPC TEST".ljust(32)
pvd[40:72] = b"JPC_TEST".ljust(32)
pvd[80:88] = both32(total)
pvd[120:124] = both16(1)
pvd[124:128] = both16(1)
pvd[128:132] = both16(SEC)
pvd[132:140] = both32(len(pt_l))
pvd[140:144] = struct.pack("<I", LBA_PT_L)
pvd[148:152] = struct.pack(">I", LBA_PT_M)
pvd[156:190] = dirrec(LBA_ROOT, SEC, 2, b"\x00")
for off, n in ((190, 128), (318, 128), (446, 128), (574, 128), (702, 37), (739, 37), (776, 37)):
    pvd[off:off + n] = b" " * n
pvd[813:830] = b"2026102100000000" + b"\0"
pvd[830:847] = b"2026102100000000" + b"\0"
pvd[847:864] = b"0000000000000000" + b"\0"
pvd[864:881] = b"2026102100000000" + b"\0"
pvd[881] = 1
put(LBA_PVD, pvd)

# El Torito boot record
br = bytearray(SEC)
br[0] = 0
br[1:6] = b"CD001"
br[6] = 1
br[7:39] = b"EL TORITO SPECIFICATION".ljust(32, b"\0")
br[71:75] = struct.pack("<I", LBA_CAT)
put(LBA_BOOTREC, br)

# terminator
term = bytearray(SEC)
term[0] = 255
term[1:6] = b"CD001"
term[6] = 1
put(LBA_TERM, term)

# boot catalog: validation entry + default entry
val = bytearray(32)
val[0] = 1          # header id
val[1] = 0          # platform: x86
val[30], val[31] = 0x55, 0xAA
words = sum(struct.unpack("<16H", bytes(val))) & 0xFFFF
struct.pack_into("<H", val, 28, (-words) & 0xFFFF)
entry = bytearray(32)
entry[0] = 0x88     # bootable
entry[1] = 2        # 1.44 MB floppy emulation
struct.pack_into("<H", entry, 6, 1)           # sector count
struct.pack_into("<I", entry, 8, LBA_IMG)     # load RBA
put(LBA_CAT, bytes(val) + bytes(entry))

put(LBA_IMG, floppy)
open(sys.argv[2], "wb").write(iso)
print("wrote", sys.argv[2], len(iso), "bytes,", total, "sectors")
