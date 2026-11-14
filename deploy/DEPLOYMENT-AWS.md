# MiniPolya on one AWS EC2 server — a test deployment

How to put MiniPolya on a single Ubuntu server on AWS, step by step, from an account that
does not exist yet to a working `https://…` the Mini App opens inside Telegram.

Written for someone who has not used AWS before. Everything that happens in a browser is
the **AWS web console**; everything else is a command you paste into a terminal. Every
command is given in full — nothing is left as "and then configure Docker".

> **This is a test deployment, not production.**
>
> - Payments are **fake**. `FakePaymentProvider` runs, no real card is ever charged, and no
>   payment provider contract is involved.
> - The Telegram bot runs on **polling** with a **test** bot, not a webhook.
> - It is **one server**: no load balancer, no standby, no failover. A reboot is downtime.
> - The database is backed up nightly to the same disk (and you copy those backups off it).
>
> What production would need instead is in the backend's `CLAUDE.md`, under "Deployment
> checklist". Do not point real users or real money at this server.

**Before you start, on your own machine:** run `./mvnw test` in `minipolyaBackend` and
`npm run typecheck && npm run lint && npm run test && npm run build` in `minipolyaFrontend`.
The server builds both images without running tests — a 2 GB box is the wrong place to find
out that something is broken.

---

## What you end up with

```
                              internet
                                 │   only ports 80 and 443 are open
                                 │   (plus SSH on 22, from your IP only)
                                 ▼
    ┌──────────────────────────────────────────────────────┐
    │ caddy        HTTPS for https://<SITE_HOST>           │  the only published ports
    │   /                → the Mini App  (index.html)      │
    │   /admin, /admin/* → the admin panel (admin.html)    │
    │   /assets/*        → hashed files, cached a year     │
    │   /api/*           → backend:8080 ───────────────────┼──┐
    │   /api/uploads/*   → the stadium photos, same route  │  │
    └──────────────────────────────────────────────────────┘  │  internal only, plain http
                                                              ▼
    ┌──────────────────────────────────────────────────────┐  ┌──────────────────────────┐
    │ backend      Spring Boot 4, Java 21, profile=staging │─▶│ postgres:17              │
    │   no published port — unreachable from outside       │  │   no published port      │
    │   stadium photos on the uploads_data volume          │  │   data on a named volume │
    └──────────────────────────────────────────────────────┘  └──────────────────────────┘
                                                                  │ nightly pg_dump
                                                                  ▼
                                                        ~/minipolya/backups  (14 days)
```

Three things follow from that picture, and they are why the rest is simple:

1. **One origin.** The Mini App, the admin panel and the API all answer on
   `https://<SITE_HOST>`. The browser never makes a cross-origin request, so there is no
   CORS anywhere — `CORS_ALLOWED_ORIGINS` stays empty and the frontend is built with
   `VITE_API_URL` empty.
2. **The backend and the database have no published ports.** From the internet they do not
   exist. The only route to the backend is Caddy's `/api/*`.
3. **Because only Caddy can reach the backend**, the `X-Forwarded-For` header Caddy sets is
   trustworthy — which is what makes the admin sign-in rate limit per-IP rather than
   per-proxy, and what makes the audit log record real client addresses.

### Where the files are

```
~/minipolya/
├── minipolyaBackend/          ← this repository
│   ├── Dockerfile               the backend image (Maven JDK 21 → JRE 21, non-root)
│   └── deploy/
│       ├── docker-compose.yml   the three containers
│       ├── Caddyfile            routing, HTTPS, caching, security headers
│       ├── .env.example         → copy to .env and fill in, ON THE SERVER
│       ├── backup.sh            the nightly pg_dump
│       └── DEPLOYMENT-AWS.md    this file
├── minipolyaFrontend/         ← the other repository, cloned beside it
│   └── Dockerfile               npm ci → npm run build → dist/ into a Caddy image
└── backups/                   ← created by backup.sh
```

The two repositories are cloned **side by side** because the frontend image has to be built
from the frontend repository: `npm ci` needs its `package-lock.json`. `docker-compose.yml`
refers to it as `../../minipolyaFrontend`, so the names of those two directories matter.

The deploy files live in the **backend** repository because that is where the server-side
configuration already is (`application-staging.yaml`, the profile guards). The Caddyfile is
**mounted**, not built into the image, so you can change a header or a route and reload it
in a second without rebuilding the frontend bundle.

---

## Contents

