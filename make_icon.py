from PIL import Image
import os

img = Image.open(r'C:/Users/toshiro/Downloads/k6.png').convert('RGBA')
w, h = img.size
print(f'Size: {w}x{h}')

# 右半分の中央ピクセルからバックグラウンドカラーを取得
bg_px = img.getpixel((int(w * 0.75), int(h * 0.5)))
print(f'BG color (RGBA): {bg_px}')
bg_hex = '#{:02X}{:02X}{:02X}'.format(bg_px[0], bg_px[1], bg_px[2])
print(f'BG color (HEX): {bg_hex}')

# 左半分を切り出し（アイコン部分）
left = img.crop((0, 0, w // 2, h))

# 白〜薄い背景色を透明化
left_rgba = left.copy()
pixels = left_rgba.load()
lw, lh = left_rgba.size
for y in range(lh):
    for x in range(lw):
        r, g, b, a = pixels[x, y]
        # 白や薄い青白色（背景）を透明に
        if r > 220 and g > 220 and b > 220:
            pixels[x, y] = (r, g, b, 0)

# アダプティブアイコン フォアグラウンド用サイズ（各密度）
# フォアグラウンド: 108dp × 各倍率
sizes = {
    'mipmap-mdpi':    108,
    'mipmap-hdpi':    162,
    'mipmap-xhdpi':   216,
    'mipmap-xxhdpi':  324,
    'mipmap-xxxhdpi': 432,
}

base_dir = r'app/src/main/res'

for folder, size in sizes.items():
    out_dir = os.path.join(base_dir, folder)
    os.makedirs(out_dir, exist_ok=True)
    resized = left_rgba.resize((size, size), Image.LANCZOS)
    out_path = os.path.join(out_dir, 'ic_launcher_foreground.png')
    resized.save(out_path, 'PNG')
    print(f'Saved: {out_path} ({size}x{size})')

print(f'\nBackground color to use: {bg_hex}')
print('Done!')
