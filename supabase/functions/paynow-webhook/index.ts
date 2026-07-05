// paynow-webhook — the `resulturl` Paynow calls server-to-server when a
// transaction changes state. This is the reliable backstop: even if the POS is
// closed when the buyer pays, the intent row is brought up to date here.
//
// Paynow POSTs application/x-www-form-urlencoded. We MUST verify the hash with
// our Integration Key before trusting anything in the body.

import {
  paynowCredentials,
  parseForm,
  field,
  verifyReply,
  normaliseStatus,
} from "../_shared/paynow.ts";
import { getIntent, patchIntent, nowIso } from "../_shared/db.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return new Response("POST only", { status: 405 });

  let creds: { id: string; key: string };
  try {
    creds = paynowCredentials();
  } catch (e) {
    return new Response((e as Error).message, { status: 500 });
  }

  const raw = await req.text();
  const reply = parseForm(raw);

  // Reject anything we can't cryptographically verify.
  if (!(await verifyReply(reply, creds.key))) {
    return new Response("bad hash", { status: 400 });
  }

  const reference = field(reply, "reference");
  if (!reference) return new Response("no reference", { status: 400 });

  const intent = await getIntent(reference);
  if (!intent) return new Response("unknown reference", { status: 404 });

  // Once paid, stay paid — never downgrade on a late duplicate callback.
  if (intent.status === "paid") return new Response("ok", { status: 200 });

  const mapped = normaliseStatus(field(reply, "status"));
  try {
    await patchIntent(reference, {
      status: mapped,
      paynow_reference: field(reply, "paynowreference") ?? intent.paynow_reference ?? null,
      updated_at: nowIso(),
    });
  } catch (e) {
    return new Response(`update failed: ${(e as Error).message}`, { status: 500 });
  }

  return new Response("ok", { status: 200 });
});
