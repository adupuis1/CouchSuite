import base64
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
from uuid import uuid4

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
    description: Optional[str] = None
    cover_url: Optional[str] = None
    chart_rank: Optional[int] = None
    chart_date: Optional[str] = None
    game_id: Optional[int] = None


class UserAppOut(AppOut):
    installed: bool = False
    owned: bool = False
    steam_appid: Optional[int] = None


class UserRegistration(BaseModel):
    username: str
    password: str


class OrgOut(BaseModel):
    id: int
    slug: str
    name: str
    role: str


class UserLoginResponse(BaseModel):
    user_id: int
    username: str
    apps: List[UserAppOut]
    settings: Dict[str, object]
    orgs: List[OrgOut]
    token: str


class UserExistsResponse(BaseModel):
    has_users: bool


class UserSettings(BaseModel):
    settings: Dict[str, object]


class InstalledUpdate(BaseModel):
    installed: bool


class AuthRequest(BaseModel):
    username: str
    password: str


class OrgCreate(BaseModel):
    slug: str
    name: str


class OrgMemberUpsert(BaseModel):
    user_id: int
    role: Optional[str] = "member"


class OrgMemberOut(BaseModel):
    user_id: int
    username: str
    role: str


class GameSummary(BaseModel):
    id: int
    slug: str
    name: str
    description: Optional[str] = None
    rating: Optional[float] = None
    cover_url: Optional[str] = None
    external_ids: Dict[str, str] = {}


class LibraryEntry(BaseModel):
    org_id: int
    user_id: int
    game: GameSummary
    ownership_source: str
    proof_type: Optional[str] = None
    proof_value: Optional[str] = None
    verified_at: Optional[str] = None
    install_ready: bool = False


class ChartEntry(BaseModel):
    rank: int
    chart_date: str
    steam_appid: Optional[int]
    game: GameSummary
    install_ready: bool = False


class SteamLinkRequest(BaseModel):
    user_id: int
    org_id: int
    steam_id: str
    display_name: Optional[str] = None


class SteamOwnershipRequest(BaseModel):
    user_id: int
    org_id: int
    steam_id: str
    game_ids: Optional[List[int]] = None


class SessionCreateRequest(BaseModel):
    org_id: int
    user_id: int
    game_id: int


class SessionOut(BaseModel):
    id: int
    org_id: int
    user_id: int
    game_id: int
    status: str
    stream_url: Optional[str] = None
    created_at: str
    updated_at: str


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


def ensure_default_org(conn: sqlite3.Connection) -> int:
    row = conn.execute(
        "SELECT id FROM orgs WHERE slug=?",
        ("default",),
    ).fetchone()
    if row is not None:
        return int(row["id"])
    cur = conn.execute(
        "INSERT INTO orgs(slug, name) VALUES(?, ?)",
        ("default", "CouchSuite Default"),
    )
    conn.commit()
    return cur.lastrowid


def fetch_orgs_for_user(conn: sqlite3.Connection, user_id: int) -> List[OrgOut]:
    rows = conn.execute(
        "SELECT m.org_id, m.role, o.slug, o.name FROM memberships m "
        "JOIN orgs o ON o.id=m.org_id WHERE m.user_id=? ORDER BY o.name",
        (user_id,),
    ).fetchall()
    return [
        OrgOut(id=row["org_id"], slug=row["slug"], name=row["name"], role=row["role"])
        for row in rows
    ]


def user_in_org(conn: sqlite3.Connection, user_id: int, org_id: int) -> bool:
    row = conn.execute(
        "SELECT 1 FROM memberships WHERE org_id=? AND user_id=?",
        (org_id, user_id),
    ).fetchone()
    return row is not None


def fetch_game_map(conn: sqlite3.Connection) -> Dict[int, sqlite3.Row]:
    rows = conn.execute(
        "SELECT id, slug, name, description, rating, cover_url FROM games"
    ).fetchall()
    return {row["id"]: row for row in rows}


