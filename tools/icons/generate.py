"""
The icons of files and tree nodes, drawn from one palette for the light and the dark theme (`name.svg`, `name_dark.svg`): the
style of the new UI of the platform — a 16x16 grid, one-pixel strokes on half-pixel coordinates, a tinted fill, the same six colors
the IDE uses for its own icons. The shapes are the plugin's own; they follow what the node is, as the icons of Rider do (a framed
`C#` for a project, a bare one for a file, a graph for Dependencies), without copying its artwork.

    python tools/icons/generate.py            # writes src/main/resources/icons/*.svg
    python tools/icons/generate.py --preview out.html   # also a page with every icon at 1x, 2x and 4x on both themes

The icons of the tool windows (nugetToolWindow, unitTestsToolWindow, monitorToolWindow and icons/expui) are not made here.
"""
import sys
from pathlib import Path

LIGHT = {
    "grey": "#6C707E", "greyFill": "#EBECF0",
    "green": "#208A3C", "greenFill": "#F2FCF3",
    "blue": "#3574F0", "blueFill": "#EDF3FF",
    "purple": "#834DF0", "purpleFill": "#FAF5FF",
    "orange": "#E66D17", "orangeFill": "#FFF4EB",
}
DARK = {
    "grey": "#CED0D6", "greyFill": "#43454A",
    "green": "#57965C", "greenFill": "#253627",
    "blue": "#548AF7", "blueFill": "#25324D",
    "purple": "#A571E6", "purpleFill": "#2F2936",
    "orange": "#C77D55", "orangeFill": "#45322B",
}

ROUND = 'stroke-linecap="round" stroke-linejoin="round"'
HASH_FILE = "M10.8 4.9 10 11.1M13.2 4.9 12.4 11.1M8.8 6.9h5.7M8.4 9.1h5.7"
HASH_FRAMED = "M10.4 5.9 9.8 10.1M12.4 5.9 11.8 10.1M8.9 7.2h4.3M8.6 8.8h4.3"
FOLDER = "M1.5 4.5a1 1 0 0 1 1-1h3.2a1 1 0 0 1 .7.3l1 1a1 1 0 0 0 .7.3h5.4a1 1 0 0 1 1 1v6.4a1 1 0 0 1-1 1h-11a1 1 0 0 1-1-1z"


def glyph_file(letter, color):
    """A language as a file: the letters alone, as large as the grid lets them be."""
    return (f'<path d="{letter}" fill="none" stroke="{{{color}}}" stroke-width="1.4" {ROUND}/>'
            f'<path d="{HASH_FILE}" fill="none" stroke="{{{color}}}" stroke-width="1.1" stroke-linecap="round"/>')


def glyph_project(letter, color, hash_path: "str | None" = HASH_FRAMED):
    """A language as a project: the same letters in a frame."""
    return (f'<rect x="0.5" y="2.5" width="15" height="11" rx="3" fill="{{{color}Fill}}" stroke="{{{color}}}"/>'
            f'<path d="{letter}" fill="none" stroke="{{{color}}}" stroke-width="1.2" {ROUND}/>'
            + (f'<path d="{hash_path}" fill="none" stroke="{{{color}}}" stroke-width="0.9" stroke-linecap="round"/>' if hash_path else ""))


C_FILE = "M7.1 5.7A3.2 3.2 0 1 0 7.1 10.3"
F_FILE = "M2.7 11.2V4.8h4M2.7 8h3.1"
VB_FILE = "M1.6 4.9l1.9 6.2 1.9-6.2M8.3 11.1V4.9h2.2a1.55 1.55 0 0 1 0 3.1H8.3h2.6a1.55 1.55 0 0 1 0 3.1z"
C_FRAMED = "M7 6.4A2.2 2.2 0 1 0 7 9.6"
F_FRAMED = "M3.6 10.3V5.7h3.1M3.6 8h2.4"
VB_FRAMED = "M3 5.7l1.6 4.6 1.6-4.6M8.6 10.3V5.7h1.9a1.15 1.15 0 0 1 0 2.3H8.6h2.2a1.15 1.15 0 0 1 0 2.3z"

