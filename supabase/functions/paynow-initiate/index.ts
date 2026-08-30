// paynow-initiate — create a Paynow web transaction and return a URL to show as
// a QR at the counter. The secret Integration Key is read from a Function
// secret and used only here, server-side; it never goes to the app.
//
// Request  (POST JSON): { amount: number, reference?: string,
//                         businessId?: string, currency?: string }
// Response (JSON):      { ok: true, reference, browserUrl, pollUrl }
//                    or { ok: false, error }
//
// NB: `authemail` is deliberately NOT accepted from the caller. It must be the
// merchant's own Paynow account email (see paynowAuthEmail), and letting a till
// choose it is how you end up with a transaction nobody can pay.

import {
  PAYNOW_INITIATE_URL,
  paynowCredentials,
  paynowAuthEmail,
  paynowHash,
  parseForm,
  field,
  toFormBody,
  verifyReply,
  json,
} from "../_shared/paynow.ts";
import { nowIso, upsertIntent } from "../_shared/db.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return json({}, 204);
  if (req.method !== "POST") return json({ ok: false, error: "POST only" }, 405);

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return json({ ok: false, error: "Invalid JSON body" }, 400);
  }

  const amountNum = Number(body.amount);
  if (!isFinite(amountNum) || amountNum <= 0) {
    return json({ ok: false, error: "amount must be a positive number" }, 400);
  }
  const amount = amountNum.toFixed(2);
  const reference = String(body.reference ?? crypto.randomUUID());
  const businessId = body.businessId != null ? String(body.businessId) : null;
  const currency = body.currency != null ? String(body.currency) : "USD";

  let creds: { id: string; key: string };
  let authemail: string;
  try {
    creds = paynowCredentials();
    authemail = paynowAuthEmail();
  } catch (e) {
    return json({ ok: false, error: (e as Error).message }, 500);
  }

  // A merchant trace lets us find this transaction again if the reply below
  // never reaches us. Max 32 chars, unique per merchant — a bare UUID hex is 32.
  const merchantTrace = crypto.randomUUID().replace(/-/g, "");

  const origin = Deno.env.get("SUPABASE_URL")!;
  const resulturl = `${origin}/functions/v1/paynow-webhook`;
  // Paynow requires a returnurl; the buyer pays in a browser/app, so we send
  // them back to a neutral page. The POS itself confirms via polling/webhook.
  const returnurl = "https://www.paynow.co.zw/";

  // Field order here is the order we hash AND the order we send. Paynow rebuilds
  // the hash from the fields as received, with `hash` last, so the two only ever
  // have to agree with each other — not with any canonical ordering.
  const fields: Array<[string, string]> = [
    ["resulturl", resulturl],
    ["returnurl", returnurl],
    ["reference", reference],
    ["amount", amount],
    ["id", creds.id],
    ["additionalinfo", "ON-SPOT POS sale"],
    ["authemail", authemail],
    ["merchanttrace", merchantTrace],
    ["status", "Message"],
  ];
  const hash = await paynowHash(fields, creds.key);
  const formBody = toFormBody([...fields, ["hash", hash]]);

  let replyText: string;
  try {
    const resp = await fetch(PAYNOW_INITIATE_URL, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: formBody,
    });
    replyText = await resp.text();
  } catch (e) {
    return json({ ok: false, error: `Could not reach Paynow: ${(e as Error).message}` }, 502);
  }

  const reply = parseForm(replyText);
  const status = (field(reply, "status") ?? "").toLowerCase();
  if (status !== "ok") {
    const err = field(reply, "error") ?? `Paynow refused the request (${replyText})`;
    return json({ ok: false, error: err }, 400);
  }

  // Verify the reply hash so a man-in-the-middle can't feed us a fake URL.
  if (!(await verifyReply(reply, creds.key))) {
    return json({ ok: false, error: "Paynow reply failed hash verification" }, 502);
  }

  const browserUrl = field(reply, "browserurl") ?? "";
  const pollUrl = field(reply, "pollurl") ?? "";
  const stamp = nowIso();

  // An intent we cannot record is an intent we cannot poll: paynow-status looks
  // the poll URL up by reference, so a missing row means the cashier watches
  // "Unknown reference" forever while the customer's money moves. Fail here,
  // loudly, before any QR is shown. The transaction is left unpaid at Paynow and
  // the trace below is how it is found again if it ever mattered.
  try {
    await upsertIntent({
      id: reference,
      business_id: businessId,
      sale_id: null,
      amount: amountNum,
      currency,
      status: "sent",
      paynow_reference: null,
      paynow_poll_url: pollUrl,
      browser_url: browserUrl,
      method: null,
      phone: null,
      merchant_trace: merchantTrace,
      raw_status: field(reply, "status") ?? null,
      created_at: stamp,
      updated_at: stamp,
    });
  } catch (e) {
    console.error(
      `intent not recorded (reference=${reference} trace=${merchantTrace}):`,
      e,
    );
    return json({
      ok: false,
      error:
        "The payment could not be recorded, so it cannot be confirmed later. " +
        "Nothing has been charged. Check that payment_intents exists (run " +
        `supabase-setup.sql). Trace: ${merchantTrace}`,
    }, 500);
  }

  return json({ ok: true, reference, browserUrl, pollUrl });
});
