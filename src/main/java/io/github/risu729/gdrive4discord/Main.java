package io.github.risu729.gdrive4discord;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import io.github.cdimascio.dotenv.Dotenv;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import okhttp3.HttpUrl;
import org.jetbrains.annotations.NotNull;

public class Main {

  private static final String APPLICATION_NAME = "gdrive4discord";
  private static final Path TOKENS_DIRECTORY_PATH = Path.of(".tokens");

  private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
  private static final Drive DRIVE_SERVICE;

  static {
    try {
      var httpTransport = GoogleNetHttpTransport.newTrustedTransport();
      DRIVE_SERVICE =
          new Drive.Builder(httpTransport, JSON_FACTORY, getGoogleCredential(httpTransport))
              .setApplicationName(APPLICATION_NAME)
              .build();
    } catch (GeneralSecurityException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static @NotNull Credential getGoogleCredential(@NotNull HttpTransport httpTransport)
      throws IOException {
    var clientSecrets =
        GoogleClientSecrets.load(
            JSON_FACTORY, new StringReader(Dotenv.load().get("GOOGLE_CREDENTIALS")));

    var flow =
        new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY, clientSecrets, List.of(DriveScopes.DRIVE_READONLY))
            .setDataStoreFactory(new FileDataStoreFactory(TOKENS_DIRECTORY_PATH.toFile()))
            .setAccessType("offline")
            .build();
    var receiver = new LocalServerReceiver.Builder().setPort(8888).build();

    return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
  }

  public static void main(String... args) throws InterruptedException {

    var jda =
        JDABuilder.createLight(
                Dotenv.load().get("DISCORD_TOKEN"),
                GatewayIntent.GUILD_MESSAGES,
                GatewayIntent.MESSAGE_CONTENT)
            .addEventListeners(
                new ListenerAdapter() {
                  @Override
                  public void onMessageReceived(@NotNull MessageReceivedEvent event) {
                    if (event.getAuthor().getId().equals(event.getJDA().getSelfUser().getId())) {
                      return;
                    }

                    // generating embeds by Discord might take time so retrieve the message again
                    try {
                      Thread.sleep(Duration.ofSeconds(2));
                    } catch (InterruptedException e) {
                      throw new RuntimeException(e);
                    }

                    var channel = event.getChannel();
                    var message = channel.retrieveMessageById(event.getMessageId()).complete();

                    var originalEmbeds = message.getEmbeds();

                    var fileIds =
                        originalEmbeds.stream()
                            .map(MessageEmbed::getUrl)
                            .filter(Objects::nonNull)
                            .map(HttpUrl::parse)
                            .filter(Objects::nonNull)
                            .filter(
                                url ->
                                    url.host().equals("drive.google.com")
                                        || url.host().equals("docs.google.com"))
                            .flatMap(
                                // take the path segment after "d" or "folders"
                                url ->
                                    url.pathSegments().stream()
                                        .dropWhile(
                                            segment ->
                                                !segment.equals("d") && !segment.equals("folders"))
                                        .skip(1)
                                        .findFirst()
                                        .stream())
                            .filter(Pattern.compile("[-\\w]+").asMatchPredicate())
                            .toList();

                    if (fileIds.isEmpty()) {
                      return;
                    } else {
                      channel.sendTyping().queue();
                    }

                    // if all embeds are Google Drive links, suppress the original embeds
                    if (originalEmbeds.size() == fileIds.size()) {
                      message.suppressEmbeds(true).queue();
                    }

                    var embeds =
                        fileIds.stream()
                            .map(
                                fileId -> {
                                  try {
                                    return DRIVE_SERVICE
                                        .files()
                                        .get(fileId)
                                        .setFields("name, webViewLink, mimeType, modifiedTime")
                                        .execute();
                                  } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                  }
                                })
                            .map(
                                file -> {
                                  var color =
                                      switch (file.getMimeType()) {
                                        case "application/vnd.google-apps.document" -> 0x4285f4;
                                        case "application/vnd.google-apps.spreadsheet" -> 0x0f9d58;
                                        case "application/vnd.google-apps.presentation" -> 0xf4b400;
                                        case "application/vnd.google-apps.form" -> 0x7627bb;
                                        default -> 0xf7f9fc;
                                      };
                                  return new EmbedBuilder()
                                      .setTitle(file.getName())
                                      .setUrl(file.getWebViewLink())
                                      .setColor(color)
                                      .setTimestamp(
                                          DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(
                                              file.getModifiedTime().toStringRfc3339()))
                                      .build();
                                })
                            .toList();

                    channel.sendMessageEmbeds(embeds).queue();
                  }
                })
            .setActivity(Activity.watching("Google Drive"))
            .build();

    jda.awaitReady();
  }
}
