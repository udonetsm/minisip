package ru.minisip.log;

/** Куда модули пишут строки лога. Потокобезопасно, вызывать можно из любого потока. */
public interface LogSink {
    void log(String line);
}