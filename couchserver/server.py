import base64
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
from pathlib import Path
from typing import Dict, List, Optional

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

DB_PATH = Path(__file__).with_name("couch.db")
SEED_PATH = Path(__file__).with_name("seed.sql")
USERNAME_KEY = os.environ.get("COUCHSUITE_USERNAME_SECRET", "couchsuite-secret").encode("utf-8")
PASSWORD_ITERATIONS = 120_000
PASSWORD_ALGORITHM = "sha256"
APP_VERSION = "0.1.0"


app = FastAPI(title="CouchServer", version=APP_VERSION)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


class AppIn(BaseModel):
    id: str
    name: str
    moonlight_name: str
    enabled: bool = True
    sort_order: int = 100


class AppOut(AppIn):
    pass


class UserAppOut(AppOut):
    installed: bool = True


class UserRegistration(BaseModel):
    username: str
    password: str


class UserLoginResponse(BaseModel):
    user_id: int
    username: str
    apps: List[UserAppOut]
    settings: Dict[str, object]


class UserExistsResponse(BaseModel):
    has_users: bool


class UserSettings(BaseModel):
    settings: Dict[str, object]


class InstalledUpdate(BaseModel):
    installed: bool


class AuthRequest(BaseModel):
    username: str
    password: str


def get_conn() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    with get_conn() as conn:
        conn.executescript(SEED_PATH.read_text())


def username_digest(username: str) -> str:
    normalized = username.strip().lower().encode("utf-8")
    digest = hmac.new(USERNAME_KEY, normalized, hashlib.sha256).digest()
    return base64.urlsafe_b64encode(digest).decode("utf-8")


def encrypt_username(username: str) -> str:
    data = username.encode("utf-8")
    key = hashlib.sha256(USERNAME_KEY).digest()
    encrypted = bytes(b ^ key[index % len(key)] for index, b in enumerate(data))
    return base64.urlsafe_b64encode(encrypted).decode("utf-8")


def decrypt_username(cipher_text: str) -> str:
    encrypted = base64.urlsafe_b64decode(cipher_text.encode("utf-8"))
    key = hashlib.sha256(USERNAME_KEY).digest()
    decrypted = bytes(b ^ key[index % len(key)] for index, b in enumerate(encrypted))
    return decrypted.decode("utf-8")


def hash_password(password: str, *, salt: Optional[bytes] = None) -> Dict[str, object]:
    if salt is None:
        salt = secrets.token_bytes(16)
    derived = hashlib.pbkdf2_hmac(
        PASSWORD_ALGORITHM,
        password.encode("utf-8"),
        salt,
        PASSWORD_ITERATIONS,
    )
    return {
        "hash": base64.urlsafe_b64encode(derived).decode("utf-8"),
        "salt": base64.urlsafe_b64encode(salt).decode("utf-8"),
        "iterations": PASSWORD_ITERATIONS,
    }


def verify_password(password: str, *, password_hash: str, salt: str, iterations: int) -> bool:
    salt_bytes = base64.urlsafe_b64decode(salt.encode("utf-8"))
    derived = hashlib.pbkdf2_hmac(
        PASSWORD_ALGORITHM,
        password.encode("utf-8"),
        salt_bytes,
        iterations,
    )
    candidate = base64.urlsafe_b64encode(derived).decode("utf-8")
    return secrets.compare_digest(candidate, password_hash)


def fetch_default_apps(conn: sqlite3.Connection) -> List[sqlite3.Row]:
    return conn.execute(
        "SELECT id,name,moonlight_name,enabled,sort_order FROM apps ORDER BY sort_order,name"
    ).fetchall()


def merge_user_repo(conn: sqlite3.Connection, user_id: int) -> List[UserAppOut]:
    defaults = fetch_default_apps(conn)
    overrides = {
        row["app_id"]: bool(row["installed"])
        for row in conn.execute(
            "SELECT app_id, installed FROM user_apps WHERE user_id=?",
            (user_id,),
        )
    }
    merged: List[UserAppOut] = []
    for row in defaults:
        installed = overrides.get(row["id"], bool(row["enabled"]))
        merged.append(
            UserAppOut(
                id=row["id"],
                name=row["name"],
                moonlight_name=row["moonlight_name"],
                enabled=bool(row["enabled"]),
                sort_order=row["sort_order"],
                installed=installed,
            )
        )
    return merged


