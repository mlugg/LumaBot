package gq.luma.bot.services.apis;

import com.github.philippheuer.credentialmanager.CredentialManager;
import com.github.philippheuer.credentialmanager.CredentialManagerBuilder;
import com.github.philippheuer.credentialmanager.domain.OAuth2Credential;
import com.github.scribejava.core.model.OAuth2AccessToken;
import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.TwitchClientBuilder;
import com.github.twitch4j.auth.providers.TwitchIdentityProvider;
import com.github.twitch4j.helix.domain.Stream;
import com.github.twitch4j.helix.domain.StreamList;
import gq.luma.bot.Luma;
import gq.luma.bot.reference.KeyReference;
import gq.luma.bot.services.Bot;
import gq.luma.bot.services.Service;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.MessageSet;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.util.logging.ExceptionLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class TwitchApi implements Service {
    private static final Logger logger = LoggerFactory.getLogger(TwitchApi.class);

    public CredentialManager credentialManager;
    public TwitchClient client;
    public String appAccessToken;

    private static final long STREAMS_CHANNEL = 595628684925206542L;
    private static final long START_MESSAGE = 596447974008160257L;

    private static final String PORTAL2_GAME_ID = "19731";

    private Map<String, Long> currentAnnouncements;

    @Override
    public void startService() throws Exception {
        credentialManager = CredentialManagerBuilder.builder()
                .build();
        credentialManager.registerIdentityProvider(new TwitchIdentityProvider(KeyReference.twitchClientId, KeyReference.twitchClientSecret, "https://verify.luma.gq/"));

        OAuth2Credential token = credentialManager.getOAuth2IdentityProviderByName("twitch")
                .orElseThrow(AssertionError::new).getAppAccessToken();

        client = TwitchClientBuilder.builder()
                .withEnableHelix(true)
                .withCredentialManager(credentialManager)
                .withDefaultAuthToken(token)
                .withClientId(KeyReference.twitchClientId)
                .withClientSecret(KeyReference.twitchClientSecret)
                .build();

        appAccessToken = token.getAccessToken();

        currentAnnouncements = new HashMap<>();

        // Delete all messages after the start message
        Bot.api.getTextChannelById(STREAMS_CHANNEL).ifPresent(channel -> channel.getMessagesAfter(Integer.MAX_VALUE, START_MESSAGE).thenAccept(MessageSet::deleteAll).exceptionally(ExceptionLogger.get()));

        Luma.schedulerService.scheduleWithFixedDelay(this::updateStreams, 0, 1, TimeUnit.MINUTES);
    }

    public void updateStreams() {
        logger.info("Updating twitch streams...");

        List<String> twitchIds = Luma.database.getVerifiedConnectionsByType("twitch");
        List<Stream> streams = getStreams(twitchIds);


        TextChannel streamsChannel = Bot.api.getTextChannelById(STREAMS_CHANNEL).orElseThrow(AssertionError::new);

        // Check that announced streams are still live
        HashMap<String, Long> announcementsToRemove = new HashMap<>();

        currentAnnouncements.forEach((twitchId, message) -> {
            if(twitchIds.contains(twitchId)) {
                // Remove announced ids
                twitchIds.remove(twitchId);

                if(streams.stream().noneMatch(s -> s.getUserId().equals(twitchId))) {
                    announcementsToRemove.put(twitchId, message);
                }
            } else {
                announcementsToRemove.put(twitchId, message);
            }
        });

        announcementsToRemove.forEach((twitchId, message) -> {
            Bot.api.getMessageById(message, streamsChannel).thenAccept(Message::delete);
        });

        // Announce new streams
        streams.forEach(stream -> {
            if(!currentAnnouncements.containsKey(stream.getUserId())) {
                AtomicReference<Color> embedColor = new AtomicReference<>(new Color(100, 65, 164));
                AtomicReference<String> userTag = new AtomicReference<>();
                AtomicReference<String> profileUrl = new AtomicReference<>();

                Luma.database.getVerifiedConnectionsById(stream.getUserId(), "twitch")
                        .stream()
                        .findFirst().ifPresent(discordUser -> {
                            discordUser.getRoleColor(Bot.api.getServerById(146404426746167296L).orElseThrow(AssertionError::new))
                                    .ifPresent(embedColor::set);

                            userTag.set(discordUser.getDiscriminatedName());
                            profileUrl.set(discordUser.getAvatar().getUrl().toString());
                        });

                client.getHelix().getUsers(appAccessToken, List.of(stream.getUserId()), null)
                        .execute()
                        .getUsers().stream()
                        .findFirst().ifPresent(twitchUser -> {
                    Message message = streamsChannel.sendMessage(new EmbedBuilder()
                            .setTimestamp(stream.getStartedAt().toInstant())
                            .setTitle(stream.getUserName() + " is live!")
                            .addField(stream.getTitle(), "https://twitch.tv/" + stream.getUserName())
                            .setColor(embedColor.get())
                            .setAuthor(userTag.get(), "https://twitch.tv/" + stream.getUserName(), profileUrl.get())
                            .setImage(stream.getThumbnailUrl() == null ? twitchUser.getProfileImageUrl() : stream.getThumbnailUrl())).join();

                    currentAnnouncements.put(stream.getUserId(), message.getId());
                });
            }
        });
    }

    public List<Stream> getStreams(List<String> userIds) {
        ArrayList<Stream> streams = new ArrayList<>();

        for(int i = 0; i < userIds.size(); i += 100) {
            StreamList userList = client.getHelix().getStreams(appAccessToken,
                    "",
                    "",
                    100,
                    null,
                    List.of(PORTAL2_GAME_ID),
                    null,
                    userIds.subList(i, Math.min(i + 100, userIds.size())),
                    null).execute();

            streams.addAll(userList.getStreams());
        }

        return streams;
    }

    public static void main(String[] args) throws Exception {
        new KeyReference().startService();

        new TwitchApi().startService();
    }
}
