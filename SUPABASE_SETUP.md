# Cloud sync setup

Cloud sync is **optional**. PortionSpot POS works fully offline — you can sell,
print receipts, and manage items with no account and no database. Connect a
database only if you want to **back up your sales** and **sync across devices**.

When you do connect one, the app stays **offline-first**: every sale is saved on
the phone immediately, and changes are pushed to your database whenever the
device is online (and automatically retried when it comes back online).

> **Your data, your database.** Nothing is hardcoded to our servers. You point
> the app at a database *you* own and control.

---

## Supported databases

| Database | Status | Notes |
|----------|--------|-------|
| **Supabase** | ✅ Supported now | Free tier is plenty for one shop. Setup below. |
| Firebase / Firestore | ⏳ Planned | |
| Plain PostgreSQL (PostgREST) | ⏳ Planned | |

---

## Set up Supabase (about 5 minutes)

### 1. Create a project
1. Go to <https://supabase.com> and sign up (free).
2. Click **New project**. Give it a name (e.g. *PortionSpot Shop*) and a strong
   database password, pick the region closest to you, and create it.
3. **Use a fresh, empty project for this shop.** The setup script never deletes
   anything, but a dedicated project keeps your POS data clean and avoids mixing
   it with other tables. (See *Connecting a database that already has data* below
   if you can't.)

### 2. Create the tables
1. In the project, open **SQL Editor** → **New query**.
2. Open [`supabase-setup.sql`](supabase-setup.sql) from this folder, copy the
   whole file, and paste it into the editor.
3. Click **Run**. You should see *Success. No rows returned.*

### 3. Get your URL and key
1. Go to **Project Settings** → **API**.
2. Copy the **Project URL** (looks like `https://abcdxyz.supabase.co`).
3. Copy the **anon** **public** key under *Project API keys*.
   - Use the **anon / public** key — **not** the `service_role` key.

### 4. Connect the app
1. In the app, open **Settings** and scroll to **Cloud sync**.
2. Paste the **Project URL** and the **anon key**.
3. Tap **Test** — you should see *“Connection works — tables found.”*
4. Tap **Connect & sync**. The first sync uploads what's on the device and pulls
   anything already in the database.

That's it. From then on the app syncs in the background (about every 15 minutes
while online) and whenever you tap **Sync now**.

---

## Good to know

**What syncs.** Your business details, items, sales, **customers**, and their
**credit/debt ledger**. Each customer's outstanding balance is recalculated from
that ledger, so it stays correct on every device.

**Already ran an earlier setup script?** Just open `supabase-setup.sql` again and
re-run the whole thing. It now also creates the `customers` and
`credit_transactions` tables and adds two columns to `sales`. Every statement uses
`if not exists`, so re-running never touches your existing tables or rows.

**Connecting a database that already has data.** The setup script uses
`create table if not exists`, so it won't touch existing tables or rows. On first
connect the app *pulls* any existing rows (matched by id; the newer `updated_at`
wins) and *pushes* its own — nothing is deleted. Still, an empty project is the
cleanest starting point.

**Multiple devices / tills.** Connect your **main device first** and let it sync.
Then connect the others to the same URL + key. Each device keeps its own local
copy and they reconcile on sync (newest change wins per row).

**What does *not* sync.** Device-only settings stay on each phone: the chosen
Bluetooth printer, paper width, large-text toggle, and your logo image.

**Security.** With no login, anyone who has *both* your Project URL and anon key
can read and write this data. Keep them private and use a project dedicated to
this shop. You can tighten the table policies in Supabase later without changing
the app.

**Disconnecting.** **Settings → Cloud sync → Disconnect** stops background sync
and forgets the URL/key on this device. Your local data and the cloud data are
both left intact.
