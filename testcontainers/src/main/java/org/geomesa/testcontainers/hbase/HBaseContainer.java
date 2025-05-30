/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.geomesa.testcontainers.hbase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.shaded.org.apache.commons.io.IOUtils;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class HBaseContainer
      extends GenericContainer<HBaseContainer> {

    static final DockerImageName DEFAULT_IMAGE =
            DockerImageName.parse("ghcr.io/geomesa/hbase-docker").withTag("2.6.2");

    private static final Logger logger = LoggerFactory.getLogger(HBaseContainer.class);

    private static final List<String> ports =
            List.of("HBASE_MASTER_PORT", "HBASE_REGIONSERVER_PORT", "NAMENODE_PORT", "ZOOKEEPER_PORT");

    public HBaseContainer() {
        this(DEFAULT_IMAGE);
    }

    public HBaseContainer(DockerImageName imageName) {
        super(imageName);
        for (String env: ports) {
            final int port = getFreePort();
            addFixedExposedPort(port, port);
            addEnv(env, Integer.toString(port));
        }
        // noinspection resource
        waitingFor(Wait.forLogMessage(".*HBase startup complete.*", 1));
        // noinspection resource
        withLogConsumer(new HBaseLogConsumer());
        // to get networking right, we need to make the container use the same hostname as the host -
        // that way when it returns server locations, they will map to localhost and go through the correct port bindings
        String hostname = null;
        try {
            hostname =
                    Runtime.getRuntime()
                            .exec("hostname -s")
                            .onExit()
                            .thenApply((p) -> {
                                try (InputStream is = p.getInputStream()) {
                                    return IOUtils.toString(is, StandardCharsets.UTF_8).trim();
                                } catch (IOException e) {
                                    logger.error("Error reading hostname:", e);
                                    return null;
                                }
                            })
                            .get();
        } catch (IOException | InterruptedException | ExecutionException e) {
            logger.error("Error reading hostname, networking may not work correctly:", e);
        }
        if (hostname != null) {
            String finalHostname = hostname;
            // noinspection resource
            withCreateContainerCmdModifier((cmd) -> cmd.withHostName(finalHostname));
            // noinspection resource
            withNetworkAliases(hostname);
        }
    }

    /**
     * Enable security labels
     *
     * @return this container
     */
    public HBaseContainer withSecurityEnabled() {
        return withEnv("HBASE_SECURITY_ENABLED", "true");
    }

    /**
     * Includes the geomesa-hbase-distributed-runtime jar, which must be present on the classpath
     *
     * @return this container
     */
    public HBaseContainer withGeoMesaDistributedRuntime() {
        return withGeoMesaDistributedRuntime(findDistributedRuntime());
    }

    /**
     * Includes the geomesa-hbase-distributed-runtime jar
     *
     * @param jarHostPath path to the jar on the host system
     * @return this container
     */
    public HBaseContainer withGeoMesaDistributedRuntime(String jarHostPath) {
        logger.info("Binding to host path {}", jarHostPath);
        return withFileSystemBind(jarHostPath,
                                  "/opt/hbase/lib/geomesa-hbase-distributed-runtime.jar",
                                  BindMode.READ_ONLY);
    }

    /**
     * Gets the hbase-site.xml from the container. Container must be started.
     *
     * @return contents of the hbase-site.xml
     */
    public String getHBaseSiteXml() {
        return copyFileFromContainer("/opt/hbase/conf/hbase-site.xml", (is) -> IOUtils.toString(is, StandardCharsets.UTF_8));
    }

    private static int getFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Unable to get free port", e);
        }
    }

    private static final String DISTRIBUTED_RUNTIME_PROPS = "geomesa-hbase-distributed-runtime.properties";

    private static String findDistributedRuntime() {
        String path = null;
        try {
            URL url = HBaseContainer.class.getClassLoader().getResource(DISTRIBUTED_RUNTIME_PROPS);
            URI uri = url == null ? null : url.toURI();
            logger.debug("Distributed runtime lookup: {}", uri);
            if (uri != null && uri.toString().endsWith("/target/classes/" + DISTRIBUTED_RUNTIME_PROPS)) {
                // running through an IDE
                File targetDir = Paths.get(uri).toFile().getParentFile().getParentFile();
                File[] names = targetDir.listFiles((dir, name) ->
                                                         name.startsWith("geomesa-hbase-distributed-runtime-hbase2_") &&
                                                         (name.endsWith("-SNAPSHOT.jar") || name.matches(
                                                               ".*-[0-9]+\\.[0-9]+\\.[0-9]+\\.jar")));
                if (names != null && names.length == 1) {
                    path = names[0].getAbsolutePath();
                }
            } else if (uri != null && "jar".equals(uri.getScheme())) {
                // running through maven
                String jar = uri.toString().substring(4).replaceAll("\\.jar!.*", ".jar");
                path = Paths.get(URI.create(jar)).toFile().getAbsolutePath();
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException("Could not load geomesa-hbase-distributed-runtime JAR from classpath", e);
        }
        if (path == null) {
            throw new RuntimeException(
                  "Could not load geomesa-hbase-distributed-runtime JAR from classpath");
        }
        return path;
    }

    private static class HBaseLogConsumer
          extends Slf4jLogConsumer {

        private boolean output = true;

        public HBaseLogConsumer() {
            super(LoggerFactory.getLogger("hbase"), true);
        }

        @Override
        public void accept(OutputFrame outputFrame) {
            if (output) {
                super.accept(outputFrame);
                if (outputFrame.getUtf8StringWithoutLineEnding().matches(".*HBase startup complete.*")) {
                    output = false;
                    byte[] msg = "Container started - suppressing further output".getBytes(StandardCharsets.UTF_8);
                    super.accept(new OutputFrame(OutputFrame.OutputType.STDOUT, msg));
                }
            }
        }
    }
}