def ensure_user_settings(conn: sqlite3.Connection, user_id: int) -> Dict[str, object]:
    existing = conn.execute(
        "SELECT settings_json FROM user_settings WHERE user_id=?",
        (user_id,),
    ).fetchone()
    if existing is None:
        conn.execute(
            "INSERT INTO user_settings(user_id, settings_json) VALUES(?, '{}')",
            (user_id,),
        )
        conn.commit()
        return {}
    try:
        return json.loads(existing["settings_json"])
    except json.JSONDecodeError:
        return {}


def update_user_settings(conn: sqlite3.Connection, user_id: int, settings: Dict[str, object]) -> Dict[str, object]:
    payload = json.dumps(settings)
    conn.execute(
        "INSERT INTO user_settings(user_id, settings_json) VALUES(?, ?)"
        " ON CONFLICT(user_id) DO UPDATE SET settings_json=excluded.settings_json",
        (user_id, payload),
    )
    conn.commit()
    return settings


def fetch_user_record(conn: sqlite3.Connection, username: str) -> Optional[sqlite3.Row]:
    digest = username_digest(username)
    return conn.execute(
        "SELECT id, username_cipher, password_hash, password_salt, password_iterations "
        "FROM users WHERE username_digest=?",
        (digest,),
    ).fetchone()


def build_login_response(conn: sqlite3.Connection, user_row: sqlite3.Row, username_input: str) -> UserLoginResponse:
    repo = merge_user_repo(conn, user_row["id"])
    settings = ensure_user_settings(conn, user_row["id"])
    stored_username = decrypt_username(user_row["username_cipher"])
    username_value = stored_username or username_input
    return UserLoginResponse(
        user_id=user_row["id"],
        username=username_value,
        apps=repo,
        settings=settings,
    )


@app.on_event("startup")
def startup() -> None:
    if not DB_PATH.exists():
        init_db()
    else:
        # Ensure new tables exist when upgrading an existing database
        init_db()


@app.get("/health")
def health() -> Dict[str, bool]:
    return {"ok": True}


@app.get("/version")
def version() -> Dict[str, str]:
    return {"server": APP_VERSION}


@app.get("/apps", response_model=List[AppOut])
def list_apps(enabled: Optional[bool] = None) -> List[AppOut]:
    query = "SELECT id,name,moonlight_name,enabled,sort_order FROM apps"
    params: List[object] = []
    if enabled is not None:
        query += " WHERE enabled=?"
        params.append(1 if enabled else 0)
    query += " ORDER BY sort_order,name"
    with get_conn() as conn:
        rows = conn.execute(query, params).fetchall()
        return [
            AppOut(
                id=row["id"],
                name=row["name"],
                moonlight_name=row["moonlight_name"],
                enabled=bool(row["enabled"]),
                sort_order=row["sort_order"],
            )
            for row in rows
        ]


@app.get("/repo/default", response_model=List[AppOut])
def default_repo() -> List[AppOut]:
    with get_conn() as conn:
        rows = fetch_default_apps(conn)
        return [
            AppOut(
                id=row["id"],
                name=row["name"],
                moonlight_name=row["moonlight_name"],
                enabled=bool(row["enabled"]),
                sort_order=row["sort_order"],
            )
            for row in rows
        ]


@app.get("/users/exists", response_model=UserExistsResponse)
def users_exist() -> UserExistsResponse:
    with get_conn() as conn:
        row = conn.execute("SELECT COUNT(1) FROM users").fetchone()
        return UserExistsResponse(has_users=bool(row[0]))


@app.post("/users", response_model=UserLoginResponse)
def create_user(payload: UserRegistration) -> UserLoginResponse:
    username = payload.username.strip()
    if not username:
        raise HTTPException(status_code=400, detail="username required")
    if not payload.password:
        raise HTTPException(status_code=400, detail="password required")
    digest = username_digest(username)
    encrypted = encrypt_username(username)
    password_record = hash_password(payload.password)
    with get_conn() as conn:
        existing = conn.execute(
            "SELECT 1 FROM users WHERE username_digest=?",
            (digest,),
        ).fetchone()
        if existing is not None:
            raise HTTPException(status_code=409, detail="username already exists")
        cur = conn.execute(
            "INSERT INTO users(username_digest, username_cipher, password_hash, password_salt, password_iterations) "
            "VALUES(?,?,?,?,?)",
            (
                digest,
                encrypted,
                password_record["hash"],
                password_record["salt"],
                password_record["iterations"],
            ),
        )
        user_id = cur.lastrowid
        conn.commit()
        row = conn.execute(
            "SELECT id, username_cipher, password_hash, password_salt, password_iterations FROM users WHERE id=?",
            (user_id,),
        ).fetchone()
        return build_login_response(conn, row, username)


