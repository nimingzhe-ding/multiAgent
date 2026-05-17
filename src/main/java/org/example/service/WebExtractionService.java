package org.example.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Web page content extraction service.
 * Fetches HTML from a URL and extracts readable text using only the standard library.
 */
@Service
public class WebExtractionService {

    private static final Logger logger = LoggerFactory.getLogger(WebExtractionService.class);

    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CHARSET_HEADER_PATTERN = Pattern.compile(
            "charset=([^\\s;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CHARSET_PATTERN = Pattern.compile(
            "<meta[^>]+charset=[\"']?([^\"';>\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT_PATTERN = Pattern.compile(
            "<!--.*?-->", Pattern.DOTALL);
    private static final Pattern NON_VISIBLE_BLOCK_PATTERN = Pattern.compile(
            "<(script|style|noscript|iframe|svg|head)\\b[^>]*>.*?</\\1\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MULTI_SPACE_PATTERN = Pattern.compile(" {2,}");
    private static final Pattern MULTI_NEWLINE_PATTERN = Pattern.compile("\\n{3,}");
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
    private static final int MIN_RENDERED_TEXT_CHARS = 200;
    private static final int MIN_SUBSTANTIAL_TEXT_CHARS = 500;
    private static final List<String> DEFAULT_BROWSER_PATHS = List.of(
            "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
            "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
            "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
            "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
            // User-local Chrome installs (common on managed Windows)
            System.getProperty("user.home") + "\\AppData\\Local\\Google\\Chrome\\Application\\chrome.exe",
            // Chromium / Brave
            "C:\\Program Files\\Chromium\\Application\\chrome.exe",
            "C:\\Program Files\\BraveSoftware\\Brave-Browser\\Application\\brave.exe"
    );

    @Value("${app.web-render.enabled:true}")
    private boolean renderEnabled;

    @Value("${app.web-render.wait-ms:3500}")
    private int renderWaitMs;

    @Value("${app.web-render.browser-path:}")
    private String configuredBrowserPath;

    /**
     * Fetch a web page and extract its visible text.
     *
     * @param url     The URL to fetch (must start with http:// or https://).
     * @param timeout Request timeout in seconds.
     * @return String array: [extracted_text, page_title_or_null]
     */
    public String[] extractTextFromUrl(String url, int timeout) {
        return extractTextFromUrl(url, timeout, null);
    }

    public String[] extractTextFromUrl(String url, int timeout, String cookieHeader) {
        logger.info("Fetching URL: {}", url);
        if (renderEnabled) {
            try {
                String[] rendered = extractRenderedTextFromUrl(url, timeout, cookieHeader);
                boolean looksLikeLogin = looksLikeLoginPage(rendered[0], rendered[1]);
                int renderedLen = rendered[0] != null ? rendered[0].trim().length() : 0;
                if (looksLikeLogin && renderedLen < MIN_SUBSTANTIAL_TEXT_CHARS) {
                    logger.warn("Rendered extraction looks like a login page with little text ({}) for {}, falling back to static fetch",
                            renderedLen, url);
                } else if (renderedLen >= MIN_RENDERED_TEXT_CHARS) {
                    logger.info("Using rendered extraction for {}, chars={}", url, rendered[0].length());
                    return rendered;
                } else {
                    logger.warn("Rendered extraction produced too little text for {}, falling back to static fetch", url);
                }
            } catch (Exception e) {
                logger.warn("Rendered extraction failed for {}, falling back to static fetch: {}", url, e.getMessage());
            }
        }

        try {
            HttpURLConnection conn = openValidatedConnection(url, timeout, 0, cookieHeader);

            String contentType = conn.getContentType();
            if (contentType == null) contentType = "text/html";

            byte[] htmlBytes;
            try (InputStream is = conn.getInputStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    if (bos.size() + n > MAX_RESPONSE_BYTES) {
                        throw new IllegalArgumentException("response exceeds max size: " + MAX_RESPONSE_BYTES + " bytes");
                    }
                    bos.write(buf, 0, n);
                }
                htmlBytes = bos.toByteArray();
            }

            // Detect charset
            String headSample = new String(htmlBytes, 0, Math.min(htmlBytes.length, 4096),
                    StandardCharsets.ISO_8859_1);
            String charset = detectCharset(contentType, headSample);

            // Decode HTML
            String htmlText;
            try {
                htmlText = new String(htmlBytes, Charset.forName(charset));
            } catch (Exception e) {
                htmlText = new String(htmlBytes, StandardCharsets.UTF_8);
            }

            // Extract title
            String title = null;
            Matcher titleMatcher = TITLE_PATTERN.matcher(htmlText);
            if (titleMatcher.find()) {
                title = titleMatcher.group(1).trim();
            }

            // Remove comments
            htmlText = COMMENT_PATTERN.matcher(htmlText).replaceAll("");
            htmlText = NON_VISIBLE_BLOCK_PATTERN.matcher(htmlText).replaceAll("");

            // Extract visible text
            String text = extractVisibleText(htmlText);

            if (text.isEmpty()) {
                logger.warn("No visible text extracted from {}", url);
                return new String[]{"", title};
            }

            logger.info("Extracted {} chars from {} {}", text.length(), url,
                    title != null ? "(title: " + title + ")" : "");
            return new String[]{text, title};

        } catch (Exception e) {
            logger.error("Failed to extract text from URL: {}", url, e);
            throw new RuntimeException("Web extraction failed: " + e.getMessage(), e);
        }
    }

