/*
+ * The MIT License
 *
 * Copyright (c) 2011, CloudBees, Inc.
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
package com.tikal.hudson.plugins.notification;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.io.CharStreams;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Base64;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import junit.framework.TestCase;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/**
 * @author Kohsuke Kawaguchi
 */
public class ProtocolTest extends TestCase {

    static class Request {
        private final String url;
        private final String method;
        private final String body;
        private final String userInfo;

        Request(HttpServletRequest request) throws IOException {
            this.url = request.getRequestURL().toString();
            this.method = request.getMethod();
            this.body = CharStreams.toString(request.getReader());
            String auth = request.getHeader("Authorization");
            this.userInfo =
                    (null == auth) ? null : new String(Base64.getDecoder().decode(auth.split(" ")[1])) + "@";
        }

        Request(String url, String method, String body) {
            this.url = url;
            this.method = method;
            this.body = body;
            this.userInfo = null;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(url, method, body);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Request)) {
                return false;
            }
            Request other = (Request) obj;
            return Objects.equal(url, other.url)
                    && Objects.equal(method, other.method)
                    && Objects.equal(body, other.body);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("url", url)
                    .add("method", method)
                    .add("body", body)
                    .toString();
        }

        public String getUrl() {
            return url;
        }

        public String getUrlWithAuthority() {
            if (null == userInfo) {
                // Detect possible bug: userInfo never moved from URI to Authorization header
                return null;
            } else {
                return url.replaceFirst("^http://", "http://" + userInfo);
            }
        }
    }

    static class RecordingServlet extends HttpServlet {
        private final BlockingQueue<Request> requests;

        public RecordingServlet(BlockingQueue<Request> requests) {
            this.requests = requests;
        }

        @Override
        protected void doPost(HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                throws ServletException, IOException {

            Request request = new Request(httpRequest);
            try {
                requests.put(request);
            } catch (InterruptedException e) {
                throw new ServletException(e);
            }

            doPost(request, httpResponse);
        }

        protected void doPost(Request request, HttpServletResponse httpResponse) {
            // noop
        }
    }

    static class RedirectHandler extends RecordingServlet {
        private final String redirectURI;

        RedirectHandler(BlockingQueue<Request> requests, String redirectURI) {
            super(requests);
            this.redirectURI = redirectURI;
        }

        @Override
        protected void doPost(Request request, HttpServletResponse httpResponse) {
            httpResponse.setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
            httpResponse.setHeader("Location", redirectURI);
        }
    }

    private List<Server> servers;

    interface UrlFactory {
        String getUrl(String path);
    }

    private UrlFactory startServer(Servlet servlet, String path) throws Exception {
        return startSecureServer(servlet, path, "");
    }

    private UrlFactory startSecureServer(Servlet servlet, String path, String authority) throws Exception {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.setConnectors(new Connector[] {connector});

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        context.addServlet(new ServletHolder(servlet), path);
        server.setHandler(context);

        server.start();
        servers.add(server);

        if (!authority.isEmpty()) {
            authority += "@";
        }

        final URL serverUrl = new URL(String.format("http://%slocalhost:%d", authority, connector.getLocalPort()));
        return new UrlFactory() {
            @Override
            public String getUrl(String path) {
                try {
                    return new URL(serverUrl, path).toExternalForm();
                } catch (MalformedURLException e) {
                    throw new IllegalArgumentException(e);
                }
            }
        };
    }

    @Override
    public void setUp() {
        servers = new LinkedList<>();
    }

    @Override
    public void tearDown() throws Exception {
        for (Server server : servers) {
            server.stop();
        }
    }

    public void testHttpPost() throws Exception {
        BlockingQueue<Request> requests = new LinkedBlockingQueue<>();

        UrlFactory urlFactory = startServer(new RecordingServlet(requests), "/realpath");

        assertTrue(requests.isEmpty());

        String uri = urlFactory.getUrl("/realpath");
        Protocol.HTTP.send(uri, "Hello".getBytes(), 30000, true);

        assertEquals(new Request(uri, "POST", "Hello"), requests.take());
        assertTrue(requests.isEmpty());
    }

    public void testHttpPostWithBasicAuth() throws Exception {
        BlockingQueue<Request> requests = new LinkedBlockingQueue<>();

        UrlFactory urlFactory = startSecureServer(new RecordingServlet(requests), "/realpath", "fred:foo");

        assertTrue(requests.isEmpty());

        String uri = urlFactory.getUrl("/realpath");
        Protocol.HTTP.send(uri, "Hello".getBytes(), 30000, true);

        Request theRequest = requests.take();
        assertTrue(requests.isEmpty());
        assertEquals(new Request(uri, "POST", "Hello").getUrl(), theRequest.getUrlWithAuthority());
    }

    public void testHttpPostWithRedirects() throws Exception {
        BlockingQueue<Request> requests = new LinkedBlockingQueue<>();

        UrlFactory urlFactory = startServer(new RecordingServlet(requests), "/realpath");

        String redirectUri = urlFactory.getUrl("/realpath");
        UrlFactory redirectorUrlFactory = startServer(new RedirectHandler(requests, redirectUri), "/path");

        assertTrue(requests.isEmpty());

        String uri = redirectorUrlFactory.getUrl("/path");
        Protocol.HTTP.send(uri, "RedirectMe".getBytes(), 30000, true);

        assertEquals(new Request(uri, "POST", "RedirectMe"), requests.take());
        assertEquals(new Request(redirectUri, "POST", "RedirectMe"), requests.take());
        assertTrue(requests.isEmpty());
    }
}
