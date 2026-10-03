package ru.minisip.screen;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.PowerManager;

/**
 * Как это устроено:
 *  - PROXIMITY_SCREEN_OFF_WAKE_LOCK: система сама гасит экран, пока датчик закрыт, и
 *    зажигает, когда открыт (механизм штатной звонилки);
 *  - слушатель датчика: на событии «открыт» принудительно будит экран, если он погашен
 *    (например, кнопкой питания) — так датчик всегда главный, а кнопка питания лишь
 *    дополнительно переключает экран вручную;
 *  - кнопку питания мы не перехватываем: она работает штатно.
 */
final class ScreenImpl implements Screen, SensorEventListener {

    private final PowerManager pm;
    private final SensorManager sm;
    private final Sensor prox;
    private final PowerManager.WakeLock proxLock;
    private final PowerManager.WakeLock cpuLock;
    private final PowerManager.WakeLock wakeLock;
    private boolean active;

    @SuppressWarnings("deprecation")
    ScreenImpl(Context ctx) {
        pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);

        proxLock = pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
                ? pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "minisip:prox")
                : null;
        if (proxLock != null) proxLock.setReferenceCounted(false);

        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "minisip:cpu");
        cpuLock.setReferenceCounted(false);

        // единственный способ включить экран из сервиса/без Activity
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP, "minisip:wake");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public synchronized void start() {
        if (active) return;
        active = true;
        cpuLock.acquire(4 * 60 * 60 * 1000L);          // страховка: не дольше 4 часов
        if (proxLock != null) proxLock.acquire(4 * 60 * 60 * 1000L);
        if (prox != null) sm.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    public synchronized void stop() {
        if (!active) return;
        active = false;
        if (prox != null) sm.unregisterListener(this);
        if (proxLock != null && proxLock.isHeld()) {
            // экран не зажигать, пока телефон ещё у уха
            proxLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
        }
        if (wakeLock.isHeld()) wakeLock.release();
        if (cpuLock.isHeld()) cpuLock.release();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        boolean near = e.values[0] < Math.min(e.sensor.getMaximumRange(), 5f);
        if (near) return;                                // гасит система через proxLock
        if (!pm.isInteractive()) wakeLock.acquire(3000); // «открыт» -> зажечь погашенный экран
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
