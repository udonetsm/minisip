package ru.minisip.screen;

import android.content.Context;

/**
 * Экран во время разговора: датчик приближения гасит и зажигает его,
 * кнопка питания работает как обычно и просто дублирует управление.
 * Ни от каких наших пакетов не зависит.
 */
public interface Screen {

    static Screen create(Context ctx) {
        return new ScreenImpl(ctx);
    }

    /**
     * Начало дозвона: включить датчик приближения и удерживать CPU.
     */
    void start();

    /**
     * Конец вызова: отпустить датчик и все блокировки.
     */
    void stop();
}
