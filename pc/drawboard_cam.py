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
import queue
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
import tkinter as tk
import winreg
from tkinter import font as tkfont
from tkinter import ttk
import xml.etree.ElementTree as ET
from logging.handlers import RotatingFileHandler

import pystray
from PIL import Image, ImageDraw, ImageOps, ImageTk

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
_cfg_lock = threading.Lock()


def read_cfg() -> dict:
    with _cfg_lock:
        try:
            with open(CFG_FILE, encoding="utf-8") as f:
                return json.load(f)
        except (FileNotFoundError, ValueError):
            return {}


def write_cfg(**changes):
    with _cfg_lock:
        try:
            with open(CFG_FILE, encoding="utf-8") as f:
                c = json.load(f)
        except (FileNotFoundError, ValueError):
            c = {}
        c.update(changes)
        tmp = CFG_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(c, f, indent=2)
        os.replace(tmp, CFG_FILE)


# Modus: "auto" = Zwischenablage + in Drawboard einfügen, "clipboard" = nur Zwischenablage, "off" = aus
MODES = {"auto": "Automatisch in Drawboard einfügen",
         "clipboard": "Nur in die Zwischenablage",
         "off": "Aus (keine Fotos abholen)"}

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

ULONG_PTR = ctypes.c_size_t


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wt.WORD), ("wScan", wt.WORD), ("dwFlags", wt.DWORD),
                ("time", wt.DWORD), ("dwExtraInfo", ULONG_PTR)]


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wt.LONG), ("dy", wt.LONG), ("mouseData", wt.DWORD), ("dwFlags", wt.DWORD),
                ("time", wt.DWORD), ("dwExtraInfo", ULONG_PTR)]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [("uMsg", wt.DWORD), ("wParamL", wt.WORD), ("wParamH", wt.WORD)]


class _INPUTUNION(ctypes.Union):
    _fields_ = [("ki", KEYBDINPUT), ("mi", MOUSEINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [("type", wt.DWORD), ("u", _INPUTUNION)]


user32.SendInput.argtypes = [wt.UINT, ctypes.POINTER(INPUT), ctypes.c_int]
user32.SendInput.restype = wt.UINT
user32.MapVirtualKeyW.argtypes = [wt.UINT, wt.UINT]
user32.MapVirtualKeyW.restype = wt.UINT
user32.GetWindowTextW.argtypes = [wt.HWND, wt.LPWSTR, ctypes.c_int]
kernel32.CreateEventW.argtypes = [ctypes.c_void_p, wt.BOOL, wt.BOOL, wt.LPCWSTR]
kernel32.CreateEventW.restype = wt.HANDLE
kernel32.SetEvent.argtypes = [wt.HANDLE]
kernel32.WaitForSingleObject.argtypes = [wt.HANDLE, wt.DWORD]
kernel32.WaitForSingleObject.restype = wt.DWORD

INVALID_HANDLE = wt.HANDLE(-1).value
MY_PID = os.getpid()


def window_title(hwnd) -> str:
    buf = ctypes.create_unicode_buffer(512)
    user32.GetWindowTextW(hwnd, buf, 512)
    return buf.value


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
        if "drawboard" in window_title(hwnd).lower():
            return True
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


def foreground_info() -> str:
    """Für die Diagnose: welches Programm ist gerade vorne und wird es als Drawboard erkannt?"""
    hwnd = user32.GetForegroundWindow()
    _, exe = window_pid_exe(hwnd)
    return f"{exe or '?'} – „{window_title(hwnd)[:50]}“ – Drawboard: {'JA' if is_drawboard_window(hwnd) else 'nein'}"


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


def _key(vk: int, up: bool) -> INPUT:
    # Mit echtem Scancode – Store-/moderne Apps wie Drawboard ignorieren Tastendrücke ohne Scancode oft.
    scan = user32.MapVirtualKeyW(vk, 0)  # MAPVK_VK_TO_VSC
    inp = INPUT()
    inp.type = 1  # INPUT_KEYBOARD
    inp.u.ki = KEYBDINPUT(vk, scan, 0x0002 if up else 0, 0, 0)
    return inp


def send_ctrl_v():
    VK_CONTROL, VK_V = 0x11, 0x56
    for vk, up in ((VK_CONTROL, False), (VK_V, False), (VK_V, True), (VK_CONTROL, True)):
        inp = _key(vk, up)
        if user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT)) != 1:
            log.warning("SendInput blockiert (Fehler %s)", ctypes.get_last_error())
        time.sleep(0.03)


