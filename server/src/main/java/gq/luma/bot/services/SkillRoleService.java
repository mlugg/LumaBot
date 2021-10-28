package gq.luma.bot.services;

import com.fasterxml.jackson.core.JsonFactory;
import com.walker.jspeedrun.api.JSpeedrun;
import com.walker.jspeedrun.api.leaderboards.Leaderboard;
import com.walker.jspeedrun.api.run.Run;
import gq.luma.bot.Luma;
import gq.luma.bot.services.apis.IVerbApi;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import okhttp3.OkHttpClient;
import org.javacord.api.entity.permission.Role;
import org.javacord.api.entity.user.User;
import org.javacord.api.util.logging.ExceptionLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class SkillRoleService implements Service {
    private static Logger logger = LoggerFactory.getLogger(SkillRoleService.class);

    private static final int FIRST_SP_CHAPTER = 7;
    private static final int FIRST_COOP_CHAPTER = 1;

    private long milliRefreshTime = 1000 * 60 * 5; // 5 minutes

    private ScheduledFuture<?> refreshFuture;
    private Instant latestProccessedScore;

    private JSpeedrun jSpeedrun;

    private static final int FIRST_COOP_MAP_INDEX = 60;
    public static final int[] IVERB_MAP_IDS = {
            62761, // Container Ride
            62758, // Portal Carousel
            47458, // Portal Gun
            47455, // Smooth Jazz
            47452, // Cube Momentum
            47106, // Future Starter
            62763, // Secret Panel
            62759, // Wakeup
            47735, // Incinerator

            62765, // Laser Intro
            47736, // Laser Stairs
            47738, // Dual Lasers
            47742, // Laser Over Goo
            62767, // Catapult Intro
            47744, // Trust Fling
            47465, // Pit Flings
            47746, // Fizzler Intro

            47748, // Ceiling Catapult
            47751, // Ricochet
            47752, // Bridge Intro
            47755, // Bridge the Gap
            47756, // Turret Intro
            47759, // Laser Relays
            47760, // Turret Blocker
            47763, // Laser vs Turret
            47764, // Pull the Rug

            47766, // Column Blocker
            47768, // Laser Chaining
            47770, // Triple Laser
            47773, // Jail Break
            47774, // Escape

            47776, // Turret Factory
            47779, // Turret Sabotage
            47780, // Neurotoxin Sabotage
            62771, // Core

            47783, // Underground
            47784, // Cave Johnson
            47787, // Repulsion Intro
            47468, // Bomb Flings
            47469, // Crazy Box
            47472, // PotatOS

            47791, // Propulsion Intro
            47793, // Propulsion Flings
            47795, // Conversion Intro
            47798, // Three Gels

            88350, // Test
            47800, // Funnel Intro
            47802, // Ceiling Button
            47804, // Wall Button
            47806, // Polarity
            47808, // Funnel Catch
            47811, // Stop the Box
            47813, // Laser Catapult
            47815, // Laser Platform
            47817, // Propulsion Catch
            47819, // Repulsion Polarity

            62776, // Finale 1
            47821, // Finale 2
            47824, // Finale 3
            47456, // Finale 4

            47741, // Doors
            47825, // Buttons
            47828, // Lasers
            47829, // Rat Maze
            45467, // Laser Crusher
            46362, // Behind the Scenes

            47831, // Flings
            47833, // Infinifling
            47835, // Team Retrieval
            47837, // Vertical Flings
            47840, // Catapults
            47841, // Multifling
            47844, // Fling Crushers
            47845, // Industrial Fan

            47848, // Cooperative Bridges
            47849, // Bridge Swap
            47854, // Fling Block
            47856, // Catapult Block
            47858, // Bridge Fling
            47861, // Turret Walls
            52642, // Turret Assassin
            52660, // Bridge Testing

            52662, // Cooperative Funnels
            52663, // Funnel Drill
            52665, // Funnel Catch
            52667, // Funnel Laser
            52671, // Cooperative Polarity
            52687, // Funnel Hop
            52689, // Advanced Polarity
            52691, // Funnel Maze
            52777, // Turret Warehouse

            52694, // Repulsion Jumps
            52711, // Double Bounce
            52714, // Bridge Repulsion
            52715, // Wall Repulsion
            52717, // Propulsion Crushers
            52735, // Turret Ninja
            52738, // Propulsion Retrieval
            52740, // Vault Entrance

            49341, // Separation
            49343, // Triple Axis
            49345, // Catapult Catch
            49347, // Bridge Gels
            49349, // Maintenance
            49351, // Bridge Catch
            52757, // Double Lift
            52759, // Gel Maze
            48287 // Crazier Box
    };

    private static final String PORTAL_2_SRCOM_ID = "om1mw4d2";
    private static final String SP_CATEGORY_SRCOM_ID = "jzd33ndn";
    private static final String COOP_CATEGORY_SRCOM_ID = "l9kv40kg";
    private static final String COOP_SUBCATEGORY_VARIABLE_ID = "38dj54e8";
    private static final String AMC_VARIABLE_CHOICE_ID = "mln3x8nq";

    private static final long ELITE_ROLE = 574794615971119135L;
    private static final long ADVANCED_ROLE = 608364011028742162L;
    private static final long PROFESSIONALS_ROLE = 313760989180985344L;
    private static final long INTERMEDIATE_ROLE = 574794742462677008L;
    private static final long MEDIOCRE_ROLE = 832011322568081448L;
    private static final long BEGINNER_ROLE = 146432621071564800L;

    // This should be a full mirror of the Iverb scores database.
    // <Map id, <Rank, List<Scores>>>
    public HashMap<Integer, ConcurrentSkipListMap<Integer, ArrayList<IVerbApi.ScoreMetadata>>> scores;
    public Long2IntMap spAggregated;
    public Long2IntMap coopAggregated;
    // Updated every cycle, a list of the people in the top 3 ranks
    // <Rank, List<User ids>>
    private ArrayList<Long> top3Users;

    private Leaderboard spLeaderboard;
    private Leaderboard coopAMCLeaderboard;

    public SkillRoleService() {
        this.jSpeedrun = new JSpeedrun();

        this.scores = new HashMap<>();
        this.spAggregated = new Long2IntOpenHashMap();
        this.coopAggregated = new Long2IntOpenHashMap();
        this.top3Users = new ArrayList<>();
    }

    @Override
    public void startService() throws IOException {
        latestProccessedScore = null;

        long totalSize = 0;
        AtomicInteger scoreCount = new AtomicInteger();

        for (int id : IVERB_MAP_IDS) {
            ConcurrentSkipListMap<Integer, ArrayList<IVerbApi.ScoreMetadata>> mapScores = new ConcurrentSkipListMap<>();

            totalSize += IVerbApi.fetchMapScores(id, (score, metadata) -> {
                        scoreCount.incrementAndGet();
                        // Add score to the scores map
                        mapScores.computeIfAbsent(score, s -> new ArrayList<>())
                                .add(metadata);
                        // Set the latest processed timestamp to the earliest timestamp found.
                        if(metadata.timeGained != null &&
                                (latestProccessedScore == null || metadata.timeGained.isAfter(latestProccessedScore))) {
                            latestProccessedScore = metadata.timeGained;
                        }
                    });

            this.scores.put(id, mapScores);
        }

        this.spAggregated = IVerbApi.getAggregatedSpRanks();
        this.coopAggregated = IVerbApi.getAggregatedCoopRanks();

        logger.info("Fetched all map scores on board.portal2.sr. Fetched " + scoreCount.get() + " scores and used " + (totalSize/1000f) + "kb of data.");

        refreshFuture = Luma.schedulerService
                .scheduleAtFixedRate(this::runChangelogFetch, milliRefreshTime, milliRefreshTime, TimeUnit.MILLISECONDS);

        CompletableFuture.allOf(
                jSpeedrun.getCategoryLeaderboard(PORTAL_2_SRCOM_ID, SP_CATEGORY_SRCOM_ID).thenAccept(response -> {
                    spLeaderboard = response.getData().get(0);
                }),
                jSpeedrun.getCategoryLeaderboard(PORTAL_2_SRCOM_ID, COOP_CATEGORY_SRCOM_ID, Map.of(COOP_SUBCATEGORY_VARIABLE_ID, AMC_VARIABLE_CHOICE_ID)).thenAccept(response -> {
                    coopAMCLeaderboard = response.getData().get(0);
                })).join();

        logger.info("Fetched leaderboards on srcom.");

        // Update all users on bot start.
        Bot.api.getServerById(146404426746167296L)
                .ifPresent(server -> server.getMembers().forEach(this::onScoreUpdate));

        logger.info("Updated all users");
    }

    private void runChangelogFetch() {
        try {
            logger.debug("Fetching score updates on board.portal2.sr...");

            Set<Long> updatedSteamUsers = new HashSet<>();
            Set<Long> updatedSrcomUsers = new HashSet<>();

            AtomicReference<Instant> newLatestProcessedScore = new AtomicReference<>();

            long dataFetched = 0;
            try {
                dataFetched = IVerbApi.fetchScoreUpdates(1,
                        scoreUpdate -> scoreUpdate.metadata.timeGained.isAfter(latestProccessedScore), // Only fetch scores that are after the latest processed score.
                        scoreUpdate -> {
                            var mapScores = scores.get(scoreUpdate.mapId);

                            // If the player's rank changed, add them to the update queue
                            if (scoreUpdate.postRank != scoreUpdate.preRank) {
                                updatedSteamUsers.add(scoreUpdate.metadata.steamId);
                            }

                            // Clean out obsolete scores from the Player
                            mapScores.forEach((score, metadatas) -> {
                                metadatas.removeIf(m -> (score >= scoreUpdate.score) &&
                                        (m.steamId == scoreUpdate.metadata.steamId));
                            });
                            /*mapScores.forEach((score, metadatas) -> {
                                if (score >= scoreUpdate.score) {
                                    metadatas.removeIf(m -> m.steamId == scoreUpdate.metadata.steamId);
                                }

                                // Remove scores that happened before the latest, if they are not the same score
                                if (score != scoreUpdate.score) {
                                    metadatas.removeIf(m -> m.steamId == scoreUpdate.metadata.steamId && m.timeGained.isBefore(scoreUpdate.metadata.timeGained));
                                }

                                if (metadatas.isEmpty()) {
                                    mapScores.remove(score);
                                }
                            });*/

                            // Add new score
                            mapScores.computeIfAbsent(scoreUpdate.score, s -> new ArrayList<>()).add(scoreUpdate.metadata);

                            // Clean out other scores that are now not top 200 or otherwise need to be updated
                            AtomicInteger rank = new AtomicInteger(1);
                            mapScores.forEach((score, metadatas) -> {
                                if (rank.get() <= 200) {
                                    // If the rank is below (greater than) the new score, it was likely updated
                                    if (rank.get() > scoreUpdate.postRank) {
                                        metadatas.forEach(m -> updatedSteamUsers.add(m.steamId));
                                    }
                                    rank.addAndGet(metadatas.size());
                                } else {
                                    mapScores.remove(score);
                                    metadatas.forEach(m -> updatedSteamUsers.add(m.steamId));
                                }
                            });

                            if (newLatestProcessedScore.get() == null
                                    || scoreUpdate.metadata.timeGained.isAfter(newLatestProcessedScore.get())) {
                                newLatestProcessedScore.set(scoreUpdate.metadata.timeGained);
                            }
                        });
            } catch (IOException e) {
                logger.error("Failed to fetch score updates", e);
            }

            this.spAggregated = IVerbApi.getAggregatedSpRanks();
            this.coopAggregated = IVerbApi.getAggregatedCoopRanks();

            // With the new elite qualification,
            //updatedSteamUsers.addAll(updateTop3());

            logger.info("Fetched iVerb score updates. Fetched " + (dataFetched / 1000f) + "kb of data and updating " + updatedSteamUsers.size() + " players.");

            if (newLatestProcessedScore.get() != null) {
                latestProccessedScore = newLatestProcessedScore.get();
            }

            logger.debug("Fetching leaderboard from speedrun.com...");

            CompletableFuture.allOf(
                    jSpeedrun.getCategoryLeaderboard(PORTAL_2_SRCOM_ID, SP_CATEGORY_SRCOM_ID).thenAccept(response -> {
                        spLeaderboard = response.getData().get(0);
                    }),
                    jSpeedrun.getCategoryLeaderboard(PORTAL_2_SRCOM_ID, COOP_CATEGORY_SRCOM_ID, Map.of(COOP_SUBCATEGORY_VARIABLE_ID, AMC_VARIABLE_CHOICE_ID)).thenAccept(response -> {
                        coopAMCLeaderboard = response.getData().get(0);
                    })).join();

            // TODO: Only update users if they change on the srcom boards. For now, update everyone.
            //updatedSteamUsers.forEach(this::onScoreUpdate);
            Bot.api.getServerById(146404426746167296L)
                    .ifPresent(server -> server.getMembers().forEach(this::onScoreUpdate));
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     *
     * @return The users that need to be updated.
     */
    private Set<Long> updateTop3() {
        // <Steam id>
        ArrayList<Long> allUsers = new ArrayList<>();

        this.scores.forEach((mapId, mapScores) -> mapScores.forEach((rank, rankScores) -> {
            rankScores.forEach(metadata -> {
                if(!allUsers.contains(metadata.steamId)) {
                    allUsers.add(metadata.steamId);
                }
            });
        }));

        // <Score, List<User id>>
        ConcurrentSkipListMap<Integer, List<Long>> allOveralls = new ConcurrentSkipListMap<>();
        for(long steamId : allUsers) {
            int overallScore = calculateRoundedTotalPoints(steamId);
            allOveralls.computeIfAbsent(overallScore, s -> new ArrayList<>())
                    .add(steamId);
        }

        Set<Long> updatesNeeded = new HashSet<>();
        ArrayList<Long> newTop3 = new ArrayList<>();

        // Pop the 3 highest scores
        for(int i = 0; i < 3; i++) {
            var lastEntry = allOveralls.pollLastEntry();
            for(long steamId : lastEntry.getValue()) {
                newTop3.add(steamId);
                if(!this.top3Users.contains(steamId)) {
                    updatesNeeded.add(steamId);
                }
            }
        }

        // Iterate through the old and see if anyone lost their spot
        for(long steamId : this.top3Users) {
            if(!newTop3.contains(steamId)) {
                updatesNeeded.add(steamId);
            }
        }

        this.top3Users = newTop3;

        return updatesNeeded;
    }

    private void onScoreUpdate(long steamId) {
        logger.debug("Score updated for steam account: " + steamId);

        for(User user : Luma.database.getVerifiedConnectionsById(String.valueOf(steamId), "steam")) {
            onScoreUpdate(user);
        }
    }

    public void onScoreUpdate(User discordUser) {

        //logger.info("Updating discord user: " + discordUser.getDiscriminatedName());

        if (discordUser.getRoles(Bot.api.getServerById(146404426746167296L).orElseThrow()).stream().anyMatch(r -> r.getId() == 312324674275115008L)) {
            logger.debug("Skipping discord user: " + discordUser.getDiscriminatedName() + " for being dunced.");
            return;
        }

        ArrayList<Long> assignedRoles = Luma.database.getAssignedRoles(discordUser.getId(), 146404426746167296L);

        AtomicBoolean elite = new AtomicBoolean(assignedRoles.contains(ELITE_ROLE));
        AtomicBoolean professionals = new AtomicBoolean(assignedRoles.contains(PROFESSIONALS_ROLE));
        AtomicBoolean advanced = new AtomicBoolean(assignedRoles.contains(ADVANCED_ROLE));
        AtomicBoolean intermediate = new AtomicBoolean(assignedRoles.contains(INTERMEDIATE_ROLE));
        AtomicBoolean beginner = new AtomicBoolean(assignedRoles.contains(BEGINNER_ROLE));
        AtomicBoolean mediocre = new AtomicBoolean(assignedRoles.contains(MEDIOCRE_ROLE));

        final Instant sixMonthsAgo = Instant.now().minus(6 * 30, ChronoUnit.DAYS);

        Luma.database.getVerifiedConnectionsByUser(discordUser.getId(), 146404426746167296L)
                .forEach((type, ids) -> {
                    switch (type) {
                        case "steam":
                            // Check a user's iverb account(s).
                            for(String steamIdString : ids) {
                                long steamId = Long.parseLong(steamIdString);
                                float spPoints = calculateSpPoints(steamId);
                                float coopPoints = calculateCoopPoints(steamId);
                                int totalPointsRounded = Math.round(spPoints) + Math.round(coopPoints);
                                Instant latestScore = getLatestScore(steamId);

                                if(logger.isDebugEnabled()) {
                                    //logger.debug(discordUser.getDiscriminatedName() + " - spPoints=" + spPoints + " - coopPoints=" + coopPoints + " - totalRounded=" + totalPointsRounded);
                                }

                                // Elite Qualifications
                                if(setIfTrue(elite, totalPointsRounded >= 20300)) {
                                    logger.debug("Giving Elite due to having >= 20,300 total points: " + totalPointsRounded);
                                }
                                //elite.compareAndSet(false, top3Users.contains(steamId));

                                // Professionals Qualifications
                                if(setIfTrue(professionals, totalPointsRounded >= 17500)) {
                                    logger.debug("Giving Professionals due to having >= 17,500 total points: " + totalPointsRounded);
                                }
                                if(setIfTrue(professionals, spPoints >= 10300)) {
                                    logger.debug("Giving Professionals due to having >= 10,300 sp points: " + spPoints);
                                }
                                if(setIfTrue(professionals, coopPoints >= 8000)) {
                                    logger.debug("Giving Professionals due to having >= 8,000 coop points: " + coopPoints);
                                }

                                // Advanced Qualifications
                                if(setIfTrue(advanced, spPoints >= 7000)) {
                                    logger.debug("Giving Advanced due to having >= 7,000 sp points: " + spPoints);
                                }
                                if(setIfTrue(advanced, coopPoints >= 6000)) {
                                    logger.debug("Giving Advanced due to having >= 6,000 sp points: " + coopPoints);
                                }

                                // Intermediate Qualifications
                                if(setIfTrue(intermediate, spPoints >= 4000)) {
                                    logger.debug("Giving Intermediate due to having >= 4,000 sp points: " + spPoints);
                                }
                                if(setIfTrue(intermediate, coopPoints >= 3500)) {
                                    logger.debug("Giving Intermediate due to having >= 3,500 sp points: " + coopPoints);
                                }

                                // Mediocre Qualifications
                                if(setIfTrue(mediocre, spPoints >= 1500)) {
                                    logger.debug("Giving Mediocre due to having >= 1500 sp points: " + spPoints);
                                }
                                if(setIfTrue(mediocre, coopPoints >= 2000)) {
                                    logger.debug("Giving Mediocre due to having >= 2000 coop points: " + coopPoints);
                                }

                                // Beginner Qualifications
                                if(setIfTrue(beginner, latestScore.isAfter(sixMonthsAgo) && discordUser.getId() != 103656524617900032L)) {
                                    logger.debug("Giving Beginner due to iVerb activity in the last 6 months: " + latestScore.toString());
                                }
                            }
                            break;
                        case "srcom":
                            // Check a user's speedrun.com account(s).
                            for(String srcomId : ids) {
                                double singlePlayerTime = Double.MAX_VALUE;
                                double amcTimePlayerBelowRank = Double.MAX_VALUE;
                                double amcTimePlayerAboveRank = Double.MAX_VALUE;

                                int singlePlayerRank = Integer.MAX_VALUE;
                                int amcRankPlayerBelow = Integer.MAX_VALUE;
                                int amcRankPlayerAbove = Integer.MAX_VALUE;

                                OffsetDateTime latestRun = null;

                                // Check single player leaderboards
                                for (Leaderboard.LeaderboardPlace place : spLeaderboard.runs) {
                                    // Check that the run is verified
                                    if (!place.run.status.status.equalsIgnoreCase("verified")) {
                                        break;
                                    }

                                    boolean containsPlayer = false;
                                    for (Run.Player player : place.run.players) {
                                        if (player.id != null) {
                                            containsPlayer = containsPlayer || (player.id.equals(srcomId));
                                        }
                                    }

                                    if (containsPlayer) {
                                        // Check if this run is the latest
                                        if (place.run.submitted != null) {
                                            if (latestRun == null) {
                                                latestRun = place.run.submitted;
                                            } else {
                                                if (place.run.submitted.isAfter(latestRun)) {
                                                    latestRun = place.run.submitted;
                                                }
                                            }
                                        }

                                        if (singlePlayerTime > place.run.times.primaryTime) {
                                            singlePlayerTime = place.run.times.primaryTime;
                                        }

                                        if (place.place > 0 && singlePlayerRank > place.place) {
                                            singlePlayerRank = place.place;
                                        }
                                    }
                                }

                                // Check coop leaderboards
                                for (Leaderboard.LeaderboardPlace place : coopAMCLeaderboard.runs) {
                                    // Check that the run is verified
                                    if (!place.run.status.status.equalsIgnoreCase("verified")) {
                                        break;
                                    }

                                    boolean containsPlayer = false;
                                    Run.Player coopPartner = null;

                                    for (Run.Player player : place.run.players) {
                                        if (player.id != null) {
                                            if (player.id.equals(srcomId)) {
                                                containsPlayer = true;
                                            } else {
                                                coopPartner = player;
                                            }
                                        }
                                    }

                                    if (containsPlayer) {
                                        // Check if this run is the latest
                                        if (place.run.submitted != null) {
                                            if (latestRun == null) {
                                                latestRun = place.run.submitted;
                                            } else {
                                                if (place.run.submitted.isAfter(latestRun)) {
                                                    latestRun = place.run.submitted;
                                                }
                                            }
                                        }

                                        // Get the coop partner's highest rank
                                        int partnerHighestRank = Integer.MAX_VALUE;

                                        if (coopPartner != null) {
                                            for (Leaderboard.LeaderboardPlace partnerPlace : coopAMCLeaderboard.runs) {
                                                // Check that the run is verified
                                                if (!partnerPlace.run.status.status.equalsIgnoreCase("verified")) {
                                                    break;
                                                }

                                                boolean containsPartner = false;
                                                for (Run.Player player : place.run.players) {
                                                    if (player.id != null) {
                                                        containsPartner = containsPartner || (player.id.equals(coopPartner.id));
                                                    }
                                                }

                                                if (place.place > 0 && containsPartner && partnerHighestRank > partnerPlace.place) {
                                                    partnerHighestRank = partnerPlace.place;
                                                }
                                            }
                                        }

                                        if (place.place > 0) {
                                            if (place.place <= partnerHighestRank) {
                                                // Partner is below current rank
                                                if (amcTimePlayerBelowRank > place.run.times.primaryTime) {
                                                    amcTimePlayerBelowRank = place.run.times.primaryTime;
                                                }
                                                if (amcRankPlayerBelow > place.place) {
                                                    amcRankPlayerBelow = place.place;
                                                }
                                            } else {
                                                // Partner is above current rank
                                                if (amcTimePlayerAboveRank > place.run.times.primaryTime) {
                                                    amcTimePlayerAboveRank = place.run.times.primaryTime;
                                                }
                                                if (amcRankPlayerAbove > place.place) {
                                                    amcRankPlayerAbove = place.place;
                                                }
                                            }
                                        }
                                    }
                                }

                                //if (logger.isDebugEnabled()) {
                                    //logger.info(discordUser.getDiscriminatedName() + " - srcom - " + srcomId + " - sp=" + singlePlayerRank + " - amcAbove=" + amcRankPlayerAbove + " - amcBelow=" + amcRankPlayerBelow);
                                //}

                                // Elite Qualifications
                                if (setIfTrue(elite, singlePlayerRank <= 3)) { // Top 3
                                    logger.debug("Giving Elite due to Top 3 Single Player Time: " + singlePlayerRank);
                                }
                                if (setIfTrue(elite, amcTimePlayerAboveRank < (26 * 60) + 30)) { // Sub 26:30
                                    logger.debug("Giving Elite due to a sub 26:30 AMC Time: " + amcTimePlayerAboveRank);
                                }
                                if (setIfTrue(elite, amcTimePlayerBelowRank < (26 * 60) + 30)) { // Sub 26:30
                                    logger.debug("Giving Elite due to a sub 26:30 AMC Time: " + amcTimePlayerBelowRank);
                                }

                                // Professionals Qualifications
                                if (setIfTrue(professionals, singlePlayerRank <= 10)) { // Top 10
                                    logger.debug("Giving Professionals due to Top 10 Single Player Time: " + singlePlayerTime);
                                }
                                if (setIfTrue(professionals, amcTimePlayerAboveRank < (27 * 60) + 15)) { // Sub 27:15
                                    logger.debug("Giving Professionals due to a sub 27:15 AMC Time: " + amcTimePlayerAboveRank);
                                }
                                if (setIfTrue(professionals, amcTimePlayerBelowRank < (27 * 60) + 15)) { // Sub 27:15
                                    logger.debug("Giving Professionals due to a sub 27:15 AMC Time: " + amcTimePlayerBelowRank);
                                }

                                // Advanced Qualifications
                                if (setIfTrue(advanced, singlePlayerRank <= 30)) { // Top 30
                                    logger.debug("Giving Advanced due to Top 30 Single Player Time: " + singlePlayerRank);
                                }
                                if (setIfTrue(advanced, amcTimePlayerAboveRank < (28 * 60) + 15)) { // Sub 28:15
                                    logger.debug("Giving Advanced due to a sub 28:15 AMC Time w/ Above Rank: " + amcTimePlayerAboveRank);
                                }
                                if (setIfTrue(advanced, amcTimePlayerBelowRank < (29 * 60))) { // Sub 29:00
                                    logger.debug("Giving Advanced due to a sub 29:00 AMC Time w/ Below Rank: " + amcTimePlayerBelowRank);
                                }

                                // Intermediate Qualifications
                                if (setIfTrue(intermediate, singlePlayerRank <= 65)) { // Top 65
                                    logger.debug("Giving Intermediate due to Top 65 Single Player Time: " + singlePlayerRank);
                                }
                                if (setIfTrue(intermediate, amcTimePlayerAboveRank < (32 * 60))) { // Sub 32:00
                                    logger.debug("Giving Intermediate due to a sub 27:15 AMC Time: " + amcTimePlayerAboveRank);
                                }
                                if (setIfTrue(intermediate, amcTimePlayerBelowRank < (32 * 60))) { // Sub 32:00
                                    logger.debug("Giving Intermediate due to a sub 27:15 AMC Time: " + amcTimePlayerBelowRank);
                                }

                                // Mediocre Qualifications
                                if (setIfTrue(mediocre, singlePlayerRank <= 130)) { // Top 130
                                    logger.debug("Giving Mediocre due to Top 130 Single Player Time: " + singlePlayerRank);
                                }
                                if (setIfTrue(mediocre, amcTimePlayerAboveRank < (36 * 60))) { // Sub 36:00
                                    logger.debug("Giving Mediocre due to a sub 36:00 AMC Time: " + amcTimePlayerAboveRank);
                                }
                                if (setIfTrue(mediocre, amcTimePlayerBelowRank < (36 * 60))) { // Sub 36:00
                                    logger.debug("Giving Mediocre due to a sub 36:00 AMC Time: " + amcTimePlayerBelowRank);
                                }

                                // Beginner Qualifications
                                if(latestRun != null) {
                                    if(setIfTrue(beginner, latestRun.isAfter(OffsetDateTime.now().minus(6, ChronoUnit.MONTHS)) && discordUser.getId() != 103656524617900032L)) { // Run in the last 6 months
                                        logger.debug("Giving Beginner due to srcom activity in the last 6 months: " + latestRun.toString());
                                    }
                                }
                            }
                            break;
                        default:
                            break;
                    }
                });

        Bot.api.getRoleById(ELITE_ROLE)
                .ifPresentOrElse(role -> giveOrTakeRole(role, discordUser, elite, true),
                        () -> logger.error("Failed to find elite role."));

        Bot.api.getRoleById(PROFESSIONALS_ROLE)
                .ifPresentOrElse(role -> giveOrTakeRole(role, discordUser, professionals, true),
                        () -> logger.error("Failed to find professionals role."));

        Bot.api.getRoleById(ADVANCED_ROLE)
                .ifPresentOrElse(role -> giveOrTakeRole(role, discordUser, advanced, true),
                        () -> logger.error("Failed to find advanced role."));

        Bot.api.getRoleById(INTERMEDIATE_ROLE)
                .ifPresentOrElse(role -> giveOrTakeRole(role, discordUser, intermediate, true),
                        () -> logger.error("Failed to find intermediate role."));

        Bot.api.getRoleById(MEDIOCRE_ROLE)
                .ifPresentOrElse(role -> giveOrTakeRole(role, discordUser, mediocre, true),
                        () -> logger.error("Failed to find mediocre role."));

        Bot.api.getRoleById(BEGINNER_ROLE)
                .ifPresentOrElse(role -> giveOrTakeRole(role, discordUser, beginner, false),
                        () -> logger.error("Failed to find beginner role."));
    }

    private boolean setIfTrue(AtomicBoolean state, boolean predicate) {
        if(predicate && !state.get()) {
            state.set(true);
            return false; // Return true to enable comprehensive logging
        } else {
            return false;
        }
    }

    private void giveOrTakeRole(Role role, User discordUser, AtomicBoolean state, boolean autoRemoveRole) {
        if(!role.getUsers().contains(discordUser)) {
            if(state.get()) {
                logger.info("Giving {} {} role", discordUser.getDiscriminatedName(), role.getName());
                discordUser.addRole(role).exceptionally(ExceptionLogger.get());
            }
        } else if (autoRemoveRole) {
            if(!state.get()) {
                logger.info("Removing {} {} role", discordUser.getDiscriminatedName(), role.getName());
                discordUser.removeRole(role).exceptionally(ExceptionLogger.get());
            }
        }
    }

    public float calculateSpPoints(long steamId) {
        /*float[] totalSpPoints = { 0f };

        for(int i = 0; i < FIRST_COOP_MAP_INDEX; i++) {
            sumMapPoints(steamId, totalSpPoints, i);
        }

        return totalSpPoints[0];*/
        return this.spAggregated.get(steamId);
    }

    public float calculateCoopPoints(long steamId) {
        /*float[] totalCoopPoints = { 0f };

        for(int i = FIRST_COOP_MAP_INDEX; i < IVERB_MAP_IDS.length; i++) {
            sumMapPoints(steamId, totalCoopPoints, i);
        }

        return totalCoopPoints[0];*/
        return this.coopAggregated.get(steamId);
    }

    public int calculateRoundedTotalPoints(long steamId) {
        return Math.round(calculateSpPoints(steamId)) + Math.round(calculateCoopPoints(steamId));
    }

    private void sumMapPoints(long steamId, float[] totalCoopPoints, int mapId) {
        var mapScores = scores.get(IVERB_MAP_IDS[mapId]);

        AtomicInteger rank = new AtomicInteger(1);
        mapScores.forEach((score, metadatas) -> {
            boolean added = false;
            for (IVerbApi.ScoreMetadata metadata : metadatas) {
                if(metadata.steamId == steamId) {
                    if (!added) {
                        totalCoopPoints[0] += calculatePoints(rank.get());
                        added = true;
                    } else {
                        logger.error("USER " + steamId + " HAS DUPLICATED SCORE ON MAP: " + mapId + " AT RANK " + rank.get());
                    }
                }
            }
            rank.addAndGet(metadatas.size());
        });
    }

    private Instant getLatestScore(long steamId) {
        AtomicReference<Instant> latestScore = new AtomicReference<>(Instant.MIN);
        this.scores.forEach((mapId, mapScores) ->
                mapScores.forEach((rank, rankScores) ->
                        rankScores.forEach(meta -> {
            if(meta.steamId == steamId && meta.timeGained != null) {
                if(meta.timeGained.isAfter(latestScore.get())) {
                    latestScore.set(meta.timeGained);
                }
            }
        })));
        return latestScore.get();
    }

    private float calculatePoints(int rank) {
        float num = (200f - (rank - 1f));
        return Math.max(1f, (num * num)/200f);
    }

    @Override
    public void reload() throws IOException {
        refreshFuture.cancel(false);

        startService();
    }

    public static void main(String[] args) throws IOException {
        Luma.okHttpClient = new OkHttpClient();
        Luma.jsonFactory = new JsonFactory();
        SkillRoleService service = new SkillRoleService();

        service.startService();

        Scanner scanner = new Scanner(System.in);
        String line;
        while(!(line = scanner.nextLine()).equals("stop")) {
            long steamId = Long.parseLong(line);

            float spPoints = service.calculateSpPoints(steamId);
            float coopPoints = service.calculateCoopPoints(steamId);

            System.out.println("Points for SteamId: " + steamId);
            System.out.println("Sp: " + spPoints);
            System.out.println("Coop: " + coopPoints);
            //TODO: Remember that Sp points and coop points are rounded separately before being added for overall points
            System.out.println("Overall: " + (spPoints + coopPoints));
        }
    }
}
