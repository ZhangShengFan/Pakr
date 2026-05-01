import urllib.request, os, io, sys, shutil
from PIL import Image, ImageDraw

url = os.environ.get('ICON_URL', '').strip()
DEFAULT_LOGO = 'logo.jpg'

if not url:
    print('No icon URL provided, fallback to root logo.jpg')
    write_default_logo()
    print('FALLBACK_ICON_USED: /logo.jpg')
else:
    img = None
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=15) as r:
            raw = r.read()
        img = Image.open(io.BytesIO(raw)).convert('RGBA')
        print(f'Image OK: {img.format} {img.size}')
    except Exception as e:
        # fix(bug#4): 下载失败时 exit 1，让 CI 明确报错而不是静默跳过
        print(f'Download/open failed: {e}')
        sys.exit(1)

    if img is None:
        print('Image unavailable, fallback to root logo.jpg')
        write_default_logo()
        print('FALLBACK_ICON_USED: /logo.jpg')
        sys.exit(0)

    if img is not None:
        for density, size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
            out = img.resize((size,size), Image.LANCZOS)
            base = f'app/src/main/res/mipmap-{density}'
            os.makedirs(base, exist_ok=True)
            out.save(f'{base}/ic_launcher.png')
            # fix(bug#9): 椭圆终点用 size-1，避免 Pillow exclusive 端点导致 1px 锯齿
            mask = Image.new('RGBA',(size,size),(0,0,0,0))
            ImageDraw.Draw(mask).ellipse((0,0,size-1,size-1),fill=(255,255,255,255))
            result = Image.new('RGBA',(size,size),(0,0,0,0))
            result.paste(out, mask=mask)
            result.save(f'{base}/ic_launcher_round.png')
        print('Icon ALL OK')

def write_default_logo():
    img = Image.open(DEFAULT_LOGO).convert('RGBA')
    for density, size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
        out = img.resize((size,size), Image.LANCZOS)
        base = f'app/src/main/res/mipmap-{density}'
        os.makedirs(base, exist_ok=True)
        out.save(f'{base}/ic_launcher.png')
        mask = Image.new('RGBA',(size,size),(0,0,0,0))
        ImageDraw.Draw(mask).ellipse((0,0,size-1,size-1),fill=(255,255,255,255))
        result = Image.new('RGBA',(size,size),(0,0,0,0))
        result.paste(out, mask=mask)
        result.save(f'{base}/ic_launcher_round.png')