ICONS = {
    # ---- languages
    "csharp": glyph_file(C_FILE, "green"),
    "csharpType": glyph_file(C_FILE, "green"),
    "fsharp": glyph_file(F_FILE, "blue"),
    "vb": f'<path d="{VB_FILE}" fill="none" stroke="{{purple}}" stroke-width="1.3" {ROUND}/>',
    "project": glyph_project(C_FRAMED, "green"),
    "projectFSharp": glyph_project(F_FRAMED, "blue"),
    "projectVb": glyph_project(VB_FRAMED, "purple", hash_path=None),

    # ---- the tree
    # a window and a gem in front of it: what holds the projects together
    "solution": '<defs><clipPath id="behind"><path d="M0 0h16v16H0zM4.5 6.3l5.2 5.2-5.2 5.2-5.2-5.2z" clip-rule="evenodd"/></clipPath></defs>'
                '<g clip-path="url(#behind)"><rect x="3.5" y="1.5" width="11" height="10" rx="2" fill="none" stroke="{grey}"/>'
                '<path d="M3.5 4.5h11" fill="none" stroke="{grey}"/></g>'
                '<path d="M4.5 7.7l3.8 3.8-3.8 3.8-3.8-3.8z" fill="{purpleFill}" stroke="{purple}" stroke-linejoin="round"/>'
                '<path d="M4.5 7.7v7.6" fill="none" stroke="{purple}"/>',
    "dependencies": '<path d="M8 5.8v1.9M8 7.7 4.6 10.6M8 7.7l3.4 2.9" fill="none" stroke="{grey}" stroke-linecap="round"/>'
                    '<circle cx="8" cy="3.9" r="1.9" fill="none" stroke="{grey}"/>'
                    '<circle cx="3.6" cy="12.1" r="1.9" fill="none" stroke="{grey}"/>'
                    '<circle cx="12.4" cy="12.1" r="1.9" fill="none" stroke="{grey}"/>',
    "propertiesFolder": '<defs><clipPath id="badge"><path d="M0 0h16v16H0zM8.5 8.5h8v8h-8z" clip-rule="evenodd"/></clipPath></defs>'
                        f'<path d="{FOLDER}" fill="{{greyFill}}" stroke="{{grey}}" clip-path="url(#badge)"/>'
                        '<path d="M10 10h2v2h-2zM13 10h2v2h-2zM10 13h2v2h-2zM13 13h2v2h-2z" fill="{blue}"/>',

    # ---- files
    "settingsJson": '<path d="M5.9 2.5c-1.5 0-1.7.9-1.7 1.9v1.7c0 1.1-.5 1.9-1.7 1.9 1.2 0 1.7.8 1.7 1.9v1.7c0 1 .2 1.9 1.7 1.9M10.1 2.5c1.5 0 1.7.9 1.7 1.9v1.7c0 '
                    f'1.1.5 1.9 1.7 1.9-1.2 0-1.7.8-1.7 1.9v1.7c0 1-.2 1.9-1.7 1.9" fill="none" stroke="{{purple}}" stroke-width="1.2" {ROUND}/>'
                    '<circle cx="8" cy="8" r="1.3" fill="{purple}"/>',
    "msbuild": '<rect x="1.5" y="2.5" width="13" height="11" rx="2" fill="{orangeFill}" stroke="{orange}"/>'
               f'<path d="M6.3 5.8 4.1 8l2.2 2.2M9.7 5.8 11.9 8l-2.2 2.2" fill="none" stroke="{{orange}}" stroke-width="1.2" {ROUND}/>',
    "nuget": '<circle cx="2.8" cy="2.8" r="1.5" fill="{blue}"/>'
             '<rect x="4.5" y="4.5" width="10" height="10" rx="3" fill="{blueFill}" stroke="{blue}"/>'
             '<circle cx="7.8" cy="7.9" r="1.1" fill="{blue}"/><circle cx="10.8" cy="11" r="1.7" fill="{blue}"/>',
    "assembly": '<path d="M6 1.5v2M10 1.5v2M6 12.5v2M10 12.5v2M1.5 6h2M1.5 10h2M12.5 6h2M12.5 10h2" fill="none" stroke="{grey}" stroke-linecap="round"/>'
                '<rect x="3.5" y="3.5" width="9" height="9" rx="1.5" fill="{greyFill}" stroke="{grey}"/>'
                '<rect x="6" y="6" width="4" height="4" rx="0.5" fill="{blue}"/>',
    # two sliders: a file of settings
    "config": '<path d="M1.5 5.5h5M10.5 5.5h4M1.5 10.5h2M7.5 10.5h7" fill="none" stroke="{grey}" stroke-linecap="round"/>'
              '<circle cx="8.5" cy="5.5" r="1.9" fill="{greyFill}" stroke="{grey}"/><circle cx="5.5" cy="10.5" r="1.9" fill="{greyFill}" stroke="{grey}"/>',
    "razor": f'<circle cx="8" cy="8" r="1.9" fill="none" stroke="{{purple}}" stroke-width="1.2"/>'
             f'<path d="M9.9 6.1v2.7a1.25 1.25 0 0 0 2.5 0V8a4.4 4.4 0 1 0-1.9 3.6" fill="none" stroke="{{purple}}" stroke-width="1.2" stroke-linecap="round"/>',
    "xaml": f'<path d="M5.3 4.7 2 8l3.3 3.3M10.7 4.7 14 8l-3.3 3.3" fill="none" stroke="{{blue}}" stroke-width="1.2" {ROUND}/>'
            '<path d="M9.1 3.7 6.9 12.3" fill="none" stroke="{purple}" stroke-width="1.2" stroke-linecap="round"/>',
    "resx": '<rect x="1.5" y="2.5" width="13" height="11" rx="2" fill="{greenFill}" stroke="{green}"/>'
            '<path d="M1.5 6h13M1.5 9.7h13M6 6v7.5" fill="none" stroke="{green}"/>',
}


