"""将本机Word实际导出的PDF交给文档技能渲染器，生成可逐页检查的PNG。"""
from pathlib import Path
import importlib.util
import os
import json
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]
SKILL = Path('C:/Users/123/.codex/plugins/cache/openai-primary-runtime/documents/26.1025.11240/skills/documents')
POPPLER = Path('C:/Users/123/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/poppler/Library/bin')
os.environ['PATH'] = str(POPPLER) + os.pathsep + os.environ['PATH']
spec = importlib.util.spec_from_file_location('skill_docx_render', SKILL / 'render_docx.py')
renderer = importlib.util.module_from_spec(spec); spec.loader.exec_module(renderer)
results = []
for document in sorted((ROOT / 'deliverables/最终提交').glob('*.docx')):
    pdf = ROOT / '.artifacts/final-doc-qa' / (document.stem + '.pdf')
    assert pdf.is_file() and pdf.stat().st_mtime >= document.stat().st_mtime
    renderer.convert_to_pdf = lambda *args, source=pdf, **kwargs: (str(source), 'Actual Microsoft Word PDF export')
    pages = renderer.rasterize(str(document), str(pdf.with_suffix('')), 120, False, False)
    reader = PdfReader(pdf)
    assert len(pages) == len(reader.pages)
    results.append(dict(name=document.name, pages=len(pages), renderer='Microsoft Word + skill render_docx.py',
                        pageTextCharacters=[len(p.extract_text()) for p in reader.pages]))
(ROOT / '.artifacts/final-doc-qa/render-receipt.json').write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(results,ensure_ascii=False))
