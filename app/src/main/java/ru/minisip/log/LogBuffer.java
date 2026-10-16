package ru.minisip.log;

/**
 * Кольцевой буфер лога в памяти. При переполнении старые строки вытесняются.
 * Слушатель зовётся из потока, записавшего строку: UI должен сам переключиться на главный.
 */
public interface LogBuffer extends LogSink {

    static LogBuffer create(int maxLines) {
        return new LogBufferImpl(maxLines);
    }

    boolean isEnabled();

    /**
     * Включает или выключает запись. Выключенный буфер строки отбрасывает, накопленное не трогает.
     */
    void setEnabled(boolean on);

    /**
     * Удаляет все накопленные строки.
     */
    void clear();

    /**
     * Весь лог одним текстом, по строке на запись.
     */
    String text();

    /**
     * Зовётся после каждой записи и после clear(); null снимает слушателя.
     */
    void setListener(Runnable onChange);
}