"""复用文档技能渲染器，将容器LibreOffice生成的PDF栅格化以逐页检查。"""
from pathlib import Path
import importlib.util
import os

ROOT = Path(__file__).resolve().parents[1]
SKILL = Path('C:/Users/123/.codex/plugins/cache/openai-primary-runtime/documents/26.1025.11240/skills/documents')
POPPLER = Path('C:/Users/123/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/poppler/Library/bin')
os.environ['PATH'] = str(POPPLER) + os.pathsep + os.environ['PATH']
spec = importlib.util.spec_from_file_location('skill_docx_render', SKILL / 'render_docx.py')
renderer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(renderer)
for document in sorted((ROOT / 'deliverables').glob('*.docx')):
    pdf = ROOT / '.artifacts/doc-qa' / (document.stem + '.pdf')
    assert pdf.exists(), 'Run the LibreOffice container conversion first'
    # 仅替换本次模块实例的转换入口；不修改受管理的技能源码。
    renderer.convert_to_pdf = lambda *args, source=pdf, **kwargs: (str(source), 'Already converted with the recorded Docker LibreOffice image')
    pages = renderer.rasterize(str(document), str(pdf.with_suffix('')), 140, False, False)
    print(document.name, len(pages), 'pages')
