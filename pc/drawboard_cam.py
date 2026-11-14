"""
Drawboard-Kamera – PC-Teil (Windows)

Holt neue Fotos aus dem TUHH-Cloud-Ordner "DrawboardCam" ab und
  • legt sie IMMER in die Zwischenablage,
  • drückt zusätzlich Strg+V, aber NUR wenn Drawboard gerade das aktive Fenster ist.

Sicherheit für Drawboard:
  • Eigenständiges Programm, greift nie in den Drawboard-Prozess ein (keine DLLs, keine Hooks).
  • Läuft mit niedriger Priorität.
  • Fügt nur ein, wenn gerade keine Maus-/Stift-Taste und keine Strg/Shift/Alt/Win-Taste gedrückt ist.
  • Stürzt das Programm ab, passiert in Drawboard nichts.
"""

import base64
import ctypes
import ctypes.wintypes as wt
import email.utils
import io
import json
import logging
import os
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
import winreg
import xml.etree.ElementTree as ET
from logging.handlers import RotatingFileHandler

import pystray
from PIL import Image, ImageDraw, ImageOps

APP = "DrawboardCam"
TITLE = "Drawboard-Kamera"
SERVER = "https://cloud.tuhh.de"
FOLDER = "DrawboardCam"
UA = "DrawboardCam (Windows)"
STALE_SECONDS = 10 * 60          # ältere Fotos werden nicht mehr eingefügt (nur aus der Cloud entfernt)
POLL_DRAWBOARD_OPEN = 2.0        # Sekunden
POLL_DRAWBOARD_CLOSED = 4.0

CFG_DIR = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), APP)
CFG_FILE = os.path.join(CFG_DIR, "config.json")
os.makedirs(CFG_DIR, exist_ok=True)

log = logging.getLogger(APP)
log.setLevel(logging.INFO)
_h = RotatingFileHandler(os.path.join(CFG_DIR, "log.txt"), maxBytes=500_000, backupCount=1, encoding="utf-8")
_h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
log.addHandler(_h)

# ---------------------------------------------------------------- Windows-API

user32 = ctypes.WinDLL("user32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
crypt32 = ctypes.WinDLL("crypt32", use_last_error=True)

HGLOBAL = ctypes.c_void_p
WNDENUMPROC = ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)

user32.GetForegroundWindow.restype = wt.HWND
user32.GetWindowThreadProcessId.argtypes = [wt.HWND, ctypes.POINTER(wt.DWORD)]
user32.GetWindowThreadProcessId.restype = wt.DWORD
user32.EnumChildWindows.argtypes = [wt.HWND, WNDENUMPROC, wt.LPARAM]
user32.GetAsyncKeyState.argtypes = [ctypes.c_int]
user32.GetAsyncKeyState.restype = ctypes.c_short
user32.keybd_event.argtypes = [wt.BYTE, wt.BYTE, wt.DWORD, ctypes.c_size_t]
user32.OpenClipboard.argtypes = [wt.HWND]
user32.OpenClipboard.restype = wt.BOOL
user32.CloseClipboard.restype = wt.BOOL
user32.EmptyClipboard.restype = wt.BOOL
user32.SetClipboardData.argtypes = [wt.UINT, wt.HANDLE]
user32.SetClipboardData.restype = wt.HANDLE
user32.RegisterClipboardFormatW.argtypes = [wt.LPCWSTR]
user32.RegisterClipboardFormatW.restype = wt.UINT

kernel32.OpenProcess.argtypes = [wt.DWORD, wt.BOOL, wt.DWORD]
kernel32.OpenProcess.restype = wt.HANDLE
kernel32.QueryFullProcessImageNameW.argtypes = [wt.HANDLE, wt.DWORD, wt.LPWSTR, ctypes.POINTER(wt.DWORD)]
kernel32.QueryFullProcessImageNameW.restype = wt.BOOL
kernel32.CloseHandle.argtypes = [wt.HANDLE]
kernel32.GlobalAlloc.argtypes = [wt.UINT, ctypes.c_size_t]
kernel32.GlobalAlloc.restype = HGLOBAL
kernel32.GlobalLock.argtypes = [HGLOBAL]
kernel32.GlobalLock.restype = ctypes.c_void_p
kernel32.GlobalUnlock.argtypes = [HGLOBAL]
kernel32.GlobalFree.argtypes = [HGLOBAL]
kernel32.GlobalFree.restype = HGLOBAL
kernel32.LocalFree.argtypes = [ctypes.c_void_p]
kernel32.GetCurrentProcess.restype = wt.HANDLE
kernel32.SetPriorityClass.argtypes = [wt.HANDLE, wt.DWORD]
kernel32.CreateMutexW.argtypes = [ctypes.c_void_p, wt.BOOL, wt.LPCWSTR]
kernel32.CreateMutexW.restype = wt.HANDLE
kernel32.CreateToolhelp32Snapshot.argtypes = [wt.DWORD, wt.DWORD]
kernel32.CreateToolhelp32Snapshot.restype = wt.HANDLE


