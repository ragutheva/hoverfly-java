/**
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this classpath except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Copyright 2016-2016 SpectoLabs Ltd.
 */
package io.specto.hoverfly.junit.core.config;


import io.specto.hoverfly.junit.core.Hoverfly;
import io.specto.hoverfly.junit.core.HoverflyConfig;

import java.net.InetSocketAddress;

/**
 * Config builder interface for settings specific to {@link Hoverfly} managed internally
 */
public class LocalHoverflyConfig extends HoverflyConfig {

    // TODO should be combined field?
    private String sslCertificatePath;
    private String sslKeyPath;
    private boolean tlsVerificationDisabled;
    private boolean plainHttpTunneling;
    private LocalMiddleware localMiddleware;
    private String upstreamProxy;

    /**
     * Sets the SSL certificate file for overriding default Hoverfly self-signed certificate
     * The file can be in any PEM encoded certificate, in .crt or .pem extensions
     * @param sslCertificatePath certificate file in classpath
     * @return the {@link LocalHoverflyConfig} for further customizations
     */
    public LocalHoverflyConfig sslCertificatePath(String sslCertificatePath) {
        this.sslCertificatePath = sslCertificatePath;
        return this;
    }


    /**
     * Sets the SSL key file for overriding default Hoverfly SSL key
     * The file can be in any PEM encoded key, in .key or .pem extensions
     * @param sslKeyPath key file in classpath
     * @return the {@link LocalHoverflyConfig} for further customizations
     */
    public LocalHoverflyConfig sslKeyPath(String sslKeyPath) {
        this.sslKeyPath = sslKeyPath;
        return this;
    }

    /**
     * Sets the middleware for Hoverfly
     * @param binary absolute or relative path of binary
     * @param path middleware script file in classpath
     * @return the {@link LocalHoverflyConfig} for further customizations
     */
    public LocalHoverflyConfig localMiddleware(String binary, String path) {
        this.localMiddleware = new LocalMiddleware(binary, path) ;
        return this;
    }

    public LocalHoverflyConfig disableTlsVerification() {
        this.tlsVerificationDisabled = true;
        return this;
    }

    /**
     * Invoke to enable plain http tunneling
     * By default it is false
     * @return a config
     */
    public LocalHoverflyConfig plainHttpTunneling() {
        this.plainHttpTunneling = true;
        return this;
    }

    /**
     * Set upstream proxy for hoverfly to connect to target host
     * @param proxyAddress socket address of the upstream proxy, eg. 127.0.0.1:8500
     * @return the {@link HoverflyConfig} for further customizations
     */
    public LocalHoverflyConfig upstreamProxy(InetSocketAddress proxyAddress) {
        this.upstreamProxy = proxyAddress.getHostString() + ":" + proxyAddress.getPort();
        return this;
    }

    @Override
    public HoverflyConfiguration build() {
        HoverflyConfiguration configs = new HoverflyConfiguration(proxyPort, adminPort, proxyLocalHost, destination,
                proxyCaCert, captureHeaders, webServer);
        configs.setSslCertificatePath(this.sslCertificatePath);
        configs.setSslKeyPath(this.sslKeyPath);
        configs.setTlsVerificationDisabled(this.tlsVerificationDisabled);
        configs.setPlainHttpTunneling(this.plainHttpTunneling);
        configs.setLocalMiddleware(this.localMiddleware);
        configs.setUpstreamProxy(this.upstreamProxy);
        HoverflyConfigValidator validator = new HoverflyConfigValidator();
        return validator.validate(configs);
    }


}
