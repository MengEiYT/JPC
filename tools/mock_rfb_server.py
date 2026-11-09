#!/usr/bin/env python3
"""Tiny fake VNC (RFB 3.8) server used to test VncClient without QEMU.

Usage: mock_rfb_server.py PORT LOGFILE

Behaviour:
  - screen starts 64x48 with a colour gradient
  - key event      -> white 8x8 square drawn at (10,10), event logged
  - pointer mask 1 -> CopyRect of that square to (30,30)
  - pointer mask 4 -> screen resized to 32x24 (DesktopSize)
"""
import socket
import struct
import sys

port = int(sys.argv[1])
logpath = sys.argv[2]
W, H = 64, 48


def log(line):
    with open(logpath, "a") as f:
        f.write(line + "\n")


def recv_exact(conn, n):
    buf = b""
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("client closed")
        buf += chunk
    return buf


def gradient_rect(w, h):
    out = bytearray()
    for y in range(h):
        for x in range(w):
            out += bytes((128, min(255, y * 5), min(255, x * 4), 0))  # B, G, R, pad
    return bytes(out)


def rect_header(x, y, w, h, enc):
    return struct.pack(">HHHHi", x, y, w, h, enc)


def update(*rects):
    return struct.pack(">BxH", 0, len(rects)) + b"".join(rects)


srv = socket.socket()
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", port))
srv.listen(1)
log("LISTENING")

conn, _ = srv.accept()
try:
    conn.sendall(b"RFB 003.008\n")
    recv_exact(conn, 12)
    conn.sendall(bytes([1, 1]))            # one security type: None
    recv_exact(conn, 1)                    # client's choice
    conn.sendall(struct.pack(">I", 0))     # security OK
    recv_exact(conn, 1)                    # ClientInit
    pixfmt = struct.pack(">BBBBHHHBBB3x", 32, 24, 0, 1, 255, 255, 255, 16, 8, 0)
    name = b"mock"
    conn.sendall(struct.pack(">HH", W, H) + pixfmt + struct.pack(">I", len(name)) + name)
    log("HANDSHAKE_OK")

    sent_first = False
    while True:
        t = recv_exact(conn, 1)[0]
        if t == 0:                          # SetPixelFormat
            pf = recv_exact(conn, 19)
            log("PIXELFORMAT bpp=%d shifts=%d,%d,%d" % (pf[3], pf[13], pf[14], pf[15]))
        elif t == 2:                        # SetEncodings
            hdr = recv_exact(conn, 3)
            n = struct.unpack(">H", hdr[1:3])[0]
            encs = struct.unpack(">%di" % n, recv_exact(conn, 4 * n))
            log("ENCODINGS " + ",".join(str(e) for e in encs))
        elif t == 3:                        # FramebufferUpdateRequest
            recv_exact(conn, 9)
            if not sent_first:
                sent_first = True
                conn.sendall(update(rect_header(0, 0, W, H, 0) + gradient_rect(W, H)))
                log("SENT_FULL_FRAME")
        elif t == 4:                        # KeyEvent
            d = recv_exact(conn, 7)
            down = d[0]
            key = struct.unpack(">I", d[3:7])[0]
            log("KEY down=%d keysym=0x%X" % (down, key))
            if down:
                white = bytes((255, 255, 255, 0)) * 64
                conn.sendall(update(rect_header(10, 10, 8, 8, 0) + white))
        elif t == 5:                        # PointerEvent
            d = recv_exact(conn, 5)
            mask = d[0]
            x, y = struct.unpack(">HH", d[1:5])
            log("PTR mask=%d x=%d y=%d" % (mask, x, y))
            if mask == 1:
                conn.sendall(update(rect_header(30, 30, 8, 8, 1) + struct.pack(">HH", 10, 10)))
            elif mask == 4:
                conn.sendall(update(rect_header(0, 0, 32, 24, -223)))
        else:
            log("UNKNOWN_MSG %d" % t)
            break
except ConnectionError:
    log("CLIENT_CLOSED")
finally:
    conn.close()
    srv.close()
