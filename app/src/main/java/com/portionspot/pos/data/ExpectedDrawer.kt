package com.portionspot.pos.data

/**
 * WHAT SHOULD BE IN THE DRAWER — the Z-report cash-up, rebuilt from the rows that
 * actually moved money.
 *
 * ── THE THREE BUGS THIS REPLACES ──────────────────────────────────────────────
 *
 * The Z-report used to derive its expected drawer from the dashboard's `recent` sales
 * list, in the UI, like this:
 *
 *     val todays   = recent.filter { it.soldAt >= startToday && it.status == "completed" }
 *     val cashSales = todays.filter { it.paymentMethod == "cash" }.sumOf { it.total }
 *     val expected  = typedOpeningFloat + cashSales
 *
 * Every clause of that is wrong, and each is wrong in a way that a shop only discovers
 * when the count comes out short and someone gets blamed:
 *
 *  1. **It read the sale HEADER's payment method.** [SaleEntity.paymentMethod] holds ONE
 *     tender code, or the literal string `"split"` when the sale has more than one tender
 *     row. So a $50 cash + $30 EcoCash sale contributed **nothing** to the expected
 *     drawer — the filter matched neither `"cash"` nor anything else. The truth is in
 *     [SalePayment] rows, which is why this file sums those instead.
 *
 *  2. **It was capped at the UI's `observeRecent(100)`.** The 101st receipt of a busy day
 *     silently stopped counting. An expected drawer that quietly truncates is worse than
 *     no expected drawer: it is confidently wrong, and it gets worse the busier the shop
 *     is. Every query behind this object is windowed by TIME and has no `LIMIT`.
 *
 *  3. **It ignored money that left or moved.** Refund payouts, cash-funded expenses and
 *     till→safe transfers all empty a real drawer; none of them appeared. Those are in
 *     here, taken from the till's own ledger.
 *
 * A fourth, quieter defect went with them: the filter was `status == "completed"`, which
 * DROPS a sale that has since been partly refunded (`status = "refunded"`) — the receipt
 * is still a receipt and its cash is still in the drawer. Receipts are [RECEIPT_STATUSES],
 * and both the SQL and the filter below name that one constant rather than re-spelling it.
 *
 * ── SHOP-WIDE, NOT PER TILL ───────────────────────────────────────────────────
 *
 * Decided, not assumed. A trading day is one [CashSession] per SHOP (see `DaySession.kt`),
 * and `CashMirror.kt` already makes a sale pulled from the other phone create a local cash
 * row so both phones agree on one drawer. There is ONE physical drawer; an expected figure
 * scoped to the phone that happened to ring a sale up would disagree with the money in it.
 *
 * ── WHY REBUILD FROM SOURCE ROWS WHEN THE TILL LEDGER ALREADY EXISTS ──────────
 *
 * [PosRepository.closeDay] takes its expected figure from `tillBalanceOnce` — the running
 * `cash_txns` balance — and that stays the authority for the close, because the close
 * writes a permanent variance against it. This is deliberately a SECOND, INDEPENDENT
 * derivation, and the independence is the point: the ledger only learns about another
 * till's sale when `reconcilePulledCash` runs, whereas the tender rows are there the
 * moment the sale is pulled. If the two disagree, the ledger has not caught up, and a
 * cash-up that quietly used the stale one would book the gap as a till shortage — a loss,
 * against a cashier's name. The opening balance is still read off the ledger (there is
 * nothing else that knows what the drawer started with), so the two figures agree once the
 * mirror has run.
 *
 * ── PURE ON PURPOSE ───────────────────────────────────────────────────────────
 *
 * No Room, no Android, no clock. The window is a pair of millis passed in, the rows are
 * plain data. That is what lets `ExpectedDrawerTest` put 150 receipts through it, or a
 * split tender, or a dual-currency one, and assert the answer — which is the only reason
 * to believe any of the three bugs above cannot come back.
 */

