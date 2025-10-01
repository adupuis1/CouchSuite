
# CouchServer (FastAPI + SQLite)

Tiny REST server to feed your CouchLauncher app with tiles, user accounts, and personalised repositories.

## Quick start (macOS / Linux)

```bash
cd couchserver
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn server:app --reload --host 0.0.0.0 --port 8080
```

Smoke tests:
```bash
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/version
curl http://127.0.0.1:8080/apps
```

Data lives in `couch.db` next to `server.py` and is seeded from `seed.sql` on first run (or whenever new tables are added). User credentials are stored with salted PBKDF2 hashes and usernames are encrypted with an app secret (`COUCHSUITE_USERNAME_SECRET`).

## API overview

### Core
- `GET /health` → basic readiness probe
- `GET /version` → `{ "server": "0.1.0" }`
- `GET /apps?enabled=true|false` → default repository tiles (filter optional)
- `POST /apps`, `PUT /apps/{id}`, `DELETE /apps/{id}` → manage default repo entries
- `POST /warm/{id}` → stub queue hook for future host warmups

### Users & personalised repos
- `GET /users/exists` → `{ "has_users": bool }` to know whether to prompt for account creation
- `POST /users` → register a new user (username + password)
- `POST /auth/login` → authenticate and receive `{ user_id, username, apps, settings }`
- `GET /users/{user_id}/apps` → merged default + user-installed state
- `PUT /users/{user_id}/apps/{app_id}` → toggle installed flag for an app
- `GET /users/{user_id}/settings` / `PUT ...` → JSON blob of per-user launcher settings

All endpoints include CORS headers for future web tooling. Auth responses provide the merged repository so the launcher can switch from the shared default repo to the per-user variant immediately after login. Passwords are only ever sent during registration or login.

## macOS launchd helper

For unattended restarts on macOS, drop these files on the user account that should own the process:

1. `~/bin/run-couchserver.sh`
   ```bash
   #!/usr/bin/env bash
   cd "$HOME/Documents/CouchSuite/couchserver" || exit 1
   source "$HOME/Documents/CouchSuite/couchserver/.venv/bin/activate"
   exec uvicorn server:app --host 0.0.0.0 --port 8080 >> "$HOME/Library/Logs/couchserver.log" 2>&1
   ```
   Make it executable with `chmod +x ~/bin/run-couchserver.sh`.

2. `~/Library/LaunchAgents/com.couch.server.plist`
   ```xml
   <?xml version="1.0" encoding="UTF-8"?>
   <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
   <plist version="1.0">
   <dict>
     <key>Label</key><string>com.couch.server</string>
     <key>ProgramArguments</key>
     <array>
       <string>/bin/bash</string>
       <string>$HOME/bin/run-couchserver.sh</string>
     </array>
     <key>RunAtLoad</key><true/>
     <key>KeepAlive</key><true/>
     <key>StandardOutPath</key><string>$HOME/Library/Logs/couchserver.log</string>
     <key>StandardErrorPath</key><string>$HOME/Library/Logs/couchserver.log</string>
     <key>EnvironmentVariables</key>
     <dict>
       <key>COUCHSUITE_USERNAME_SECRET</key><string>replace-me</string>
     </dict>
   </dict>
   </plist>
   ```

Use `launchctl` to manage the service:

```bash
launchctl load ~/Library/LaunchAgents/com.couch.server.plist   # first time
launchctl start com.couch.server                               # manual start
launchctl stop com.couch.server                                # graceful stop
launchctl unload ~/Library/LaunchAgents/com.couch.server.plist # remove
```

Logs land in `~/Library/Logs/couchserver.log`. Update the plist paths if CouchSuite lives elsewhere.

## Desktop manager GUI

For a local control panel, run the Tk-based manager:

```bash
cd couchserver
python3 manager_gui.py
```

The window lets you start/stop the FastAPI server, watch the live health check, open the server log, and browse all SQLite tables. Table data is limited to the first 500 rows to keep the UI responsive. You can sort columns by clicking the headers and use the new `Add Row` / `Delete Selected` controls to perform quick edits without leaving the GUI. Ensure the same Python environment has the dependencies from `requirements.txt` installed (Tkinter is part of the standard library on most Linux/macOS installs).

To reuse the launcher without typing the Python command each time, use the helper script in this directory:

```bash
cd couchserver
./launch_manager_gui.sh
```

It activates `.venv` automatically (if present) before running the GUI so `uvicorn` and related packages are available.

For a desktop icon, run `./install_database_manager_shortcut.sh`. The generated `.desktop` file points at the helper script above, so double-clicking the icon starts the manager inside the virtual environment as well.
