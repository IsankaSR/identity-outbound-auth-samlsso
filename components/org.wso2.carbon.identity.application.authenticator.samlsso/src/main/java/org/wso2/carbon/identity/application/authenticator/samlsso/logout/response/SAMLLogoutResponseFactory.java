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

package org.wso2.carbon.identity.application.authenticator.samlsso.logout.response;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.entity.ContentType;
import org.owasp.encoder.Encode;
import org.wso2.carbon.identity.application.authentication.framework.exception.FrameworkException;
import org.wso2.carbon.identity.application.authentication.framework.inbound.HttpIdentityResponse;
import org.wso2.carbon.identity.application.authentication.framework.inbound.HttpIdentityResponseFactory;
import org.wso2.carbon.identity.application.authentication.framework.inbound.IdentityResponse;
import org.wso2.carbon.identity.application.authenticator.samlsso.logout.exception.SAMLLogoutException;
import org.wso2.carbon.identity.application.authenticator.samlsso.logout.processor.SAMLLogoutRequestProcessor;

import static javax.servlet.http.HttpServletResponse.SC_OK;

/**
 * This class  builds a HTTP response instance based on the common IdentityRequest format used by
 * the authentication framework.
 */
public class SAMLLogoutResponseFactory extends HttpIdentityResponseFactory {

    private static final Log log = LogFactory.getLog(SAMLLogoutRequestProcessor.class);

    @Override
    public boolean canHandle(IdentityResponse identityResponse) {

        return (identityResponse instanceof SAMLLogoutResponse);
    }

    @Override
    public boolean canHandle(FrameworkException exception) {

        return (exception instanceof SAMLLogoutException) && ((SAMLLogoutException) exception).getAcsUrl() != null;
    }

    @Override
    public HttpIdentityResponse.HttpIdentityResponseBuilder create(IdentityResponse identityResponse) {

        HttpIdentityResponse.HttpIdentityResponseBuilder responseBuilder =
                new HttpIdentityResponse.HttpIdentityResponseBuilder();
        create(responseBuilder, identityResponse);
        return responseBuilder;
    }

    @Override
    public void create(HttpIdentityResponse.HttpIdentityResponseBuilder builder, IdentityResponse identityResponse) {

        SAMLLogoutResponse response = (SAMLLogoutResponse) identityResponse;
        String samlPostPage = generateSamlPostPage(response.getAcsUrl(), response.getResponse(), response.getRelayState());
        builder.setBody(samlPostPage);
        builder.setStatusCode(SC_OK);
        builder.setContentType("text/html; charset=UTF-8");
        builder.setRedirectURL(response.getAcsUrl());
    }

    @Override
    public HttpIdentityResponse.HttpIdentityResponseBuilder handleException(FrameworkException exception) {

        HttpIdentityResponse.HttpIdentityResponseBuilder errorResponseBuilder =
                new HttpIdentityResponse.HttpIdentityResponseBuilder();
        SAMLLogoutException samlException = (SAMLLogoutException) exception;
        String samlPostPage = generateSamlPostPage(samlException.getAcsUrl(), samlException.getExceptionMessage(),
                samlException.getRelayState());
        errorResponseBuilder.setBody(samlPostPage);
        errorResponseBuilder.setStatusCode(SC_OK);
        errorResponseBuilder.setContentType("text/html; charset=UTF-8");
        errorResponseBuilder.setRedirectURL(samlException.getAcsUrl());
        return errorResponseBuilder;
    }

    /**
     * Generate the post page for the logout response.
     *
     * @param acUrl       Assertion Service Consumer URL of the Logout Request.
     * @param samlMessage SAML Logout Response.
     * @param relayState  RelayState of the Logout Request.
     * @return Post page.
     */
    private String generateSamlPostPage(String acUrl, String samlMessage, String relayState) {

        String postPage = "<html><body><p>You are now redirected back to " + Encode.forHtmlContent(acUrl) +
                " If the redirection fails, please click the post button.</p><form method='post' action='" +
                Encode.forHtmlAttribute(acUrl) + "'><p><input type='hidden' name='SAMLResponse' value='"
                + Encode.forHtmlAttribute(samlMessage) + "'/>";

        if (StringUtils.isNotBlank(relayState)) {
            postPage = postPage + "<input type='hidden' name='RelayState' value='"
                    + Encode.forHtmlAttribute(relayState) + "'/>";
        }

        postPage = postPage + "<button type='submit'>POST</button></p></form><script type='text/javascript'>" +
                "document.forms[0].submit();</script></body></html>";

        if (log.isDebugEnabled()) {
            log.debug("Post page for the logout response: " + postPage);
        }
        return postPage;
    }
}
