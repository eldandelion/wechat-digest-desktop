#!/usr/bin/env python3
"""Bundled macOS WCDB SQLCipher 4 key extractor for WeChat Digest.

Supports WeChat 4.0.x memory scanning (`task_for_pid` / `mach_vm_read`) and WeChat 4.1.10+
passphrase capture via `lldb` breakpoint on `CCKeyDerivationPBKDF`, followed by PBKDF2-HMAC-SHA512
key derivation and Page 1 HMAC-SHA512 verification. Outputs only status/progress events and never
prints raw keys or passphrases to stdout/stderr.
"""
from __future__ import annotations

import argparse
import ctypes
import ctypes.util
import hashlib
import hmac as hmac_mod
import json
import os
import re
import select
import signal
import struct
import subprocess
import sys
import tempfile
import time
from typing import Optional

PAGE_SZ = 4096
KEY_SZ = 32
SALT_SZ = 16
IV_SZ = 16
HMAC_SZ = 64
RESERVE_SZ = 80
DEFAULT_TIMEOUT = 180

_libSystem = ctypes.CDLL(ctypes.util.find_library("System"))

_libSystem.mach_task_self.argtypes = []
_libSystem.mach_task_self.restype = ctypes.c_uint32

_libSystem.task_for_pid.argtypes = [ctypes.c_uint32, ctypes.c_int, ctypes.POINTER(ctypes.c_uint32)]
_libSystem.task_for_pid.restype = ctypes.c_int

_libSystem.mach_vm_region.argtypes = [
    ctypes.c_uint32,
    ctypes.POINTER(ctypes.c_uint64),
    ctypes.POINTER(ctypes.c_uint64),
    ctypes.c_int,
    ctypes.c_void_p,
    ctypes.POINTER(ctypes.c_uint32),
    ctypes.POINTER(ctypes.c_uint32),
]
_libSystem.mach_vm_region.restype = ctypes.c_int

_libSystem.mach_vm_read.argtypes = [
    ctypes.c_uint32,
    ctypes.c_uint64,
    ctypes.c_uint64,
    ctypes.POINTER(ctypes.c_uint64),
    ctypes.POINTER(ctypes.c_uint64),
]
_libSystem.mach_vm_read.restype = ctypes.c_int

_libSystem.mach_vm_deallocate.argtypes = [ctypes.c_uint32, ctypes.c_uint64, ctypes.c_uint64]
_libSystem.mach_vm_deallocate.restype = ctypes.c_int

_libSystem.mach_port_deallocate.argtypes = [ctypes.c_uint32, ctypes.c_uint32]
_libSystem.mach_port_deallocate.restype = ctypes.c_int

KERN_SUCCESS = 0
VM_REGION_BASIC_INFO_64 = 9
VM_REGION_BASIC_INFO_COUNT_64 = 9
VM_PROT_READ = 0x01
_WECHAT_KEY_PATTERN = re.compile(rb"x'([0-9a-f]{96})'")
_JSON_EVENTS = False


class vm_region_basic_info_64(ctypes.Structure):
    _fields_ = [
        ("protection", ctypes.c_int32),
        ("max_protection", ctypes.c_int32),
        ("inheritance", ctypes.c_uint32),
        ("shared", ctypes.c_uint32),
        ("reserved", ctypes.c_uint32),
        ("offset", ctypes.c_uint64),
        ("behavior", ctypes.c_int32),
        ("user_wired_count", ctypes.c_uint16),
    ]


class SignatureInvalidError(RuntimeError):
    pass


class KeysNotFoundError(RuntimeError):
    pass


