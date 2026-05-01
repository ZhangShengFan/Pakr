import urllib.request, os, io
from PIL import Image, ImageDraw

url = os.environ.get('ICON_URL', '').strip()

if not url:
    print('No icon URL provided, keeping default Android launcher icon.')
else:
    img = None
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=15) as r:
            raw = r.read()
        img = Image.open(io.BytesIO(raw)).convert('RGBA')
        print(f'Image OK: {img.format} {img.size}')
    except Exception as e:
        print(f'Download/open failed: {e}, keeping default Android launcher icon.')
        img = None

    if img is not None:
        for density, size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
            out = img.resize((size,size), Image.LANCZOS)
            base = f'app/src/main/res/mipmap-{density}'
            os.makedirs(base, exist_ok=True)
            out.save(f'{base}/ic_launcher.png')
            mask = Image.new('RGBA',(size,size),(0,0,0,0))
            ImageDraw.Draw(mask).ellipse((0,0,size,size),fill=(255,255,255,255))
            result = Image.new('RGBA',(size,size),(0,0,0,0))
            result.paste(out, mask=mask)
            result.save(f'{base}/ic_launcher_round.png')
        print('Icon ALL OK')
