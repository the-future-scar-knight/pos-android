// Tiny PostgREST helper for the payment_intents table, using the SERVICE-ROLE
// key (auto-injected into every Edge Function). Service role bypasses RLS, so
// this table never needs an anon policy — keeping payment data off the public
// anon key the app holds.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

export interface PaymentIntent {
  id: string;
  business_id?: string | null;
  sale_id?: string | null;
  amount: number;
  currency?: string | null;
  status: string;
  paynow_reference?: string | null;
  paynow_poll_url?: string | null;
  browser_url?: string | null;
  method?: string | null;
  phone?: string | null;
  merchant_trace?: string | null;
  /** Paynow's status verbatim, before normaliseStatus folds it down. */
  raw_status?: string | null;
  created_at: string;
  updated_at: string;
}

function headers(extra: Record<string, string> = {}): HeadersInit {
  return {
    apikey: SERVICE_KEY,
    authorization: `Bearer ${SERVICE_KEY}`,
    "content-type": "application/json",
    ...extra,
  };
}

const rest = (path: string) => `${SUPABASE_URL}/rest/v1/${path}`;

/** Insert-or-merge one intent (PK = id). */
export async function upsertIntent(row: PaymentIntent): Promise<void> {
  const resp = await fetch(rest("payment_intents?on_conflict=id"), {
    method: "POST",
    headers: headers({ Prefer: "resolution=merge-duplicates,return=minimal" }),
    body: JSON.stringify([row]),
  });
  if (!resp.ok) {
    throw new Error(`upsert intent failed: ${resp.status} ${await resp.text()}`);
  }
}

/** Look up a single intent by our merchant reference, or null. */
export async function getIntent(id: string): Promise<PaymentIntent | null> {
  const url = rest(`payment_intents?id=eq.${encodeURIComponent(id)}&select=*&limit=1`);
  const resp = await fetch(url, { headers: headers() });
  if (!resp.ok) {
    throw new Error(`get intent failed: ${resp.status} ${await resp.text()}`);
  }
  const rows = (await resp.json()) as PaymentIntent[];
  return rows.length ? rows[0] : null;
}

/** Patch selected columns of one intent. */
export async function patchIntent(
  id: string,
  patch: Partial<PaymentIntent>,
): Promise<void> {
  const url = rest(`payment_intents?id=eq.${encodeURIComponent(id)}`);
  const resp = await fetch(url, {
    method: "PATCH",
    headers: headers({ Prefer: "return=minimal" }),
    body: JSON.stringify(patch),
  });
  if (!resp.ok) {
    throw new Error(`patch intent failed: ${resp.status} ${await resp.text()}`);
  }
}

export function nowIso(): string {
  // toISOString() is already fixed-width "yyyy-MM-ddTHH:mm:ss.SSSZ",
  // which matches the app's IsoTime format exactly.
  return new Date().toISOString();
}
