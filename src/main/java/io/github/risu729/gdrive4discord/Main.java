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
import io.github.risu729.gdrive4discord.util.Envs;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.jetbrains.annotations.NotNull;

public class Main {

  static final Drive DRIVE_SERVICE;
  private static final String APPLICATION_NAME = "gdrive4discord";
  private static final Path TOKENS_DIRECTORY_PATH = Path.of(".tokens");
  private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

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
        GoogleClientSecrets.load(JSON_FACTORY, new StringReader(Envs.getEnv("GOOGLE_CREDENTIALS")));

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
                Envs.getEnv("DISCORD_TOKEN"),
                GatewayIntent.GUILD_MESSAGES,
                GatewayIntent.MESSAGE_CONTENT)
            .addEventListeners(new Listener())
            .setActivity(Activity.watching("Google Drive"))
            .build();

    jda.awaitReady();
  }
}