@app.post("/auth/login", response_model=UserLoginResponse)
def login(payload: AuthRequest) -> UserLoginResponse:
    username = payload.username.strip()
    with get_conn() as conn:
        user_row = fetch_user_record(conn, username)
        if user_row is None:
            raise HTTPException(status_code=401, detail="invalid credentials")
        if not verify_password(
            payload.password,
            password_hash=user_row["password_hash"],
            salt=user_row["password_salt"],
            iterations=user_row["password_iterations"],
        ):
            raise HTTPException(status_code=401, detail="invalid credentials")
        return build_login_response(conn, user_row, username)


@app.get("/users/{user_id}/apps", response_model=List[UserAppOut])
def user_apps(user_id: int) -> List[UserAppOut]:
    with get_conn() as conn:
        owned = conn.execute(
            "SELECT 1 FROM users WHERE id=?",
            (user_id,),
        ).fetchone()
        if owned is None:
            raise HTTPException(status_code=404, detail="user not found")
        return merge_user_repo(conn, user_id)


@app.put("/users/{user_id}/apps/{app_id}", response_model=UserAppOut)
def update_user_app(user_id: int, app_id: str, payload: InstalledUpdate) -> UserAppOut:
    with get_conn() as conn:
        user_exists = conn.execute("SELECT 1 FROM users WHERE id=?", (user_id,)).fetchone()
        if user_exists is None:
            raise HTTPException(status_code=404, detail="user not found")
        app_exists = conn.execute("SELECT 1 FROM apps WHERE id=?", (app_id,)).fetchone()
        if app_exists is None:
            raise HTTPException(status_code=404, detail="app not found")
        conn.execute(
            "INSERT INTO user_apps(user_id, app_id, installed) VALUES(?,?,?)"
            " ON CONFLICT(user_id, app_id) DO UPDATE SET installed=excluded.installed",
            (user_id, app_id, 1 if payload.installed else 0),
        )
        conn.commit()
        repo = merge_user_repo(conn, user_id)
        for entry in repo:
            if entry.id == app_id:
                return entry
        raise HTTPException(status_code=500, detail="failed to update app")


@app.get("/users/{user_id}/settings", response_model=UserSettings)
def get_settings(user_id: int) -> UserSettings:
    with get_conn() as conn:
        exists = conn.execute("SELECT 1 FROM users WHERE id=?", (user_id,)).fetchone()
        if exists is None:
            raise HTTPException(status_code=404, detail="user not found")
        settings = ensure_user_settings(conn, user_id)
        return UserSettings(settings=settings)


@app.put("/users/{user_id}/settings", response_model=UserSettings)
def put_settings(user_id: int, payload: UserSettings) -> UserSettings:
    with get_conn() as conn:
        exists = conn.execute("SELECT 1 FROM users WHERE id=?", (user_id,)).fetchone()
        if exists is None:
            raise HTTPException(status_code=404, detail="user not found")
        updated = update_user_settings(conn, user_id, payload.settings)
        return UserSettings(settings=updated)


@app.post("/apps", response_model=AppOut)
def create_app(app_in: AppIn) -> AppOut:
    with get_conn() as conn:
        try:
            conn.execute(
                "INSERT INTO apps(id,name,moonlight_name,enabled,sort_order) VALUES(?,?,?,?,?)",
                (
                    app_in.id,
                    app_in.name,
                    app_in.moonlight_name,
                    1 if app_in.enabled else 0,
                    app_in.sort_order,
                ),
            )
            conn.commit()
            return app_in
        except sqlite3.IntegrityError:
            raise HTTPException(status_code=409, detail="id already exists")


@app.put("/apps/{app_id}", response_model=AppOut)
def update_app(app_id: str, app_in: AppIn) -> AppOut:
    with get_conn() as conn:
        cur = conn.execute(
            "UPDATE apps SET name=?, moonlight_name=?, enabled=?, sort_order=? WHERE id=?",
            (
                app_in.name,
                app_in.moonlight_name,
                1 if app_in.enabled else 0,
                app_in.sort_order,
                app_id,
            ),
        )
        conn.commit()
        if cur.rowcount == 0:
            raise HTTPException(status_code=404, detail="not found")
        return app_in


@app.delete("/apps/{app_id}")
def delete_app(app_id: str) -> Dict[str, str]:
    with get_conn() as conn:
        cur = conn.execute("DELETE FROM apps WHERE id=?", (app_id,))
        conn.commit()
        if cur.rowcount == 0:
            raise HTTPException(status_code=404, detail="not found")
        return {"deleted": app_id}


@app.post("/warm/{app_id}")
def warm(app_id: str) -> Dict[str, str]:
    return {"queued": app_id}
