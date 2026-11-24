/*
 * IconFetcherTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;


/** The parts of fetching that need no network; nothing here connects anywhere. */
class IconFetcherTest
{
    @Test
    void siteHostFromWhatTheUserEntered()
    {
        assertEquals("example.com", IconFetcher.siteHost("example.com"));
        assertEquals("example.com", IconFetcher.siteHost(" Example.COM. "));
        assertEquals("www.example.com", IconFetcher.siteHost("https://www.example.com/login?next=/"));
        // http is taken as the site, but only HTTPS is used
        assertEquals("example.com", IconFetcher.siteHost("http://example.com"));
        assertEquals("example.com:8443", IconFetcher.siteHost("https://example.com:8443/"));
        assertEquals("example.com", IconFetcher.siteHost("example.com:443"));
        assertEquals("intranet", IconFetcher.siteHost("intranet"));
        assertEquals("xn--r8jz45g.jp", IconFetcher.siteHost("例え.jp"));
    }

    @Test
    void invalidSites()
    {
        assertNull(IconFetcher.siteHost(null));
        assertNull(IconFetcher.siteHost(""));
        assertNull(IconFetcher.siteHost("ftp://example.com"));
        assertNull(IconFetcher.siteHost("javascript:alert(1)"));
        assertNull(IconFetcher.siteHost("exa mple.com"));
        assertNull(IconFetcher.siteHost("user@example.com"));
        assertNull(IconFetcher.siteHost("https://user:pw@example.com/"));
        assertNull(IconFetcher.siteHost("example.com:99999"));
        assertNull(IconFetcher.siteHost("example..com"));
        assertNull(IconFetcher.siteHost("exa_mple.com"));
    }

    @Test
    void requestsStayWithinTheSite()
    {
        assertTrue(IconFetcher.sameSite("example.com", "example.com"));
        assertTrue(IconFetcher.sameSite("example.com", "www.example.com"));
        assertTrue(IconFetcher.sameSite("www.example.com", "example.com"));
        assertTrue(IconFetcher.sameSite("example.com", "static.example.com"));
        assertTrue(IconFetcher.sameSite("example.com", "EXAMPLE.com"));
        assertFalse(IconFetcher.sameSite("example.com", "example.com.evil.test"));
        assertFalse(IconFetcher.sameSite("example.com", "badexample.com"));
        assertFalse(IconFetcher.sameSite("example.com", "cdn.example.net"));
        assertFalse(IconFetcher.sameSite("accounts.example.co.jp", "co.jp"), "never the parent domain");
        assertFalse(IconFetcher.sameSite("example.com", null));
    }

    @Test
    void onlyHttpsWithinTheSiteIsRequested()
    {
        IconFetcher f = new IconFetcher("example.com");
        assertTrue(f.allowed(URI.create("https://example.com/favicon.ico")));
        assertTrue(f.allowed(URI.create("https://www.example.com:443/a.png")));
        assertFalse(f.allowed(URI.create("http://example.com/favicon.ico")));
        assertFalse(f.allowed(URI.create("https://example.com:8443/favicon.ico")));
        assertFalse(f.allowed(URI.create("https://other.test/favicon.ico")));
        assertFalse(f.allowed(URI.create("https://user@example.com/favicon.ico")));
        assertFalse(f.allowed(null));
        IconFetcher port = new IconFetcher("example.com:8443");
        assertTrue(port.allowed(URI.create("https://example.com:8443/favicon.ico")));
        assertFalse(port.allowed(URI.create("https://example.com/favicon.ico")));
    }

    private static List<String> links(String html, String page)
    {
        return IconFetcher.iconLinks(html, URI.create(page)).stream().map(l -> l.uri.toString())
                          .collect(Collectors.toList());
    }

    @Test
    void findsIconLinks()
    {
        String html = "<html><head>"
                      + "<link rel=\"stylesheet\" href=\"/style.css\">"
                      + "<LINK REL='shortcut icon' HREF='/favicon.ico'>"
                      + "<link rel=icon type=image/png sizes=32x32 href=icon-32.png>"
                      + "<link rel=\"icon\" type=\"image/png\" sizes=\"192x192\" href=\"https://example.com/icon-192.png?v=1&amp;x=2\">"
                      + "<link rel=\"apple-touch-icon\" href=\"/apple-touch-icon.png\">"
                      + "<link rel=\"icon\" type=\"image/svg+xml\" href=\"/icon.svg\">"
                      + "<link rel=\"icon\" href=\"/icon.webp\">"
                      + "<link rel=\"mask-icon\" href=\"/mask.png\" color=\"#000\">"
                      + "<link rel=\"icon\" href=\"data:image/png;base64,AAAA\">"
                      + "<link rel=\"icon\" href=\"http://example.com/insecure.png\">"
                      + "<!-- <link rel=\"icon\" sizes=\"64x64\" href=\"/commented.png\"> -->"
                      + "</head></html>";
        assertEquals(List.of("https://example.com/apple-touch-icon.png",       // 180, the smallest of at least 64
                             "https://example.com/icon-192.png?v=1&x=2",
                             "https://example.com/favicon.ico",                // then the larger of the smaller,
                             "https://example.com/dir/icon-32.png"),           // in the order of the page
                     links(html, "https://example.com/dir/page"));
    }

    @Test
    void baseAddressIsUsed()
    {
        String html = "<base href=\"https://www.example.com/app/\"><link rel=\"icon\" href=\"favicon.png\">";
        assertEquals(List.of("https://www.example.com/app/favicon.png"), links(html, "https://example.com/"));
    }

    @Test
    void pagesWithoutIcons()
    {
        assertEquals(List.of(), links("", "https://example.com/"));
        assertEquals(List.of(), links("<link rel=\"icon\">", "https://example.com/"));
        assertEquals(List.of(), links("<link rel=\"icon\" href=\"::invalid uri::\">", "https://example.com/"));
        assertEquals(List.of(), links("<link href=\"/favicon.ico\">", "https://example.com/"));
    }
}
