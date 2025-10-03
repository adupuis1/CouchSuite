# Goal

Build a runnable JavaFX client (CouchLauncherFX) that connects to the **multi-tenant CouchServer**.
On boot -> Connect Controller -> Select/Add User (org-aware) -> Hub (Home/Gaming/TV).
New machines go straight to Home after first user creation.

## Cloud awareness

- The launcher must authenticate to the **cloud server** and operate per **organization (tenant)**.
- If the logged-in user has memberships in multiple orgs, prompt for org selection before entering the Hub.
- Base API URL is configurable (env flag or config file); no hard-coding.

## Inputs (assets)

Use images from:
`CouchSuite/CouchSuiteLauncherClientDesignReference/Launcher layout/`

- `connect controller.png` (connect/controller)
- `select user.png` (select/user)
- `home.png` (home)
- `gaming.png` (gaming)
- `tv.png` (tv)

Copy at runtime to `/launcher_layout`.

### Icons (override first)

Always pull icons from:
`/CouchLauncherFX/resources/app/assets/`
- If both an icon and a layout image exist, prefer the **icon**.
- Header (all tabs): **user**, **controller**, **wifi**.
  - User -> profile/switch user/org
  - Controller -> settings/connect
  - Wi-Fi -> reflects connectivity (no action yet)

## Screen flow (state machine)

```
BOOT
|- CONNECT_CONTROLLER (continue on input)
|- SELECT_USER (shows "+" if none; org selection if multiple)
\- HUB (tabs: Home | Gaming | TV; default = Home)
```

## Game Integration (cloud-first)

- **Home** calls `GET /charts/top10` and renders tiles.
- Selecting a tile opens **Game Page**:
  - Show cover, name, description, rating, platforms.
  - Fetch `/users/{uid}/library` to determine **ownership** (org-scoped).
  - Ask server if a **ready installation** exists for this org (and region).
  - If owned **and** an install exists -> show **green Play**.

### Play action

- **Cloud mode:** `POST /sessions` with `{ org_id, user_id, game_id }`
  - Server allocates a node and returns a session descriptor (for example, stream URL or deep link).
  - Launcher opens the returned URL or starts the streaming client.
- **Dev/local mode:** `POST /play/{game_id}` may return a local executable path or store deep link.

## UX/Engineering Notes

- Controller and keyboard navigation must be smooth.
- UI should cope with API failures: show retry button and small status toasts.
- Store config at `~/.config/couchlauncherfx/config.json` (API base URL, last org, last user).
- Keep Home/Gaming/TV visuals consistent; prefer icons over layout images when present.
