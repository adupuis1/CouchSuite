
# CouchLauncherFX (JavaFX, Gradle) — IntelliJ Quick Start

## Prereqs (macOS Apple Silicon)
- Install **JDK 25 (ARM64)** — e.g., `temurin 17` via Homebrew:
  ```bash
  brew install --cask temurin@25
  /usr/libexec/java_home -V    # verify
  ```

## Open in IntelliJ
1. **File → Open…** and select the `CouchLauncherFX` folder (this folder).
2. IntelliJ detects **Gradle**; click **Trust Project** and let it **Sync**.
3. In the Gradle tool window, run **Tasks → application → run**, or create a normal Run config with `app.Main`.

## What this app does
- Fullscreen **TV-style launcher** with big tiles (Steam Big Picture, Switch Emulator, Settings).
- A **Host** text field for your GPU server IP/hostname.
- On macOS it shows a stub dialog; on Linux you can change the `launch()` method to call Moonlight:
  ```java
  new ProcessBuilder("moonlight", "stream", host, "--app", "Steam Big Picture").start();
  ```

## Next steps
- Add a settings page (save host to a config file, e.g., `~/.config/couchlauncherfx/config.json`).
- When you move to the Linux VM, install `moonlight-qt`, `bluez`, etc., and wire the ProcessBuilder calls.
