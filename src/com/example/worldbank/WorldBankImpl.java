package com.example.worldbank;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sun.star.lang.Locale;
import com.sun.star.lang.XSingleComponentFactory;
import com.sun.star.lib.uno.helper.Factory;
import com.sun.star.lib.uno.helper.WeakBase;
import com.sun.star.registry.XRegistryKey;
import com.sun.star.uno.Any;

/**
 * LibreOffice Calc add-in exposing World Bank Open Data indicator values as
 * worksheet functions. No API key is required.
 *
 * <p>Implements the custom {@link XWorldBank} interface plus the standard
 * add-in plumbing ({@code com.sun.star.sheet.XAddIn}, {@code XServiceName},
 * {@code XServiceInfo}). Function display names, descriptions and
 * per-argument help live in config/CalcAddIns.xcu; the {@code XAddIn}
 * accessors below return the programmatic names as a safe fallback.
 *
 * <p>Every function resolves through {@link WorldBankCache} (see that class
 * for the non-blocking cache + background-fetch pattern) and returns either
 * the requested data or one of three sentinel strings understood by the
 * user: {@code #FETCHING}, {@code #NOT_FOUND}, {@code #ERR} (detail via
 * {@link #wbLastError()}). Cell functions never throw.
 *
 * <p>{@link #wbValue}, {@link #wbSeries} and {@link #wbLatest} all read from
 * a single full-history fetch per (country, indicator) pair - the World
 * Bank Indicators API returns a country/indicator's entire series cheaply
 * in one request, so "latest value" and "value for year Y" are answered
 * client-side from the same cached series rather than issuing a separate
 * {@code mrv=1} or {@code date=Y} request. This means calling all three for
 * the same country/indicator triggers exactly one background fetch.
 */
