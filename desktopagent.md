# CouchLauncher Desktop Agent Notes

This launcher must follow the flow defined in the legacy JavaFX docs:

1. **Connect Controller**
   - First screen after boot.
   - User cannot advance until a game controller is detected or the operator explicitly marks it connected.

2. **First Launch Login**
   - Once the controller check passes, show the account screen.
   - Allow login (`POST /auth/login`) or create account (`POST /users`).
   - The console may not enter the main hub until at least one user successfully signs in.
   - Cache the most recent successful user locally so future boots can resume without re-entry.

3. **Hub Navigation**
   - Hub contains three tabs in order: **Home** (default), **Gaming**, **TV**.
   - Navigation is controller-first: left/right moves between tabs, up/down moves within content.
   - Animate the tab changes so the layout feels like smooth horizontal scrolling.

4. **Data + Actions**
   - Pull catalog data from the FastAPI CouchServer (`/charts/top10`, `/users/{id}/apps`, `/sessions`).
   - Launch buttons post to `/sessions` and surface the returned status/stream URL.
   - Persist base host, primary org, and last user locally;
     refuse to open the hub if no saved user exists.

The Android Studio (Compose) project in `CouchLauncherDesktop` is the reference desktop build. All client work should live there.
