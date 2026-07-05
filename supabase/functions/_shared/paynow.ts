// Shared Paynow helpers for the ON-SPOT POS Edge Functions.
//
// These run in Supabase's Deno runtime inside the SHOP'S OWN project, so the
// secret Integration Key (read from an env secret) never reaches the Android
// app or any database table. The hashing/parsing here mirrors the canonical
// Paynow `paynow` JS SDK so the gateway accepts our requests and we can verify
// its replies.

export const PAYNOW_INITIATE_URL =
  "https://www.paynow.co.zw/interface/initiatetransaction";

/** Uppercase SHA-512 hex of a UTF-8 string (Paynow's hashing primitive). */
export async function sha512Upper(input: string): Promise<string> {
  const bytes = new TextEncoder().encode(input);
  const digest = await crypto.subtle.digest("SHA-512", bytes);
  return [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("")
    .toUpperCase();
}

/**
 * Paynow hash = SHA512( concat of every field VALUE except `hash`, in order,
 * then the integration key ). We keep fields as an ordered [key,value] list so
 * the order we hash is exactly the order we send / receive.
 */
export async function paynowHash(
  fields: Array<[string, string]>,
  integrationKey: string,
): Promise<string> {
  let joined = "";
  for (const [k, v] of fields) {
    if (k.toLowerCase() !== "hash") joined += v;
  }
  joined += integrationKey;
  return await sha512Upper(joined);
}

/** Build an application/x-www-form-urlencoded body from ordered fields. */
export function toFormBody(fields: Array<[string, string]>): string {
  return fields
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
    .join("&");
}

/**
 * Parse Paynow's urlencoded reply into an ordered [key,value] list (order
 * preserved so we can re-hash it for verification).
 */
export function parseForm(text: string): Array<[string, string]> {
  const out: Array<[string, string]> = [];
  for (const part of text.split("&")) {
    if (!part) continue;
    const i = part.indexOf("=");
    const k = i < 0 ? part : part.slice(0, i);
    const v = i < 0 ? "" : part.slice(i + 1);
    out.push([
      decodeURIComponent(k.replace(/\+/g, " ")),
      decodeURIComponent(v.replace(/\+/g, " ")),
    ]);
  }
  return out;
}

/** Case-insensitive lookup in an ordered field list. */
export function field(
  fields: Array<[string, string]>,
  name: string,
): string | undefined {
  const lower = name.toLowerCase();
  for (const [k, v] of fields) if (k.toLowerCase() === lower) return v;
  return undefined;
}

/**
 * Verify the `hash` Paynow attached to a reply against a freshly computed one.
 * Returns true only when a hash is present AND matches — never trust an
 * unverified status update.
 */
export async function verifyReply(
  fields: Array<[string, string]>,
  integrationKey: string,
): Promise<boolean> {
  const given = field(fields, "hash");
  if (!given) return false;
  const expected = await paynowHash(fields, integrationKey);
  return timingSafeEqual(given.toUpperCase(), expected);
}

/** Constant-time string compare to avoid leaking the hash via timing. */
export function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/** Paynow statuses that mean the money has been received. */
export function isPaidStatus(status: string | undefined): boolean {
  const s = (status ?? "").trim().toLowerCase();
  return s === "paid" || s === "awaiting delivery" || s === "delivered";
}

/** Map a raw Paynow status onto our small payment_intents vocabulary. */
export function normaliseStatus(status: string | undefined): string {
  const s = (status ?? "").trim().toLowerCase();
  if (isPaidStatus(s)) return "paid";
  if (s === "cancelled") return "cancelled";
  if (s === "" ) return "sent";
  if (s === "created" || s === "sent") return "sent";
  // disputed / refunded / failed and anything unexpected -> failed
  if (s === "disputed" || s === "refunded" || s === "failed") return "failed";
  return "sent";
}

/** Read the two Paynow secrets; throws a clear error if not configured. */
export function paynowCredentials(): { id: string; key: string } {
  const id = Deno.env.get("PAYNOW_INTEGRATION_ID")?.trim();
  const key = Deno.env.get("PAYNOW_INTEGRATION_KEY")?.trim();
  if (!id || !key) {
    throw new Error(
      "Paynow is not configured: set PAYNOW_INTEGRATION_ID and " +
        "PAYNOW_INTEGRATION_KEY as Edge Function secrets.",
    );
  }
  return { id, key };
}

/** JSON response helper with permissive CORS (harmless for the native app). */
export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json",
      "access-control-allow-origin": "*",
      "access-control-allow-headers": "authorization, apikey, content-type",
      "access-control-allow-methods": "POST, OPTIONS",
    },
  });
}
