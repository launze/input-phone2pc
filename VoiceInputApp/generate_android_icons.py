import os
import shutil
import subprocess
import tempfile


ROOT_DIR = os.path.dirname(__file__)
SVG_PATH = os.path.join(ROOT_DIR, 'art', 'app_icon.svg')


def create_android_icon(size, output_path):
    """Render the SVG launcher icon at the requested Android density."""
    magick = shutil.which('magick')
    if not magick:
        raise RuntimeError('ImageMagick magick command is required to render app_icon.svg')

    with tempfile.NamedTemporaryFile(suffix='.png', delete=False) as tmp:
        tmp_path = tmp.name

    try:
        subprocess.run(
            [
                magick,
                '-background',
                'none',
                '-density',
                '384',
                SVG_PATH,
                '-resize',
                f'{size}x{size}',
                tmp_path,
            ],
            check=True,
        )
        shutil.copyfile(tmp_path, output_path)
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)

    print(f"Created: {output_path}")

# Android icon sizes
icon_sizes = {
    'mipmap-mdpi': 48,
    'mipmap-hdpi': 72,
    'mipmap-xhdpi': 96,
    'mipmap-xxhdpi': 144,
    'mipmap-xxxhdpi': 192,
}

res_dir = os.path.join(ROOT_DIR, 'app', 'src', 'main', 'res')

for folder, size in icon_sizes.items():
    folder_path = os.path.join(res_dir, folder)
    os.makedirs(folder_path, exist_ok=True)
    
    # Create ic_launcher.png
    create_android_icon(size, os.path.join(folder_path, 'ic_launcher.png'))
    
    # Create ic_launcher_round.png (same for now)
    create_android_icon(size, os.path.join(folder_path, 'ic_launcher_round.png'))

print("Android icons generated successfully!")
