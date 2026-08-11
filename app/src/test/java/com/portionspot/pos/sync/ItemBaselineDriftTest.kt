package com.portionspot.pos.sync

import com.portionspot.pos.data.Item
import com.portionspot.pos.data.StockMovement
import com.portionspot.pos.data.stockOnHandFromLedger
import com.portionspot.pos.sync.wire.ItemDto
import com.portionspot.pos.sync.wire.ItemPushDto
import com.portionspot.pos.sync.wire.toItem
import com.portionspot.pos.sync.wire.toPush
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A rename must not un-sell a sale.
 *
 * The stock baseline was re-adopted on EVERY accepted pull, stamped with
 * `client_updated_at` — which is the row's edit clock, not a stock clock. It bumps for a
 * rename or a reprice just the same, so the baseline moved past every movement made
 * before that edit and those movements stopped counting.
 *
 * Live case: `ATF Type IV 1L` sits at `stock_qty` 24 in the cloud with a sale of 3
 * against it, so a till correctly shows 21. Correct the product's spelling on the web and
 * the till re-baselined to 24 as-of-now, the −3 fell before the new baseline, and the
 * till went back to reporting 24. The sale silently un-happened from the stock figure.
 */
class ItemBaselineDriftTest {

    private val countedAt = "2026-08-09T13:39:48.836Z"
    private val countedAtMs = IsoTime.toMillis(countedAt)
    private val soldAtMs = countedAtMs + 60_000L
    private val renamedAt = "2026-08-11T08:17:35.715Z"

    private fun cloudRow(name: String, qty: String, clientStamp: String) = ItemDto(
        id = "i1",
        name = name,
        sku = "FLD-ATF-TYPE-IV",
        price = "8.00",
        stockQty = qty,
        trackStock = true,
        productType = "piece",
        updatedAt = clientStamp,
        clientUpdatedAt = clientStamp,
    )

    private val sale = StockMovement(
        businessId = "biz", itemId = "i1", type = "sale",
        delta = -3.0, balanceAfter = 21.0, createdAt = soldAtMs,
    )

    @Test
    fun theFirstPullAdoptsTheShopsFigure() {
        val local = cloudRow("ATF Type IV 1L", "24", countedAt).toItem("biz", null)
        assertEquals(24.0, local.stockBaseQty, 1e-9)
        assertEquals(countedAtMs, local.stockBaseAt)
        // 24 counted, 3 sold since => 21.
        assertEquals(21.0, stockOnHandFromLedger(local, listOf(sale))!!, 1e-9)
    }

    @Test
    fun renamingTheProductDoesNotMoveTheBaseline() {
        val before = cloudRow("ATF Type IV 1L", "24", countedAt).toItem("biz", null)
        // Same stock figure, new name, new edit clock — the only thing that changed is
        // the spelling.
        val after = cloudRow("ATF Type IV 1 Litre", "24", renamedAt).toItem("biz", before)

        assertEquals("ATF Type IV 1 Litre", after.name)
        // The baseline is still the moment the count was actually taken...
        assertEquals(countedAtMs, after.stockBaseAt)
        // ...so the sale still counts. This returned 24.0 before the fix.
        assertEquals(21.0, stockOnHandFromLedger(after, listOf(sale))!!, 1e-9)
    }

    @Test
    fun arepriceDoesNotMoveTheBaselineEither() {
        val before = cloudRow("ATF Type IV 1L", "24", countedAt).toItem("biz", null)
        val repriced = cloudRow("ATF Type IV 1L", "24", renamedAt)
            .copy(price = "9.50").toItem("biz", before)

        assertEquals(9.50, repriced.price, 1e-9)
        assertEquals(countedAtMs, repriced.stockBaseAt)
        assertEquals(21.0, stockOnHandFromLedger(repriced, listOf(sale))!!, 1e-9)
    }

    @Test
    fun aRealStockTakeDoesMoveTheBaseline() {
        // The case the mechanism exists for: the shop recounts and says 30. That figure
        // supersedes the ledger before it, so the earlier sale must NOT be replayed.
        val before = cloudRow("ATF Type IV 1L", "24", countedAt).toItem("biz", null)
        val recounted = cloudRow("ATF Type IV 1L", "30", renamedAt).toItem("biz", before)

        assertEquals(30.0, recounted.stockBaseQty, 1e-9)
        assertEquals(IsoTime.toMillis(renamedAt), recounted.stockBaseAt)
        assertEquals(30.0, stockOnHandFromLedger(recounted, listOf(sale))!!, 1e-9)
    }

    @Test
    fun anItemWithNoBaselineYetAlwaysAdopts() {
        // Nothing to preserve — this is the first reconciliation against the shop.
        val untouched = Item(id = "i1", businessId = "biz", name = "ATF Type IV 1L", trackStock = true)
        assertNull(stockOnHandFromLedger(untouched, listOf(sale)))
        val adopted = cloudRow("ATF Type IV 1L", "24", countedAt).toItem("biz", untouched)
        assertEquals(24.0, adopted.stockBaseQty, 1e-9)
        assertEquals(countedAtMs, adopted.stockBaseAt)
    }

    @Test
    fun theCataloguePushNeverCarriesAStockFigure() {
        // The invariant the whole stock model rests on: a till states what a product IS,
        // never how many of it are on the shelf. Serialised so an added field cannot
        // reintroduce `stock_qty` without this failing.
        val item = cloudRow("ATF Type IV 1L", "24", countedAt).toItem("biz", null)
        val json = Json.encodeToString(ItemPushDto.serializer(), item.toPush())
        assert(!json.contains("stock_qty")) { "the catalogue push must not carry stock: $json" }
        assert(!json.contains("\"updated_at\"")) { "only the client clock may be sent: $json" }
        assert(json.contains("\"client_updated_at\"")) { json }
        assert(json.contains("\"name\":\"ATF Type IV 1L\"")) { json }
    }
}
