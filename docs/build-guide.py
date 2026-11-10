#!/usr/bin/env python3
"""Render docs/ADMIN-GUIDE.md to docs/ADMIN-GUIDE.html (needs: pip install markdown).

The .md stays the source of truth; the HTML is the styled copy served on auth.veilo.tr.
"""
import html, pathlib, re
import markdown

here = pathlib.Path(__file__).parent
src = (here / "ADMIN-GUIDE.md").read_text(encoding="utf-8")
md = markdown.Markdown(extensions=["tables", "toc", "sane_lists"], extension_configs={"toc": {"toc_depth": "2"}})
body = md.convert(src)
title = re.search(r"^# (.+)$", src, re.M).group(1)
body = re.sub(r"<h1[^>]*>.*?</h1>\s*", "", body, count=1, flags=re.S)
lead_end = body.find("<h2")
lead, rest = body[:lead_end], body[lead_end:]
sections = [c for t in md.toc_tokens for c in (t["children"] or [t])]
toc = "".join(f'<a href="#{t["id"]}">{html.escape(t["name"])}</a>' for t in sections)

page = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Administrator Guide — Veilo Authenticator</title>
<meta name="description" content="Deploy Veilo Authenticator through your MDM and govern it with organisation policy.">
<link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Cpath d='M16 2l11 4v9c0 7-5 12-11 15C10 27 5 22 5 15V6z' fill='%233b82f6'/%3E%3C/svg%3E">
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&family=Orbitron:wght@500;600&family=Syne:wght@600;700;800&display=swap" rel="stylesheet">
<style>
:root{{--bg:#0b1628;--panel:#111e33;--card:#162240;--blue:#3b82f6;--blue-soft:rgba(96,165,250,.15);--blue-line:rgba(59,130,246,.3);--green:#34d399;--amber:#f59e0b;--text:#f8fafc;--muted:#b8c5da;--faint:#64748b}}
*{{box-sizing:border-box;margin:0;padding:0}}
html{{scroll-behavior:smooth;scroll-padding-top:90px}}
body{{font-family:Inter,system-ui,sans-serif;background:radial-gradient(circle at 80% -10%,rgba(59,130,246,.14),transparent 45%),var(--bg);color:var(--muted);line-height:1.75;font-size:16px}}
a{{color:var(--blue);text-decoration:none}} a:hover{{color:#93c5fd}}
.top{{position:sticky;top:0;z-index:10;backdrop-filter:blur(14px);background:rgba(11,22,40,.85);border-bottom:1px solid var(--blue-soft)}}
.top div{{max-width:1120px;margin:0 auto;padding:14px 24px;display:flex;justify-content:space-between;align-items:center;gap:16px}}
.brand{{font-family:Orbitron,monospace;letter-spacing:.14em;font-size:.8rem;color:var(--text)}} .brand b{{color:var(--blue)}}
.top a.back{{font-size:.85rem;border:1px solid var(--blue-line);padding:7px 14px;border-radius:9px;color:var(--text)}}
.top a.back:hover{{background:rgba(59,130,246,.15);border-color:var(--blue)}}
.hero{{max-width:1120px;margin:0 auto;padding:64px 24px 24px}}
.tag{{display:inline-block;font-family:Orbitron,monospace;font-size:.66rem;letter-spacing:.16em;color:var(--green);border:1px solid rgba(16,185,129,.4);background:rgba(16,185,129,.08);padding:5px 14px;border-radius:999px;margin-bottom:20px}}
h1{{font-family:Syne,sans-serif;font-size:clamp(1.9rem,4.5vw,3rem);line-height:1.15;color:var(--text);max-width:820px}}
h1 span{{color:var(--blue)}}
.lead{{max-width:760px;margin-top:20px;font-size:1.05rem}}.lead p{{margin-bottom:12px}}
.layout{{max-width:1120px;margin:0 auto;padding:24px;display:grid;grid-template-columns:220px minmax(0,1fr);gap:48px}}
nav.toc{{position:sticky;top:90px;align-self:start;display:flex;flex-direction:column;gap:2px;border-left:1px solid var(--blue-soft)}}
nav.toc a{{padding:8px 14px;font-size:.86rem;color:var(--muted);border-left:2px solid transparent;margin-left:-1px;transition:.2s}}
nav.toc a:hover{{color:var(--text);border-left-color:var(--blue);background:rgba(59,130,246,.08)}}
nav.toc small{{padding:0 14px 8px;font-family:Orbitron,monospace;font-size:.62rem;letter-spacing:.16em;color:var(--faint)}}
main{{min-width:0}}
main h2{{font-family:Syne,sans-serif;font-size:1.5rem;color:var(--text);margin:56px 0 18px;padding-bottom:12px;border-bottom:1px solid var(--blue-soft)}}
main h2:first-child{{margin-top:0}}
main p,main ul,main ol{{margin-bottom:16px}}
main ul,main ol{{padding-left:1.4rem}} main li{{margin-bottom:8px}} main li::marker{{color:var(--blue)}}
strong{{color:var(--text);font-weight:600}}
code{{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.86em;background:rgba(59,130,246,.12);border:1px solid var(--blue-soft);color:#bfdbfe;padding:2px 7px;border-radius:6px}}
.tablewrap{{overflow-x:auto;border:1px solid var(--blue-line);border-radius:14px;background:rgba(22,34,64,.6);margin:20px 0 24px;box-shadow:0 8px 32px rgba(0,0,0,.25)}}
table{{width:100%;border-collapse:collapse;font-size:.9rem;min-width:620px}}
th{{text-align:left;font-family:Orbitron,monospace;font-size:.66rem;letter-spacing:.12em;text-transform:uppercase;color:var(--blue);background:rgba(11,22,40,.8);padding:14px 18px}}
td{{padding:14px 18px;border-top:1px solid var(--blue-soft);vertical-align:top}}
td:first-child{{color:var(--text);font-weight:600;min-width:200px;width:30%}}
tbody tr{{transition:background .2s}} tbody tr:hover{{background:rgba(59,130,246,.14)}}
main ol{{counter-reset:s;list-style:none;padding-left:0}}
main ol>li{{counter-increment:s;position:relative;padding:14px 18px 14px 58px;background:rgba(22,34,64,.6);border:1px solid var(--blue-soft);border-radius:12px;transition:.25s}}
main ol>li:hover{{border-color:var(--blue);background:rgba(59,130,246,.12)}}
main ol>li::before{{content:counter(s);position:absolute;left:16px;top:50%;transform:translateY(-50%);width:28px;height:28px;border-radius:50%;background:rgba(59,130,246,.2);border:1px solid var(--blue-line);color:var(--text);font-family:Orbitron,monospace;font-size:.75rem;display:grid;place-items:center}}
main ul>li{{padding-left:4px}}
footer{{max-width:1120px;margin:40px auto 0;padding:28px 24px 48px;border-top:1px solid var(--blue-soft);font-size:.85rem;color:var(--faint);display:flex;justify-content:space-between;flex-wrap:wrap;gap:10px}}
@media(max-width:900px){{.layout{{grid-template-columns:1fr;gap:12px}}nav.toc{{position:static;flex-direction:row;flex-wrap:wrap;border:0;gap:6px}}nav.toc small{{width:100%}}nav.toc a{{border:1px solid var(--blue-soft);border-radius:8px;margin:0}}}}
</style>
</head>
<body>
<header class="top"><div><span class="brand"><b>VEILO</b> AUTHENTICATOR</span><a class="back" href="./#enterprise">&larr; Back to Enterprise</a></div></header>
<section class="hero">
<span class="tag">ENTERPRISE &middot; MDM &middot; v0.4</span>
<h1>Administrator guide: <span>Veilo Authenticator</span> on managed devices</h1>
<div class="lead">{lead}</div>
</section>
<div class="layout">
<nav class="toc"><small>ON THIS PAGE</small>{toc}</nav>
<main>{rest.replace("<table>", '<div class="tablewrap"><table>').replace("</table>", "</table></div>")}</main>
</div>
<footer><span>Veilo Authenticator &middot; Apache-2.0</span><span><a href="ADMIN-GUIDE.md">Raw Markdown</a> &middot; <a href="./">auth.veilo.tr</a></span></footer>
</body>
</html>
"""
(here / "ADMIN-GUIDE.html").write_text(page, encoding="utf-8")
print("wrote ADMIN-GUIDE.html")
