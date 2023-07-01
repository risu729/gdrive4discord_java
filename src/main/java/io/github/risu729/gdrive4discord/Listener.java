package io.github.risu729.gdrive4discord;

import com.google.common.collect.MoreCollectors;
import com.google.common.collect.Streams;
import io.github.risu729.gdrive4discord.util.ZeroWidthChars;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import lombok.experimental.FieldDefaults;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.*;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import okhttp3.HttpUrl;
import org.jetbrains.annotations.NotNull;
import org.nibor.autolink.LinkExtractor;
import org.nibor.autolink.LinkType;

@FieldDefaults(level = lombok.AccessLevel.PRIVATE, makeFinal = true)
class Listener extends ListenerAdapter {

  private static final Pattern GOOGLE_FILE_ID_PATTERN = Pattern.compile("[-\\w]+");

  private static final int MAX_RETRIES = 3;
  private static final int HISTORY_SIZE = 5;

  LinkExtractor linkExtractor = LinkExtractor.builder().linkTypes(Set.of(LinkType.URL)).build();

  @Override
  public void onMessageReceived(@NotNull MessageReceivedEvent event) {
    if (isSelfMessage(event.getMessage())) {
      return;
    }
    replaceEmbeds(event.getMessage(), true);
  }

  @Override
  public void onMessageUpdate(@NotNull MessageUpdateEvent event) {
    if (isSelfMessage(event.getMessage())) {
      return;
    }
    replaceEmbeds(event.getMessage(), false);
  }

  @Override
  public void onMessageDelete(@NotNull MessageDeleteEvent event) {
    findOldEmbedsMessage(event.getChannel(), event.getMessageId())
        .map(Message::delete)
        .ifPresent(AuditableRestAction::queue);
  }

  @Override
  public void onMessageBulkDelete(@NotNull MessageBulkDeleteEvent event) {
    event.getMessageIds().stream()
        .map(id -> findOldEmbedsMessage(event.getChannel(), id))
        .flatMap(Optional::stream)
        .map(Message::delete)
        .forEach(AuditableRestAction::queue);
  }

  private boolean isSelfMessage(@NotNull Message message) {
    return message.getAuthor().getId().equals(message.getJDA().getSelfUser().getId());
  }

  private void replaceEmbeds(@NotNull Message source, boolean isNewMessage) {
    var channel = source.getChannel();

    // disable since it loops
    // unsuppress embeds if suppressed before
    // if (oldEmbedsMessage != null && source.isSuppressedEmbeds()) {
    //  source.suppressEmbeds(false).queue();
    // }

    var content = source.getContentRaw();
    var fileIds =
        Streams.stream(linkExtractor.extractLinks(content))
            .map(linkSpan -> content.substring(linkSpan.getBeginIndex(), linkSpan.getEndIndex()))
            .map(this::extractFileIdFromUrl)
            .flatMap(Optional::stream)
            .distinct()
            .toList();

    if (fileIds.isEmpty()) {
      return;
    }

    Optional<Message> oldEmbedsMessage =
        isNewMessage ? Optional.empty() : findOldEmbedsMessage(channel, source.getId());

    // send typing indicator to indicate the bot is working
    // only send for new messages
    if (oldEmbedsMessage.isEmpty()) {
      channel.sendTyping().queue();
    }

    var embeds =
        Streams.mapWithIndex(
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
                        }),
                (file, index) -> {
                  var title = file.getName();
                  if (index == 0) {
                    title = ZeroWidthChars.append(title, source.getId());
                  }
                  return new EmbedBuilder()
                      .setTitle(title)
                      .setUrl(file.getWebViewLink())
                      .setColor(FileType.fromMimeType(file.getMimeType()).color())
                      .setTimestamp(
                          DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(
                              file.getModifiedTime().toStringRfc3339()))
                      .build();
                })
            .toList();

    if (oldEmbedsMessage.isEmpty()) {
      channel.sendMessageEmbeds(embeds).queue();
    } else {
      oldEmbedsMessage.orElseThrow().editMessageEmbeds(embeds).queue();
    }

    // re-retrieve the message to update the embeds
    var updatedSource = source;
    for (int i = 0; i < MAX_RETRIES; i++) {
      if (updatedSource.getEmbeds().isEmpty()) {
        // wait for 3 seconds after second retry
        if (i != 0) {
          try {
            Thread.sleep(Duration.ofSeconds(3));
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }
        try {
          updatedSource = channel.retrieveMessageById(source.getId()).complete();
        } catch (ErrorResponseException e) {
          if (e.getErrorCode() == 10008) {
            // Unknown Message
            break;
          }
          throw e;
        }
      } else {
        var newEmbeds = updatedSource.getEmbeds();
        var embedFileIds =
            newEmbeds.stream()
                .map(MessageEmbed::getUrl)
                .filter(Objects::nonNull)
                .map(this::extractFileIdFromUrl)
                .flatMap(Optional::stream)
                .toList();

        // if all links are Google Drive links and all file IDs are the same, suppress the embeds
        if (newEmbeds.size() == embedFileIds.size() && fileIds.containsAll(embedFileIds)) {
          try {
            // use complete to catch the exception
            source.suppressEmbeds(true).complete();
          } catch (ErrorResponseException e) {
            // Unknown Message
            if (e.getErrorCode() != 10008) {
              throw e;
            }
          }
        }
        break;
      }
    }
  }

  private @NotNull Optional<Message> findOldEmbedsMessage(
      @NotNull MessageChannel channel, @NotNull String sourceMessageId) {
    return channel
        .getHistoryAfter(sourceMessageId, HISTORY_SIZE)
        .complete()
        .getRetrievedHistory()
        .stream()
        .filter(
            message -> message.getAuthor().getId().equals(channel.getJDA().getSelfUser().getId()))
        .filter(
            message ->
                message.getEmbeds().stream()
                    .findFirst()
                    .map(MessageEmbed::getTitle)
                    .map(ZeroWidthChars::decodeAppended)
                    .filter(Predicate.isEqual(sourceMessageId))
                    .isPresent())
        .collect(MoreCollectors.toOptional());
  }

  private @NotNull Optional<String> extractFileIdFromUrl(@NotNull String url) {
    return Optional.ofNullable(HttpUrl.parse(url))
        .filter(
            url1 -> url1.host().equals("drive.google.com") || url1.host().equals("docs.google.com"))
        // take the path segment after "d" or "folders"
        .flatMap(
            url1 ->
                url1.pathSegments().stream()
                    .dropWhile(segment -> !segment.equals("d") && !segment.equals("folders"))
                    .skip(1)
                    .filter(GOOGLE_FILE_ID_PATTERN.asMatchPredicate())
                    .findFirst());
  }
}
