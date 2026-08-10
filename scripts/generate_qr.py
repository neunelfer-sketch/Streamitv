"""Erzeugt aus einer URL eine Android-VectorDrawable-XML und ein PNG."""
import segno, pathlib, sys

BORDER = 4  # Ruhezone: vier Module, wie es die QR-Norm verlangt


def to_vector(content: str) -> tuple[int, str]:
    qr = segno.make(content, error="q")
    matrix = [list(row) for row in qr.matrix]
    n = len(matrix)
    size = n + 2 * BORDER
    parts = []
    for y, row in enumerate(matrix):
        x = 0
        while x < n:
            if row[x]:
                start = x
                while x < n and row[x]:
                    x += 1
                w = x - start
                parts.append(f"M{start + BORDER},{y + BORDER}h{w}v1h-{w}z")
            else:
                x += 1
    return size, "".join(parts)


def write_vector(path: pathlib.Path, content: str, url: str) -> None:
    size, data = to_vector(content)
    path.write_text(
        f'<?xml version="1.0" encoding="utf-8"?>\n'
        f'<!--\n'
        f'  QR-Code auf {url}\n'
        f'  Maschinell erzeugt (scripts/generate_qr.py) - nicht von Hand aendern.\n'
        f'  Weisse Flaeche samt Ruhezone ist eingebaut, damit der Code auch auf\n'
        f'  dunklem Untergrund lesbar bleibt.\n'
        f'-->\n'
        f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="240dp"\n'
        f'    android:height="240dp"\n'
        f'    android:viewportWidth="{size}"\n'
        f'    android:viewportHeight="{size}">\n'
        f'    <path\n'
        f'        android:fillColor="#FFFFFFFF"\n'
        f'        android:pathData="M0,0h{size}v{size}h-{size}z" />\n'
        f'    <path\n'
        f'        android:fillColor="#FF000000"\n'
        f'        android:pathData="{data}" />\n'
        f'</vector>\n',
        encoding="utf-8",
    )
    print(f"  {path.name}: {size}x{size} Module, {len(data)} Zeichen Pfad")


TARGETS = {
    "qr_telegram_neunelfzig": "https://t.me/neunelfzig",
    "qr_telegram_streamingpate": "https://t.me/streamingpate",
}

drawable = pathlib.Path(sys.argv[1])
png_dir = pathlib.Path(sys.argv[2])
drawable.mkdir(parents=True, exist_ok=True)
png_dir.mkdir(parents=True, exist_ok=True)

for name, url in TARGETS.items():
    write_vector(drawable / f"{name}.xml", url, url)
    segno.make(url, error="q").save(
        str(png_dir / f"{name}.png"), scale=12, border=BORDER
    )
    print(f"  {name}.png geschrieben")
