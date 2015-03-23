package net.powermatcher.remote.websockets;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.powermatcher.api.MatcherEndpoint;
import net.powermatcher.api.messages.BidUpdate;
import net.powermatcher.api.messages.PriceUpdate;
import net.powermatcher.api.monitoring.ObservableAgent;
import net.powermatcher.core.BaseMatcherEndpoint;
import net.powermatcher.core.bidcache.AggregatedBid;
import net.powermatcher.core.concentrator.BidHistoryStore;
import net.powermatcher.core.concentrator.SentBidInformation;
import net.powermatcher.remote.websockets.data.ClusterInfoModel;
import net.powermatcher.remote.websockets.data.PmMessage;
import net.powermatcher.remote.websockets.data.PmMessage.PayloadType;
import net.powermatcher.remote.websockets.data.PriceUpdateModel;
import net.powermatcher.remote.websockets.json.ModelMapper;
import net.powermatcher.remote.websockets.json.PmJsonSerializer;

import org.eclipse.jetty.websocket.api.CloseStatus;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import aQute.bnd.annotation.component.Activate;
import aQute.bnd.annotation.component.Component;
import aQute.bnd.annotation.component.Deactivate;
import aQute.bnd.annotation.metatype.Configurable;
import aQute.bnd.annotation.metatype.Meta;

import com.google.gson.JsonSyntaxException;

/**
 * WebSocket implementation of an {@link MatcherEndpointProxy}. Enabled two agents to communicate via WebSockets and
 * JSON over a TCP connection.
 * 
 * @author FAN
 * @version 2.0
 */
@WebSocket()
@Component(designateFactory = MatcherEndpointProxyWebsocket.Config.class,
           immediate = true,
           provide = { ObservableAgent.class })
