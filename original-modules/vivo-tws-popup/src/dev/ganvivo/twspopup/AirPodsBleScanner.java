package dev.ganvivo.twspopup;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Handler;
import android.os.Looper;

import java.util.Collections;

/**
 * 按需 8 秒短扫描 Apple Continuity (0x004C, type 0x07) BLE 广播，解析 AirPods 真实左耳/右耳/充电盒三电量与充电态。
 * 仅在耳机刚连上的 8 秒内短暂扫描并自动停止，平时 0 耗电。
 */
final class AirPodsBleScanner {

    interface Callback {
        void onBattery(int left, int right, int box, int charging, int inCase);
    }

    private static final int APPLE_COMPANY_ID = 0x004C;
    private static final long BURST_MS = 8_000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static ScanCallback sActiveCallback;
    private static Runnable sStopTask;

    private AirPodsBleScanner() {
    }

    static void startBurstScan(final Callback callback) {
        stop();
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                return;
            }
            final BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                return;
            }

            ScanFilter filter = new ScanFilter.Builder()
                    .setManufacturerData(APPLE_COMPANY_ID, new byte[]{0x07}, new byte[]{(byte) 0xFF})
                    .build();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();

            ScanCallback cb = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    if (result == null || result.getRssi() < -72) {
                        return;
                    }
                    ScanRecord rec = result.getScanRecord();
                    if (rec == null) {
                        return;
                    }
                    byte[] data = rec.getManufacturerSpecificData(APPLE_COMPANY_ID);
                    if (data == null || data.length < 16 || data[0] != 0x07 || (data[1] & 0xFF) < 0x13) {
                        return;
                    }
                    parseAndNotify(data, callback);
                }
            };

            sActiveCallback = cb;
            scanner.startScan(Collections.singletonList(filter), settings, cb);
            sStopTask = () -> stop();
            MAIN.postDelayed(sStopTask, BURST_MS);
            Logx.i("AirPods BLE 8s burst scan started");
        } catch (Throwable t) {
            Logx.w("AirPods BLE scan unavailable", t);
        }
    }

    static void stop() {
        if (sStopTask != null) {
            MAIN.removeCallbacks(sStopTask);
            sStopTask = null;
        }
        ScanCallback cb = sActiveCallback;
        sActiveCallback = null;
        if (cb != null) {
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                BluetoothLeScanner scanner = adapter != null ? adapter.getBluetoothLeScanner() : null;
                if (scanner != null) {
                    scanner.stopScan(cb);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void parseAndNotify(byte[] d, Callback cb) {
        // Apple Proximity Pairing (0x07, len=0x19):
        // d[5]: status flags (bit 5 = 0 means left/right nibbles swapped; bits 1,3 = in-ear/in-case)
        // d[6]: battery nibbles (high/low = left/right depending on flip; 0..10 -> *10%, 15 -> unknown)
        // d[7]: low nibble = case battery (0..10 -> *10%, 15 -> unknown); high nibble = charging bits
        int status = d[5] & 0xFF;
        boolean flipped = (status & 0x20) == 0;

        int b6 = d[6] & 0xFF;
        int rawA = (b6 >> 4) & 0x0F;
        int rawB = b6 & 0x0F;
        int leftNib = flipped ? rawB : rawA;
        int rightNib = flipped ? rawA : rawB;

        int b7 = d[7] & 0xFF;
        int boxNib = b7 & 0x0F;
        int chgNib = (b7 >> 4) & 0x0F;

        int left = decodeNibble(leftNib);
        int right = decodeNibble(rightNib);
        int box = decodeNibble(boxNib);
        if (left < 0 && right < 0 && box < 0) {
            return;
        }

        boolean chgA = (chgNib & 0x01) != 0;
        boolean chgB = (chgNib & 0x02) != 0;
        boolean chgCase = (chgNib & 0x04) != 0;
        int charging = 0;
        if (flipped ? chgB : chgA) charging |= PopupParams.LEFT;
        if (flipped ? chgA : chgB) charging |= PopupParams.RIGHT;
        if (chgCase) charging |= PopupParams.CASE;

        int inCase = 0;
        if (left >= 0) inCase |= PopupParams.LEFT;
        if (right >= 0) inCase |= PopupParams.RIGHT;

        stop(); // 拿到一帧有效三电量即刻停扫省电
        Logx.i("AirPods BLE decoded L=" + left + " R=" + right + " box=" + box + " chg=" + charging);
        cb.onBattery(left, right, box, charging, inCase);
    }

    private static int decodeNibble(int n) {
        return (n >= 0 && n <= 10) ? n * 10 : -1;
    }
}