def paste_into_drawboard():
    """Strg+V nur, wenn Drawboard vorne ist und du gerade nichts drückst. Max. 5 s warten.
    Gibt (eingefügt?, Grund) zurück."""
    deadline = time.time() + 5
    while time.time() < deadline:
        hwnd = user32.GetForegroundWindow()
        if not is_drawboard_window(hwnd):
            return False, "Drawboard nicht im Vordergrund (" + foreground_info() + ")"
        if inputs_idle():
            time.sleep(0.15)
            if inputs_idle() and user32.GetForegroundWindow() == hwnd:
                send_ctrl_v()
                return True, "Strg+V gesendet"
        time.sleep(0.1)
    return False, "Taste/Stift war die ganze Zeit gedrückt"


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
        c = read_cfg()
        if not c.get("appPassword"):
            return
        try:
            self.server, self.login_name, self.user_id = c["server"], c["loginName"], c["userId"]
            self.password = _dpapi(base64.b64decode(c["appPassword"]), protect=False).decode()
        except Exception as e:
            log.warning("Konfiguration unlesbar: %s", e)

    def save(self):
        write_cfg(server=self.server, loginName=self.login_name, userId=self.user_id,
                  appPassword=base64.b64encode(_dpapi(self.password.encode(), protect=True)).decode())

    def logout(self):
        self.server = self.login_name = self.user_id = self.password = None
        write_cfg(server=None, loginName=None, userId=None, appPassword=None)
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




def make_test_image() -> bytes:
    im = Image.new("RGB", (800, 300), "white")
    d = ImageDraw.Draw(im)
    d.rectangle((4, 4, 795, 295), outline=(21, 101, 192), width=8)
    d.text((40, 120), f"Test Drawboard-Kamera  {time.strftime('%H:%M:%S')}", fill=(21, 101, 192))
    buf = io.BytesIO()
    im.save(buf, "PNG")
    return buf.getvalue()


class Worker:
    def __init__(self, cloud: Cloud):
        self.cloud = cloud
        self.mode = read_cfg().get("mode", "auto")
        if self.mode not in MODES:
            self.mode = "auto"
        self.stop = threading.Event()
        self.status = "Startet …"
        self.events = []          # letzte Ereignisse fürs Fenster
        self.icon = None
        self.seen = set()
        self.login_lock = threading.Lock()

    def set_mode(self, mode):
        if mode in MODES and mode != self.mode:
            self.mode = mode
            write_cfg(mode=mode)
            self.event("Modus: " + MODES[mode])
            self.set_status(MODES[mode] if mode != "off" else "Aus")

    def set_status(self, s):
        if s != self.status:
            log.info(s)
        self.status = s
        if self.icon:
            try:
                self.icon.title = f"{TITLE}: {s}"[:127]
            except Exception:
                pass

    def event(self, s):
        log.info(s)
        self.events.insert(0, time.strftime("%H:%M:%S  ") + s)
        del self.events[12:]

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
                self.event("Mit TUHH-Cloud verbunden")
                self.notify("Mit der TUHH-Cloud verbunden.")
            except Exception as e:
                log.exception("Login")
                self.set_status(f"Anmeldung fehlgeschlagen: {e}")
            finally:
                self.login_lock.release()

        threading.Thread(target=run, daemon=True).start()

    def deliver(self, img_bytes: bytes, name: str):
        dib, png = image_to_clipboard_formats(img_bytes)
        set_clipboard(dib, png)
        if self.mode == "auto":
            pasted, why = paste_into_drawboard()
        else:
            pasted, why = False, "Modus „Nur Zwischenablage“"
        msg = ("In Drawboard eingefügt: " if pasted else "In Zwischenablage: ") + name
        self.event(f"{msg}  ({why})")
        self.set_status(msg)

    def handle(self, name, url, age):
        if age > STALE_SECONDS:
            self.event(f"Altes Foto {name} ({age / 60:.0f} min) – nicht eingefügt, aus Cloud entfernt")
            self.cloud.delete(url)
            return
        data = self.cloud.download(url)
        try:
            self.deliver(data, name)
        finally:
            self.cloud.delete(url)  # Original bleibt auf dem Handy

    def test_paste(self):
        def run():
            for i in (3, 2, 1):
                self.set_status(f"Test: Jetzt zu Drawboard wechseln … {i}")
                time.sleep(1)
            try:
                self.deliver(make_test_image(), "Testbild")
            except Exception as e:
                log.exception("Test")
                self.set_status(f"Test fehlgeschlagen: {e}")
        threading.Thread(target=run, daemon=True).start()

    def loop(self):
        backoff = 0
        while not self.stop.is_set():
            interval = POLL_DRAWBOARD_CLOSED
            try:
                if self.mode == "off":
                    self.set_status("Aus")
                    interval = 1.0
                elif not self.cloud.ok:
                    self.set_status("Nicht mit Cloud verbunden")
                    interval = 1.0
                else:
                    interval = POLL_DRAWBOARD_OPEN if drawboard_is_running() else POLL_DRAWBOARD_CLOSED
                    for name, url, age in self.cloud.list_photos():
                        if name in self.seen:
                            continue
                        self.seen.add(name)
                        try:
                            self.handle(name, url, age)
                        except Exception as e:
                            log.exception("Foto %s", name)
                            self.event(f"Fehler bei {name}: {e}")
                            self.set_status(f"Fehler bei {name}: {e}")
                    if self.status.startswith(("Startet", "Nicht mit", "Aus", "Keine Verbindung", "Cloud-Login")):
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


