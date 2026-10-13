#!/usr/bin/env python3
"""Write the download page (index.html) and latest.json for https://chagai.website/matrix-chat/.

Called by release.sh with the target directory as argv[1] and everything it needs in the
environment, so the shell never has to template HTML.
"""
from __future__ import annotations

import html
import json
import os
import pathlib
import re
import sys

out = pathlib.Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)

version = os.environ["VERSION"]
version_code = os.environ["VERSION_CODE"]
sha_arm64 = os.environ["SHA_ARM64"]
size_arm64 = int(os.environ["SIZE_ARM64"])
sha_univ = os.environ.get("SHA_UNIV") or None
notes = os.environ.get("NOTES", "")
base = os.environ["PUBLIC_URL"].rstrip("/")
today = os.environ["TODAY"]
changelog_path = pathlib.Path(os.environ.get("CHANGELOG", "CHANGELOG-chagai.md"))

apk_name = f"matrix-chat-{version}-arm64.apk"
apk_url = f"{base}/{apk_name}"

latest = {
    "app": "Matrix Chat",
    "applicationId": "website.chagai.matrixchat",
    "version": version,
    "versionCode": int(version_code),
    "released": today,
    "defaultHomeserver": "chagai.website",
    "upstream": "https://github.com/element-hq/element-x-android",
    "source": "https://github.com/chagai95/element-x-android",
    "licence": "AGPL-3.0-only",
    "notes": notes,
    "artifacts": [
        {"abi": "arm64-v8a", "url": apk_url, "sha256": sha_arm64, "size": size_arm64},
    ],
}
if sha_univ:
    latest["artifacts"].append(
        {"abi": "universal", "url": f"{base}/matrix-chat-{version}-universal.apk", "sha256": sha_univ}
    )
(out / "latest.json").write_text(json.dumps(latest, indent=2) + "\n")


def changelog_sections(limit: int = 6) -> list[tuple[str, list[str]]]:
    """The last few '## <version> — <date>' blocks of the changelog, as (heading, bullets)."""
    if not changelog_path.exists():
        return []
    sections: list[tuple[str, list[str]]] = []
    current: tuple[str, list[str]] | None = None
    for line in changelog_path.read_text().splitlines():
        if line.startswith("## "):
            if current:
                sections.append(current)
            current = (line[3:].strip(), [])
        elif current is not None and line.strip().startswith("- "):
            current[1].append(line.strip()[2:])
    if current:
        sections.append(current)
    return sections[:limit]


def md_inline(text: str) -> str:
    """Escape, then re-allow `code` and **bold** — the only markup the changelog uses."""
    t = html.escape(text)
    t = re.sub(r"`([^`]+)`", r"<code>\1</code>", t)
    t = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", t)
    return t


rows = "\n".join(
    f"      <section>\n        <h3>{md_inline(title)}</h3>\n        <ul>\n"
    + "\n".join(f"          <li>{md_inline(b)}</li>" for b in bullets)
    + "\n        </ul>\n      </section>"
    for title, bullets in changelog_sections()
)

page = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Matrix Chat</title>
<link rel="icon" href="icon.png">
<style>
  :root {{
    color-scheme: light dark;
    --bg: #ffffff; --fg: #15191e; --muted: #5b6672; --card: #f4f6f8;
    --line: #dfe4e9; --accent: #0a9d74;
  }}
  @media (prefers-color-scheme: dark) {{
    :root {{ --bg: #101419; --fg: #e9eef3; --muted: #99a4b0; --card: #171c23;
             --line: #262d36; --accent: #0dbd8b; }}
  }}
  * {{ box-sizing: border-box; }}
  body {{ margin: 0; background: var(--bg); color: var(--fg);
         font: 16px/1.6 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }}
  main {{ max-width: 42rem; margin: 0 auto; padding: 2.5rem 1.25rem 4rem; }}
  header {{ display: flex; align-items: center; gap: 1rem; margin-bottom: .5rem; }}
  header img {{ width: 64px; height: 64px; border-radius: 14px; }}
  h1 {{ font-size: 1.75rem; margin: 0; letter-spacing: -.01em; }}
  .sub {{ color: var(--muted); margin: .15rem 0 0; }}
  .cta {{ display: inline-block; margin: 1.75rem 0 .5rem; padding: .85rem 1.5rem;
          background: var(--accent); color: #04120d; font-weight: 700; border-radius: 10px;
          text-decoration: none; }}
  .cta:hover {{ filter: brightness(1.08); }}
  dl {{ display: grid; grid-template-columns: max-content 1fr; gap: .4rem 1.25rem;
        background: var(--card); border: 1px solid var(--line); border-radius: 12px;
        padding: 1rem 1.25rem; margin: 1.5rem 0; }}
  dt {{ color: var(--muted); }}
  dd {{ margin: 0; overflow-wrap: anywhere; }}
  code {{ font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .86em; }}
  h2 {{ font-size: 1.05rem; text-transform: uppercase; letter-spacing: .06em;
        color: var(--muted); margin: 2.5rem 0 .5rem; }}
  section h3 {{ font-size: 1rem; margin: 1.25rem 0 .35rem; }}
  ul {{ margin: .35rem 0; padding-left: 1.15rem; }}
  footer {{ color: var(--muted); font-size: .88rem; margin-top: 3rem;
            border-top: 1px solid var(--line); padding-top: 1.25rem; }}
  a {{ color: var(--accent); }}
</style>
</head>
<body>
<main>
  <header>
    <img src="icon.png" alt="">
    <div>
      <h1>Matrix Chat</h1>
      <p class="sub">Version {html.escape(version)} &middot; {html.escape(today)}</p>
    </div>
  </header>

  <a class="cta" href="{html.escape(apk_name)}">Download the APK (arm64)</a>

  <dl>
    <dt>Signs in to</dt><dd>chagai.website (changeable on the sign-in screen)</dd>
    <dt>App ID</dt><dd><code>website.chagai.matrixchat</code></dd>
    <dt>Version code</dt><dd><code>{html.escape(str(version_code))}</code></dd>
    <dt>Size</dt><dd>{size_arm64 / (1024 * 1024):.1f} MB</dd>
    <dt>SHA-256</dt><dd><code>{html.escape(sha_arm64)}</code></dd>
  </dl>

  <h2 id="whats-new">What&rsquo;s new</h2>
{rows}

  <h2 id="terms">Installing</h2>
  <p>Android will ask you to allow installing from the browser the first time. The app installs
  <em>next to</em> the official Element X rather than replacing it, so nothing you already have
  is touched.</p>

  <h2 id="privacy">Privacy</h2>
  <p>This is a personal build for one person. It sends nothing anywhere except to the Matrix
  homeserver you sign in to. There is no analytics key, no crash-reporting key and no tracking
  of any kind compiled into it.</p>

  <h2 id="licence">Licence &amp; source</h2>
  <p>Matrix Chat is a fork of
  <a href="https://github.com/element-hq/element-x-android">Element X Android</a>, which is
  licensed under the <strong>AGPL-3.0</strong>. The complete corresponding source for this build
  is published at
  <a href="https://github.com/chagai95/element-x-android">github.com/chagai95/element-x-android</a>.
  Element, Element X and the Element logo are trademarks of Element Creations Ltd; this fork is
  not affiliated with or endorsed by them.</p>

  <footer>
    Built on chagai.website &middot;
    <a href="latest.json">latest.json</a>
  </footer>
</main>
</body>
</html>
"""
(out / "index.html").write_text(page)
print(f"  wrote index.html + latest.json for {version}")
