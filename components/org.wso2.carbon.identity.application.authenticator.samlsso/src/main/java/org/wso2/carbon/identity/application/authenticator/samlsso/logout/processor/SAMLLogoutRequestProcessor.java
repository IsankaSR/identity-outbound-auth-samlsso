/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.application.authenticator.samlsso.logout.processor;

import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.saml.saml2.core.LogoutRequest;
import org.opensaml.saml.saml2.core.LogoutResponse;
import org.wso2.carbon.identity.application.authentication.framework.cache.AuthenticationRequestCacheEntry;
import org.wso2.carbon.identity.application.authentication.framework.inbound.FrameworkLogoutResponse;
import org.wso2.carbon.identity.application.authentication.framework.inbound.FrameworkRuntimeException;
import org.wso2.carbon.identity.application.authentication.framework.inbound.IdentityMessageContext;
import org.wso2.carbon.identity.application.authentication.framework.inbound.IdentityProcessor;
import org.wso2.carbon.identity.application.authentication.framework.inbound.IdentityRequest;
import org.wso2.carbon.identity.application.authentication.framework.inbound.InboundUtil;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticationRequest;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkUtils;
import org.wso2.carbon.identity.application.authenticator.samlsso.exception.SAMLSSOException;
import org.wso2.carbon.identity.application.authenticator.samlsso.logout.context.SAMLMessageContext;
import org.wso2.carbon.identity.application.authenticator.samlsso.logout.dao.SessionInfoDAO;
import org.wso2.carbon.identity.application.authenticator.samlsso.logout.exception.SAMLLogoutException;
import org.wso2.carbon.identity.application.authenticator.samlsso.logout.request.SAMLLogoutRequest;
import org.wso2.carbon.identity.application.authenticator.samlsso.logout.util.SAMLLogoutUtil;
import org.wso2.carbon.identity.application.authenticator.samlsso.logout.validators.LogoutRequestValidator;
import org.wso2.carbon.identity.application.authenticator.samlsso.util.SSOConstants;
import org.wso2.carbon.identity.application.authenticator.samlsso.util.SSOUtils;
import org.wso2.carbon.identity.application.common.model.IdentityProvider;
import org.wso2.carbon.identity.application.common.util.IdentityApplicationConstants;
import org.wso2.carbon.identity.application.mgt.ApplicationConstants;
import org.wso2.carbon.identity.base.IdentityConstants;
import org.wso2.carbon.identity.core.ServiceURLBuilder;
import org.wso2.carbon.identity.core.URLBuilderException;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.idp.mgt.IdentityProviderManagementException;
import org.wso2.carbon.idp.mgt.IdentityProviderManager;
import org.wso2.carbon.registry.core.utils.UUIDGenerator;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * This class processes the SAML single logout request from the federated IdP.
 */
public class SAMLLogoutRequestProcessor extends IdentityProcessor {

    private static final Log log = LogFactory.getLog(SAMLLogoutRequestProcessor.class);

    @Override
    public boolean canHandle(IdentityRequest identityRequest) {

        return (identityRequest instanceof SAMLLogoutRequest);
    }

    /**
     * Processes the authentication request according to SAML SSO Web Browser Specification.
     *
     * @param identityRequest {@link IdentityRequest} Identity Request of SAML Logout Request type.
     * @return FrameworkLogoutResponse.FrameworkLogoutResponseBuilder instance.
     * @throws SAMLLogoutException Error when processing the Logout Request.
     */
    @Override
    public FrameworkLogoutResponse.FrameworkLogoutResponseBuilder process(IdentityRequest identityRequest)
            throws SAMLLogoutException {

        SAMLMessageContext<String, String> samlMessageContext = new SAMLMessageContext<>(identityRequest,
                new HashMap<>());

        try {
            XMLObject samlRequest;
            if (samlMessageContext.getSAMLLogoutRequest().isPost()) {
                samlRequest = SSOUtils.unmarshall(SSOUtils.decodeForPost(identityRequest.getParameter
                        (SSOConstants.HTTP_POST_PARAM_SAML2_AUTH_REQ)));
            } else {
                samlRequest = SSOUtils.unmarshall(SSOUtils.decode(identityRequest.getParameter
                        (SSOConstants.HTTP_POST_PARAM_SAML2_AUTH_REQ)));
            }

            LogoutRequest logoutRequest;
            if (samlRequest instanceof LogoutRequest) {
                logoutRequest = (LogoutRequest) samlRequest;
                samlMessageContext.setValidStatus(true);
            } else {
                samlMessageContext.setValidStatus(false);
                throw new SAMLLogoutException("Invalid Single Logout SAML Request");
            }

            samlMessageContext.setIdPSessionID(SAMLLogoutUtil.getSessionIndex(logoutRequest));
            if (StringUtils.isNotBlank(samlMessageContext.getIdPSessionID())) {
                populateContextWithSessionDetails(samlMessageContext);
            }

            if (!Boolean.parseBoolean(samlMessageContext.getFedIdPConfigs().get(IdentityApplicationConstants.
                    Authenticator.SAML2SSO.IS_SLO_REQUEST_ACCEPTED))) {
                throw new SAMLLogoutException("Single logout requests from the federated IdP: "
                        + samlMessageContext.getFederatedIdP().getIdentityProviderName() + " are not accepted");
            }

            LogoutRequestValidator logoutRequestValidator = new LogoutRequestValidator(samlMessageContext);
            if (logoutRequestValidator.isValidate(logoutRequest)) {
                LogoutResponse logoutResp = SAMLLogoutUtil.buildResponse(samlMessageContext, logoutRequest.getID(),
                        SSOConstants.StatusCodes.SUCCESS_CODE, null);
                samlMessageContext.setResponse(SSOUtils.encode(SSOUtils.marshall(logoutResp)));
                samlMessageContext.setAcsUrl(logoutResp.getDestination());
            }
            return buildResponseForFrameworkLogout(samlMessageContext);

        } catch (SAMLSSOException e) {
            throw new SAMLLogoutException("Error when processing the Logout Request.", e);
        }
    }

