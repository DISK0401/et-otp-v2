/*
 * IconFetcher.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;


/**
 * Fetches the icon of a service from its site (issue #27), only when the user asks for it.
 * <p>
 * The site is asked directly (no third party icon service, which would learn the registered services):
 * the top page for its {@code <link rel="icon">} and {@code apple-touch-icon}, else {@code /favicon.ico}.
 * Only HTTPS is used, no cookies are sent, the top page is only read from the site itself (also after
 * redirects), the icon may also come from another server the site names (a CDN, as for Slack), and every
 * response has a time and size limit. The proxy settings of the operating system are used if
 * {@code java.net.useSystemProxies} was set at the start ({@link GUI#main}), and the certificates trusted by
 * the operating system are trusted besides those of Java: a company network that inspects HTTPS usually adds
 * its certificate authority to the operating system only.
 */
final class IconFetcher
{
    static final int MAX_REDIRECTS = 3;
    /** the start of the top page that is searched for icon links (they are in the head). */
    static final int MAX_HTML_BYTES = 256 * 1024;
    static final int MAX_CANDIDATES = 5;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    /** for the whole fetch, also for a site that sends its answer very slowly. */
    static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(40);

    private static final String USER_AGENT = "et-otp-v2 (icon fetch)";
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<(link|base)\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE =
        Pattern.compile("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))");
    private static final Pattern SIZES = Pattern.compile("(\\d{1,4})[xX](\\d{1,4})");

    /** closes the responses that take longer than the total time. */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "et-otp-icon-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final String site;
    private final long deadline;
    private HttpClient client;


    /** @param site the host name of the site, from {@link #siteHost} */
    IconFetcher(String site)
    {
        this.site = site;
        this.deadline = System.nanoTime() + TOTAL_TIMEOUT.toNanos();
    }


    /**
     * Turns what the user entered ("example.com", "https://www.example.com/login") into the host name of the
     * site, with an optional port.
     *
     * @return the host name (ASCII, lower case), or null if it is no valid host
     */
    static String siteHost(String input)
    {
        if (input == null) {
            return null;
        }
        String s = input.trim();
        if (s.isEmpty() || s.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            String name = s.substring(0, scheme).toLowerCase(Locale.ROOT);
            if (!name.equals("https") && !name.equals("http")) {
                return null;
            }
            s = s.substring(scheme + 3);
        }
        int end = s.length();
        for (char c : new char[] { '/', '?', '#' }) {
            int i = s.indexOf(c);
            if (i >= 0) {
                end = Math.min(end, i);
            }
        }
        s = s.substring(0, end);
        if (s.contains("@")) {
            return null; // user information is not sent
        }
        String host = s;
        String port = "";
        int colon = s.lastIndexOf(':');
        if (colon >= 0) {
            host = s.substring(0, colon);
            port = s.substring(colon + 1);
            if (!port.matches("\\d{1,5}") || Integer.parseInt(port) == 0 || Integer.parseInt(port) > 65535) {
                return null;
            }
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        try {
            host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (host.isEmpty() || host.startsWith(".") || host.contains("..")) {
            return null;
        }
        return port.isEmpty() || port.equals("443") ? host : host + ":" + port;
    }


    /**
     * @return true if a request to the host stays within the site: the same host, with or without "www.",
     *         or a subdomain of it (for example "www.example.com" and "static.example.com" for "example.com",
     *         and "example.com" for "www.example.com")
     */
    static boolean sameSite(String site, String host)
    {
        if (host == null) {
            return false;
        }
        String a = bare(site);
        String b = bare(host.toLowerCase(Locale.ROOT));
        // not the parent domain: for "example.co.jp" it would be "co.jp"
        return a.equals(b) || b.endsWith("." + a);
    }


    private static String bare(String host)
    {
        String h = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        return h.startsWith("www.") ? h.substring(4) : h;
    }


    /** @return true if the URI may be requested: HTTPS within the site */
    boolean allowed(URI uri)
    {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null) {
            return false;
        }
        int colon = site.indexOf(':');
        int sitePort = colon < 0 ? 443 : Integer.parseInt(site.substring(colon + 1));
        int port = uri.getPort() == -1 ? 443 : uri.getPort();
        if (port != sitePort) {
            return false;
        }
        String siteHost = colon < 0 ? site : site.substring(0, colon);
        String host = uri.getHost();
        return sameSite(siteHost, host);
    }


    /**
     * @return true if an icon may be requested there: within the site ({@link #allowed}), or on another server
     *         that the site names for its icon (a CDN), if it is a public host name on the HTTPS port. IP
     *         addresses and host names without a dot are refused, so a page cannot make this computer request
     *         something from a server in its own network.
     */
    boolean allowedIcon(URI uri)
    {
        if (allowed(uri)) {
            return true;
        }
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
            || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return false;
        }
        return publicHostName(uri.getHost());
    }


