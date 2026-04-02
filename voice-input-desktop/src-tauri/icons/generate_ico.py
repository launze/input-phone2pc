from PIL import Image
import os


icons_dir = os.path.dirname(os.path.abspath(__file__))
source_path = os.path.join(icons_dir, "512x512.png")
ico_path = os.path.join(icons_dir, "icon.ico")

source = Image.open(source_path).convert("RGBA")
source.save(
    ico_path,
    format="ICO",
    sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
)

print(f"Windows ICO icon created at: {ico_path}")
