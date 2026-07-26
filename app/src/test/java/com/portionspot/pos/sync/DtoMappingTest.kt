package com.portionspot.pos.sync

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the SHARED web-POS sync contract (see pos-web-sync-contract): products bridge
 * on sku with box+loose stock, PostgREST numeric columns arrive as JSON STRINGS, and
 * a sale's line items live in the JSONB `items` column with the web's exact key shape.
 * Getting any of these wrong would mis-read the live shared database.
 */
class DtoMappingTest {
    private val eps = 1e-9

    @Test
    fun product_mapsToItem_bridgingSkuAndSplittingStock() {
        val dto = ProductDto(
            sku = "OIL-DELO-5L", name = "Delo 5L", category = "Oils",
            boxPrice = "80", boxSize = 4, wholesalePrice = "20", retailPrice = "23",
            costPrice = "15", stockBoxes = 3, stockUnits = 2, lowStockThreshold = 5,
            active = true, updatedAt = "2026-07-06T09:00:00.000Z",
        )
        val item = dto.toItem("biz1", null)
        assertEquals("biz1", item.businessId)
        assertEquals("OIL-DELO-5L", item.sku)
        assertEquals(23.0, item.price, eps)             // retail_price (numeric string)
        assertEquals(20.0, item.wholesalePrice, eps)
        assertEquals(80.0, item.boxPrice, eps)
        assertEquals(4, item.boxSize)
        assertEquals(15.0, item.cost!!, eps)
        assertEquals(14.0, item.stockQty, eps)          // 3 boxes × 4 + 2 loose
        assertEquals(5.0, item.reorderLevel, eps)
        assertTrue(item.trackStock)
    }

    @Test
    fun product_zeroCost_meansUnknown() {
        val dto = ProductDto(sku = "X", name = "X", costPrice = "0", retailPrice = "10")
        assertNull(dto.toItem("biz1", null).cost)       // 0 = unknown ⇒ excluded from profit
    }

    @Test
    fun product_pullPreservesLocalItemId() {
        val existing = com.portionspot.pos.data.Item(
            id = "local-uuid-1", businessId = "biz1", name = "old", sku = "OIL-DELO-5L",
        )
        val dto = ProductDto(sku = "OIL-DELO-5L", name = "new", retailPrice = "9")
        val merged = dto.toItem("biz1", existing)
        assertEquals("local-uuid-1", merged.id)         // keeps the local id (bridge = sku)
        assertEquals("new", merged.name)
        assertEquals(9.0, merged.price, eps)
    }

    @Test
    fun product_imageUrlAndShowImage_roundTrip() {
        // Pull: cloud image_url + show_image land on the Item.
        val dto = ProductDto(
            sku = "OIL-DELO-5L", name = "Delo 5L", retailPrice = "23",
            imageUrl = "https://x.supabase.co/storage/v1/object/public/product-images/biz1/i1.jpg",
            showImage = false, updatedAt = "2026-07-06T09:00:00.000Z",
        )
        val item = dto.toItem("biz1", null)
        assertEquals(dto.imageUrl, item.imageUrl)
        assertTrue(!item.showImage)

        // Push: a REMOTE url is sent; a still-pending local path is NOT (sent as null).
        val remote = item.copy(imagePending = false).toProductPush()
        assertEquals(dto.imageUrl, remote.imageUrl)
        assertTrue(!remote.showImage)
        val pendingLocal = item.copy(
            imageLocalPath = "/data/.../local.jpg", imagePending = true,
        ).toProductPush()
        assertNull(pendingLocal.imageUrl)              // never push a device-local path
    }

