# HOSTING — free, max margin

No host gives 9999999% margin forever, but you can get very close to $0/mo.
Real math: free tier ($0) + your time + ~$0-12/yr if you want a custom domain.

## Option A — Render Free (easiest, recommended)

1. Put `dolla-desk/` in its own GitHub repo (don't paste tokens in chat — use `gh auth login`):
   ```
   cd dolla-desk
   git init
   git add .
   git commit -m "dolla-desk v2"
   gh repo create dolla-desk --public --source=. --push
   ```
2. Go to dashboard.render.com → New → Web Service → select your repo
3. Settings:
   - Runtime: Docker (uses the included Dockerfile)
   - Plan: Free
   - Health check: `/api/health`
   - Leave PORT alone — Render injects it, the app reads `$PORT`
4. Deploy → you get `https://dolla-desk-xxxx.onrender.com`
5. Free limits: sleeps after ~15 min idle (first load is slow), 750 hrs/mo, disk is ephemeral (data.json resets on redeploy).

Fix persistence free: plug in free Postgres later (Render free Postgres 90 days, or Neon/Supabase free tier) — v2 currently uses a JSON file on purpose so it runs with zero config.

Keep it awake free: add a free UptimeRobot monitor pinging `/api/health` every 5 min.

## Option B — Koyeb Free

1. Same GitHub push as above
2. app.koyeb.com → Create App → GitHub repo → Docker → Free / Hobby
3. Health check `/api/health`, port 8080 autodetected via $PORT
4. Similar sleep/ephemeral limits as Render.

## Option C — Oracle Always Free (truly free VM, no sleep)

1. cloud.oracle.com → Always Free Ampere VM (4 CPU / 24GB free forever)
2. SSH in, install Java 17 + Docker, `git clone` your repo, `docker build -t dolla .`, `docker run -p 80:8080 dolla`
3. Add Cloudflare (free) in front for SSL + free domain routing.
4. Best margin long-term, more setup.

## Custom domain for ~$0-12/yr

- Freenom-style free domains are mostly dead. Cheapest real: Cloudflare Registrar (~$9/yr .com) or use the free `*.onrender.com` subdomain = $0.
- Cloudflare free plan = free SSL + CDN.

## How to actually make dolla

1. Ship free version, ask 10 freelancers to try it
2. Add what they beg for (login, PDF invoices, reminders)
3. Charge via Stripe Payment Link ($6-9/mo) — no code needed to start
4. Post build-in-public clips. Hosting stays $0 until you have paying users.
