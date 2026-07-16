"""Smoke test for the World Bank Open Data Calc add-in.

Run with LibreOffice's bundled Python (it ships the `uno` module) against a
headless instance listening on a UNO socket:

    soffice --headless --norestore --accept="socket,host=localhost,port=2002;urp;"
    <LO>/program/python tools/test_worldbank.py

No API key is required. This test exercises the real World Bank API, so it
needs network access. Prints RESULT: PASS / FAIL and exits non-zero on
failure.
"""
import sys
import time
import uno


def connect(port=2002, tries=60):
    local = uno.getComponentContext()
    resolver = local.ServiceManager.createInstanceWithContext(
        "com.sun.star.bridge.UnoUrlResolver", local)
    url = "uno:socket,host=localhost,port=%d;urp;StarOffice.ComponentContext" % port
    last = None
    for _ in range(tries):
        try:
            return resolver.resolve(url)
        except Exception as e:  # not yet listening
            last = e
            time.sleep(0.5)
    raise SystemExit("could not connect to LibreOffice: %s" % last)


def settle(doc, cell_or_range, max_wait=30, poll=1.5):
    """Recalc until the top-left value moves off #FETCHING, or timeout."""
    deadline = time.time() + max_wait
    top_left = cell_or_range.getCellByPosition(0, 0) if hasattr(cell_or_range, "getCellByPosition") else cell_or_range
    doc.calculateAll()
    while top_left.getString() == "#FETCHING" and time.time() < deadline:
        time.sleep(poll)
        doc.calculateAll()
    return top_left.getString()


def main():
    ctx = connect()
    smgr = ctx.ServiceManager
    desktop = smgr.createInstanceWithContext("com.sun.star.frame.Desktop", ctx)
    doc = desktop.loadComponentFromURL("private:factory/scalc", "_blank", 0, ())
    checks = {}
    try:
        sheet = doc.Sheets.getByIndex(0)

        def formula(col, row, f):
            c = sheet.getCellByPosition(col, row)
            c.setFormula(f)
            return c

        def array_formula(c0, r0, c1, r1, f):
            rng = sheet.getCellRangeByPosition(c0, r0, c1, r1)
            rng.setArrayFormula(f)
            return rng

        # WBVALUE with an explicit year -> a known historical GDP figure.
        c = formula(0, 0, '=WBVALUE("US";"NY.GDP.MKTP.CD";2020)')
        v = settle(doc, c)
        print("WBVALUE US 2020 -> %r" % v)
        checks["wbvalue_year_registered"] = "#NAME?" not in v
        checks["wbvalue_year_is_number"] = _is_number(v)

        # WBVALUE with no year -> most recent value, should differ from 2020's.
        c = formula(0, 1, '=WBVALUE("US";"NY.GDP.MKTP.CD")')
        v2 = settle(doc, c, max_wait=15)
        print("WBVALUE US latest -> %r" % v2)
        checks["wbvalue_latest_is_number"] = _is_number(v2)

        # WBLATEST -> two-cell row, reuses the cache from above (fast).
        rng = array_formula(0, 2, 1, 2, '=WBLATEST("US";"NY.GDP.MKTP.CD")')
        settle(doc, rng, max_wait=10)
        year_cell = rng.getCellByPosition(1, 0)
        print("WBLATEST US -> value=%r year=%r" % (rng.getCellByPosition(0, 0).getString(), year_cell.getValue()))
        checks["wblatest_year_is_recent"] = year_cell.getValue() >= 2020

        # WBMETA -> indicator name.
        c = formula(0, 3, '=WBMETA("NY.GDP.MKTP.CD")')
        meta = settle(doc, c, max_wait=15)
        print("WBMETA -> %r" % meta)
        checks["wbmeta_has_gdp"] = "GDP" in meta

        # WBSERIES with a bounded range -> 6 rows, ascending by year.
        rng = array_formula(0, 4, 1, 9, '=WBSERIES("US";"NY.GDP.MKTP.CD";2015;2020)')
        settle(doc, rng, max_wait=15)
        years = [rng.getCellByPosition(0, r).getValue() for r in range(6)]
        print("WBSERIES 2015-2020 years -> %r" % years)
        checks["wbseries_range_ascending"] = years == sorted(years)
        checks["wbseries_range_bounds"] = years[0] == 2015 and years[-1] == 2020

        # Invalid indicator -> #ERR, with a detail message via WBLASTERROR.
        c = formula(0, 10, '=WBVALUE("US";"BOGUS.INDICATOR.XYZ")')
        err_val = settle(doc, c, max_wait=15)
        print("WBVALUE bogus indicator -> %r" % err_val)
        checks["wbvalue_bad_indicator_is_err"] = err_val == "#ERR"

        c = formula(0, 11, '=WBLASTERROR()')
        doc.calculateAll()
        last_err = c.getString()
        print("WBLASTERROR -> %r" % last_err)
        checks["wblasterror_has_detail"] = len(last_err) > 0

    finally:
        doc.close(False)
        desktop.terminate()

    print("---")
    for name, ok in checks.items():
        print("CHECK %-32s %s" % (name, "PASS" if ok else "FAIL"))

    ok = all(checks.values())
    print("RESULT:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


def _is_number(s):
    try:
        float(s.replace(",", ""))
        return True
    except (ValueError, AttributeError):
        return False


if __name__ == "__main__":
    main()
