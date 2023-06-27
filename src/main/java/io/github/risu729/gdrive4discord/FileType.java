package io.github.risu729.gdrive4discord;

import com.google.common.collect.MoreCollectors;
import java.awt.Color;
import java.util.Arrays;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Getter
@Accessors(fluent = true)
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
enum FileType {

  // https://developers.google.com/drive/api/guides/mime-types
  DOCS("document", 0x4285f4),
  SHEETS("spreadsheet", 0x0f9d58),
  SLIDES("presentation", 0xf4b400),
  FORMS("form", 0x7627bb),
  OTHER(0xe3e5e8);

  @Nullable String mimeType;
  @NotNull Color color;

  FileType(int color) {
    this(null, color);
  }

  FileType(@Nullable String mimeType, int color) {
    this.mimeType = "application/vnd.google-apps." + mimeType;
    this.color = new Color(color);
  }

  static @NotNull FileType fromMimeType(@NotNull String mimeType) {
    return Arrays.stream(values())
        .filter(type -> mimeType.equals(type.mimeType))
        .collect(MoreCollectors.toOptional())
        .orElse(OTHER);
  }
}