    /**
     * Build the authentication request for the framework logout.
     *
     * @param samlMessageContext {@link SAMLMessageContext} object which holds details on logout flow.
     * @return FrameworkLogoutResponse.FrameworkLogoutResponseBuilder instance.
     */
    private FrameworkLogoutResponse.FrameworkLogoutResponseBuilder buildResponseForFrameworkLogout
    (SAMLMessageContext<String, String> samlMessageContext) {

        IdentityRequest identityRequest = samlMessageContext.getRequest();
        String callback = getCallbackPath(samlMessageContext);
        String type = getType(samlMessageContext);
        Map<String, String[]> parameterMap = identityRequest.getParameterMap();

        AuthenticationRequest authenticationRequest = new AuthenticationRequest();
        authenticationRequest.appendRequestQueryParams(parameterMap);
        if (identityRequest.getHeaderMap() != null) {
            identityRequest.getHeaderMap().forEach(authenticationRequest::addHeader);
        }
        authenticationRequest.setTenantDomain(samlMessageContext.getTenantDomain());
        authenticationRequest.setRelyingParty(getRelyingPartyId(samlMessageContext));
        authenticationRequest.setType(type);
        try {
            authenticationRequest.setCommonAuthCallerPath(URLEncoder.encode(callback, StandardCharsets.UTF_8.name()));
        } catch (UnsupportedEncodingException e) {
            throw FrameworkRuntimeException.error("Error occurred while URL encoding callback path: " +
                    callback, e);
        }
        authenticationRequest.addRequestQueryParam(FrameworkConstants.LOGOUT, new String[]{IdentityConstants.TRUE});
        authenticationRequest.addRequestQueryParam(FrameworkConstants.AnalyticsAttributes.SESSION_ID,
                new String[]{samlMessageContext.getSessionID()});

        AuthenticationRequestCacheEntry authRequest = new AuthenticationRequestCacheEntry(authenticationRequest);
        String sessionDataKey = UUIDGenerator.generateUUID();
        authRequest.setValidityPeriod(TimeUnit.MINUTES.toNanos(IdentityUtil.getOperationCleanUpTimeout()));
        FrameworkUtils.addAuthenticationRequestToCache(sessionDataKey, authRequest);

        InboundUtil.addContextToCache(sessionDataKey, samlMessageContext);

        FrameworkLogoutResponse.FrameworkLogoutResponseBuilder responseBuilder =
                new FrameworkLogoutResponse.FrameworkLogoutResponseBuilder(samlMessageContext);
        responseBuilder.setContextKey(sessionDataKey);
        responseBuilder.setCallbackPath(callback);
        responseBuilder.setAuthType(type);

        String commonAuthURL;
        try {
            commonAuthURL = ServiceURLBuilder.create().addPath(IdentityApplicationConstants.COMMONAUTH).build().
                    getAbsolutePublicURL();
        } catch (URLBuilderException e) {
            throw FrameworkRuntimeException.error("Error while building commonauth URL.", e);
        }
        responseBuilder.setRedirectURL(commonAuthURL);
        return responseBuilder;
    }

    /**
     * Populate SAMLMessageContext with session details of the SAML index.
     *
     * @param samlMessageContext {@link SAMLMessageContext} object which has details on logout flow.
     * @throws SAMLLogoutException Error while retrieving the session details.
     */
    private void populateContextWithSessionDetails(SAMLMessageContext<String, String> samlMessageContext)
            throws SAMLLogoutException {

        SessionInfoDAO sessionInfoDAO = new SessionInfoDAO();
        String tenantDomain = samlMessageContext.getSAMLLogoutRequest().getTenantDomain();
        Map<String, String> sessionDetails = sessionInfoDAO.getSessionDetails
                (samlMessageContext.getIdPSessionID());
        if ( MapUtils.isNotEmpty(sessionDetails)) {
            if (StringUtils.isNotBlank(tenantDomain)) {
                samlMessageContext.setTenantDomain(tenantDomain);
            } else {
                samlMessageContext.setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
            }
            IdentityProvider identityProvider;
            try {
                identityProvider = IdentityProviderManager.getInstance().getIdPByName(
                        sessionDetails.get(ApplicationConstants.IDP_NAME), samlMessageContext.getTenantDomain());
            } catch (IdentityProviderManagementException e) {
                throw new SAMLLogoutException("Error when getting the Identity Provider by IdP name: "
                        + sessionDetails.get(ApplicationConstants.IDP_NAME) + "with tenant domain: "
                            + samlMessageContext.getTenantDomain(), e);
            }
            samlMessageContext.setSessionID(sessionDetails.get(FrameworkConstants.AnalyticsAttributes.SESSION_ID));
            samlMessageContext.setFederatedIdP(identityProvider);
            samlMessageContext.setFedIdPConfigs(SAMLLogoutUtil.getFederatedIdPConfigs(identityProvider));
        }
    }

    @Override
    public String getType(IdentityMessageContext context) {

        return IdentityConstants.ServerConfig.SAMLSSO;
    }

    @Override
    public String getCallbackPath(IdentityMessageContext context) {

        return SSOConstants.SAML_SLO_URL;
    }

    @Override
    public String getRelyingPartyId() {

        return null;
    }

    @Override
    public String getRelyingPartyId(IdentityMessageContext context) {

        return null;
    }
}
