# Tutorial: World Bank Open Data in LibreOffice Calc

A hands-on walkthrough of every function and feature in the World Bank Open
Data Calc add-in. No API key, no signup — just install and start typing
formulas. Screenshots aren't included here, but every formula below is
copy-pasteable into a real sheet; the sample outputs were captured from a
live run against the real API on 2026-07-16; the World Bank periodically
revises GDP and similar figures, so your numbers may differ slightly, and
"most recent year" will keep advancing as new years are published.

## What you'll build

By the end of this tutorial you'll have a working sheet that:

- looks up a single indicator value for a specific year,
- pulls a full time series and charts it,
- compares two countries side by side,
- labels indicator codes automatically,
- and gracefully survives typos and network hiccups.

---

## 0. Install it

If you haven't already:

```bash
"$LO_HOME/program/unopkg" add --force build/WorldBank.oxt
```

(No build tools? Download the prebuilt `WorldBank.oxt` from the releases
page instead — see the [README](../README.md#install-prebuilt).) Restart
LibreOffice, then open a new Calc document.

Confirm it's registered before going further — type this in any cell:

```
=WBMETA("NY.GDP.MKTP.CD")
```

If you see `#NAME?`, the extension didn't register; check
`unopkg list` and see [INSTALL.md](INSTALL.md#troubleshooting). Anything
else (including `#FETCHING`) means you're good to continue.

---

## 1. Your first lookup: `WBVALUE`

Every function needs two things at minimum: a **country code** and an
**indicator code**. Countries are ISO2 (`"US"`), ISO3 (`"USA"`), or a World
Bank aggregate (`"WLD"` for World). Indicators are World Bank's dotted codes
— `NY.GDP.MKTP.CD` is GDP in current US dollars. (More codes in the
[README's table](../README.md#common-indicator-codes), or look one up live
with `WBMETA` — see Part 5.)

In cell `A1`, type:

```
=WBVALUE("US"; "NY.GDP.MKTP.CD"; 2020)
```

Note the **semicolons** — that's Calc's argument separator, not commas.

The first time you enter this formula, don't be surprised if you see:

```
#FETCHING
```

That's not an error — it means the add-in just kicked off a background
request and handed the cell a placeholder immediately, rather than freezing
your spreadsheet while it waits on the network. Press **Ctrl+Shift+F9**
(recalculate hard) a second or two later, and it resolves:

```
21375281000000
```

That's the United States' 2020 GDP in current dollars.

### Omit the year for "most recent"

Drop the third argument entirely and you get whatever year the World Bank
has most recently published:

```
=WBVALUE("US"; "NY.GDP.MKTP.CD")
```

→ resolves near-instantly (see "One fetch, three functions" below) to the
latest available figure — e.g. `30769700000000` for 2025, as of this
writing.

---

## 2. Understanding the three sentinels

Besides real numbers, any `WB*` function can show one of three short-lived
placeholder strings instead of throwing an error or freezing the UI:

| You see | What it means | What to do |
|---|---|---|
| `#FETCHING` | First request for this data; a background fetch just started. | Wait a second or two, recalculate (F9 or Ctrl+Shift+F9). |
| `#NOT_FOUND` | The request reached the API, but nothing matched — e.g. every value in range is null. | Try a different year/range, or double-check the country/indicator pairing. |
| `#ERR` | The fetch failed persistently — usually an invalid indicator or country code. | Call `WBLASTERROR()` (Part 6) for the detail message. |

Try triggering `#ERR` on purpose — type a made-up indicator code:

```
=WBVALUE("US"; "BOGUS.INDICATOR.XYZ")
```

You'll get `#ERR`. That's the add-in correctly detecting that the World
Bank API rejected the code, rather than silently returning a wrong number.
Keep this cell around — you'll use it in Part 6.

---

## 3. A time series: `WBSERIES`

`WBSERIES` returns a whole table, so it needs an **array formula**: select
a *range* of cells (not just one), type the formula, then press
**Ctrl+Shift+Enter** instead of plain Enter (LibreOffice has no automatic
spill, so this step is required every time). If you forget, you'll only see
the top-left value.

Select `A3:B12` (10 rows, 2 columns), type:

```
=WBSERIES("US"; "NY.GDP.MKTP.CD"; 2015; 2020)
```

and confirm with **Ctrl+Shift+Enter**. You'll get 6 rows (2015 through
2020 inclusive), sorted ascending by year:

| year | value |
|---|---|
| 2015 | 18295019000000 |
| 2016 | 18804913000000 |
| 2017 | 19612102000000 |
| 2018 | 20656516000000 |
| 2019 | 21539982000000 |
| 2020 | 21375281000000 |

Notice row 2020's value matches the `WBVALUE` lookup from Part 1 — same
underlying data, two different views.

### The full history, for free

Omit both year bounds and you get *every* year the World Bank has for that
country/indicator — currently back to 1960 for GDP:

```
=WBSERIES("US"; "NY.GDP.MKTP.CD")
```

Select a generous range (at least ~70 rows for a long-running annual
indicator) before entering it. This is a good formula to pair with
**Insert → Chart** for an instant line chart of six decades of GDP.

### Null years show up blank, never as zero

If a country/indicator combination has gaps (a young country reporting a
long-running indicator, for instance), those years appear in the output
with an **empty** value cell — never `0`. A `0` in a WBSERIES row always
means the World Bank actually reported zero, not "no data."

### Multiple countries at once

Join country codes with a semicolon *inside the string* (this is separate
from Calc's own argument-separating semicolons):

```
=WBSERIES("US;GB"; "NY.GDP.MKTP.CD"; 2022; 2023)
```

Select a **3-column** range this time (`A;B;C`) — WBSERIES automatically
adds a leading `country` column whenever you name more than one country:

| country | year | value |
|---|---|---|
| GB | 2022 | 3181244350465.41 |
| US | 2022 | 26054614000000 |
| GB | 2023 | 3420796653789.08 |
| US | 2023 | *(next row)* |

This is the fastest way to build a side-by-side country comparison without
four separate formulas.

---

## 4. Dashboard tiles: `WBLATEST`

`WBSERIES` and repeated `WBVALUE` calls are great for tables, but a
dashboard often wants just "the current number and what year it's from" as
a compact tile. That's `WBLATEST` — also an array formula, but always
exactly one row, two columns:

Select `A20:B20`, type:

```
=WBLATEST("US"; "NY.GDP.MKTP.CD")
```

confirm with Ctrl+Shift+Enter, and you get:

| value | year |
|---|---|
| 30769700000000 | 2025 |

### One fetch, three functions

Here's a detail worth knowing: `WBVALUE`, `WBSERIES`, and `WBLATEST` for
the **same country + indicator** all share one cached fetch. The World Bank
API hands back a country's entire history in a single request, so the
add-in pulls it once and answers "value in year Y," "most recent value,"
and "the full series" all from that same cached data. Try it: after any of
the formulas above have resolved for `("US", "NY.GDP.MKTP.CD")`, enter
another one for the same pair — it resolves instantly, no `#FETCHING` at
all, because there's nothing left to fetch.

---

## 5. Labeling: `WBMETA`

Indicator codes like `NY.GDP.MKTP.CD` aren't self-explanatory in a shared
sheet. `WBMETA` turns a code into its human-readable name:

```
=WBMETA("NY.GDP.MKTP.CD")
```

→ `GDP (current US$)`

This shines when you're building a small reference table. Put codes down a
column and labels next to them:

| A | B |
|---|---|
| `NY.GDP.MKTP.CD` | `=WBMETA(A1)` → GDP (current US$) |
| `SP.POP.TOTL` | `=WBMETA(A2)` → Population, total |
| `FP.CPI.TOTL.ZG` | `=WBMETA(A3)` → Inflation, consumer prices (annual %) |

Then reference column A (the code) in your `WBVALUE`/`WBSERIES` formulas
and column B (the label) as your chart/table headers — change the code in
one place, and both the data and its label update together.

---

## 6. Diagnostics: `WBLASTERROR` and `WBCACHECLEAR`

Remember the `#ERR` cell from Part 2? Now find out *why* it failed:

```
=WBLASTERROR()
```

→ `World Bank API error: The provided parameter value is not valid`

That's the World Bank API's own rejection message for the bogus indicator
code, surfaced verbatim rather than a generic "something went wrong."
`WBLASTERROR()` always reflects the *most recent* failure across every
`WB*` function in the session — handy as a single diagnostic cell near the
top of a sheet full of formulas.

If you want to force a completely fresh pull of everything (e.g. you
suspect World Bank has revised a figure since your session started, and
don't want to wait out the 24-hour cache TTL):

```
=WBCACHECLEAR()
```

→ returns the number of cache entries cleared. One quirk worth knowing:
LibreOffice's formula engine doesn't guarantee a cell's function runs
*exactly* once per recalculation, so this count is best-effort — treat it
as "yes, something got cleared," not an exact audit number. Every formula
you recalculate afterward will show `#FETCHING` again as it refetches.

---

## 7. Put it together: a two-country GDP comparison

As a capstone, build a small comparison sheet from scratch:

1. **A1**: `Country`  **B1**: `GDP label`  **C1**: `2023 GDP`  **D1**: `Latest GDP`  **E1**: `Latest year`
2. **A2**: `US`  **A3**: `GB`  **A4**: `DE`
3. **B2**: `=WBMETA("NY.GDP.MKTP.CD")` (only need this once — copy down if you like, it's the same for every row)
4. **C2**: `=WBVALUE(A2; "NY.GDP.MKTP.CD"; 2023)` — fill down to C3, C4
5. Select **D2:E2**, enter `=WBLATEST(A2; "NY.GDP.MKTP.CD")` as an array formula (Ctrl+Shift+Enter), then repeat for rows 3 and 4

Recalculate (Ctrl+Shift+F9) once or twice to clear any `#FETCHING`
placeholders, and you have a live, three-country GDP comparison built from
five formulas — each country's three columns (2023 value, latest value,
latest year) sharing one cached fetch per country, so recalculating this
sheet repeatedly costs nothing extra.

---

## Quick reference

| Function | Entry mode | Purpose | Columns |
|---|---|---|---|
| `WBVALUE(country; indicator; [year])` | normal cell | one number | *(scalar, no columns)* |
| `WBSERIES(country; indicator; [start]; [end])` | **array formula** | a table of years | `year, value` — or `country, year, value` for more than one country |
| `WBLATEST(country; indicator)` | **array formula** | value + year, one row | `value, year` |
| `WBMETA(indicator)` | normal cell | indicator's name | *(scalar, no columns)* |
| `WBLASTERROR()` | normal cell | last failure's detail | *(scalar, no columns)* |
| `WBCACHECLEAR()` | normal cell | force a fresh refetch | *(scalar, no columns)* |

**Array formula reminder**: select the *whole* output range first, type the
formula, then **Ctrl+Shift+Enter** (not plain Enter).

**No header row**: `WBSERIES` and `WBLATEST` spill data only — the
`year, value` / `value, year` column order above is *not* written to the
sheet as text. Type your own header row in the cells above the array
formula's output range if you want labels.

**Sentinel reminder**: `#FETCHING` → wait and recalculate. `#NOT_FOUND` →
no data for that combination. `#ERR` → check `WBLASTERROR()`.

---

## Where to go next

- Full function signatures and argument tables: [README.md](../README.md)
- Build-from-source and platform-specific install steps: [INSTALL.md](INSTALL.md)
- Browse thousands more indicator codes: <https://data.worldbank.org/indicator>
- A pre-built example workbook with every function already resolved:
  [`test/worldbank_demo.ods`](../test/worldbank_demo.ods)
