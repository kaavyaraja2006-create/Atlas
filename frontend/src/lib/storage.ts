// Browser storage is only for per-viewer conveniences (last organization, theme).
// It can be unavailable (private mode, blocked site data), so every access is guarded.

export function readPref(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

export function writePref(key: string, value: string | null): void {
  try {
    if (value === null) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, value);
  } catch {
    /* preference simply not remembered */
  }
}