# ---------------------------------------------------------------- Autostart, Startmenü

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
FROZEN = getattr(sys, "frozen", False)


def autostart_command():
    if FROZEN:
        return f'"{sys.executable}" --autostart'
    pyw = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
    return f'"{pyw}" "{os.path.abspath(__file__)}" --autostart'


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


def ensure_shortcuts(first_run: bool):
    """Startmenü-Eintrag anlegen und Autostart-Pfad aktuell halten (falls die .exe verschoben wurde)."""
    try:
        if first_run or autostart_enabled():
            set_autostart(True)
        if not FROZEN:
            return
        exe = sys.executable
        lnk = os.path.join(os.environ["APPDATA"], r"Microsoft\Windows\Start Menu\Programs", "Drawboard-Kamera.lnk")
        if read_cfg().get("shortcut_target") == exe and os.path.exists(lnk):
            return
        q = lambda s: s.replace("'", "''")  # noqa: E731
        ps = (f"$s=(New-Object -ComObject WScript.Shell).CreateShortcut('{q(lnk)}');"
              f"$s.TargetPath='{q(exe)}';$s.WorkingDirectory='{q(os.path.dirname(exe))}';"
              f"$s.Description='Drawboard-Kamera Einstellungen';$s.Save()")
        subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
                       creationflags=0x08000000, timeout=60)  # CREATE_NO_WINDOW
        write_cfg(shortcut_target=exe)
    except Exception as e:
        log.warning("Verknüpfungen: %s", e)


def make_icon(size=64):
    im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((2, 2, 62, 62), 12, fill=(21, 101, 192, 255))
    d.rounded_rectangle((10, 22, 54, 50), 5, fill="white")
    d.polygon([(22, 22), (26, 15), (38, 15), (42, 22)], fill="white")
    d.ellipse((23, 26, 41, 44), fill=(21, 101, 192, 255))
    d.ellipse((27, 30, 37, 40), fill="white")
    if size != 64:
        big = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        f = size / 64
        d = ImageDraw.Draw(big)
        d.rounded_rectangle((2 * f, 2 * f, 62 * f, 62 * f), 12 * f, fill=(21, 101, 192, 255))
        d.rounded_rectangle((10 * f, 22 * f, 54 * f, 50 * f), 5 * f, fill="white")
        d.polygon([(22 * f, 22 * f), (26 * f, 15 * f), (38 * f, 15 * f), (42 * f, 22 * f)], fill="white")
        d.ellipse((23 * f, 26 * f, 41 * f, 44 * f), fill=(21, 101, 192, 255))
        d.ellipse((27 * f, 30 * f, 37 * f, 40 * f), fill="white")
        return big
    return im


# ---------------------------------------------------------------- Einstellungsfenster