    /** @return true for a host name with a dot that is not an IP address, "localhost" or a local name */
    static boolean publicHostName(String host)
    {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.startsWith("[") || h.matches("[0-9.]+") || !h.contains(".")) {
            return false; // IPv6, IPv4 (also short forms such as 127.1), single label
        }
        return !(h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local")
                 || h.endsWith(".internal") || h.endsWith(".lan") || h.endsWith(".home.arpa"));
    }


    /** An icon link of a page. */
    static final class Link
    {
        final URI uri;
        /** the larger side given in the sizes attribute, 0 if unknown. */
        final int size;
        final boolean touchIcon;

        Link(URI uri, int size, boolean touchIcon)
        {
            this.uri = uri;
            this.size = size;
            this.touchIcon = touchIcon;
        }

        /** an icon without size is usually 16 or 32 pixels; touch icons are 180. */
        int expectedSize()
        {
            return size > 0 ? size : touchIcon ? 180 : 32;
        }
    }


    /**
     * Finds the icon links in an HTML page, the most suitable first: the smallest that is at least
     * {@link IconImages#SIZE} pixels, then the larger of the smaller ones. SVG and WebP icons are left out
     * (JDK cannot read them), and so are data URIs and other schemes than HTTPS.
     *
     * @param page the address of the page, after redirects, to resolve relative links
     */
    static List<Link> iconLinks(String html, URI page)
    {
        String text = COMMENT.matcher(html).replaceAll(" ");
        URI base = page;
        List<Link> links = new ArrayList<>();
        Matcher m = TAG.matcher(text);
        while (m.find()) {
            Map<String, String> attributes = attributes(m.group(2));
            String href = attributes.get("href");
            if (href == null || href.trim().isEmpty()) {
                continue;
            }
            URI uri = resolve(base, unescape(href.trim()));
            if (m.group(1).equalsIgnoreCase("base")) {
                if (uri != null && links.isEmpty()) {
                    base = uri;
                }
                continue;
            }
            String rel = " " + attributes.getOrDefault("rel", "").toLowerCase(Locale.ROOT).trim() + " ";
            boolean touch = rel.contains(" apple-touch-icon ") || rel.contains(" apple-touch-icon-precomposed ");
            if (!touch && !rel.matches(".*\\sicon\\s.*")) {
                continue; // also leaves out "mask-icon", an SVG
            }
            String type = attributes.getOrDefault("type", "").toLowerCase(Locale.ROOT);
            if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || type.contains("svg") || type.contains("webp")) {
                continue;
            }
            String path = Optional.ofNullable(uri.getPath()).orElse("").toLowerCase(Locale.ROOT);
            if (path.endsWith(".svg") || path.endsWith(".svgz") || path.endsWith(".webp")) {
                continue;
            }
            links.add(new Link(uri, size(attributes.getOrDefault("sizes", "")), touch));
        }
        links.sort(Comparator.comparingInt((Link l) -> l.expectedSize() >= IconImages.SIZE ? 0 : 1)
                       .thenComparingInt(l -> l.expectedSize() >= IconImages.SIZE ? l.expectedSize() : -l.expectedSize()));
        return links;
    }


    private static Map<String, String> attributes(String text)
    {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher m = ATTRIBUTE.matcher(text);
        while (m.find()) {
            String value = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
            result.putIfAbsent(m.group(1).toLowerCase(Locale.ROOT), value);
        }
        return result;
    }


    private static int size(String sizes)
    {
        int max = 0;
        Matcher m = SIZES.matcher(sizes);
        while (m.find()) {
            max = Math.max(max, Math.max(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
        }
        return max;
    }


    /** The few character references that occur in links. */
    private static String unescape(String s)
    {
        return s.replace("&amp;", "&").replace("&#38;", "&").replace("&#x2F;", "/").replace("&#47;", "/");
    }


    private static URI resolve(URI base, String href)
    {
        try {
            return base.resolve(new URI(href.replace(" ", "%20")));
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }


    /**
     * Fetches the icon.
     *
     * @return the icon in the stored form ({@link IconImages#toStored})
     * @throws IOException if no icon could be fetched; the message tells why
     */
    String fetch() throws IOException, InterruptedException
    {
        // no cookie handler: no cookies are stored or sent
        try (HttpClient c = HttpClient.newBuilder()
                .sslContext(sslContext())
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .proxy(ProxySelector.getDefault())
                .build()) {
            client = c;
            return fetchIcon();
        } finally {
            client = null;
        }
    }


    private String fetchIcon() throws IOException, InterruptedException
    {
        URI home = URI.create("https://" + site + "/");
        List<URI> candidates = new ArrayList<>();
        IOException problem = null;
        URI favicon = home.resolve("/favicon.ico");
        try {
            Response page = get(home, MAX_HTML_BYTES, true, false);
            favicon = page.uri.resolve("/favicon.ico");
            String html = new String(page.body, StandardCharsets.ISO_8859_1);
            for (Link l : iconLinks(html, page.uri)) {
                if (allowedIcon(l.uri) && !candidates.contains(l.uri) && candidates.size() < MAX_CANDIDATES) {
                    candidates.add(l.uri);
                }
            }
        } catch (IOException e) {
            problem = e; // the top page may need a login: try the favicon
        }
        if (!candidates.contains(favicon)) {
            candidates.add(favicon);
        }
        for (URI uri : candidates) {
            try {
                return IconImages.toStored(get(uri, IconImages.MAX_FILE_BYTES, false, true).body);
            } catch (IOException e) {
                // an icon on another server tells the user more than a missing favicon
                if (!(problem instanceof Problem && ((Problem) problem).kind == Problem.Kind.OUTSIDE)) {
                    problem = e;
                }
            }
        }
        throw problem != null ? problem : new IOException("No icon found");
    }


    /** @return a TLS context that trusts the certificate authorities of Java and of the operating system */
    static SSLContext sslContext() throws IOException
    {
        try {
            List<X509ExtendedTrustManager> managers = new ArrayList<>();
            managers.add(trustManager(null));
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            // macOS: the system roots, and the certificates trusted in the keychains (where companies add theirs)
            String[] types = os.startsWith("windows") ? new String[] { "Windows-ROOT" }
                           : os.startsWith("mac") ? new String[] { "KeychainStore-ROOT", "KeychainStore" }
                           : new String[0];
            for (String type : types) {
                try {
                    KeyStore store = KeyStore.getInstance(type);
                    store.load(null, null);
                    managers.add(trustManager(store));
                } catch (GeneralSecurityException | IOException e) {
                    // without the store of the operating system, Java's own authorities are trusted
                }
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] { new AnyTrustManager(managers) }, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IOException("TLS is not available: " + e, e);
        }
    }


    private static X509ExtendedTrustManager trustManager(KeyStore store) throws GeneralSecurityException
    {
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init(store);
        for (TrustManager m : f.getTrustManagers()) {
            if (m instanceof X509ExtendedTrustManager) {
                return (X509ExtendedTrustManager) m;
            }
        }
        throw new GeneralSecurityException("No X.509 trust manager");
    }


    /** Trusts a server if one of the trust managers does (each checks the host name as well). */
    static final class AnyTrustManager extends X509ExtendedTrustManager
    {
        private final List<X509ExtendedTrustManager> managers;

        AnyTrustManager(List<X509ExtendedTrustManager> managers)
        {
            this.managers = managers;
        }

        private interface Check
        {
            void run(X509ExtendedTrustManager m) throws CertificateException;
        }

        private void any(Check check) throws CertificateException
        {
            CertificateException first = null;
            for (X509ExtendedTrustManager m : managers) {
                try {
                    check.run(m);
                    return;
                } catch (CertificateException e) {
                    if (first == null) {
                        first = e;
                    }
                }
            }
            throw first != null ? first : new CertificateException("No trust manager");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException
        {
            any(m -> m.checkServerTrusted(chain, authType, engine));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException
        {
            any(m -> m.checkServerTrusted(chain, authType, socket));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException
        {
            any(m -> m.checkServerTrusted(chain, authType));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException
        {
            throw new CertificateException("No client certificates are accepted");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException
        {
            throw new CertificateException("No client certificates are accepted");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException
        {
            throw new CertificateException("No client certificates are accepted");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers()
        {
            return managers.stream().flatMap(m -> java.util.Arrays.stream(m.getAcceptedIssuers()))
                           .toArray(X509Certificate[]::new);
        }
    }


    /** A response: the address after redirects and the body. */
    static final class Response
    {
        final URI uri;
        final byte[] body;

        Response(URI uri, byte[] body)
        {
            this.uri = uri;
            this.body = body;
        }
    }


    /**
     * @param truncate true to read only the start of a longer body, false to fail if it is longer
     */
    /** Why an icon was not fetched, told to the user in their language. */
    static final class Problem extends IOException
    {
        private static final long serialVersionUID = 1L;

        enum Kind
        {
            /** the icon or a redirect is on another server, or not on HTTPS. */
            OUTSIDE,
            REDIRECTS,
            /** an HTTP status other than 200; the detail is the status. */
            STATUS,
            TOO_LARGE,
            TIMEOUT
        }

        final Kind kind;
        /** the host for {@link Kind#OUTSIDE}, the status for {@link Kind#STATUS}, else null. */
        final String detail;

        Problem(Kind kind, String detail, String message)
        {
            super(message);
            this.kind = kind;
            this.detail = detail;
        }
    }


    /**
     * Checks a redirect.
     *
     * @param redirects the redirects followed before this one
     * @return the address to request next (checked with {@link #allowed} before the request)
     * @throws IOException if it must not be followed
     */
    static URI redirect(URI current, String location, int redirects) throws IOException
    {
        if (redirects >= MAX_REDIRECTS) {
            throw new Problem(Problem.Kind.REDIRECTS, null, "Too many redirects: " + current);
        }
        URI next = location == null || location.trim().isEmpty() ? null : resolve(current, location.trim());
        if (next == null) {
            throw new Problem(Problem.Kind.REDIRECTS, null, "Invalid redirect from " + current + ": " + location);
        }
        return next;
    }


    /** @param icon true for an icon, which may also be on another server ({@link #allowedIcon}) */
    private Response get(URI uri, int maxBytes, boolean truncate, boolean icon) throws IOException, InterruptedException
    {
        URI current = uri;
        for (int redirects = 0;; redirects++) {
            if (!(icon ? allowedIcon(current) : allowed(current))) {
                throw new Problem(Problem.Kind.OUTSIDE, current.getHost(),
                                  "Not fetched (outside the site or not HTTPS): " + current);
            }
            HttpRequest request = HttpRequest.newBuilder(current)
                .timeout(remaining(REQUEST_TIMEOUT))
                .header("User-Agent", USER_AGENT)
                .header("Accept", truncate ? "text/html" : "image/png,image/x-icon,image/*;q=0.8")
                .GET()
                .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            try (InputStream in = response.body()) {
                if (status >= 300 && status < 400) {
                    current = redirect(current, response.headers().firstValue("Location").orElse(null), redirects);
                    continue;
                }
                if (status != 200) {
                    throw new Problem(Problem.Kind.STATUS, Integer.toString(status), "HTTP " + status + ": " + current);
                }
                return new Response(current, read(in, maxBytes, truncate, deadline));
            }
        }
    }


    private Duration remaining(Duration limit)
    {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            return Duration.ofMillis(1);
        }
        return Duration.ofNanos(Math.min(left, limit.toNanos()));
    }


    /**
     * Reads the body up to the limit; the stream is closed when the time is over.
     *
     * @param truncate true to return the start of a longer body, false to fail
     * @param deadline the end of the time in {@link System#nanoTime()}
     */
    static byte[] read(InputStream in, int maxBytes, boolean truncate, long deadline) throws IOException
    {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            throw new Problem(Problem.Kind.TIMEOUT, null, "Timed out");
        }
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
            try {
                in.close();
            } catch (IOException e) {
                // closing only interrupts the reading
            }
        }, left, TimeUnit.NANOSECONDS);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (true) {
                int n;
                try {
                    n = in.read(buffer);
                } catch (IOException e) {
                    if (System.nanoTime() >= deadline) {
                        throw new Problem(Problem.Kind.TIMEOUT, null, "Timed out");
                    }
                    throw e;
                }
                if (n < 0) {
                    return out.toByteArray();
                }
                if (out.size() + n > maxBytes) {
                    if (truncate) {
                        out.write(buffer, 0, maxBytes - out.size());
                        return out.toByteArray();
                    }
                    throw new Problem(Problem.Kind.TOO_LARGE, null,
                                      "The answer is too large (more than " + maxBytes + " bytes)");
                }
                out.write(buffer, 0, n);
            }
        } finally {
            watchdog.cancel(false);
        }
    }
}
