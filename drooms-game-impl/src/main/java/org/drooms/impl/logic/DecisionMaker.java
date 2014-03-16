package org.drooms.impl.logic;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.codehaus.plexus.classworlds.strategy.Strategy;
import org.drools.core.time.SessionPseudoClock;
import org.drooms.api.Action;
import org.drooms.api.Node;
import org.drooms.api.Player;
import org.drooms.api.Playground;
import org.drooms.impl.logic.events.CollectibleAdditionEvent;
import org.drooms.impl.logic.events.CollectibleRemovalEvent;
import org.drooms.impl.logic.events.CollectibleRewardEvent;
import org.drooms.impl.logic.events.PlayerActionEvent;
import org.drooms.impl.logic.events.PlayerDeathEvent;
import org.drooms.impl.logic.events.SurvivalRewardEvent;
import org.drooms.impl.logic.facts.CurrentPlayer;
import org.drooms.impl.logic.facts.CurrentTurn;
import org.drooms.impl.logic.facts.GameProperty;
import org.drooms.impl.logic.facts.Wall;
import org.drooms.impl.logic.facts.Worm;
import org.drooms.impl.util.DroomsKnowledgeSessionValidator;
import org.drooms.impl.util.GameProperties;
import org.kie.api.KieServices;
import org.kie.api.logger.KieRuntimeLogger;
import org.kie.api.runtime.Channel;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.api.runtime.conf.ClockTypeOption;
import org.kie.api.runtime.rule.EntryPoint;
import org.kie.api.runtime.rule.FactHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a {@link Player}'s Strategy in action. This class holds
 * and maintains Drools engine's state for each particular player.
 * 
 * <p>
 * When asked (see {@link #decideNextMove()}), the strategy should make a decision on the next move, based on the
 * current state of the working memory. This decision should be sent over the provided 'decision' channel. If not sent,
 * it will default to STAY. See {@link Action} for the various types of decisions.
 * </p>
 * <p>
 * This class enforces the following requirements on the strategies:
 * </p>
 * 
 * <ul>
 * <li>'gameEvents' entry point must be declared, where the events not directly related to player actions will be sent.
 * These events are {@link CollectibleAdditionEvent} and {@link CollectibleRemovalEvent}.</li>
 * <li>'playerEvents' entry point must be declared, where the player-caused events will be sent. These events are
 * {@link PlayerActionEvent} and {@link PlayerDeathEvent}.</li>
 * <li>'rewardEvents' entry point must be declared, where the reward events will be sent. These events are
 * {@link CollectibleRewardEvent} and {@link SurvivalRewardEvent}.</li>
 * </ul>
 * 
 * <p>
 * This class provides the following Drools globals, if declared in the strategy:
 * </p>
 * 
 * <ul>
 * <li>'logger' implementation of the {@link Logger} interface, to use for logging from within the rules.</li>
 * <li>'tracker' instance of the {@link PathTracker}, to facilitate path-finding in the rules.</li>
 * </ul>
 * 
 * <p>
 * Your strategies can be validated for all these - just make your tests extend {@link DroomsTestHelper}.
 * </p>
 * 
 * <p>
 * The working memory will contain instances of the various helper fact types:
 * </p>
 * 
 * <ul>
 * <li>{@link GameProperty}, many. Will never change or be removed.</li>
 * <li>{@link CurrentPlayer}, once. Will never change or be removed.</li>
 * <li>{@link CurrentTurn}, once. Will change with every turn.</li>
 * <li>{@link Wall}, many. Will remain constant over the whole game.</li>
 * <li>{@link Worm}, many. Will be added and removed as the worms will move, but never modified.</li>
 * </ul>
 * 
 */
public class DecisionMaker implements Channel {

    private static final Logger LOGGER = LoggerFactory.getLogger(DecisionMaker.class);

    private static void setGlobal(final KieSession session, final String global, final Object value) {
        try {
            session.setGlobal(global, value);
        } catch (final RuntimeException ex) {
            // do nothing, since the user has already been notified
        }
    }

    private final FactHandle currentTurn;
    private final EntryPoint gameEvents, playerEvents, rewardEvents;
    private final Map<Player, Map<Node, FactHandle>> handles = new HashMap<Player, Map<Node, FactHandle>>();
    private final boolean isDisposed = false;
    private Action latestDecision = null;
    private final Player player;
    private final KieSession session;

    private final KieRuntimeLogger sessionAudit;

    public DecisionMaker(final Player p, final PathTracker tracker, final GameProperties properties,
            final File reportFolder) {
        this.player = p;
        final KieSessionConfiguration config = KieServices.Factory.get().newKieSessionConfiguration();
        config.setOption(ClockTypeOption.get("pseudo"));
        this.session = p.constructKieBase().newKieSession(config, null);
        // validate session
        final DroomsKnowledgeSessionValidator validator = new DroomsKnowledgeSessionValidator(this.session);
        if (!validator.isValid()) {
            throw new IllegalStateException("Player " + this.player.getName() + " has a malformed strategy: "
                    + validator.getErrors().get(0));
        }
        if (!validator.isClean()) {
            for (final String message : validator.getWarnings()) {
                DecisionMaker.LOGGER.info("Player {} has an incomplete strategy: {}", this.player.getName(), message);
            }
        }
        // FIXME figure out how to audit kie session
        DecisionMaker.LOGGER.info("Auditing the Drools session is disabled.");
        this.sessionAudit = null;
        // this is where we listen for decisions
        this.session.registerChannel("decision", this);
        // this is where we will send events from the game
        this.rewardEvents = this.session.getEntryPoint("rewardEvents");
        this.gameEvents = this.session.getEntryPoint("gameEvents");
        this.playerEvents = this.session.getEntryPoint("playerEvents");
        // configure the globals for the session
        DecisionMaker.setGlobal(this.session, "tracker", tracker);
        DecisionMaker.setGlobal(this.session, "logger",
                LoggerFactory.getLogger("org.drooms.players." + this.player.getName()));
        /*
         * insert playground walls; make sure the playground is always
         * surrounded with walls.
         */
        final Playground playground = tracker.getPlayground();
        for (int x = -1; x <= playground.getWidth(); x++) {
            for (int y = -1; y <= playground.getHeight(); y++) {
                if (!playground.isAvailable(x, y)) {
                    this.session.insert(new Wall(playground.getNodeAt(x, y)));
                }
            }
        }
        // insert info about the game configuration
        this.session.insert(new GameProperty(GameProperty.Name.MAX_TURNS, properties.getMaximumTurns()));
        this.session
                .insert(new GameProperty(GameProperty.Name.MAX_INACTIVE_TURNS, properties.getMaximumInactiveTurns()));
        this.session.insert(new GameProperty(GameProperty.Name.DEAD_WORM_BONUS, properties.getDeadWormBonus()));
        this.session.insert(new GameProperty(GameProperty.Name.TIMEOUT_IN_SECONDS, properties
                .getStrategyTimeoutInSeconds()));
        // insert info about the game status
        this.currentTurn = this.session.insert(new CurrentTurn(0));
        this.session.insert(new CurrentPlayer(p));
    }

    /**
     * Call on the Drools engine to make the decision on worm's next move,
     * according to the {@link Player}'s {@link Strategy}.
     * 
     * @return The move. STAY will be chosen when the strategy doesn't respond.
     */
    public Action decideNextMove() {
        this.validate();
        DecisionMaker.LOGGER.trace("Player {} advancing time. ", new Object[]{this.player.getName()});
        final SessionPseudoClock clock = this.session.getSessionClock();
        clock.advanceTime(1, TimeUnit.MINUTES);
        // decide
        DecisionMaker.LOGGER.trace("Player {} deciding. ", new Object[]{this.player.getName()});
        this.latestDecision = null;
        this.session.fireAllRules();
        // increase turn number
        final CurrentTurn turn = (CurrentTurn) this.session.getObject(this.currentTurn);
        this.session.update(this.currentTurn, new CurrentTurn(turn.getNumber() + 1));
        // store the decision
        if (this.latestDecision == null) {
            DecisionMaker.LOGGER.info("Player {} didn't make a decision. STAY forced.", this.player.getName());
            return Action.NOTHING;
        } else {
            DecisionMaker.LOGGER.info("Player {} final decision is {}. ", this.player.getName(), this.latestDecision);
            return this.latestDecision;
        }
    }

    public Player getPlayer() {
        return this.player;
    }

    /**
     * Stop the decision-making process, no matter where it currently is.
     */
    public void halt() {
        this.session.halt();
    }

    /**
     * Whether or not this object can still be used for decision making.
     * 
     * @return False when it can be used.
     */
    public boolean isTerminated() {
        return this.isDisposed;
    }

    public void notifyOfCollectibleAddition(final CollectibleAdditionEvent evt) {
        this.gameEvents.insert(evt);
    }

    public void notifyOfCollectibleRemoval(final CollectibleRemovalEvent evt) {
        this.gameEvents.insert(evt);
    }

    public void notifyOfCollectibleReward(final CollectibleRewardEvent evt) {
        this.rewardEvents.insert(evt);
    }

    public void notifyOfDeath(final PlayerDeathEvent evt) {
        this.playerEvents.insert(evt);
        final Player p = evt.getPlayer();
        // remove player from the WM
        for (final Map.Entry<Node, FactHandle> entry : this.handles.remove(p).entrySet()) {
            this.session.delete(entry.getValue());
        }
    }

    public void notifyOfPlayerMove(final PlayerActionEvent evt) {
        final Player p = evt.getPlayer();
        this.playerEvents.insert(evt);
        // update player positions
        if (!this.handles.containsKey(p)) {
            this.handles.put(p, new HashMap<Node, FactHandle>());
        }
        final Map<Node, FactHandle> playerHandles = this.handles.get(p);
        final Set<Node> untraversedNodes = new HashSet<Node>(playerHandles.keySet());
        for (final Node n : evt.getNodes()) {
            if (!playerHandles.containsKey(n)) { // worm occupies a new node
                final FactHandle fh = this.session.insert(new Worm(p, n));
                playerHandles.put(n, fh);
            }
            untraversedNodes.remove(n);
        }
        for (final Node n : untraversedNodes) { // worm no longer
                                                // occupies a node
            final FactHandle fh = playerHandles.remove(n);
            this.session.delete(fh);
        }
    }

    public void notifyOfSurvivalReward(final SurvivalRewardEvent evt) {
        this.rewardEvents.insert(evt);
    }

    @Override
    public void send(final Object object) {
        this.validate();
        if (object instanceof Action) {
            if (this.latestDecision != null) {
                DecisionMaker.LOGGER.debug("Player {} has changed the decision from {} to {}.", new Object[]{
                        this.player.getName(), this.latestDecision, object});
            }
            this.latestDecision = (Action) object;
        } else {
            DecisionMaker.LOGGER.warn("Player {} indicated an invalid move {}.", new Object[]{this.player.getName(),
                    this.latestDecision});
        }
    }

    /**
     * Clean up after this Drools instance. Will terminate the session and leave
     * all the objects up for garbage collection. Only call once and then don't
     * use this object anymore.
     * 
     * @return False if already terminated.
     */
    public boolean terminate() {
        if (this.isDisposed) {
            DecisionMaker.LOGGER.warn("Player {} already terminated.", new Object[]{this.player.getName()});
            return false;
        } else {
            DecisionMaker.LOGGER.info("Terminating player {}.", new Object[]{this.player.getName()});
            if (this.sessionAudit != null) {
                this.sessionAudit.close();
            }
            this.halt();
            this.session.dispose();
            return true;
        }
    }

    private void validate() {
        if (this.isDisposed) {
            throw new IllegalStateException("Player " + this.player.getName() + " already terminated!");
        }
    }

}