class SettingsWindow:
    """Öffnet sich nur auf Wunsch: Klick aufs Taskleisten-Symbol, Startmenü oder .exe nochmal starten."""

    def __init__(self, worker: Worker, cloud: Cloud):
        self.worker, self.cloud = worker, cloud
        self.icon = None
        self.queue = queue.Queue()
        self.visible = False
        self.last_foreign_fg = "–"

        self.root = tk.Tk()
        self.root.withdraw()
        self.root.title(TITLE)
        self.root.resizable(False, False)
        self.root.protocol("WM_DELETE_WINDOW", self.hide)
        self.scale = max(1.0, self.root.winfo_fpixels("1i") / 96.0)
        try:
            for name in ("TkDefaultFont", "TkTextFont", "TkMenuFont", "TkHeadingFont"):
                tkfont.nametofont(name).configure(family="Segoe UI", size=10)
        except Exception:
            pass
        try:
            self._icon_img = ImageTk.PhotoImage(make_icon(256))
            self.root.iconphoto(True, self._icon_img)
        except Exception:
            pass
        self._build()
        self.root.after(300, self._tick)

    def _build(self):
        r = self.root
        pad = {"padx": 12, "pady": 6}
        f = ttk.Frame(r, padding=14)
        f.grid(sticky="nsew")

        self.v_status = tk.StringVar()
        ttk.Label(f, textvariable=self.v_status, font=("Segoe UI", 11, "bold"), wraplength=int(460 * self.scale)).grid(
            row=0, column=0, columnspan=2, sticky="w", pady=(0, 10))

        box = ttk.LabelFrame(f, text="Was soll mit neuen Fotos passieren?", padding=8)
        box.grid(row=1, column=0, columnspan=2, sticky="ew", **pad)
        self.v_mode = tk.StringVar(value=self.worker.mode)
        for i, (key, label) in enumerate(MODES.items()):
            ttk.Radiobutton(box, text=label, value=key, variable=self.v_mode,
                            command=lambda: self.worker.set_mode(self.v_mode.get())).grid(row=i, column=0, sticky="w")

        self.v_auto = tk.BooleanVar(value=autostart_enabled())
        ttk.Checkbutton(f, text="Mit Windows starten (unsichtbar im Hintergrund)", variable=self.v_auto,
                        command=lambda: set_autostart(self.v_auto.get())).grid(row=2, column=0, columnspan=2,
                                                                               sticky="w", **pad)

        cbox = ttk.LabelFrame(f, text="TUHH-Cloud", padding=8)
        cbox.grid(row=3, column=0, columnspan=2, sticky="ew", **pad)
        self.v_cloud = tk.StringVar()
        ttk.Label(cbox, textvariable=self.v_cloud).grid(row=0, column=0, columnspan=2, sticky="w")
        self.b_connect = ttk.Button(cbox, text="Verbinden", command=self.worker.start_login)
        self.b_connect.grid(row=1, column=0, sticky="w", pady=(6, 0))
        self.b_logout = ttk.Button(cbox, text="Abmelden", command=self._logout)
        self.b_logout.grid(row=1, column=1, sticky="w", padx=8, pady=(6, 0))

        dbox = ttk.LabelFrame(f, text="Test & Diagnose", padding=8)
        dbox.grid(row=4, column=0, columnspan=2, sticky="ew", **pad)
        ttk.Button(dbox, text="Einfügen testen (3 Sek. Zeit, um zu Drawboard zu wechseln)",
                   command=self.worker.test_paste).grid(row=0, column=0, sticky="w")
        self.v_fg = tk.StringVar()
        ttk.Label(dbox, textvariable=self.v_fg, wraplength=int(460 * self.scale), foreground="#555").grid(
            row=1, column=0, sticky="w", pady=(6, 0))
        ttk.Label(dbox, text="Letzte Ereignisse:").grid(row=2, column=0, sticky="w", pady=(8, 0))
        self.events = tk.Listbox(dbox, height=7, width=78, activestyle="none")
        self.events.grid(row=3, column=0, sticky="ew")

        bar = ttk.Frame(f)
        bar.grid(row=5, column=0, columnspan=2, sticky="ew", pady=(10, 0))
        ttk.Button(bar, text="Log-Ordner öffnen", command=lambda: os.startfile(CFG_DIR)).pack(side="left")
        ttk.Button(bar, text="Programm beenden", command=self.quit).pack(side="left", padx=8)
        ttk.Button(bar, text="Schließen (läuft weiter)", command=self.hide).pack(side="right")

    def _logout(self):
        self.cloud.logout()
        self.worker.event("Von der Cloud abgemeldet")

    def show(self):
        self.visible = True
        self.v_mode.set(self.worker.mode)
        self.v_auto.set(autostart_enabled())
        self.root.deiconify()
        self.root.lift()
        self.root.attributes("-topmost", True)
        self.root.after(200, lambda: self.root.attributes("-topmost", False))
        self.root.focus_force()
        self._refresh()

    def hide(self):
        self.visible = False
        self.root.withdraw()

    def quit(self):
        self.worker.stop.set()
        try:
            if self.icon:
                self.icon.stop()
        finally:
            self.root.destroy()

    def _refresh(self):
        self.v_status.set(self.worker.status)
        if self.v_mode.get() != self.worker.mode:  # z. B. über das Taskleisten-Menü geändert
            self.v_mode.set(self.worker.mode)
        c = self.cloud
        self.v_cloud.set(f"✓ Verbunden als {c.login_name}  (Ordner „{FOLDER}“)" if c.ok else "Nicht verbunden")
        self.b_connect.configure(text="Neu verbinden" if c.ok else "Verbinden")
        self.b_logout.state(["!disabled"] if c.ok else ["disabled"])
        self.v_fg.set("Zuletzt aktives Fenster: " + self.last_foreign_fg)
        items = list(self.events.get(0, "end"))
        if items != self.worker.events:
            self.events.delete(0, "end")
            for e in self.worker.events:
                self.events.insert("end", e)

    def _tick(self):
        try:
            while True:
                cmd = self.queue.get_nowait()
                if cmd == "show":
                    self.show()
                elif cmd == "quit":
                    self.quit()
                    return
        except queue.Empty:
            pass
        try:
            hwnd = user32.GetForegroundWindow()
            if hwnd and window_pid_exe(hwnd)[0] != MY_PID:
                self.last_foreign_fg = foreground_info()
            if self.visible:
                self._refresh()
        except Exception:
            pass
        self.root.after(400, self._tick)


