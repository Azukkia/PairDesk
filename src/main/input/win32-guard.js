// Detects when Windows silently drops injected input (User Interface
// Privilege Isolation): the foreground window belongs to a process with a
// higher integrity level than PairDesk (an app "run as administrator", Task
// Manager...), or the secure desktop is shown (UAC prompt, lock screen,
// Ctrl+Alt+Del). SendInput reports success in these cases, so the controller
// only sees a mouse that "freezes": PairDesk tells them why instead.

import koffi from 'koffi';

const PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
const TOKEN_QUERY = 0x0008;
const TOKEN_INTEGRITY_LEVEL = 25; // TOKEN_INFORMATION_CLASS.TokenIntegrityLevel
const DESKTOP_READOBJECTS = 0x0001;
const UOI_NAME = 2;

/** Integrity RID of a TOKEN_MANDATORY_LABEL buffer (the SID follows the struct). */
export function integrityFromLabel(buf, pointerSize = 8) {
  // TOKEN_MANDATORY_LABEL = { PSID Sid; DWORD Attributes } → 16 bytes on x64,
  // 8 on x86; the SID itself is stored right after it in the same buffer.
  for (const off of [pointerSize * 2, 8, 16]) {
    if (off + 12 > buf.length) continue;
    const revision = buf[off];
    const count = buf[off + 1];
    // S-1-16-<rid>: authority 16, one sub-authority.
    if (revision === 1 && count >= 1 && count <= 15 && buf[off + 7] === 16 && off + 8 + 4 * count <= buf.length) {
      return buf.readUInt32LE(off + 8 + 4 * (count - 1));
    }
  }
  return null;
}

export function createWin32Guard({ log } = {}) {
  const user32 = koffi.load('user32.dll');
  const kernel32 = koffi.load('kernel32.dll');
  const advapi32 = koffi.load('advapi32.dll');

  const GetForegroundWindow = user32.func('void * __stdcall GetForegroundWindow()');
  const GetWindowThreadProcessId = user32.func('uint32_t __stdcall GetWindowThreadProcessId(void *hWnd, _Out_ uint32_t *pid)');
  const OpenInputDesktop = user32.func('void * __stdcall OpenInputDesktop(uint32_t flags, int inherit, uint32_t access)');
  const CloseDesktop = user32.func('int __stdcall CloseDesktop(void *hDesktop)');
  const GetUserObjectInformationW = user32.func(
    'int __stdcall GetUserObjectInformationW(void *hObj, int index, _Out_ uint8_t *info, uint32_t length, _Out_ uint32_t *needed)',
  );
  const OpenProcess = kernel32.func('void * __stdcall OpenProcess(uint32_t access, int inherit, uint32_t pid)');
  const GetCurrentProcess = kernel32.func('void * __stdcall GetCurrentProcess()');
  const CloseHandle = kernel32.func('int __stdcall CloseHandle(void *h)');
  const OpenProcessToken = advapi32.func('int __stdcall OpenProcessToken(void *process, uint32_t access, _Out_ void **token)');
  const GetTokenInformation = advapi32.func(
    'int __stdcall GetTokenInformation(void *token, int infoClass, _Out_ uint8_t *info, uint32_t length, _Out_ uint32_t *returned)',
  );
  const pointerSize = koffi.sizeof('void *');

  function integrityOf(processHandle) {
    const token = [null];
    if (!OpenProcessToken(processHandle, TOKEN_QUERY, token) || !token[0]) return null;
    try {
      const buf = Buffer.alloc(64);
      const returned = [0];
      if (!GetTokenInformation(token[0], TOKEN_INTEGRITY_LEVEL, buf, buf.length, returned)) return null;
      return integrityFromLabel(buf.subarray(0, returned[0] || buf.length), pointerSize);
    } finally {
      CloseHandle(token[0]);
    }
  }

  const own = integrityOf(GetCurrentProcess());
  log?.info?.(`[input] own integrity level 0x${(own ?? 0).toString(16)}`);

  function inputDesktopName() {
    const desk = OpenInputDesktop(0, 0, DESKTOP_READOBJECTS);
    if (!desk) return null; // access denied: the secure desktop is active
    try {
      const buf = Buffer.alloc(128);
      const needed = [0];
      if (!GetUserObjectInformationW(desk, UOI_NAME, buf, buf.length, needed)) return '';
      return buf.toString('utf16le').replace(/\0.*$/s, '');
    } finally {
      CloseDesktop(desk);
    }
  }

  return {
    /** null when input reaches the desktop, else 'secure-desktop' or 'elevated'. */
    check() {
      const desktop = inputDesktopName();
      if (desktop == null || (desktop && desktop.toLowerCase() !== 'default')) return 'secure-desktop';
      if (own == null) return null;
      const hwnd = GetForegroundWindow();
      if (!hwnd) return null;
      const pid = [0];
      GetWindowThreadProcessId(hwnd, pid);
      if (!pid[0]) return null;
      const proc = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid[0]);
      if (!proc) return null;
      try {
        const level = integrityOf(proc);
        return level != null && level > own ? 'elevated' : null;
      } finally {
        CloseHandle(proc);
      }
    },
  };
}
