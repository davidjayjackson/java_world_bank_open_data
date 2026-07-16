package com.example.worldbank;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin client over the World Bank Open Data Indicators API
 * (https://api.worldbank.org/v2/) - no API key required.
 *
 * <ul>
 *   <li>Uses only the JDK: {@link HttpURLConnection} for I/O and the
 *       hand-rolled {@link Json} parser, so no third-party jars are bundled
 *       (avoids classloader conflicts inside the LibreOffice-embedded JVM).</li>
 *   <li>Every list endpoint's top-level JSON is a two-element array: index 0
 *       is a pagination/error header object, index 1 is the data array (or
 *       {@code null} when the indicator/country combination has no data).
 *       An invalid indicator or country code replaces the header's
 *       pagination fields with a {@code message} array - this is detected
 *       and surfaced as an {@link IOException} distinct from a genuine
 *       empty result.</li>
 *   <li>HTTP 429 / 5xx responses are retried a bounded number of times with
 *       exponential backoff; other non-200 responses fail immediately.</li>
 * </ul>
 */
final class WorldBankClient {

    private static final String BASE = "https://api.worldbank.org/v2";
    private static final String USER_AGENT =
            "LibreOffice-WorldBank-AddIn/1.0 (+https://github.com/davidjayjackson/java_world_bank_open_data)";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 1000;

    /** Safety cap on page-following for a single request; normal per_page=20000 requests need one page. */
    private static final int MAX_PAGES = 40;

    private WorldBankClient() {
    }

    // ------------------------------------------------------------------ //
    // HTTP                                                                //
    // ------------------------------------------------------------------ //

    private static String enc(String v) {
        try {
            return URLEncoder.encode(v, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unavailable", e); // never happens
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }

    /** GET a fully-formed URL, retrying on 429/5xx with bounded exponential backoff. */
    private static String httpGet(String url) throws IOException {
        long backoff = INITIAL_BACKOFF_MS;
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", USER_AGENT);

                int status = conn.getResponseCode();
                if (status == 200) {
                    return readAll(conn.getInputStream());
                }

                String body = readAll(conn.getErrorStream());
                if (status == 429 || status >= 500) {
                    lastFailure = new IOException("World Bank API returned HTTP " + status
                            + (body.isEmpty() ? "" : ": " + body));
                    if (attempt < MAX_ATTEMPTS) {
                        try {
                            Thread.sleep(backoff);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw lastFailure;
                        }
                        backoff *= 2;
                        continue;
                    }
                    throw lastFailure;
                }

                throw new IOException("World Bank API returned HTTP " + status
                        + (body.isEmpty() ? "" : ": " + body));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw lastFailure != null ? lastFailure
                : new IOException("World Bank API request failed after " + MAX_ATTEMPTS + " attempts");
    }

    // ------------------------------------------------------------------ //
    // Response-shape helpers                                              //
    // ------------------------------------------------------------------ //

    @SuppressWarnings("unchecked")
    private static String errorMessage(Object message) {
        if (message instanceof List) {
            List<Object> list = (List<Object>) message;
            if (!list.isEmpty() && list.get(0) instanceof Map) {
                Object v = ((Map<String, Object>) list.get(0)).get("value");
                if (v != null) return String.valueOf(v);
            }
        }
        return String.valueOf(message);
    }

    // ------------------------------------------------------------------ //
    // Endpoints                                                           //
    // ------------------------------------------------------------------ //

    /**
     * Fetch observations for one or more countries (semicolon-joined ISO2/
     * ISO3 codes, or "all") and one indicator, optionally filtered by a date
     * expression ("2000:2023" or "2020") or limited to the {@code mrv} most
     * recent non-null values. Returns an empty list when the API reports no
     * data for the combination (not an error).
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> fetchSeries(String countries, String indicatorId, String date, Integer mrv)
            throws IOException {
        StringBuilder u = new StringBuilder(BASE)
                .append("/country/").append(enc(countries))
                .append("/indicator/").append(enc(indicatorId))
                .append("?format=json&per_page=20000");
        if (date != null) {
            u.append("&date=").append(enc(date));
        }
        if (mrv != null) {
            u.append("&mrv=").append(mrv.intValue());
        }
        String baseUrl = u.toString();

        List<Map<String, Object>> all = new ArrayList<Map<String, Object>>();
        int page = 1;
        int pages = 1;
        do {
            Object root = Json.parse(httpGet(baseUrl + "&page=" + page));
            if (!(root instanceof List) || ((List<Object>) root).size() < 1) {
                throw new IOException("Unexpected World Bank response shape");
            }
            List<Object> arr = (List<Object>) root;
            Object headerObj = arr.get(0);
            if (headerObj instanceof Map) {
                Map<String, Object> header = (Map<String, Object>) headerObj;
                Object message = header.get("message");
                if (message != null) {
                    throw new IOException("World Bank API error: " + errorMessage(message));
                }
                Object pagesVal = header.get("pages");
                if (pagesVal instanceof Number) {
                    pages = ((Number) pagesVal).intValue();
                }
            }
            Object dataObj = arr.size() > 1 ? arr.get(1) : null;
            if (dataObj instanceof List) {
                for (Object o : (List<Object>) dataObj) {
                    if (o instanceof Map) {
                        all.add((Map<String, Object>) o);
                    }
                }
            }
            // dataObj null -> no data for this combination; nothing to add, loop ends below.
            page++;
        } while (page <= pages && page <= MAX_PAGES);

        return all;
    }

    /**
     * Fetch an indicator's metadata (name, source, source note). Returns
     * {@code null} when the API reports no data for the code; an invalid
     * code instead throws (its header carries a {@code message}).
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> fetchIndicatorMeta(String indicatorId) throws IOException {
        String url = BASE + "/indicator/" + enc(indicatorId) + "?format=json";
        Object root = Json.parse(httpGet(url));
        if (!(root instanceof List)) {
            throw new IOException("Unexpected World Bank response shape");
        }
        List<Object> arr = (List<Object>) root;
        Object headerObj = arr.isEmpty() ? null : arr.get(0);
        if (headerObj instanceof Map) {
            Object message = ((Map<String, Object>) headerObj).get("message");
            if (message != null) {
                throw new IOException("World Bank API error: " + errorMessage(message));
            }
        }
        Object dataObj = arr.size() > 1 ? arr.get(1) : null;
        if (!(dataObj instanceof List) || ((List<Object>) dataObj).isEmpty()) {
            return null;
        }
        Object first = ((List<Object>) dataObj).get(0);
        return first instanceof Map ? (Map<String, Object>) first : null;
    }
}
