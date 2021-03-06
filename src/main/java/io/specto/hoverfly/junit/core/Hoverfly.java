/**
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this classpath except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 * <p>
 * Copyright 2016-2016 SpectoLabs Ltd.
 */
package io.specto.hoverfly.junit.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.specto.hoverfly.junit.api.HoverflyClient;
import io.specto.hoverfly.junit.api.HoverflyClientException;
import io.specto.hoverfly.junit.api.model.ModeArguments;
import io.specto.hoverfly.junit.api.view.HoverflyInfoView;
import io.specto.hoverfly.junit.core.config.HoverflyConfiguration;
import io.specto.hoverfly.junit.core.model.Journal;
import io.specto.hoverfly.junit.core.model.Request;
import io.specto.hoverfly.junit.core.model.RequestResponsePair;
import io.specto.hoverfly.junit.core.model.Simulation;
import io.specto.hoverfly.junit.dsl.RequestMatcherBuilder;
import io.specto.hoverfly.junit.dsl.StubServiceBuilder;
import io.specto.hoverfly.junit.verification.VerificationCriteria;
import io.specto.hoverfly.junit.verification.VerificationData;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.StartedProcess;

import static io.specto.hoverfly.junit.core.HoverflyConfig.localConfigs;
import static io.specto.hoverfly.junit.core.HoverflyMode.CAPTURE;
import static io.specto.hoverfly.junit.core.HoverflyUtils.checkPortInUse;
import static io.specto.hoverfly.junit.dsl.matchers.HoverflyMatchers.any;
import static io.specto.hoverfly.junit.verification.HoverflyVerifications.atLeastOnce;
import static io.specto.hoverfly.junit.verification.HoverflyVerifications.never;
import static io.specto.hoverfly.junit.verification.HoverflyVerifications.times;

/**
 * A wrapper class for the Hoverfly binary.  Manage the lifecycle of the processes, and then manage Hoverfly itself by using it's API endpoints.
 */