def fetch_external_ids(conn: sqlite3.Connection, game_ids: List[int]) -> Dict[int, Dict[str, str]]:
    if not game_ids:
        return {}
    placeholder = ",".join(["?"] * len(game_ids))
    rows = conn.execute(
        f"SELECT game_id, platform, external_id FROM game_external_ids WHERE game_id IN ({placeholder})",
        game_ids,
    ).fetchall()
    mapping: Dict[int, Dict[str, str]] = {}
    for row in rows:
        mapping.setdefault(row["game_id"], {})[row["platform"]] = row["external_id"]
    return mapping


def fetch_install_map(conn: sqlite3.Connection, org_id: Optional[int]) -> Dict[int, bool]:
    if org_id is None:
        rows = conn.execute("SELECT DISTINCT game_id FROM downloaded_games").fetchall()
    else:
        rows = conn.execute(
            "SELECT DISTINCT game_id FROM downloaded_games WHERE org_id=?",
            (org_id,),
        ).fetchall()
    return {row["game_id"]: True for row in rows}


def fetch_ownership_map(conn: sqlite3.Connection, org_id: Optional[int], user_id: Optional[int]) -> Dict[int, sqlite3.Row]:
    if org_id is None or user_id is None:
        return {}
    rows = conn.execute(
        "SELECT game_id, ownership_source, proof_type, proof_value, verified_at FROM user_game_library "
        "WHERE org_id=? AND user_id=?",
        (org_id, user_id),
    ).fetchall()
    return {row["game_id"]: row for row in rows}


def fetch_apps_map(conn: sqlite3.Connection) -> Dict[str, sqlite3.Row]:
    rows = conn.execute(
        "SELECT id, name, moonlight_name, enabled, sort_order FROM apps"
    ).fetchall()
    return {row["id"]: row for row in rows}


def build_game_summary(row: sqlite3.Row, external_ids: Dict[str, str]) -> GameSummary:
    return GameSummary(
        id=row["id"],
        slug=row["slug"],
        name=row["name"],
        description=row["description"],
        rating=row["rating"],
        cover_url=row["cover_url"],
        external_ids=external_ids,
    )


def build_catalog(
    conn: sqlite3.Connection,
    *,
    user_id: Optional[int] = None,
    org_id: Optional[int] = None,
    chart_date: Optional[str] = None,
) -> List[UserAppOut]:
    params: Tuple[Any, ...]
    if chart_date:
        params = (chart_date,)
        chart_rows = conn.execute(
            "SELECT chart_date, rank, steam_appid, game_id, name FROM charts_top10 WHERE chart_date=? ORDER BY rank",
            params,
        ).fetchall()
    else:
        latest = conn.execute(
            "SELECT chart_date FROM charts_top10 ORDER BY chart_date DESC LIMIT 1"
        ).fetchone()
        if latest is None:
            chart_rows = []
        else:
            params = (latest["chart_date"],)
            chart_rows = conn.execute(
                "SELECT chart_date, rank, steam_appid, game_id, name FROM charts_top10 WHERE chart_date=? ORDER BY rank",
                params,
            ).fetchall()

    games = fetch_game_map(conn)
    game_ids = [row["game_id"] for row in chart_rows if row["game_id"] is not None]
    install_map = fetch_install_map(conn, org_id)
    ownership_map = fetch_ownership_map(conn, org_id, user_id)
    apps_map = fetch_apps_map(conn)

    catalog: List[UserAppOut] = []
    for index, chart in enumerate(chart_rows, start=1):
        game_row = games.get(chart["game_id"]) if chart["game_id"] else None
        if game_row is None:
            # fallback when chart entry has no associated game record
            slug = f"chart-{chart['chart_date']}-{chart['rank']}"
            catalog.append(
                UserAppOut(
                    id=slug,
                    name=chart["name"],
                    moonlight_name=chart["name"],
                    enabled=True,
                    sort_order=index,
                    installed=False,
                    owned=False,
                    chart_rank=chart["rank"],
                    chart_date=chart["chart_date"],
                    description=None,
                    cover_url=None,
                    game_id=None,
                    steam_appid=chart["steam_appid"],
                )
            )
            continue

        slug = game_row["slug"] or f"game-{game_row['id']}"
        app_row = apps_map.get(slug)
        moonlight_name = app_row["moonlight_name"] if app_row else game_row["name"]
        sort_order = app_row["sort_order"] if app_row else chart["rank"]
        enabled = bool(app_row["enabled"]) if app_row else True
        install_ready = bool(install_map.get(game_row["id"], False))
        ownership_row = ownership_map.get(game_row["id"])
        catalog.append(
            UserAppOut(
                id=slug,
                name=game_row["name"],
                moonlight_name=moonlight_name,
                enabled=enabled,
                sort_order=sort_order,
                installed=install_ready,
                owned=ownership_row is not None,
                chart_rank=chart["rank"],
                chart_date=chart["chart_date"],
                description=game_row["description"],
                cover_url=game_row["cover_url"],
                game_id=game_row["id"],
                steam_appid=chart["steam_appid"],
            )
        )

    if not catalog:
        # fall back to plain apps table so client always has content
        for row in apps_map.values():
            catalog.append(
                UserAppOut(
                    id=row["id"],
                    name=row["name"],
                    moonlight_name=row["moonlight_name"],
                    enabled=bool(row["enabled"]),
                    sort_order=row["sort_order"],
                installed=bool(row["enabled"]),
                owned=False,
                chart_rank=None,
                chart_date=None,
                description=None,
                cover_url=None,
                game_id=None,
                steam_appid=None,
            )
        )

    return catalog