    private String[] extractRenderedTextFromUrl(String rawUrl, int timeout, String cookieHeader) throws Exception {
        URI uri = URI.create(rawUrl).normalize();
        validatePublicHttpUri(uri);

        Path browserPath = resolveBrowserPath();
        if (browserPath == null) {
            throw new IllegalStateException("Chrome/Edge executable not found; set app.web-render.browser-path");
        }
        logger.info("Using browser: {}", browserPath);

        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(true)
                     .setExecutablePath(browserPath)
                     .setArgs(List.of(
                             "--disable-gpu",
                             "--disable-dev-shm-usage",
                             "--disable-extensions",
                             "--no-first-run",
                             "--no-default-browser-check",
                             "--disable-blink-features=AutomationControlled",
                             "--disable-features=IsolateOrigins,site-per-process",
                             "--no-sandbox"
                     )))) {

            Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                    .setIgnoreHTTPSErrors(true)
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .setBypassCSP(true)
                    .setJavaScriptEnabled(true)
                    .setLocale("zh-CN");
            var context = browser.newContext(contextOptions);
            List<Cookie> cookies = parseCookieHeader(uri, cookieHeader);
            if (!cookies.isEmpty()) {
                context.addCookies(cookies);
            }
            // Register route guard, but allow the main page URL without re-validation
            String targetUrl = rawUrl;
            context.route("**/*", route -> guardBrowserRequest(route, targetUrl));

            Page page = context.newPage();
            try {
                com.microsoft.playwright.options.Response response = page.navigate(rawUrl,
                        new Page.NavigateOptions()
                                .setTimeout(timeout * 1000.0)
                                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
                if (response != null) {
                    logger.info("Page {} responded with status {}, headers content-type={}",
                            rawUrl, response.status(), response.headerValue("content-type"));
                }
            } catch (Exception navEx) {
                logger.error("Navigation failed for {}: {}", rawUrl, navEx.getMessage());
                throw navEx;
            }
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(Math.min(timeout * 1000.0, 10_000.0)));
            } catch (Exception ignored) {
                // Some SPAs keep long-polling connections open; a fixed wait still lets client rendering finish.
            }
            if (renderWaitMs > 0) {
                page.waitForTimeout(renderWaitMs);
            }

            // Evaluate page state diagnostics
            String title = page.title();
            Object bodyHtmlLen = page.evaluate("() => document.body ? document.body.innerHTML.length : -1");
            logger.info("Page diagnostics for {}: title='{}', bodyHtmlLen={}", rawUrl, title, bodyHtmlLen);

            // Try multiple text extraction methods
            Object textObject = page.evaluate(
                    "() => {"
                    + "  var body = document.body;"
                    + "  if (!body) return '';"
                    + "  var text = body.innerText;"
                    + "  if (!text || text.trim().length < 50) {"
                    + "    text = body.textContent;"
                    + "  }"
                    + "  return text || '';"
                    + "}");
            String text = normalizeExtractedText(textObject == null ? "" : textObject.toString());

            logger.info("Rendered extraction got {} chars from {} {}", text.length(), rawUrl,
                    title != null && !title.isBlank() ? "(title: " + title + ")" : "");
            return new String[]{text, title == null || title.isBlank() ? null : title};
        }
    }

    private void guardBrowserRequest(Route route, String targetUrl) {
        try {
            String requestUrl = route.request().url();
            // Allow the main page URL without re-validation (already validated)
            if (targetUrl != null && requestUrl.startsWith(targetUrl)) {
                route.resume();
                return;
            }
            String scheme = URI.create(requestUrl).getScheme();
            if ("data".equalsIgnoreCase(scheme) || "blob".equalsIgnoreCase(scheme) || "about".equalsIgnoreCase(scheme)) {
                route.resume();
                return;
            }
            validatePublicHttpUri(URI.create(requestUrl).normalize());
            route.resume();
        } catch (Exception e) {
            logger.warn("Blocked browser subrequest during web extraction: {}", route.request().url());
            route.abort();
        }
    }

    private Path resolveBrowserPath() {
        if (configuredBrowserPath != null && !configuredBrowserPath.isBlank()) {
            Path configured = Paths.get(configuredBrowserPath);
            if (Files.isRegularFile(configured)) {
                return configured;
            }
        }
        for (String candidate : DEFAULT_BROWSER_PATHS) {
            Path path = Paths.get(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    private String detectCharset(String contentType, String htmlHead) {
        // From HTTP header
        Matcher m = CHARSET_HEADER_PATTERN.matcher(contentType);
        if (m.find()) return m.group(1);

        // From <meta charset="...">
        m = META_CHARSET_PATTERN.matcher(htmlHead);
        if (m.find()) return m.group(1);

        return "utf-8";
    }

    private HttpURLConnection openValidatedConnection(String rawUrl, int timeout, int redirectCount) throws Exception {
        return openValidatedConnection(rawUrl, timeout, redirectCount, null);
    }

    private HttpURLConnection openValidatedConnection(String rawUrl, int timeout, int redirectCount, String cookieHeader) throws Exception {
        if (redirectCount > MAX_REDIRECTS) {
            throw new IllegalArgumentException("too many redirects");
        }

        URI uri = URI.create(rawUrl).normalize();
        validatePublicHttpUri(uri);

        URL validatedUrl = uri.toURL();
        HttpURLConnection conn = (HttpURLConnection) validatedUrl.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeout * 1000);
        conn.setReadTimeout(timeout * 1000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        conn.setRequestProperty("Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        if (cookieHeader != null && !cookieHeader.isBlank()) {
            conn.setRequestProperty("Cookie", sanitizeCookieHeader(cookieHeader));
        }

        int status = conn.getResponseCode();
        if (status >= 300 && status < 400) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if (location == null || location.isBlank()) {
                throw new IllegalArgumentException("redirect response missing Location header");
            }
            URI next = uri.resolve(location).normalize();
            return openValidatedConnection(next.toString(), timeout, redirectCount + 1, cookieHeader);
        }

        return conn;
    }

    private List<Cookie> parseCookieHeader(URI uri, String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return List.of();
        }
        String sanitized = sanitizeCookieHeader(cookieHeader);
        List<Cookie> cookies = new ArrayList<>();
        String[] pairs = sanitized.split(";");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String name = pair.substring(0, idx).trim();
            String value = pair.substring(idx + 1).trim();
            if (name.isEmpty()) {
                continue;
            }
            cookies.add(new Cookie(name, value)
                    .setDomain(uri.getHost())
                    .setPath("/")
                    .setSecure("https".equalsIgnoreCase(uri.getScheme())));
        }
        return cookies;
    }

    private String sanitizeCookieHeader(String cookieHeader) {
        return cookieHeader.replace("\r", "").replace("\n", "").trim();
    }

    private void validatePublicHttpUri(URI uri) throws Exception {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("unsupported URL scheme: " + uri);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL host cannot be empty");
        }

        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (isBlockedAddress(address)) {
                throw new IllegalArgumentException("URL resolves to a private or local address: " + host);
            }
        }
    }

    private boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            int first = bytes[0] & 0xff;
            return first == 0xfc || first == 0xfd;
        }
        return false;
    }

    private String extractVisibleText(String html) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();

        int i = 0;
        while (i < html.length()) {
            if (html.charAt(i) == '<') {
                // Flush current text
                String text = current.toString().trim();
                if (!text.isEmpty()) parts.add(text);
                current = new StringBuilder();

                int close = html.indexOf('>', i);
                if (close < 0) break;
                String tag = html.substring(i + 1, close);
                if (tag.endsWith("/")) {
                    i = close + 1;
                    continue;
                }
                String tagName = tag.split("\\s")[0].toLowerCase();
                if (tagName.startsWith("/")) {
                    // Closing tag
                    tagName = tagName.substring(1);
                    if (depth > 0 && ("script".equals(tagName) || "style".equals(tagName) ||
                            "noscript".equals(tagName) || "iframe".equals(tagName) ||
                            "svg".equals(tagName) || "head".equals(tagName))) {
                        depth--;
                    }
                    // Insert newline after block-level elements
                    if ("br".equals(tagName) || "p".equals(tagName) || "div".equals(tagName) ||
                            "li".equals(tagName) || "tr".equals(tagName) ||
                            "h1".equals(tagName) || "h2".equals(tagName) || "h3".equals(tagName) ||
                            "h4".equals(tagName) || "h5".equals(tagName) || "h6".equals(tagName)) {
                        if (!parts.isEmpty() && !"\n".equals(parts.get(parts.size() - 1))) {
                            parts.add("\n");
                        }
                    }
                } else {
                    // Opening tag
                    if ("script".equals(tagName) || "style".equals(tagName) ||
                            "noscript".equals(tagName) || "iframe".equals(tagName) ||
                            "svg".equals(tagName) || "head".equals(tagName)) {
                        depth++;
                    }
                }
                i = close + 1;
            } else {
                char c = html.charAt(i);
                if (depth == 0) {
                    if (c == '\n' || c == '\r' || c == '\t') {
                        c = ' ';
                    }
                    current.append(c);
                }
                i++;
            }
        }

        // Flush remaining
        String text = current.toString().trim();
        if (!text.isEmpty()) parts.add(text);

        String result = String.join(" ", parts);
        return normalizeExtractedText(result);
    }

    private String normalizeExtractedText(String text) {
        String result = text == null ? "" : text.replace('\r', '\n').replace('\t', ' ');
        result = MULTI_SPACE_PATTERN.matcher(result).replaceAll(" ");
        result = MULTI_NEWLINE_PATTERN.matcher(result).replaceAll("\n\n");
        return result.trim();
    }

    private boolean looksLikeLoginPage(String text, String title) {
        String combined = ((title == null ? "" : title) + "\n" + (text == null ? "" : text)).toLowerCase(Locale.ROOT);
        // Only treat as login page when explicit login+password indicators exist together
        boolean hasLoginIndicator = combined.contains("登录") || combined.contains("login")
                || combined.contains("sign in") || combined.contains("signin");
        boolean hasPasswordIndicator = combined.contains("密码") || combined.contains("password");
        if (hasLoginIndicator && hasPasswordIndicator) {
            return true;
        }
        // Passport/通行证 alone could appear in news/articles, only match with other login cues
        boolean hasPassport = combined.contains("passport") || combined.contains("通行证");
        if (hasPassport && (hasLoginIndicator || hasPasswordIndicator)) {
            return true;
        }
        return false;
    }
}
