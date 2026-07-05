package woyou.aidlservice.jiuiv5;

import woyou.aidlservice.jiuiv5.ICallback;

/**
 * Sunmi built-in (InnerPrinter) service interface.
 *
 * Only the canonical method PREFIX through sendRAWData is declared. AIDL assigns
 * each method a binder transaction code by declaration order, so as long as the
 * methods BEFORE sendRAWData match Sunmi's published interface (which has been
 * stable across every InnerPrinter version), sendRAWData resolves to the correct
 * transaction code. We send our existing ESC/POS byte stream via sendRAWData and
 * pass a null callback, so the later methods of Sunmi's interface aren't needed.
 */
interface IWoyouService {
    void printerInit(in ICallback callback);

    void printerSelfChecking(in ICallback callback);

    String getPrinterSerialNo();

    String getPrinterVersion();

    String getPrinterModal();

    void getPrintedLength(in ICallback callback);

    void lineWrap(int n, in ICallback callback);

    void sendRAWData(in byte[] data, in ICallback callback);
}