    @Test
    fun product_pullKeepsLocalCache_whenImageUrlUnchanged() {
        val url = "https://x/obj/public/product-images/biz1/i1.jpg"
        val existing = com.portionspot.pos.data.Item(
            id = "local-1", businessId = "biz1", name = "Delo", sku = "OIL-DELO-5L",
            imageUrl = url, imageLocalPath = "/data/cache/i1.jpg",
        )
        // Same remote url ⇒ keep the on-device cached copy.
        val same = ProductDto(sku = "OIL-DELO-5L", name = "Delo", retailPrice = "9", imageUrl = url)
            .toItem("biz1", existing)
        assertEquals("/data/cache/i1.jpg", same.imageLocalPath)
        // Changed remote url ⇒ drop the stale local copy (display falls back to remote).
        val changed = ProductDto(sku = "OIL-DELO-5L", name = "Delo", retailPrice = "9", imageUrl = "$url?v=2")
            .toItem("biz1", existing)
        assertNull(changed.imageLocalPath)
    }

    @Test
    fun sale_parsesJsonbItemsAndStringTotals() {
        val json = """
          {"id":"PSM-1","ref":"PSM-1","type":"sale","status":"completed",
           "grand_total":"160","total_discount":"0","vat_amount":"0",
           "pay_method":"cash","cashier":"Admin","cashier_id":"admin",
           "items":[{"qty":2,"sku":"OIL-DELO-5L","mode":"box","name":"Delo 5L",
                     "unitPrice":80,"lineDiscount":0,"unitsPerLine":4,"boxSize":4}],
           "payments":[{"amount":160,"method":"cash"}],
           "created_at":"2026-07-06T09:00:00.000Z","updated_at":"2026-07-06T09:00:00.000Z"}
        """.trimIndent()
        val dto = syncJson.decodeFromString<SaleDto>(json)

        val sale = dto.toSaleEntity("biz1")
        assertEquals("PSM-1", sale.id)                  // id = ref
        assertEquals("PSM-1", sale.receiptNo)
        assertEquals(160.0, sale.total, eps)            // numeric-as-string parsed
        assertEquals("cash", sale.paymentMethod)
        assertEquals("admin", sale.createdBy)
        assertEquals("Admin", sale.createdByName)

        val lines = dto.toSaleLines("biz1") { sku -> if (sku == "OIL-DELO-5L") "item-1" else null }
        assertEquals(1, lines.size)
        assertEquals("item-1", lines[0].itemId)         // resolved from sku
        assertEquals(2.0, lines[0].qty, eps)
        assertEquals(80.0, lines[0].unitPrice, eps)
        assertEquals("box", lines[0].mode)
        assertEquals(4, lines[0].unitsPerLine)
        assertEquals(160.0, lines[0].lineTotal, eps)    // unitPrice*qty − lineDiscount
    }

    // ── Stage 2 push shapes (must match the web sales row exactly) ───────────

    @Test
    fun salePush_usesRefAsIdAndCarriesSkuInJsonb() {
        val sale = com.portionspot.pos.data.SaleEntity(
            id = "uuid-1", businessId = "biz1", receiptNo = "PSM-260707-1234",
            status = "completed", subtotal = 160.0, total = 160.0, taxTotal = 0.0,
            paymentMethod = "cash", amountPaid = 160.0, createdBy = "admin",
            createdByName = "Admin", soldAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
        )
        val line = com.portionspot.pos.data.SaleLine(
            saleId = "uuid-1", businessId = "biz1", itemId = "item-1", name = "Delo 5L",
            qty = 2.0, unitPrice = 80.0, mode = "box", unitsPerLine = 4, lineTotal = 160.0,
        )
        val pay = com.portionspot.pos.data.SalePayment(
            saleId = "uuid-1", businessId = "biz1", method = "cash", amount = 160.0,
        )
        val dto = buildSalePush(sale, listOf(line), listOf(pay)) { id ->
            if (id == "item-1") "OIL-DELO-5L" else null
        }
        assertEquals("PSM-260707-1234", dto.id)         // id = ref (web convention)
        assertEquals("PSM-260707-1234", dto.ref)
        assertEquals("sale", dto.type)
        assertEquals(160.0, dto.grandTotal, eps)
        assertEquals(1, dto.items.size)
        assertEquals("OIL-DELO-5L", dto.items[0].sku)   // sku resolved onto the line
        assertEquals("box", dto.items[0].mode)
        assertEquals(4, dto.items[0].boxSize)
        assertEquals(1, dto.payments.size)
        assertEquals("cash", dto.payments[0].method)
    }

