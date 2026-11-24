package com.hackathon.agent.evidence.sanitizer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TailBufferTest {

    @Test
    void keepsEverythingThatFits() throws IOException {
        TailBuffer buffer = new TailBuffer(10);
        buffer.readFrom(new ByteArrayInputStream("0123456789".getBytes(StandardCharsets.UTF_8)));

        assertThat(new String(buffer.toByteArray(), StandardCharsets.UTF_8)).isEqualTo("0123456789");
        assertThat(buffer.overflowed()).isFalse();
    }

    @Test
    void keepsTheLastBytes() throws IOException {
        TailBuffer buffer = new TailBuffer(4);
        buffer.readFrom(new ByteArrayInputStream("ab\ncd\nefgh".getBytes(StandardCharsets.UTF_8)));

        assertThat(new String(buffer.toByteArray(), StandardCharsets.UTF_8)).isEqualTo("efgh");
        assertThat(buffer.overflowed()).isTrue();
        assertThat(buffer.lineBreaks()).isEqualTo(2);
    }

    @Test
    void matchesTheTailForArbitraryWriteSizes() {
        Random random = new Random(42);
        for (int round = 0; round < 200; round++) {
            byte[] data = new byte[random.nextInt(400)];
            random.nextBytes(data);
            int capacity = 1 + random.nextInt(100);
            TailBuffer buffer = new TailBuffer(capacity);
            int position = 0;
            while (position < data.length) {
                int length = Math.min(data.length - position, 1 + random.nextInt(150));
                buffer.write(Arrays.copyOfRange(data, position, position + length), length);
                position += length;
            }
            assertThat(buffer.toByteArray())
                    .isEqualTo(Arrays.copyOfRange(data, Math.max(0, data.length - capacity), data.length));
        }
    }
}
