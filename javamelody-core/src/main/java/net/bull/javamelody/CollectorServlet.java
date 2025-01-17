/*
 * Copyright 2008-2019 by Emeric Vernat
 *
 *     This file is part of Java Melody.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.bull.javamelody;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;

import net.bull.javamelody.internal.common.HttpParameter;
import net.bull.javamelody.internal.common.I18N;
import net.bull.javamelody.internal.common.Parameters;
import net.bull.javamelody.internal.model.Collector;
import net.bull.javamelody.internal.model.CollectorServer;
import net.bull.javamelody.internal.web.CollectorController;
import net.bull.javamelody.internal.web.HttpAuth;
import net.bull.javamelody.internal.web.MonitoringController;

/**
 * Collection servlet used only for the collection server ({@link CollectorServer}) separated from the monitored application.
 *
 * @author Emeric Vernat
 */
public class CollectorServlet extends HttpServlet {

    @SuppressWarnings("all")
    private static final Logger LOGGER = LogManager.getLogger("javamelody");

    @SuppressWarnings("all")
    private transient HttpAuth httpAuth;

    @SuppressWarnings("all")
    private transient CollectorServer collectorServer;

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        Parameters.initialize(config.getServletContext());
        if (!Parameter.LOG.getValueAsBoolean()) {
            // si log désactivé dans serveur de collecte,
            // alors pas de log, comme dans webapp
            Configurator.setLevel(LOGGER.getName(), Level.WARN);

        }
        // dans le serveur de collecte, on est sûr que log4j est disponible
        LOGGER.info("initialization of the collector servlet of the monitoring");

        // Create a trust manager that does not validate certificate chains
        TrustManager[] trustAllCerts;
        trustAllCerts = new TrustManager[]{
            new X509TrustManager() {

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }

                @Override
                public void checkClientTrusted(X509Certificate[] certs, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] certs, String authType) {
                }
            }
        };

        // Install the all-trusting trust manager
        try {
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        } catch (GeneralSecurityException e) {
            LOGGER.error("Error registering the ssl trust manager", e);
        }

        httpAuth = new HttpAuth();

        try {
            collectorServer = new CollectorServer();
        } catch (final IOException e) {
            throw new ServletException(e.getMessage(), e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        if (!httpAuth.isAllowed(req, resp)) {
            return;
        }

        final long start = System.currentTimeMillis();
        final String resource = HttpParameter.RESOURCE.getParameterFrom(req);
        if (resource != null) {
            MonitoringController.doResource(resp, resource);
            return;
        }
        final CollectorController collectorController = new CollectorController(collectorServer);
        final String application = collectorController.getApplication(req, resp);
        I18N.bindLocale(req.getLocale());
        try {
            if (application == null) {
                CollectorController.writeOnlyAddApplication(resp);
                return;
            }
            if (!collectorServer.isApplicationDataAvailable(application)
                    && HttpParameter.ACTION.getParameterFrom(req) == null) {
                CollectorController.writeDataUnavailableForApplication(application, resp);
                return;
            }
            collectorController.doMonitoring(req, resp, application);
        } finally {
            I18N.unbindLocale();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("monitoring from " + req.getRemoteAddr() + ", request="
                        + req.getRequestURI()
                        + (req.getQueryString() != null ? '?' + req.getQueryString() : "")
                        + ", application=" + application + " in "
                        + (System.currentTimeMillis() - start) + "ms");
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (!httpAuth.isAllowed(req, resp)) {
            return;
        }

        // post du formulaire d'ajout d'application à monitorer
        I18N.bindLocale(req.getLocale());
        try {
            addCollectorApplication(req, resp);
        } catch (Exception e) {
            LOGGER.warn(e.toString(), e);
            String userAgent = req.getHeader("User-Agent");
            if (userAgent != null && userAgent.startsWith("Java")) {
                resp.sendError(HttpServletResponse.SC_PRECONDITION_FAILED, e.toString());
            } else {
                CollectorController collectorController = new CollectorController(
                        collectorServer);
                String application = collectorController.getApplication(req, resp);
                collectorController.writeMessage(req, resp, application, e.toString());
            }
        } finally {
            I18N.unbindLocale();
        }
    }

    private void addCollectorApplication(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        final String appName = req.getParameter("appName");
        final String appUrls = req.getParameter("appUrls");
        final String action = req.getParameter("action");
        final String[] aggregatedApps = req.getParameterValues("aggregatedApps");
        try {
            if (appName == null || appUrls == null && aggregatedApps == null) {
                throw new IllegalArgumentException(I18N.getString("donnees_manquantes"));
            }
            if (appUrls != null && !appUrls.startsWith("http://")
                    && !appUrls.startsWith("https://")) {
                throw new IllegalArgumentException(I18N.getString("urls_format"));
            }
            final CollectorController collectorController = new CollectorController(
                    collectorServer);
            if ("unregisterNode".equals(action)) {
                collectorController.removeCollectorApplicationNodes(appName, appUrls);
                LOGGER.info("monitored application node removed: " + appName + ", url: " + appUrls);
            } else if (appUrls != null) {
                collectorController.addCollectorApplication(appName, appUrls);
                LOGGER.info("monitored application added: " + appName);
                LOGGER.info("urls of the monitored application: " + appUrls);
            } else {
                assert aggregatedApps != null;
                collectorController.addCollectorAggregationApplication(appName,
                        Arrays.asList(aggregatedApps));
                LOGGER.info("aggregation application added: " + appName);
                LOGGER.info("aggregated applications of the aggregation application: "
                        + Arrays.asList(aggregatedApps));
            }
            CollectorController.showAlertAndRedirectTo(resp,
                    I18N.getFormattedString("application_ajoutee", appName),
                    "?application=" + appName);
        } catch (final FileNotFoundException e) {
            final String message = I18N.getString("monitoring_configure");
            throw new IllegalStateException(message + '\n' + e, e);
        } catch (final StreamCorruptedException e) {
            final String message = I18N.getFormattedString("reponse_non_comprise", appUrls);
            throw new IllegalStateException(message + '\n' + e, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() {
        LOGGER.info("collector servlet stopping");
        if (collectorServer != null) {
            collectorServer.stop();
        }
        Collector.stopJRobin();
        LOGGER.info("collector servlet stopped");
        super.destroy();
    }

    // addCollectorApplication and removeCollectorApplication added for spring-boot-admin
    // see https://github.com/codecentric/spring-boot-admin/pull/450
    public static void addCollectorApplication(String application, String urls) throws IOException {
        Parameters.addCollectorApplication(application, Parameters.parseUrls(urls));
    }

    public static void removeCollectorApplication(String application) throws IOException {
        Parameters.removeCollectorApplication(application);
    }
}
