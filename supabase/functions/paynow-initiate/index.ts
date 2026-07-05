// paynow-initiate — create a Paynow web transaction and return a URL to show as
// a QR at the counter. The secret Integration Key is read from a Function
// secret and used only here, server-side; it never goes to the app.
//
// Request  (POST JSON): { amount: number, reference?: string, authemail?: string,
//                         businessId?: string }
// Response (JSON):      { ok: true, reference, browserUrl, pollUrl }
//                    or { ok: false, error }

import {
  PAYNOW_INITIATE_URL,
  paynowCredentials,
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
  const authemail = String(body.authemail ?? "");
  const businessId = body.businessId != null ? String(body.businessId) : null;

  let creds: { id: string; key: string };
  try {
    creds = paynowCredentials();
  } catch (e) {
    return json({ ok: false, error: (e as Error).message }, 500);
  }

  const origin = Deno.env.get("SUPABASE_URL")!;
  const resulturl = `${origin}/functions/v1/paynow-webhook`;
  // Paynow requires a returnurl; the buyer pays in a browser/app, so we send
  // them back to a neutral page. The POS itself confirms via polling/webhook.
  const returnurl = "https://www.paynow.co.zw/";

  // Field order here is the order we hash AND the order we send (Paynow
  // validates the hash over the received fields in order).
  const fields: Array<[string, string]> = [
    ["resulturl", resulturl],
    ["returnurl", returnurl],
    ["reference", reference],
    ["amount", amount],
    ["id", creds.id],
    ["additionalinfo", "ON-SPOT POS sale"],
    ["authemail", authemail],
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

  try {
    await upsertIntent({
      id: reference,
      business_id: businessId,
      sale_id: null,
      amount: amountNum,
      status: "sent",
      paynow_reference: null,
      paynow_poll_url: pollUrl,
      browser_url: browserUrl,
      created_at: stamp,
      updated_at: stamp,
    });
  } catch (e) {
    // The transaction exists at Paynow; surface the bookkeeping error but still
    // give the caller the URL so a sale isn't lost.
    return json({
      ok: true,
      reference,
      browserUrl,
      pollUrl,
      warning: `intent not recorded: ${(e as Error).message}`,
    });
  }

  return json({ ok: true, reference, browserUrl, pollUrl });
});