def serialize_session(row: sqlite3.Row) -> SessionOut:
    return SessionOut(
        id=row["id"],
        org_id=row["org_id"],
        user_id=row["user_id"],
        game_id=row["game_id"],
        status=row["status"],
        stream_url=row["stream_url"],
        created_at=row["created_at"],
        updated_at=row["updated_at"],
    )


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


def issue_token(user_id: int, username: str) -> str:
    payload = f"{user_id}:{username}:{APP_VERSION}:{secrets.token_hex(8)}"
    digest = hashlib.sha256(payload.encode("utf-8")).digest()
    return base64.urlsafe_b64encode(digest).decode("utf-8").rstrip("=")


def build_login_response(conn: sqlite3.Connection, user_row: sqlite3.Row, username_input: str) -> UserLoginResponse:
    settings = ensure_user_settings(conn, user_row["id"])
    stored_username = decrypt_username(user_row["username_cipher"])
    username_value = stored_username or username_input
    orgs = fetch_orgs_for_user(conn, user_row["id"])
    primary_org = orgs[0].id if orgs else None
    catalog = build_catalog(conn, user_id=user_row["id"], org_id=primary_org)
    token = issue_token(user_row["id"], username_value)
    return UserLoginResponse(
        user_id=user_row["id"],
        username=username_value,
        apps=catalog,
        settings=settings,
        orgs=orgs,
        token=token,
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


@app.get("/apps", response_model=List[UserAppOut])
def list_apps(enabled: Optional[bool] = None, chart_date: Optional[str] = None) -> List[UserAppOut]:
    with get_conn() as conn:
        catalog = build_catalog(conn, chart_date=chart_date)
    if enabled is not None:
        catalog = [app for app in catalog if app.enabled == enabled]
    return catalog


@app.get("/repo/default", response_model=List[UserAppOut])
def default_repo(chart_date: Optional[str] = None) -> List[UserAppOut]:
    return list_apps(chart_date=chart_date)


@app.get("/charts/top10", response_model=List[ChartEntry])
def charts_top10(date: Optional[str] = None, org_id: Optional[int] = None) -> List[ChartEntry]:
    with get_conn() as conn:
        if date:
            chart_date = date
        else:
            latest = conn.execute(
                "SELECT chart_date FROM charts_top10 ORDER BY chart_date DESC LIMIT 1"
            ).fetchone()
            if latest is None:
                return []
            chart_date = latest["chart_date"]
        rows = conn.execute(
            "SELECT chart_date, rank, steam_appid, game_id, name FROM charts_top10 WHERE chart_date=? ORDER BY rank",
            (chart_date,),
        ).fetchall()
        game_ids = [row["game_id"] for row in rows if row["game_id"] is not None]
        game_map = fetch_game_map(conn)
        external_map = fetch_external_ids(conn, game_ids)
        install_map = fetch_install_map(conn, org_id)
        entries: List[ChartEntry] = []
        for row in rows:
            game_id_value = row["game_id"]
            if game_id_value and game_id_value in game_map:
                game_row = game_map[game_id_value]
                summary = build_game_summary(game_row, external_map.get(game_id_value, {}))
            else:
                summary = GameSummary(
                    id=0,
                    slug=f"chart-{row['chart_date']}-{row['rank']}",
                    name=row["name"],
                    description=None,
                    rating=None,
                    cover_url=None,
                    external_ids={},
                )
            install_ready = bool(install_map.get(game_id_value, False)) if game_id_value else False
            entries.append(
                ChartEntry(
                    rank=row["rank"],
                    chart_date=row["chart_date"],
                    steam_appid=row["steam_appid"],
                    game=summary,
                    install_ready=install_ready,
                )
            )
        return entries


@app.get("/games/{game_id}", response_model=GameSummary)
def game_details(game_id: int) -> GameSummary:
    with get_conn() as conn:
        row = conn.execute(
            "SELECT id, slug, name, description, rating, cover_url FROM games WHERE id=?",
            (game_id,),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="game not found")
        external_map = fetch_external_ids(conn, [game_id])
        return build_game_summary(row, external_map.get(game_id, {}))


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
        total_users = conn.execute("SELECT COUNT(1) FROM users").fetchone()[0]
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
        default_org_id = ensure_default_org(conn)
        role = "owner" if total_users == 0 else "member"
        conn.execute(
            "INSERT OR IGNORE INTO memberships(org_id, user_id, role) VALUES(?,?,?)",
            (default_org_id, user_id, role),
        )
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


@app.get("/orgs", response_model=List[OrgOut])
def list_orgs() -> List[OrgOut]:
    with get_conn() as conn:
        rows = conn.execute("SELECT id, slug, name FROM orgs ORDER BY name").fetchall()
        return [OrgOut(id=row["id"], slug=row["slug"], name=row["name"], role="member") for row in rows]


@app.post("/orgs", response_model=OrgOut)
def create_org(payload: OrgCreate) -> OrgOut:
    slug = payload.slug.strip()
    name = payload.name.strip()
    if not slug or not name:
        raise HTTPException(status_code=400, detail="slug and name required")
    with get_conn() as conn:
        try:
            cur = conn.execute(
                "INSERT INTO orgs(slug, name) VALUES(?, ?)",
                (slug, name),
            )
            conn.commit()
        except sqlite3.IntegrityError as exc:
            raise HTTPException(status_code=409, detail="org slug already exists") from exc
        return OrgOut(id=cur.lastrowid, slug=slug, name=name, role="member")


@app.get("/orgs/{org_id}/members", response_model=List[OrgMemberOut])
def list_org_members(org_id: int) -> List[OrgMemberOut]:
    with get_conn() as conn:
        exists = conn.execute("SELECT 1 FROM orgs WHERE id=?", (org_id,)).fetchone()
        if exists is None:
            raise HTTPException(status_code=404, detail="org not found")
        rows = conn.execute(
            "SELECT m.user_id, m.role, u.username_cipher FROM memberships m "
            "JOIN users u ON u.id=m.user_id WHERE m.org_id=? ORDER BY m.role DESC, u.id",
            (org_id,),
        ).fetchall()
        members: List[OrgMemberOut] = []
        for row in rows:
            members.append(
                OrgMemberOut(
                    user_id=row["user_id"],
                    username=decrypt_username(row["username_cipher"]),
                    role=row["role"],
                )
            )
        return members


@app.post("/orgs/{org_id}/members", response_model=OrgMemberOut)
def add_org_member(org_id: int, payload: OrgMemberUpsert) -> OrgMemberOut:
    role = payload.role or "member"
    with get_conn() as conn:
        org_exists = conn.execute("SELECT 1 FROM orgs WHERE id=?", (org_id,)).fetchone()
        if org_exists is None:
            raise HTTPException(status_code=404, detail="org not found")
        user_row = conn.execute(
            "SELECT id, username_cipher FROM users WHERE id=?",
            (payload.user_id,),
        ).fetchone()
        if user_row is None:
            raise HTTPException(status_code=404, detail="user not found")
        conn.execute(
            "INSERT OR IGNORE INTO memberships(org_id, user_id, role) VALUES(?,?,?)",
            (org_id, payload.user_id, role),
        )
        conn.execute(
            "UPDATE memberships SET role=? WHERE org_id=? AND user_id=?",
            (role, org_id, payload.user_id),
        )
        conn.commit()
        return OrgMemberOut(
            user_id=payload.user_id,
            username=decrypt_username(user_row["username_cipher"]),
            role=role,
        )


@app.get("/users/{user_id}/apps", response_model=List[UserAppOut])
def user_apps(user_id: int, org_id: Optional[int] = None) -> List[UserAppOut]:
    with get_conn() as conn:
        owned = conn.execute(
            "SELECT 1 FROM users WHERE id=?",
            (user_id,),
        ).fetchone()
        if owned is None:
            raise HTTPException(status_code=404, detail="user not found")
        if org_id is None:
            orgs = fetch_orgs_for_user(conn, user_id)
            org_id = orgs[0].id if orgs else None
        return build_catalog(conn, user_id=user_id, org_id=org_id)


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
        orgs = fetch_orgs_for_user(conn, user_id)
        org_id = orgs[0].id if orgs else None
        repo = build_catalog(conn, user_id=user_id, org_id=org_id)
        for entry in repo:
            if entry.id == app_id:
                return entry
        raise HTTPException(status_code=500, detail="failed to update app")


@app.get("/users/{user_id}/library", response_model=List[LibraryEntry])
def user_library(user_id: int, org_id: Optional[int] = None) -> List[LibraryEntry]:
    with get_conn() as conn:
        exists = conn.execute("SELECT 1 FROM users WHERE id=?", (user_id,)).fetchone()
        if exists is None:
            raise HTTPException(status_code=404, detail="user not found")
        if org_id is None:
            orgs = fetch_orgs_for_user(conn, user_id)
            if not orgs:
                return []
            org_id = orgs[0].id
        if not user_in_org(conn, user_id, org_id):
            raise HTTPException(status_code=403, detail="user not in organization")
        rows = conn.execute(
            "SELECT game_id, ownership_source, proof_type, proof_value, verified_at FROM user_game_library "
            "WHERE org_id=? AND user_id=?",
            (org_id, user_id),
        ).fetchall()
        if not rows:
            return []
        game_ids = [row["game_id"] for row in rows]
        game_map = fetch_game_map(conn)
        external_map = fetch_external_ids(conn, game_ids)
        install_map = fetch_install_map(conn, org_id)
        entries: List[LibraryEntry] = []
        for row in rows:
            game_row = game_map.get(row["game_id"])
            if game_row is None:
                continue
            summary = build_game_summary(game_row, external_map.get(row["game_id"], {}))
            entries.append(
                LibraryEntry(
                    org_id=org_id,
                    user_id=user_id,
                    game=summary,
                    ownership_source=row["ownership_source"],
                    proof_type=row["proof_type"],
                    proof_value=row["proof_value"],
                    verified_at=row["verified_at"],
                    install_ready=bool(install_map.get(row["game_id"], False)),
                )
            )
        return entries


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


@app.post("/auth/link/steam")
def link_steam_account(payload: SteamLinkRequest) -> Dict[str, object]:
    with get_conn() as conn:
        if not user_in_org(conn, payload.user_id, payload.org_id):
            raise HTTPException(status_code=403, detail="user not in organization")
        metadata = {
            "steam_id": payload.steam_id,
            "linked_at": datetime.utcnow().isoformat(),
        }
        if payload.display_name:
            metadata["display_name"] = payload.display_name
        conn.execute(
            "INSERT INTO user_accounts(org_id, user_id, platform, account_id, display_name, metadata_json) "
            "VALUES(?,?,?,?,?,?) "
            "ON CONFLICT(org_id, user_id, platform) DO UPDATE SET account_id=excluded.account_id, display_name=excluded.display_name, metadata_json=excluded.metadata_json",
            (payload.org_id, payload.user_id, "steam", payload.steam_id, payload.display_name, json.dumps(metadata)),
        )
        conn.commit()
        return {"linked": True, "platform": "steam", "org_id": payload.org_id, "user_id": payload.user_id}


@app.post("/ownership/verify/steam")
def verify_steam_ownership(payload: SteamOwnershipRequest) -> Dict[str, object]:
    with get_conn() as conn:
        if not user_in_org(conn, payload.user_id, payload.org_id):
            raise HTTPException(status_code=403, detail="user not in organization")
        if payload.game_ids:
            game_ids = payload.game_ids
        else:
            rows = conn.execute(
                "SELECT game_id FROM game_external_ids WHERE platform='steam'"
            ).fetchall()
            game_ids = [row["game_id"] for row in rows]
        if not game_ids:
            return {"verified": [], "library": []}
        timestamp = datetime.utcnow().isoformat()
        verified: List[int] = []
        for game_id in game_ids:
            external = conn.execute(
                "SELECT external_id FROM game_external_ids WHERE game_id=? AND platform='steam'",
                (game_id,),
            ).fetchone()
            external_id = external["external_id"] if external else None
            proof = {
                "steam_id": payload.steam_id,
                "external_id": external_id,
                "verified_at": timestamp,
            }
            conn.execute(
                "INSERT INTO user_game_library(org_id, user_id, game_id, platform, external_id, ownership_source, proof_type, proof_value, verified_at) "
                "VALUES(?,?,?,?,?,?,?,?,?) "
                "ON CONFLICT(org_id, user_id, game_id) DO UPDATE SET proof_value=excluded.proof_value, verified_at=excluded.verified_at, ownership_source=excluded.ownership_source",
                (
                    payload.org_id,
                    payload.user_id,
                    game_id,
                    "steam",
                    external_id,
                    "steam",
                    "steam",
                    json.dumps(proof),
                    timestamp,
                ),
            )
            verified.append(game_id)
        conn.commit()
    library = user_library(payload.user_id, payload.org_id)
    return {"verified": verified, "library": library}


@app.post("/sessions", response_model=SessionOut)
def create_session(payload: SessionCreateRequest) -> SessionOut:
    with get_conn() as conn:
        if not user_in_org(conn, payload.user_id, payload.org_id):
            raise HTTPException(status_code=403, detail="user not in organization")
        game_row = conn.execute(
            "SELECT id FROM games WHERE id=?",
            (payload.game_id,),
        ).fetchone()
        if game_row is None:
            raise HTTPException(status_code=404, detail="game not found")
        install_map = fetch_install_map(conn, payload.org_id)
        if not install_map.get(payload.game_id):
            raise HTTPException(status_code=409, detail="game not installed for organization")
        stream_url = f"https://stream.couchsuite.local/sessions/{uuid4()}"
        now = datetime.utcnow().isoformat()
        cur = conn.execute(
            "INSERT INTO sessions(org_id, user_id, game_id, status, stream_url, created_at, updated_at) "
            "VALUES(?,?,?,?,?,?,?)",
            (payload.org_id, payload.user_id, payload.game_id, "provisioning", stream_url, now, now),
        )
        conn.commit()
        row = conn.execute("SELECT * FROM sessions WHERE id=?", (cur.lastrowid,)).fetchone()
        return serialize_session(row)


@app.get("/sessions/{session_id}", response_model=SessionOut)
def get_session(session_id: int) -> SessionOut:
    with get_conn() as conn:
        row = conn.execute("SELECT * FROM sessions WHERE id=?", (session_id,)).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="session not found")
        return serialize_session(row)


@app.delete("/sessions/{session_id}", response_model=SessionOut)
def terminate_session(session_id: int) -> SessionOut:
    with get_conn() as conn:
        row = conn.execute("SELECT * FROM sessions WHERE id=?", (session_id,)).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="session not found")
        now = datetime.utcnow().isoformat()
        conn.execute(
            "UPDATE sessions SET status=?, updated_at=? WHERE id=?",
            ("terminated", now, session_id),
        )
        conn.commit()
        updated = conn.execute("SELECT * FROM sessions WHERE id=?", (session_id,)).fetchone()
        return serialize_session(updated)


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