/**
 * One receipt in the window. [status] travels WITH the row rather than being filtered away
 * in SQL alone, so the "a refunded sale is still a receipt" rule is asserted in a test
 * instead of living only inside a query string nothing can execute off-device.
 *
 * [changeGiven] is the sale's `changeDue`, which — per [PosRepository.checkout] and
 * [netCashForSale] — is the change ACTUALLY HANDED OVER, not the change owed. Change the
 * shop could not give back never left the drawer; it is a liability, and it is counted as
 * one in the credit ledger, not subtracted here.
 */
data class DrawerReceipt(
    val saleId: String,
    val status: String,
    val total: Double,
    val changeGiven: Double,
)

/**
 * One tender against one receipt — a row of `sale_payments`, which is where a SPLIT
 * payment's truth lives.
 *
 * ★ [amount] IS THE ONLY FIGURE ANY SUM MAY READ. It is always in the shop's base
 * currency. [tenderCurrency]/[tenderAmount] are the second-currency face value of the
 * notes that crossed the counter and exist for DISPLAY and the Z-report's currency split —
 * summing 340 ZWG into a USD drawer is not a rounding error, it is a different number
 * altogether. The trio is carried here precisely so the temptation is visible and the
 * refusal is testable.
 */
data class DrawerTender(
    val saleId: String,
    val method: String,
    val amount: Double,
    val tenderCurrency: String? = null,
    val tenderAmount: Double? = null,
)

/**
 * One payout against a refund — a row of `refund_payments`.
 *
 * Windowed on the PAYOUT's own `createdAt`, never the refund's: a refund can be paid off
 * over days, and the drawer is emptied on the day the money is handed over. Only a `cash`
 * payout ever opens the drawer; a card or EcoCash reversal goes back the way it came.
 */
data class DrawerPayout(
    val method: String,
    val amount: Double,
    val tenderCurrency: String? = null,
    val tenderAmount: Double? = null,
)

/**
 * Everything else that moved the till in the window, netted per [CashTxn.type] — expenses
 * and purchases paid out of the drawer, till↔safe transfers, change paid out later,
 * owner money in, drawings, and a day-close true-up.
 *
 * [amount] is SIGNED and already location-filtered to the till, so the sum is a plain
 * addition. Sale and refund movements are EXCLUDED upstream (`refType` `sale` / `refund`)
 * because this object rebuilds those from the tender and payout rows themselves; counting
 * both would double every receipt.
 *
 * Grouped by type rather than reduced to one scalar so the cash-up can show WHY the
 * expected figure is not just the day's takings — "$40 of it went out as an expense" is
 * the difference between a cashier understanding a count and disputing it.
 */
data class DrawerMovement(
    val type: String,
    val amount: Double,
)

/**
 * The cash-up, with every component named so the arithmetic on screen can be checked by
 * eye. [expected] is the only figure a variance is ever measured against.
 *
 *  - [opening]          what the till held before the window opened (ledger, not typed).
 *  - [receipts]         how many real receipts the window contains.
 *  - [salesValue]       what those receipts BILLED, every tender type. Context, not cash.
 *  - [cashIn]           cash tenders received, base currency.
 *  - [nonCashIn]        card / mobile-money / bank tenders. Never touches the drawer;
 *                       shown so the cashier can see the day's split and NOT wonder why
 *                       the drawer holds less than the sales figure.
 *  - [changeGiven]      change actually handed back out of those cash tenders.
 *  - [cashRefunded]     cash paid back out on refunds, POSITIVE.
 *  - [nonCashRefunded]  refunds reversed to a card / wallet. Drawer-neutral, shown for the
 *                       same reason [nonCashIn] is.
 *  - [otherMovements]   signed net of [DrawerMovement] — expenses out, transfers, etc.
 *  - [foreignTenders]   second-currency face value taken in cash, by currency code. The
 *                       Z-report's currency split; NEVER part of [expected].
 */
