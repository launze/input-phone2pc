from PIL import Image
import os

icons_dir = os.path.dirname(os.path.abspath(__file__))

sizes = [(32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
images = []

for size in sizes:
    if size == (32, 32):
        img = Image.open(os.path.join(icons_dir, '32x32.png'))
    elif size == (128, 128):
        img = Image.open(os.path.join(icons_dir, '128x128.png'))
    elif size == (256, 256):
        img = Image.open(os.path.join(icons_dir, '256x256.png'))
    else:
        img = Image.open(os.path.join(icons_dir, '512x512.png'))
        img = img.resize(size, Image.Resampling.LANCZOS)
    
    img = img.convert('RGBA')
    images.append(img)

ico_path = os.path.join(icons_dir, 'icon.ico')
images[0].save(ico_path, format='ICO', sizes=[(32, 32), (48, 48), (64, 64), (128, 128), (256, 256)], 
               append_images=images[1:])

print(f"Windows ICO icon created at: {ico_path}")