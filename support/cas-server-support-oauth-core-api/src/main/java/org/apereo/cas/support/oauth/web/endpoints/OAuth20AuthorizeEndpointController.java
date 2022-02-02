package org.apereo.cas.support.oauth.web.endpoints;

import org.apereo.cas.audit.AuditableContext;
import org.apereo.cas.authentication.Authentication;
import org.apereo.cas.authentication.AuthenticationCredentialsThreadLocalBinder;
import org.apereo.cas.authentication.PreventedException;
import org.apereo.cas.authentication.PrincipalException;
import org.apereo.cas.authentication.principal.Service;
import org.apereo.cas.services.RegisteredServiceAccessStrategyUtils;
import org.apereo.cas.services.UnauthorizedServiceException;
import org.apereo.cas.support.oauth.OAuth20Constants;
import org.apereo.cas.support.oauth.OAuth20GrantTypes;
import org.apereo.cas.support.oauth.services.OAuthRegisteredService;
import org.apereo.cas.support.oauth.util.OAuth20Utils;
import org.apereo.cas.support.oauth.web.response.OAuth20AuthorizationRequest;
import org.apereo.cas.support.oauth.web.response.accesstoken.ext.AccessTokenRequestContext;
import org.apereo.cas.util.LoggingUtils;

import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.jooq.lambda.Unchecked;
import org.pac4j.core.context.JEEContext;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.profile.ProfileManager;
import org.springframework.core.OrderComparator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Objects;
import java.util.Optional;

/**
 * This controller is in charge of responding to the authorize call in OAuth v2 protocol.
 * When the request is valid, this endpoint is protected by a CAS authentication.
 * It returns an OAuth code or directly an access token.
 *
 * @author Jerome Leleu
 * @since 3.5.0
 */
@Slf4j
public class OAuth20AuthorizeEndpointController<T extends OAuth20ConfigurationContext> extends BaseOAuth20Controller<T> {
    public OAuth20AuthorizeEndpointController(final T oAuthConfigurationContext) {
        super(oAuthConfigurationContext);
    }

    /**
     * Handle request via GET.
     *
     * @param request  the request
     * @param response the response
     * @return the model and view
     * @throws Exception the exception
     */
    @GetMapping(path = OAuth20Constants.BASE_OAUTH20_URL + '/' + OAuth20Constants.AUTHORIZE_URL)
    public ModelAndView handleRequest(final HttpServletRequest request,
                                      final HttpServletResponse response) throws Exception {

        ensureSessionReplicationIsAutoconfiguredIfNeedBe(request);

        val context = new JEEContext(request, response);
        val manager = new ProfileManager(context, getConfigurationContext().getSessionStore());

        if (context.getRequestAttribute(OAuth20Constants.ERROR).isPresent()) {
            val mv = getConfigurationContext().getOauthInvalidAuthorizationResponseBuilder().build(context);
            if (!mv.isEmpty() && mv.hasView()) {
                return mv;
            }
        }

        val clientId = OAuth20Utils.getRequestParameter(context, OAuth20Constants.CLIENT_ID)
            .map(String::valueOf)
            .orElse(StringUtils.EMPTY);
        val registeredService = getRegisteredServiceByClientId(clientId);
        RegisteredServiceAccessStrategyUtils.ensureServiceAccessIsAllowed(clientId, registeredService);

        if (isRequestAuthenticated(manager, context, registeredService)) {
            val mv = getConfigurationContext().getConsentApprovalViewResolver().resolve(context, registeredService);
            if (!mv.isEmpty() && mv.hasView()) {
                LOGGER.debug("Redirecting to consent-approval view with model [{}]", mv.getModel());
                return mv;
            }
        }

        return redirectToCallbackRedirectUrl(manager, registeredService, context);
    }

    /**
     * Handle request post.
     *
     * @param request  the request
     * @param response the response
     * @return the model and view
     * @throws Exception the exception
     */
    @PostMapping(path = OAuth20Constants.BASE_OAUTH20_URL + '/' + OAuth20Constants.AUTHORIZE_URL)
    public ModelAndView handleRequestPost(final HttpServletRequest request, final HttpServletResponse response) throws Exception {
        return handleRequest(request, response);
    }

