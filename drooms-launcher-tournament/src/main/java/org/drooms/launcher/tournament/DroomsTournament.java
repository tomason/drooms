package org.drooms.launcher.tournament;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.SortedMap;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.drooms.api.Game;
import org.drooms.api.Player;
import org.drooms.impl.DroomsGame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DroomsTournament {

    private static final Logger LOGGER = LoggerFactory.getLogger(DroomsTournament.class);

    private static String getTimestamp() {
        final Date date = new java.util.Date();
        return new Timestamp(date.getTime()).toString();
    }

    public static void main(final String[] args) {
        // load the CLI
        final CLI cli = CLI.getInstance();
        final File config = cli.process(args);
        if (config == null) {
            cli.printHelp();
            System.exit(-1);
        }
        // load players
        final TournamentProperties props = TournamentProperties.read(config);
        if (props == null) {
            throw new IllegalStateException("Failed reading tournament config file.");
        }
        // load report folder
        final String id = DroomsTournament.getTimestamp();
        final File reports = new File(props.getTargetFolder(), "tournaments/" + id);
        if (!reports.exists()) {
            reports.mkdirs();
        }
        // load game class
        final Class<? extends Game> game = props.getGameClass();
        final Collection<Player> players = props.getPlayers();
        // prepare a result tracker
        final TournamentResults result = new DroomsTournamentResults(id, players);
        // for each playground...
        for (final ImmutablePair<File, File> gameConfig : props.getPlaygrounds()) {
            final String playgroundName = gameConfig.getLeft().getName();
            // run N games on the playground
            DroomsTournament.LOGGER.info("Starting games on playground {}.", playgroundName);
            for (int i = 1; i <= Integer.valueOf(props.getNumberOfRunsPerPlayground()); i++) {
                DroomsTournament.LOGGER.info("Starting game #{} on playground {}.", i, playgroundName);
                // randomize player order
                final List<Player> randomPlayers = new ArrayList<>(players);
                Collections.shuffle(randomPlayers);
                // play the game
                final DroomsGame dg = new DroomsGame(game, gameConfig.getLeft(), randomPlayers, gameConfig.getRight(),
                        reports);
                result.addResults(playgroundName, dg.play(playgroundName + "_" + i));
            }
        }
        DroomsTournament.LOGGER.info("Tournament results:");
        int i = 1;
        for (final SortedMap.Entry<Long, Collection<Player>> entry : result.evaluate().entrySet()) {
            DroomsTournament.LOGGER.info("#" + i + " with " + entry.getKey() + " points: " + entry.getValue());
            i++;
        }
        try (BufferedWriter w = new BufferedWriter(new FileWriter(new File(reports, "report.html")))) {
            result.write(w);
        } catch (final IOException e) {
            // FIXME do something here
        }
    }

}