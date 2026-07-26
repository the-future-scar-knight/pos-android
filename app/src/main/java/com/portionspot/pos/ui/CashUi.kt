package com.portionspot.pos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portionspot.pos.data.CashLocation
import com.portionspot.pos.data.DayClose
import com.portionspot.pos.data.PosRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * THE OWNER'S OWN CASH MODEL (§1–§6) — the admin console's cash section.
 *
 * The shop has no bank. Its money sits in two places on the premises: a working TILL
 * float and a SAFE holding the day's takings. This screen is where the owner sees both,
 * moves money between them, closes the day, and answers the question he actually cares
 * about — "how much of this money is actually mine?"
 *
 * ★ ON COMMAND, NEVER ON A TIMER. There is no scheduled prompt anywhere in here and
 * nothing nags. "Close the day" and "Top up float" are BUTTONS, pressed when the person
 * decides. The float target is edited INSIDE those flows, next to the actual cash, rather
 * than buried in Settings. The only thing that happens by itself is the admin
 * notification each completed action raises, so the owner learns it happened.
 */

private val CASH_DAY_FMT = SimpleDateFormat("EEE dd MMM, HH:mm", Locale.getDefault())

private fun parseMoney(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)

/**
 * The whole till-and-safe block. Rendered as one `item {}` inside the admin console's
 * LazyColumn, so it keeps the console's scroll behaviour and its card styling.
 */
@Composable
fun TillAndSafeSection(vm: PosViewModel, currency: String) {
    val t = LocalPosTokens.current
    val till by vm.tillBalance.collectAsState()
    val safe by vm.safeBalance.collectAsState()
    val target by vm.floatTarget.collectAsState()
    val split by vm.cashSplit.collectAsState()
    val totals by vm.outsideFundTotals.collectAsState()
    val lastClose by vm.latestDayClose.collectAsState()
    val closes by vm.dayCloses.collectAsState()

    var showClose by remember { mutableStateOf(false) }
    var showTopUp by remember { mutableStateOf(false) }
    var moneyIn by remember { mutableStateOf(false) }
    var moneyOut by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {

        // ── The two locations, side by side. Both on-site, no bank. ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CashLocationCard(
                label = "Till",
                amount = till,
                currency = currency,
                caption = "Float target ${money(target, currency)}",
                warn = till + 0.005 < target,
                icon = { Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null, tint = t.brand.s500, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f)
            )
            CashLocationCard(
                label = "Safe",
                amount = safe,
                currency = currency,
                caption = "The day's takings",
                warn = false,
                icon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = t.inkSecondary, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            "Cash on hand ${money(till + safe, currency)} — till plus safe. Moving money " +
                "between them changes neither the total nor your profit.",
            color = t.inkTertiary, fontSize = 11.sp
        )

        // ── The two commands. Pressed when the person chooses; never scheduled. ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { showClose = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = t.brand.s600, contentColor = t.inkOnBrand)
            ) { Text("Close the day") }
            OutlinedButton(onClick = { showTopUp = true }, modifier = Modifier.weight(1f)) {
                Text("Top up float")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { moneyIn = true }, modifier = Modifier.weight(1f)) {
                Text("Put money in")
            }
            OutlinedButton(onClick = { moneyOut = true }, modifier = Modifier.weight(1f)) {
                Text("Take money out")
            }
        }
        lastClose?.let { c ->
            Text(
                "Last closed ${CASH_DAY_FMT.format(Date(c.closedAt))} · counted " +
                    "${money(c.countedCash, currency)}" +
                    (if (abs(c.variance) > 0.005)
                        " · ${if (c.variance < 0) "short" else "over"} ${money(abs(c.variance), currency)}"
                    else " · balanced") +
                    (c.closedByName?.let { " · by $it" } ?: ""),
                color = if (abs(c.variance) > 0.005) t.warning else t.inkTertiary, fontSize = 11.sp
            )
        }
        toast?.let { Text(it, color = t.warning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }

        // ── §6 THE SPLIT: how much of this money is actually mine ──
        CashSplitCard(split, currency)

        // ── The owner's own money in and out ──
        OwnerMoneyCard(totals, currency)

        // ── Close history, per cashier, so a repeat offender is visible ──
        if (closes.isNotEmpty()) {
            PosSectionLabel("Close history")
            closes.take(8).forEach { DayCloseRow(it, currency) }
        }
    }

    if (showClose) {
        CloseDayDialog(vm, currency, onDismiss = { showClose = false })
    }
    if (showTopUp) {
        TopUpFloatDialog(vm, currency, onDismiss = { showTopUp = false }) { msg -> toast = msg }
    }
    if (moneyIn) {
        MoneyInDialog(vm, currency, onDismiss = { moneyIn = false })
    }
    if (moneyOut) {
        TakeMoneyOutDialog(vm, currency, till = till, safe = safe, onDismiss = { moneyOut = false })
    }
}