1. [Account safety first](#1-account-safety-first)
2. [Launch the server](#2-launch-the-server)
3. [Connect with SSH from Windows](#3-connect-with-ssh-from-windows)
4. [Set the server up](#4-set-the-server-up)
5. [Get the code onto the server](#5-get-the-code-onto-the-server)
6. [Fill in `.env`](#6-fill-in-env)
7. [Start it](#7-start-it)
8. [Create the first admin user](#8-create-the-first-admin-user)
9. [Telegram: the bot and the Mini App](#9-telegram-the-bot-and-the-mini-app)
10. [Yandex Maps: allow the host](#10-yandex-maps-allow-the-host)
11. [Deploying a change, and rolling back](#11-deploying-a-change-and-rolling-back)
12. [Backups: where, how to copy, how to restore](#12-backups-where-how-to-copy-how-to-restore)
13. [Logs and troubleshooting](#13-logs-and-troubleshooting)
14. [What it costs, and the free plan's deadline](#14-what-it-costs-and-the-free-plans-deadline)

---

## 1. Account safety first

Do this before launching anything. An AWS account with a password and no MFA, and no budget
alert, is the one way a test server becomes an expensive mistake — either because someone
signs in as you and mines cryptocurrency on your card, or because you forget the instance
is running.

### 1.1 Turn on MFA for the root user

The **root user** is the email address you signed up with. It can do anything, including
close the account and change the payment method, and it cannot be restricted. It is the one
login that must have a second factor.

1. Sign in at <https://console.aws.amazon.com/> as the root user.
2. Top right, click your account name → **Security credentials**.
3. Under **Multi-factor authentication (MFA)**, click **Assign MFA device**.
4. Give it a name (`phone`), choose **Authenticator app**, click **Next**.
5. Scan the QR code with Google Authenticator, Authy, 1Password or Microsoft Authenticator.
6. Type **two consecutive codes** from the app, then **Add MFA**.

**Save the recovery path now:** write down which app and which phone holds it. If you lose
that phone with no backup, recovering a root login takes days and a phone call.

From here on, sign in as root only when you must (billing, closing the account). For
everyday work, AWS recommends creating an IAM user — but for a one-server test deployment,
root with MFA is an acceptable simplification, and one fewer thing to get wrong.

### 1.2 Set budget alerts at $10 and $20

A budget alert does **not** stop spending. It emails you. That is the point: you find out
in a day, not in a monthly invoice.

1. In the console search bar, type **Billing and Cost Management** and open it.
2. In the left menu, **Budgets** → **Create budget**.
3. Choose **Customize (advanced)** → budget type **Cost budget** → **Next**.
4. Name: `minipolya-10`. Period: **Monthly**. Budget renewal type: **Recurring budget**.
5. Choose **Fixed** budget amount and enter **10**.
6. **Next**, then **Add an alert threshold**:
   - Threshold: **100** % of budgeted amount
   - Trigger: **Actual**
   - Email recipients: your email address
7. Add a **second** threshold on the same budget: **80** % of **Forecasted** cost. That one
   warns you mid-month that you are *heading* for $10, which is the warning you actually
   want.
8. **Next** → **Create budget**.
9. Repeat the whole thing for `minipolya-20` with **20**.

> Confirm the alert email. AWS sends a subscription confirmation; until you click it, the
> alert goes nowhere.

### 1.3 Work in Frankfurt (eu-central-1)

Pick the region **before** creating anything: EC2 resources belong to one region, and a
security group or Elastic IP made in the wrong region is invisible from the right one.

Top right of the console, next to your account name, there is a region menu. Choose
**Europe (Frankfurt) eu-central-1**. Check it on every page in the rest of this guide — it
is the single most common reason a resource "disappears".

Frankfurt because it is the closest low-latency region to Tashkent with full service
coverage and EU data-protection rules. (Round-trip time Tashkent → Frankfurt is roughly
80–110 ms, which is fine for this.)

---

## 2. Launch the server

### 2.1 Start the wizard

1. Console search bar → **EC2** → **Instances** → **Launch instances**.
2. **Name**: `minipolya-test`.

### 2.2 The image: Ubuntu 24.04 LTS

Under **Application and OS Images**:

1. Click **Ubuntu**.
2. In the **Amazon Machine Image (AMI)** dropdown, choose **Ubuntu Server 24.04 LTS (HVM),
   SSD Volume Type**.
3. Under **Architecture**, choose:
   - **64-bit (Arm)** if you will use `t4g.small` — **recommended**, see below
   - **64-bit (x86)** if you will use `t3.small`

**Get this right before picking the instance type.** An ARM AMI will not boot on an x86
instance type and the wizard will simply not offer the type you want.

### 2.3 The instance type

| Type | vCPU | RAM | Architecture | Roughly, per month |
|---|---|---|---|---|
| **`t4g.small`** | 2 | 2 GB | ARM (Graviton) | **≈ $14** |
| `t3.small` | 2 | 2 GB | x86 | ≈ $17 |

**Use `t4g.small`.** It is the cheaper of the two for the same 2 GB, and every image in this
deployment — `postgres:17`, `maven:…-eclipse-temurin-21`, `eclipse-temurin:21-jre-noble`,
`node:22-bookworm-slim`, `caddy:2.10-alpine` — is published for ARM as well as x86, so the
same `docker compose up -d --build` works unchanged. Nothing here contains native x86 code.

Choose `t3.small` only if you later add a tool with no ARM build.

> Both are **burstable** (T-series) instances: they earn CPU credits while idle and spend
> them under load. A long `npm run build` can use up credits and get throttled. That is
> survivable — the build takes a few minutes longer — and §13 shows how to see it.

### 2.4 The key pair — this is the only copy

Under **Key pair (login)**, click **Create new key pair**:

1. Name: `minipolya-test`
2. Key pair type: **ED25519** (shorter and faster than RSA; OpenSSH on Windows 11
   supports it)
3. Private key file format: **.pem**
4. Click **Create key pair**. The browser downloads `minipolya-test.pem`.

**AWS does not keep a copy.** Lose this file and you cannot SSH into this instance again —
the only recovery is detaching the disk and attaching it to another instance.

Move it out of `Downloads` right now, in PowerShell:

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\.ssh" | Out-Null
Move-Item "$env:USERPROFILE\Downloads\minipolya-test.pem" "$env:USERPROFILE\.ssh\minipolya-test.pem"
```

Then back it up somewhere that is not this laptop — a password manager's file attachment is
ideal. Do **not** put it in either Git repository (both `.gitignore` `*.pem`-shaped files
only by accident; treat it as radioactive).

### 2.5 The security group — the firewall that actually matters

Under **Network settings**, click **Edit**. Security group name: `minipolya-test-sg`.
Description: `MiniPolya test server`.

Create exactly **three** inbound rules:

| # | Type | Protocol | Port | Source | Why |
|---|---|---|---|---|---|
| 1 | SSH | TCP | 22 | **My IP** | only you, for administration |
| 2 | HTTP | TCP | 80 | Anywhere-IPv4 `0.0.0.0/0` | Let's Encrypt's check + the redirect to HTTPS |
| 3 | HTTPS | TCP | 443 | Anywhere-IPv4 `0.0.0.0/0` | the site itself |

For rule 1, pick **My IP** from the Source dropdown and AWS fills in your current address.

- **Port 80 is not optional**, even though everything runs on HTTPS. Caddy proves it
  controls the host by answering a Let's Encrypt request on port 80 (the HTTP-01
  challenge). Close 80 and **no certificate is ever issued** and the site has no HTTPS.
- **Nothing opens 5432 or 8080.** The database and the backend publish no ports at all
  (`docker-compose.yml` has no `ports:` for them), so there is nothing to open.
- Leave **outbound** at "all traffic allowed". The server needs it to pull images, fetch
  certificates and talk to Telegram.

> **Your home IP changes.** When SSH suddenly times out after a few days, this is almost
> always why. Fix: EC2 → **Security Groups** → `minipolya-test-sg` → **Inbound rules** →
> **Edit** → set rule 1's source to **My IP** again → **Save**. Resist the urge to open 22
> to `0.0.0.0/0`: an SSH port open to the internet collects thousands of login attempts a
> day.

### 2.6 Storage

Under **Configure storage**: **1 × 30 GiB gp3**.

- 20 GiB is enough; 30 GiB costs about $1 more a month and removes a whole class of
  problem. Docker images, build caches and layers add up fast — the backend image alone is
  about 540 MB, and `docker build` keeps the Maven and npm caches.
- **gp3**, not gp2: same price band, better baseline throughput, and you can change IOPS
  without resizing.
- Leave "Delete on termination" at **Yes** for a test server. It means terminating the
  instance deletes the disk — which is exactly why §12's backups must be copied **off** the
  server.

### 2.7 Launch

Click **Launch instance**, then **View all instances**. Within a minute, **Instance state**
is `Running` and **Status check** becomes `2/2 checks passed`.

### 2.8 An Elastic IP, so the address survives a reboot

By default, an instance's public IP is released when it stops and a different one is
assigned when it starts again. That would break your certificate, your sslip.io hostname,
the Mini App URL in BotFather and the Yandex referer list — all at once. An **Elastic IP**
is an address that stays yours.

1. EC2 → left menu → **Elastic IPs** → **Allocate Elastic IP address**.
2. Network border group: leave as `eu-central-1`. Click **Allocate**.
3. Select the new address → **Actions** → **Associate Elastic IP address**.
4. Resource type **Instance**, choose `minipolya-test`, click **Associate**.

Write the address down — everything below calls it `<ELASTIC_IP>`.

**What it costs.** Since February 2024 AWS charges for **every** public IPv4 address at
about **$0.005 per hour (≈ $3.60 a month)** — including the one the instance would have had
anyway. So attaching an Elastic IP to a running instance costs you **nothing extra**; you
were already paying for a public address.

The charge to avoid is an Elastic IP that is **allocated but not associated** with a running
instance: it bills at the same rate for nothing. If you ever terminate the instance,
**release** the Elastic IP too (Elastic IPs → select → Actions → **Release**).

### 2.9 Your hostname, free

Telegram requires HTTPS for a Mini App, and a certificate needs a **hostname** — an IP will
not do. You do not have to buy a domain: [sslip.io](https://sslip.io) resolves any IP
written into the name itself.

Take your Elastic IP, **replace the dots with dashes**, add `.sslip.io`:

```
Elastic IP   3.120.45.67
SITE_HOST    3-120-45-67.sslip.io
```

That name resolves from anywhere in the world, which is all Let's Encrypt and Telegram need.
This is your `SITE_HOST` for the rest of the guide.

A real domain later is the same work: point an `A` record at the Elastic IP, put the domain
in `SITE_HOST`, run `docker compose up -d`, and Caddy fetches a certificate for it.

---

## 3. Connect with SSH from Windows

Windows 11 includes OpenSSH, so PowerShell is all you need — no PuTTY.

### 3.1 Fix the key's permissions

OpenSSH refuses to use a private key that other accounts on the machine can read. On
Windows that means stripping the inherited permissions. Run this **once**, in PowerShell
(not as administrator):

```powershell
$key = "$env:USERPROFILE\.ssh\minipolya-test.pem"

# Remove every inherited permission, leaving the file with none.
icacls $key /inheritance:r

# Grant read to your account only.
icacls $key /grant:r "$($env:USERNAME):(R)"

# Check: exactly one line, your username, with (R).
icacls $key
```

Skip this and the next command fails with `UNPROTECTED PRIVATE KEY FILE` and
`Permissions for '…' are too open`.

### 3.2 Connect

The login user for an Ubuntu AMI is `ubuntu` (not `root`, not `ec2-user`):

```powershell
ssh -i "$env:USERPROFILE\.ssh\minipolya-test.pem" ubuntu@<ELASTIC_IP>
```

The first time, it asks:

```
The authenticity of host '3.120.45.67' can't be established.
ED25519 key fingerprint is SHA256:…
Are you sure you want to continue connecting (yes/no/[fingerprint])?
```

Type `yes`. (The careful version is to compare that fingerprint against EC2 → Instances →
your instance → **Actions** → **Monitor and troubleshoot** → **Get system log**, which
prints the host keys the server generated on first boot.)

You should land on:

```
ubuntu@ip-172-31-x-x:~$
```

### 3.3 Make it one word to type

Put this in `$env:USERPROFILE\.ssh\config` (create the file if it does not exist):

```
Host minipolya
    HostName 3.120.45.67
    User ubuntu
    IdentityFile ~/.ssh/minipolya-test.pem
    IdentitiesOnly yes
    ServerAliveInterval 60
```

Now `ssh minipolya` is the whole command, and `scp` in §12 can use the same short name.
`ServerAliveInterval 60` stops the connection dropping silently while you read output.

---

## 4. Set the server up

Everything in this section runs **on the server**, in the SSH session.

### 4.1 Update everything

```bash
sudo apt-get update
sudo apt-get upgrade -y
```

If it tells you a reboot is required (a new kernel), take it now — it is free at this point:

```bash
sudo reboot
```

The connection drops. Wait about 30 seconds and `ssh minipolya` again.

### 4.2 A swap file, so a build cannot kill the server

2 GB of RAM is enough to *run* MiniPolya but tight to *build* it: `npm run build` and
`tsc -b` together are the heaviest moment this server ever has. Without swap, Linux's OOM
killer picks a process — often PostgreSQL — and kills it. With 2 GB of swap, the build gets
slower instead of fatal.

```bash
# A 2 GB file. fallocate is instant; it does not write 2 GB of zeroes.
sudo fallocate -l 2G /swapfile

# Only root may read it: it holds whatever was in memory.
sudo chmod 600 /swapfile

sudo mkswap /swapfile
sudo swapon /swapfile

# Make it survive a reboot.
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

# Prefer RAM, use swap only under real pressure. The default (60) swaps too eagerly
# for a server and makes everything feel slow.
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-swappiness.conf
sudo sysctl -p /etc/sysctl.d/99-swappiness.conf
```

Check it:

```bash
free -h
```

`Swap:` should show `2.0Gi` total.

### 4.3 Docker and the Compose plugin

Install from **Docker's own repository**, not Ubuntu's `docker.io` package: Ubuntu's is
older and does not include the `compose` plugin, and `docker-compose` (the old Python v1
with a hyphen) does not understand this `docker-compose.yml`.

```bash
# Prerequisites.
sudo apt-get install -y ca-certificates curl

# Docker's signing key.
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

# The repository, for this machine's architecture and this Ubuntu release.
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get install -y \
  docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

Let your user run Docker without `sudo`:

```bash
sudo usermod -aG docker $USER
```

**Group membership only applies to a new login.** Log out and back in:

```bash
exit
```

```powershell
ssh minipolya
```

Then check — both must print a version, and the second must say **v2**:

```bash
docker --version
docker compose version
```

```
Docker version 28.x.x, build …
Docker Compose version v2.x.x
```

A quick end-to-end test:

```bash
docker run --rm hello-world
```

### 4.4 The firewall — and what it does *not* cover

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable        # answer y; it warns that this could break the SSH session
sudo ufw status verbose
```

> **Read this once.** Docker writes its **own** iptables rules, and they are evaluated
> *before* ufw's. A container that publishes a port is reachable from the internet **even if
> ufw says that port is denied.**
>
> That is not a problem in this deployment — the only container that publishes ports is
> Caddy, on 80 and 443, which is exactly what should be public, and the backend and database
> publish nothing. But it means:
>
> - **The EC2 security group is the firewall that matters.** It sits outside the machine
>   and nothing inside can go around it. Treat §2.5 as the real configuration.
> - ufw is a useful second layer for anything *not* in Docker (a package that opens a port,
>   something you install later).
> - **Never add `ports:` to the postgres or backend service** "just to have a look". It
>   would publish the database to the entire internet in one line, and ufw would not stop
>   it. §13 shows how to look at the database without doing that.

### 4.5 Automatic security updates

Ubuntu 24.04 installs `unattended-upgrades` and enables security updates by default. Verify
rather than assume:

```bash
systemctl status unattended-upgrades --no-pager
cat /etc/apt/apt.conf.d/20auto-upgrades
```

You want both lines set to `"1"`:

```
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
```

If the file is missing or shows `"0"`:

```bash
sudo apt-get install -y unattended-upgrades
echo 'APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";' | sudo tee /etc/apt/apt.conf.d/20auto-upgrades
sudo systemctl enable --now unattended-upgrades
```

**Optional: let it reboot itself.** Kernel and libc updates only take effect after a
reboot, and a server that never reboots accumulates unpatched kernels. Every container is
`restart: unless-stopped`, so they all come back on their own — a reboot costs about a
minute of downtime, which a test server can afford:

```bash
sudo tee /etc/apt/apt.conf.d/51unattended-upgrades-reboot > /dev/null <<'EOF'
Unattended-Upgrade::Automatic-Reboot "true";
Unattended-Upgrade::Automatic-Reboot-WithUsers "false";
Unattended-Upgrade::Automatic-Reboot-Time "04:30";
EOF
```

`04:30` is server time, which is **UTC** — 09:30 in Tashkent. Pick a UTC time that is
quiet in Tashkent; `23:00` UTC is 04:00 Tashkent.

Check what it has been doing:

```bash
sudo unattended-upgrade --dry-run --debug | tail -20
grep -i upgrade /var/log/unattended-upgrades/unattended-upgrades.log | tail
```

---

## 5. Get the code onto the server

Both repositories are private, so the server needs read access. Two ways; the first is
better and barely harder.

### Option A (recommended): a read-only deploy key per repository

A **deploy key** is an SSH key granted access to **one** repository. It cannot push if you
mark it read-only, it cannot touch your other repositories, and it is not tied to your
GitHub account — revoking it affects nothing else.

> GitHub allows a given key on only **one** repository, so this needs **two** keys. That is
> what the SSH aliases below are for.

**On the server**, make two keys with no passphrase (a passphrase would mean typing it on
every `git pull`, which breaks unattended updates):

```bash
ssh-keygen -t ed25519 -C "minipolya-test-server backend"  -f ~/.ssh/deploy_backend  -N ""
ssh-keygen -t ed25519 -C "minipolya-test-server frontend" -f ~/.ssh/deploy_frontend -N ""
```

Print the two **public** keys (`.pub` — never the other file):

```bash
echo "=== BACKEND ==="  && cat ~/.ssh/deploy_backend.pub
echo "=== FRONTEND ===" && cat ~/.ssh/deploy_frontend.pub
```

**On GitHub**, for the **backend** repository:

1. Open the repository → **Settings** → **Deploy keys** (left menu, under "Security")
2. **Add deploy key**
3. Title: `minipolya-test server (read-only)`
4. Key: paste the whole `=== BACKEND ===` line, from `ssh-ed25519` to the end
5. **Leave "Allow write access" unchecked** — the server only ever reads
6. **Add key**

Repeat for the **frontend** repository with the `=== FRONTEND ===` key.

**On the server**, teach SSH which key belongs to which repository. Both are
`github.com`, so they need distinct aliases:

```bash
cat >> ~/.ssh/config <<'EOF'

Host github-backend
    HostName github.com
    User git
    IdentityFile ~/.ssh/deploy_backend
    IdentitiesOnly yes

Host github-frontend
    HostName github.com
    User git
    IdentityFile ~/.ssh/deploy_frontend
    IdentitiesOnly yes
EOF

chmod 600 ~/.ssh/config
```

`IdentitiesOnly yes` matters: without it SSH offers every key it has and GitHub accepts the
first that works, which may be the other repository's — and then the clone fails with a
confusing "repository not found".

Test both (each should greet you with the repository name; "successfully authenticated, but
GitHub does not provide shell access" is the expected success message):

```bash
ssh -T git@github-backend
ssh -T git@github-frontend
```

Now clone, using the alias in place of `github.com`:

```bash
mkdir -p ~/minipolya && cd ~/minipolya

git clone git@github-backend:<your-github-user>/<backend-repo>.git   minipolyaBackend
git clone git@github-frontend:<your-github-user>/<frontend-repo>.git minipolyaFrontend
```

Fill in your own user and repository names — on GitHub, the green **Code** button → **SSH**
shows them as `git@github.com:user/repo.git`; swap `github.com` for the alias.

**The directory names matter.** `docker-compose.yml` looks for `../../minipolyaFrontend`.
That is why `minipolyaBackend` and `minipolyaFrontend` are given explicitly above rather
than letting git name them after the repositories.

### Option B: a fine-grained personal access token

Simpler (one credential for both repositories), slightly worse (it is tied to your account,
and it sits on the server's disk in plain text).

1. GitHub → your avatar → **Settings** → **Developer settings** →
   **Personal access tokens** → **Fine-grained tokens** → **Generate new token**
2. Name `minipolya-test-server`, expiry **90 days** (not "no expiration")
3. **Repository access** → *Only select repositories* → pick both repositories
4. **Permissions** → Repository permissions → **Contents: Read-only**. Nothing else.
5. **Generate token** and copy it — it is shown once.

On the server:

```bash
mkdir -p ~/minipolya && cd ~/minipolya
git clone https://github.com/<user>/<backend-repo>.git   minipolyaBackend
git clone https://github.com/<user>/<frontend-repo>.git  minipolyaFrontend
```

Git asks for a username (your GitHub username) and a password (**paste the token**). To
avoid retyping it on every pull:

```bash
git -C ~/minipolya/minipolyaBackend  config credential.helper store
git -C ~/minipolya/minipolyaFrontend config credential.helper store
```

That writes the token in clear text to `~/.git-credentials`. `chmod 600 ~/.git-credentials`
and remember it expires in 90 days — a `git pull` that suddenly asks for a password is the
token, not the server.

### Both repositories on the right branch

```bash
cd ~/minipolya/minipolyaBackend  && git log --oneline -1 && git branch --show-current
cd ~/minipolya/minipolyaFrontend && git log --oneline -1 && git branch --show-current
```

If the deploy files are on a branch rather than the default one, check it out in **both**:

```bash
cd ~/minipolya/minipolyaBackend  && git checkout feature/aws-deploy
cd ~/minipolya/minipolyaFrontend && git checkout feature/aws-deploy
```

Confirm the files arrived:

```bash
ls ~/minipolya/minipolyaBackend/deploy/
ls ~/minipolya/minipolyaFrontend/Dockerfile
```

---

## 6. Fill in `.env`

```bash
cd ~/minipolya/minipolyaBackend/deploy
cp .env.example .env
chmod 600 .env
```

`chmod 600` is not decoration: this is the only file on the server holding secrets, and it
should be readable by your user alone. It is git-ignored, so it can never be committed.

### 6.1 Generate the secrets *on the server*

Paste this whole block. It prints three fresh values:

```bash
echo "POSTGRES_PASSWORD=$(openssl rand -base64 24)"
echo "JWT_SECRET=$(openssl rand -base64 48)"
echo "CARD_TOKEN_KEY=$(openssl rand -base64 32)"
```

- **New values, not the ones from your laptop's `.env`.** A test server is the least
  protected thing you own; a secret shared with your development environment turns one
  compromise into two. The app enforces part of this itself: under the `staging` profile it
  refuses to start without a real `JWT_SECRET` and `CARD_TOKEN_KEY` rather than falling back
  to a development default.
- `CARD_TOKEN_KEY` must decode to **exactly 32 bytes** — `openssl rand -base64 32` is
  exactly that. Keep it: it encrypts saved card tokens, and losing it makes every saved card
  in the database unreadable.
- `JWT_SECRET` must be at least 32 bytes. Changing it later signs everyone out.

### 6.2 Edit the file

```bash
nano .env
```

(`nano`: arrow keys to move, `Ctrl+O` then `Enter` to save, `Ctrl+X` to quit.)

| Variable | What to put | Where it comes from |
|---|---|---|
| `SITE_HOST` | `3-120-45-67.sslip.io` | your Elastic IP with dashes, §2.9. **No `https://`, no trailing slash.** |
| `ACME_EMAIL` | your email | optional; Let's Encrypt expiry warnings |
| `POSTGRES_DB` | `minipolya` | leave it |
| `POSTGRES_USER` | `minipolya` | leave it |
| `POSTGRES_PASSWORD` | the generated value | §6.1 |
| `JWT_SECRET` | the generated value | §6.1 |
| `CARD_TOKEN_KEY` | the generated value | §6.1 |
| `BOT_TOKEN` | `8123…:AAE…` | @BotFather, §9.1. A **test** bot. |
| `BOT_USERNAME` | `minipolya_test_bot` | the bot's username, no `@` |
| `SUPPORT_USERNAME` | your username or empty | no `@` |
| `VITE_YANDEX_MAPS_API_KEY` | the Maps v3 key | §10; empty is fine, maps fall back to OpenStreetMap |
| `VITE_TELEGRAM_BOT_USERNAME` | `minipolya_test_bot` | same bot, public name |
| `VITE_TELEGRAM_APP_NAME` | `minipolya` | the Mini App short name, §9.2 |
| `VITE_TELEGRAM_SUPPORT_USERNAME` | your username or empty | no `@` |

If you do not have the bot token yet, do §9.1 now and come back — the backend will not start
without `BOT_TOKEN`.

### 6.3 Check it before starting anything

```bash
docker compose config --quiet && echo "OK: compose can read .env"
```

Silence plus `OK` means every `${…}` resolved. If a required one is missing, compose says
exactly which:

```
error while interpolating services.backend.environment.JWT_SECRET:
required variable JWT_SECRET is missing a value: set JWT_SECRET in .env (openssl rand -base64 48)
```

And confirm the values look right without printing the secrets:

```bash
grep -E '^(SITE_HOST|POSTGRES_DB|POSTGRES_USER|BOT_USERNAME|VITE_)' .env
```

---

## 7. Start it

```bash
cd ~/minipolya/minipolyaBackend/deploy
docker compose up -d --build
```

### What happens, and how long it takes

The **first** run builds both images from source and takes **8–20 minutes** on a
`t4g.small`. Most of it is two things that are slow exactly once:

1. **Maven downloads the dependency tree** (~2–5 min) and compiles the backend (~2–4 min).
2. **`npm ci` installs node_modules** (~2–4 min) and `vite build` + `tsc -b` builds both
   apps (~2–5 min).

Later builds reuse the cached layers and take 1–3 minutes unless `pom.xml` or
`package-lock.json` changed.

> **If the frontend build is killed**, you will see `Killed` or
> `exit code: 137` in the npm stage. That is the OOM killer, and it means §4.2's swap file
> is missing. Check `free -h`, add the swap, and run the command again.

### Watch it come up

```bash
docker compose ps
```

What you want, after a couple of minutes:

```
NAME                 IMAGE                STATUS                   PORTS
minipolya-backend-1  minipolya-backend    Up 2 minutes (healthy)
minipolya-caddy-1    minipolya-caddy      Up 2 minutes             0.0.0.0:80->80/tcp, 0.0.0.0:443->443/tcp
minipolya-postgres-1 postgres:17          Up 3 minutes (healthy)
```

Three things to read in that output:

- **`(healthy)`** on backend and postgres. The backend's health check asks its own
  `/actuator/health`, which is only `UP` once Spring has started *and* the database is
  reachable — so `(healthy)` means the whole chain works. It can take 60–90 seconds on the
  first start because Liquibase is creating about thirty tables.
- **`PORTS` is empty for backend and postgres.** That is the goal. If either shows
  `0.0.0.0:…`, stop and check you have not edited `ports:` into them.
- Caddy on **80 and 443** only.

### Read the logs

```bash
# Everything, newest last, and keep following.
docker compose logs -f

# One service.
docker compose logs -f backend
docker compose logs -f caddy

# Just the last 50 lines, no follow.
docker compose logs --tail=50 backend
```

(`Ctrl+C` stops following; it does not stop the containers.)

**In the backend log, you are looking for:**

```
Started MinipolyaBackendApplication in 18.4 seconds
```

and, from Liquibase on the first run, a long list of `changeset … ran successfully`.

You should **not** see `Using generated security password:` — that line is gone on purpose
(`UserDetailsServiceAutoConfiguration` is excluded), along with the phantom `user` account
it came with.

If `BOT_TOKEN` is a test bot and polling started, you will see the bot register its
commands and menu button.

**In the Caddy log, the certificate:**

```
{"level":"info","logger":"tls.obtain","msg":"acquiring lock","identifier":"3-120-45-67.sslip.io"}
{"level":"info","logger":"tls.issuance.acme","msg":"waiting on internal rate limiter"}
{"level":"info","logger":"tls.obtain","msg":"certificate obtained successfully","identifier":"3-120-45-67.sslip.io"}
```

That usually takes 5–30 seconds. If it does not arrive, §13 has the three reasons.

### Check it from your own machine

In PowerShell on your PC (not on the server):

```powershell
# The Mini App. Expect 200 and text/html.
curl.exe -I https://3-120-45-67.sslip.io/

# The admin panel.
curl.exe -I https://3-120-45-67.sslip.io/admin

# The API, through Caddy. Expect 200 and a JSON envelope.
curl.exe -s https://3-120-45-67.sslip.io/api/stadiums?page=0`&size=1

# http must redirect to https.
curl.exe -I http://3-120-45-67.sslip.io/
```

Then open them in a browser:

- `https://<SITE_HOST>/` — the Mini App. **Outside Telegram it will not sign you in**: it
  needs Telegram's `initData`, so expect it to stop at the sign-in screen. That is correct
  behaviour, not a failure. §9 is how you see it properly.
- `https://<SITE_HOST>/admin` — the admin panel's login form. You cannot sign in yet;
  §8 creates the account.
- A padlock with no warning in the address bar means Let's Encrypt issued the certificate.

### Confirm the build has no dev login and no dev key

Worth doing once, because it is the one thing you cannot see from the outside:

```bash
# Both must print 0. The dev sign-in picker and its key must not be in the bundle.
docker compose exec caddy sh -c 'grep -rl "X-Dev-Login-Key" /srv | wc -l'
docker compose exec caddy sh -c 'grep -rl "api/auth/dev" /srv | wc -l'

# And the backend must not have the dev profile active.
docker compose exec backend sh -c 'echo $SPRING_PROFILES_ACTIVE'     # → staging
docker compose exec backend printenv | grep -c DEV_LOGIN_KEY         # → 0
```

Why they are zero:
- `AuthGate.tsx` loads the dev picker as
  `import.meta.env.DEV ? lazy(() => import('./dev/DevLoginPicker')) : null`, so a production
  build drops the whole module, and the frontend `Dockerfile` does not pass
  `VITE_DEV_LOGIN_KEY` at all.
- `DevAuthController` is `@Profile("dev")`, and `DevProfileGuard` refuses to start if `dev`
  is ever active next to `staging`. The dev endpoints cannot exist here.

Also confirm, from your PC, that the API is only reachable through Caddy — both of these
should **time out**, not connect:

```powershell
curl.exe -m 5 http://3-120-45-67.sslip.io:8080/actuator/health
curl.exe -m 5 http://3-120-45-67.sslip.io:5432
```

---

## 8. Create the first admin user

There is deliberately **no API** to create an admin or set a password — not even an admin's
own. Accounts are created with SQL by whoever has the database, and that is on purpose
(backend `CLAUDE.md`, "Admin accounts").

The password is hashed by **pgcrypto** inside the database (`crypt` with
`gen_salt('bf', 12)`), which produces the same BCrypt-cost-12 hash the app verifies.
pgcrypto is already there — Liquibase changeset 027 created it.

### Create it

Open psql **inside** the postgres container — no client on the host, no published port:

```bash
cd ~/minipolya/minipolyaBackend/deploy
docker compose exec postgres psql -U minipolya -d minipolya
```

At the `minipolya=#` prompt, paste these three lines:

```sql
\set HISTFILE /dev/null
INSERT INTO users (username, name, password_hash, is_admin)
VALUES ('dilnur', 'Dilnur Aliqulov', crypt('a-long-unique-password', gen_salt('bf', 12)), true);
```

- **`\set HISTFILE /dev/null` goes first.** It turns off psql's history file, so the
  statement below — which contains the password in plain text — is not written to disk.
- The password is typed **inside psql**, not at the shell prompt, so it never reaches bash
  history either. Do not put this statement in a script file, a ticket or a chat message.
- Replace `'a-long-unique-password'` with the real password: **at least 10 characters**, at
  most 72 bytes (BCrypt ignores the rest). Change `'dilnur'` and `'Dilnur Aliqulov'` too.
  The username matches case-insensitively.

Expect:

```
INSERT 0 1
```

> **Why not psql's `\prompt`?** It looks like the safer option — read the password into a
> variable, pass it as `crypt(:'pw', …)` — but `\prompt` takes the *next line of input* as
> its answer. Paste it as part of a block and it swallows the `INSERT` as the password, then
> runs nothing; you get no error, just no row. It would also echo what you type, so it hides
> nothing that the line above does not. Not worth it.

Check the row without printing the hash:

```sql
SELECT username, name, is_admin, password_hash IS NOT NULL AS has_password,
       created_at
FROM users WHERE is_admin = true;
```

Leave psql:

```sql
\q
```

### Clear the histories

```bash
# psql's history inside the container (should already be empty thanks to \set HISTFILE).
docker compose exec postgres sh -c 'rm -f /var/lib/postgresql/.psql_history'

# Your shell's history, in case you typed anything sensitive at the bash prompt.
history -c && history -w
```

### Sign in

Open `https://<SITE_HOST>/admin` and sign in with that username and password.

The limits are real, so know them before you lock yourself out: **5** wrong passwords in a
row locks the account for **15 minutes**, and one IP gets **20** attempts per **15 minutes**
whether they are right or wrong. If you do lock it, wait, or clear it:

```bash
docker compose exec postgres psql -U minipolya -d minipolya -c \
  "UPDATE users SET locked_until = NULL, failed_login_attempts = 0 WHERE LOWER(username) = LOWER('dilnur');"
```

To **change** a password later, the same way — `\set HISTFILE /dev/null` first, then the
reset statement from `CLAUDE.md`. Keep its `password_changed_at = now()`: that is what
signs out every existing session, so a stolen token does not survive the reset.

---

## 9. Telegram: the bot and the Mini App

### 9.1 A test bot — and why it must be a different one

Open [@BotFather](https://t.me/BotFather) in Telegram.

```
/newbot
```

It asks for a display name (`MiniPolya Test`) and then a username, which must end in `bot`
(`minipolya_test_bot`). It replies with a token like `8123456789:AAE…`.

Put that token in `.env` as `BOT_TOKEN`, and the username (without `@`) in both
`BOT_USERNAME` and `VITE_TELEGRAM_BOT_USERNAME`.

> **This must not be your production bot, and here is why.**
>
> This server runs the bot on **polling** (`TELEGRAM_BOT_MODE=polling`), which means it
> calls `getUpdates` in a loop. Telegram enforces two things about that:
>
> 1. **Calling `getUpdates` deletes that bot's webhook.** Point this server at the
>    production bot and production's webhook silently stops receiving anything.
> 2. **Only one process may poll a bot at a time.** A second one gets
>    `409 Conflict: terminated by other getUpdates request`, and the two then steal each
>    other's updates at random.
>
> So: **one bot, one poller.** While this server runs, either stop your local backend —
>
> ```bash
> # on your PC, in the terminal running the local backend
> Ctrl+C
> ```
>
> — or, better, give the local one a **third** bot of its own. Make
> `minipolya_dev_bot` with `/newbot` and use its token in your local `.env`. Then the local
> machine, the test server and production each have their own bot and never interfere.
>
> If the bot goes quiet, check for exactly this: §13.

### 9.2 Register the Mini App

This is what makes `https://t.me/<bot>/<app>?startapp=…` invite links work.

```
/newapp
```

BotFather asks, in order:

1. **Which bot** — the test bot
2. **Title** — `MiniPolya`
3. **Description** — one or two sentences
4. **Photo** — 640×360 (any placeholder will do for a test)
5. **Demo GIF** — send `/empty` to skip
6. **Web App URL** — `https://3-120-45-67.sslip.io` ← your `SITE_HOST`, **https, no path,
   no trailing slash**
7. **Short name** — `minipolya`. This is the `<app>` in the link, and it must equal
   `VITE_TELEGRAM_APP_NAME` in `.env`.

### 9.3 The menu button sets itself

You do **not** need `/setmenubutton`: the backend sets the bot's description, its command
list and its menu button at startup from `MINIAPP_URL`, which `docker-compose.yml` builds
as `https://${SITE_HOST}`. It only writes what differs, so it is safe on every restart.

That is also why `MINIAPP_URL` is not a separate variable in `.env` — it cannot drift out of
sync with `SITE_HOST`, and the `staging` profile refuses to start if it is not `https`.

Check it worked: open a chat with the bot. There should be a menu button next to the message
box that opens the Mini App, and `/start` should reply with a banner and an open button.

### 9.4 Try it for real

Open the Mini App **from inside Telegram** — the menu button, or `/start`'s button, or
`https://t.me/minipolya_test_bot/minipolya`. Only there does Telegram hand the app the
signed `initData` it needs to sign you in; in a desktop browser it correctly stops at the
sign-in screen.

Worth walking through once:

- the stadium list loads, and photos appear (that is `/api/uploads/**` through Caddy)
- open a stadium, pick a slot, book it, and pay with a **fake** card:
  any `8600…` or `9860…` 16-digit number, any future MM/YY, and any 6-digit SMS code
- the bot sends you a notification about the booking
- `/admin` shows the booking and the payment

### 9.5 If you change `SITE_HOST` later

Three places have to change together, or the Mini App breaks in a way that is hard to read:

1. `SITE_HOST` in `.env` → then `docker compose up -d --build` (the frontend bundle and the
   certificate both depend on it)
2. BotFather → `/myapps` → your app → **Edit Web App URL**
3. The Yandex referer list, §10

---

## 10. Yandex Maps: allow the host

The Mini App shows pitch locations with the Yandex Maps JavaScript API v3. The key is public
(it is in the bundle — that is unavoidable for a browser map), so Yandex restricts it by
**HTTP Referer** instead: requests only work from hosts you list.

1. Open the [Yandex Developer Dashboard](https://developer.tech.yandex.ru/services/) and
   sign in.
2. Select your **JavaScript API and Geocoder** key (or create one — the free tier is
   generous and enough for a test server).
3. Find **Restrictions** → **HTTP Referer** (sometimes "Allowed domains" / "Ограничения по
   HTTP Referer").
4. Add your host. Add both forms, because browsers and SDKs differ in what they send:

   ```
   3-120-45-67.sslip.io
   3-120-45-67.sslip.io/*
   ```

   If the field wants full origins:

   ```
   https://3-120-45-67.sslip.io
   https://3-120-45-67.sslip.io/*
   ```

5. Save. It can take a few minutes to take effect.
6. Put the key in `.env` as `VITE_YANDEX_MAPS_API_KEY` and rebuild — it is a **build-time**
   value, baked into the JavaScript:

   ```bash
   docker compose up -d --build caddy
   ```

> **Do not add a bare `sslip.io` or `*.sslip.io`.** sslip.io resolves *every* IP, so a
> wildcard there would let anyone's server use your key by pointing their own sslip.io name
> at it. List the exact host.

**Two things that silently break the maps, both already handled here:**

- **`Referrer-Policy`.** The referer check only works if the browser actually sends the
  origin. The Caddyfile sets `Referrer-Policy: strict-origin-when-cross-origin`, which sends
  the origin (never the path) to Yandex. `no-referrer` or `same-origin` would make every map
  request look unauthorised and the maps would stop loading. Do not "tighten" that header.
- **The CSP.** `api-maps.yandex.ru` and `*.maps.yandex.net` are listed in `script-src`,
  `connect-src` and `img-src`, and `'wasm-unsafe-eval'` lets the renderer compile its
  WebAssembly. Remove any of those and the map fails in the browser console.

**If the key is empty or Yandex fails to load**, the app falls back to Leaflet with
OpenStreetMap tiles on its own. The maps still work, they just look different — a perfectly
good state for a test server if you have not got a key yet.

Check in the browser: open a stadium, tap **Xaritani koʻrsatish**, and look at the DevTools
console. A Yandex 403 names the referer it saw, which tells you exactly what to add to the
list.

---

## 11. Deploying a change, and rolling back

### Deploy

```bash
cd ~/minipolya/minipolyaBackend/deploy

git -C ~/minipolya/minipolyaBackend  pull
git -C ~/minipolya/minipolyaFrontend pull

docker compose up -d --build
```

`up -d --build` rebuilds the images, then replaces only the containers whose image or
configuration actually changed. Expect 1–3 minutes and a few seconds of downtime as each
container is swapped.

**Back up first if the release contains new Liquibase changesets** — a schema migration is
the one thing a code rollback cannot undo:

```bash
~/minipolya/minipolyaBackend/deploy/backup.sh
```

Narrow it down when you know what changed:

```bash
docker compose up -d --build backend     # backend only
docker compose up -d --build caddy       # frontend bundle only
```

And for a Caddyfile change, no rebuild at all — it is mounted, so reload it in place:

```bash
docker compose exec caddy caddy validate --config /etc/caddy/Caddyfile   # check first
docker compose exec caddy caddy reload   --config /etc/caddy/Caddyfile
```

That swaps the configuration without dropping a connection or re-fetching the certificate.

Verify after any deploy:

```bash
docker compose ps                        # both (healthy) again?
docker compose logs --tail=40 backend
```

### Roll back

Go back to a commit that worked. Find it first:

```bash
git -C ~/minipolya/minipolyaBackend log --oneline -15
```

Then, in **whichever repository** you are rolling back (often both, to the commits that were
deployed together):

```bash
git -C ~/minipolya/minipolyaBackend  checkout <commit-sha>
git -C ~/minipolya/minipolyaFrontend checkout <commit-sha>

cd ~/minipolya/minipolyaBackend/deploy
docker compose up -d --build
```

You are now on a *detached HEAD*, which is fine for a server — it is a checkout, not a
branch. To go back to the tip:

```bash
git -C ~/minipolya/minipolyaBackend  checkout feature/aws-deploy
git -C ~/minipolya/minipolyaFrontend checkout feature/aws-deploy
```

> **A code rollback does not roll back the database.** Liquibase applies changesets on
> startup and never reverses them on its own. If the release you are undoing added a column
> or a constraint, the schema still has it. Usually harmless — the old code ignores a column
> it does not know. Occasionally not: a new `NOT NULL` column with no default will make the
> old code's inserts fail.
>
> When that happens, restore the pre-deploy backup (§12) instead of rolling the code back
> and hoping. That is why the backup before a migration matters.

### Changing a value in `.env`

```bash
nano .env
docker compose up -d           # recreates the containers whose environment changed
```

A `VITE_*` value needs `--build` instead, because it is compiled into the bundle:

```bash
docker compose up -d --build caddy
```

---

## 12. Backups: where, how to copy, how to restore

### What `backup.sh` does

`deploy/backup.sh` runs `pg_dump` **inside** the postgres container (so the host needs no
PostgreSQL client and no port is published), gzips the result, and keeps the last **14**
days:

```
~/minipolya/backups/
├── minipolya-2026-11-14-0215.sql.gz
├── minipolya-2026-11-13-0215.sql.gz
├── …
└── backup.log
```

It is a plain-SQL, self-contained dump taken with `--clean --if-exists --no-owner
--no-privileges`, so restoring it needs nothing but `psql` and works into a fresh container
or onto your laptop. It runs while the app is running: `pg_dump` takes one consistent
snapshot and never blocks a reader or a writer. It writes to a `.partial` file and renames
it only on success, so an interrupted backup never looks like a good one.

### Install the nightly job

```bash
chmod +x ~/minipolya/minipolyaBackend/deploy/backup.sh
mkdir -p ~/minipolya/backups

# Run it once by hand first — never trust a cron job you have not seen work.
~/minipolya/minipolyaBackend/deploy/backup.sh
ls -lh ~/minipolya/backups/
```

Then add the cron entry:

```bash
crontab -e
```

(It asks which editor the first time; choose `nano`.) Add one line:

```cron
15 2 * * * /home/ubuntu/minipolya/minipolyaBackend/deploy/backup.sh >> /home/ubuntu/minipolya/backups/backup.log 2>&1
```

`02:15` is **UTC** — 07:15 in Tashkent, a quiet hour either way. Check it is registered:

```bash
crontab -l
```

And the next morning, that it ran:

```bash
tail -20 ~/minipolya/backups/backup.log
ls -lh ~/minipolya/backups/
```

### The stadium photos

`backup.sh` backs up the **database only**. Photo rows point at files on the `uploads_data`
volume, so a database-only restore leaves gaps where photos were. For a test server that is
usually an acceptable trade; when it is not, this is the whole command:

```bash
cd ~/minipolya/backups
docker run --rm \
  -v minipolya_uploads_data:/from:ro \
  -v "$PWD":/to \
  alpine tar czf "/to/uploads-$(date +%F).tar.gz" -C /from .

ls -lh uploads-*.tar.gz
```

### Copy a backup to your PC

**Do this regularly.** The disk has "Delete on termination" set to Yes, so terminating the
instance deletes the backups along with everything else. A backup that exists only on the
machine it is backing up is not a backup.

From **PowerShell on your PC** (using the `minipolya` alias from §3.3):

```powershell
# Everything, into a local folder.
New-Item -ItemType Directory -Force "$env:USERPROFILE\minipolya-backups" | Out-Null
scp minipolya:/home/ubuntu/minipolya/backups/*.sql.gz "$env:USERPROFILE\minipolya-backups\"

# Or just the newest one.
$latest = ssh minipolya "ls -t /home/ubuntu/minipolya/backups/*.sql.gz | head -1"
scp "minipolya:$latest" "$env:USERPROFILE\minipolya-backups\"
```

Without the alias, spell it out:

```powershell
scp -i "$env:USERPROFILE\.ssh\minipolya-test.pem" `
  ubuntu@3.120.45.67:/home/ubuntu/minipolya/backups/minipolya-2026-11-14-0215.sql.gz `
  "$env:USERPROFILE\minipolya-backups\"
```

### Restore

Stop the backend first. Otherwise Liquibase and the running app are reading and writing a
schema that is being dropped and recreated underneath them:

```bash
cd ~/minipolya/minipolyaBackend/deploy

docker compose stop backend

# The dump drops what it replaces (--clean --if-exists), so this works over existing data.
gunzip -c ~/minipolya/backups/minipolya-2026-11-14-0215.sql.gz \
  | docker compose exec -T postgres psql -U minipolya -d minipolya -v ON_ERROR_STOP=1

docker compose up -d backend
docker compose logs -f backend
```

- `-T` is required: without it `exec` wants a terminal and the piped dump goes nowhere.
- `-v ON_ERROR_STOP=1` makes psql stop at the first error instead of carrying on and leaving
  you a half-restored database that looks fine.
- A few `NOTICE: … does not exist, skipping` lines at the start are **normal** — that is
  `--if-exists` dropping things that were not there.

Check what came back:

```bash
docker compose exec postgres psql -U minipolya -d minipolya -c \
  "SELECT (SELECT count(*) FROM users) AS users,
          (SELECT count(*) FROM stadiums) AS stadiums,
          (SELECT count(*) FROM bookings) AS bookings;"
```

### Practise it once

A restore you have never run is a hope, not a plan. Try it into a throwaway database, which
touches nothing real:

```bash
docker compose exec postgres createdb -U minipolya restore_test
gunzip -c ~/minipolya/backups/minipolya-*.sql.gz | tail -n +1 \
  | docker compose exec -T postgres psql -U minipolya -d restore_test -v ON_ERROR_STOP=1
docker compose exec postgres psql -U minipolya -d restore_test -c "SELECT count(*) FROM users;"
docker compose exec postgres dropdb -U minipolya restore_test
```

---

## 13. Logs and troubleshooting

### The commands worth knowing

```bash
cd ~/minipolya/minipolyaBackend/deploy

docker compose ps                        # what is up, and is it healthy
docker compose logs -f                   # everything, following
docker compose logs -f backend           # one service
docker compose logs --tail=100 backend    # the last 100 lines
docker compose logs --since=15m backend   # the last 15 minutes
docker compose restart backend            # restart one service
docker compose down && docker compose up -d   # recreate everything (volumes are kept)

docker stats --no-stream                 # live CPU and memory per container
df -h /                                  # disk
free -h                                  # memory and swap
```

Logs are capped at 10 MB × 3 files per container (`docker-compose.yml`), so they cannot fill
the disk — but they also do not go back forever.

To look at the database without publishing a port:

```bash
docker compose exec postgres psql -U minipolya -d minipolya
```

### Out of memory

**What you see:** a container restarting by itself; `exit code: 137`; `Killed` in a build;
the backend answering, then not.

```bash
# Who was killed, and when.
sudo dmesg -T | grep -i -E "killed process|out of memory" | tail

# Live usage against each container's limit.
docker stats --no-stream

# Is the swap file actually there?
free -h
```

The limits in `docker-compose.yml` are postgres 512 MB, backend 900 MB, caddy 128 MB —
1.54 GB of the server's 2 GB, leaving the rest for Ubuntu itself.

| Cause | Fix |
|---|---|
| **No swap** and the frontend build died | §4.2. This is the common one. |
| The JVM hit its own ceiling | `docker compose logs backend \| grep -i outofmemory`. The Dockerfile sets `-XX:MaxRAMPercentage=70` and `-XX:+ExitOnOutOfMemoryError`, so it dies cleanly and restarts. Repeatedly means a real leak, not a sizing problem. |
| Everything is just tight | Build elsewhere: `docker build` on your PC, push to a registry, and pull on the server. Or move to a 4 GB instance (`t4g.medium`). |

A quick way to take the build off the server's back for one deploy: stop the backend while
the frontend builds, so the JVM's 900 MB is free for npm.

```bash
docker compose stop backend
docker compose up -d --build caddy
docker compose up -d backend
```

### The certificate was never issued

**What you see:** `https://` does not connect, or the browser warns about a self-signed
certificate. `docker compose logs caddy` shows retries.

```bash
docker compose logs caddy | grep -i -E "obtain|challenge|error|acme" | tail -30
```

Three causes, in the order they happen:

1. **Port 80 is not open.** Let's Encrypt's HTTP-01 challenge arrives on **port 80**, even
   though the site runs on 443. Check the security group has rule 2 from §2.5, source
   `0.0.0.0/0`. Test from your PC: `curl.exe -I http://<SITE_HOST>/` — a connection timeout
   means the port is closed, a redirect means it is fine.
2. **`SITE_HOST` does not resolve to this server.** Check from your PC:

   ```powershell
   nslookup 3-120-45-67.sslip.io
   ```

   It must answer with your Elastic IP. If the dashes do not match the IP exactly, the name
   points somewhere else. Also check `.env` has no `https://` and no trailing slash.
3. **Let's Encrypt's rate limit.** Five certificates per hostname per week. If you have been
   recreating the stack, you may have used them up:

   ```bash
   docker compose logs caddy | grep -i "too many certificates"
   ```

   Wait it out, or test against the staging CA (untrusted by browsers, but unlimited) by
   adding `acme_ca https://acme-staging-v02.api.letsencrypt.org/directory` inside the
   Caddyfile's global `{ }` block — and **remove it before you want a real certificate**.

   The `caddy_data` volume is what stops this happening normally: it keeps the certificate
   across restarts. Never `docker compose down -v` casually — that deletes it, along with
   the database.

### A port seems blocked

Work outwards, in this order:

```bash
# 1. Is the container listening?
docker compose ps                       # caddy must show 0.0.0.0:80->80, 0.0.0.0:443->443

# 2. Does it answer on the server itself?
curl -I http://localhost/               # a redirect to https is correct
curl -kI https://localhost/             # -k because the cert is for SITE_HOST, not localhost

# 3. Does it answer through the public address, from the server?
curl -I https://<SITE_HOST>/
```

If 2 works and 3 does not, it is **not** the server — it is the **security group** (§2.5).
That is by far the most common answer. ufw is almost never the cause for these two ports,
because Docker's rules are evaluated before ufw's (§4.4).

If 1 shows nothing on 80/443, Caddy failed to start:

```bash
docker compose logs caddy | tail -40
```

An invalid Caddyfile is the usual reason, and it says which line.

### The bot does not answer

```bash
docker compose logs backend | grep -i -E "telegram|polling|getUpdates|409" | tail -30
```

| What the log says | What it means |
|---|---|
| `409 Conflict: terminated by other getUpdates request` | **Two pollers on one bot.** Your local backend is still running, or an old container is. Stop the local one, or give it its own bot (§9.1). |
| `401 Unauthorized` | `BOT_TOKEN` is wrong, or the bot was deleted in BotFather. |
| `MINIAPP_URL is missing or not https` | `SITE_HOST` is wrong in `.env`. Under `staging` this is fatal — the app will not start. |
| Nothing about Telegram at all | `BOT_TOKEN` is empty. The app refuses to start under `staging` without it; check it actually started: `docker compose ps`. |

Also worth checking: is anything else polling that bot? `curl` the Bot API from the server
(the token is in your `.env`, so read it from there rather than typing it):

```bash
set -a; source .env; set +a
curl -s "https://api.telegram.org/bot$BOT_TOKEN/getWebhookInfo" | head -c 400; echo
```

A non-empty `"url"` means a webhook is still set and fighting with polling. Polling clears
it on its own, but if you have been switching modes, clear it explicitly:

```bash
curl -s "https://api.telegram.org/bot$BOT_TOKEN/deleteWebhook?drop_pending_updates=true"
```

### The backend will not start

The `staging` profile checks its configuration before it builds anything, and lists
**everything** that is missing at once:

```bash
docker compose logs backend | head -40
```

```
The staging profile needs 2 settings that are not set:

  JWT_SECRET
      at least 32 bytes of random text (openssl rand -base64 48), different from every
      other environment. Changing it signs everyone out.

  CARD_TOKEN_KEY
      32 bytes, base64 (openssl rand -base64 32): encrypts saved card tokens at rest.
      Keep it — losing it makes every saved card unreadable.
```

Fill them in `.env`, then `docker compose up -d`.

Other startup failures and what they mean:

| Message | Cause |
|---|---|
| `CARD_TOKEN_KEY must decode to 32 bytes, not 24` | you used `openssl rand -base64 24`; it must be `32` |
| `WeakKeyException` / signing key size | `JWT_SECRET` is shorter than 32 bytes |
| `Connection to postgres:5432 refused` | postgres is not healthy yet, or `POSTGRES_*` disagree between the two services. `docker compose logs postgres` |
| `liquibase … checksum` | a changeset that already ran was edited. Never edit an applied changeset; add a new one. |
| `The staging profile must not be active together with dev` | `SPRING_PROFILES_ACTIVE` was overridden. It should be `staging` and nothing else. |

### The site loads but the API returns 502

Caddy is up and the backend is not:

```bash
docker compose ps                        # is backend (healthy)?
docker compose logs --tail=60 backend
docker compose exec backend curl -fsS http://127.0.0.1:8080/actuator/health
```

That last command should print `{"status":"UP"}`. If it does from inside the container but
Caddy still 502s, the Caddyfile's `reverse_proxy backend:8080` is not matching — check you
have not changed the service name in `docker-compose.yml`.

### Disk filling up

```bash
df -h /
docker system df                         # what Docker is holding
du -sh ~/minipolya/backups
```

Build caches are usually the culprit. Reclaim them:

```bash
docker image prune -f                    # dangling images
docker builder prune -f                  # the build cache — the big one
```

> **Never run `docker system prune -a --volumes`.** `--volumes` deletes the database, the
> stadium photos and the TLS certificates. The two commands above are the safe ones.

### Starting completely over (and keeping the data)

```bash
docker compose down                      # stop and remove containers; volumes are KEPT
docker compose up -d --build
```

To throw the data away as well — the database, the photos, the certificates — `docker
compose down -v`. There is no undo, so take a backup first.

---

## 14. What it costs, and the free plan's deadline

### Roughly, per month

Frankfurt, on-demand, as of late 2026. **Check the current numbers** on the
[EC2 pricing page](https://aws.amazon.com/ec2/pricing/on-demand/) and the
[AWS Pricing Calculator](https://calculator.aws/) — these move.

| What | Rate | Per month |
|---|---|---|
| `t4g.small`, running 24/7 | ≈ $0.019 / hour | ≈ $14 |
| 30 GB gp3 disk | ≈ $0.10 / GB-month | ≈ $3 |
| 1 public IPv4 address | $0.005 / hour | ≈ $3.60 |
| Outbound data transfer | first 100 GB/month free | ≈ $0 for a test server |
| **Total** | | **≈ $20–21** |

With `t3.small` instead, add about $3.

The $10 and $20 budget alerts from §1.2 are set deliberately below and at that figure: the
first one firing is normal and tells you the month is tracking as expected; the second one
firing means something is running that you did not intend.

**Cheaper, if you only test sometimes.** Stop the instance when you are not using it: a
stopped instance costs **nothing** for compute. You keep paying for the disk (≈ $3) and the
Elastic IP (≈ $3.60), and everything comes back on start because the volumes are intact and
the Elastic IP keeps the address.

```
EC2 → Instances → select → Instance state → Stop instance
EC2 → Instances → select → Instance state → Start instance
```

After a start, the containers come back on their own (`restart: unless-stopped`); give it a
minute and check `docker compose ps`.

### The free plan ends — and the account closes

If you opened this account on the **Free plan**, it ends at whichever comes **first**:

- **six months** after you created the account, or
- when your **credits run out**.

You cannot extend it. **When it expires, AWS closes the account** and you lose access to the
instance and its data. AWS keeps your data for **90 days** after that, and upgrading to a
paid plan within those 90 days restores access and applies any remaining credits to your
bill.

### Check where you stand

- **Credits:** Billing and Cost Management → **Credits** (left menu). It shows the amount
  remaining and the expiry date. Trust the date on that page over anything written here.
- **Spend so far this month:** Billing and Cost Management → **Bills**.
- **What is costing what:** Billing and Cost Management → **Cost Explorer** → group by
  **Service**.
- **Which plan you are on:** Billing and Cost Management → **Account overview**, or the
  Free Tier page in the same console.

Look at the Credits page **now**, write the date in your calendar, and set a reminder two
weeks before it.

### What to do before the deadline

Two weeks ahead, pick one:

**1. Keep it — upgrade to the paid plan.** Billing and Cost Management → upgrade. Nothing
about the server changes; you just start paying the ≈ $20 a month above. Keep the budget
alerts.

**2. Let it go — but take the data first.** In this order:

```bash
# On the server: a fresh backup of the database and the photos.
~/minipolya/minipolyaBackend/deploy/backup.sh

cd ~/minipolya/backups
docker run --rm -v minipolya_uploads_data:/from:ro -v "$PWD":/to \
  alpine tar czf "/to/uploads-final-$(date +%F).tar.gz" -C /from .
```

```powershell
# On your PC: copy everything off.
scp minipolya:/home/ubuntu/minipolya/backups/* "$env:USERPROFILE\minipolya-backups\"
```

Verify the copies open on your PC — a backup you have not looked at is not a backup. Then:

- EC2 → Instances → **Terminate instance**
- EC2 → **Elastic IPs** → select → Actions → **Release** (otherwise it keeps billing)
- EC2 → **Volumes** → check nothing is left `available`
- EC2 → **Security Groups** and **Key pairs** → delete if you are done

**3. Move it somewhere else.** Nothing in this deployment is AWS-specific: it is Docker
Compose, a Caddyfile and a `.env`. Any 2 GB VPS with Docker — Hetzner, DigitalOcean,
Contabo — runs it with the same `docker compose up -d --build`, usually for less. The steps
that change are §2 (the provider's console), §3 (their SSH key handling) and the IP in
`SITE_HOST`. Restore the database with §12 and copy the uploads tarball back into the
volume.

> **Do not let it lapse by accident.** An account that closes with the only copy of the data
> on its disk is how a test deployment becomes a lost month. The 90-day window is a grace
> period, not a backup.

---

## Appendix: the whole thing, in order

For when you have done it once and want it again on a new server. Fill in `<ELASTIC_IP>`,
`<SITE_HOST>` and the repository URLs.

```bash
# --- 4. the server -------------------------------------------------------------------
sudo apt-get update && sudo apt-get upgrade -y

sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-swappiness.conf
sudo sysctl -p /etc/sysctl.d/99-swappiness.conf

sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker $USER

sudo ufw default deny incoming && sudo ufw default allow outgoing
sudo ufw allow 22/tcp && sudo ufw allow 80/tcp && sudo ufw allow 443/tcp
sudo ufw --force enable

exit                                    # log out and back in for the docker group
```

```bash
# --- 5. the code --------------------------------------------------------------------
mkdir -p ~/minipolya && cd ~/minipolya
git clone <backend-url>  minipolyaBackend
git clone <frontend-url> minipolyaFrontend

# --- 6. configuration ---------------------------------------------------------------
cd ~/minipolya/minipolyaBackend/deploy
cp .env.example .env && chmod 600 .env
echo "POSTGRES_PASSWORD=$(openssl rand -base64 24)"
echo "JWT_SECRET=$(openssl rand -base64 48)"
echo "CARD_TOKEN_KEY=$(openssl rand -base64 32)"
nano .env
docker compose config --quiet && echo OK

# --- 7. start -----------------------------------------------------------------------
docker compose up -d --build
docker compose ps
docker compose logs -f

# --- 8. the first admin -------------------------------------------------------------
docker compose exec postgres psql -U minipolya -d minipolya
#   \set HISTFILE /dev/null
#   INSERT INTO users (username, name, password_hash, is_admin)
#   VALUES ('dilnur', 'Dilnur Aliqulov', crypt('a-long-unique-password', gen_salt('bf', 12)), true);
#   \q

# --- 12. backups --------------------------------------------------------------------
chmod +x ~/minipolya/minipolyaBackend/deploy/backup.sh
~/minipolya/minipolyaBackend/deploy/backup.sh
crontab -e
#   15 2 * * * /home/ubuntu/minipolya/minipolyaBackend/deploy/backup.sh >> /home/ubuntu/minipolya/backups/backup.log 2>&1
```

Then §9 (BotFather) and §10 (Yandex) in a browser, and you are up.
