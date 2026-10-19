package ru.minisip.log;

/**
 * Log sink interface for writing log entries.
 */
public interface LogSink {
    void log(String line);
}
