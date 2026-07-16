"""Render a project Markdown doc to a styled, paginated PDF for offline/print use.

Uses only local tools already on this machine: the `markdown` library to
convert to HTML, `weasyprint` to lay it out and print to PDF. No network
access, no LibreOffice involved.

Usage:
    python3 tools/render_pdf.py docs/TUTORIAL.md docs/TUTORIAL.pdf
"""
import sys
import os
import markdown
from weasyprint import HTML, CSS

CSS_TEXT = """
@page {
    size: Letter;
    margin: 2.2cm 2cm;
    @bottom-center {
        content: counter(page) " / " counter(pages);
        font-family: -apple-system, "Segoe UI", Helvetica, Arial, sans-serif;
        font-size: 9pt;
        color: #888;
    }
}
body {
    font-family: -apple-system, "Segoe UI", Helvetica, Arial, sans-serif;
    font-size: 10.5pt;
    line-height: 1.55;
    color: #1a1a1a;
}
h1 {
    font-size: 22pt;
    color: #0b3d91;
    border-bottom: 2px solid #0b3d91;
    padding-bottom: 6px;
    margin-top: 0;
}
h2 {
    font-size: 15pt;
    color: #0b3d91;
    margin-top: 1.6em;
    border-bottom: 1px solid #cfd8e3;
    padding-bottom: 3px;
    page-break-after: avoid;
}
h3 {
    font-size: 12pt;
    color: #14508c;
    margin-top: 1.3em;
    page-break-after: avoid;
}
p, li { orphans: 3; widows: 3; }
a { color: #0b5fff; text-decoration: none; }
code {
    font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, monospace;
    font-size: 9.3pt;
    background: #f2f4f7;
    border: 1px solid #e1e5ea;
    border-radius: 3px;
    padding: 0.1em 0.35em;
}
pre {
    background: #0b2740;
    color: #e8f0fe;
    padding: 10px 14px;
    border-radius: 5px;
    font-size: 9.3pt;
    line-height: 1.45;
    overflow-x: auto;
    page-break-inside: avoid;
}
pre code {
    background: none;
    border: none;
    color: inherit;
    padding: 0;
}
blockquote {
    margin: 1em 0;
    padding: 0.6em 1em;
    border-left: 4px solid #0b5fff;
    background: #eef4ff;
    color: #1a1a1a;
}
table {
    border-collapse: collapse;
    width: 100%;
    margin: 1em 0;
    font-size: 9.5pt;
    page-break-inside: avoid;
}
th, td {
    border: 1px solid #d3d9e0;
    padding: 5px 9px;
    text-align: left;
    vertical-align: top;
}
th {
    background: #0b3d91;
    color: #ffffff;
}
tr:nth-child(even) td { background: #f7f9fc; }
hr {
    border: none;
    border-top: 1px solid #cfd8e3;
    margin: 1.8em 0;
}
"""


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: render_pdf.py <input.md> <output.pdf>")
    src_path, out_path = sys.argv[1], sys.argv[2]

    with open(src_path, "r", encoding="utf-8") as f:
        text = f.read()

    body_html = markdown.markdown(
        text,
        extensions=["tables", "fenced_code", "toc", "sane_lists"],
    )
    title = os.path.splitext(os.path.basename(src_path))[0].replace("_", " ")
    full_html = "<!doctype html><html><head><meta charset='utf-8'><title>%s</title></head><body>%s</body></html>" % (
        title, body_html)

    HTML(string=full_html, base_url=os.path.dirname(os.path.abspath(src_path))).write_pdf(
        out_path, stylesheets=[CSS(string=CSS_TEXT)])
    print("wrote", out_path)


if __name__ == "__main__":
    main()