// TODO extract interface and create LocalHoverfly and RemoteHoverfly
public class Hoverfly implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(Hoverfly.class);
    private static final ObjectWriter JSON_PRETTY_PRINTER = new ObjectMapper().writerWithDefaultPrettyPrinter();
    private static final int BOOT_TIMEOUT_SECONDS = 10;
    private static final int RETRY_BACKOFF_INTERVAL_MS = 100;


    private final HoverflyConfiguration hoverflyConfig;
    private final HoverflyMode hoverflyMode;
    private final ProxyConfigurer proxyConfigurer;
    private final SslConfigurer sslConfigurer = new SslConfigurer();
    private final HoverflyClient hoverflyClient;

    private final TempFileManager tempFileManager = new TempFileManager();
    private StartedProcess startedProcess;
    private boolean useDefaultSslCert = true;

    /**
     * Instantiates {@link Hoverfly}
     *
     * @param hoverflyConfigBuilder the config
     * @param hoverflyMode   the mode
     */
    public Hoverfly(HoverflyConfig hoverflyConfigBuilder, HoverflyMode hoverflyMode) {
        hoverflyConfig = hoverflyConfigBuilder.build();
        this.proxyConfigurer = new ProxyConfigurer(hoverflyConfig);
        this.hoverflyClient = HoverflyClient.custom()
                .scheme(hoverflyConfig.getScheme())
                .host(hoverflyConfig.getHost())
                .port(hoverflyConfig.getAdminPort())
                .withAuthToken()
                .build();
        this.hoverflyMode = hoverflyMode;

    }

    /**
     * Instantiates {@link Hoverfly}
     *
     * @param hoverflyMode the mode
     */
    public Hoverfly(HoverflyMode hoverflyMode) {
        this(localConfigs(), hoverflyMode);
    }

    /**
     * <ol>
     * <li>Adds Hoverfly SSL certificate to the trust store</li>
     * <li>Sets the proxy system properties to route through Hoverfly</li>
     * <li>Starts Hoverfly</li>
     * </ol>
     */
    public void start() {

        Runtime.getRuntime().addShutdownHook(new Thread(this::close));

        if (startedProcess != null) {
            LOGGER.warn("Local Hoverfly is already running.");
            return;
        }

        if (!hoverflyConfig.isRemoteInstance()) {
            startHoverflyProcess();
        } else {
            resetJournal();
        }

        waitForHoverflyToBecomeHealthy();

        if (StringUtils.isNotBlank(hoverflyConfig.getDestination())) {
            setDestination(hoverflyConfig.getDestination());
        }

        if (hoverflyMode == CAPTURE) {
            hoverflyClient.setMode(hoverflyMode, new ModeArguments(hoverflyConfig.getCaptureHeaders()));
        } else {
            hoverflyClient.setMode(hoverflyMode);
        }

        if (hoverflyConfig.getProxyCaCertificate().isPresent()) {
          sslConfigurer.setDefaultSslContext(hoverflyConfig.getProxyCaCertificate().get());
        } else if (useDefaultSslCert) {
            sslConfigurer.setDefaultSslContext();
        }

        proxyConfigurer.setProxySystemProperties();
    }

    private void startHoverflyProcess() {
        checkPortInUse(hoverflyConfig.getProxyPort());
        checkPortInUse(hoverflyConfig.getAdminPort());

        final SystemConfig systemConfig = new SystemConfigFactory().createSystemConfig();

        Path binaryPath = tempFileManager.copyHoverflyBinary(systemConfig);

        LOGGER.info("Executing binary at {}", binaryPath);
        final List<String> commands = new ArrayList<>();
        commands.add(binaryPath.toString());
        commands.add("-db");
        commands.add("memory");
        commands.add("-pp");
        commands.add(String.valueOf(hoverflyConfig.getProxyPort()));
        commands.add("-ap");
        commands.add(String.valueOf(hoverflyConfig.getAdminPort()));

        if (StringUtils.isNotBlank(hoverflyConfig.getSslCertificatePath())) {
            tempFileManager.copyClassPathResource(hoverflyConfig.getSslCertificatePath(), "ca.crt");
            commands.add("-cert");
            commands.add("ca.crt");
        }
        if (StringUtils.isNotBlank(hoverflyConfig.getSslKeyPath())) {
            tempFileManager.copyClassPathResource(hoverflyConfig.getSslKeyPath(), "ca.key");
            commands.add("-key");
            commands.add("ca.key");
            useDefaultSslCert = false;
        }
        if (hoverflyConfig.isPlainHttpTunneling()) {
            commands.add("-plain-http-tunneling");
        }

        if (hoverflyConfig.isWebServer()) {
            commands.add("-webserver");
        }

        if (hoverflyConfig.isTlsVerificationDisabled()) {
            commands.add("-tls-verification");
            commands.add("false");
        }

        if (hoverflyConfig.isMiddlewareEnabled()) {
            final String path = hoverflyConfig.getLocalMiddleware().getPath();
            final String scriptName = path.contains(File.separator) ? path.substring(path.lastIndexOf(File.separator) + 1) : path;
            tempFileManager.copyClassPathResource(path, scriptName);
            commands.add("-middleware");
            commands.add(hoverflyConfig.getLocalMiddleware().getBinary() + " " + scriptName);
        }

        if (StringUtils.isNotBlank(hoverflyConfig.getUpstreamProxy())) {
            commands.add("-upstream-proxy");
            commands.add(hoverflyConfig.getUpstreamProxy());
        }

        try {
            startedProcess = new ProcessExecutor()
                    .command(commands)
                    .redirectOutput(System.out)
                    .directory(tempFileManager.getTempDirectory().toFile())
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("Could not start Hoverfly process", e);
        }
    }

    /**
     * Stops the running {@link Hoverfly} process and clean up resources
     */
    @Override
    public void close() {
        cleanUp();
    }

    /**
     * Imports a simulation into {@link Hoverfly} from a {@link SimulationSource}
     *
     * @param simulationSource the simulation to import
     */

    @Deprecated
    public void importSimulation(SimulationSource simulationSource) {
        simulate(simulationSource);
    }

    public void simulate(SimulationSource simulationSource) {
        LOGGER.info("Importing simulation data to Hoverfly");

        final Simulation simulation = simulationSource.getSimulation();

        hoverflyClient.setSimulation(simulation);
    }

    /**
     * Delete existing simulations and journals
     */
    public void reset() {
        hoverflyClient.deleteSimulation();
        resetJournal();
    }


    /**
     * Delete journal logs
     */
    public void resetJournal() {
        try {
            hoverflyClient.deleteJournal();
        } catch (HoverflyClientException e) {
            LOGGER.warn("Older version of Hoverfly may not have a reset journal API", e);
        }
    }

    /**
     * Deletes all states from Hoverfly
     */
    public void resetStates() {
        try {
            hoverflyClient.deleteStates();
        } catch (HoverflyClientException e) {
            LOGGER.warn("Older version of Hoverfly may not have a delete state API", e);
        }
    }

    /**
     * Exports a simulation and stores it on the filesystem at the given path
     *
     * @param path the path on the filesystem to where the simulation should be stored
     */
    public void exportSimulation(Path path) {

        if (path == null) {
            throw new IllegalArgumentException("Export path cannot be null.");
        }

        LOGGER.info("Exporting simulation data from Hoverfly");
        try {
            Files.deleteIfExists(path);
            final Simulation simulation = hoverflyClient.getSimulation();
            persistSimulation(path, simulation);
        } catch (Exception e) {
            LOGGER.error("Failed to export simulation data", e);
        }
    }

    /**
     * Gets the simulation currently used by the running {@link Hoverfly} instance
     *
     * @return the simulation
     */
    public Simulation getSimulation() {
        return hoverflyClient.getSimulation();
    }

    /**
     * Gets configuration information from the running instance of Hoverfly.
     * @return the hoverfly info object
     */
    public HoverflyInfoView getHoverflyInfo() {
        return hoverflyClient.getConfigInfo();
    }

    /**
     * Sets a new destination for the running instance of Hoverfly, overwriting the existing destination setting.
     * @param destination the destination setting to override
     */
    public void setDestination(String destination) {
        hoverflyClient.setDestination(destination);
    }


    /**
     * Changes the mode of the running instance of Hoverfly.
     * @param mode hoverfly mode to change
     */
    public void setMode(HoverflyMode mode) {
        hoverflyClient.setMode(mode);
    }

    /**
     * Reset mode with the initial mode arguments.
     * @param mode Hoverfly mode to reset
     */
    public void resetMode(HoverflyMode mode) {
        hoverflyClient.setMode(mode, new ModeArguments(hoverflyConfig.getCaptureHeaders()));
    }

    /**
     * Gets the validated {@link HoverflyConfig} object used by the current Hoverfly instance
     * @return the current Hoverfly configurations
     */
    public HoverflyConfiguration getHoverflyConfig() {
        return hoverflyConfig;
    }

    /**
     * Gets the currently activated Hoverfly mode
     * @return hoverfly mode
     */
    public HoverflyMode getMode() {
        return HoverflyMode.valueOf(hoverflyClient.getConfigInfo().getMode().toUpperCase());
    }

    public boolean isHealthy() {
        return hoverflyClient.getHealth();
    }

    public SslConfigurer getSslConfigurer() {
        return sslConfigurer;
    }

    public void verify(RequestMatcherBuilder requestMatcher, VerificationCriteria criteria) {
        verifyRequest(requestMatcher.build(), criteria);
    }

    public void verify(RequestMatcherBuilder requestMatcher) {
        verify(requestMatcher, times(1));
    }

    public void verifyZeroRequestTo(StubServiceBuilder requestedServiceBuilder) {
        verify(requestedServiceBuilder.anyMethod(any()), never());
    }


    public void verifyAll() {
        Simulation simulation = hoverflyClient.getSimulation();
        simulation.getHoverflyData().getPairs().stream()
                .map(RequestResponsePair::getRequest)
                .forEach(request -> verifyRequest(request, atLeastOnce()));
    }

    private void verifyRequest(Request request, VerificationCriteria criteria) {
        Journal journal = hoverflyClient.searchJournal(request);

        criteria.verify(request, new VerificationData(journal));
    }

    private void persistSimulation(Path path, Simulation simulation) throws IOException {
        Files.createDirectories(path.getParent());
        JSON_PRETTY_PRINTER.writeValue(path.toFile(), simulation);
    }


    /**
     * Blocks until the Hoverfly process becomes healthy, otherwise time out
     */
    private void waitForHoverflyToBecomeHealthy() {
        final Instant now = Instant.now();

        while (Duration.between(now, Instant.now()).getSeconds() < BOOT_TIMEOUT_SECONDS) {
            if (hoverflyClient.getHealth()) return;
            try {
                // TODO: prefer executors and tasks to threads
                Thread.sleep(RETRY_BACKOFF_INTERVAL_MS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        throw new IllegalStateException("Hoverfly has not become healthy in " + BOOT_TIMEOUT_SECONDS + " seconds");
    }

    private void cleanUp() {
        LOGGER.info("Destroying hoverfly process");

        if (startedProcess != null) {
            Process process = startedProcess.getProcess();
            process.destroy();

            // Some platforms terminate process asynchronously, eg. Windows, and cannot guarantee that synchronous file deletion
            // can acquire file lock
            ExecutorService executorService = Executors.newSingleThreadExecutor();
            Future<Integer> future = executorService.submit((Callable<Integer>) process::waitFor);
            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                LOGGER.warn("Timeout when waiting for hoverfly process to terminate.");
            }
            executorService.shutdownNow();
        }

        proxyConfigurer.restoreProxySystemProperties();
        // TODO: reset default SslContext?
        sslConfigurer.reset();
        tempFileManager.purge();
    }
}