public final class WorldBankImpl extends WeakBase
        implements XWorldBank,
                   com.sun.star.sheet.XAddIn,
                   com.sun.star.lang.XServiceName,
                   com.sun.star.lang.XServiceInfo {

    /** Implementation name: must match the AddInInfo node in CalcAddIns.xcu. */
    private static final String IMPLEMENTATION_NAME = "com.example.worldbank.WorldBankImpl";

    /** The one service that marks this component as a Calc add-in. */
    private static final String ADDIN_SERVICE = "com.sun.star.sheet.AddIn";

    private static final String[] SERVICE_NAMES = { ADDIN_SERVICE, IMPLEMENTATION_NAME };

    /** Current locale (tracked for XLocalizable; metadata is English-only here). */
    private Locale locale = new Locale("en", "US", "");

    // ------------------------------------------------------------------ //
    // Sentinels + cache TTLs                                             //
    // ------------------------------------------------------------------ //

    private static final String FETCHING = "#FETCHING";
    private static final String NOT_FOUND = "#NOT_FOUND";
    private static final String ERR = "#ERR";

    // World Bank annual data changes rarely; a long TTL avoids needless refetches.
    private static final long TTL_SERIES = 24L * 3600 * 1000; // 24h
    private static final long TTL_META = 7L * 24 * 3600 * 1000; // 7d

    // ------------------------------------------------------------------ //
    // XWorldBank - the actual worksheet functions                        //
    // ------------------------------------------------------------------ //

    public Object wbValue(String country, String indicator, Object yearArg) {
        final String c = norm(country);
        final String ind = norm(indicator);
        if (c.isEmpty() || ind.isEmpty()) return NOT_FOUND;

        WorldBankCache.Result r = fetchFullSeries(c, ind);
        if (FETCHING.equals(status(r))) return FETCHING;
        if (ERR.equals(status(r))) return ERR;

        List<Map<String, Object>> obs = cast(r.data);
        if (obs.isEmpty()) return NOT_FOUND;

        Double yearNum = optDouble(yearArg);
        if (yearNum == null) {
            Map<String, Object> latest = pickLatest(obs);
            return latest == null ? NOT_FOUND : latest.get("value");
        }
        String year = yearStr(yearNum);
        Map<String, Object> match = pickYear(obs, year);
        return match == null ? NOT_FOUND : match.get("value");
    }

    public Object[][] wbSeries(String country, String indicator, Object startArg, Object endArg) {
        final String c = norm(country);
        final String ind = norm(indicator);
        if (c.isEmpty() || ind.isEmpty()) return sentinelTable(NOT_FOUND);

        WorldBankCache.Result r = fetchFullSeries(c, ind);
        if (FETCHING.equals(status(r))) return sentinelTable(FETCHING);
        if (ERR.equals(status(r))) return sentinelTable(ERR);

        List<Map<String, Object>> obs = cast(r.data);
        if (obs.isEmpty()) return sentinelTable(NOT_FOUND);

        Double startNum = optDouble(startArg);
        Double endNum = optDouble(endArg);
        Integer start = startNum == null ? null : Integer.valueOf(startNum.intValue());
        Integer end = endNum == null ? null : Integer.valueOf(endNum.intValue());

        List<Map<String, Object>> filtered = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> o : obs) {
            Integer y = yearOf(o);
            if (y == null) continue;
            if (start != null && y.intValue() < start.intValue()) continue;
            if (end != null && y.intValue() > end.intValue()) continue;
            filtered.add(o);
        }
        if (filtered.isEmpty()) return sentinelTable(NOT_FOUND);

        java.util.Collections.sort(filtered, new java.util.Comparator<Map<String, Object>>() {
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                int ya = yearOf(a).intValue();
                int yb = yearOf(b).intValue();
                if (ya != yb) return ya - yb;
                return countryCode(a).compareTo(countryCode(b));
            }
        });

        boolean multiCountry = distinctCountryCount(filtered) > 1;
        int cols = multiCountry ? 3 : 2;
        Object[][] out = new Object[filtered.size()][cols];
        for (int i = 0; i < filtered.size(); i++) {
            Map<String, Object> o = filtered.get(i);
            int col = 0;
            if (multiCountry) out[i][col++] = countryCode(o);
            out[i][col++] = yearOf(o);
            Object v = o.get("value");
            out[i][col] = v == null ? "" : v;
        }
        return out;
    }

    public Object[][] wbLatest(String country, String indicator) {
        final String c = norm(country);
        final String ind = norm(indicator);
        if (c.isEmpty() || ind.isEmpty()) return sentinelTable(NOT_FOUND);

        WorldBankCache.Result r = fetchFullSeries(c, ind);
        if (FETCHING.equals(status(r))) return sentinelTable(FETCHING);
        if (ERR.equals(status(r))) return sentinelTable(ERR);

        List<Map<String, Object>> obs = cast(r.data);
        Map<String, Object> latest = obs.isEmpty() ? null : pickLatest(obs);
        if (latest == null) return sentinelTable(NOT_FOUND);

        return new Object[][] { { latest.get("value"), yearOf(latest) } };
    }

    public Object wbMeta(String indicator) {
        final String ind = norm(indicator);
        if (ind.isEmpty()) return NOT_FOUND;

        String key = "meta;ind=" + ind;
        WorldBankCache.Result r = WorldBankCache.get(key, TTL_META, new WorldBankCache.Fetcher() {
            public Object fetch() throws Exception { return WorldBankClient.fetchIndicatorMeta(ind); }
        });
        if (FETCHING.equals(status(r))) return FETCHING;
        if (ERR.equals(status(r))) return ERR;

        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) r.data;
        if (meta == null) return NOT_FOUND;
        Object name = meta.get("name");
        return name == null ? NOT_FOUND : name;
    }

    public String wbLastError() {
        return WorldBankCache.lastError();
    }

    public double wbCacheClear() {
        return WorldBankCache.clear();
    }

    // ------------------------------------------------------------------ //
    // Domain helpers                                                      //
    // ------------------------------------------------------------------ //

    private static WorldBankCache.Result fetchFullSeries(final String country, final String indicator) {
        String key = "series;country=" + country + ";ind=" + indicator;
        return WorldBankCache.get(key, TTL_SERIES, new WorldBankCache.Fetcher() {
            public Object fetch() throws Exception { return WorldBankClient.fetchSeries(country, indicator, null, null); }
        });
    }

    private static String status(WorldBankCache.Result r) {
        if (WorldBankCache.Result.LOADING.equals(r.status)) return FETCHING;
        if (WorldBankCache.Result.ERROR.equals(r.status)) return ERR;
        return WorldBankCache.Result.READY;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cast(Object o) {
        return (List<Map<String, Object>>) o;
    }

    /** The most recent observation with a non-null value; assumes API order (newest first) but re-checks defensively. */
    private static Map<String, Object> pickLatest(List<Map<String, Object>> obs) {
        Map<String, Object> best = null;
        Integer bestYear = null;
        for (Map<String, Object> o : obs) {
            if (o.get("value") == null) continue;
            Integer y = yearOf(o);
            if (y == null) continue;
            if (bestYear == null || y.intValue() > bestYear.intValue()) {
                best = o;
                bestYear = y;
            }
        }
        return best;
    }

    /** The first observation matching the given year with a non-null value. */
    private static Map<String, Object> pickYear(List<Map<String, Object>> obs, String year) {
        for (Map<String, Object> o : obs) {
            if (year.equals(str(o.get("date"))) && o.get("value") != null) {
                return o;
            }
        }
        return null;
    }

    private static Integer yearOf(Map<String, Object> o) {
        String d = str(o.get("date"));
        try {
            return Integer.valueOf(d.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static String countryCode(Map<String, Object> o) {
        Object countryObj = o.get("country");
        if (countryObj instanceof Map) {
            Object id = ((Map<String, Object>) countryObj).get("id");
            if (id != null) return String.valueOf(id);
        }
        return str(o.get("countryiso3code"));
    }

    private static int distinctCountryCount(List<Map<String, Object>> obs) {
        java.util.Set<String> codes = new java.util.HashSet<String>();
        for (Map<String, Object> o : obs) {
            codes.add(countryCode(o));
        }
        return codes.size();
    }

    private static Object[][] sentinelTable(String sentinel) {
        return new Object[][] { { sentinel } };
    }

    private static String yearStr(double d) {
        return String.valueOf((long) d);
    }

    // ------------------------------------------------------------------ //
    // Argument / value helpers                                           //
    // ------------------------------------------------------------------ //

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** Trim and uppercase a country/indicator code so "us"/"US" and "ny.gdp..."/"NY.GDP..." share one cache entry. */
    private static String norm(String s) {
        return s == null ? "" : s.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** Unwrap a 1x1 matrix (a single-cell reference may arrive as Object[][]). */
    private static Object scalar(Object arg) {
        if (arg instanceof Object[][]) {
            Object[][] m = (Object[][]) arg;
            return (m.length > 0 && m[0].length > 0) ? m[0][0] : null;
        }
        return arg;
    }

    /** Interpret an optional numeric argument; VOID/empty/non-numeric -> null. */
    private static Double optDouble(Object arg) {
        arg = scalar(arg);
        if (arg == null || arg instanceof Any) {
            return null; // omitted argument arrives as VOID Any
        }
        if (arg instanceof Number) {
            return ((Number) arg).doubleValue();
        }
        String s = String.valueOf(arg).trim();
        if (s.isEmpty()) return null;
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ //
    // XAddIn - function metadata                                         //
    //                                                                    //
    // Calc uses getDisplayFunctionName() as the AUTHORITATIVE display    //
    // (formula) name; CalcAddIns.xcu only supplies wizard help. So these //
    // must map programmatic <-> display names explicitly, or the cell    //
    // formula (=WBVALUE(...)) resolves to #NAME?.                       //
    // ------------------------------------------------------------------ //

    /** { programmatic, display } for every exposed function. */
    private static final String[][] FUNCS = {
        { "wbValue",      "WBVALUE" },
        { "wbSeries",     "WBSERIES" },
        { "wbLatest",     "WBLATEST" },
        { "wbMeta",       "WBMETA" },
        { "wbLastError",  "WBLASTERROR" },
        { "wbCacheClear", "WBCACHECLEAR" },
    };

    private static String funcDescription(String prog) {
        if ("wbValue".equals(prog)) return "Returns a World Bank indicator value for a country and year (or the most recent value if year is omitted).";
        if ("wbSeries".equals(prog)) return "Returns a time series of [year, value] pairs for a country and indicator as a spillable array.";
        if ("wbLatest".equals(prog)) return "Returns the most recent value plus its year as a two-cell row, for dashboards.";
        if ("wbMeta".equals(prog)) return "Returns an indicator's human-readable name, for labeling.";
        if ("wbLastError".equals(prog)) return "Returns the most recent fetch error message, for diagnostics.";
        if ("wbCacheClear".equals(prog)) return "Clears every cached response; returns the number of entries cleared.";
        return "";
    }

    private static String[] argNames(String prog) {
        if ("wbValue".equals(prog)) return new String[] { "country", "indicator", "year" };
        if ("wbSeries".equals(prog)) return new String[] { "country", "indicator", "start_year", "end_year" };
        if ("wbLatest".equals(prog)) return new String[] { "country", "indicator" };
        if ("wbMeta".equals(prog)) return new String[] { "indicator" };
        return new String[0];
    }

    private static String[] argDescriptions(String prog) {
        if ("wbValue".equals(prog)) {
            return new String[] {
                "ISO2/ISO3 country code, e.g. \"US\" or \"USA\".",
                "World Bank indicator code, e.g. \"NY.GDP.MKTP.CD\".",
                "Optional. A 4-digit year. Omit for the most recent available value.",
            };
        }
        if ("wbSeries".equals(prog)) {
            return new String[] {
                "ISO2/ISO3 country code, e.g. \"US\" or \"USA\".",
                "World Bank indicator code, e.g. \"NY.GDP.MKTP.CD\".",
                "Optional. First year of the range (inclusive). Omit for the full available series.",
                "Optional. Last year of the range (inclusive).",
            };
        }
        if ("wbLatest".equals(prog)) {
            return new String[] {
                "ISO2/ISO3 country code, e.g. \"US\" or \"USA\".",
                "World Bank indicator code, e.g. \"NY.GDP.MKTP.CD\".",
            };
        }
        if ("wbMeta".equals(prog)) {
            return new String[] { "World Bank indicator code, e.g. \"NY.GDP.MKTP.CD\"." };
        }
        return new String[0];
    }

    public String getProgrammaticFuntionName(String displayName) {
        for (String[] f : FUNCS) {
            if (f[1].equals(displayName)) return f[0];
        }
        return "";
    }

    public String getDisplayFunctionName(String programmaticName) {
        for (String[] f : FUNCS) {
            if (f[0].equals(programmaticName)) return f[1];
        }
        return "";
    }

    public String getFunctionDescription(String programmaticName) {
        return funcDescription(programmaticName);
    }

    public String getDisplayArgumentName(String programmaticName, int argument) {
        String[] a = argNames(programmaticName);
        return (argument >= 0 && argument < a.length) ? a[argument] : "";
    }

    public String getArgumentDescription(String programmaticName, int argument) {
        String[] a = argDescriptions(programmaticName);
        return (argument >= 0 && argument < a.length) ? a[argument] : "";
    }

    public String getProgrammaticCategoryName(String programmaticName) {
        return "Add-In";
    }

    public String getDisplayCategoryName(String programmaticName) {
        return "Add-In";
    }

    // ------------------------------------------------------------------ //
    // XLocalizable (inherited via XAddIn)                                //
    // ------------------------------------------------------------------ //

    public void setLocale(Locale locale) {
        this.locale = locale;
    }

    public Locale getLocale() {
        return locale;
    }

    // ------------------------------------------------------------------ //
    // XServiceName / XServiceInfo                                        //
    // ------------------------------------------------------------------ //

    public String getServiceName() {
        return IMPLEMENTATION_NAME;
    }

    public String getImplementationName() {
        return IMPLEMENTATION_NAME;
    }

    public boolean supportsService(String service) {
        for (String s : SERVICE_NAMES) {
            if (s.equals(service)) return true;
        }
        return false;
    }

    public String[] getSupportedServiceNames() {
        return SERVICE_NAMES.clone();
    }

    // ------------------------------------------------------------------ //
    // UNO component registration entry points                           //
    // ------------------------------------------------------------------ //

    public static XSingleComponentFactory __getComponentFactory(String implName) {
        if (IMPLEMENTATION_NAME.equals(implName)) {
            return Factory.createComponentFactory(WorldBankImpl.class, SERVICE_NAMES);
        }
        return null;
    }

    public static boolean __writeRegistryServiceInfo(XRegistryKey regKey) {
        return Factory.writeRegistryServiceInfo(IMPLEMENTATION_NAME, SERVICE_NAMES, regKey);
    }
}