class PROCESSENTRY32W(ctypes.Structure):
    _fields_ = [
        ("dwSize", wt.DWORD), ("cntUsage", wt.DWORD), ("th32ProcessID", wt.DWORD),
        ("th32DefaultHeapID", ctypes.c_size_t), ("th32ModuleID", wt.DWORD),
        ("cntThreads", wt.DWORD), ("th32ParentProcessID", wt.DWORD),
        ("pcPriClassBase", ctypes.c_long), ("dwFlags", wt.DWORD), ("szExeFile", ctypes.c_wchar * 260),
    ]


kernel32.Process32FirstW.argtypes = [wt.HANDLE, ctypes.POINTER(PROCESSENTRY32W)]
kernel32.Process32NextW.argtypes = [wt.HANDLE, ctypes.POINTER(PROCESSENTRY32W)]


class DATA_BLOB(ctypes.Structure):
    _fields_ = [("cbData", wt.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]


crypt32.CryptProtectData.argtypes = [ctypes.POINTER(DATA_BLOB), wt.LPCWSTR, ctypes.POINTER(DATA_BLOB),
                                     ctypes.c_void_p, ctypes.c_void_p, wt.DWORD, ctypes.POINTER(DATA_BLOB)]
crypt32.CryptUnprotectData.argtypes = [ctypes.POINTER(DATA_BLOB), ctypes.c_void_p, ctypes.POINTER(DATA_BLOB),
                                       ctypes.c_void_p, ctypes.c_void_p, wt.DWORD, ctypes.POINTER(DATA_BLOB)]

INVALID_HANDLE = wt.HANDLE(-1).value
MY_PID = os.getpid()


def _dpapi(data: bytes, protect: bool) -> bytes:
    """Windows-Verschlüsselung an dein Benutzerkonto gebunden (App-Passwort liegt nie im Klartext)."""
    buf = ctypes.create_string_buffer(data, len(data))
    inp = DATA_BLOB(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))
    out = DATA_BLOB()
    fn = crypt32.CryptProtectData if protect else crypt32.CryptUnprotectData
    if not fn(ctypes.byref(inp), None, None, None, None, 0, ctypes.byref(out)):
        raise OSError("DPAPI fehlgeschlagen")
    try:
        return ctypes.string_at(out.pbData, out.cbData)
    finally:
        kernel32.LocalFree(ctypes.cast(out.pbData, ctypes.c_void_p))


def exe_of_pid(pid: int) -> str:
    h = kernel32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
    if not h:
        return ""
    try:
        buf = ctypes.create_unicode_buffer(1024)
        n = wt.DWORD(1024)
        if kernel32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(n)):
            return os.path.basename(buf.value).lower()
        return ""
    finally:
        kernel32.CloseHandle(h)


def window_pid_exe(hwnd):
    pid = wt.DWORD()
    user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    return pid.value, exe_of_pid(pid.value)


def is_drawboard_window(hwnd) -> bool:
    """Prüft am Prozessnamen (nicht am Fenstertitel – sonst würde z. B. ein Browser-Tab 'drawboard-cam' zählen)."""
    if not hwnd:
        return False
    pid, exe = window_pid_exe(hwnd)
    if pid == MY_PID:
        return False
    if "drawboard" in exe:
        return True
    if exe == "applicationframehost.exe":  # Store-Apps stecken in einem Rahmenfenster
        found = [False]

        def cb(child, _lp):
            cpid, cexe = window_pid_exe(child)
            if cpid != pid and "drawboard" in cexe:
                found[0] = True
                return False
            return True

        user32.EnumChildWindows(hwnd, WNDENUMPROC(cb), 0)
        return found[0]
    return False


def drawboard_is_foreground() -> bool:
    return is_drawboard_window(user32.GetForegroundWindow())


def drawboard_is_running() -> bool:
    snap = kernel32.CreateToolhelp32Snapshot(0x2, 0)  # TH32CS_SNAPPROCESS
    if not snap or snap == INVALID_HANDLE:
        return False
    try:
        e = PROCESSENTRY32W()
        e.dwSize = ctypes.sizeof(PROCESSENTRY32W)
        ok = kernel32.Process32FirstW(snap, ctypes.byref(e))
        while ok:
            if "drawboard" in e.szExeFile.lower() and e.th32ProcessID != MY_PID:
                return True
            ok = kernel32.Process32NextW(snap, ctypes.byref(e))
        return False
    finally:
        kernel32.CloseHandle(snap)


