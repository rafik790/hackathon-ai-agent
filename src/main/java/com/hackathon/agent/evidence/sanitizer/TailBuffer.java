package com.hackathon.agent.evidence.sanitizer;

import java.io.IOException;
import java.io.InputStream;

/**
 * Ring buffer that keeps the last {@code capacity} bytes of a stream, using constant memory.
 */
public final class TailBuffer {

    private final byte[] buffer;
    private long total;
    private long lineBreaks;

    public TailBuffer(int capacity) {
        this.buffer = new byte[capacity];
    }

    public void readFrom(InputStream in) throws IOException {
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            write(chunk, read);
        }
    }

    public void write(byte[] data, int length) {
        for (int i = 0; i < length; i++) {
            if (data[i] == '\n') {
                lineBreaks++;
            }
        }
        int from = Math.max(0, length - buffer.length);
        int position = (int) ((total + from) % buffer.length);
        int count = length - from;
        int first = Math.min(count, buffer.length - position);
        System.arraycopy(data, from, buffer, position, first);
        System.arraycopy(data, from + first, buffer, 0, count - first);
        total += length;
    }

    /** Whether earlier bytes were dropped. */
    public boolean overflowed() {
        return total > buffer.length;
    }

    /** Line breaks seen in the whole stream. */
    public long lineBreaks() {
        return lineBreaks;
    }

    public byte[] toByteArray() {
        int size = (int) Math.min(total, buffer.length);
        byte[] result = new byte[size];
        int start = overflowed() ? (int) (total % buffer.length) : 0;
        int first = Math.min(size, buffer.length - start);
        System.arraycopy(buffer, start, result, 0, first);
        System.arraycopy(buffer, 0, result, first, size - first);
        return result;
    }
}
