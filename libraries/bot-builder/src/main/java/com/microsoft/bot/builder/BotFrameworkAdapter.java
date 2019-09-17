// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.bot.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.BaseEncoding;
import com.microsoft.bot.connector.*;
import com.microsoft.bot.connector.authentication.*;
import com.microsoft.bot.connector.rest.RestConnectorClient;
import com.microsoft.bot.connector.rest.RestOAuthClient;
import com.microsoft.bot.schema.*;
import com.microsoft.rest.retry.RetryStrategy;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.StringUtils;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.concurrent.CompletableFuture.completedFuture;

/**
 * A bot adapter that can connect a bot to a service endpoint.
 * The bot adapter encapsulates authentication processes and sends
 * activities to and receives activities from the Bot Connector Service. When your
 * bot receives an activity, the adapter creates a context object, passes it to your
 * bot's application logic, and sends responses back to the user's channel.
 * <p>Use {@link #use(Middleware)} to add {@link Middleware} objects
 * to your adapter’s middleware collection. The adapter processes and directs
 * incoming activities in through the bot middleware pipeline to your bot’s logic
 * and then back out again. As each activity flows in and out of the bot, each piece
 * of middleware can inspect or act upon the activity, both before and after the bot
 * logic runs.</p>
 * <p>
 * {@link TurnContext}
 * {@link Activity}
 * {@link Bot}
 * {@link Middleware}
 */
public class BotFrameworkAdapter extends BotAdapter {
    private final static String InvokeResponseKey = "BotFrameworkAdapter.InvokeResponse";
    private final static String BotIdentityKey = "BotIdentity";

    private final CredentialProvider credentialProvider;
    private ChannelProvider channelProvider;
    private AuthenticationConfiguration authConfiguration;
    private final RetryStrategy connectorClientRetryStrategy;
    private Map<String, MicrosoftAppCredentials> appCredentialMap = new ConcurrentHashMap<String, MicrosoftAppCredentials>();

    /**
     * Initializes a new instance of the {@link BotFrameworkAdapter} class,
     * using a credential provider.
     *
     * @param withCredentialProvider The credential provider.
     */
    public BotFrameworkAdapter(CredentialProvider withCredentialProvider) {
        this(withCredentialProvider, null, null, null);
    }

    /**
     * Initializes a new instance of the {@link BotFrameworkAdapter} class,
     * using a credential provider.
     *
     * @param withCredentialProvider The credential provider.
     * @param withChannelProvider The channel provider.
     * @param withRetryStrategy Retry policy for retrying HTTP operations.
     * @param withMiddleware The middleware to initially add to the adapter.
     */
    public BotFrameworkAdapter(CredentialProvider withCredentialProvider,
                               ChannelProvider withChannelProvider,
                               RetryStrategy withRetryStrategy,
                               Middleware withMiddleware) {

        this(withCredentialProvider,
             new AuthenticationConfiguration(),
             withChannelProvider,
             withRetryStrategy,
             withMiddleware);
    }

    /**
     * Initializes a new instance of the {@link BotFrameworkAdapter} class,
     * using a credential provider.
     *
     * @param withCredentialProvider The credential provider.
     * @param withAuthConfig The authentication configuration.
     * @param withChannelProvider The channel provider.
     * @param withRetryStrategy Retry policy for retrying HTTP operations.
     * @param withMiddleware The middleware to initially add to the adapter.
     */
    public BotFrameworkAdapter(CredentialProvider withCredentialProvider,
                               AuthenticationConfiguration withAuthConfig,
                               ChannelProvider withChannelProvider,
                               RetryStrategy withRetryStrategy,
                               Middleware withMiddleware) {

        if (withCredentialProvider == null) {
            throw new IllegalArgumentException("CredentialProvider cannot be null");
        }

        if (withAuthConfig == null) {
            throw new IllegalArgumentException("AuthenticationConfiguration cannot be null");
        }

        credentialProvider = withCredentialProvider;
        channelProvider = withChannelProvider;
        connectorClientRetryStrategy = withRetryStrategy;
        authConfiguration = withAuthConfig;

        // Relocate the tenantId field used by MS Teams to a new location (from channelData to conversation)
        // This will only occur on activities from teams that include tenant info in channelData but NOT in
        // conversation, thus should be future friendly.  However, once the transition is complete. we can
        // remove this.
        use(new TenantIdWorkaroundForTeamsMiddleware());

        if (withMiddleware != null) {
            use(withMiddleware);
        }
    }