def set_clipboard(dib: bytes, png: bytes):
    fmt_png = user32.RegisterClipboardFormatW("PNG")
    for _ in range(40):
        if user32.OpenClipboard(None):
            break
        time.sleep(0.05)
    else:
        raise OSError("Zwischenablage ist gerade von einem anderen Programm belegt")
    try:
        user32.EmptyClipboard()
        for fmt, data in ((8, dib), (fmt_png, png)):  # 8 = CF_DIB
            h = kernel32.GlobalAlloc(0x0002, len(data))  # GMEM_MOVEABLE
            if not h:
                raise MemoryError("GlobalAlloc")
            p = kernel32.GlobalLock(h)
            ctypes.memmove(p, data, len(data))
            kernel32.GlobalUnlock(h)
            if not user32.SetClipboardData(fmt, h):
                kernel32.GlobalFree(h)
                raise OSError("SetClipboardData")
    finally:
        user32.CloseClipboard()


_KEYS_THAT_MUST_BE_UP = (0x01, 0x02, 0x04, 0x05, 0x06,   # Maustasten (Stift-Kontakt meldet sich als linke Taste)
                         0x10, 0x11, 0x12, 0x5B, 0x5C)   # Shift, Strg, Alt, Win


def inputs_idle() -> bool:
    return all(not (user32.GetAsyncKeyState(k) & 0x8000) for k in _KEYS_THAT_MUST_BE_UP)


def paste_into_drawboard() -> bool:
    """Strg+V nur, wenn Drawboard vorne ist und du gerade nichts drückst. Max. 5 s warten."""
    deadline = time.time() + 5
    while time.time() < deadline:
        hwnd = user32.GetForegroundWindow()
        if not is_drawboard_window(hwnd):
            return False
        if inputs_idle():
            time.sleep(0.15)
            if inputs_idle() and user32.GetForegroundWindow() == hwnd:
                VK_CONTROL, VK_V, UP = 0x11, 0x56, 0x0002
                user32.keybd_event(VK_CONTROL, 0, 0, 0)
                user32.keybd_event(VK_V, 0, 0, 0)
                user32.keybd_event(VK_V, 0, UP, 0)
                user32.keybd_event(VK_CONTROL, 0, UP, 0)
                return True
        time.sleep(0.1)
    return False


# ---------------------------------------------------------------- Cloud (Nextcloud / WebDAV)