public class MatcherEndpointProxyWebsocket
    extends BaseMatcherEndpoint {

    private static final Logger LOGGER = LoggerFactory.getLogger(MatcherEndpointProxyWebsocket.class);

    @Meta.OCD
    public static interface Config {
        @Meta.AD(deflt = "matcherendpointproxy", description = "The unique identifier of the agent")
        String agentId();

        @Meta.AD(deflt = "ws://localhost:8080/powermatcher/websockets/agentendpoint",
                 description = "URL of powermatcher websocket endpoint.")
        String powermatcherUrl();

        @Meta.AD(deflt = "30", description = "reconnect timeout keeping the connection alive.")
        int reconnectTimeout();

        @Meta.AD(deflt = "60", description = "connect timeout to wait for remote server to respond.")
        int connectTimeout();

        @Meta.AD(deflt = "1000",
                 description = "Mimimum time between two BidUpdates generated by the Concentratro in milliseconds")
        long minTimeBetweenBidUpdates();
    }

    private URI powermatcherUrl;

    private Session remoteSession;

    private WebSocketClient client;

    private int reconnectDelay, connectTimeout;

    private long minTimeBetweenBidUpdates;

    private BundleContext bundleContext;

    private ServiceRegistration<MatcherEndpoint> matcherEndpointServiceRegistration;

    private final ScheduledThreadPoolExecutor executorService = new ScheduledThreadPoolExecutor(1);

    private ScheduledFuture<?> scheduledFuture;

    private final AtomicInteger bidNumberGenerator = new AtomicInteger();

    private final BidHistoryStore sentBids = new BidHistoryStore();

    /**
     * OSGi calls this method to activate a managed service.
     * 
     * @param properties
     *            the configuration properties
     */
    @Activate
    public synchronized void activate(BundleContext bundleContext, Map<String, Object> properties) {
        // Read configuration properties
        Config config = Configurable.createConfigurable(Config.class, properties);
        init(config.agentId());

        try {
            powermatcherUrl = new URI(config.powermatcherUrl()
                                      + "?agentId=" + getAgentId());
        } catch (URISyntaxException e) {
            LOGGER.error("Malformed URL for powermatcher websocket endpoint. Reason {}", e);
            return;
        }

        reconnectDelay = config.reconnectTimeout();
        connectTimeout = config.connectTimeout();
        minTimeBetweenBidUpdates = config.minTimeBetweenBidUpdates();

        this.bundleContext = bundleContext;

        Runnable reconnectJob = new Runnable() {
            @Override
            public void run() {
                connectRemote();
            }
        };

        scheduledFuture = executorService.scheduleAtFixedRate(reconnectJob,
                                                              1,
                                                              reconnectDelay,
                                                              TimeUnit.SECONDS);
    }

    /**
     * OSGi calls this method to deactivate a managed service.
     */
    @Deactivate
    public synchronized void deactivate() {
        scheduledFuture.cancel(true);
        disconnectRemote();
        unregisterMatcherEndpoint();
    }

    /**
     * {@inheritDoc}
     * 
     * This specific implementation opens a websocket.
     */
    public synchronized void connectRemote() {
        if (!isRemoteConnected()) {
            // Try to setup a new websocket connection.
            client = new WebSocketClient();
            ClientUpgradeRequest request = new ClientUpgradeRequest();

            try {
                client.start();
                Future<Session> connectFuture = client.connect(this, powermatcherUrl, request);
                LOGGER.info("Connecting to : {}", request);

                // Wait configurable time for remote to respond
                Session newRemoteSession = connectFuture.get(connectTimeout, TimeUnit.SECONDS);

                remoteSession = newRemoteSession;
            } catch (Exception e) {
                LOGGER.error("Unable to connect to remote agent. Reason {}", e);
                remoteSession = null;
            }
        }
    }

    /**
     * {@inheritDoc}
     * 
     * This specific implementation closes the open websocket.
     */
    public synchronized boolean disconnectRemote() {
        // Terminate remote session (if any)
        if (isRemoteConnected()) {
            remoteSession.close(new CloseStatus(0, "Normal disconnect"));
        }

        remoteSession = null;

        // Stop the client
        if (client != null && !client.isStopped()) {
            try {
                client.stop();
            } catch (Exception e) {
                LOGGER.warn("Unable to disconnect, reason: [{}]", e);
                return false;
            }
        }

        // Unregister the MatcherEndpoint with the OSGI runtime, to disable connections locally
        unregisterMatcherEndpoint();

        return true;
    }

    /**
     * {@inheritDoc}
     * 
     * This specific implementation checks to see if the websocket is open.
     */
    @Override
    public boolean isConnected() {
        return super.isConnected() && isRemoteConnected();
    }

    /**
     * Determines whether the Websocket is connected.
     * 
     * @return true when connected, false otherwise
     */
    public boolean isRemoteConnected() {
        return remoteSession != null && remoteSession.isOpen();
    }

    /**
     * 
     * @param statusCode
     * @param reason
     */
    @OnWebSocketClose
    public void onDisconnect(int statusCode, String reason) {
        LOGGER.info("Connection closed: {} - {}", statusCode, reason);
        remoteSession = null;

        // Unregister the MatcherEndpoint with the OSGI runtime, to disable connections locally
        unregisterMatcherEndpoint();
    }

    /**
     * Handle Websocket receive message
     * 
     * @param message
     *            the message received via Websockets
     */
    @OnWebSocketMessage
    public void onMessage(String message) {
        LOGGER.debug("Received message from remote agent {}", message);

        try {
            // Decode the JSON data
            PmJsonSerializer serializer = new PmJsonSerializer();
            PmMessage pmMessage = serializer.deserialize(message);

            // Handle specific message
            if (pmMessage.getPayloadType() == PayloadType.PRICE_UPDATE) {
                // Relay price update to local agents
                PriceUpdate priceUpdate = ModelMapper.mapPriceUpdate((PriceUpdateModel) pmMessage.getPayload());

                SentBidInformation info = sentBids.retrieveAggregatedBid(priceUpdate.getBidNumber());
                publishPrice(priceUpdate.getPrice(), info.getOriginalBid());
            }

            if (pmMessage.getPayloadType() == PayloadType.CLUSTERINFO) {
                // Sync marketbasis and clusterid with local session, for new
                // connections
                ClusterInfoModel clusterInfo = (ClusterInfoModel) pmMessage.getPayload();
                configure(ModelMapper.convertMarketBasis(clusterInfo.getMarketBasis()),
                          clusterInfo.getClusterId(),
                          minTimeBetweenBidUpdates);

                // Register the MatcherEndpoint with the OSGI runtime, to make it available for connections
                registerMatcherEndpoint();
            }
        } catch (JsonSyntaxException e) {
            LOGGER.warn("Unable to understand message from remote agent: {}", message);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void performUpdate(AggregatedBid aggregatedBid) {
        synchronized (sentBids) {
            BidUpdate bidUpdate = publishBid(aggregatedBid);
            if (bidUpdate != null) {
                sentBids.saveBid(aggregatedBid, bidUpdate);
            }
        }
    }

    /**
     * Publish an AggregatedBid via Websockets to {@link AgentProxy}
     * 
     * @param newBid
     *            the bid to publish
     * @return bidupdate containing bidnumber and published bid
     */
    private BidUpdate publishBid(AggregatedBid newBid) {
        if (isConnected() && isRemoteConnected()) {
            try {
                BidUpdate update = new BidUpdate(newBid, bidNumberGenerator.incrementAndGet());

                PmJsonSerializer serializer = new
                                              PmJsonSerializer();
                String message = serializer.serializeBidUpdate(update);
                remoteSession.getRemote().sendString(message);

                return update;
            } catch (IOException e) {
                LOGGER.error("Unable to send new bid to remote agent. Reason {}", e);
            }
        }

        return null;
    }

    /**
     * Register the MatcherEndpoint service
     */
    private void registerMatcherEndpoint() {
        if (matcherEndpointServiceRegistration == null) {
            matcherEndpointServiceRegistration = bundleContext.registerService(MatcherEndpoint.class, this, null);
        }
    }

    /**
     * Unregister the MatcherEndpoint service
     */
    private void unregisterMatcherEndpoint() {
        if (matcherEndpointServiceRegistration != null) {
            matcherEndpointServiceRegistration.unregister();
            matcherEndpointServiceRegistration = null;
        }
    }
}