class CaptureError(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def emit_event(payload: dict) -> None:
    if _JSON_EVENTS:
        print(json.dumps(payload, ensure_ascii=False), flush=True)
    else:
        ev = payload.get("event")
        if ev == "stage":
            print(f"[*] {payload.get('message', '')}", flush=True)
        elif ev == "complete":
            print(
                f"[+] Verified {payload.get('verifiedCount', 0)}/{payload.get('totalCount', 0)} databases.",
                flush=True,
            )
        elif ev == "error":
            print(f"[ERROR] ({payload.get('code', 'unknown')}) {payload.get('message', '')}", flush=True)


def verify_enc_key(enc_key: bytes, db_page1: bytes) -> bool:
    if len(db_page1) < PAGE_SZ or len(enc_key) != KEY_SZ:
        return False
    salt = db_page1[:SALT_SZ]
    mac_salt = bytes(b ^ 0x3A for b in salt)
    mac_key = hashlib.pbkdf2_hmac("sha512", enc_key, mac_salt, 2, dklen=KEY_SZ)
    hmac_data = db_page1[SALT_SZ: PAGE_SZ - RESERVE_SZ + IV_SZ]
    stored_hmac = db_page1[PAGE_SZ - HMAC_SZ: PAGE_SZ]
    hm = hmac_mod.new(mac_key, hmac_data, hashlib.sha512)
    hm.update(struct.pack("<I", 1))
    return hmac_mod.compare_digest(hm.digest(), stored_hmac)


def is_encrypted_db(db_page1: bytes) -> bool:
    return len(db_page1) >= 16 and db_page1[:16] != b"SQLite format 3\x00"


def _has_symlink_ancestor(path: str) -> bool:
    norm = os.path.normpath(os.path.abspath(path))
    cursor = norm
    while True:
        if os.path.islink(cursor):
            parent, name = os.path.split(cursor)
            if not (parent == "/" and name in ("var", "tmp", "etc")):
                return True
        parent = os.path.dirname(cursor)
        if parent == cursor:
            break
        cursor = parent
    return False


def collect_db_files(db_dir: str) -> tuple[list, dict]:
    db_files = []
    salt_to_dbs: dict[str, list[str]] = {}
    norm_dir = os.path.normpath(os.path.abspath(db_dir))
    if _has_symlink_ancestor(norm_dir) or not os.path.isdir(norm_dir):
        return db_files, salt_to_dbs
    for root, dirs, files in os.walk(norm_dir):
        dirs[:] = [d for d in dirs if not os.path.islink(os.path.join(root, d))]
        for name in sorted(files):
            if (
                not name.endswith(".db")
                or name.endswith("-wal")
                or name.endswith("-shm")
                or name in ("message_fts.db", "message_resource.db")
            ):
                continue
            path = os.path.join(root, name)
            if os.path.islink(path) or not os.path.isfile(path):
                continue
            size = os.path.getsize(path)
            if size < PAGE_SZ:
                continue
            with open(path, "rb") as f:
                page1 = f.read(PAGE_SZ)
            if len(page1) < PAGE_SZ or not is_encrypted_db(page1):
                continue
            rel = os.path.relpath(path, norm_dir).replace("\\", "/")
            salt = page1[:SALT_SZ].hex()
            db_files.append((rel, path, size, salt, page1))
            salt_to_dbs.setdefault(salt, []).append(rel)
    return db_files, salt_to_dbs


def _find_pids(process_name: str, owner_uid: int = -1) -> list[int]:
    try:
        cmd = ["/usr/bin/pgrep"]
        if owner_uid >= 0:
            cmd.extend(["-u", str(owner_uid)])
        cmd.extend(["-x", process_name])
        r = subprocess.run(cmd, capture_output=True, text=True)
        return [int(p) for p in r.stdout.strip().split() if p.strip().isdigit()]
    except (FileNotFoundError, ValueError):
        return []


def _task_for_pid(pid: int) -> int:
    task = ctypes.c_uint32(0)
    kr = _libSystem.task_for_pid(_libSystem.mach_task_self(), ctypes.c_int(pid), ctypes.byref(task))
    if kr != KERN_SUCCESS:
        raise PermissionError(f"task_for_pid failed (kern_return={kr})")
    return task.value


def _enum_readable_regions(task: int) -> list[tuple[int, int]]:
    regions = []
    address = ctypes.c_uint64(0)
    size = ctypes.c_uint64(0)
    info = vm_region_basic_info_64()
    info_count = ctypes.c_uint32(VM_REGION_BASIC_INFO_COUNT_64)
    object_name = ctypes.c_uint32(0)
    while True:
        kr = _libSystem.mach_vm_region(
            ctypes.c_uint32(task),
            ctypes.byref(address),
            ctypes.byref(size),
            ctypes.c_int(VM_REGION_BASIC_INFO_64),
            ctypes.byref(info),
            ctypes.byref(info_count),
            ctypes.byref(object_name),
        )
        if kr != KERN_SUCCESS:
            break
        reg_size = size.value
        if (info.protection & VM_PROT_READ) and 0 < reg_size < 500 * 1024 * 1024:
            regions.append((address.value, reg_size))
        next_addr = address.value + reg_size
        if reg_size == 0 or next_addr <= address.value or next_addr >= (1 << 64):
            break
        address.value = next_addr
    return regions


def _read_memory(task: int, address: int, size: int) -> Optional[bytes]:
    data_ptr = ctypes.c_uint64(0)
    data_size = ctypes.c_uint64(0)
    kr = _libSystem.mach_vm_read(
        ctypes.c_uint32(task),
        ctypes.c_uint64(address),
        ctypes.c_uint64(size),
        ctypes.byref(data_ptr),
        ctypes.byref(data_size),
    )
    if kr != KERN_SUCCESS:
        return None
    try:
        return ctypes.string_at(data_ptr.value, data_size.value)
    finally:
        _libSystem.mach_vm_deallocate(_libSystem.mach_task_self(), data_ptr, data_size)


def _scan_memory_raw_key(db_files: list, salt_to_dbs: dict, owner_uid: int = -1) -> dict[str, str]:
    pids = _find_pids("WeChat", owner_uid)
    if not pids:
        raise CaptureError("wechat_not_running", "WeChat is not running. Start WeChat before extracting keys.")

    key_map: dict[str, str] = {}
    remaining_salts = set(salt_to_dbs.keys())
    task_ok_count = 0

    for pid in pids:
        if not remaining_salts:
            break
        try:
            task = _task_for_pid(pid)
            task_ok_count += 1
        except PermissionError:
            continue

        try:
            regions = _enum_readable_regions(task)
            for base, size in regions:
                if not remaining_salts:
                    break
                data = _read_memory(task, base, size)
                if not data:
                    continue
                for m in _WECHAT_KEY_PATTERN.finditer(data):
                    hex_str = m.group(1).decode("ascii")
                    enc_key_hex, salt_hex = hex_str[:64], hex_str[64:]
                    if salt_hex not in remaining_salts:
                        continue
                    enc_key = bytes.fromhex(enc_key_hex)
                    for _rel, _path, _sz, s, page1 in db_files:
                        if s == salt_hex and verify_enc_key(enc_key, page1):
                            key_map[salt_hex] = enc_key_hex
                            remaining_salts.discard(salt_hex)
                            break
        finally:
            _libSystem.mach_port_deallocate(_libSystem.mach_task_self(), ctypes.c_uint32(task))

    if not key_map:
        if task_ok_count == 0:
            raise SignatureInvalidError(
                "Unable to attach to WeChat (task_for_pid denied). Re-sign WeChat locally without Hardened Runtime."
            )
        raise KeysNotFoundError("No raw keys found in memory; using LLDB passphrase capture for WeChat 4.1.10+.")

    return key_map


def _parse_passphrase(out: str) -> Optional[str]:
    by = []
    for line in out.splitlines():
        m = re.match(r"\s*0x[0-9a-f]+:\s+((?:0x[0-9a-f]{2}\s*)+)$", line)
        if m:
            by.extend(re.findall(r"0x([0-9a-f]{2})", m.group(1)))
    return "".join(by[:32]) if len(by) >= 32 else None


def _is_cancelled(cancel_file: Optional[str], cancel_parent: Optional[str], cancel_parent_existed: bool) -> bool:
    if not cancel_file:
        return False
    if os.path.exists(cancel_file):
        return True
    return cancel_parent_existed and cancel_parent is not None and not os.path.isdir(cancel_parent)


def capture_passphrase_lldb(
    timeout: int = DEFAULT_TIMEOUT,
    owner_uid: int = -1,
    cancel_file: Optional[str] = None,
) -> str:
    import platform

    lldb_bin = "/usr/bin/lldb"
    if not os.access(lldb_bin, os.X_OK):
        raise CaptureError("lldb_missing", "Xcode Command Line Tools (lldb) not found. Run: xcode-select --install")

    pids = _find_pids("WeChat", owner_uid)
    if not pids:
        raise CaptureError("wechat_not_running", "WeChat is not running. Launch and log into WeChat first.")
    pid = pids[0]

    cancel_parent: Optional[str] = None
    cancel_parent_existed = False
    if cancel_file:
        norm_cancel = os.path.normpath(os.path.abspath(cancel_file))
        cancel_parent = os.path.dirname(norm_cancel)
        cancel_parent_existed = os.path.isdir(cancel_parent)

    if _is_cancelled(cancel_file, cancel_parent, cancel_parent_existed):
        raise CaptureError("cancelled", "Key extraction was cancelled.")

    is_arm = platform.machine() in ("arm64", "aarch64")
    pw_reg, len_reg = ("x1", "x2") if is_arm else ("rsi", "rdx")

    script = (
        "settings set target.preload-symbols false\n"
        f"process attach -p {pid}\n"
        f"breakpoint set -n CCKeyDerivationPBKDF -c '${len_reg} == 32'\n"
        "breakpoint command add 1\n"
        f"memory read --size 1 --count 32 --format x ${pw_reg}\n"
        "detach\n"
        "quit\n"
        "DONE\n"
        "process continue\n"
    )

    env = dict(os.environ)
    env["TERM"] = "dumb"
    proc = subprocess.Popen(
        [lldb_bin, "--no-lldbinit"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        env=env,
        start_new_session=True,
    )
    buf = ""
    cancelled = False
    deadline = time.time() + timeout
    try:
        assert proc.stdin is not None
        assert proc.stdout is not None
        proc.stdin.write(script)
        proc.stdin.flush()

        while time.time() < deadline:
            if _is_cancelled(cancel_file, cancel_parent, cancel_parent_existed):
                cancelled = True
                break
            r, _, _ = select.select([proc.stdout], [], [], 1.0)
            if r:
                line = proc.stdout.readline()
                if not line:
                    if proc.poll() is not None:
                        break
                    continue
                buf += line
                if "attach failed" in line.lower() or "unable to locate" in line.lower():
                    raise CaptureError(
                        "signature_invalid",
                        "LLDB failed to attach to WeChat. Ensure WeChat is locally re-signed and SIP/DevToolsSecurity permits debugging.",
                    )
                if _parse_passphrase(buf):
                    break
    finally:
        try:
            if proc.stdin and not proc.stdin.closed:
                try:
                    proc.stdin.write("\ndetach\nquit\n")
                    proc.stdin.flush()
                except OSError:
                    pass
                proc.stdin.close()
        except OSError:
            pass
        try:
            proc.wait(timeout=1.5)
        except Exception:
            try:
                os.killpg(proc.pid, signal.SIGTERM)
                proc.wait(timeout=2)
            except Exception:
                try:
                    os.killpg(proc.pid, signal.SIGKILL)
                    proc.wait(timeout=2)
                except Exception:
                    pass

    if cancelled:
        raise CaptureError("cancelled", "Key extraction was cancelled.")

    ph = _parse_passphrase(buf)
    if ph:
        return ph
    raise CaptureError(
        "timeout",
        "Timed out waiting for WeChat login. Log out of WeChat desktop (not quit) and log back in during capture.",
    )


def _set_owner_dir_perms_nofollow(dir_path: str, owner_uid: int, owner_gid: int) -> None:
    flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
    dir_fd = os.open(dir_path, flags)
    try:
        os.fchmod(dir_fd, 0o700)
        if os.geteuid() == 0 and owner_uid >= 0 and owner_gid >= 0:
            os.fchown(dir_fd, owner_uid, owner_gid)
    finally:
        os.close(dir_fd)


def _ensure_owner_dir(path: str, owner_uid: int, owner_gid: int) -> None:
    norm_path = os.path.normpath(os.path.abspath(path))
    if _has_symlink_ancestor(norm_path):
        raise CaptureError("permission_denied", f"Refusing symlinked directory: {norm_path}")

    missing_dirs: list[str] = []
    cursor = norm_path
    while cursor and not os.path.exists(cursor):
        missing_dirs.append(cursor)
        parent = os.path.dirname(cursor)
        if parent == cursor:
            break
        cursor = parent

    for dir_path in reversed(missing_dirs):
        os.makedirs(dir_path, mode=0o700, exist_ok=True)
        _set_owner_dir_perms_nofollow(dir_path, owner_uid, owner_gid)

    if os.path.basename(norm_path) == "WeChatDigest":
        _set_owner_dir_perms_nofollow(norm_path, owner_uid, owner_gid)


def _write_owner_file(target_path: str, data: dict, owner_uid: int, owner_gid: int) -> None:
    norm_target = os.path.normpath(os.path.abspath(target_path))
    if _has_symlink_ancestor(norm_target):
        raise CaptureError("permission_denied", f"Refusing symlinked output path: {norm_target}")
    parent = os.path.dirname(norm_target)
    _ensure_owner_dir(parent, owner_uid, owner_gid)
    fd, tmp_path = tempfile.mkstemp(prefix=".tmp_", dir=parent)
    try:
        os.fchmod(fd, 0o600)
        if os.geteuid() == 0 and owner_uid >= 0 and owner_gid >= 0:
            os.fchown(fd, owner_uid, owner_gid)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
            f.write("\n")
            f.flush()
        fd = -1
        os.replace(tmp_path, norm_target)
    finally:
        if fd >= 0:
            try:
                os.close(fd)
            except OSError:
                pass
        if os.path.exists(tmp_path):
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def _derive_keys_from_passphrase(passphrase: bytes, db_files: list, salt_to_dbs: dict) -> dict[str, str]:
    key_map: dict[str, str] = {}
    for salt_hex in salt_to_dbs:
        salt = bytes.fromhex(salt_hex)
        enc_key = hashlib.pbkdf2_hmac("sha512", passphrase, salt, 256000, dklen=KEY_SZ)
        for _rel, _path, _sz, s, page1 in db_files:
            if s == salt_hex and verify_enc_key(enc_key, page1):
                key_map[salt_hex] = enc_key.hex()
                break
    return key_map


def _build_all_keys_payload(db_files: list, key_map: dict[str, str], db_dir: str) -> dict:
    payload: dict = {}
    for rel, _path, sz, salt_hex, _page1 in db_files:
        if salt_hex in key_map:
            payload[rel] = {
                "enc_key": key_map[salt_hex],
                "salt": salt_hex,
                "size_mb": round(sz / 1024 / 1024, 1),
            }
    if not payload:
        raise CaptureError("unknown", "No database keys matched Page 1 HMAC verification.")
    payload["_db_dir"] = os.path.abspath(db_dir)
    return payload


def _load_passphrase(passphrase_file: Optional[str]) -> Optional[str]:
    if not passphrase_file:
        return None
    norm = os.path.normpath(os.path.abspath(passphrase_file))
    if _has_symlink_ancestor(norm) or not os.path.isfile(norm):
        return None
    try:
        with open(norm, "r", encoding="utf-8") as f:
            raw = json.load(f).get("passphrase", "")
        if isinstance(raw, str) and len(raw.strip()) == 64:
            bytes.fromhex(raw.strip())
            return raw.strip().lower()
    except Exception:
        return None
    return None


def _handle_termination_signal(_signum: int, _frame) -> None:
    raise SystemExit(143)


def main() -> int:
    global _JSON_EVENTS
    signal.signal(signal.SIGTERM, _handle_termination_signal)
    signal.signal(signal.SIGHUP, _handle_termination_signal)
    parser = argparse.ArgumentParser(description="WeChat Digest macOS key extractor")
    parser.add_argument("--db-dir", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--passphrase-file", required=True)
    parser.add_argument("--owner-uid", type=int, default=-1)
    parser.add_argument("--owner-gid", type=int, default=-1)
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT)
    parser.add_argument("--cancel-file", default=None)
    parser.add_argument("--json-events", action="store_true")
    args = parser.parse_args()
    _JSON_EVENTS = args.json_events

    try:
        emit_event({"event": "stage", "stage": "checking_cache", "message": "Inspecting WeChat database headers..."})
        db_files, salt_to_dbs = collect_db_files(args.db_dir)
        if not db_files:
            emit_event({"event": "complete", "verifiedCount": 0, "totalCount": 0, "outputPath": args.output})
            return 0

        # Tier 1: verify existing all_keys.json
        norm_output = os.path.normpath(os.path.abspath(args.output))
        if not _has_symlink_ancestor(norm_output) and os.path.isfile(norm_output):
            try:
                with open(norm_output, encoding="utf-8") as f:
                    existing = {k: v for k, v in json.load(f).items() if not k.startswith("_") and isinstance(v, dict)}
                ok = True
                for rel, _p, _sz, _salt, page1 in db_files:
                    info = existing.get(rel) or existing.get(os.path.basename(rel))
                    if not info or "enc_key" not in info or not verify_enc_key(bytes.fromhex(info["enc_key"]), page1):
                        ok = False
                        break
                if ok:
                    emit_event({
                        "event": "complete",
                        "verifiedCount": len(db_files),
                        "totalCount": len(db_files),
                        "outputPath": args.output,
                    })
                    return 0
            except Exception:
                pass

        # Tier 2: cached passphrase derivation
        cached_ph = _load_passphrase(args.passphrase_file)
        if cached_ph:
            emit_event({"event": "stage", "stage": "deriving", "message": "Deriving database keys from saved passphrase..."})
            key_map = _derive_keys_from_passphrase(bytes.fromhex(cached_ph), db_files, salt_to_dbs)
            if key_map:
                payload = _build_all_keys_payload(db_files, key_map, args.db_dir)
                _write_owner_file(args.output, payload, args.owner_uid, args.owner_gid)
                emit_event({
                    "event": "complete",
                    "verifiedCount": len(payload) - 1,
                    "totalCount": len(db_files),
                    "outputPath": args.output,
                })
                return 0

        # Tier 3: 4.0.x raw key scan
        emit_event({"event": "stage", "stage": "memory_scan", "message": "Checking running WeChat process memory..."})
        try:
            key_map = _scan_memory_raw_key(db_files, salt_to_dbs, args.owner_uid)
            payload = _build_all_keys_payload(db_files, key_map, args.db_dir)
            _write_owner_file(args.output, payload, args.owner_uid, args.owner_gid)
            emit_event({
                "event": "complete",
                "verifiedCount": len(payload) - 1,
                "totalCount": len(db_files),
                "outputPath": args.output,
            })
            return 0
        except SignatureInvalidError as err:
            emit_event({"event": "error", "code": "signature_invalid", "message": str(err)})
            return 2
        except KeysNotFoundError:
            pass

        # Tier 4: 4.1.10+ LLDB passphrase breakpoint
        emit_event({
            "event": "stage",
            "stage": "lldb_wait",
            "message": "Log out of WeChat (Account -> Log Out) and log back in to capture the key.",
            "timeoutSec": args.timeout,
        })
        ph_hex = capture_passphrase_lldb(
            timeout=args.timeout,
            owner_uid=args.owner_uid,
            cancel_file=args.cancel_file,
        )
        _write_owner_file(args.passphrase_file, {"passphrase": ph_hex}, args.owner_uid, args.owner_gid)

        emit_event({"event": "stage", "stage": "deriving", "message": "Deriving and verifying SQLCipher database keys..."})
        key_map = _derive_keys_from_passphrase(bytes.fromhex(ph_hex), db_files, salt_to_dbs)
        payload = _build_all_keys_payload(db_files, key_map, args.db_dir)
        _write_owner_file(args.output, payload, args.owner_uid, args.owner_gid)
        emit_event({
            "event": "complete",
            "verifiedCount": len(payload) - 1,
            "totalCount": len(db_files),
            "outputPath": args.output,
        })
        return 0
    except CaptureError as err:
        emit_event({"event": "error", "code": err.code, "message": str(err)})
        return 1
    except PermissionError as err:
        emit_event({"event": "error", "code": "permission_denied", "message": str(err)})
        return 1
    except Exception as err:
        emit_event({"event": "error", "code": "unknown", "message": str(err)})
        return 1


if __name__ == "__main__":
    sys.exit(main())
