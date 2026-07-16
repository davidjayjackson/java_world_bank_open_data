"""Generate test/worldbank_demo.ods using the installed World Bank Calc
add-in, built around US and UK GDP.

Run against a headless LibreOffice listening on a UNO socket (no API key
needed - the World Bank API is open); see docs/INSTALL.md. Produces a
spreadsheet whose cells contain live WB* formulas, with values already
computed.

Formulas are entered and settled one at a time (waiting out #FETCHING)
rather than all at once, since a fresh formula's first evaluation only
kicks off the background fetch - the demo wants baked-in, resolved values,
not a sheet full of #FETCHING.
"""
import os
import time
import uno
from com.sun.star.beans import PropertyValue


def connect(port=2002, tries=60):
    local = uno.getComponentContext()
    resolver = local.ServiceManager.createInstanceWithContext(
        "com.sun.star.bridge.UnoUrlResolver", local)
    url = "uno:socket,host=localhost,port=%d;urp;StarOffice.ComponentContext" % port
    last = None
    for _ in range(tries):
        try:
            return resolver.resolve(url)
        except Exception as e:
            last = e
            time.sleep(0.5)
    raise SystemExit("could not connect to LibreOffice: %s" % last)


def main():
    out_path = os.path.abspath(
        os.path.join(os.path.dirname(__file__), "..", "test", "worldbank_demo.ods"))
    out_url = uno.systemPathToFileUrl(out_path)

    ctx = connect()
    smgr = ctx.ServiceManager
    desktop = smgr.createInstanceWithContext("com.sun.star.frame.Desktop", ctx)
    doc = desktop.loadComponentFromURL("private:factory/scalc", "_blank", 0, ())
    try:
        sh = doc.Sheets.getByIndex(0)
        sh.Name = "World Bank Demo"

        def put(col, row, value):
            sh.getCellByPosition(col, row).setString(value)

        def formula(col, row, f):
            c = sh.getCellByPosition(col, row)
            c.setFormula(f)
            return c

        def array_formula(c0, r0, c1, r1, f):
            rng = sh.getCellRangeByPosition(c0, r0, c1, r1)
            rng.setArrayFormula(f)
            return rng

        def settle(cell_or_range, max_wait=30, poll=1.5):
            deadline = time.time() + max_wait
            top_left = cell_or_range.getCellByPosition(0, 0) if hasattr(cell_or_range, "getCellByPosition") else cell_or_range
            doc.calculateAll()
            while top_left.getString() == "#FETCHING" and time.time() < deadline:
                time.sleep(poll)
                doc.calculateAll()
            return top_left.getString()

        put(0, 0, "World Bank Open Data Calc Add-In - demo (US / UK GDP)")
        put(0, 1, "No API key needed. Recalc with Ctrl+Shift+F9 if a cell shows #FETCHING.")
        put(0, 2, "#FETCHING means a background fetch just started - recalc again once it settles.")

        put(0, 4, "Function")
        put(1, 4, "Live result")
        put(2, 4, "Formula")
        row = {}
        r = 5
        for key in ("value_year", "value_latest", "meta", "lasterror"):
            row[key] = r
            r += 1

        labels = {
            "value_year": "WBVALUE (US GDP, 2020)",
            "value_latest": "WBVALUE (US GDP, most recent)",
            "meta": "WBMETA (indicator name)",
            "lasterror": "WBLASTERROR",
        }
        for key, label in labels.items():
            put(0, row[key], label)

        # --- Phase 1: WBVALUE with a year -> triggers the full-series fetch for US GDP. ---
        f = '=WBVALUE("US";"NY.GDP.MKTP.CD";2020)'
        put(2, row["value_year"], f)
        c = formula(1, row["value_year"], f)
        print("value_year ->", settle(c))

        # --- Phase 2: WBVALUE with no year -> reuses the same cached series. ---
        f = '=WBVALUE("US";"NY.GDP.MKTP.CD")'
        put(2, row["value_latest"], f)
        c = formula(1, row["value_latest"], f)
        print("value_latest ->", settle(c, max_wait=10))

        # --- Phase 3: WBLATEST -> also reuses the cached series. ---
        put(0, 13, "WBLATEST (US GDP: value, year)")
        lat_f = '=WBLATEST("US";"NY.GDP.MKTP.CD")'
        lat_rng = array_formula(1, 13, 2, 13, lat_f)
        print("latest ->", settle(lat_rng, max_wait=10))
        put(0, 14, "{" + lat_f + "}  (select range, Ctrl+Shift+Enter)")

        # --- Phase 4: WBMETA -> separate metadata fetch. ---
        f = '=WBMETA("NY.GDP.MKTP.CD")'
        put(2, row["meta"], f)
        c = formula(1, row["meta"], f)
        print("meta ->", settle(c, max_wait=15))

        # --- Phase 5: WBSERIES bounded range, US -> reuses the cached series. ---
        put(0, 16, "WBSERIES (US GDP, 2015-2024)")
        series_header = 17
        for i, h in enumerate(["year", "value"]):
            put(i, series_header, h)
        series_first = series_header + 1
        series_f = '=WBSERIES("US";"NY.GDP.MKTP.CD";2015;2024)'
        series_rng = array_formula(0, series_first, 1, series_first + 9, series_f)  # 10 rows
        print("series ->", settle(series_rng, max_wait=15))
        put(0, series_first + 11, "{" + series_f + "}  (select range, Ctrl+Shift+Enter)")

        # --- Phase 6: WBSERIES multi-country -> a fresh fetch (US;GB combined key). ---
        r = series_first + 13
        put(0, r, "WBSERIES multi-country (US;GB GDP, 2022-2023) - adds a country column")
        multi_header = r + 1
        for i, h in enumerate(["country", "year", "value"]):
            put(i, multi_header, h)
        multi_first = multi_header + 1
        multi_f = '=WBSERIES("US;GB";"NY.GDP.MKTP.CD";2022;2023)'
        multi_rng = array_formula(0, multi_first, 2, multi_first + 3, multi_f)  # 4 rows
        print("multi ->", settle(multi_rng, max_wait=15))
        put(0, multi_first + 5, "{" + multi_f + "}  (select range, Ctrl+Shift+Enter)")

        # --- Phase 7: an invalid indicator -> #ERR, then WBLASTERROR shows the detail. ---
        r = multi_first + 7
        put(0, r, "WBVALUE with an invalid indicator code (expect #ERR)")
        bad_f = '=WBVALUE("US";"BOGUS.INDICATOR.XYZ")'
        put(2, r, bad_f)
        c = formula(1, r, bad_f)
        print("bad_indicator ->", settle(c, max_wait=15))

        f = '=WBLASTERROR()'
        put(2, row["lasterror"], f)
        c = formula(1, row["lasterror"], f)
        doc.calculateAll()
        print("lasterror ->", c.getString())

        # Widen columns a little for readability.
        cols = sh.Columns
        for i in range(6):
            cols.getByIndex(i).Width = 4500
        cols.getByIndex(0).Width = 9500

        doc.calculateAll()

        # Save as ODF spreadsheet (calc8 = .ods).
        fn = PropertyValue()
        fn.Name = "FilterName"
        fn.Value = "calc8"
        doc.storeToURL(out_url, (fn,))
        print("wrote", out_path)
    finally:
        doc.close(False)
        desktop.terminate()


if __name__ == "__main__":
    main()