    /**
     * Is the request authenticated?
     *
     * @param manager           the Profile Manager
     * @param context           the context
     * @param registeredService the registered service
     * @return whether the request is authenticated or not
     */
    protected boolean isRequestAuthenticated(final ProfileManager manager, final WebContext context,
                                             final OAuthRegisteredService registeredService) {
        return manager.getProfile().isPresent();
    }

    /**
     * Ensure Session Replication Is Auto-Configured If needed.
     *
     * @param request the request
     */
    protected void ensureSessionReplicationIsAutoconfiguredIfNeedBe(final HttpServletRequest request) {
        val casProperties = getConfigurationContext().getCasProperties();
        val replicationRequested = casProperties.getAuthn().getOauth().isReplicateSessions();
        val cookieAutoconfigured = casProperties.getSessionReplication().getCookie().isAutoConfigureCookiePath();
        if (replicationRequested && cookieAutoconfigured) {
            val contextPath = request.getContextPath();
            val cookiePath = StringUtils.isNotBlank(contextPath) ? contextPath + '/' : "/";

            val path = getConfigurationContext().getOauthDistributedSessionCookieGenerator().getCookiePath();
            if (StringUtils.isBlank(path)) {
                LOGGER.debug("Setting path for cookies for OAuth distributed session cookie generator to: [{}]", cookiePath);
                getConfigurationContext().getOauthDistributedSessionCookieGenerator().setCookiePath(cookiePath);
            } else {
                LOGGER.trace("OAuth distributed cookie domain is [{}] with path [{}]",
                    getConfigurationContext().getOauthDistributedSessionCookieGenerator().getCookieDomain(), path);
            }
        }
    }

    /**
     * Gets registered service by client id.
     *
     * @param clientId the client id
     * @return the registered service by client id
     */
    protected OAuthRegisteredService getRegisteredServiceByClientId(final String clientId) {
        return OAuth20Utils.getRegisteredOAuthServiceByClientId(getConfigurationContext().getServicesManager(), clientId);
    }

    /**
     * Redirect to callback redirect url model and view.
     *
     * @param manager           the manager
     * @param registeredService the registered service
     * @param context           the context
     * @return the model and view
     */
    protected ModelAndView redirectToCallbackRedirectUrl(final ProfileManager manager,
                                                         final OAuthRegisteredService registeredService,
                                                         final JEEContext context) {
        val profile = manager.getProfile().orElseThrow(() -> new IllegalArgumentException("Unable to locate authentication profile"));
        val service = getConfigurationContext().getAuthenticationBuilder()
            .buildService(registeredService, context, false);
        LOGGER.trace("Created service [{}] based on registered service [{}]", service, registeredService);

        val authentication = getConfigurationContext().getAuthenticationBuilder()
            .build(profile, registeredService, context, service);
        LOGGER.trace("Created OAuth authentication [{}] for service [{}]", authentication, service);

        try {
            AuthenticationCredentialsThreadLocalBinder.bindCurrent(authentication);
            val audit = AuditableContext.builder()
                .service(service)
                .authentication(authentication)
                .registeredService(registeredService)
                .build();
            val accessResult = getConfigurationContext().getRegisteredServiceAccessStrategyEnforcer().execute(audit);
            accessResult.throwExceptionIfNeeded();
        } catch (final UnauthorizedServiceException | PrincipalException e) {
            LoggingUtils.error(LOGGER, e);
            return OAuth20Utils.produceUnauthorizedErrorView();
        }

        val modelAndView = buildAuthorizationForRequest(registeredService, context, service, authentication);
        if (modelAndView != null && modelAndView.hasView()) {
            return modelAndView;
        }
        LOGGER.trace("No explicit view was defined as part of the authorization response");
        return null;
    }

