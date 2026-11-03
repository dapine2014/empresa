#!/usr/bin/env python3
"""Captura de diseño (spec 2026-11-01 §4 y §9): PNG y cajas de cada pantalla, en teléfono y escritorio.

Solo biblioteca estándar (el sandbox no tiene red ni pip): habla con Chrome sin pantalla por el protocolo DevTools
(websocket propio, mínimo) y decodifica el PNG con zlib para el color dominante de cada caja.

  capture.py mockups <dir-maquetas> <dir-salida>
      abre cada <PANT-NN>.html en file:// (las fuentes ya están en <dir-maquetas>/fonts, las copia run.sh);
      cajas = elementos con data-id.
  capture.py app <build/web> <dir-maquetas> <dir-salida>
      sirve build/web en 127.0.0.1 y abre /?preview=<PANT-NN> por cada maqueta;
      cajas = elementos con flt-semantics-identifier (Semantics(identifier:) en Flutter web).

Salida: <pantalla>-<phone|desktop>.png y .json con [{id, x, y, w, h, color}] (color = fondo dominante, #RRGGBB;
null si la caja queda fuera de la pantalla). Sale con 1 si no hay pantallas, si Chrome no arranca o si no se pudo
capturar ninguna; una pantalla que falla sola se reporta y no se escribe (Java la ve como faltante).
"""
import base64
import collections
import functools
import http.server
import json
import math
import os
import pathlib
import re
import socket
import struct
import subprocess
import sys
import threading
import time
import zlib

SIZES = (("phone", 390, 844), ("desktop", 1280, 800))
SCREEN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]*$")
PROFILE_DIR = "/tmp/forjai-capture-chrome"
MAX_SAMPLES = 20000


