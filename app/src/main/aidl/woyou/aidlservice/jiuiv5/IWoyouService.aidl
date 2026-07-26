package woyou.aidlservice.jiuiv5;

import woyou.aidlservice.jiuiv5.ICallback;

/**
 * Sunmi built-in (InnerPrinter) service interface — woyou.aidlservice.jiuiv5.
 *
 * AIDL assigns each method a binder transaction code by DECLARATION ORDER
 * (FIRST_CALL_TRANSACTION + index). The service on the device was compiled from
 * Sunmi's canonical interface, so our declaration order must match it byte-for-byte
 * up to every method we actually call, or a call lands on the wrong method.
 *
 * The first three methods (updateFirmware / getFirmwareStatus / getServiceVersion)
 * are easy to forget — omitting them shifts every later method up by three, which
 * is why an earlier version of this file routed sendRAWData (real code 11) to the
 * transaction code of getPrinterModal (code 8) and silently printed nothing.
 * Verified against the codes RawBT uses on the same service (printerInit=4,
 * lineWrap=10, printText=15, printBitmap=18). Do not reorder.
 *
 * We only declare through sendRAWData — the later methods (setAlignment, printText,
 * printBitmap, ...) aren't called, and leaving them out doesn't affect the
 * transaction codes of the methods above them.
 */
interface IWoyouService {
    void updateFirmware();

    int getFirmwareStatus();

    String getServiceVersion();

    void printerInit(in ICallback callback);

    void printerSelfChecking(in ICallback callback);

    String getPrinterSerialNo();

    String getPrinterVersion();

    String getPrinterModal();

    void getPrintedLength(in ICallback callback);

    void lineWrap(int n, in ICallback callback);

    void sendRAWData(in byte[] data, in ICallback callback);
}
