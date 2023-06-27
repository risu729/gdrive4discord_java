package io.github.risu729.gdrive4discord;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.Streams;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.experimental.FieldDefaults;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.GenericMessageEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import okhttp3.HttpUrl;
import org.jetbrains.annotations.NotNull;
import org.nibor.autolink.LinkExtractor;
import org.nibor.autolink.LinkType;

@FieldDefaults(level = lombok.AccessLevel.PRIVATE, makeFinal = true)
class Listener extends ListenerAdapter {

  private static final Pattern GOOGLE_FILE_ID_PATTERN = Pattern.compile("[-\\w]+");

  LinkExtractor linkExtractor = LinkExtractor.builder().linkTypes(Set.of(LinkType.URL)).build();

  @Override
  public void onMessageReceived(@NotNull MessageReceivedEvent event) {
    if (isSelfMessage(event)) {
      return;
    }

    replaceEmbeds(event.getChannel(), event.getMessage());
  }

  private boolean isSelfMessage(@NotNull GenericMessageEvent event) {
    User author;
    try {
      // use method if possible
      author = (User) event.getClass().getMethod("getAuthor").invoke(event);
    } catch (NoSuchMethodException
        | IllegalAccessException
        | InvocationTargetException
        | RuntimeException e) {
      author = event.getChannel().retrieveMessageById(event.getMessageId()).complete().getAuthor();
    }
    return author.getId().equals(event.getJDA().getSelfUser().getId());
  }

  private void replaceEmbeds(@NotNull MessageChannel channel, @NotNull Message message) {

    var content = message.getContentRaw();
    var fileIds =
        Streams.stream(linkExtractor.extractLinks(content))
            .map(linkSpan -> content.substring(linkSpan.getBeginIndex(), linkSpan.getEndIndex()))
            .map(this::extractFileId)
            .flatMap(Optional::stream)
            .distinct()
            .toList();

    if (fileIds.isEmpty()) {
      return;
    } else {
      channel.sendTyping().queue();
    }

    var embeds =
        fileIds.stream()
            .map(
                fileId -> {
                  try {
                    return Main.DRIVE_SERVICE
                        .files()
                        .get(fileId)
                        .setFields("name, webViewLink, mimeType, modifiedTime")
                        .execute();
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                })
            .map(
                file ->
                    new EmbedBuilder()
                        .setTitle(file.getName())
                        .setUrl(file.getWebViewLink())
                        .setColor(FileType.fromMimeType(file.getMimeType()).color())
                        .setTimestamp(
                            DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(
                                file.getModifiedTime().toStringRfc3339()))
                        .build())
            .toList();

    channel.sendMessageEmbeds(embeds).queue();

    // re-retrieve the message to update the embeds
    var newMessage = message;
    for (int i = 0; i < 3; i++) {
      if (newMessage.getEmbeds().isEmpty()) {
        // wait for 3 seconds after second retry
        if (i != 0) {
          try {
            Thread.sleep(Duration.ofSeconds(3));
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }
        newMessage = channel.retrieveMessageById(message.getId()).complete();
      } else {
        var newEmbeds = newMessage.getEmbeds();
        var embedFileIds =
            newEmbeds.stream()
                .map(MessageEmbed::getUrl)
                .filter(Objects::nonNull)
                .map(this::extractFileId)
                .flatMap(Optional::stream)
                .toList();

        // if all links are Google Drive links and all file IDs are the same, suppress the embeds
        if (newEmbeds.size() == embedFileIds.size() && fileIds.containsAll(embedFileIds)) {
          message.suppressEmbeds(true).queue();
        }
        break;
      }
    }
  }

  private @NotNull Optional<String> extractFileId(@NotNull String url) {
    var fileId =
        Optional.ofNullable(HttpUrl.parse(url))
            .filter(
                url1 ->
                    url1.host().equals("drive.google.com") || url1.host().equals("docs.google.com"))
            // take the path segment after "d" or "folders"
            .flatMap(
                url1 ->
                    url1.pathSegments().stream()
                        .dropWhile(segment -> !segment.equals("d") && !segment.equals("folders"))
                        .skip(1)
                        .findFirst());
    // just in case, filter out illegal file IDs
    if (fileId.isPresent()) {
      checkState(
          GOOGLE_FILE_ID_PATTERN.matcher(fileId.orElseThrow()).matches(),
          "Illegal Google Drive file ID is mis-extracted: %s (from %s)",
          fileId.orElseThrow(),
          url);
    }
    return fileId;
  }
}