/** One cash location: a big balance with a small caption underneath. */
@Composable
private fun CashLocationCard(
    label: String,
    amount: Double,
    currency: String,
    caption: String,
    warn: Boolean,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalPosTokens.current
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(t.surface1)
            .border(1.dp, t.surfaceBorder, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(6.dp))
            Text(
                label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp, color = t.inkTertiary
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            money(amount, currency), fontSize = 22.sp, fontWeight = FontWeight.Black,
            color = if (amount < 0) t.danger else t.inkPrimary, maxLines = 1
        )
        Text(caption, fontSize = 10.sp, color = if (warn) t.warning else t.inkTertiary, maxLines = 2)
    }
}

/**
 * §6 — the four-part split of the cash actually held. The parts add up to the held cash
 * exactly, because profit is the residual: whatever is left once the float, the restock
 * money and the customers' money are set aside.
 */
@Composable
private fun CashSplitCard(split: PosRepository.CashSplit, currency: String) {
    val t = LocalPosTokens.current
    PosFormCard {
        PosSectionLabel("Where this money sits")
        Text(
            "Cash held ${money(split.held, currency)}",
            color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 18.sp
        )
        SplitRow(
            "Float & capital", split.floatCapital, currency, t.inkSecondary,
            "The float plus money you put in. Never profit."
        )
        SplitRow(
            "Stock money", split.stockMoney, currency, t.inkSecondary,
            "The cost of what you sold. It has to buy the next lot."
        )
        SplitRow(
            "Owed to customers", split.owedToCustomers, currency, t.warning,
            "Change and refunds you still owe. Not your money."
        )
        HorizontalDivider(color = t.surfaceBorder)
        SplitRow(
            "Yours to take", split.profit, currency,
            if (split.profit < 0) t.danger else t.success,
            "What is genuinely profit, in cash, right now."
        )
        if (split.creditOutstanding > 0.005) {
            Text(
                "Plus ${money(split.creditOutstanding, currency)} owed to you on credit — " +
                    "profit you have earned but are not yet holding. It counts as sales " +
                    "only when the money comes in.",
                color = t.inkTertiary, fontSize = 11.sp
            )
        }
        Text(
            "This splits the cash that is here. It says nothing about stock still on the " +
                "shelf — unsold goods are not profit until they sell.",
            color = t.inkTertiary, fontSize = 10.sp
        )
    }
}

