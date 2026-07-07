// create-cashier — admin-only creation and (de)activation of POS staff accounts.
//
// PREPARED, NOT YET DEPLOYED. Deploy with:  supabase functions deploy create-cashier
//
// Why this exists: the app holds only the public anon key, which cannot create
// auth users. This Edge Function runs with the SERVICE-ROLE key (auto-injected)
// and, matching the project's existing raw-fetch style (see _shared/db.ts):
//   1. verifies the CALLER's JWT belongs to an ACTIVE pos_staff ADMIN, then
//   2. creates a Supabase auth user + a pos_staff row (role 'cashier' by default),
//      or flips an existing staff member's `active` flag (admin-only delete/disable).
// This is the "one main admin creates cashier sub-accounts; only the admin can
// deactivate them" requirement (POS App features.txt §3–4). Cashiers can then log
// in on any device with their own email + password and be attributed per action.
//
// Request (POST JSON), Authorization: Bearer <caller admin JWT>:
//   { "action": "create", "email": "...", "password": "...", "displayName": "...", "role"?: "cashier"|"admin" }
//   { "action": "set_active", "staffId": "<uuid>", "active": true|false }
// Response: { ok: true, ... } | { ok: false, error }

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY")!;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "content-type": "application/json" },
  });
}

function svcHeaders(extra: Record<string, string> = {}): HeadersInit {
  return {
    apikey: SERVICE_KEY,
    authorization: `Bearer ${SERVICE_KEY}`,
    "content-type": "application/json",
    ...extra,
  };
}

/** The auth user id behind a caller JWT, or null if the token is invalid. */
async function callerUserId(jwt: string): Promise<string | null> {
  const resp = await fetch(`${SUPABASE_URL}/auth/v1/user`, {
    headers: { apikey: ANON_KEY, authorization: `Bearer ${jwt}` },
  });
  if (!resp.ok) return null;
  const user = await resp.json();
  return user?.id ?? null;
}

/** True when [userId] is an active pos_staff admin (checked with the service key). */
async function isActiveAdmin(userId: string): Promise<boolean> {
  const url =
    `${SUPABASE_URL}/rest/v1/pos_staff?id=eq.${userId}&select=role,active&limit=1`;
  const resp = await fetch(url, { headers: svcHeaders() });
  if (!resp.ok) return false;
  const rows = await resp.json();
  const row = Array.isArray(rows) && rows.length ? rows[0] : null;
  return !!row && row.role === "admin" && row.active === true;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ ok: false, error: "POST only" }, 405);

  const jwt = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "").trim();
  if (!jwt) return json({ ok: false, error: "Not authenticated" }, 401);

  const callerId = await callerUserId(jwt);
  if (!callerId) return json({ ok: false, error: "Invalid session" }, 401);
  if (!(await isActiveAdmin(callerId))) {
    return json({ ok: false, error: "Admin access required" }, 403);
  }

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return json({ ok: false, error: "Invalid JSON body" }, 400);
  }

  const action = String(body.action ?? "create");

  if (action === "create") {
    const email = String(body.email ?? "").trim().toLowerCase();
    const password = String(body.password ?? "");
    const displayName = String(body.displayName ?? "").trim();
    const role = body.role === "admin" ? "admin" : "cashier";
    if (!email || password.length < 6 || !displayName) {
      return json(
        { ok: false, error: "Email, a 6+ character password and a name are required" },
        400,
      );
    }

    // 1) create the auth user (email pre-confirmed so they can log in immediately)
    const createResp = await fetch(`${SUPABASE_URL}/auth/v1/admin/users`, {
      method: "POST",
      headers: svcHeaders(),
      body: JSON.stringify({
        email,
        password,
        email_confirm: true,
        user_metadata: { display_name: displayName },
      }),
    });
    const created = await createResp.json();
    if (!createResp.ok || !created?.id) {
      return json(
        { ok: false, error: created?.msg ?? created?.error_description ?? "Could not create the login" },
        400,
      );
    }
    const newId = created.id as string;

    // 2) create the pos_staff row; roll the auth user back if this fails so we
    //    never orphan a login with no staff record.
    const staffResp = await fetch(`${SUPABASE_URL}/rest/v1/pos_staff`, {
      method: "POST",
      headers: svcHeaders({ Prefer: "return=minimal" }),
      body: JSON.stringify([{
        id: newId,
        role,
        display_name: displayName,
        active: true,
        created_by: callerId,
      }]),
    });
    if (!staffResp.ok) {
      await fetch(`${SUPABASE_URL}/auth/v1/admin/users/${newId}`, {
        method: "DELETE",
        headers: svcHeaders(),
      });
      return json({ ok: false, error: "Could not save staff record: " + (await staffResp.text()) }, 400);
    }
    return json({ ok: true, id: newId, email, role, displayName });
  }

  if (action === "set_active") {
    const staffId = String(body.staffId ?? "");
    const active = body.active === true;
    if (!staffId) return json({ ok: false, error: "staffId is required" }, 400);
    if (staffId === callerId && !active) {
      return json({ ok: false, error: "You can't deactivate your own account" }, 400);
    }
    const patchResp = await fetch(`${SUPABASE_URL}/rest/v1/pos_staff?id=eq.${staffId}`, {
      method: "PATCH",
      headers: svcHeaders({ Prefer: "return=minimal" }),
      body: JSON.stringify({ active, updated_at: new Date().toISOString() }),
    });
    if (!patchResp.ok) return json({ ok: false, error: await patchResp.text() }, 400);
    return json({ ok: true, id: staffId, active });
  }

  return json({ ok: false, error: `Unknown action '${action}'` }, 400);
});
