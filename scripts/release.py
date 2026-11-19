#!/usr/bin/env python3
"""Send the release announcement to Telegram.

Version comes from build.gradle.kts, notes from RELEASE_NOTES.md.
Secrets live in ~/.zenith_release.env (never in the repo):

    TELEGRAM_BOT_TOKEN=...
    TELEGRAM_CHAT_ID=...
    TELEGRAM_THREAD_ID=...
    TELEGRAM_TEST_CHAT_ID=...
    TELEGRAM_TEST_THREAD_ID=...

Usage:
    python scripts/release.py                 (interactive menu)
    python scripts/release.py --dry-run
    python scripts/release.py --test --images /storage/emulated/0/CodeOnTheGoProjects/png
    python scripts/release.py --images /storage/emulated/0/CodeOnTheGoProjects/png
"""
import argparse
import getpass
import html
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

ENV_FILE = Path.home() / ".zenith_release.env"
ROOT = Path(__file__).resolve().parent.parent
IMAGE_TYPES = {".jpg", ".jpeg", ".png"}
TEXT_LIMIT = 4000
ALBUM_MAX = 10


def esc(text):
    return html.escape(text, quote=False)


def read_env_file():
    config = {}
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                config[key.strip()] = value.strip().strip("\"'")
    return config


def load_config():
    config = read_env_file()
    for key, value in os.environ.items():
        if key.startswith("TELEGRAM_"):
            config[key] = value
    return config


def read_version(gradle):
    path = Path(gradle)
    if not path.exists():
        sys.exit(f"{gradle} not found; run from the repo root or pass --version")
    match = re.search(r'zenithVersionName"\]\s*=\s*"([^"]+)"', path.read_text(encoding="utf-8"))
    if not match:
        sys.exit(f"Version not found in {gradle}; pass --version")
    return match.group(1)


def inline(text):
    text = esc(text)
    text = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", text)
    text = re.sub(r"`(.+?)`", r"<code>\1</code>", text)
    return text


def parse_notes(text):
    sections = []
    current = None
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("###"):
            current = (line.lstrip("#").strip(), [])
            sections.append(current)
        elif line.startswith("#"):
            continue
        elif line[0] in "-*" and line[1:2] == " ":
            if current is None:
                current = ("What's new", [])
                sections.append(current)
            current[1].append(line[2:].strip())
        elif current and current[1]:
            current[1][-1] += " " + line
    return [(title, items) for title, items in sections if items]


def render(title, items):
    tag = "<blockquote expandable>" if "fix" in title.lower() else "<blockquote>"
    body = "\n".join("• " + inline(item) for item in items)
    return f"<b>{esc(title)}</b>\n{tag}{body}</blockquote>"


def chunk(head, blocks):
    messages = []
    current = head
    for block in blocks:
        candidate = f"{current}\n\n{block}" if current else block
        if len(candidate) > TEXT_LIMIT and current:
            messages.append(current)
            current = block
        else:
            current = candidate
    if current:
        messages.append(current)
    return messages


