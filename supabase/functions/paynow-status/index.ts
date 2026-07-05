// paynow-status — poll Paynow for the result of a transaction the POS started.
// The app calls this every few seconds while the QR is on screen. We look up
// the poll URL by our merchant reference (so the app never has to hold it),
// fetch + hash-verify Paynow's reply server-side, update payment_intents, and
// return a simple paid/not-paid answer.
//
// Request  (POST JSON): { reference: string }
// Response (JSON):      { ok: true, status, paid, paynowReference }
//                    or { ok: false, error }

import {
  paynowCredentials,
  parseForm,
  field,
  verifyReply,
  isPaidStatus,
  normaliseStatus,
  json,
} from "../_shared/paynow.ts";
import { getIntent, patchIntent, nowIso } from "../_shared/db.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return json({}, 204);
  if (req.method !== "POST") return json({ ok: false, error: "POST only" }, 405);

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return json({ ok: false, error: "Invalid JSON body" }, 400);
  }

  const reference = String(body.reference ?? "").trim();
  if (!reference) return json({ ok: false, error: "reference is required" }, 400);

  let creds: { id: string; key: string };
  try {
    creds = paynowCredentials();
  } catch (e) {
    return json({ ok: false, error: (e as Error).message }, 500);
  }

  const intent = await getIntent(reference);
  if (!intent) return json({ ok: false, error: "Unknown reference" }, 404);

  // Already settled — don't re-poll, just report (idempotent).
  if (intent.status === "paid") {
    return json({
      ok: true,
      status: "paid",
      paid: true,
      paynowReference: intent.paynow_reference ?? null,
    });
  }

  const pollUrl = intent.paynow_poll_url;
  if (!pollUrl) return json({ ok: false, error: "No poll URL on file" }, 409);

  let replyText: string;
  try {
    const resp = await fetch(pollUrl, { method: "GET" });
    replyText = await resp.text();
  } catch (e) {
    return json({ ok: false, error: `Could not reach Paynow: ${(e as Error).message}` }, 502);
  }

  const reply = parseForm(replyText);
  if (!(await verifyReply(reply, creds.key))) {
    return json({ ok: false, error: "Paynow reply failed hash verification" }, 502);
  }

  const rawStatus = field(reply, "status");
  const paid = isPaidStatus(rawStatus);
  const paynowReference = field(reply, "paynowreference") ?? intent.paynow_reference ?? null;
  const mapped = normaliseStatus(rawStatus);

  try {
    await patchIntent(reference, {
      status: mapped,
      paynow_reference: paynowReference,
      updated_at: nowIso(),
    });
  } catch (_e) {
    // Reporting the live status to the cashier matters more than the write.
  }

  return json({ ok: true, status: mapped, paid, paynowReference });
});