    // ── pulled receipt EDITS (the fix for "the admin phone kept the old total") ──
    // A cloud sale that already exists locally must be folded ONTO the local row, not
    // skipped and not duplicated. These pin the mapping half of that; the engine half
    // (match, last-write-wins, transactional line replacement) lives in pullSales.

    private fun editedDto(
        ref: String = "8FD-0031",
        grandTotal: String = "80",
        amountPaid: String = "80",
        items: List<SaleItemJson> = listOf(
            SaleItemJson(qty = 1.0, sku = "OIL-DELO-5L", name = "Delo 5L", unitPrice = 80.0)
        ),
        updatedAt: String = "2026-07-25T11:30:02.000Z",
    ) = SaleDto(
        id = ref, ref = ref, type = "sale", status = "completed",
        items = items, subtotal = grandTotal, grandTotal = grandTotal,
        amountPaid = amountPaid, changeGiven = "0", changeOwed = "0", payMethod = "cash",
        createdAt = "2026-07-25T11:00:00.000Z", updatedAt = updatedAt,
    )

    private fun localSale(
        id: String = "local-uuid-1",
        total: Double = 100.0,
        updatedAt: Long = 1L,
    ) = com.portionspot.pos.data.SaleEntity(
        id = id, businessId = "biz1", receiptNo = "8FD-0031", status = "completed",
        subtotal = total, total = total, amountPaid = total, paymentMethod = "cash",
        soldAt = 1L, updatedAt = updatedAt, synced = true,
    )

    @Test
    fun sale_mergeKeepsLocalPrimaryKeyAndTakesCloudMoney() {
        val merged = editedDto().mergeIntoSale(localSale())
        assertEquals("local-uuid-1", merged.id)          // PK never moves: lines/refunds/audit point at it
        assertEquals("8FD-0031", merged.receiptNo)
        assertEquals("biz1", merged.businessId)
        assertEquals(80.0, merged.total, eps)            // the edited grand_total lands
        assertEquals(80.0, merged.subtotal, eps)
        assertEquals(80.0, merged.amountPaid, eps)
        assertEquals("paid", merged.paymentStatus)       // total + change given - paid = 0
        assertEquals(1L, merged.soldAt)                  // an edit never moves the sale date
        assertTrue(merged.synced)                        // came DOWN — must not bounce back up
    }

    @Test
    fun sale_mergeDerivesUnpaidFromSettlement() {
        // Android's push does not populate amount_owing, so "still owing" comes from the
        // same arithmetic the till uses: total + change handed back - tendered.
        val merged = editedDto(grandTotal = "100", amountPaid = "60").mergeIntoSale(localSale())
        assertEquals("unpaid", merged.paymentStatus)
        assertEquals(60.0, merged.amountPaid, eps)
    }

    @Test
    fun sale_mergeNeverBlanksMoneyTheCloudRowOmits() {
        val bare = SaleDto(id = "8FD-0031", ref = "8FD-0031", updatedAt = "2026-07-25T11:30:02.000Z")
        val merged = bare.mergeIntoSale(localSale(total = 100.0))
        assertEquals(100.0, merged.total, eps)           // absent grand_total ⇒ keep ours
        assertEquals(100.0, merged.amountPaid, eps)
        assertEquals("paid", merged.paymentStatus)       // no settlement info ⇒ keep the flag
    }

    @Test
    fun sale_signatureChangesWithMoneyAndGoods() {
        val local = localSale()
        val lines = listOf(
            com.portionspot.pos.data.SaleLine(
                saleId = local.id, businessId = "biz1", name = "Delo 5L",
                qty = 2.0, unitPrice = 50.0, lineTotal = 100.0,
            )
        )
        val dto = editedDto()
        val merged = dto.mergeIntoSale(local)
        val newLines = dto.toSaleLines("biz1", local.id) { "item-1" }
        // 2 x 50 = 100 became 1 x 80 = 80: a real edit, so the badge is earned.
        assertTrue(saleSignature(merged, newLines) != saleSignature(local, lines))
        // The same receipt re-pulled with only a fresher stamp is NOT an edit.
        val same = dto.mergeIntoSale(merged)
        assertEquals(saleSignature(merged, newLines), saleSignature(same, newLines))
    }