def call(token, method, payload):
    request = urllib.request.Request(
        f"https://api.telegram.org/bot{token}/{method}",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        try:
            return json.load(error)
        except ValueError:
            return {"ok": False, "description": f"HTTP {error.code}"}
    except urllib.error.URLError as error:
        return {"ok": False, "description": str(error.reason)}


def send_text(token, chat, thread, text):
    payload = {
        "chat_id": chat,
        "text": text,
        "parse_mode": "HTML",
        "link_preview_options": {"is_disabled": True},
    }
    if thread:
        payload["message_thread_id"] = int(thread)
    return call(token, "sendMessage", payload)


def send_images(token, chat, thread, images, caption):
    base = ["curl", "-s", "--form-string", f"chat_id={chat}"]
    if thread:
        base += ["--form-string", f"message_thread_id={thread}"]
    if len(images) == 1:
        method = "sendPhoto"
        cmd = base + [
            "-F", f"photo=@{images[0]}",
            "--form-string", f"caption={caption}",
            "--form-string", "parse_mode=HTML",
        ]
    else:
        method = "sendMediaGroup"
        media = []
        cmd = list(base)
        for index, path in enumerate(images):
            item = {"type": "photo", "media": f"attach://f{index}"}
            if index == 0:
                item.update(caption=caption, parse_mode="HTML")
            media.append(item)
            cmd += ["-F", f"f{index}=@{path}"]
        cmd += ["--form-string", "media=" + json.dumps(media)]
    cmd.append(f"https://api.telegram.org/bot{token}/{method}")
    result = subprocess.run(cmd, capture_output=True, text=True)
    try:
        return json.loads(result.stdout)
    except ValueError:
        return {"ok": False, "description": result.stderr.strip() or "no response"}


def check(result, what):
    if not result.get("ok"):
        reason = result.get("description", "unknown error")
        if reason == "Not Found":
            reason += " (bot token salah atau bot sudah dihapus, cek menu [c])"
        sys.exit(f"{what} failed: {reason}")
    print(f"Sent: {what}")


MENU_FIELDS = [
    ("TELEGRAM_BOT_TOKEN", "Bot token", True),
    ("TELEGRAM_CHAT_ID", "Chat ID rilis", False),
    ("TELEGRAM_THREAD_ID", "Topic ID rilis", False),
    ("TELEGRAM_TEST_CHAT_ID", "Chat ID tes", False),
    ("TELEGRAM_TEST_THREAD_ID", "Topic ID tes", False),
    ("RELEASE_APP", "Nama app", False),
    ("RELEASE_TAGLINE", "Tagline", False),
    ("RELEASE_IMAGES", "Folder gambar", False),
]


def mask(value, secret):
    if not value:
        return "(kosong)"
    if secret:
        return value[:6] + "…" + value[-4:] if len(value) > 12 else "*" * len(value)
    return value


def ask(text, secret=False):
    try:
        return (getpass.getpass(text) if secret else input(text)).strip()
    except (EOFError, KeyboardInterrupt):
        print()
        return None


def save_env_value(key, value):
    lines = ENV_FILE.read_text(encoding="utf-8").splitlines() if ENV_FILE.exists() else []
    out = []
    done = False
    for line in lines:
        name = line.split("=", 1)[0].strip()
        if "=" in line and not line.lstrip().startswith("#") and name == key:
            if value and not done:
                out.append(f"{key}={value}")
            done = True
            continue
        out.append(line)
    if value and not done:
        out.append(f"{key}={value}")
    fd = os.open(str(ENV_FILE), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write("\n".join(out) + "\n")


def edit_field(key, label, secret):
    current = mask(read_env_file().get(key, ""), secret)
    value = ask(f"{label} [{current}] (Enter = tetap, - = hapus): ", secret)
    if not value:
        print("Tidak diubah.")
        return
    if value == "-":
        save_env_value(key, "")
        print("Dihapus.")
        return
    if key == "TELEGRAM_BOT_TOKEN" and ":" not in value:
        print("Token tidak valid, formatnya 123456:ABC...")
        return
    if key.endswith("THREAD_ID") and not value.isdigit():
        print("Topic ID harus berupa angka.")
        return
    save_env_value(key, value)
    print("Tersimpan.")
    if key == "TELEGRAM_BOT_TOKEN":
        check_bot({"TELEGRAM_BOT_TOKEN": value})
    if key == "RELEASE_IMAGES" and not Path(value).expanduser().is_dir():
        print("Peringatan: folder tidak ditemukan.")


def run_cli(argv):
    saved = sys.argv
    sys.argv = ["release.py"] + argv
    try:
        main()
    except SystemExit as error:
        if error.code not in (None, 0):
            print(error.code)
    finally:
        sys.argv = saved


def menu_args(config, test=False, dry=False):
    argv = []
    if config.get("RELEASE_APP"):
        argv += ["--app", config["RELEASE_APP"]]
    if config.get("RELEASE_TAGLINE"):
        argv += ["--tagline", config["RELEASE_TAGLINE"]]
    if config.get("RELEASE_NO_IMAGES") == "1":
        argv.append("--no-images")
    elif config.get("RELEASE_IMAGES"):
        argv += ["--images", config["RELEASE_IMAGES"]]
    if test:
        argv.append("--test")
    if dry:
        argv.append("--dry-run")
    return argv


def check_bot(config):
    token = config.get("TELEGRAM_BOT_TOKEN")
    if not token:
        print("Isi bot token dulu (menu 1).")
        return
    result = call(token, "getMe", {})
    if result.get("ok"):
        print(f"Bot aktif: @{result['result'].get('username', '?')}")
    elif result.get("description") == "Not Found":
        print("Gagal: token salah atau bot sudah dihapus. Ambil token baru dari @BotFather.")
    else:
        print("Gagal:", result.get("description", "unknown error"))


def shown(config, key):
    return config.get(key) or "(kosong)"


def edit_target(chat_key, thread_key, title):
    edit_field(chat_key, f"Chat ID {title}", False)
    edit_field(thread_key, f"Topic ID {title}", False)


def detect_chats(config):
    token = config.get("TELEGRAM_BOT_TOKEN")
    if not token:
        print("Isi bot token dulu (menu 1).")
        return
    result = call(token, "getUpdates", {"limit": 100})
    if not result.get("ok"):
        print("Gagal:", result.get("description", "unknown error"))
        return
    seen = {}
    for update in result.get("result", []):
        message = (
            update.get("message")
            or update.get("channel_post")
            or update.get("edited_message")
            or {}
        )
        chat = message.get("chat")
        if not chat:
            continue
        topic = ""
        if message.get("is_topic_message"):
            topic = str(message.get("message_thread_id", ""))
        seen[(chat["id"], topic)] = chat
    if not seen:
        print("Belum ada pesan masuk.")
        print("Kirim satu pesan ke grup / topic yang dituju, lalu coba lagi.")
        return
    items = list(seen.items())
    for number, ((chat_id, topic), chat) in enumerate(items, 1):
        name = chat.get("title") or chat.get("username") or chat.get("first_name") or "?"
        extra = f" | topic {topic}" if topic else ""
        print(f"[{number}] {name} ({chat.get('type', '?')}) id {chat_id}{extra}")
    pick = ask("Pilih nomor (kosong = batal): ")
    if not pick or not pick.isdigit() or not 1 <= int(pick) <= len(items):
        print("Dibatalkan.")
        return
    (chat_id, topic), _ = items[int(pick) - 1]
    target = ask("Simpan sebagai [1] rilis, [2] tes (kosong = batal): ")
    if target == "1":
        chat_key, thread_key = "TELEGRAM_CHAT_ID", "TELEGRAM_THREAD_ID"
    elif target == "2":
        chat_key, thread_key = "TELEGRAM_TEST_CHAT_ID", "TELEGRAM_TEST_THREAD_ID"
    else:
        print("Dibatalkan.")
        return
    save_env_value(chat_key, str(chat_id))
    save_env_value(thread_key, topic)
    print(f"Tersimpan: chat {chat_id}, topic {topic or '(kosong)'}.")


USE_COLOR = sys.stdout.isatty() and not os.environ.get("NO_COLOR")
MENU_WIDTH = 34
IS_TTY = sys.stdout.isatty()
MENU_PAGES = {
    "1": "Bot token",
    "2": "Tujuan rilis",
    "3": "Tujuan tes",
    "4": "Deteksi chat & topic",
    "5": "Konten rilis",
    "6": "Gambar",
    "p": "Pratinjau",
    "t": "Kirim tes",
    "r": "Kirim rilis",
    "c": "Cek bot",
}


def clear_screen():
    if IS_TTY:
        print("\033[H\033[2J\033[3J", end="", flush=True)


def page(title):
    clear_screen()
    print(paint("╭" + "─" * MENU_WIDTH + "╮", "36"))
    heading = paint(title.upper().center(MENU_WIDTH), "1")
    print(paint("│", "36") + heading + paint("│", "36"))
    print(paint("╰" + "─" * MENU_WIDTH + "╯", "36"))


def paint(text, code):
    return f"\033[{code}m{text}\033[0m" if USE_COLOR else text


def target_text(config, chat_key, thread_key):
    chat = config.get(chat_key)
    if not chat:
        return "belum diisi"
    topic = config.get(thread_key)
    return f"{chat} #{topic}" if topic else chat


def info_row(label, value, ok=None):
    mark = ""
    if ok is not None:
        mark = " " + (paint("●", "32") if ok else paint("○", "31"))
    print(f"  {paint(label.ljust(8), '2')}{value}{mark}")


def section(title):
    print(f"\n {paint(title, '1;36')}")


def menu_key(key, text):
    print(f"  {paint('[' + key + ']', '33')} {text}")


def draw_menu(config):
    token = config.get("TELEGRAM_BOT_TOKEN", "")
    release_ok = bool(token and config.get("TELEGRAM_CHAT_ID"))
    test_ok = bool(token and config.get("TELEGRAM_TEST_CHAT_ID"))
    page("ZENITH RELEASE NOTIFY")

    section("BOT")
    info_row("Token", mask(token, True), bool(token))

    section("TUJUAN")
    info_row(
        "Rilis",
        target_text(config, "TELEGRAM_CHAT_ID", "TELEGRAM_THREAD_ID"),
        release_ok,
    )
    info_row(
        "Tes",
        target_text(config, "TELEGRAM_TEST_CHAT_ID", "TELEGRAM_TEST_THREAD_ID"),
        test_ok,
    )

    section("KONTEN")
    info_row("App", config.get("RELEASE_APP") or "BitChord")
    info_row("Tagline", config.get("RELEASE_TAGLINE") or "-")
    if config.get("RELEASE_NO_IMAGES") == "1":
        info_row("Gambar", "mati (hanya teks)")
    else:
        info_row("Gambar", config.get("RELEASE_IMAGES") or "bawaan repo")

    saved = str(ENV_FILE).replace(str(Path.home()), "~")
    print(paint(f"\n  {saved}", "2"))
    print(paint("  ● siap   ○ belum lengkap", "2"))

    section("SETELAN")
    menu_key("1", "Bot token")
    menu_key("2", "Tujuan rilis (chat & topic)")
    menu_key("3", "Tujuan tes (chat & topic)")
    menu_key("4", "Deteksi chat & topic otomatis")
    menu_key("5", "Nama app, tagline, folder gambar")
    menu_key("6", "Gambar nyala / mati")

    section("KIRIM")
    menu_key("p", "Pratinjau (dry run)")
    menu_key("t", "Kirim tes ke chat tes")
    menu_key("r", "Kirim rilis ke chat rilis")
    menu_key("c", "Cek bot")
    menu_key("q", "Keluar")


def toggle_images():
    off = read_env_file().get("RELEASE_NO_IMAGES") == "1"
    save_env_value("RELEASE_NO_IMAGES", "" if off else "1")
    print("Gambar: nyala." if off else "Gambar: mati, rilis dikirim hanya teks.")


def run_menu():
    while True:
        config = read_env_file()
        draw_menu(config)
        choice = ask("\n> Pilih: ")
        if choice is None or choice.lower() == "q":
            clear_screen()
            print("Sampai jumpa.")
            return
        choice = choice.lower()
        if choice not in MENU_PAGES:
            continue
        page(MENU_PAGES[choice])
        print()
        if choice == "1":
            edit_field(*MENU_FIELDS[0])
        elif choice == "2":
            edit_target("TELEGRAM_CHAT_ID", "TELEGRAM_THREAD_ID", "rilis")
        elif choice == "3":
            edit_target("TELEGRAM_TEST_CHAT_ID", "TELEGRAM_TEST_THREAD_ID", "tes")
        elif choice == "4":
            detect_chats(config)
        elif choice == "5":
            for field in MENU_FIELDS[5:]:
                edit_field(*field)
        elif choice == "6":
            toggle_images()
        elif choice == "p":
            run_cli(menu_args(config, dry=True))
        elif choice == "t":
            run_cli(menu_args(config, test=True))
        elif choice == "r":
            run_cli(menu_args(config))
        elif choice == "c":
            check_bot(load_config())
        ask(paint("\nEnter untuk kembali ke menu...", "2"))


def main():
    parser = argparse.ArgumentParser(description="Send the release announcement to Telegram.")
    parser.add_argument("--app", default="BitChord", help="app name in the title")
    parser.add_argument("--version", help="override the version read from build.gradle.kts")
    parser.add_argument("--tagline", default="", help="one line under the title")
    parser.add_argument("--notes", default=str(ROOT / "RELEASE_NOTES.md"))
    parser.add_argument("--gradle", default=str(ROOT / "build.gradle.kts"))
    parser.add_argument(
        "--images",
        default=str(ROOT / ".github" / "assets" / "release"),
        help="folder with release images",
    )
    parser.add_argument("--no-images", action="store_true")
    parser.add_argument("--test", action="store_true", help="send to the test chat")
    parser.add_argument("--dry-run", action="store_true", help="print the message, send nothing")
    parser.add_argument("--yes", action="store_true", help="skip the confirmation")
    args = parser.parse_args()

    version = args.version or read_version(args.gradle)
    notes = Path(args.notes)
    if not notes.exists():
        sys.exit(f"{args.notes} not found")
    sections = parse_notes(notes.read_text(encoding="utf-8"))
    if not sections:
        sys.exit(f"No '### Section' with '- item' lines found in {args.notes}")

    images = []
    folder = Path(args.images).expanduser()
    if not args.no_images and folder.is_dir():
        images = sorted(p for p in folder.iterdir() if p.suffix.lower() in IMAGE_TYPES)[:ALBUM_MAX]

    caption = f"<b>{esc(args.app)} {esc(version)}</b>"
    if args.tagline:
        caption += f"\n<i>{esc(args.tagline)}</i>"
    head = f"<b>What's new in {esc(version)}</b>" if images else caption
    messages = chunk(head, [render(title, items) for title, items in sections])

    if args.dry_run:
        print(f"Images ({len(images)}):")
        for path in images:
            print(f"  {path}")
        if images:
            print("\n--- album caption ---\n" + caption)
        for number, message in enumerate(messages, 1):
            print(f"\n--- message {number}/{len(messages)} ({len(message)} chars) ---\n{message}")
        return

    config = load_config()
    prefix = "TELEGRAM_TEST_" if args.test else "TELEGRAM_"
    token = config.get("TELEGRAM_BOT_TOKEN")
    chat = config.get(prefix + "CHAT_ID")
    thread = config.get(prefix + "THREAD_ID")
    if not token or not chat:
        sys.exit(f"Set TELEGRAM_BOT_TOKEN and {prefix}CHAT_ID in {ENV_FILE}")
    if images and not shutil.which("curl"):
        sys.exit("curl is required to upload images")

    if not args.test and not args.yes:
        answer = input(
            f"Send {args.app} {version} ({len(images)} image(s), {len(messages)} message(s)) "
            "to the release chat? [y/N] "
        )
        if answer.strip().lower() != "y":
            sys.exit("Cancelled")

    if images:
        check(send_images(token, chat, thread, images, caption), f"{len(images)} image(s)")
    for number, message in enumerate(messages, 1):
        check(send_text(token, chat, thread, message), f"message {number}/{len(messages)}")


def menu():
    if IS_TTY:
        print("\033[?1049h", end="", flush=True)
    try:
        run_menu()
    finally:
        if IS_TTY:
            print("\033[?1049l", end="", flush=True)
            print("Sampai jumpa.")


if __name__ == "__main__":
    if len(sys.argv) > 1:
        main()
    else:
        menu()