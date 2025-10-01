"""Tkinter GUI for managing the CouchServer FastAPI process and its SQLite database."""

from __future__ import annotations

import contextlib
import json
import os
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import tkinter as tk
from tkinter import messagebox, ttk
from PIL import Image, ImageTk

PALETTE = {
    "background": "#0b1220",
    "surface": "#121c33",
    "surface_alt": "#16223b",
    "elevated": "#1b2947",
    "accent": "#4f9dff",
    "accent_strong": "#9cc8ff",
    "danger": "#ff5f7a",
    "power": "#ff9a3d",
    "text": "#e6edff",
    "muted": "#8aa3c7",
    "border": "#233350",
    "selection": "#1f3a63",
}

try:
    from server import APP_VERSION, DB_PATH, init_db
except Exception:  # pragma: no cover - fallback if run before deps installed
    APP_VERSION = "unknown"
    DB_PATH = Path(__file__).resolve().with_name("couch.db")

    def init_db() -> None:
        """Best-effort database bootstrap when server import is unavailable."""
        server_dir = Path(__file__).resolve().parent
        seed_path = server_dir / "seed.sql"
        with sqlite3.connect(DB_PATH) as conn:
            if seed_path.exists():
                conn.executescript(seed_path.read_text())
            else:
                conn.execute("CREATE TABLE IF NOT EXISTS apps(id TEXT PRIMARY KEY, name TEXT)")

SERVER_HOST = "127.0.0.1"
SERVER_PORT = 8080
HEALTH_URL = f"http://{SERVER_HOST}:{SERVER_PORT}/health"
VERSION_URL = f"http://{SERVER_HOST}:{SERVER_PORT}/version"
DEFAULT_LOG_FILE = Path(__file__).resolve().with_name("server.log")
MAX_DISPLAY_ROWS = 500
SPLASH_PATH = Path(__file__).resolve().parent.parent / "icons" / "serverlaunch.png"