data class ExpectedDrawer(
    val opening: Double = 0.0,
    val receipts: Int = 0,
    val salesValue: Double = 0.0,
    val cashIn: Double = 0.0,
    val nonCashIn: Double = 0.0,
    val changeGiven: Double = 0.0,
    val cashRefunded: Double = 0.0,
    val nonCashRefunded: Double = 0.0,
    val otherMovements: Double = 0.0,
    val foreignTenders: Map<String, Double> = emptyMap(),
    val movements: List<DrawerMovement> = emptyList(),
) {
    /** Cash the day's selling actually left in the drawer — the same arithmetic
     *  [netCashForSale] does per sale, so the two can never drift. */
    val netCashFromSales: Double get() = cashIn - changeGiven

    /**
     * What should be in the drawer right now.
     *
     * The funding waterfall (till → safe → outside funds) decides the SIGNS: anything
     * paid out of the till is negative in [otherMovements] already, a top-up from the safe
     * is positive there, and money that never reached the till (paid straight from the
     * safe, or from outside funds) never appears — which is correct, because it never
     * moved this drawer.
     */
    val expected: Double
        get() = opening + netCashFromSales - cashRefunded + otherMovements

    /** Counted minus expected. NEGATIVE is short (a real loss); positive is over. */
    fun variance(counted: Double): Double = counted - expected
}

/** The tender code that physically opens a drawer. Everything else is drawer-neutral. */
const val DRAWER_CASH = "cash"

/** Half a cent — the tolerance every money comparison in this codebase uses. */
private const val DRAWER_CENT = 0.005

/**
 * Build the cash-up from the window's rows.
 *
 * [receipts] is filtered on [RECEIPT_STATUSES] HERE as well as in SQL, and that is not
 * belt-and-braces for its own sake: a quote or a parked cart carries tender rows in some
 * flows, and a drawer figure that counted a quote would show cash for goods that never
 * left the shop. Tenders are then admitted only if their sale survived that filter, which
 * is why [DrawerTender] carries its `saleId` at all.
 *
 * Nothing here is capped, sorted or short-circuited — the sums are over whatever they are
 * given. A day with 4 receipts and a day with 400 go down the same path.
 */
fun expectedDrawer(
    opening: Double,
    receipts: List<DrawerReceipt>,
    tenders: List<DrawerTender>,
    payouts: List<DrawerPayout>,
    movements: List<DrawerMovement>,
): ExpectedDrawer {
    val live = receipts.distinctBy { it.saleId }.filter { it.status in RECEIPT_STATUSES }
    val liveIds = live.mapTo(HashSet(live.size)) { it.saleId }

    var cashIn = 0.0
    var nonCashIn = 0.0
    val foreign = LinkedHashMap<String, Double>()
    for (t in tenders) {
        if (t.saleId !in liveIds) continue
        if (isDrawerCash(t.method)) {
            cashIn += t.amount
            // Display only. Deliberately NOT added to any base-currency total.
            val code = t.tenderCurrency?.trim()?.uppercase()
            val face = t.tenderAmount
            if (!code.isNullOrEmpty() && face != null && face != 0.0) {
                foreign[code] = (foreign[code] ?: 0.0) + face
            }
        } else {
            nonCashIn += t.amount
        }
    }

    var cashRefunded = 0.0
    var nonCashRefunded = 0.0
    for (p in payouts) {
        if (isDrawerCash(p.method)) cashRefunded += p.amount else nonCashRefunded += p.amount
    }

    return ExpectedDrawer(
        opening = opening,
        receipts = live.size,
        salesValue = live.sumOf { it.total },
        cashIn = cashIn,
        nonCashIn = nonCashIn,
        changeGiven = live.sumOf { it.changeGiven },
        cashRefunded = cashRefunded,
        nonCashRefunded = nonCashRefunded,
        otherMovements = movements.sumOf { it.amount },
        foreignTenders = foreign.filterValues { kotlin.math.abs(it) > DRAWER_CENT },
        movements = movements.filter { kotlin.math.abs(it.amount) > DRAWER_CENT },
    )
}

/** Case-insensitive because the wire, the SMS parser and the till have all written this
 *  code at one time or another, and a `"Cash"` that fell out of the drawer sum would
 *  read as a shortage rather than as a bug. */
private fun isDrawerCash(method: String): Boolean = method.trim().equals(DRAWER_CASH, ignoreCase = true)
