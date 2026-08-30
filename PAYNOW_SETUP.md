# Paynow online payments (QR + auto-verify)

This is **optional**. Without it, the app still offers Paynow as a manual
tender — the cashier types the transaction reference and confirms the sale.
Turning on the steps below adds the *online* experience the brief asked for:
the app asks Paynow to create the payment, shows a **QR code** at the counter,
and **verifies the payment automatically** (polling + a webhook) before the
sale is completed.

The money goes **straight to your own Paynow merchant account**. ON-SPOT POS
never touches it, and your secret Integration **Key never leaves your own
Supabase project** — it is stored as a Function secret, not in any table and
not in the app.

---

## What you need

1. A Paynow merchant account with an **Integration ID** and **Integration Key**
   (Paynow Dashboard → *Integrations* → *Create / view*).
2. The same Supabase project you already use for **Settings → Cloud sync**
   (the app calls the functions at that same URL with the same anon key).
3. The **Supabase CLI** installed on a computer:
   <https://supabase.com/docs/guides/cli> (`npm i -g supabase`, or scoop/brew).

---

## One-time setup

### 1. Create the table

Run `supabase-setup.sql` in your project's SQL Editor (you may have already
done this for Cloud sync — it's safe to re-run). It creates the locked-down
`payment_intents` table the functions use, in section 7d.

This step is **not optional and not silent**: if `payment_intents` is missing,
`paynow-initiate` now refuses the payment rather than handing the counter a QR
code it will never be able to confirm.

### 2. Log in and link the CLI to your project

```bash
supabase login
supabase link --project-ref YOUR_PROJECT_REF
```

`YOUR_PROJECT_REF` is the part of your project URL:
`https://<PROJECT_REF>.supabase.co`.

### 3. Store your Paynow credentials as Function secrets

These live only in your Supabase project, encrypted. **Do not** put them in the
app or in any table.

```bash
supabase secrets set PAYNOW_INTEGRATION_ID=your_integration_id
supabase secrets set PAYNOW_INTEGRATION_KEY=your_integration_key
supabase secrets set PAYNOW_AUTH_EMAIL=your_paynow_account_email
```

(`SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` are provided automatically — you
do not set those.)

**`PAYNOW_AUTH_EMAIL` must be the email you log into Paynow with** — the account
that owns the integration, not the shop's customer-facing address. Paynow's test
mode is strict about this:

> After creating a transaction ONLY THE MERCHANT ACCOUNT USED TO CREATE THE
> INTEGRATION can login and Fake a Payment. Any other users will get a message
> saying the merchant is in testing and they cannot proceed with payment.

Get it wrong and nothing errors — you just get a transaction that nobody is
allowed to pay. That is why it is a secret here and not an editable setting in
the app: the till never sends it.

### 4. Deploy the three functions

From the `pos-android` folder (where the `supabase/functions` folder lives):

```bash
supabase functions deploy paynow-initiate
supabase functions deploy paynow-status
supabase functions deploy paynow-webhook
```

`paynow-webhook` must be reachable by Paynow's servers, which it is by default
(Supabase Edge Functions are public HTTPS URLs). Its address is:

```
https://YOUR_PROJECT_REF.functions.supabase.co/paynow-webhook
```

You don't need to paste that anywhere — the `paynow-initiate` function tells
Paynow about it automatically as the transaction's `resulturl`.

### 5. Turn it on in the app

In ON-SPOT POS: **Settings → Cloud sync** must be connected (same project), and
**Settings → Payment methods → Paynow** must be enabled. That's it — at
checkout, picking **Paynow** now shows a **Generate QR** button.

---

## How a sale works

1. Cashier rings up the cart and picks **Paynow**.
2. Taps **Generate Paynow QR**. The app calls `paynow-initiate`; your Supabase
   builds the request (signing it with your secret Key), Paynow returns a
   payment page URL, and the app shows it as a **QR code** plus the amount.
3. The customer scans the QR and pays from their phone.
4. The app polls `paynow-status` every few seconds. As soon as Paynow reports
   the money received (hash-verified server-side), the app fills in the Paynow
   reference and lets the cashier **complete the sale**. The webhook updates the
   record too, so a payment is never missed even if the app was closed.

## Testing before you go live

A new integration starts in **test mode**, where no money moves.

1. Ring up a sale, pick **Paynow**, tap **Generate Paynow QR**.
2. Scan it, or open the URL on any browser.
3. **Log into Paynow as the merchant account** (the same email as
   `PAYNOW_AUTH_EMAIL`). No other account is allowed to pay a test transaction.
4. Choose **[TESTING: Faked Success]** and **Make Payment**.
5. The counter should flip to paid within a few seconds — the app polls every
   4.5s, and the webhook updates the record even if the app was closed.

When you are happy, go to the Integration Keys section on Paynow and click
**Request to be Set Live**. Paynow checks you have completed at least one
successful test transaction first.

## Security notes

- The **Integration Key** is only ever read inside your Edge Functions. It is
  never sent to the phone and never written to a table.
- `payment_intents` has Row Level Security **on with no anon policy**, so the
  public anon key the app holds cannot read payment data — only the functions
  (service role) can.
- Every reply from Paynow (initiate, poll, webhook) is **hash-verified** with
  your Key before it is trusted, so a forged "paid" message is rejected.
- Re-initiating or a late duplicate webhook can't flip a paid sale back — once
  `paid`, it stays `paid` (idempotent).