    @Test
    fun sale_pulledLinesHangOffTheLocalSaleId() {
        val lines = editedDto().toSaleLines("biz1", "local-uuid-1") { "item-1" }
        assertEquals(1, lines.size)
        assertEquals("local-uuid-1", lines[0].saleId)    // not the cloud ref
        assertEquals("item-1", lines[0].itemId)
        assertEquals(80.0, lines[0].lineTotal, eps)
    }

    @Test
    fun credit_pullResolvesCustomerAndParsesAmount() {
        // cloud credit.customer_id is the customers BIGINT (as text); the engine
        // resolves it to the Android local id before calling this mapping.
        val dto = CreditDto(
            id = 1, localId = "CTX-1", customerId = "1", customerName = "Pachedu",
            type = "change_owed", amount = "20",
            createdAt = "2026-07-06T09:00:00.000Z", updatedAt = "2026-07-06T09:00:00.000Z",
        )
        val c = dto.toCreditTxn("biz1", "CUST-abc", null)
        assertEquals("CTX-1", c.id)
        assertEquals("CUST-abc", c.customerId)     // resolved local id, not the bigint "1"
        assertEquals("change_owed", c.type)
        assertEquals(20.0, c.amount, eps)
    }

    @Test
    fun mobileMoney_pullAndPushRoundTrip() {
        val dto = MobileMoneyDto(
            localId = "mm-1", provider = "ecocash", txnCode = "CI260706.T1",
            amount = "358", currency = "USD", status = "unmatched",
            receivedAt = "2026-07-06T09:00:00.000Z", updatedAt = "2026-07-06T09:00:00.000Z",
        )
        val r = dto.toReceipt("biz1", null)
        assertEquals("mm-1", r.id)
        assertEquals("CI260706.T1", r.txnCode)
        assertEquals(358.0, r.amount, eps)
        val push = r.toPush()
        assertEquals("mm-1", push.localId)
        assertEquals("CI260706.T1", push.txnCode)  // upsert key stays stable
        assertEquals(358.0, push.amount, eps)
    }

    @Test
    fun customerPush_carriesTradeFlag() {
        val cust = com.portionspot.pos.data.Customer(
            id = "CUST-abc", businessId = "biz1", name = "Spartan Motors", wholesale = true, updatedAt = 1L,
        )
        val push = cust.toCustomerPush()
        assertEquals("CUST-abc", push.localId)
        assertTrue(push.isTradeAccount)            // is_trade_account = wholesale
        // CustomerPushDto has no `balance` field by design → it can never overwrite the cloud balance.
    }

    @Test
    fun refundPush_isNegativeReturnRow() {
        val refund = com.portionspot.pos.data.Refund(
            id = "ref-1", businessId = "biz1", saleId = "uuid-1", saleReceiptNo = "PSM-260707-1234",
            refundTotal = 80.0, createdBy = "admin", createdByName = "Admin",
            createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
        )
        val rl = com.portionspot.pos.data.RefundLine(
            refundId = "ref-1", businessId = "biz1", itemId = "item-1", name = "Delo 5L",
            qty = 1.0, unitPrice = 80.0, mode = "box", unitsPerLine = 4,
        )
        val dto = buildRefundPush(refund, listOf(rl)) { id -> if (id == "item-1") "OIL-DELO-5L" else null }
        assertEquals("return", dto.type)                // web refunds are type='return'
        assertEquals(-80.0, dto.grandTotal, eps)        // NEGATIVE = cash going back out
        assertEquals(-80.0, dto.subtotal, eps)
        assertTrue(dto.ref.startsWith("RTN-"))
        assertEquals("OIL-DELO-5L", dto.items[0].sku)
    }
}
