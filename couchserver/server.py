
import sqlite3
from pathlib import Path
from typing import List, Optional
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

DB_PATH = Path(__file__).with_name("couch.db")

app = FastAPI(title="CouchServer", version="0.1.0")

class AppIn(BaseModel):
    id: str
    name: str
    moonlight_name: str
    enabled: bool = True
    sort_order: int = 100

class AppOut(AppIn):
    pass

def get_conn():
    return sqlite3.connect(DB_PATH)

def init_db():
    with get_conn() as conn:
        conn.executescript(Path(__file__).with_name("seed.sql").read_text())

@app.on_event("startup")
def startup():
    if not DB_PATH.exists():
        init_db()

@app.get("/health")
def health():
    return {"ok": True}

@app.get("/apps", response_model=List[AppOut])
def list_apps(enabled: Optional[bool] = None):
    q = "SELECT id,name,moonlight_name,enabled,sort_order FROM apps"
    params = []
    if enabled is not None:
        q += " WHERE enabled=?"
        params.append(1 if enabled else 0)
    q += " ORDER BY sort_order,name"
    with get_conn() as conn:
        rows = conn.execute(q, params).fetchall()
        return [{
            "id": r[0], "name": r[1], "moonlight_name": r[2],
            "enabled": bool(r[3]), "sort_order": r[4]
        } for r in rows]

@app.post("/apps", response_model=AppOut)
def create_app(app_in: AppIn):
    with get_conn() as conn:
        try:
            conn.execute(
                "INSERT INTO apps(id,name,moonlight_name,enabled,sort_order) VALUES(?,?,?,?,?)",
                (app_in.id, app_in.name, app_in.moonlight_name, 1 if app_in.enabled else 0, app_in.sort_order)
            )
            conn.commit()
            return app_in
        except sqlite3.IntegrityError:
            raise HTTPException(status_code=409, detail="id already exists")

@app.put("/apps/{app_id}", response_model=AppOut)
def update_app(app_id: str, app_in: AppIn):
    with get_conn() as conn:
        cur = conn.execute(
            "UPDATE apps SET name=?, moonlight_name=?, enabled=?, sort_order=? WHERE id=?",
            (app_in.name, app_in.moonlight_name, 1 if app_in.enabled else 0, app_in.sort_order, app_id)
        )
        conn.commit()
        if cur.rowcount == 0:
            raise HTTPException(status_code=404, detail="not found")
        return app_in

@app.delete("/apps/{app_id}")
def delete_app(app_id: str):
    with get_conn() as conn:
        cur = conn.execute("DELETE FROM apps WHERE id=?", (app_id,))
        conn.commit()
        if cur.rowcount == 0:
            raise HTTPException(status_code=404, detail="not found")
        return {"deleted": app_id}

@app.post("/warm/{app_id}")
def warm(app_id: str):
    # Stub for later cache-building on the host
    return {"queued": app_id}
