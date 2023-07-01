package io.github.risu729.gdrive4discord.util;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

@UtilityClass
public class ZeroWidthChars {

  public @NotNull String encode(@NotNull String str) {
    var bytes = str.getBytes();
    var builder = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      int i = b & 0xff;
      int high = i / 16;
      int low = i % 16;
      // 0x2065 is unassigned, so use 0x200b instead
      builder.appendCodePoint(high == 5 ? 0x200b : high + 0x2060);
      builder.appendCodePoint(low == 5 ? 0x200b : low + 0x2060);
    }
    return builder.toString();
  }

  public @NotNull String decode(@NotNull String encoded) {
    var bytes = new byte[encoded.length() / 2];
    for (var i = 0; i < encoded.length(); i += 2) {
      int high = encoded.codePointAt(i);
      int low = encoded.codePointAt(i + 1);
      if (high == 0x200b) {
        high = 5;
      } else {
        high -= 0x2060;
      }
      if (low == 0x200b) {
        low = 5;
      } else {
        low -= 0x2060;
      }
      bytes[i / 2] = (byte) ((high * 16) + low);
    }
    return new String(bytes);
  }

  public @NotNull String append(@NotNull String visible, @NotNull String hidden) {
    return visible + "\u200f" + encode(hidden);
  }

  public @NotNull String decodeAppended(@NotNull String appended) {
    return decode(appended.substring(appended.lastIndexOf(0x200f) + 1));
  }
}