    /**
     * Sends a proactive message from the bot to a conversation.
     *
     * <p>Call this method to proactively send a message to a conversation.
     * Most channels require a user to initiate a conversation with a bot
     * before the bot can send activities to the user.</p>
     * <p>This overload differers from the Node implementation by requiring the BotId to be
     * passed in. The .Net code allows multiple bots to be hosted in a single adapter which
     * isn't something supported by Node.</p>
     *
     * {@link #processActivity(String, Activity, BotCallbackHandler)}
     * {@link BotAdapter#runPipeline(TurnContext, BotCallbackHandler)}
     *
     * @param botAppId  The application ID of the bot. This is the appId returned by Portal registration, and is
     *                  generally found in the "MicrosoftAppId" parameter in appSettings.json.
     * @param reference A reference to the conversation to continue.
     * @param callback  The method to call for the resulting bot turn.
     * @return A task that represents the work queued to execute.
     * @throws IllegalArgumentException botAppId, reference, or callback is null.
     */
    @Override
    public CompletableFuture<Void> continueConversation(String botAppId,
                                                        ConversationReference reference,
                                                        BotCallbackHandler callback) {
        if (StringUtils.isEmpty(botAppId)) {
            throw new IllegalArgumentException("botAppId");
        }

        if (reference == null) {
            throw new IllegalArgumentException("reference");
        }

        if (callback == null) {
            throw new IllegalArgumentException("callback");
        }

        TurnContextImpl context = new TurnContextImpl(this, reference.getContinuationActivity());

        // Hand craft Claims Identity.
        HashMap<String, String> claims = new HashMap<String, String>() {{
            put(AuthenticationConstants.AUDIENCE_CLAIM, botAppId);
            put(AuthenticationConstants.APPID_CLAIM, botAppId);
        }};
        ClaimsIdentity claimsIdentity = new ClaimsIdentity("ExternalBearer", claims);

        context.getTurnState().add(TurnContextStateNames.BOT_IDENTITY, claimsIdentity);

        return createConnectorClient(reference.getServiceUrl(), claimsIdentity)
            .thenCompose(connectorClient -> {
                context.getTurnState().add(TurnContextStateNames.CONNECTOR_CLIENT, connectorClient);
                return runPipeline(context, callback);
            });
    }

    /**
     * Adds middleware to the adapter's pipeline.
     *
     * Middleware is added to the adapter at initialization time.
     * For each turn, the adapter calls middleware in the order in which you added it.
     *
     * @param middleware The middleware to add.
     * @return The updated adapter object.
     */
    public BotFrameworkAdapter use(Middleware middleware) {
        super.middlewareSet.use(middleware);
        return this;
    }

    /**
     * Creates a turn context and runs the middleware pipeline for an incoming activity.
     *
     * @param authHeader The HTTP authentication header of the request.
     * @param activity   The incoming activity.
     * @param callback   The code to run at the end of the adapter's middleware
     *                   pipeline.
     * @return A task that represents the work queued to execute. If the activity type
     * was 'Invoke' and the corresponding key (channelId + activityId) was found
     * then an InvokeResponse is returned, otherwise null is returned.
     * @throws IllegalArgumentException Activity is null.
     */
    public CompletableFuture<InvokeResponse> processActivity(String authHeader,
                                                             Activity activity,
                                                             BotCallbackHandler callback) {
        BotAssert.activityNotNull(activity);

        return JwtTokenValidation.authenticateRequest(activity,
            authHeader, credentialProvider, channelProvider, authConfiguration)

            .thenCompose(claimsIdentity -> processActivity(claimsIdentity, activity, callback));
    }

    /**
     * Creates a turn context and runs the middleware pipeline for an incoming activity.
     *
     * @param identity A {@link ClaimsIdentity} for the request.
     * @param activity The incoming activity.
     * @param callback The code to run at the end of the adapter's middleware
     *                 pipeline.
     * @return A task that represents the work queued to execute. If the activity type
     * was 'Invoke' and the corresponding key (channelId + activityId) was found
     * then an InvokeResponse is returned, otherwise null is returned.
     * @throws IllegalArgumentException Activity is null.
     */
    public CompletableFuture<InvokeResponse> processActivity(ClaimsIdentity identity,
                                                             Activity activity,
                                                             BotCallbackHandler callback) {
        BotAssert.activityNotNull(activity);

        TurnContextImpl context = new TurnContextImpl(this, activity);
        context.getTurnState().add(TurnContextStateNames.BOT_IDENTITY, identity);

        return createConnectorClient(activity.getServiceUrl(), identity)

            .thenCompose(connectorClient -> {
                    context.getTurnState().add(TurnContextStateNames.CONNECTOR_CLIENT, connectorClient);

                    return runPipeline(context, callback);
                })

            .thenCompose(result -> {
                // Handle Invoke scenarios, which deviate from the request/response model in that
                // the Bot will return a specific body and return code.
                if (activity.isType(ActivityTypes.INVOKE)) {
                    Activity invokeResponse = context.getTurnState().get(InvokeResponseKey);
                    if (invokeResponse == null) {
                        throw new IllegalStateException("Bot failed to return a valid 'invokeResponse' activity.");
                    } else {
                        return completedFuture((InvokeResponse) invokeResponse.getValue());
                    }
                }

                // For all non-invoke scenarios, the HTTP layers above don't have to mess
                // with the Body and return codes.
                return CompletableFuture.completedFuture(null);
            });
    }

