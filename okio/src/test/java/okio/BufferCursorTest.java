/*
 * Copyright (C) 2018 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import static okio.TestUtil.bufferWithRandomSegmentLayout;
import static okio.TestUtil.bufferWithSegments;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

@RunWith(Parameterized.class)
public final class BufferCursorTest {
  enum BufferFactory {
    EMPTY {
      @Override Buffer newBuffer() {
        return new Buffer();
      }
    },

    SMALL_BUFFER {
      @Override Buffer newBuffer() {
        return new Buffer().writeUtf8("abcde");
      }
    },

    SMALL_SEGMENTED_BUFFER {
      @Override Buffer newBuffer() throws Exception {
        return bufferWithSegments("abc", "defg", "hijkl");
      }
    },

    LARGE_BUFFER {
      @Override Buffer newBuffer() throws Exception {
        Random dice = new Random(0);
        byte[] largeByteArray = new byte[512 * 1024];
        dice.nextBytes(largeByteArray);

        return new Buffer().write(largeByteArray);
      }
    },

    LARGE_BUFFER_WITH_RANDOM_LAYOUT {
      @Override Buffer newBuffer() throws Exception {
        Random dice = new Random(0);
        byte[] largeByteArray = new byte[512 * 1024];
        dice.nextBytes(largeByteArray);

        return bufferWithRandomSegmentLayout(dice, largeByteArray);
      }
    };

    abstract Buffer newBuffer() throws Exception;
  }

  @Parameters(name = "{0}")
  public static List<Object[]> parameters() throws Exception {
    List<Object[]> result = new ArrayList<>();
    for (BufferFactory bufferFactory : BufferFactory.values()) {
      result.add(new Object[] { bufferFactory });
    }
    return result;
  }

  @Parameter public BufferFactory bufferFactory;

  @Test public void accessSegmentBySegment() throws Exception {
    Buffer buffer = bufferFactory.newBuffer();
    BufferCursor cursor = new BufferCursor();
    cursor.acquireReadOnly(buffer);

    try {
      Buffer actual = new Buffer();
      while (cursor.next() != -1L) {
        actual.write(cursor.data, cursor.pos, cursor.limit - cursor.pos);
      }
      assertEquals(buffer, actual);
    } finally {
      cursor.release();
    }
  }

  @Test public void accessByteByByte() throws Exception {
    Buffer buffer = bufferFactory.newBuffer();
    BufferCursor cursor = new BufferCursor();
    cursor.acquireReadOnly(buffer);
    try {
      byte[] actual = new byte[(int) buffer.size];
      for (int i = 0; i < buffer.size; i++) {
        cursor.seek(i);
        actual[i] = cursor.data[cursor.pos];
      }
      assertEquals(ByteString.of(actual), buffer.snapshot());
    } finally {
      cursor.release();
    }
  }

  @Test public void accessByteByByteReverse() throws Exception {
    Buffer buffer = bufferFactory.newBuffer();
    BufferCursor cursor = new BufferCursor();
    cursor.acquireReadOnly(buffer);
    try {
      byte[] actual = new byte[(int) buffer.size];
      for (int i = (int) (buffer.size - 1); i >= 0; i--) {
        cursor.seek(i);
        actual[i] = cursor.data[cursor.pos];
      }
      assertEquals(ByteString.of(actual), buffer.snapshot());
    } finally {
      cursor.release();
    }
  }

  @Test public void accessByteByByteAlwaysResettingToZero() throws Exception {
    Buffer buffer = bufferFactory.newBuffer();
    BufferCursor cursor = new BufferCursor();
    cursor.acquireReadOnly(buffer);
    try {
      byte[] actual = new byte[(int) buffer.size];
      for (int i = 0; i < buffer.size; i++) {
        cursor.seek(i);
        actual[i] = cursor.data[cursor.pos];
        cursor.seek(0L);
      }
      assertEquals(ByteString.of(actual), buffer.snapshot());
    } finally {
      cursor.release();
    }
  }

  @Test public void segmentBySegmentNavigation() throws Exception {
    Buffer buffer = bufferFactory.newBuffer();
    BufferCursor cursor = new BufferCursor();
    cursor.acquireReadOnly(buffer);
    assertEquals(-1, cursor.offset);
    try {
      long lastOffset = cursor.offset;
      while (cursor.next() != -1L) {
        assertTrue(cursor.offset > lastOffset);
        lastOffset = cursor.offset;
      }
      assertEquals(buffer.size, cursor.offset);
      assertNull(cursor.data);
      assertEquals(-1, cursor.pos);
      assertEquals(-1, cursor.limit);
    } finally {
      cursor.release();
    }
  }

  @Test public void seekWithinSegment() throws Exception {
    assumeTrue(bufferFactory == BufferFactory.SMALL_SEGMENTED_BUFFER);
    Buffer buffer = bufferFactory.newBuffer();
    assertEquals("abcdefghijkl", buffer.clone().readUtf8());

    // Seek to the 'f' in the "defg" segment.
    BufferCursor cursor = new BufferCursor();
    cursor.acquireReadOnly(buffer);
    try {
      assertEquals(2, cursor.seek(5)); // 2 for 2 bytes left in the segment: "fg".
      assertEquals(5, cursor.offset);
      assertEquals(2, cursor.limit - cursor.pos);
      assertEquals('d', (char) cursor.data[cursor.pos - 2]); // Out of bounds!
      assertEquals('e', (char) cursor.data[cursor.pos - 1]); // Out of bounds!
      assertEquals('f', (char) cursor.data[cursor.pos]);
      assertEquals('g', (char) cursor.data[cursor.pos + 1]);
    } finally {
      cursor.release();
    }
  }

  @Test public void acquireAndRelease() throws Exception {
    Buffer buffer = bufferFactory.newBuffer();
    BufferCursor cursor = new BufferCursor();

    // Nothing initialized before acquire.
    assertEquals(-1, cursor.size);
    assertEquals(-1, cursor.offset);
    assertNull(cursor.data);
    assertEquals(-1, cursor.pos);
    assertEquals(-1, cursor.limit);

    cursor.acquireReadOnly(buffer);
    cursor.release();

    // Nothing initialized after release.
    assertEquals(-1, cursor.size);
    assertEquals(-1, cursor.offset);
    assertNull(cursor.data);
    assertEquals(-1, cursor.pos);
    assertEquals(-1, cursor.limit);
  }

  @Test public void doubleAcquire() throws Exception {
    Buffer buffer = bufferFactory.newBuffer();
    BufferCursor cursor = new BufferCursor();
    cursor.acquireReadOnly(buffer);
    try {
      cursor.acquireReadOnly(buffer);
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void releaseWithoutAcquire() throws Exception {
    BufferCursor cursor = new BufferCursor();
    try {
      cursor.release();
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void releaseAfterRelease() throws Exception {
    Buffer buffer = bufferFactory.newBuffer();
    BufferCursor cursor = new BufferCursor();
    cursor.acquireReadOnly(buffer);
    cursor.release();
    try {
      cursor.release();
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void enlarge() throws Exception {
    Buffer buffer = bufferFactory.newBuffer();
    long originalSize = buffer.size();

    Buffer expected = new Buffer();
    buffer.copyTo(expected, 0, originalSize);
    expected.writeUtf8("abc");

    BufferCursor cursor = new BufferCursor();
    cursor.acquireReadOnly(buffer);
    try {
      cursor.enlarge(originalSize + 3);
      cursor.seek(originalSize);
      cursor.data[cursor.pos] = 'a';
      cursor.seek(originalSize + 1);
      cursor.data[cursor.pos] = 'b';
      cursor.seek(originalSize + 2);
      cursor.data[cursor.pos] = 'c';
    } finally {
      cursor.release();
    }

    assertEquals(expected, buffer);
  }

  @Test public void enlargeByManySegments() throws Exception {
    Buffer buffer = bufferFactory.newBuffer();
    long originalSize = buffer.size();

    Buffer expected = new Buffer();
    buffer.copyTo(expected, 0, originalSize);
    expected.writeUtf8(TestUtil.repeat('x', 1_000_000));

    BufferCursor cursor = new BufferCursor();
    cursor.acquireReadOnly(buffer);
    try {
      cursor.enlarge(originalSize + 1_000_000);
      cursor.seek(originalSize);
      do {
        Arrays.fill(cursor.data, cursor.pos, cursor.limit, (byte) 'x');
      } while (cursor.next() != -1);
    } finally {
      cursor.release();
    }

    assertEquals(expected, buffer);
  }

  @Test public void enlargeTooSmall() throws Exception {
    Buffer buffer = new Buffer().writeUtf8("abc");
    BufferCursor cursor = new BufferCursor();
    cursor.acquireReadOnly(buffer);
    try {
      cursor.enlarge(2);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void enlargeNotAcquired() throws Exception {
    BufferCursor cursor = new BufferCursor();
    try {
      cursor.enlarge(10);
      fail();
    } catch (IllegalStateException expected) {
    }
  }
}