class Cdp:
    """Cliente websocket mínimo (RFC 6455) para el endpoint del navegador, en modo flatten (sessionId)."""

    def __init__(self, port, path):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=60)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((f"GET {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nUpgrade: websocket\r\n"
                           f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n")
                          .encode())
        self.buf = bytearray()
        while b"\r\n\r\n" not in self.buf:
            self._fill()
        end = self.buf.index(b"\r\n\r\n")
        status = bytes(self.buf[:end]).split(b"\r\n")[0]
        del self.buf[:end + 4]
        if b" 101 " not in status + b" ":
            raise RuntimeError(f"DevTools rechazó el websocket: {status!r}")
        self.next_id = 0

    def _fill(self):
        chunk = self.sock.recv(1 << 16)
        if not chunk:
            raise RuntimeError("DevTools cerró la conexión")
        self.buf += chunk

    def _read(self, n):
        while len(self.buf) < n:
            self._fill()
        out = bytes(self.buf[:n])
        del self.buf[:n]
        return out

    def _send_frame(self, opcode, payload):
        header = bytearray([0x80 | opcode])
        n = len(payload)
        if n < 126:
            header.append(0x80 | n)
        elif n < 1 << 16:
            header.append(0x80 | 126)
            header += struct.pack(">H", n)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", n)
        mask = os.urandom(4)
        repeated = (mask * (n // 4 + 1))[:n]
        masked = (int.from_bytes(payload, "big") ^ int.from_bytes(repeated, "big")).to_bytes(n, "big") if n else b""
        self.sock.sendall(bytes(header) + mask + masked)

    def _recv_message(self):
        parts = []
        while True:
            b0, b1 = self._read(2)
            opcode, n = b0 & 0x0F, b1 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._read(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._read(8))[0]
            mask = self._read(4) if b1 & 0x80 else None
            payload = self._read(n)
            if mask:
                payload = bytes(c ^ mask[i % 4] for i, c in enumerate(payload))
            if opcode == 0x8:
                raise RuntimeError("DevTools cerró el websocket")
            if opcode == 0x9:
                self._send_frame(0xA, payload)
                continue
            if opcode == 0xA:
                continue
            parts.append(payload)
            if b0 & 0x80:
                return json.loads(b"".join(parts))

    def call(self, method, params=None, session=None):
        self.next_id += 1
        msg = {"id": self.next_id, "method": method, "params": params or {}}
        if session:
            msg["sessionId"] = session
        self._send_frame(0x1, json.dumps(msg).encode())
        while True:
            reply = self._recv_message()
            if reply.get("id") == self.next_id:
                if "error" in reply:
                    raise RuntimeError(f"{method}: {reply['error'].get('message')}")
                return reply.get("result", {})


def start_chrome():
    log = open("/tmp/chrome-capture.log", "wb")
    proc = subprocess.Popen([
        "google-chrome", "--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--no-first-run",
        "--no-default-browser-check", "--disable-extensions", "--hide-scrollbars", "--force-device-scale-factor=1",
        "--allow-file-access-from-files",
        # Sin GPU: WebGL por software para CanvasKit (Flutter web).
        "--use-angle=swiftshader", "--enable-unsafe-swiftshader",
        "--remote-debugging-port=0", f"--user-data-dir={PROFILE_DIR}", "--window-size=1280,800", "about:blank",
    ], stdout=log, stderr=log)
    active = pathlib.Path(PROFILE_DIR, "DevToolsActivePort")
    deadline = time.time() + 30
    while time.time() < deadline:
        if active.exists():
            lines = active.read_text().split()
            if len(lines) >= 2:
                return proc, Cdp(int(lines[0]), lines[1])
        if proc.poll() is not None:
            break
        time.sleep(0.2)
    proc.kill()
    raise RuntimeError("Chrome no abrió el puerto de DevTools (ver /tmp/chrome-capture.log)")


def evaluate(cdp, session, expression, await_promise=False):
    result = cdp.call("Runtime.evaluate", {"expression": expression, "returnByValue": True,
                                           "awaitPromise": await_promise}, session)
    if "exceptionDetails" in result:
        raise RuntimeError(f"error de JS: {result['exceptionDetails'].get('text')}")
    return result.get("result", {}).get("value")


def wait_for(cdp, session, expression, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if evaluate(cdp, session, expression):
            return True
        time.sleep(0.25)
    return False


def boxes_script(attr):
    return ("Array.from(document.querySelectorAll('[%s]')).map(e => { const r = e.getBoundingClientRect();"
            " return {id: e.getAttribute('%s'), x: Math.round(r.left), y: Math.round(r.top),"
            " w: Math.round(r.width), h: Math.round(r.height)}; })" % (attr, attr))


def settle_mockup(cdp, session):
    wait_for(cdp, session, "document.readyState === 'complete'", 30)
    evaluate(cdp, session, "document.fonts.ready.then(() => true)", await_promise=True)
    # La app Flutter ocupa la ventana de borde a borde; sin esto el margen de 8 px que el navegador le pone al body
    # corre toda la maqueta (prueba en vivo 3: 1264 px de ancho en vez de 1280).
    evaluate(cdp, session, "(() => { const s = document.createElement('style');"
                           " s.textContent = 'html, body { margin: 0; }'; document.head.appendChild(s); return true; })()")
    time.sleep(0.3)


def settle_app(cdp, session):
    wait_for(cdp, session, "document.readyState === 'complete'", 30)
    if not wait_for(cdp, session, "!!document.querySelector('flutter-view, flt-glass-pane')", 60):
        raise RuntimeError("la app Flutter no apareció")
    # La app llama a ensureSemantics(); si no, el placeholder de accesibilidad las activa con un click.
    evaluate(cdp, session, "(() => { const p = document.querySelector('flt-semantics-placeholder');"
                           " if (p) p.click(); return true; })()")
    count, stable, deadline = -1, 0, time.time() + 20
    while time.time() < deadline and stable < 3:
        now = evaluate(cdp, session, "document.querySelectorAll('[flt-semantics-identifier]').length")
        stable = stable + 1 if now == count and now > 0 else 0
        count = now
        time.sleep(0.5)
    time.sleep(1.0)


def capture_one(cdp, url, width, height, attr, settle):
    target = cdp.call("Target.createTarget", {"url": "about:blank"})["targetId"]
    try:
        session = cdp.call("Target.attachToTarget", {"targetId": target, "flatten": True})["sessionId"]
        cdp.call("Emulation.setDeviceMetricsOverride",
                 {"width": width, "height": height, "deviceScaleFactor": 1, "mobile": False}, session)
        cdp.call("Page.enable", {}, session)
        nav = cdp.call("Page.navigate", {"url": url}, session)
        if nav.get("errorText"):
            raise RuntimeError(f"no se pudo abrir {url}: {nav['errorText']}")
        settle(cdp, session)
        boxes = evaluate(cdp, session, boxes_script(attr)) or []
        png = base64.b64decode(cdp.call("Page.captureScreenshot", {"format": "png"}, session)["data"])
        return png, boxes
    finally:
        cdp.call("Target.closeTarget", {"targetId": target})


def decode_png(data):
    """PNG de 8 bits RGB/RGBA sin entrelazado (lo que escribe Chrome): (ancho, alto, bytes por pixel, filas)."""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("no es un PNG")
    pos, idat, header = 8, [], None
    while pos < len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            idat.append(body)
        elif kind == b"IEND":
            break
    width, height, depth, color_type, _, _, interlace = header
    if depth != 8 or interlace != 0 or color_type not in (2, 6):
        raise ValueError(f"PNG no soportado (bits={depth}, tipo={color_type}, entrelazado={interlace})")
    bpp = 3 if color_type == 2 else 4
    raw = zlib.decompress(b"".join(idat))
    stride = width * bpp
    rows, prev, i = [], bytearray(stride), 0
    for _ in range(height):
        kind, line = raw[i], bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        if kind == 1:
            for x in range(bpp, stride):
                line[x] = (line[x] + line[x - bpp]) & 0xFF
        elif kind == 2:
            line = bytearray((a + b) & 0xFF for a, b in zip(line, prev))
        elif kind == 3:
            for x in range(stride):
                left = line[x - bpp] if x >= bpp else 0
                line[x] = (line[x] + ((left + prev[x]) >> 1)) & 0xFF
        elif kind == 4:
            for x in range(stride):
                a = line[x - bpp] if x >= bpp else 0
                b = prev[x]
                c = prev[x - bpp] if x >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 0xFF
        rows.append(line)
        prev = line
    return width, height, bpp, rows


def dominant_color(image, box):
    width, height, bpp, rows = image
    x0, y0 = max(0, box["x"]), max(0, box["y"])
    x1, y1 = min(width, box["x"] + box["w"]), min(height, box["y"] + box["h"])
    if x1 <= x0 or y1 <= y0:
        return None
    step = max(1, int(math.sqrt((x1 - x0) * (y1 - y0) / MAX_SAMPLES)))
    counts = collections.Counter()
    for y in range(y0, y1, step):
        row = rows[y]
        for x in range(x0, x1, step):
            o = x * bpp
            counts[(row[o], row[o + 1], row[o + 2])] += 1
    r, g, b = counts.most_common(1)[0][0]
    return f"#{r:02X}{g:02X}{b:02X}"


def screens(mockups):
    return sorted(p.stem for p in pathlib.Path(mockups).glob("*.html") if SCREEN.match(p.stem))


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


def serve(directory):
    handler = functools.partial(QuietHandler, directory=directory)
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def main(argv):
    if len(argv) == 4 and argv[1] == "mockups":
        mockups, out = pathlib.Path(argv[2]), pathlib.Path(argv[3])
        attr, settle = "data-id", settle_mockup
        url_of = lambda screen: (mockups / f"{screen}.html").resolve().as_uri()
    elif len(argv) == 5 and argv[1] == "app":
        mockups, out = pathlib.Path(argv[3]), pathlib.Path(argv[4])
        attr, settle = "flt-semantics-identifier", settle_app
        server = serve(argv[2])
        port = server.server_address[1]
        url_of = lambda screen: f"http://127.0.0.1:{port}/?preview={screen}"
    else:
        print(__doc__)
        return 2

    names = screens(mockups)
    if not names:
        print(f"No hay maquetas en {mockups}")
        return 1
    out.mkdir(parents=True, exist_ok=True)
    proc, cdp = start_chrome()
    done = 0
    try:
        for screen in names:
            for size, width, height in SIZES:
                started = time.time()
                try:
                    png, boxes = capture_one(cdp, url_of(screen), width, height, attr, settle)
                    image = decode_png(png)
                    for box in boxes:
                        box["color"] = dominant_color(image, box)
                    (out / f"{screen}-{size}.png").write_bytes(png)
                    (out / f"{screen}-{size}.json").write_text(json.dumps(boxes, ensure_ascii=False))
                    done += 1
                    print(f"{screen}-{size}: {len(boxes)} cajas ({time.time() - started:.1f} s)")
                except Exception as ex:  # una pantalla que falla no corta las demás
                    print(f"{screen}-{size}: FALLÓ: {ex}")
    finally:
        proc.kill()
    print(f"== capturas: {done} de {len(names) * len(SIZES)}")
    return 0 if done else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