def http(method, url, data=None, headers=None, auth=None, timeout=30):
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("User-Agent", UA)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    if auth:
        token = base64.b64encode(f"{auth[0]}:{auth[1]}".encode()).decode()
        req.add_header("Authorization", "Basic " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


class Cloud:
    def __init__(self):
        self.server = self.login_name = self.user_id = self.password = None
        self.load()

    # --- Speichern / Laden
    def load(self):
        try:
            with open(CFG_FILE, encoding="utf-8") as f:
                c = json.load(f)
            self.server, self.login_name, self.user_id = c["server"], c["loginName"], c["userId"]
            self.password = _dpapi(base64.b64decode(c["appPassword"]), protect=False).decode()
        except FileNotFoundError:
            pass
        except Exception as e:
            log.warning("Konfiguration unlesbar: %s", e)

    def save(self):
        c = {}
        if os.path.exists(CFG_FILE):
            with open(CFG_FILE, encoding="utf-8") as f:
                c = json.load(f)
        c.update(server=self.server, loginName=self.login_name, userId=self.user_id,
                 appPassword=base64.b64encode(_dpapi(self.password.encode(), protect=True)).decode())
        with open(CFG_FILE, "w", encoding="utf-8") as f:
            json.dump(c, f, indent=2)

    @property
    def ok(self):
        return bool(self.server and self.password and self.user_id)

    @property
    def auth(self):
        return (self.login_name, self.password)

    @property
    def folder_url(self):
        return f"{self.server}/remote.php/dav/files/{urllib.parse.quote(self.user_id)}/{FOLDER}/"

    # --- Login Flow v2 (TUHH-Login im Browser, App-Passwort kommt automatisch)
    def login(self, status_cb):
        st, body = http("POST", f"{SERVER}/index.php/login/v2", data=b"")
        if st != 200:
            raise RuntimeError(f"Cloud antwortet mit {st}")
        j = json.loads(body)
        webbrowser.open(j["login"])
        status_cb("Bitte im Browser bei der TUHH-Cloud anmelden …")
        endpoint, token = j["poll"]["endpoint"], j["poll"]["token"]
        for _ in range(600):  # max. 20 min
            time.sleep(2)
            st, body = http("POST", endpoint, data=urllib.parse.urlencode({"token": token}).encode(),
                            headers={"Content-Type": "application/x-www-form-urlencoded"})
            if st == 200:
                r = json.loads(body)
                self.server, self.login_name, self.password = r["server"].rstrip("/"), r["loginName"], r["appPassword"]
                st, body = http("GET", f"{self.server}/ocs/v1.php/cloud/user?format=json",
                                headers={"OCS-APIRequest": "true"}, auth=self.auth)
                if st != 200:
                    raise RuntimeError(f"Benutzerdaten nicht lesbar ({st})")
                self.user_id = json.loads(body)["ocs"]["data"]["id"]
                self.ensure_folder()
                self.save()
                log.info("Mit Cloud verbunden als %s", self.login_name)
                return
        raise RuntimeError("Zeit für die Anmeldung abgelaufen")

    def ensure_folder(self):
        st, _ = http("MKCOL", self.folder_url, auth=self.auth)
        if st not in (201, 405):
            raise RuntimeError(f"Ordner {FOLDER} konnte nicht angelegt werden ({st})")

    def list_photos(self):
        """[(name, url, alter_in_sekunden)] aller Bilder im Ordner."""
        body = (b'<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop>'
                b'<d:getlastmodified/><d:resourcetype/></d:prop></d:propfind>')
        st, data = http("PROPFIND", self.folder_url, data=body, auth=self.auth,
                        headers={"Depth": "1", "Content-Type": "application/xml"})
        if st == 404:
            self.ensure_folder()
            return []
        if st == 401:
            raise PermissionError("Cloud-Login ungültig")
        if st != 207:
            raise RuntimeError(f"PROPFIND {st}")
        out = []
        now = time.time()
        for resp in ET.fromstring(data).findall("{DAV:}response"):
            if resp.find(".//{DAV:}collection") is not None:
                continue
            href = resp.findtext("{DAV:}href") or ""
            name = urllib.parse.unquote(href.rstrip("/").rsplit("/", 1)[-1])
            if not name.lower().endswith((".jpg", ".jpeg", ".png")):
                continue
            lm = resp.findtext(".//{DAV:}getlastmodified")
            try:
                age = now - email.utils.parsedate_to_datetime(lm).timestamp()
            except Exception:
                age = 0
            out.append((name, urllib.parse.urljoin(self.server + "/", href), age))
        out.sort()
        return out

    def download(self, url) -> bytes:
        st, data = http("GET", url, auth=self.auth, timeout=120)
        if st != 200:
            raise RuntimeError(f"Download {st}")
        return data

    def delete(self, url):
        http("DELETE", url, auth=self.auth)


# ---------------------------------------------------------------- Hauptlogik

def image_to_clipboard_formats(data: bytes):
    img = Image.open(io.BytesIO(data))
    img = ImageOps.exif_transpose(img).convert("RGB")  # Handy-Drehung anwenden
    bmp = io.BytesIO()
    img.save(bmp, "BMP")
    png = io.BytesIO()
    img.save(png, "PNG", compress_level=1)
    return bmp.getvalue()[14:], png.getvalue()  # DIB = BMP ohne 14-Byte-Dateikopf


class Worker:
    def __init__(self, cloud: Cloud):
        self.cloud = cloud
        self.paused = False
        self.stop = threading.Event()
        self.status = "Startet …"
        self.icon = None
        self.seen = set()
        self.login_lock = threading.Lock()

    def set_status(self, s):
        if s != self.status:
            log.info(s)
        self.status = s
        if self.icon:
            try:
                self.icon.title = f"{TITLE}: {s}"[:127]
                self.icon.update_menu()
            except Exception:
                pass

    def notify(self, msg):
        try:
            if self.icon:
                self.icon.notify(msg, TITLE)
        except Exception:
            pass

    def start_login(self):
        if not self.login_lock.acquire(blocking=False):
            return

        def run():
            try:
                self.cloud.login(self.set_status)
                self.set_status("Verbunden – wartet auf Fotos")
                self.notify("Mit der TUHH-Cloud verbunden.")
            except Exception as e:
                log.exception("Login")
                self.set_status(f"Anmeldung fehlgeschlagen: {e}")
            finally:
                self.login_lock.release()

        threading.Thread(target=run, daemon=True).start()

    def handle(self, name, url, age):
        if age > STALE_SECONDS:
            log.info("Altes Foto %s (%.0f s) – nur aus der Cloud entfernt", name, age)
            self.cloud.delete(url)
            return
        data = self.cloud.download(url)
        dib, png = image_to_clipboard_formats(data)
        set_clipboard(dib, png)
        pasted = paste_into_drawboard()
        self.cloud.delete(url)  # Original bleibt auf dem Handy
        self.set_status(("In Drawboard eingefügt: " if pasted else "In Zwischenablage: ") + name)

    def loop(self):
        backoff = 0
        while not self.stop.is_set():
            interval = POLL_DRAWBOARD_CLOSED
            try:
                interval = POLL_DRAWBOARD_OPEN if drawboard_is_running() else POLL_DRAWBOARD_CLOSED
                if self.paused:
                    self.set_status("Pausiert")
                elif not self.cloud.ok:
                    self.set_status("Nicht mit Cloud verbunden – Rechtsklick aufs Symbol")
                else:
                    for name, url, age in self.cloud.list_photos():
                        if name in self.seen:
                            continue
                        self.seen.add(name)
                        try:
                            self.handle(name, url, age)
                        except Exception as e:
                            log.exception("Foto %s", name)
                            self.set_status(f"Fehler bei {name}: {e}")
                    if self.status.startswith(("Startet", "Nicht mit", "Pausiert", "Keine Verbindung", "Cloud-Login")):
                        self.set_status("Verbunden – wartet auf Fotos")
                backoff = 0
            except PermissionError:
                self.set_status("Cloud-Login ungültig – bitte neu verbinden")
                backoff = min(backoff + 10, 60)
            except Exception as e:
                log.warning("Abfrage fehlgeschlagen: %s", e)
                self.set_status("Keine Verbindung zur Cloud – versuche weiter")
                backoff = min(backoff + 5, 30)
            self.stop.wait(interval + backoff)


# ---------------------------------------------------------------- Autostart, Tray

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"


def autostart_command():
    if getattr(sys, "frozen", False):
        return f'"{sys.executable}"'
    pyw = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
    return f'"{pyw}" "{os.path.abspath(__file__)}"'


def autostart_enabled():
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            winreg.QueryValueEx(k, APP)
            return True
    except OSError:
        return False


def set_autostart(on: bool):
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
        if on:
            winreg.SetValueEx(k, APP, 0, winreg.REG_SZ, autostart_command())
        else:
            try:
                winreg.DeleteValue(k, APP)
            except OSError:
                pass


def make_icon():
    im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((2, 2, 62, 62), 12, fill=(21, 101, 192, 255))
    d.rounded_rectangle((10, 22, 54, 50), 5, fill="white")
    d.polygon([(22, 22), (26, 15), (38, 15), (42, 22)], fill="white")
    d.ellipse((23, 26, 41, 44), fill=(21, 101, 192, 255))
    d.ellipse((27, 30, 37, 40), fill="white")
    return im


def main():
    kernel32.CreateMutexW(None, False, "Local\\DrawboardCamSingleInstance")
    if ctypes.get_last_error() == 183:  # ERROR_ALREADY_EXISTS → läuft schon
        return
    kernel32.SetPriorityClass(kernel32.GetCurrentProcess(), 0x4000)  # BELOW_NORMAL

    first_run = not os.path.exists(CFG_FILE)
    cloud = Cloud()
    worker = Worker(cloud)

    if first_run:
        try:
            set_autostart(True)
        except OSError:
            pass

    def toggle_pause(icon, item):
        worker.paused = not worker.paused
        worker.set_status("Pausiert" if worker.paused else "Läuft")

    def toggle_autostart(icon, item):
        set_autostart(not autostart_enabled())

    def open_folder(icon, item):
        os.startfile(CFG_DIR)

    def quit_app(icon, item):
        worker.stop.set()
        icon.stop()

    menu = pystray.Menu(
        pystray.MenuItem(lambda item: worker.status[:60], None, enabled=False),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("Pausieren", toggle_pause, checked=lambda item: worker.paused),
        pystray.MenuItem("Mit TUHH-Cloud verbinden …", lambda i, it: worker.start_login()),
        pystray.MenuItem("Mit Windows starten", toggle_autostart, checked=lambda item: autostart_enabled()),
        pystray.MenuItem("Log-Ordner öffnen", open_folder),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("Beenden", quit_app),
    )
    icon = pystray.Icon(APP, make_icon(), TITLE, menu)
    worker.icon = icon

    def setup(ic):
        ic.visible = True
        threading.Thread(target=worker.loop, daemon=True).start()
        if not cloud.ok:
            worker.start_login()

    log.info("Gestartet")
    icon.run(setup)
    log.info("Beendet")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        log.exception("Absturz")
        raise
