import json
import os
import time
import uuid
from pathlib import Path
from typing import List, Dict, Optional, Tuple

DATA_DIR = Path(__file__).resolve().parent.parent / "data"
KEYS_FILE = DATA_DIR / "keys.json"

SUPPORTED_PROVIDERS = ["verboo", "groq", "gemini"]

def _ensure_storage():
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    if not KEYS_FILE.exists():
        default_data = {p: [] for p in SUPPORTED_PROVIDERS}
        KEYS_FILE.write_text(json.dumps(default_data, indent=2), encoding="utf-8")
    else:
        try:
            data = json.loads(KEYS_FILE.read_text(encoding="utf-8"))
            changed = False
            for p in SUPPORTED_PROVIDERS:
                if p not in data:
                    data[p] = []
                    changed = True
            if changed:
                KEYS_FILE.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
        except Exception:
            pass

def load_keys() -> Dict[str, List[dict]]:
    _ensure_storage()
    try:
        data = json.loads(KEYS_FILE.read_text(encoding="utf-8"))
        for p in SUPPORTED_PROVIDERS:
            if p not in data:
                data[p] = []
        return data
    except Exception:
        return {p: [] for p in SUPPORTED_PROVIDERS}

def save_keys(data: Dict[str, List[dict]]):
    _ensure_storage()
    KEYS_FILE.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")

def mask_key(key: str) -> str:
    if len(key) <= 8:
        return "***"
    return f"{key[:5]}...{key[-4:]}"

def get_keys_summary() -> Dict[str, List[dict]]:
    """Return keys with masked values for UI safety"""
    data = load_keys()
    result = {p: [] for p in SUPPORTED_PROVIDERS}
    for provider in SUPPORTED_PROVIDERS:
        for item in data.get(provider, []):
            item_copy = dict(item)
            item_copy["masked_key"] = mask_key(item.get("key", ""))
            # Do not expose full key to public JSON responses
            item_copy.pop("key", None)
            result[provider].append(item_copy)
    return result

def add_keys(provider: str, raw_text: str) -> int:
    """Add one or multiple keys (split by newline or commas)"""
    if provider not in SUPPORTED_PROVIDERS:
        raise ValueError(f"Provider must be one of {SUPPORTED_PROVIDERS}")
    
    data = load_keys()
    existing_keys = {item["key"] for item in data.get(provider, [])}
    
    # Split by newlines, commas, or semicolons
    raw_lines = [k.strip() for k in raw_text.replace(",", "\n").replace(";", "\n").split("\n")]
    added_count = 0
    
    for raw in raw_lines:
        k = raw.strip()
        if not k or len(k) < 6:
            continue
        if k in existing_keys:
            continue
        
        data[provider].append({
            "id": uuid.uuid4().hex[:8],
            "key": k,
            "status": "active",  # 'active', 'exhausted', 'invalid'
            "exhausted_reason": "",
            "added_at": int(time.time()),
            "last_used": None,
            "success_count": 0
        })
        existing_keys.add(k)
        added_count += 1
        
    save_keys(data)
    return added_count

def delete_key(provider: str, key_id: str) -> bool:
    data = load_keys()
    if provider not in data:
        return False
    before = len(data[provider])
    data[provider] = [item for item in data[provider] if item.get("id") != key_id]
    if len(data[provider]) != before:
        save_keys(data)
        return True
    return False

def reset_key(provider: str, key_id: str) -> bool:
    data = load_keys()
    if provider not in data:
        return False
    for item in data[provider]:
        if item.get("id") == key_id:
            item["status"] = "active"
            item["exhausted_reason"] = ""
            save_keys(data)
            return True
    return False

def reset_all_exhausted(provider: Optional[str] = None):
    data = load_keys()
    targets = [provider] if provider else SUPPORTED_PROVIDERS
    for prov in targets:
        for item in data.get(prov, []):
            if item.get("status") in ["exhausted", "invalid"]:
                item["status"] = "active"
                item["exhausted_reason"] = ""
    save_keys(data)

def get_active_keys(provider: str) -> List[dict]:
    data = load_keys()
    active = [item for item in data.get(provider, []) if item.get("status") == "active"]
    if not active:
        env_map = {
            "verboo": "VERBOO_API_KEY",
            "groq": "GROQ_API_KEY",
            "gemini": "GEMINI_API_KEY"
        }
        env_val = os.environ.get(env_map.get(provider, ""))
        if env_val:
            active = [{"key": env_val, "id": "env", "status": "active"}]
    return active

def mark_key_exhausted(provider: str, key_val: str, reason: str = "Quota limit reached"):
    data = load_keys()
    modified = False
    for item in data.get(provider, []):
        if item.get("key") == key_val:
            item["status"] = "exhausted"
            item["exhausted_reason"] = reason[:200]
            item["last_used"] = int(time.time())
            modified = True
            break
    if modified:
        save_keys(data)

def record_key_success(provider: str, key_val: str):
    data = load_keys()
    modified = False
    for item in data.get(provider, []):
        if item.get("key") == key_val:
            item["last_used"] = int(time.time())
            item["success_count"] = item.get("success_count", 0) + 1
            modified = True
            break
    if modified:
        save_keys(data)
