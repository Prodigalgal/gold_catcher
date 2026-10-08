"""Render the compiled React prototype as an offline inline HTML fragment."""
import argparse
from pathlib import Path
import re

parser = argparse.ArgumentParser()
parser.add_argument("output", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
assets = root / "web/dist/assets"
scripts = list(assets.glob("*.js"))
styles = list(assets.glob("*.css"))
if len(scripts) != 1 or len(styles) != 1:
    raise SystemExit("expected one self-contained JS bundle and stylesheet")
code = scripts[0].read_text(encoding="utf-8")
if re.search(r"\b(?:fetch\s*\(|XMLHttpRequest\b|WebSocket\b)", code):
    raise SystemExit("inline prototype must not perform external requests")
fragment = (root / "scripts/preview-template.html").read_text(encoding="utf-8")
fragment = fragment.replace("<!-- GOLD_CATCHER_STYLES -->", styles[0].read_text(encoding="utf-8"))
fragment = fragment.replace("// GOLD_CATCHER_APP", code.replace("</script", "<\\/script"))
if len(fragment.encode("utf-8")) >= 1_000_000:
    raise SystemExit("preview exceeds 1 MB")
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(fragment, encoding="utf-8")
print(f"Inline preview: {len(fragment.encode('utf-8'))} bytes")