@Composable
private fun SplitRow(label: String, amount: Double, currency: String, accent: Color, hint: String) {
    val t = LocalPosTokens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(label, color = t.inkPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(hint, color = t.inkTertiary, fontSize = 10.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(money(amount, currency), color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** The owner's running "put in / taken out / net", plus any borrowings. */
@Composable
private fun OwnerMoneyCard(totals: com.portionspot.pos.data.OutsideFundTotals, currency: String) {
    val t = LocalPosTokens.current
    PosFormCard {
        PosSectionLabel("Your money in the business")
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Put in", color = t.inkTertiary, fontSize = 11.sp)
                Text(money(totals.capitalIn, currency), color = t.inkPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Column(Modifier.weight(1f)) {
                Text("Taken out", color = t.inkTertiary, fontSize = 11.sp)
                Text(money(totals.capitalOut, currency), color = t.inkPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Column(Modifier.weight(1f)) {
                Text("Net", color = t.inkTertiary, fontSize = 11.sp)
                Text(
                    money(totals.ownerNet, currency),
                    color = if (totals.ownerNet < 0) t.danger else t.success,
                    fontWeight = FontWeight.Black, fontSize = 15.sp
                )
            }
        }
        Text(
            "Taking money out does not reduce your profit — it is your own money leaving, not a cost.",
            color = t.inkTertiary, fontSize = 10.sp
        )
        if (totals.loanIn > 0.005 || totals.loanOut > 0.005) {
            HorizontalDivider(color = t.surfaceBorder)
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Borrowed", color = t.inkTertiary, fontSize = 11.sp)
                    Text(money(totals.loanIn, currency), color = t.inkPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text("Repaid", color = t.inkTertiary, fontSize = 11.sp)
                    Text(money(totals.loanOut, currency), color = t.inkPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text("Still owing", color = t.inkTertiary, fontSize = 11.sp)
                    Text(
                        money(totals.loanOutstanding, currency),
                        color = if (totals.loanOutstanding > 0.005) t.warning else t.inkSecondary,
                        fontWeight = FontWeight.Black, fontSize = 15.sp
                    )
                }
            }
        }
    }
}

/** One close in the history — who closed, what they counted, how far out they were. */
@Composable
private fun DayCloseRow(c: DayClose, currency: String) {
    val t = LocalPosTokens.current
    val short = c.variance < -0.005
    val over = c.variance > 0.005
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(t.surface1)
            .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                c.closedByName ?: "Unattributed",
                color = t.inkPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
            )
            Text(
                CASH_DAY_FMT.format(Date(c.closedAt)) +
                    " · counted ${money(c.countedCash, currency)}" +
                    " · ${money(c.movedToSafe, currency)} to safe",
                color = t.inkTertiary, fontSize = 11.sp
            )
            c.note?.let { Text(it, color = t.inkTertiary, fontSize = 11.sp) }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                short -> "Short ${money(-c.variance, currency)}"
                over -> "Over ${money(c.variance, currency)}"
                else -> "Balanced"
            },
            color = when {
                short -> t.danger
                over -> t.warning
                else -> t.success
            },
            fontSize = 12.sp, fontWeight = FontWeight.Bold
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  §2 CLOSE THE DAY
// ══════════════════════════════════════════════════════════════════════════

/**
 * The close-of-day flow, exactly as the owner described it:
 *   expected till cash → enter the COUNTED amount → see the difference → keep the float
 *   target in the till, move the excess to the safe → confirm (having physically moved
 *   it) → everything is recorded in one go.
 *
 * The counted figure WINS: confirming writes a `variance` movement so the ledger agrees
 * with the drawer. A variance over the admin's threshold demands a note; under it, the
 * owner is not made to type. The float target is editable right here — it is a decision
 * made while looking at the cash, not a settings screen.
 */
@Composable
private fun CloseDayDialog(vm: PosViewModel, currency: String, onDismiss: () -> Unit) {
    val t = LocalPosTokens.current
    var proposal by remember { mutableStateOf<PosRepository.DayCloseProposal?>(null) }
    LaunchedEffect(Unit) { proposal = vm.dayCloseProposal() }

    var counted by remember { mutableStateOf("") }
    var targetText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    val p = proposal
    LaunchedEffect(p) {
        if (p != null && !loaded) {
            targetText = fmt2(p.floatTarget)
            loaded = true
        }
    }

    val expected = p?.expectedTill ?: 0.0
    val threshold = p?.noteThreshold ?: PosRepository.DEFAULT_VARIANCE_NOTE_THRESHOLD
    val countedVal = parseMoney(counted)
    val targetVal = parseMoney(targetText) ?: (p?.floatTarget ?: 0.0)
    val variance = (countedVal ?: expected) - expected
    val moveToSafe = ((countedVal ?: 0.0) - targetVal).coerceAtLeast(0.0)
    val needsNote = abs(variance) > threshold + 0.005
    val canConfirm = p != null && countedVal != null && countedVal >= 0.0 &&
        (!needsNote || note.isNotBlank())

    PosContainedForm(
        title = "Close the day",
        onDismiss = onDismiss,
        confirmLabel = "Confirm — money moved",
        confirmEnabled = canConfirm,
        onConfirm = {
            val c = countedVal ?: return@PosContainedForm
            vm.closeDay(
                countedCash = c,
                moveToSafe = moveToSafe,
                floatTarget = targetVal,
                note = note.trim().ifBlank { null }
            )
            onDismiss()
        }
    ) {
        if (p == null) {
            Text("Reading the till…", color = t.inkTertiary, fontSize = 13.sp)
            return@PosContainedForm
        }

        // 1. What the books think is in the drawer.
        PosFormCard {
            Row(Modifier.fillMaxWidth()) {
                Text("Expected in the till", color = t.inkSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(money(expected, currency), color = t.inkPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Text(
                "Everything sold, paid out and moved so far today and before.",
                color = t.inkTertiary, fontSize = 10.sp
            )
        }

        // 2. What is actually there.
        PosField(
            value = counted,
            onValueChange = { counted = it },
            label = "Counted in the till ($currency)",
            keyboardType = KeyboardType.Decimal,
            placeholder = "Count the drawer and type it in",
            modifier = Modifier.fillMaxWidth()
        )

        // 3. The difference, stated plainly.
        if (countedVal != null) {
            val short = variance < -0.005
            val over = variance > 0.005
            Text(
                when {
                    short -> "Short by ${money(-variance, currency)} — this counts as a loss."
                    over -> "Over by ${money(variance, currency)} — this counts as a gain."
                    else -> "Balanced — the drawer matches the books."
                },
                color = when {
                    short -> t.danger
                    over -> t.warning
                    else -> t.success
                },
                fontSize = 13.sp, fontWeight = FontWeight.Bold
            )
            Text(
                "Your count wins. Confirming corrects the books to match it.",
                color = t.inkTertiary, fontSize = 10.sp
            )
        }

        // 4. The float target, set right here, and what it means for the safe.
        PosField(
            value = targetText,
            onValueChange = { targetText = it },
            label = "Keep in the till ($currency)",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.fillMaxWidth()
        )
        if (countedVal != null) {
            PosFormCard {
                Row(Modifier.fillMaxWidth()) {
                    Text("Leave in the till", color = t.inkSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(
                        money(countedVal.coerceAtMost(targetVal), currency),
                        color = t.inkPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp
                    )
                }
                Row(Modifier.fillMaxWidth()) {
                    Text("Move to the safe", color = t.inkSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(
                        money(moveToSafe, currency),
                        color = t.brand.s600, fontWeight = FontWeight.Black, fontSize = 14.sp
                    )
                }
                Text(
                    "Move the cash into the safe first, then confirm. Moving it changes " +
                        "neither your total cash nor your profit.",
                    color = t.inkTertiary, fontSize = 10.sp
                )
            }
        }

        // 5. A note, demanded only when the miss is big enough to matter.
        PosField(
            value = note,
            onValueChange = { note = it },
            label = if (needsNote) "What happened?  (required)" else "Note  (optional)",
            singleLine = false,
            modifier = Modifier.fillMaxWidth()
        )
        if (needsNote && note.isBlank()) {
            Text(
                "A difference over ${money(threshold, currency)} needs an explanation.",
                color = t.warning, fontSize = 11.sp
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  §3 TOP UP THE FLOAT
// ══════════════════════════════════════════════════════════════════════════

/**
 * "The till is $60 — move $40 from the safe?" On command, confirmed by the person who is
 * about to open the safe. A pure transfer: safe down, till up, total cash unchanged.
 */
@Composable
private fun TopUpFloatDialog(
    vm: PosViewModel,
    currency: String,
    onDismiss: () -> Unit,
    onResult: (String) -> Unit
) {
    val t = LocalPosTokens.current
    var proposal by remember { mutableStateOf<PosRepository.FloatTopUp?>(null) }
    LaunchedEffect(Unit) { proposal = vm.floatTopUpProposal() }

    var amountText by remember { mutableStateOf("") }
    var targetText by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    val p = proposal
    LaunchedEffect(p) {
        if (p != null && !loaded) {
            amountText = fmt2(p.available)
            targetText = fmt2(p.target)
            loaded = true
        }
    }

    val amount = parseMoney(amountText) ?: 0.0
    val targetVal = parseMoney(targetText) ?: (p?.target ?: 0.0)
    val canConfirm = p != null && amount > 0.005 && amount <= (p.safe) + 0.005

    PosContainedForm(
        title = "Top up the float",
        onDismiss = onDismiss,
        confirmLabel = "Confirm — money moved",
        confirmEnabled = canConfirm,
        onConfirm = {
            if (targetVal != (p?.target ?: 0.0)) vm.setFloatTarget(targetVal)
            vm.topUpFloat(amount) { moved ->
                onResult(
                    if (moved > 0.005) "Moved ${money(moved, currency)} from the safe into the till."
                    else "Nothing moved — the safe is empty."
                )
            }
            onDismiss()
        }
    ) {
        if (p == null) {
            Text("Reading the till…", color = t.inkTertiary, fontSize = 13.sp)
            return@PosContainedForm
        }
        PosFormCard {
            Row(Modifier.fillMaxWidth()) {
                Text("In the till now", color = t.inkSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(money(p.till, currency), color = t.inkPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Row(Modifier.fillMaxWidth()) {
                Text("In the safe", color = t.inkSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(money(p.safe, currency), color = t.inkPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
        Text(
            if (p.shortfall > 0.005)
                "The till is ${money(p.till, currency)}. Move ${money(p.available, currency)} from the safe?"
            else "The till is already at its target. You can still move money across.",
            color = if (p.shortfall > 0.005) t.inkPrimary else t.inkTertiary,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold
        )
        PosField(
            value = amountText,
            onValueChange = { amountText = it },
            label = "Move from the safe ($currency)",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.fillMaxWidth()
        )
        PosField(
            value = targetText,
            onValueChange = { targetText = it },
            label = "Float target ($currency)",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.fillMaxWidth()
        )
        if (amount > p.safe + 0.005) {
            Text(
                "The safe only holds ${money(p.safe, currency)}.",
                color = t.danger, fontSize = 11.sp
            )
        }
        Text(
            "Open the safe and move the cash, then confirm. Your total cash does not change.",
            color = t.inkTertiary, fontSize = 10.sp
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  §4 / §6 OUTSIDE MONEY IN, OWNER MONEY OUT
// ══════════════════════════════════════════════════════════════════════════

/**
 * Money from OUTSIDE the shop coming in as cash: the owner's own money (capital — it
 * raises what the shop owes him) or a LOAN (a liability to repay). Neither is a sale and
 * neither is profit, which is why they are recorded here and not through the till.
 */
@Composable
private fun MoneyInDialog(vm: PosViewModel, currency: String, onDismiss: () -> Unit) {
    val t = LocalPosTokens.current
    var kind by remember { mutableStateOf("capital") }
    var location by remember { mutableStateOf(CashLocation.SAFE) }
    var amountText by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val amount = parseMoney(amountText) ?: 0.0

    PosContainedForm(
        title = "Put money in",
        onDismiss = onDismiss,
        confirmLabel = "Record it",
        confirmEnabled = amount > 0.005,
        onConfirm = {
            vm.recordOutsideCashIn(
                amount = amount, kind = kind,
                source = source.trim().ifBlank { null },
                note = note.trim().ifBlank { null },
                location = location
            )
            onDismiss()
        }
    ) {
        PosSegmented(
            options = listOf("capital" to "My own money", "loan" to "Borrowed"),
            selected = kind,
            onSelect = { kind = it }
        )
        Text(
            if (kind == "loan")
                "Borrowed money is a debt to repay. It is not a sale and not profit."
            else "Your own money raises what the business owes you. It is not a sale and not profit.",
            color = t.inkTertiary, fontSize = 11.sp
        )
        PosField(
            value = amountText, onValueChange = { amountText = it },
            label = "Amount ($currency)", keyboardType = KeyboardType.Decimal,
            modifier = Modifier.fillMaxWidth()
        )
        PosSegmented(
            options = listOf(CashLocation.TILL to "Into the till", CashLocation.SAFE to "Into the safe"),
            selected = location,
            onSelect = { location = it }
        )
        PosField(
            value = source, onValueChange = { source = it },
            label = if (kind == "loan") "Who lent it  (optional)" else "Source  (optional)",
            modifier = Modifier.fillMaxWidth()
        )
        PosField(
            value = note, onValueChange = { note = it },
            label = "Note  (optional)", modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * TAKE MONEY OUT — the owner drawing cash, or repaying a loan.
 *
 * ★ A drawing is NOT an expense. It reduces the cash held and reduces what the business
 * owes the owner; it must never reduce profit, and it does not, because it is never
 * written to the expense ledger the net-profit figure subtracts.
 */
@Composable
private fun TakeMoneyOutDialog(
    vm: PosViewModel,
    currency: String,
    till: Double,
    safe: Double,
    onDismiss: () -> Unit
) {
    val t = LocalPosTokens.current
    var purpose by remember { mutableStateOf("drawing") }
    var location by remember { mutableStateOf(CashLocation.SAFE) }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val amount = parseMoney(amountText) ?: 0.0
    val available = (if (location == CashLocation.SAFE) safe else till).coerceAtLeast(0.0)

    PosContainedForm(
        title = "Take money out",
        onDismiss = onDismiss,
        confirmLabel = "Record it",
        confirmEnabled = amount > 0.005 && amount <= available + 0.005,
        onConfirm = {
            if (purpose == "loan") {
                vm.recordLoanRepayment(
                    amount = amount, location = location, source = null,
                    note = note.trim().ifBlank { null }
                )
            } else {
                vm.recordOwnerDrawing(
                    amount = amount, location = location,
                    note = note.trim().ifBlank { null }
                )
            }
            onDismiss()
        }
    ) {
        PosSegmented(
            options = listOf("drawing" to "For myself", "loan" to "Repay a loan"),
            selected = purpose,
            onSelect = { purpose = it }
        )
        Text(
            if (purpose == "loan")
                "Repaying borrowed money reduces what you owe. It is not a business cost."
            else "Money you take for yourself. It reduces your cash, not your profit.",
            color = t.inkTertiary, fontSize = 11.sp
        )
        PosSegmented(
            options = listOf(CashLocation.TILL to "From the till", CashLocation.SAFE to "From the safe"),
            selected = location,
            onSelect = { location = it }
        )
        Text("Available ${money(available, currency)}", color = t.inkTertiary, fontSize = 11.sp)
        PosField(
            value = amountText, onValueChange = { amountText = it },
            label = "Amount ($currency)", keyboardType = KeyboardType.Decimal,
            modifier = Modifier.fillMaxWidth()
        )
        if (amount > available + 0.005) {
            Text(
                "There is only ${money(available, currency)} in the " +
                    CashLocation.label(location).lowercase() + ".",
                color = t.danger, fontSize = 11.sp
            )
        }
        PosField(
            value = note, onValueChange = { note = it },
            label = "Note  (optional)", modifier = Modifier.fillMaxWidth()
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  §4 FUNDING SOURCE PICKER — TILL → SAFE → OUTSIDE FUNDS → abort
// ══════════════════════════════════════════════════════════════════════════

/**
 * The one place a payer chooses WHERE the money comes from. Offered in the order the
 * owner actually reaches for it, and honest about what each choice costs:
 *
 *   TILL → SAFE → OUTSIDE FUNDS → abort
 *
 * The SAFE option is shown to everyone but behaves differently by role: an admin takes
 * it inline, anyone else raises a `safe_withdrawal` request that the owner approves on
 * their own phone. That is enforced in the ViewModel, not here — this dialog only says
 * which it will be, so nobody is surprised.
 *
 * "Take what's available" is preserved and is now aware of BOTH locations: it spends the
 * till, then the safe, and leaves any remainder owed.
 */
@Composable
fun FundingSourceDialog(
    title: String,
    amount: Double,
    till: Double,
    safe: Double,
    currency: String,
    isAdmin: Boolean,
    allowPayable: Boolean = true,
    onDismiss: () -> Unit,
    onChoose: (mode: String) -> Unit
) {
    val t = LocalPosTokens.current
    val tillHas = till.coerceAtLeast(0.0)
    val safeHas = safe.coerceAtLeast(0.0)
    val fromTill = amount.coerceAtMost(tillHas)
    val fromSafe = (amount - fromTill).coerceAtMost(safeHas)
    val remainder = (amount - fromTill - fromSafe).coerceAtLeast(0.0)

    PosDialog(title = title, onDismiss = onDismiss) {
        Text(
            "Paying ${money(amount, currency)}. Where does it come from?",
            color = t.inkPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
        )
        Text(
            "Till ${money(tillHas, currency)} · Safe ${money(safeHas, currency)}",
            color = t.inkTertiary, fontSize = 11.sp
        )

        // 1. The till.
        if (tillHas + 0.005 >= amount) {
            Button(
                onClick = { onChoose("till"); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = t.brand.s600, contentColor = t.inkOnBrand)
            ) { Text("From the till") }
        }

        // 2. The safe — approval-gated for anyone who is not the owner.
        if (safeHas > 0.005) {
            OutlinedButton(onClick = { onChoose("safe"); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (isAdmin) "From the safe" else "Ask the owner to open the safe")
            }
            if (!isAdmin) {
                Text(
                    "The owner has to approve every trip to the safe. This sends them a " +
                        "request; once they approve, the money moves into the till.",
                    color = t.inkTertiary, fontSize = 10.sp
                )
            }
        }

        // 3. Take what's available across BOTH locations, owe the rest.
        if (tillHas + safeHas + 0.005 < amount && allowPayable) {
            OutlinedButton(onClick = { onChoose("waterfall"); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                Text("Take ${money(fromTill + fromSafe, currency)} · owe ${money(remainder, currency)}")
            }
        }

        HorizontalDivider(color = t.surfaceBorder)
        PosSectionLabel("Money from outside the shop")

        // 4. Outside funds — the owner's own money, or borrowed.
        OutlinedButton(onClick = { onChoose("capital"); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
            Text("My own money (shop cash untouched)")
        }
        OutlinedButton(onClick = { onChoose("loan"); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
            Text("Borrowed money (a debt to repay)")
        }
        Text(
            "Neither is a sale and neither is profit. Your own money raises what the " +
                "business owes you; borrowed money is a debt.",
            color = t.inkTertiary, fontSize = 10.sp
        )

        // 5. Abort.
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = t.danger)
        ) { Text("Cancel — pay nothing") }
    }
}
