/* Copyright (C) 2026 Lunaris AOSP
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package com.android.dialer.voip;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class StereoWave {
  private StereoWave() {}

  static long write(File remote, File microphone, OutputStream destination, int sampleRate)
      throws IOException {
    long remoteBytes = remote.length();
    long microphoneBytes = microphone.length();
    long frames = Math.max(remoteBytes, microphoneBytes) / 2;
    long dataBytes = frames * 4;
    if (sampleRate <= 0 || sampleRate > 192000 || (remoteBytes & 1) != 0
        || (microphoneBytes & 1) != 0 || frames == 0 || dataBytes > 0xffffffffL - 36) {
      throw new IOException("Invalid PCM size or sample rate");
    }
    ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
    header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int) (dataBytes + 36));
    header.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
    header.putShort((short) 1).putShort((short) 2).putInt(sampleRate).putInt(sampleRate * 4);
    header.putShort((short) 4).putShort((short) 16);
    header.put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int) dataBytes);
    BufferedOutputStream output = new BufferedOutputStream(destination);
    output.write(header.array());
    try (InputStream left = new BufferedInputStream(new FileInputStream(remote));
         InputStream right = new BufferedInputStream(new FileInputStream(microphone))) {
      byte[] interleaved = new byte[4096];
      long position = 0;
      while (position < frames) {
        int count = (int) Math.min(interleaved.length / 4, frames - position);
        for (int i = 0; i < count; i++) {
          readSample(left, interleaved, i * 4, (position + i) * 2 < remoteBytes);
          readSample(right, interleaved, i * 4 + 2, (position + i) * 2 < microphoneBytes);
        }
        output.write(interleaved, 0, count * 4);
        position += count;
      }
    }
    output.flush();
    return frames * 1000 / sampleRate;
  }

  private static void readSample(InputStream input, byte[] buffer, int offset, boolean present)
      throws IOException {
    if (!present) {
      buffer[offset] = 0;
      buffer[offset + 1] = 0;
      return;
    }
    int low = input.read();
    int high = input.read();
    if (low < 0 || high < 0) throw new IOException("PCM track was truncated while saving");
    buffer[offset] = (byte) low;
    buffer[offset + 1] = (byte) high;
  }
}