def svg(body, palette):
    return '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">' + body.format(**palette) + "</svg>\n"


def main():
    root = Path(__file__).resolve().parents[2]
    target = root / "src" / "main" / "resources" / "icons"
    for name, body in ICONS.items():
        (target / f"{name}.svg").write_text(svg(body, LIGHT), encoding="utf-8")
        (target / f"{name}_dark.svg").write_text(svg(body, DARK), encoding="utf-8")
    print(len(ICONS), "icons ->", target)

    if "--preview" in sys.argv:
        page = Path(sys.argv[sys.argv.index("--preview") + 1])
        rows = []
        for theme, palette, background, text in (("light", LIGHT, "#F7F8FA", "#1E1F22"), ("dark", DARK, "#2B2D30", "#DFE1E5")):
            cells = "".join(
                f'<div class="cell"><div class="sizes">'
                + "".join(f'<span style="width:{16 * k}px;height:{16 * k}px">{svg(body, palette).replace("width=\"16\" height=\"16\"", f"width=\"{16 * k}\" height=\"{16 * k}\"")}</span>' for k in (1, 2, 4))
                + f'</div><div class="row"><span>{svg(body, palette)}</span>{name}</div></div>'
                for name, body in ICONS.items())
            rows.append(f'<section style="background:{background};color:{text}"><h2>{theme}</h2><div class="grid">{cells}</div></section>')
        page.write_text('<!doctype html><meta charset="utf-8"><title>icons</title><style>body{margin:0;font:13px "Segoe UI",sans-serif}section{padding:20px 28px}'
                        'h2{margin:0 0 14px;font-size:14px}.grid{display:grid;grid-template-columns:repeat(6,1fr);gap:18px 22px}.sizes{display:flex;align-items:flex-end;gap:10px;height:66px}'
                        '.sizes span,.row span{display:inline-block;line-height:0}.row{display:flex;align-items:center;gap:6px;margin-top:8px}</style>' + "".join(rows), encoding="utf-8")
        print("preview ->", page)


if __name__ == "__main__":
    main()
