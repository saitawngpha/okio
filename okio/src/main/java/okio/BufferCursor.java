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

/**
 * Direct access to the underlying byte arrays in a buffer.
 *
 * Note that cursors are stateful and can be reused.
 *
 * It is an error to mutate a buffer that is currently being operated on with a cursor.
 *
 * When reading it is an error to mutate the fields of this cursor or the values of the data[]
 * array.
 *
 * After enlarging the caller must write every byte of the newly-allocated capacity.
 *
 * Writing elsewhere is not currently supported.
 *
 * Access should be in acquire/release pairs. This guarded access may be used to detect bugs where
 * cursors get out of sync with their underlying buffers.
 *
 * TODO(jwilson): nice, human-readable docs
 * TODO(jwilson): support acquiring for write (with seek() that gets writable segments)
 */
public final class BufferCursor {
  private Buffer buffer;
  public long size = -1L;

  private Segment segment;
  public long offset = -1L;
  public byte[] data;
  public int pos = -1;
  public int limit = -1;

  public void acquireReadOnly(Buffer buffer) {
    if (this.buffer != null) throw new IllegalStateException("already acquired");

    this.buffer = buffer;
    this.size = buffer.size;
  }

  /**
   * Seeks to the next range of bytes, advancing the offset by {@code limit - pos}. Returns the size
   * of the readable range (at least 1), or -1 if we have reached the end of the buffer and there
   * are no more bytes to read.
   */
  public int next() {
    if (offset == size) throw new IllegalStateException();
    if (offset == -1L) return seek(0L);
    return seek(offset + (limit - pos));
  }

  /**
   * Reposition the cursor so that the data at {@code offset} is readable at {@code data[pos]}.
   * Returns the number of bytes readable in {@code data} (at least 1), or -1 if we have reached the
   * end of the buffer and there are no more bytes to read.
   */
  public int seek(long offset) {
    if (offset < 0 || offset > size) {
      throw new ArrayIndexOutOfBoundsException(String.format("offset=%s > size=%s", offset, size));
    }

    if (offset == size) {
      this.segment = null;
      this.offset = offset;
      this.data = null;
      this.pos = -1;
      this.limit = -1;
      return -1;
    }

    // Navigate to the segment that contains `offset` starting from our current segment if possible.
    Segment next;
    long nextOffset;
    if (segment == null || this.offset > offset) {
      next = buffer.head;
      nextOffset = 0L;
    } else {
      next = this.segment;
      nextOffset = this.offset - (pos - next.pos);
    }
    while (offset >= nextOffset + next.limit - next.pos) {
      nextOffset += (next.limit - next.pos);
      next = next.next;
    }

    // Update this cursor to the requested offset within the found segment.
    // TODO(jwilson): get a segment that is OWNED and NOT SHARED if we're writing.
    this.segment = next;
    this.offset = offset;
    this.data = next.data;
    this.pos = next.pos + (int) (offset - nextOffset);
    this.limit = next.limit;
    return limit - pos;
  }

  /**
   * Add additional capacity to the end of this buffer until its new size is {@code newSize}. This
   * must be at least the current size. Newly added capacity may span multiple segments.
   *
   * <p>Warning: it its the caller’s responsibility to write new data to every byte of the
   * newly-allocated capacity. Failure to do so may cause serious security problems as the data
   * in the returned buffers is not zero filled. The buffers may contain dirty pooled segments that
   * hold very sensitive data from other parts of the current process.
   *
   * TODO(jwilson): just zero-fill this to be safe? Or expose a parameter to make that easy?
   */
  public void enlarge(long newSize) {
    if (buffer == null) {
      throw new IllegalStateException("not acquired");
    }
    if (newSize < size) {
      throw new IllegalArgumentException("newSize: " + newSize + " < size: " + size);
    }

    for (long bytesToAdd = newSize - size; bytesToAdd > 0; ) {
      Segment tail = buffer.writableSegment(1);
      long segmentBytesToAdd = Math.min(bytesToAdd, Segment.SIZE - tail.limit);
      tail.limit += segmentBytesToAdd;
      bytesToAdd -= segmentBytesToAdd;
    }

    size = newSize;
    buffer.size = newSize;

    // Buffer.writableSegment() invalidates our local segment state. Recompute it.
    segment = null;
    if (offset != -1L) seek(offset);
  }

  public void release() {
    // TODO(jwilson): use edit counts or other information to track unexpected changes?
    if (buffer == null) throw new IllegalStateException("not acquired");

    buffer = null;
    segment = null;
    offset = -1L;
    size = -1L;
    data = null;
    pos = -1;
    limit = -1;
  }
}
