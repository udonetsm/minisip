package ru.minisip.update;

import android.content.Context;

import java.io.File;

import ru.minisip.log.LogSink;

/**
 * Проверка и установка APK-обновлений из GitHub Releases. Один экземпляр на приложение.
 * Проверка и скачивание идут в фоновом потоке, колбэки — в главном.
 */
public interface Update {

    static Update create(Context ctx) {
        return new UpdateImpl(ctx);
    }

    /**
     * "stable" или "prerelease". Сохраняется в prefs.
     */
    void setChannel(String channel);

    String channel();

    /**
     * Постоянный слушатель (полоса обновления в MainActivity). null снимает.
     */
    void setListener(Listener l);

    /**
     * GET к выбранному каналу. При hasUpdate=true сам зовёт download().
     */
    void check(Listener l);

    /**
     * Скачать APK последнего релиза в кэш с прогрессом.
     */
    void download(Listener l);

    /**
     * Запустить системный установщик APK, скачанного последним download().
     */
    void install();

    /**
     * Куда писать журнал событий (null — никуда). Строки без секретов.
     */
    void setLogSink(LogSink s);

    interface Listener {
        /**
         * Проверка завершена. hasUpdate=true → сейчас же начнётся download().
         */
        default void onChecked(Result r) {
        }

        /**
         * Прогресс скачивания, 0..100.
         */
        default void onProgress(int percent) {
        }

        /**
         * APK скачан; установка — по команде install().
         */
        default void onDownloaded(File apk) {
        }

        /**
         * Любая ошибка проверки или скачивания: reason — готовый текст для UI.
         */
        default void onError(String reason) {
        }
    }

    /**
     * Итог проверки канала. tag — только для отображения.
     */
    final class Result {
        public boolean hasUpdate;
        public long releaseMs;
        public String tag;
        public String changelog;
        /**
         * Стабильный канал пуст (404): предложить пользователю пре-релизы.
         */
        public boolean noStable;
    }
}
