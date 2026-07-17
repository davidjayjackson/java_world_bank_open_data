<p align="center">
  <img src="assets/banner.png" alt="World Bank Open Data Calc Add-In" width="100%">
</p>

# World Bank Open Data Calc Add-In

A LibreOffice Calc add-in (UNO component, **Java**, MIT licensed) exposing
indicator data from the [World Bank Open Data Indicators API](https://api.worldbank.org/v2/)
as worksheet functions. **No API key required.**

> New here? Follow the step-by-step [**Tutorial**](docs/TUTORIAL.md) — it
> walks through every function and feature with copy-pasteable formulas.

| Function | Signature | Returns | Columns |
|----------|-----------|---------|---------|
| `WBVALUE`     | `WBVALUE(country; indicator; [year])`               | a single indicator value (number), or the most recent available value if `year` is omitted | *(scalar, no columns)* |
| `WBSERIES`    | `WBSERIES(country; indicator; [start_year]; [end_year])` | spillable array: `[year, value]` rows, ascending by year (a `country` column is added when you pass more than one country code) | `year, value` — or `country, year, value` for more than one country code |
| `WBLATEST`    | `WBLATEST(country; indicator)`                      | spillable two-cell row: `[value, year]` | `value, year` |
| `WBMETA`      | `WBMETA(indicator)`                                 | the indicator's human-readable name, e.g. `"GDP (current US$)"` | *(scalar, no columns)* |
| `WBLASTERROR` | `WBLASTERROR()`                                     | most recent fetch error message (diagnostics) | *(scalar, no columns)* |
| `WBCACHECLEAR`| `WBCACHECLEAR()`                                    | clears the cache; returns the number of entries cleared (best-effort, see note below) | *(scalar, no columns)* |

> In Calc's UI, arguments are separated by **semicolons**:
> `=WBVALUE("US"; "NY.GDP.MKTP.CD"; 2020)`.
>
> `country` accepts an ISO2 code (`"US"`), ISO3 code (`"USA"`), a World Bank
> aggregate code (`"WLD"` for World, `"EUU"` for the EU, etc.), or several
> codes joined by semicolons (`"US;GB;DE"`).

---

## Cell functions never block, and never throw

Every function above resolves against a shared cache and returns
**immediately** — none of them ever block on network I/O or raise an
exception. Instead, a cell may show one of:

| Value | Meaning |
|-------|---------|
| `#FETCHING` | First request for this data. A background fetch just started. Recalculate (**F9** or **Ctrl+Shift+F9**) once it completes — usually a second or two. |
| `#NOT_FOUND` | The request reached the API, but nothing matched (unknown indicator/country pairing, or every value in the requested range is null). |
| `#ERR` | The fetch failed persistently (network error, or the API rejected an invalid indicator/country code). Call `WBLASTERROR()` for the detail message. An errored key is retried automatically ~15s later, so recalculating again often clears it. |

World Bank annual data changes rarely, so responses are cached with a long
TTL and refreshed silently in the background once stale (you keep seeing the
last-known-good value while the refresh runs):

| Data | TTL |
|------|-----|
| Indicator series (`WBVALUE` / `WBSERIES` / `WBLATEST`) | 24 hours |
| Indicator metadata (`WBMETA`) | 7 days |

`WBVALUE`, `WBSERIES`, and `WBLATEST` for the same `(country, indicator)`
pair share **one** underlying fetch: the World Bank API returns a country's
entire indicator history cheaply in a single request, so the add-in pulls
the full series once and answers "value in year Y" or "most recent value"
client-side from that same cached data, instead of issuing a separate
request per function. Calling all three for the same country/indicator
therefore triggers exactly one background fetch.

The cache is a bounded, thread-safe (`ConcurrentHashMap`-backed) in-memory
store (up to 2000 entries, oldest evicted first) that lives for the life of
the LibreOffice session — call `WBCACHECLEAR()` to force fresh data, or
restart LibreOffice.

## Install (prebuilt)

Download `WorldBank.oxt` from the
[releases page](https://github.com/davidjayjackson/java_world_bank_open_data/releases)
and install it — no build required, no key to configure:

```bash
"$LO_HOME/program/unopkg" add --force WorldBank.oxt
```

Skip to [Try it](#try-it) below.

## Build the .oxt

```bash
export JAVA_HOME=~/jdks/jdk8u<version>   # any JDK 8+; see docs/INSTALL.md
export LO_HOME=~/libreoffice26.2         # LibreOffice + SDK
./build.sh
# or pass paths explicitly:
./build.sh --jdk ~/jdks/jdk8u<version> --libreoffice ~/libreoffice26.2
```

This runs `unoidl-write` → `javamaker` → `javac --release 8` → `jar` → zip,
producing **`build/WorldBank.oxt`**. See [docs/INSTALL.md](docs/INSTALL.md)
for full prerequisites (JDK 8, LibreOffice + SDK, the Java-vendor allow-list
fix) and platform-specific build/install steps (Slackware, Debian, Ubuntu,
Windows).

## Install

```bash
"$LO_HOME/program/unopkg" add --force build/WorldBank.oxt
```

Or double-click `build/WorldBank.oxt` to open the Extension Manager.
Restart LibreOffice afterwards.

## Try it

```
=WBVALUE("US"; "NY.GDP.MKTP.CD"; 2020)          -> 21375281000000
=WBVALUE("US"; "NY.GDP.MKTP.CD")                -> most recent year's GDP
=WBLATEST("US"; "NY.GDP.MKTP.CD")               -> spills {value, year}  (array formula)
=WBMETA("NY.GDP.MKTP.CD")                       -> "GDP (current US$)"
=WBSERIES("US"; "NY.GDP.MKTP.CD"; 2015; 2020)   -> spills 6 rows of {year, value}  (array formula)
=WBSERIES("US"; "NY.GDP.MKTP.CD")               -> spills the full available history (array formula)
=WBSERIES("US;GB"; "NY.GDP.MKTP.CD"; 2022; 2023) -> spills {country, year, value} rows  (array formula)
=WBLASTERROR()                                  -> "" (or the last failure's detail)
=WBCACHECLEAR()                                 -> number of entries cleared
```

A ready-made example workbook is at
[`test/worldbank_demo.ods`](test/worldbank_demo.ods) — every function above,
built around US and UK GDP. Live results are baked in from a real run, but
since the cache is per-session, reopening it will show `#FETCHING` again
until you recalculate (Ctrl+Shift+F9, a couple of times since fetches are
async). Regenerate it with `tools/build_demo.py` against a headless
LibreOffice instance.

## Behavior notes

- **Multi-cell / spilling.** LibreOffice has no dynamic spill: to see every
  row of a table-returning function, select the output range and enter it as
  an **array formula** (Ctrl+Shift+Enter, or tick **Array** in the Function
  Wizard). A plain single-cell entry shows only the top-left value.
- **`WBSERIES` column count.** Returns two columns (`year`, `value`) for a
  single country. When `country` names more than one code (semicolon-joined
  or `"all"`), a leading `country` column is added, so pick a wide enough
  output range in that case.
- **No header row.** `WBSERIES` and `WBLATEST` spill data only — the column
  names in the table above describe the value order, not literal text
  written to the sheet. Type your own header row above the array formula if
  you want labels.
- **Null values.** The World Bank API returns `value: null` for years a
  country/indicator has no data for. `WBSERIES` shows these years with an
  empty cell (never `0`); `WBVALUE`/`WBLATEST` skip null years entirely when
  picking "most recent" or a specific year, since a null observation isn't a
  real value.
- **Case-insensitive codes.** `country` and `indicator` are upper-cased
  internally (`"us"` and `"US"`, `"ny.gdp.mktp.cd"` and `"NY.GDP.MKTP.CD"`
  all share one cache entry), matching the API's own case-insensitivity.
- **`WBCACHECLEAR` count is best-effort.** LibreOffice's formula engine does
  not guarantee a cell's function runs exactly once per recalculation; a
  second, silent invocation can see an already-drained cache and overwrite
  the shown count with `0`. Treat it as a diagnostic nudge ("did something
  clear?"), not an authoritative count.
- **No third-party jars.** HTTP uses `java.net.HttpURLConnection`; JSON is
  parsed by a small hand-rolled, tolerant parser (`Json.java`). Nothing
  beyond the JDK + UNO is bundled — avoids classloader conflicts inside the
  LibreOffice-embedded JVM. Compiled to Java 8 bytecode/source only (no
  `var`, no records, no `HttpClient`, no switch expressions or text blocks).
- **`CompatibilityName`** is set for every function in `CalcAddIns.xcu`, so
  formulas survive a save-as/reopen round trip through XLS/XLSX.
- **Invalid indicator/country codes.** The API returns a JSON `message`
  array (not the usual pagination header) for an unrecognized code; the
  add-in detects this and surfaces `#ERR`, with the message text available
  via `WBLASTERROR()`.

## Common indicator codes

| Code | Indicator |
|------|-----------|
| `NY.GDP.MKTP.CD` | GDP (current US$) |
| `NY.GDP.MKTP.KD.ZG` | GDP growth (annual %) |
| `NY.GDP.PCAP.CD` | GDP per capita (current US$) |
| `SP.POP.TOTL` | Population, total |
| `SP.DYN.LE00.IN` | Life expectancy at birth, total (years) |
| `FP.CPI.TOTL.ZG` | Inflation, consumer prices (annual %) |
| `SL.UEM.TOTL.ZS` | Unemployment, total (% of labor force) |
| `EN.ATM.CO2E.PC` | CO2 emissions (metric tons per capita) |
| `SE.ADT.LITR.ZS` | Literacy rate, adult total (% of people ages 15+) |

Browse the full catalog at <https://data.worldbank.org/indicator> or via
`WBMETA("<code>")` once you have a candidate code.

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for the release history.

## License

Released under the [MIT License](LICENSE).