    /**
     * Sends activities to the conversation.
     *
     * @param context    The context object for the turn.
     * @param activities The activities to send.
     * @return A task that represents the work queued to execute.
     * If the activities are successfully sent, the task result contains
     * an array of {@link ResourceResponse} objects containing the IDs that
     * the receiving channel assigned to the activities.
     *
     * {@link TurnContext#onSendActivities(SendActivitiesHandler)}
     */
    @SuppressWarnings("checkstyle:EmptyBlock")
    @Override
    public CompletableFuture<ResourceResponse[]> sendActivities(TurnContext context, Activity[] activities) {
        if (context == null) {
            throw new IllegalArgumentException("context");
        }

        if (activities == null) {
            throw new IllegalArgumentException("activities");
        }

        if (activities.length == 0) {
            throw new IllegalArgumentException("Expecting one or more activities, but the array was empty.");
        }

        return CompletableFuture.supplyAsync(() -> {
            ResourceResponse[] responses = new ResourceResponse[activities.length];

            /*
             * NOTE: we're using for here (vs. foreach) because we want to simultaneously index into the
             * activities array to get the activity to process as well as use that index to assign
             * the response to the responses array and this is the most cost effective way to do that.
             */
            for (int index = 0; index < activities.length; index++) {
                Activity activity = activities[index];
                ResourceResponse response = null;

                if (activity.isType(ActivityTypes.DELAY)) {
                    // The Activity Schema doesn't have a delay type build in, so it's simulated
                    // here in the Bot. This matches the behavior in the Node connector.
                    int delayMs = (int) activity.getValue();
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                    }
                    //await(Task.Delay(delayMs));
                    // No need to create a response. One will be created below.
                } else if (activity.isType(ActivityTypes.INVOKE_RESPONSE)) {
                    context.getTurnState().add(InvokeResponseKey, activity);
                    // No need to create a response. One will be created below.
                } else if (activity.isType(ActivityTypes.TRACE)
                    && !StringUtils.equals(activity.getChannelId(), Channels.EMULATOR)) {
                    // if it is a Trace activity we only send to the channel if it's the emulator.
                } else if (!StringUtils.isEmpty(activity.getReplyToId())) {
                    ConnectorClient connectorClient = context.getTurnState().get(
                        TurnContextStateNames.CONNECTOR_CLIENT);
                    response = connectorClient.getConversations().replyToActivity(activity).join();
                } else {
                    ConnectorClient connectorClient = context.getTurnState().get(
                        TurnContextStateNames.CONNECTOR_CLIENT);
                    response = connectorClient.getConversations().sendToConversation(activity).join();
                }

                // If No response is set, then default to a "simple" response. This can't really be done
                // above, as there are cases where the ReplyTo/SendTo methods will also return null
                // (See below) so the check has to happen here.

                // Note: In addition to the Invoke / Delay / Activity cases, this code also applies
                // with Skype and Teams with regards to typing events.  When sending a typing event in
                // these channels they do not return a RequestResponse which causes the bot to blow up.
                // https://github.com/Microsoft/botbuilder-dotnet/issues/460
                // bug report : https://github.com/Microsoft/botbuilder-dotnet/issues/465
                if (response == null) {
                    response = new ResourceResponse((activity.getId() == null) ? "" : activity.getId());
                }

                responses[index] = response;
            }

            return responses;
        }, ExecutorFactory.getExecutor());
    }

    /**
     * Replaces an existing activity in the conversation.
     *
     * @param context  The context object for the turn.
     * @param activity New replacement activity.
     * @return A task that represents the work queued to execute.
     * If the activity is successfully sent, the task result contains
     * a {@link ResourceResponse} object containing the ID that the receiving
     * channel assigned to the activity.
     * <p>Before calling this, set the ID of the replacement activity to the ID
     * of the activity to replace.</p>
     * {@link TurnContext#onUpdateActivity(UpdateActivityHandler)}
     */
    @Override
    public CompletableFuture<ResourceResponse> updateActivity(TurnContext context, Activity activity) {
        ConnectorClient connectorClient = context.getTurnState().get(TurnContextStateNames.CONNECTOR_CLIENT);
        return connectorClient.getConversations().updateActivity(activity);
    }

    /**
     * Deletes an existing activity in the conversation.
     *
     * @param context   The context object for the turn.
     * @param reference Conversation reference for the activity to delete.
     * @return A task that represents the work queued to execute.
     * {@link TurnContext#onDeleteActivity(DeleteActivityHandler)}
     */
    @Override
    public CompletableFuture<Void> deleteActivity(TurnContext context, ConversationReference reference) {
        ConnectorClient connectorClient = context.getTurnState().get(TurnContextStateNames.CONNECTOR_CLIENT);
        return connectorClient.getConversations().deleteActivity(
            reference.getConversation().getId(), reference.getActivityId());
    }

    /**
     * Deletes a member from the current conversation.
     *
     * @param context  The context object for the turn.
     * @param memberId ID of the member to delete from the conversation
     * @return A task that represents the work queued to execute.
     */
    public CompletableFuture<Void> deleteConversationMember(TurnContextImpl context, String memberId) {
        if (context.getActivity().getConversation() == null) {
            throw new IllegalArgumentException(
                "BotFrameworkAdapter.deleteConversationMember(): missing conversation");
        }

        if (StringUtils.isEmpty(context.getActivity().getConversation().getId())) {
            throw new IllegalArgumentException(
                "BotFrameworkAdapter.deleteConversationMember(): missing conversation.id");
        }

        ConnectorClient connectorClient = context.getTurnState().get(TurnContextStateNames.CONNECTOR_CLIENT);
        String conversationId = context.getActivity().getConversation().getId();
        return connectorClient.getConversations().deleteConversationMember(conversationId, memberId);
    }

    /**
     * Lists the members of a given activity.
     *
     * @param context The context object for the turn.
     * @return List of Members of the activity
     */
    public CompletableFuture<List<ChannelAccount>> getActivityMembers(TurnContextImpl context) {
        return getActivityMembers(context, null);
    }

    /**
     * Lists the members of a given activity.
     *
     * @param context The context object for the turn.
     * @param activityId (Optional) Activity ID to enumerate. If not specified the current activities ID will be used.
     * @return List of Members of the activity
     */
    public CompletableFuture<List<ChannelAccount>> getActivityMembers(TurnContextImpl context, String activityId) {
        // If no activity was passed in, use the current activity.
        if (activityId == null) {
            activityId = context.getActivity().getId();
        }

        if (context.getActivity().getConversation() == null) {
            throw new IllegalArgumentException("BotFrameworkAdapter.GetActivityMembers(): missing conversation");
        }

        if (StringUtils.isEmpty((context.getActivity().getConversation().getId()))) {
            throw new IllegalArgumentException("BotFrameworkAdapter.GetActivityMembers(): missing conversation.id");
        }

        ConnectorClient connectorClient = context.getTurnState().get(TurnContextStateNames.CONNECTOR_CLIENT);
        String conversationId = context.getActivity().getConversation().getId();

        return connectorClient.getConversations().getActivityMembers(conversationId, activityId);
    }

    /**
     * Lists the members of the current conversation.
     *
     * @param context The context object for the turn.
     * @return List of Members of the current conversation
     */
    public CompletableFuture<List<ChannelAccount>> getConversationMembers(TurnContextImpl context) {
        if (context.getActivity().getConversation() == null) {
            throw new IllegalArgumentException("BotFrameworkAdapter.GetActivityMembers(): missing conversation");
        }

        if (StringUtils.isEmpty(context.getActivity().getConversation().getId())) {
            throw new IllegalArgumentException("BotFrameworkAdapter.GetActivityMembers(): missing conversation.id");
        }

        ConnectorClient connectorClient = context.getTurnState().get("ConnectorClient");
        String conversationId = context.getActivity().getConversation().getId();

        return connectorClient.getConversations().getConversationMembers(conversationId);
    }

    /**
     * Lists the Conversations in which this bot has participated for a given channel server. The
     * channel server returns results in pages and each page will include a `continuationToken`
     * that can be used to fetch the next page of results from the server.
     *
     * @param serviceUrl  The URL of the channel server to query.  This can be retrieved
     *                    from `context.activity.serviceUrl`.
     * @param credentials The credentials needed for the Bot to connect to the.services().
     * @return List of Members of the current conversation
     * <p>
     * This overload may be called from outside the context of a conversation, as only the
     * Bot's ServiceUrl and credentials are required.
     */
    public CompletableFuture<ConversationsResult> getConversations(String serviceUrl,
                                                                   MicrosoftAppCredentials credentials) {
        return getConversations(serviceUrl, credentials, null);
    }

    /**
     * Lists the Conversations in which this bot has participated for a given channel server. The
     * channel server returns results in pages and each page will include a `continuationToken`
     * that can be used to fetch the next page of results from the server.
     *
     * This overload may be called from outside the context of a conversation, as only the
     * Bot's ServiceUrl and credentials are required.
     *
     * @param serviceUrl  The URL of the channel server to query.  This can be retrieved
     *                    from `context.activity.serviceUrl`.
     * @param credentials The credentials needed for the Bot to connect to the.services().
     * @param continuationToken The continuation token from the previous page of results.
     * @return List of Members of the current conversation
     */
    public CompletableFuture<ConversationsResult> getConversations(String serviceUrl,
                                                                   MicrosoftAppCredentials credentials,
                                                                   String continuationToken) {
        if (StringUtils.isEmpty(serviceUrl)) {
            throw new IllegalArgumentException("serviceUrl");
        }

        if (credentials == null) {
            throw new IllegalArgumentException("credentials");
        }

        CompletableFuture<ConversationsResult> result = new CompletableFuture<>();

        try {
            ConnectorClient connectorClient = getOrCreateConnectorClient(serviceUrl, credentials);
            return connectorClient.getConversations().getConversations(continuationToken);
        } catch (Throwable t) {
            result.completeExceptionally(t);
            return result;
        }
    }

    /**
     * Lists the Conversations in which this bot has participated for a given channel server. The
     * channel server returns results in pages and each page will include a `continuationToken`
     * that can be used to fetch the next page of results from the server.
     *
     * This overload may be called during standard Activity processing, at which point the Bot's
     * service URL and credentials that are part of the current activity processing pipeline
     * will be used.
     *
     * @param context The context object for the turn.
     * @return List of Members of the current conversation
     */
    public CompletableFuture<ConversationsResult> getConversations(TurnContextImpl context) {
        ConnectorClient connectorClient = context.getTurnState().get(TurnContextStateNames.CONNECTOR_CLIENT);
        return connectorClient.getConversations().getConversations();
    }

    /**
     * Lists the Conversations in which this bot has participated for a given channel server. The
     * channel server returns results in pages and each page will include a `continuationToken`
     * that can be used to fetch the next page of results from the server.
     *
     * This overload may be called during standard Activity processing, at which point the Bot's
     * service URL and credentials that are part of the current activity processing pipeline
     * will be used.
     *
     * @param context The context object for the turn.
     * @param continuationToken The continuation token from the previous page of results.
     * @return List of Members of the current conversation
     */
    public CompletableFuture<ConversationsResult> getConversations(TurnContextImpl context, String continuationToken) {
        ConnectorClient connectorClient = context.getTurnState().get(TurnContextStateNames.CONNECTOR_CLIENT);
        return connectorClient.getConversations().getConversations(continuationToken);
    }

    /**
     * Attempts to retrieve the token for a user that's in a login flow.
     *
     * @param context        Context for the current turn of conversation with the user.
     * @param connectionName Name of the auth connection to use.
     * @param magicCode      (Optional) Optional user entered code to validate.
     * @return Token Response
     */
    public CompletableFuture<TokenResponse> getUserToken(TurnContextImpl context, String connectionName, String magicCode) {
        BotAssert.contextNotNull(context);

        if (context.getActivity().getFrom() == null || StringUtils.isEmpty(context.getActivity().getFrom().getId())) {
            throw new IllegalArgumentException("BotFrameworkAdapter.GetuserToken(): missing from or from.id");
        }

        if (StringUtils.isEmpty(connectionName)) {
            throw new IllegalArgumentException("connectionName");
        }

        CompletableFuture<TokenResponse> result = new CompletableFuture<>();

        try {
            // TODO: getUserToken
//            OAuthClientOld client = createOAuthApiClient(context);
//            return client.getUserToken(context.getActivity().getFrom().getId(), connectionName, magicCode);
            result.completeExceptionally(new NotImplementedException("getUserToken"));
            return result;
        } catch (Throwable t) {
            result.completeExceptionally(t);
            return result;
        }
    }

    /**
     * Get the raw signin link to be sent to the user for signin for a connection name.
     *
     * @param context        Context for the current turn of conversation with the user.
     * @param connectionName Name of the auth connection to use.
     * @return A task that represents the work queued to execute.
     */
    public CompletableFuture<String> getOauthSignInLink(TurnContextImpl context, String connectionName) {
        BotAssert.contextNotNull(context);
        if (StringUtils.isEmpty(connectionName)) {
            throw new IllegalArgumentException("connectionName");
        }

        CompletableFuture<String> result = new CompletableFuture<>();

        try {
            Activity activity = context.getActivity();

            TokenExchangeState tokenExchangeState = new TokenExchangeState() {{
                setConnectionName(connectionName);
                setConversation(new ConversationReference(){{
                    setActivityId(activity.getId());
                    setBot(activity.getRecipient());
                    setChannelId(activity.getChannelId());
                    setConversation(activity.getConversation());
                    setServiceUrl(activity.getServiceUrl());
                    setUser(activity.getFrom());
                }});

                // TODO: on what planet would this ever be valid (from dotnet)?
                //MsAppId = (_credentialProvider as MicrosoftAppCredentials)?.MicrosoftAppId,
            }};

            ObjectMapper mapper = new ObjectMapper();
            String serializedState = mapper.writeValueAsString(tokenExchangeState);
            byte[] encodedState = serializedState.getBytes(StandardCharsets.UTF_8);
            String state = BaseEncoding.base64().encode(encodedState);

            // TODO: getOauthSignInLink
//            ConnectorClient client = getOrCreateConnectorClient(context.getActivity().getServiceUrl());
//            return client.getBotSignIn().getSignInUrl(state);
            result.completeExceptionally(new NotImplementedException("getOauthSignInLink"));
            return result;
        } catch (Throwable t) {
            result.completeExceptionally(t);
            return result;
        }
    }

    /**
     * Get the raw signin link to be sent to the user for signin for a connection name.
     *
     * @param context        Context for the current turn of conversation with the user.
     * @param connectionName Name of the auth connection to use.
     * @param userId The user id that will be associated with the token.
     * @return A task that represents the work queued to execute.
     */
    public CompletableFuture<String> getOauthSignInLink(TurnContextImpl context, String connectionName, String userId) {
        BotAssert.contextNotNull(context);
        if (StringUtils.isEmpty(connectionName)) {
            throw new IllegalArgumentException("connectionName");
        }
        if (StringUtils.isEmpty(userId)) {
            throw new IllegalArgumentException("userId");
        }

        CompletableFuture<String> result = new CompletableFuture<>();

        try {
            TokenExchangeState tokenExchangeState = new TokenExchangeState() {{
                setConnectionName(connectionName);
                setConversation(new ConversationReference(){{
                    setActivityId(null);
                    setBot(new ChannelAccount() {{
                        setRole(RoleTypes.BOT);
                    }});
                    setChannelId(Channels.DIRECTLINE);
                    setConversation(new ConversationAccount());
                    setServiceUrl(null);
                    setUser(new ChannelAccount() {{
                        setRole(RoleTypes.USER);
                        setId(userId);
                    }});
                }});

                // TODO: on what planet would this ever be valid (from dotnet)?
                //MsAppId = (_credentialProvider as MicrosoftAppCredentials)?.MicrosoftAppId,
            }};

            ObjectMapper mapper = new ObjectMapper();
            String serializedState = mapper.writeValueAsString(tokenExchangeState);
            byte[] encodedState = serializedState.getBytes(StandardCharsets.UTF_8);
            String state = BaseEncoding.base64().encode(encodedState);

            // TODO: getOauthSignInLink
//            ConnectorClient client = getOrCreateConnectorClient(context.getActivity().getServiceUrl());
//            return client.getBotSignIn().getSignInUrl(state);
            result.completeExceptionally(new NotImplementedException("getOauthSignInLink"));
            return result;
        } catch (Throwable t) {
            result.completeExceptionally(t);
            return result;
        }
    }

    /**
     * Signs the user out with the token server.
     *
     * @param context        Context for the current turn of conversation with the user.
     * @param connectionName Name of the auth connection to use.
     * @return A task that represents the work queued to execute.
     */
    public CompletableFuture<Void> signOutUser(TurnContextImpl context, String connectionName) {
        BotAssert.contextNotNull(context);
        if (StringUtils.isEmpty(connectionName)) {
            throw new IllegalArgumentException("connectionName");
        }

        CompletableFuture<Void> result = new CompletableFuture<>();

        try {
            ConnectorClient client = getOrCreateConnectorClient(context.getActivity().getServiceUrl());
            // TODO: signoutUser
            //return client.signOutUser(context.getActivity().getFrom().getId(), connectionName);
            result.completeExceptionally(new NotImplementedException("signOutUser"));
            return result;
        } catch (Throwable t) {
            result.completeExceptionally(t);
            return result;
        }
    }

    /**
     * Retrieves the token status for each configured connection for the given user.
     *
     * @param context Context for the current turn of conversation with the user.
     * @param userId The user Id for which token status is retrieved.
     * @return Array of {@link TokenStatus}.
     */
    public CompletableFuture<TokenStatus[]> getTokenStatus(TurnContext context, String userId) {
        // TODO: getTokenStatus
        throw new NotImplementedException("getTokenStatus");
    }

    /**
     * Retrieves Azure Active Directory tokens for particular resources on a configured connection.
     *
     * @param context Context for the current turn of conversation with the user.
     * @param connectionName The name of the Azure Active Directory connection configured with this bot.
     * @param resourceUrls The list of resource URLs to retrieve tokens for.
     * @return Map of resourceUrl to the corresponding {@link TokenResponse}.
     */
    public CompletableFuture<Map<String, TokenResponse>> getAadTokens(TurnContext context,
                                                                      String connectionName,
                                                                      String[] resourceUrls) {
        // TODO: getAadTokens
        throw new NotImplementedException("getAadTokens");
    }

    /**
     * Creates a conversation on the specified channel.
     *
     * To start a conversation, your bot must know its account information
     * and the user's account information on that channel.
     * Most channels only support initiating a direct message (non-group) conversation.
     * <p>The adapter attempts to create a new conversation on the channel, and
     * then sends a {@code conversationUpdate} activity through its middleware pipeline
     * to the {@code callback} method.</p>
     * <p>If the conversation is established with the
     * specified users, the ID of the activity's {@link Activity#getConversation}
     * will contain the ID of the new conversation.</p>
     *
     * @param channelId              The ID for the channel.
     * @param serviceUrl             The channel's service URL endpoint.
     * @param credentials            The application credentials for the bot.
     * @param conversationParameters The conversation information to use to
     *                               create the conversation.
     * @param callback               The method to call for the resulting bot turn.
     * @return A task that represents the work queued to execute.
     */
    public CompletableFuture<Void> createConversation(String channelId,
                                                      String serviceUrl,
                                                      MicrosoftAppCredentials credentials,
                                                      ConversationParameters conversationParameters,
                                                      BotCallbackHandler callback) {
        // Validate serviceUrl - can throw
        // TODO: all these joins are gross
        return CompletableFuture.supplyAsync(() -> {
            //URI uri = new URI(serviceUrl);
            ConnectorClient connectorClient = null;
            try {
                connectorClient = getOrCreateConnectorClient(serviceUrl, credentials);
            } catch (Throwable t) {
                throw new CompletionException(String.format("Bad serviceUrl: %s", serviceUrl), t);
            }

            Conversations conversations = connectorClient.getConversations();
            ConversationResourceResponse conversationResourceResponse =
                conversations.createConversation(conversationParameters).join();

            // Create a event activity to represent the result.
            Activity eventActivity = Activity.createEventActivity();
            eventActivity.setName("CreateConversation");
            eventActivity.setChannelId(channelId);
            eventActivity.setServiceUrl(serviceUrl);
            eventActivity.setId((conversationResourceResponse.getActivityId() != null)
                ? conversationResourceResponse.getActivityId()
                : UUID.randomUUID().toString());
            eventActivity.setConversation(new ConversationAccount(conversationResourceResponse.getId()));
            eventActivity.setRecipient(conversationParameters.getBot());

            TurnContextImpl context = new TurnContextImpl(this, eventActivity);

            HashMap<String, String> claims = new HashMap<String, String>() {{
                put(AuthenticationConstants.AUDIENCE_CLAIM, credentials.getAppId());
                put(AuthenticationConstants.APPID_CLAIM, credentials.getAppId());
                put(AuthenticationConstants.SERVICE_URL_CLAIM, serviceUrl);
            }};
            ClaimsIdentity claimsIdentity = new ClaimsIdentity("anonymous", claims);

            context.getTurnState().add(TurnContextStateNames.BOT_IDENTITY, claimsIdentity);
            context.getTurnState().add(TurnContextStateNames.CONNECTOR_CLIENT, connectorClient);

            return runPipeline(context, callback).join();
        }, ExecutorFactory.getExecutor());
    }

    /**
     * Creates a conversation on the specified channel.
     *
     * To start a conversation, your bot must know its account information
     * and the user's account information on that channel.
     * Most channels only support initiating a direct message (non-group) conversation.
     * <p>The adapter attempts to create a new conversation on the channel, and
     * then sends a {@code conversationUpdate} activity through its middleware pipeline
     * to the {@code callback} method.</p>
     * <p>If the conversation is established with the
     * specified users, the ID of the activity's {@link Activity#getConversation}
     * will contain the ID of the new conversation.</p>
     *
     * @param channelId              The ID for the channel.
     * @param serviceUrl             The channel's service URL endpoint.
     * @param credentials            The application credentials for the bot.
     * @param conversationParameters The conversation information to use to
     *                               create the conversation.
     * @param callback               The method to call for the resulting bot turn.
     * @param reference              A conversation reference that contains the tenant.
     * @return A task that represents the work queued to execute.
     */
    @SuppressWarnings("checkstyle:InnerAssignment")
    public CompletableFuture<Void> createConversation(String channelId,
                                                      String serviceUrl,
                                                      MicrosoftAppCredentials credentials,
                                                      ConversationParameters conversationParameters,
                                                      BotCallbackHandler callback,
                                                      ConversationReference reference) {
        if (reference.getConversation() == null) {
            return CompletableFuture.completedFuture(null);
        }

        if (!StringUtils.isEmpty(reference.getConversation().getTenantId())) {
            // TODO: Not sure this is doing the same as dotnet.  Test.
            // Putting tenantId in channelData is a temporary solution while we wait for the Teams API to be updated
            conversationParameters.setChannelData(new Object() {
                private String tenantId;
            }.tenantId = reference.getConversation().getTenantId());

            conversationParameters.setTenantId(reference.getConversation().getTenantId());
        }

        return createConversation(channelId, serviceUrl, credentials, conversationParameters, callback);
    }

    /**
     * Creates an OAuth client for the bot.
     *
     * @param turnContext The context object for the current turn.
     * @return An OAuth client for the bot.
     */
    protected CompletableFuture<OAuthClient> createOAuthClient(TurnContext turnContext) {
        return CompletableFuture.supplyAsync(() -> {
            if (!OAuthClientConfig.emulateOAuthCards
                && StringUtils.equalsIgnoreCase(turnContext.getActivity().getChannelId(), Channels.EMULATOR)
                && credentialProvider.isAuthenticationDisabled().join()) {

                OAuthClientConfig.emulateOAuthCards = true;
            }

            ConnectorClient connectorClient = turnContext.getTurnState().get(TurnContextStateNames.CONNECTOR_CLIENT);
            if (connectorClient == null) {
                throw new RuntimeException("An ConnectorClient is required in TurnState for this operation.");
            }

            if (OAuthClientConfig.emulateOAuthCards) {
                // do not join task - we want this to run in the background.
                OAuthClient oAuthClient = new RestOAuthClient(
                    turnContext.getActivity().getServiceUrl(), connectorClient.getRestClient().credentials());
                OAuthClientConfig.sendEmulateOAuthCards(oAuthClient, OAuthClientConfig.emulateOAuthCards);
                return oAuthClient;
            }

            return new RestOAuthClient(OAuthClientConfig.OAUTHENDPOINT, connectorClient.getRestClient().credentials());
        });
    }

    /**
     * Creates the connector client asynchronous.
     *
     * @param serviceUrl     The service URL.
     * @param claimsIdentity The claims identity.
     * @return ConnectorClient instance.
     * @throws UnsupportedOperationException ClaimsIdentity cannot be null. Pass Anonymous ClaimsIdentity if
     * authentication is turned off.
     */
    private CompletableFuture<ConnectorClient> createConnectorClient(String serviceUrl, ClaimsIdentity claimsIdentity) {
        return CompletableFuture.supplyAsync(() -> {
            if (claimsIdentity == null) {
                throw new UnsupportedOperationException(
                    "ClaimsIdentity cannot be null. Pass Anonymous ClaimsIdentity if authentication is turned off.");
            }

            // For requests from channel App Id is in Audience claim of JWT token. For emulator it is in AppId claim.
            // For unauthenticated requests we have anonymous identity provided auth is disabled.
            if (claimsIdentity.claims() == null) {
                try {
                    return getOrCreateConnectorClient(serviceUrl);
                } catch (MalformedURLException | URISyntaxException e) {
                    throw new IllegalArgumentException(String.format("Invalid Service URL: %s", serviceUrl), e);
                }
            }

            // For Activities coming from Emulator AppId claim contains the Bot's AAD AppId.
            // For anonymous requests (requests with no header) appId is not set in claims.

            Map.Entry<String, String> botAppIdClaim = claimsIdentity.claims().entrySet().stream()
                .filter(claim -> claim.getKey() == AuthenticationConstants.AUDIENCE_CLAIM)
                .findFirst()
                .orElse(null);
            if (botAppIdClaim == null) {
                botAppIdClaim = claimsIdentity.claims().entrySet().stream()
                    .filter(claim -> claim.getKey() == AuthenticationConstants.APPID_CLAIM)
                    .findFirst()
                    .orElse(null);
            }

            try {
                if (botAppIdClaim != null) {
                    String botId = botAppIdClaim.getValue();
                    MicrosoftAppCredentials appCredentials = this.getAppCredentials(botId).join();

                    return this.getOrCreateConnectorClient(serviceUrl, appCredentials);
                } else {
                    return this.getOrCreateConnectorClient(serviceUrl);
                }
            } catch (MalformedURLException | URISyntaxException e) {
                e.printStackTrace();
                throw new CompletionException(String.format("Bad Service URL: %s", serviceUrl), e);
            }
        }, ExecutorFactory.getExecutor());
    }

    private ConnectorClient getOrCreateConnectorClient(String serviceUrl)
        throws MalformedURLException, URISyntaxException {

        return getOrCreateConnectorClient(serviceUrl, null);
    }

    private ConnectorClient getOrCreateConnectorClient(String serviceUrl, MicrosoftAppCredentials appCredentials)
        throws MalformedURLException, URISyntaxException {

        RestConnectorClient connectorClient = null;
        if (appCredentials != null) {
            connectorClient = new RestConnectorClient(
                new URI(serviceUrl).toURL().toString(), appCredentials);
        } else  {
            connectorClient = new RestConnectorClient(
                new URI(serviceUrl).toURL().toString(), MicrosoftAppCredentials.empty());
        }

        if (this.connectorClientRetryStrategy != null) {
            connectorClient.setRestRetryStrategy(this.connectorClientRetryStrategy);
        }

        return connectorClient;
    }

    /**
     * Gets the application credentials. App Credentials are cached so as to ensure we are not refreshing
     * token everytime.
     *
     * @param appId The application identifier (AAD Id for the bot).
     * @return App credentials.
     */
    private CompletableFuture<MicrosoftAppCredentials> getAppCredentials(String appId) {
        if (appId == null) {
            return CompletableFuture.completedFuture(MicrosoftAppCredentials.empty());
        }

        if (appCredentialMap.containsKey(appId)) {
            return CompletableFuture.completedFuture(appCredentialMap.get(appId));
        }

        return credentialProvider.getAppPassword(appId)
            .thenApply(appPassword -> {
                MicrosoftAppCredentials appCredentials = new MicrosoftAppCredentials(appId, appPassword);
                appCredentialMap.put(appId, appCredentials);
                return appCredentials;
            });
    }

    /**
     * Middleware to assign tenantId from channelData to Conversation.TenantId.
     *
     * MS Teams currently sends the tenant ID in channelData and the correct behavior is to expose this
     * value in Activity.Conversation.TenantId.
     *
     * This code copies the tenant ID from channelData to Activity.Conversation.TenantId.
     * Once MS Teams sends the tenantId in the Conversation property, this middleware can be removed.
     */
    private static class TenantIdWorkaroundForTeamsMiddleware implements Middleware {
        @Override
        public CompletableFuture<Void> onTurn(TurnContext turnContext, NextDelegate next) {
            if (StringUtils.equalsIgnoreCase(turnContext.getActivity().getChannelId(), Channels.MSTEAMS)
                && turnContext.getActivity().getConversation() != null
                && StringUtils.isEmpty(turnContext.getActivity().getConversation().getTenantId())) {

                JsonNode teamsChannelData = new ObjectMapper().valueToTree(turnContext.getActivity().getChannelData());
                if (teamsChannelData != null
                    && teamsChannelData.has("tenant")
                    && teamsChannelData.get("tenant").has("id")) {

                    turnContext.getActivity().getConversation().setTenantId(
                        teamsChannelData.get("tenant").get("id").asText());
                }
            }

            return next.next();
        }
    }
}
