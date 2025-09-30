CREATE TABLE IF NOT EXISTS apps (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  moonlight_name TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  sort_order INTEGER NOT NULL DEFAULT 100
);

INSERT OR IGNORE INTO apps (id, name, moonlight_name, enabled, sort_order) VALUES
('steam_big_picture','Steam Big Picture','Steam Big Picture',1,1),
('switch_emulator','Switch Emulator','Switch Emulator',1,2);
