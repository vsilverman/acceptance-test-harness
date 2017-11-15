/*
 * The MIT License
 *
 * Copyright 2017 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.test.acceptance.update_center;

import com.google.inject.Inject;
import com.google.inject.Injector;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.IOUtils;
import org.apache.http.ConnectionClosedException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.FileEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.bootstrap.HttpServer;
import org.apache.http.impl.bootstrap.ServerBootstrap;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpProcessorBuilder;
import org.apache.http.protocol.RequestConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseServer;
import org.apache.http.protocol.UriHttpRequestHandlerMapper;
import org.jenkinsci.test.acceptance.guice.AutoCleaned;
import org.jenkinsci.test.acceptance.guice.TestScope;
import org.jenkinsci.test.acceptance.po.Jenkins;
import org.jenkinsci.test.acceptance.po.UpdateCenter;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Serves a fake update center locally.
 */
@TestScope
public class MockUpdateCenter implements AutoCleaned {

    private static final Logger LOGGER = Logger.getLogger(MockUpdateCenter.class.getName());

    @Inject
    public Injector injector;

    @Inject
    private UpdateCenterMetadataProvider ucmd;

    /** Original default site ID; note that this may not match {@link CachedUpdateCenterMetadataLoader#url}. */
    private String original;

    private HttpServer server;

    public void ensureRunning() {
        if (original != null) {
            return;
        }
        Jenkins jenkins = injector.getInstance(Jenkins.class);
        List<String> sites = new UpdateCenter(jenkins).getJson("tree=sites[url]").findValuesAsText("url");
        if (sites.size() != 1) {
            LOGGER.log(Level.WARNING, "found an unexpected number of update sites: {0}", sites);
            return;
        }
        UpdateCenterMetadata ucm;
        try {
            ucm = ucmd.get(jenkins);
        } catch (IOException x) {
            LOGGER.log(Level.WARNING, "cannot load data for mock update center", x);
            return;
        }
        JSONObject all;
        try {
            all = new JSONObject(ucm.originalJSON);
            all.remove("signature");
            JSONObject plugins = all.getJSONObject("plugins");
            for (PluginMetadata meta : ucm.plugins.values()) {
                String name = meta.getName();
                String version = meta.getVersion();
                JSONObject plugin = plugins.getJSONObject(name);
                if (plugin == null) {
                    plugin = new JSONObject().accumulate("name", name);
                    plugins.put(name, plugin);
                }
                plugin.put("url", name + ".hpi");
                plugin.put("version", version);
                plugin.remove("sha1");
                // TODO update dependencies, requiredCore
            }
        } catch (JSONException x) {
            LOGGER.log(Level.WARNING, "cannot prepare mock update center", x);
            return;
        }
        HttpProcessor proc = HttpProcessorBuilder.create().
            add(new ResponseServer("MockUpdateCenter")).
            add(new ResponseContent()).
            add(new RequestConnControl()).
            build();
        UriHttpRequestHandlerMapper handlerMapper = new UriHttpRequestHandlerMapper();
        String json = "updateCenter.post(\n" + all + "\n);";
        handlerMapper.register("/update-center.json", (HttpRequest request, HttpResponse response, HttpContext context) -> {
            response.setStatusCode(HttpStatus.SC_OK);
            response.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
        });
        handlerMapper.register("*.hpi", (HttpRequest request, HttpResponse response, HttpContext context) -> {
            String plugin = request.getRequestLine().getUri().replaceFirst("^/(.+)[.]hpi$", "$1");
            PluginMetadata meta = ucm.plugins.get(plugin);
            if (meta == null) {
                LOGGER.log(Level.WARNING, "no such plugin {0}", plugin);
                response.setStatusCode(HttpStatus.SC_NOT_FOUND);
                return;
            }
            File local = meta.resolve(injector, meta.getVersion());
            LOGGER.log(Level.INFO, "serving {0}", local);
            response.setStatusCode(HttpStatus.SC_OK);
            response.setEntity(new FileEntity(local));
        });
        handlerMapper.register("*", (HttpRequest request, HttpResponse response, HttpContext context) -> {
            String location = original.replace("/update-center.json", request.getRequestLine().getUri());
            LOGGER.log(Level.INFO, "redirect to {0}", location);
            /* TODO for some reason DownloadService.loadJSONHTML does not seem to process the redirect, despite calling setInstanceFollowRedirects(true):
            response.setStatusCode(HttpStatus.SC_MOVED_TEMPORARILY);
            response.setHeader("Location", location);
             */
            HttpURLConnection uc = (HttpURLConnection) new URL(location).openConnection();
            uc.setInstanceFollowRedirects(true);
            byte[] data = IOUtils.toByteArray(uc);
            String contentType = uc.getContentType();
            response.setStatusCode(HttpStatus.SC_OK);
            response.setEntity(new ByteArrayEntity(data, ContentType.create(contentType)));

        });
        server = ServerBootstrap.bootstrap().
            // could setLocalAddress if using a JenkinsController that requires it
            setHttpProcessor(proc).
            setHandlerMapper(handlerMapper).
            setExceptionLogger((Exception x) -> LOGGER.log(x instanceof ConnectionClosedException ? Level.FINE : Level.WARNING, null, x)).
            create();
        try {
            server.start();
        } catch (IOException x) {
            LOGGER.log(Level.WARNING, "cannot start mock update center", x);
            return;

        }
        original = sites.get(0);
        // TODO figure out how to deal with Docker-based controllers which would need to have an IP address for the host
        String override = "http://" + server.getInetAddress().getHostAddress() + ":" + server.getLocalPort() + "/update-center.json";
        LOGGER.log(Level.INFO, "replacing update site {0} with {1}", new Object[] {original, override});
        jenkins.runScript("DownloadService.signatureCheck = false; Jenkins.instance.updateCenter.sites.replaceBy([new UpdateSite(UpdateCenter.ID_DEFAULT, '%s')])", override);
    }

    @Override
    public void close() throws IOException {
        if (original != null) {
            LOGGER.log(Level.INFO, "stopping MockUpdateCenter on http://{0}:{1}/update-center.json", new Object[] {server.getInetAddress().getHostAddress(), server.getLocalPort()});
            server.shutdown(5, TimeUnit.SECONDS);
            server = null;
            /* TODO only if RemoteController etc.:
            injector.getInstance(Jenkins.class).runScript("DownloadService.signatureCheck = true; Jenkins.instance.updateCenter.sites.replaceBy([new UpdateSite(UpdateCenter.ID_DEFAULT, '%s')])", original);
            */
            original = null;
        }
    }

}