class ServerManagerApp:
    def __init__(self) -> None:
        init_db()

        self.server_dir = Path(__file__).resolve().parent
        self.db_path = Path(DB_PATH)
        self.log_path = Path(DEFAULT_LOG_FILE)
        self.server_process: Optional[subprocess.Popen] = None
        self._log_handle: Optional[Any] = None
        self.last_status_ok = False
        self.last_version = ""
        self._status_message: Optional[str] = None
        self.off_button: Optional[ttk.Button] = None
        self.current_table: Optional[str] = None
        self.current_columns: List[str] = []
        self.current_rows: List[Tuple[int, Dict[str, Any]]] = []
        self.current_table_info: List[Dict[str, Any]] = []
        self.sort_column: Optional[str] = None
        self.sort_desc: bool = False

        self.root = tk.Tk()
        self.root.title("CouchServer Manager")
        self.root.minsize(960, 560)
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        self._init_theme()
        self._load_splash()

        self.status_var = tk.StringVar(value="Checking server…")
        self.detail_var = tk.StringVar(value="")
        self.table_info_var = tk.StringVar(value="Select a table to inspect")

        self.status_canvas: Optional[tk.Canvas] = None
        self.tables_listbox: Optional[tk.Listbox] = None
        self.tree: Optional[ttk.Treeview] = None
        self.main_container: Optional[ttk.Frame] = None
        self.splash_container: Optional[tk.Widget] = None

        self._show_splash()
        self.root.after(3000, self._init_main_ui)

    # ------------------------------------------------------------------ UI ---
    def _init_theme(self) -> None:
        self.root.configure(bg=PALETTE["background"])
        self.root.option_add("*Font", "{Segoe UI} 11")
        self.root.option_add("*TCombobox*Listbox.foreground", PALETTE["text"])
        self.root.option_add("*TCombobox*Listbox.background", PALETTE["surface"])

        style = ttk.Style(self.root)
        try:
            style.theme_use("clam")
        except tk.TclError:  # pragma: no cover - fallback if theme missing
            pass

        style.configure("Background.TFrame", background=PALETTE["background"])
        style.configure("Surface.TFrame", background=PALETTE["surface"], borderwidth=0)
        style.configure("SurfaceAlt.TFrame", background=PALETTE["surface_alt"], borderwidth=0)

        style.configure("Title.TLabel", background=PALETTE["surface"], foreground=PALETTE["accent_strong"], font=("Segoe UI Semibold", 12))
        style.configure("Body.TLabel", background=PALETTE["surface"], foreground=PALETTE["muted"], font=("Segoe UI", 10))
        style.configure("BodyBold.TLabel", background=PALETTE["background"], foreground=PALETTE["accent_strong"], font=("Segoe UI Semibold", 11))
        style.configure("Section.TLabel", background=PALETTE["surface_alt"], foreground=PALETTE["accent_strong"], font=("Segoe UI Semibold", 10))

        style.configure("Accent.TButton", background=PALETTE["accent"], foreground=PALETTE["background"], padding=(18, 8), borderwidth=0, focusthickness=3, focuscolor=PALETTE["accent_strong"])
        style.map(
            "Accent.TButton",
            background=[("active", PALETTE["accent_strong"]), ("disabled", PALETTE["border"])],
            foreground=[("disabled", PALETTE["muted"])],
        )

        style.configure("Danger.TButton", background=PALETTE["danger"], foreground=PALETTE["background"], padding=(18, 8), borderwidth=0, focusthickness=3, focuscolor=PALETTE["danger"])
        style.map(
            "Danger.TButton",
            background=[("active", "#ff728d"), ("disabled", PALETTE["border"])],
            foreground=[("disabled", PALETTE["muted"])],
        )

        style.configure("Power.TButton", background=PALETTE["power"], foreground=PALETTE["background"], padding=(18, 8), borderwidth=0, focusthickness=3, focuscolor=PALETTE["power"])
        style.map(
            "Power.TButton",
            background=[("active", "#ffb469"), ("disabled", PALETTE["border"])],
            foreground=[("disabled", PALETTE["muted"])],
        )

        style.configure("Ghost.TButton", background=PALETTE["surface"], foreground=PALETTE["accent_strong"], padding=(16, 8), borderwidth=1, focusthickness=3, focuscolor=PALETTE["accent"], relief="flat")
        style.map(
            "Ghost.TButton",
            background=[("active", PALETTE["surface_alt"]), ("disabled", PALETTE["border"])],
            foreground=[("disabled", PALETTE["muted"])],
            bordercolor=[("active", PALETTE["accent"]), ("!active", PALETTE["border"])],
        )

        style.configure("Divider.TSeparator", background=PALETTE["border"], foreground=PALETTE["border"])

        style.configure(
            "Dark.Treeview",
            background=PALETTE["surface"],
            fieldbackground=PALETTE["surface"],
            foreground=PALETTE["text"],
            bordercolor=PALETTE["border"],
            rowheight=28,
        )
        style.map(
            "Dark.Treeview",
            background=[("selected", PALETTE["selection"])],
            foreground=[("selected", PALETTE["accent_strong"])],
        )
        style.configure("Treeview.Heading", background=PALETTE["surface_alt"], foreground=PALETTE["accent_strong"], font=("Segoe UI Semibold", 10), relief="flat")
        style.map(
            "Treeview.Heading",
            background=[("active", PALETTE["accent"])],
            foreground=[("active", PALETTE["background"])],
        )

        style.configure("Vertical.TScrollbar", background=PALETTE["surface"], troughcolor=PALETTE["surface_alt"], arrowcolor=PALETTE["accent_strong"], gripcount=0, bordercolor=PALETTE["border"])
        style.configure("Horizontal.TScrollbar", background=PALETTE["surface"], troughcolor=PALETTE["surface_alt"], arrowcolor=PALETTE["accent_strong"], gripcount=0, bordercolor=PALETTE["border"])

    def _show_splash(self) -> None:
        container = tk.Frame(self.root, bg=PALETTE["background"], highlightthickness=0, bd=0)
        container.pack(fill=tk.BOTH, expand=True)
        self.splash_container = container
        if self.splash_image is not None:
            splash_label = tk.Label(
                container,
                image=self.splash_image,
                bg=PALETTE["background"],
                borderwidth=0,
                highlightthickness=0,
            )
            splash_label.pack(expand=True)
        else:
            ttk.Label(
                container,
                text="CouchServer Manager",
                style="BodyBold.TLabel",
            ).pack(expand=True)

    def _init_main_ui(self) -> None:
        if self.main_container is not None:
            return
        if self.splash_container is not None:
            try:
                self.splash_container.destroy()
            except Exception:
                pass
            self.splash_container = None
        self._build_main_ui()
        self.refresh_tables()
        self._poll_status()

    def _build_main_ui(self) -> None:
        outer = ttk.Frame(self.root, padding=24, style="Background.TFrame")
        outer.pack(fill=tk.BOTH, expand=True)
        self.main_container = outer

        status_frame = ttk.Frame(outer, padding=(18, 16), style="Surface.TFrame")
        status_frame.pack(fill=tk.X)

        self.status_canvas = tk.Canvas(
            status_frame,
            width=16,
            height=16,
            highlightthickness=0,
            bg=PALETTE["surface"],
            bd=0,
        )
        self.status_canvas.grid(row=0, column=0, padx=(0, 8))
        self.status_led = self.status_canvas.create_oval(2, 2, 14, 14, fill="#9e9e9e", outline="")

        status_label = ttk.Label(status_frame, textvariable=self.status_var, style="Title.TLabel")
        status_label.grid(row=0, column=1, sticky=tk.W)

        detail_label = ttk.Label(status_frame, textvariable=self.detail_var, style="Body.TLabel")
        detail_label.grid(row=1, column=1, sticky=tk.W, pady=(4, 0))

        button_frame = ttk.Frame(status_frame, style="Surface.TFrame")
        button_frame.grid(row=0, column=2, rowspan=2, padx=(32, 0))

        self.start_button = ttk.Button(button_frame, text="Start Server", command=self.start_server, style="Accent.TButton")
        self.start_button.grid(row=0, column=0, padx=4)

        self.stop_button = ttk.Button(button_frame, text="Stop Server", command=self.stop_server, style="Danger.TButton")
        self.stop_button.grid(row=0, column=1, padx=4)

        self.refresh_button = ttk.Button(button_frame, text="Refresh Status", command=self.refresh_status_now, style="Ghost.TButton")
        self.refresh_button.grid(row=0, column=2, padx=4)

        self.log_button = ttk.Button(button_frame, text="Open Log", command=self.open_log, style="Ghost.TButton")
        self.log_button.grid(row=0, column=3, padx=4)

        self.off_button = ttk.Button(button_frame, text="Off", command=self.power_off, style="Power.TButton")
        self.off_button.grid(row=0, column=4, padx=4)

        status_frame.columnconfigure(1, weight=1)

        ttk.Separator(outer, orient=tk.HORIZONTAL, style="Divider.TSeparator").pack(fill=tk.X, pady=18)

        body = ttk.Frame(outer, style="Background.TFrame")
        body.pack(fill=tk.BOTH, expand=True)

        tables_frame = ttk.Frame(body, padding=(0, 0, 16, 0), style="SurfaceAlt.TFrame")
        tables_frame.pack(side=tk.LEFT, fill=tk.Y)

        tables_header = ttk.Frame(tables_frame, style="SurfaceAlt.TFrame")
        tables_header.pack(fill=tk.X)

        ttk.Label(tables_header, text="Database Tables", style="Section.TLabel").pack(side=tk.LEFT)
        ttk.Button(tables_header, text="Rescan", command=self.refresh_tables, style="Ghost.TButton").pack(side=tk.RIGHT)

        self.tables_listbox = tk.Listbox(
            tables_frame,
            exportselection=False,
            height=18,
            bg=PALETTE["surface_alt"],
            fg=PALETTE["text"],
            selectbackground=PALETTE["selection"],
            selectforeground=PALETTE["accent_strong"],
            bd=0,
            highlightthickness=0,
            relief=tk.FLAT,
        )
        self.tables_listbox.pack(side=tk.LEFT, fill=tk.Y, expand=True, pady=(8, 0))
        self.tables_listbox.bind("<<ListboxSelect>>", self.on_table_select)

        tables_scroll = ttk.Scrollbar(tables_frame, orient=tk.VERTICAL, command=self.tables_listbox.yview)
        tables_scroll.pack(side=tk.RIGHT, fill=tk.Y, pady=(8, 0))
        self.tables_listbox.configure(yscrollcommand=tables_scroll.set)

        data_frame = ttk.Frame(body, style="Background.TFrame")
        data_frame.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(16, 0))

        ttk.Label(data_frame, textvariable=self.table_info_var, style="BodyBold.TLabel").pack(anchor=tk.W)

        tree_container = ttk.Frame(data_frame, style="Surface.TFrame")
        tree_container.pack(fill=tk.BOTH, expand=True, pady=(8, 0))

        self.tree = ttk.Treeview(tree_container, show="headings", style="Dark.Treeview")
        self.tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

        tree_scroll_y = ttk.Scrollbar(tree_container, orient=tk.VERTICAL, command=self.tree.yview)
        tree_scroll_y.pack(side=tk.RIGHT, fill=tk.Y)
        self.tree.configure(yscrollcommand=tree_scroll_y.set)

        tree_scroll_x = ttk.Scrollbar(data_frame, orient=tk.HORIZONTAL, command=self.tree.xview)
        tree_scroll_x.pack(fill=tk.X)
        self.tree.configure(xscrollcommand=tree_scroll_x.set)

        table_actions = ttk.Frame(data_frame, style="Background.TFrame")
        table_actions.pack(fill=tk.X, pady=(12, 0))

        ttk.Button(
            table_actions,
            text="Add Row",
            command=self.add_row,
            style="Accent.TButton",
        ).pack(side=tk.RIGHT, padx=(8, 0))

        ttk.Button(
            table_actions,
            text="Delete Selected",
            command=self.delete_selected_rows,
            style="Danger.TButton",
        ).pack(side=tk.RIGHT)

    # --------------------------------------------------------- Server control ---
    def start_server(self) -> None:
        if self.server_process is not None and self.server_process.poll() is None:
            messagebox.showinfo("Server", "The server process started by this manager is already running.")
            return

        def launch() -> None:
            self._set_controls_enabled(False)
            self._update_status_indicator("#fb8c00", "Starting server…")
            log_handle = None
            try:
                command = [
                    sys.executable,
                    "-m",
                    "uvicorn",
                    "server:app",
                    "--host",
                    "0.0.0.0",
                    "--port",
                    str(SERVER_PORT),
                ]
                env = os.environ.copy()
                env.setdefault("PYTHONPATH", str(self.server_dir))
                log_handle = self.log_path.open("a", encoding="utf-8")
                log_handle.write(f"\n--- Launch at {time.strftime('%Y-%m-%d %H:%M:%S')} ---\n")
                log_handle.flush()
                self.server_process = subprocess.Popen(
                    command,
                    cwd=self.server_dir,
                    stdout=log_handle,
                    stderr=subprocess.STDOUT,
                    env=env,
                )
                self._log_handle = log_handle
                self._status_message = f"Process PID {self.server_process.pid}"
                log_handle = None  # ownership transferred to process tracking
            except FileNotFoundError:
                messagebox.showerror(
                    "uvicorn not found",
                    "uvicorn is not installed in this Python environment. Run 'pip install -r requirements.txt' first.",
                )
                self.server_process = None
            except Exception as exc:  # pragma: no cover - defensive UI path
                messagebox.showerror("Failed to start server", str(exc))
                self.server_process = None
            finally:
                if log_handle is not None:
                    with contextlib.suppress(Exception):
                        log_handle.close()
                self._set_controls_enabled(True)

        threading.Thread(target=launch, daemon=True).start()

    def stop_server(self) -> None:
        if self.server_process is None:
            if self.last_status_ok:
                messagebox.showinfo(
                    "Server running externally",
                    "The server appears to be running, but not from this manager. Stop it manually or via toggle scripts.",
                )
            else:
                messagebox.showinfo("Server", "No server process tracked by this manager.")
            return

        if self.server_process.poll() is not None:
            self._clear_process_tracking()
            self._update_status_indicator("#e53935", "Server stopped")
            return

        def terminate() -> None:
            self._set_controls_enabled(False)
            self.server_process.terminate()
            try:
                self.server_process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.server_process.kill()
                self.server_process.wait(timeout=5)
            finally:
                self._clear_process_tracking()
                self._set_controls_enabled(True)
                self._status_message = None

        threading.Thread(target=terminate, daemon=True).start()

    def refresh_status_now(self) -> None:
        self._check_status()

    def power_off(self) -> None:
        if self.off_button is not None:
            self.off_button.configure(state=tk.DISABLED)
        self._update_status_indicator("#e53935", "Powering off…")

        def shutdown() -> None:
            if self.server_process is not None and self.server_process.poll() is None:
                try:
                    self.server_process.terminate()
                    self.server_process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    with contextlib.suppress(Exception):
                        self.server_process.kill()
                        self.server_process.wait(timeout=5)
            self._clear_process_tracking()
            self._status_message = None
            self.root.after(0, self.root.destroy)

        threading.Thread(target=shutdown, daemon=True).start()

    def _set_controls_enabled(self, enabled: bool) -> None:
        state = tk.NORMAL if enabled else tk.DISABLED
        for widget in (self.start_button, self.stop_button, self.refresh_button, self.log_button):
            widget.configure(state=state)

    def _poll_status(self) -> None:
        self._check_status()
        self.root.after(2000, self._poll_status)

    def _check_status(self) -> None:
        healthy, detail = self._fetch_status()
        self.last_status_ok = healthy
        if healthy:
            color = "#43a047"
            message = detail or "Server online"
        else:
            color = "#e53935"
            message = detail or "Server offline"
            if not healthy:
                self._status_message = None
        if self._status_message:
            detail_message = f"{message} • {self._status_message}"
        else:
            detail_message = message
        self._update_status_indicator(color, detail_message)

        if self.server_process is not None and self.server_process.poll() is not None:
            self._clear_process_tracking()

    def _fetch_status(self) -> Tuple[bool, str]:
        try:
            with urllib.request.urlopen(HEALTH_URL, timeout=1.5) as response:
                if response.status != 200:
                    return False, f"Health check returned {response.status}"
                payload = json.loads(response.read().decode("utf-8"))
                if not payload.get("ok"):
                    return False, "Health check failed"
            with urllib.request.urlopen(VERSION_URL, timeout=1.5) as response:
                data = json.loads(response.read().decode("utf-8"))
                version = data.get("server") or APP_VERSION
                self.last_version = version
            version_info = f"Server online (v{self.last_version})"
            return True, version_info
        except urllib.error.URLError as exc:
            reason = getattr(exc, "reason", exc)
            return False, f"Offline • {reason}"  # pragma: no cover - env specific
        except Exception as exc:  # pragma: no cover - network edge cases
            return False, f"Offline • {exc}"

    def _update_status_indicator(self, color: str, message: str) -> None:
        self.status_canvas.itemconfigure(self.status_led, fill=color)
        self.status_canvas.configure(bg=PALETTE["surface"])
        self.status_var.set("CouchServer")
        self.detail_var.set(message)

    def _clear_process_tracking(self) -> None:
        if self.server_process is not None and self.server_process.poll() is None:
            return
        self.server_process = None
        if self._log_handle is not None:
            with contextlib.suppress(Exception):
                self._log_handle.flush()
                self._log_handle.close()
        self._log_handle = None

    # ----------------------------------------------------------- DB helpers ---
    def refresh_tables(self) -> None:
        if self.tables_listbox is None or self.tree is None:
            return
        if not self.db_path.exists():
            try:
                init_db()
            except Exception as exc:  # pragma: no cover
                messagebox.showerror("Database", f"Failed to prepare database: {exc}")
                return
        tables = self._list_tables()
        self.tables_listbox.delete(0, tk.END)
        for table in tables:
            self.tables_listbox.insert(tk.END, table)

        self.current_table = None
        self.current_columns = []
        self.current_rows = []
        self.current_table_info = []
        self.sort_column = None
        self.sort_desc = False

        if tables:
            self.tables_listbox.selection_clear(0, tk.END)
            self.tables_listbox.selection_set(0)
            self.on_table_select()
        else:
            self.table_info_var.set("Database has no tables yet")
            self.tree.delete(*self.tree.get_children())
            self.tree.configure(columns=())

    def on_table_select(self, _event: Optional[tk.Event] = None) -> None:
        if self.tables_listbox is None:
            return
        selection = self.tables_listbox.curselection()
        if not selection:
            return
        index = selection[0]
        table_name = self.tables_listbox.get(index)
        self._show_table(table_name)

    def _show_table(self, table_name: str) -> None:
        rows, columns, columns_info = self._fetch_table_rows(table_name)
        self.current_table = table_name
        self.current_rows = rows
        self.current_columns = columns
        self.current_table_info = columns_info
        if self.sort_column not in columns:
            self.sort_column = None
            self.sort_desc = False
        self._render_table()

    def _render_table(self) -> None:
        if self.tree is None:
            return
        columns = self.current_columns
        rows = self.current_rows
        if self.sort_column:
            rows = sorted(
                rows,
                key=lambda item: self._sort_key(item[1].get(self.sort_column)),
                reverse=self.sort_desc,
            )

        self.tree.delete(*self.tree.get_children())
        self.tree.configure(columns=columns)
        for column in columns:
            heading = self._heading_text(column)
            self.tree.heading(column, text=heading, command=lambda col=column: self.sort_by_column(col))
            self.tree.column(column, width=160, stretch=True, anchor=tk.W)
        for row_id, data in rows:
            values = [self._render_cell(data.get(column)) for column in columns]
            self.tree.insert("", tk.END, iid=str(row_id), values=values)

        total_rows = len(self.current_rows)
        suffix = " (limited to first %d rows)" % MAX_DISPLAY_ROWS if total_rows >= MAX_DISPLAY_ROWS else ""
        if self.sort_column:
            direction = "▼" if self.sort_desc else "▲"
            self.table_info_var.set(
                f"{self.current_table}: {total_rows} rows{suffix} • sorted by {self.sort_column} {direction}"
            )
        else:
            self.table_info_var.set(f"{self.current_table}: {total_rows} rows{suffix}")

    def sort_by_column(self, column: str) -> None:
        if not self.current_columns:
            return
        if self.sort_column == column:
            self.sort_desc = not self.sort_desc
        else:
            self.sort_column = column
            self.sort_desc = False
        self._render_table()

    def _list_tables(self) -> List[str]:
        query = "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.execute(query)
            return [row[0] for row in cursor.fetchall()]

    def _fetch_table_rows(self, table_name: str) -> Tuple[List[Tuple[int, Dict[str, Any]]], List[str], List[Dict[str, Any]]]:
        with sqlite3.connect(self.db_path) as conn:
            conn.row_factory = sqlite3.Row
            try:
                cursor = conn.execute(
                    f'SELECT rowid AS __rowid__, * FROM "{table_name}" LIMIT {MAX_DISPLAY_ROWS}'
                )
            except sqlite3.Error as exc:
                messagebox.showerror("Query error", str(exc))
                return [], [], []
            fetched = cursor.fetchall()
            rows: List[Tuple[int, Dict[str, Any]]] = []
            columns: List[str]
            if fetched:
                columns = [key for key in fetched[0].keys() if key != "__rowid__"]
            else:
                pragma_rows = conn.execute(f"PRAGMA table_info('{table_name}')").fetchall()
                columns = [row[1] for row in pragma_rows]
            for record in fetched:
                data = dict(record)
                row_id = int(data.pop("__rowid__", -1))
                rows.append((row_id, data))
            pragma_rows = conn.execute(f"PRAGMA table_info('{table_name}')").fetchall()
            columns_info = [dict(row) for row in pragma_rows]
        return rows, columns, columns_info

    @staticmethod
    def _render_cell(value: Any) -> str:
        if value is None:
            return ""
        if isinstance(value, (bytes, bytearray)):
            return value.hex()
        if isinstance(value, (dict, list)):
            return json.dumps(value)
        return str(value)

    def _heading_text(self, column: str) -> str:
        if column != self.sort_column:
            return column
        return f"{column} {'▼' if self.sort_desc else '▲'}"

    @staticmethod
    def _sort_key(value: Any) -> Tuple[int, Any]:
        if value is None:
            return (1, "")
        if isinstance(value, (int, float)):
            return (0, value)
        if isinstance(value, (dict, list)):
            return (0, json.dumps(value))
        return (0, str(value))

    def add_row(self) -> None:
        if self.current_table is None or not self.current_table_info:
            messagebox.showinfo("Add row", "Select a table before adding rows.")
            return

        dialog = tk.Toplevel(self.root)
        dialog.title(f"Insert into {self.current_table}")
        dialog.configure(bg=PALETTE["background"])
        dialog.transient(self.root)
        dialog.grab_set()

        container = ttk.Frame(dialog, padding=16, style="Background.TFrame")
        container.pack(fill=tk.BOTH, expand=True)

        entries: Dict[str, tk.Entry] = {}
        row_index = 0
        for info in self.current_table_info:
            name = info.get("name")
            if name is None:
                continue
            field_frame = ttk.Frame(container, style="Background.TFrame")
            field_frame.grid(row=row_index, column=0, sticky=tk.EW, pady=4)
            ttk.Label(field_frame, text=name, style="BodyBold.TLabel").pack(side=tk.LEFT, padx=(0, 12))
            entry = ttk.Entry(field_frame, width=32)
            entry.pack(side=tk.LEFT, fill=tk.X, expand=True)
            default_value = info.get("dflt_value")
            if default_value is not None:
                entry.insert(0, str(default_value))
            entries[name] = entry
            row_index += 1

        helper = ttk.Label(
            container,
            text="Leave primary key blank for defaults. Values are parsed as JSON when possible.",
            style="Body.TLabel",
            wraplength=420,
            justify=tk.LEFT,
        )
        helper.grid(row=row_index, column=0, sticky=tk.W, pady=(8, 16))

        actions = ttk.Frame(container, style="Background.TFrame")
        actions.grid(row=row_index + 1, column=0, sticky=tk.E)

        def on_cancel() -> None:
            dialog.destroy()

        def on_submit() -> None:
            column_names: List[str] = []
            values: List[Any] = []
            for info in self.current_table_info:
                name = info.get("name")
                if name is None:
                    continue
                raw = entries[name].get().strip()
                if raw == "" and info.get("pk"):
                    continue  # let SQLite assign primary key
                if raw == "" and info.get("notnull"):
                    messagebox.showerror("Add row", f"Column '{name}' cannot be empty.")
                    return
                if raw == "" and info.get("dflt_value") is not None:
                    continue  # allow default value
                column_names.append(name)
                value = None if raw == "" else self._coerce_value(raw)
                values.append(value)

            try:
                with sqlite3.connect(self.db_path) as conn:
                    if column_names:
                        placeholders = ",".join("?" for _ in column_names)
                        columns_clause = ",".join(f'"{col}"' for col in column_names)
                        conn.execute(
                            f'INSERT INTO "{self.current_table}" ({columns_clause}) VALUES ({placeholders})',
                            values,
                        )
                    else:
                        conn.execute(f'INSERT INTO "{self.current_table}" DEFAULT VALUES')
                    conn.commit()
            except sqlite3.Error as exc:
                messagebox.showerror("Add row", f"Failed to insert row: {exc}")
                return

            dialog.destroy()
            self._status_message = "Row added"
            self._show_table(self.current_table)

        ttk.Button(actions, text="Cancel", command=on_cancel, style="Ghost.TButton").pack(side=tk.RIGHT, padx=(8, 0))
        ttk.Button(actions, text="Insert", command=on_submit, style="Accent.TButton").pack(side=tk.RIGHT)

        dialog.columnconfigure(0, weight=1)
        container.columnconfigure(0, weight=1)
        dialog.wait_window()

    def delete_selected_rows(self) -> None:
        if self.current_table is None:
            messagebox.showinfo("Delete rows", "Select a table before deleting rows.")
            return
        selection = self.tree.selection()
        if not selection:
            messagebox.showinfo("Delete rows", "Select at least one row in the table view.")
            return
        if not messagebox.askyesno(
            "Delete rows",
            f"Delete {len(selection)} row(s) from {self.current_table}? This action cannot be undone.",
        ):
            return

        rowids = [int(item) for item in selection]
        try:
            with sqlite3.connect(self.db_path) as conn:
                conn.executemany(
                    f'DELETE FROM "{self.current_table}" WHERE rowid=?',
                    [(rowid,) for rowid in rowids],
                )
                conn.commit()
        except sqlite3.Error as exc:
            messagebox.showerror("Delete rows", f"Failed to delete rows: {exc}")
            return

        self._status_message = f"Deleted {len(rowids)} row(s)"
        self._show_table(self.current_table)

    @staticmethod
    def _coerce_value(raw: str) -> Any:
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return raw

    def _load_splash(self) -> None:
        self.splash_image: Optional[ImageTk.PhotoImage] = None
        if not SPLASH_PATH.exists():
            return
        try:
            image = Image.open(SPLASH_PATH)
            target_width = 280
            ratio = target_width / image.width
            size = (target_width, int(image.height * ratio))
            image = image.resize(size, Image.LANCZOS)
            self.splash_image = ImageTk.PhotoImage(image)
        except Exception:
            self.splash_image = None

    # ---------------------------------------------------------- Utilities ---
    def open_log(self) -> None:
        if not self.log_path.exists():
            self.log_path.touch()
        try:
            if sys.platform.startswith("darwin"):
                subprocess.Popen(["open", str(self.log_path)])
            elif os.name == "nt":  # pragma: no cover - Windows convenience
                os.startfile(self.log_path)  # type: ignore[attr-defined]
            else:
                subprocess.Popen(["xdg-open", str(self.log_path)])
        except Exception as exc:  # pragma: no cover
            messagebox.showerror("Open log", f"Failed to open log file: {exc}")

    def on_close(self) -> None:
        if self.server_process is not None and self.server_process.poll() is None:
            if not messagebox.askyesno(
                "Quit CouchServer Manager",
                "A server process started from this manager is still running. Quit anyway?",
            ):
                return
        if self.server_process is not None and self.server_process.poll() is None:
            with contextlib.suppress(Exception):
                self.server_process.terminate()
                self.server_process.wait(timeout=5)
        self._clear_process_tracking()
        self.root.destroy()

    def run(self) -> None:
        self.root.mainloop()


def main() -> None:
    app = ServerManagerApp()
    app.run()


if __name__ == "__main__":
    main()
