
# CouchServer (FastAPI + SQLite)

Tiny REST server to feed your CouchLauncher app with tiles.

## Quick start (macOS / Linux)

```bash
cd couchserver
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn server:app --reload --host 0.0.0.0 --port 8080
```

Test:
```bash
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/apps
```

### API
- `GET /apps` → list tiles
- `POST /apps` (JSON body) → create tile
- `PUT /apps/{id}` → update tile
- `DELETE /apps/{id}` → delete tile
- `POST /warm/{id}` → (stub) request warm-up

Data lives in `couch.db` next to `server.py` and is seeded from `seed.sql` on first run.
