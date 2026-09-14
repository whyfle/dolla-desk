# DollaDesk — know who owes you money

A tiny web app so freelancers never lose track of who pays and who doesn't.
Add an invoice when you do work, mark it paid when money lands, and the dashboard
always shows billed / paid / pending / overdue. CSV export for your accountant.

No login. No monthly fee to run it. No 50-field accounting monster.
Just open it and use it.

## Why people would use this

- Forgets Excel: one page, search + sort + overdue highlighting
- Works on phone and PC at the same URL
- CSV export in one click
- Real API so you (or GPT) can build on top: bots, reminders, dashboards
- Runs for $0/mo on free hosting (see HOSTING.md)

## Quickstart (local, 30 seconds)

You only need JDK 17. No Node, no Python, no database.

```
cd dolla-desk
./run.sh          # Linux / macOS
# or double-click run.bat  # Windows
```

Then open http://localhost:8080

Data saves to `dolla-desk/data.json`. Delete that file to reset.

## What is where

```
dolla-desk/
  src/Main.java        backend: HTTP server + JSON API + static file server, zero deps
  public/index.html    front page + app (hero explains WTF this is, then the tracker)
  public/app.js        fetches /api/invoices, renders table + totals
  public/style.css     dark UI
  public/api.html      human API docs (also GET /api returns JSON docs)
  public/test.html     API test page with live checks
  run.sh               Linux / macOS launcher
  run.bat              Windows launcher
  Dockerfile           free-host deploy (Render / Koyeb / any Docker host)
  render.yaml          Render free plan + /api/health check
  HOSTING.md           how to host for $0
  README.md            this file
```

## How to use (normal human version)

1. Add invoice: client name + amount + due date + optional note (e.g. "Logo design")
2. Watch the 4 numbers on top: Billed / Paid / Pending / Overdue count
3. Search box filters by client or note, chips filter All / Pending / Paid
4. Mark paid when money arrives, Delete if you messed up
5. Export CSV before tax time

Overdue = not paid + due date is before today. It gets a red badge.

## API (for nerds / automation)

Base = same host. All JSON except export.

```
GET  /api/health                        -> {"ok":true,"app":"dolla-desk","version":"2.0.0"}
GET  /api                               -> list of endpoints
GET  /api/stats                         -> {"count","billed","paid","pending","overdue"}
GET  /api/invoices?q=acme&paid=false&sort=due
POST /api/invoices                      body: {"client":"Acme","amount":500,"due":"2026-10-01","notes":"Logo"}
POST /api/invoices/toggle?id=abcd1234   -> flips paid true/false
DELETE /api/invoices?id=abcd1234
GET  /api/export.csv                    -> downloads invoices.csv
GET  /api.html                          -> human docs page
GET  /test.html                         -> live API test page
```

curl examples:

```
curl localhost:8080/api/stats
curl -X POST localhost:8080/api/invoices -H "Content-Type: application/json" -d "{\"client\":\"Acme\",\"amount\":250.5,\"due\":\"2026-10-01\",\"notes\":\"Site fix\"}"
curl -X POST "localhost:8080/api/invoices/toggle?id=YOURID"
```

Validation: client 1-80 chars, amount 0.01-1000000 (rounded to 2 decimals),
due YYYY-MM-DD real date, notes max 200 chars.

## Cross-platform

The backend is pure Java 17 with `com.sun.net.httpserver` — zero external
libraries. It runs anywhere Java runs.

- `run.sh` — Linux / macOS (bash + JDK 17)
- `run.bat` — Windows
- `Dockerfile` — any platform that supports Docker
- `render.yaml` — Render free deploy, uses Docker so the same image everywhere

No platform-specific code, no Node, no Python, no database. That is the point.

## Hosting for free (max margin)

Full guide in HOSTING.md. Shortest version:

1. Upload this folder to a new public GitHub repo (web upload, no terminal needed)
2. dashboard.render.com -> New -> Web Service -> pick repo -> Docker -> Free -> `/api/health`
3. You get `https://yours.onrender.com`. Free tier sleeps when idle and disk is ephemeral.

Want no-sleep + real persistence? Oracle Always Free VM, or add free Neon/Supabase Postgres later.

## Make dolla with it (honest version)

Hosting free does not mean customers appear. What works:

1. Give it to 10 freelancers free, watch what they complain about
2. Add the top request (usually: PDF invoice, login, reminders)
3. Charge $6-9/mo via Stripe Payment Link, post demo videos where freelancers hang out

## FAQ

No auth? Correct, v2 is single-user. Don't put it public with real client data unless you add a password. Run locally or add basic auth in front (Cloudflare Access free).

Data lost on Render? Yes on redeploy, free disk is ephemeral. Keep local copy via Export CSV, or upgrade to free Postgres.

Can I change the frontend? Yes, it's vanilla HTML/CSS/JS on purpose so any AI can restyle it. Backend IDs are stable: tBilled/tPaid/tPending/tOverdue, form/client/amount/due/notes, q/sort/rows.

License: do whatever you want with it, keep the name if you're nice.
