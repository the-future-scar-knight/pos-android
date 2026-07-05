package woyou.aidlservice.jiuiv5;

// Sunmi InnerPrinter result callback. We pass null in practice (sendRAWData
// accepts a null callback), but the interface must exist for the AIDL binding.
interface ICallback {
    void onRunResult(boolean isSuccess);
    void onReturnString(String result);
    void onRaiseException(int code, String msg);
    void onPrintResult(int code, String msg);
}