    /**
     * Build callback url for request string.
     *
     * @param registeredService the registered service
     * @param context           the context
     * @param service           the service
     * @param authentication    the authentication
     * @return the model and view
     */
    protected ModelAndView buildAuthorizationForRequest(
        final OAuthRegisteredService registeredService,
        final JEEContext context,
        final Service service,
        final Authentication authentication) {

        val registeredBuilders = getConfigurationContext().getOauthAuthorizationResponseBuilders().getObject();

        val authzRequest = registeredBuilders
            .stream()
            .sorted(OrderComparator.INSTANCE)
            .map(builder -> builder.toAuthorizationRequest(context, authentication, service, registeredService))
            .filter(Objects::nonNull)
            .filter(Optional::isPresent)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unable to build authorization request"))
            .get()
            .build();

        val payload = Optional.ofNullable(authzRequest.getAccessTokenRequest())
            .orElseGet(Unchecked.supplier(() -> prepareAccessTokenRequestContext(authzRequest,
                registeredService, context, service, authentication)));

        return registeredBuilders
            .stream()
            .sorted(OrderComparator.INSTANCE)
            .filter(b -> b.supports(authzRequest))
            .findFirst()
            .map(Unchecked.function(builder -> {
                if (authzRequest.isSingleSignOnSessionRequired() && payload.getTicketGrantingTicket() == null) {
                    val message = String.format("Missing ticket-granting-ticket for client id [%s] and service [%s]",
                        authzRequest.getClientId(), registeredService.getName());
                    LOGGER.error(message);
                    return OAuth20Utils.produceErrorView(new PreventedException(message));
                }
                return builder.build(payload);
            }))
            .orElseGet(() -> OAuth20Utils.produceErrorView(new PreventedException("Could not build the callback response")));
    }

    /**
     * Build access token request context.
     *
     * @param authzRequest      the authz request
     * @param registeredService the registered service
     * @param context           the context
     * @param service           the service
     * @param authentication    the authentication
     * @return the access token request context
     * @throws Exception the exception
     */
    protected AccessTokenRequestContext prepareAccessTokenRequestContext(
        final OAuth20AuthorizationRequest authzRequest,
        final OAuthRegisteredService registeredService,
        final JEEContext context,
        final Service service,
        final Authentication authentication) throws Exception {

        var payloadBuilder = AccessTokenRequestContext.builder();
        if (authzRequest.isSingleSignOnSessionRequired()) {
            val tgt = getConfigurationContext().fetchTicketGrantingTicketFrom(context);
            payloadBuilder = payloadBuilder.ticketGrantingTicket(tgt);
        }
        val redirectUri = OAuth20Utils.getRequestParameter(context, OAuth20Constants.REDIRECT_URI)
            .map(String::valueOf)
            .orElse(StringUtils.EMPTY);
        val grantType = context.getRequestParameter(OAuth20Constants.GRANT_TYPE)
            .map(String::valueOf)
            .orElseGet(OAuth20GrantTypes.AUTHORIZATION_CODE::getType)
            .toUpperCase();
        val scopes = OAuth20Utils.parseRequestScopes(context);
        val codeChallenge = context.getRequestParameter(OAuth20Constants.CODE_CHALLENGE)
            .map(String::valueOf).orElse(StringUtils.EMPTY);
        val codeChallengeMethod = context.getRequestParameter(OAuth20Constants.CODE_CHALLENGE_METHOD)
            .map(String::valueOf).orElse(StringUtils.EMPTY)
            .toUpperCase();

        val userProfile = OAuth20Utils.getAuthenticatedUserProfile(context, getConfigurationContext().getSessionStore());
        val claims = OAuth20Utils.parseRequestClaims(context);
        val holder = payloadBuilder
            .service(service)
            .authentication(authentication)
            .registeredService(registeredService)
            .grantType(OAuth20GrantTypes.valueOf(grantType))
            .responseType(OAuth20Utils.getResponseType(context))
            .codeChallenge(codeChallenge)
            .codeChallengeMethod(codeChallengeMethod)
            .scopes(scopes)
            .clientId(authzRequest.getClientId())
            .redirectUri(redirectUri)
            .userProfile(userProfile)
            .claims(claims)
            .responseMode(OAuth20Utils.getResponseModeType(context))
            .build();
        context.getRequestParameters().keySet()
            .forEach(key -> context.getRequestParameter(key).ifPresent(value -> holder.getParameters().put(key, value)));
        LOGGER.debug("Building authorization response for grant type [{}] with scopes [{}] for client id [{}]",
            grantType, scopes, authzRequest.getClientId());
        return holder;
    }


}
