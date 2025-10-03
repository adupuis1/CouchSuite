CREATE TABLE IF NOT EXISTS orgs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  slug TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username_digest TEXT NOT NULL UNIQUE,
  username_cipher TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  password_salt TEXT NOT NULL,
  password_iterations INTEGER NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS memberships (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  org_id INTEGER NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role TEXT NOT NULL DEFAULT 'member',
  UNIQUE(org_id, user_id)
);

CREATE TABLE IF NOT EXISTS user_accounts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  org_id INTEGER NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  platform TEXT NOT NULL,
  account_id TEXT NOT NULL,
  display_name TEXT,
  metadata_json TEXT,
  UNIQUE(org_id, user_id, platform)
);

CREATE TABLE IF NOT EXISTS games (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  slug TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  description TEXT,
  rating REAL,
  cover_url TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS game_external_ids (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  game_id INTEGER NOT NULL REFERENCES games(id) ON DELETE CASCADE,
  platform TEXT NOT NULL,
  external_id TEXT NOT NULL,
  UNIQUE(game_id, platform)
);

CREATE TABLE IF NOT EXISTS game_ratings (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  game_id INTEGER NOT NULL REFERENCES games(id) ON DELETE CASCADE,
  source TEXT,
  score REAL,
  details TEXT
);

CREATE TABLE IF NOT EXISTS user_game_library (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  org_id INTEGER NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  game_id INTEGER NOT NULL REFERENCES games(id) ON DELETE CASCADE,
  platform TEXT,
  external_id TEXT,
  ownership_source TEXT NOT NULL,
  proof_type TEXT,
  proof_value TEXT,
  verified_at TEXT,
  UNIQUE(org_id, user_id, game_id)
);

CREATE TABLE IF NOT EXISTS cluster_nodes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  org_id INTEGER NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  region TEXT,
  capacity INTEGER,
  status TEXT DEFAULT 'active',
  UNIQUE(org_id, name)
);

CREATE TABLE IF NOT EXISTS downloaded_games (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  org_id INTEGER NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  node_id INTEGER NOT NULL REFERENCES cluster_nodes(id) ON DELETE CASCADE,
  game_id INTEGER NOT NULL REFERENCES games(id) ON DELETE CASCADE,
  install_path TEXT NOT NULL,
  launcher TEXT,
  executable TEXT,
  last_seen TEXT DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(org_id, node_id, game_id)
);

CREATE TABLE IF NOT EXISTS charts_top10 (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  chart_date TEXT NOT NULL,
  rank INTEGER NOT NULL,
  name TEXT NOT NULL,
  steam_appid INTEGER,
  game_id INTEGER REFERENCES games(id) ON DELETE SET NULL,
  source TEXT NOT NULL DEFAULT 'steamcharts',
  UNIQUE(chart_date, rank)
);

CREATE INDEX IF NOT EXISTS idx_charts_date ON charts_top10(chart_date);

CREATE TABLE IF NOT EXISTS sessions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  org_id INTEGER NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  game_id INTEGER NOT NULL REFERENCES games(id) ON DELETE CASCADE,
  status TEXT NOT NULL DEFAULT 'provisioning',
  stream_url TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS apps (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  moonlight_name TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  sort_order INTEGER NOT NULL DEFAULT 100
);

CREATE TABLE IF NOT EXISTS user_apps (
  user_id INTEGER NOT NULL,
  app_id TEXT NOT NULL,
  installed INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, app_id),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (app_id) REFERENCES apps(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS user_settings (
  user_id INTEGER PRIMARY KEY,
  settings_json TEXT NOT NULL DEFAULT '{}',
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

INSERT OR IGNORE INTO orgs (id, slug, name) VALUES
  (1, 'default', 'CouchSuite Default');

INSERT OR IGNORE INTO games (id, slug, name, description, rating, cover_url) VALUES
  (1, 'steam_big_picture', 'Steam Big Picture', 'Launch your Steam library in Big Picture mode.', 4.5, 'https://cdn.couchsuite.local/steam_big_picture.png'),
  (2, 'switch_emulator', 'Switch Emulator', 'Play Nintendo Switch titles via approved emulator.', 4.2, 'https://cdn.couchsuite.local/switch_emulator.png'),
  (3, 'retro_arcade', 'Retro Arcade Classics', 'Stream curated retro arcade experiences.', 4.7, 'https://cdn.couchsuite.local/retro_arcade.png');

INSERT OR IGNORE INTO game_external_ids (game_id, platform, external_id) VALUES
  (1, 'steam', '1593500'),
  (2, 'switch', 'switch-suite-001'),
  (3, 'arcade', 'arcade-group-2024');

INSERT OR IGNORE INTO charts_top10 (chart_date, rank, name, steam_appid, game_id, source) VALUES
  ('2024-07-01', 1, 'Steam Big Picture', 1593500, 1, 'steamcharts'),
  ('2024-07-01', 2, 'Switch Emulator', NULL, 2, 'steamcharts'),
  ('2024-07-01', 3, 'Retro Arcade Classics', NULL, 3, 'steamcharts');

INSERT OR IGNORE INTO cluster_nodes (id, org_id, name, region, capacity, status) VALUES
  (1, 1, 'default-cluster-a', 'us-east', 8, 'active');

INSERT OR IGNORE INTO downloaded_games (org_id, node_id, game_id, install_path, launcher, executable) VALUES
  (1, 1, 1, '/var/couchsuite/steam_big_picture', 'steam', 'steam'),
  (1, 1, 3, '/var/couchsuite/retro_arcade', 'retro', 'retro-launcher');

INSERT OR IGNORE INTO apps (id, name, moonlight_name, enabled, sort_order) VALUES
  ('steam_big_picture','Steam Big Picture','Steam Big Picture',1,1),
  ('switch_emulator','Switch Emulator','Switch Emulator',1,2),
  ('retro_arcade','Retro Arcade','Retro Arcade',1,3);