# ---------------------------------------------------------------- Start

def enable_sharp_rendering():
    """Windows sagen: Wir zeichnen selbst in voller Auflösung → kein unscharfes Hochskalieren."""
    try:
        user32.SetProcessDpiAwarenessContext.argtypes = [ctypes.c_void_p]
        if user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4)):  # PER_MONITOR_AWARE_V2
            return
    except Exception:
        pass
    try:
        ctypes.WinDLL("shcore").SetProcessDpiAwareness(2)
    except Exception:
        try:
            user32.SetProcessDPIAware()
        except Exception:
            pass


def main():
    autostart = "--autostart" in sys.argv
    kernel32.CreateMutexW(None, False, "Local\\DrawboardCamSingleInstance")
    already = ctypes.get_last_error() == 183  # ERROR_ALREADY_EXISTS
    show_event = kernel32.CreateEventW(None, False, False, "Local\\DrawboardCamShowSettings")
    if already:
        if not autostart and show_event:
            kernel32.SetEvent(show_event)  # laufendes Programm soll sein Fenster zeigen
        return
    kernel32.SetPriorityClass(kernel32.GetCurrentProcess(), 0x4000)  # BELOW_NORMAL

    enable_sharp_rendering()
    first_run = not os.path.exists(CFG_FILE)
    threading.Thread(target=ensure_shortcuts, args=(first_run,), daemon=True).start()

    cloud = Cloud()
    worker = Worker(cloud)
    win = SettingsWindow(worker, cloud)

    def tray_mode(mode):
        return pystray.MenuItem(MODES[mode], lambda i, it: worker.set_mode(mode),
                                checked=lambda it: worker.mode == mode, radio=True)

    menu = pystray.Menu(
        pystray.MenuItem("Einstellungen öffnen", lambda i, it: win.queue.put("show"), default=True),
        pystray.Menu.SEPARATOR,
        tray_mode("auto"), tray_mode("clipboard"), tray_mode("off"),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("Beenden", lambda i, it: win.queue.put("quit")),
    )
    icon = pystray.Icon(APP, make_icon(), TITLE, menu)
    worker.icon = icon
    win.icon = icon

    def wait_for_show():
        while not worker.stop.is_set():
            if show_event and kernel32.WaitForSingleObject(show_event, 1000) == 0:
                win.queue.put("show")

    threading.Thread(target=worker.loop, daemon=True).start()
    threading.Thread(target=wait_for_show, daemon=True).start()
    icon.run_detached()

    if not cloud.ok and not autostart:
        worker.start_login()
    if not autostart:  # von Hand gestartet → Fenster zeigen; beim Windows-Start nicht
        win.queue.put("show")

    log.info("Gestartet%s", " (Autostart)" if autostart else "")
    win.root.mainloop()
    worker.stop.set()
    try:
        icon.stop()
    except Exception:
        pass
    log.info("Beendet")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        log.exception("Absturz")
        raise
