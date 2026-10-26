"""将真实Playwright录像和已有截图汇编为LOCAL_MOCK演示摘录；不伪造沙箱交易。"""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / '.artifacts/video-build'
FFMPEG = Path('D:/David/software/ffmpeg/bin/ffmpeg.exe')
FFPROBE = FFMPEG.with_name('ffprobe.exe')


def run(command):
    subprocess.run([str(x) for x in command], cwd=ROOT, check=True, stdout=subprocess.DEVNULL,
                   stderr=subprocess.PIPE)


def metadata(source):
    result = subprocess.run([str(FFPROBE), '-v', 'error', '-show_format', '-show_streams', '-of', 'json', str(source)],
                            check=True, capture_output=True, text=True, encoding='utf-8')
    return json.loads(result.stdout)


def clip(index, source, title, still_seconds=None):
    text = BUILD / f'title-{index}.txt'; text.write_text(title, encoding='utf-8')
    target = BUILD / f'clip-{index}.mp4'
    filters = ('setpts=PTS-STARTPTS,' if still_seconds else 'setpts=3*(PTS-STARTPTS),')
    filters += 'scale=1280:610:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:80:black,setsar=1,fps=30,'
    filters += f'drawtext=fontfile=.artifacts/video-build/font.ttc:textfile=.artifacts/video-build/title-{index}.txt:fontcolor=white:fontsize=27:x=30:y=22,'
    filters += 'drawtext=fontfile=.artifacts/video-build/font.ttc:textfile=.artifacts/video-build/disclaimer.txt:fontcolor=white:fontsize=20:x=30:y=690'
    command = [FFMPEG, '-y', '-loglevel', 'error']
    if still_seconds: command += ['-loop', '1', '-t', str(still_seconds)]
    command += ['-i', source, '-vf', filters, '-an', '-c:v', 'libx264', '-preset', 'fast', '-threads', '2', '-crf', '22', '-pix_fmt', 'yuv420p', target]
    run(command)
    return target


def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    shutil.copyfile('C:/Windows/Fonts/msyh.ttc', BUILD / 'font.ttc')
    (BUILD / 'disclaimer.txt').write_text('LOCAL_MOCK 自动化联调摘录  虚构资料  沙箱付款待验  无真人讲解', encoding='utf-8')
    recordings = ROOT / '.artifacts/R-playwright-results'
    desktop = next(recordings.glob('commerce-真实页面*-desktop/video.webm'))
    mobile = next(recordings.glob('commerce-真实页面*-mobile/video.webm'))
    sources = [
        (ROOT / '.artifacts/ppt-build/slide-1.png', '项目概览  课程提交预览', 8),
        (desktop, '桌面跨店下单 独立模拟支付 履约与退款  录像放慢3倍', None),
        (mobile, '移动端交易与售后  录像放慢3倍', None),
        (ROOT / '.artifacts/R-settlement-mock-paid.png', '真实结算页面截图  模拟出款  已有浏览器职责分离验收', 12),
        (ROOT / '.artifacts/ppt-build/slide-10.png', '剩余验收与待补资料', 8),
    ]
    clips = [clip(i, *source) for i, source in enumerate(sources)]
    listing = BUILD / 'concat.txt'
    listing.write_text('\n'.join("file '" + p.as_posix() + "'" for p in clips) + '\n', encoding='utf-8')
    output = ROOT / 'deliverables/05-阅集商城本地联调演示.mp4'
    run([FFMPEG, '-y', '-loglevel', 'error', '-f', 'concat', '-safe', '0', '-i', listing, '-c', 'copy', '-movflags', '+faststart', output])
    result = metadata(output); duration = float(result['format']['duration'])
    assert duration <= 900 and any(s['codec_type'] == 'video' for s in result['streams'])
    manifest = dict(output=output.name, durationSeconds=duration, sha256=hashlib.sha256(output.read_bytes()).hexdigest(),
                    scope='LOCAL_MOCK recorded automated browser excerpts; sandbox transaction and live narration absent',
                    sourceRecordings=[str(desktop.relative_to(ROOT)), str(mobile.relative_to(ROOT))], slowdown=3,
                    sourceDurationsSeconds=[float(metadata(p)['format']['duration']) for p in (desktop, mobile)])
    (BUILD / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(manifest, ensure_ascii=False))


if __name__ == '__main__': main()
