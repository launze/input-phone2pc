from PIL import Image, ImageDraw, ImageFont
import os

def create_android_icon(size, output_path):
    """Create a simple microphone icon for Android"""
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    
    # Background circle
    padding = size // 8
    bg_color = (99, 102, 241, 255)  # Indigo color
    draw.ellipse([padding, padding, size - padding, size - padding], fill=bg_color)
    
    # Microphone body
    mic_color = (255, 255, 255, 255)
    center_x = size // 2
    mic_width = size // 5
    mic_height = size // 3
    mic_top = size // 2 - mic_height // 2
    
    # Draw microphone rounded rectangle
    draw.rounded_rectangle(
        [center_x - mic_width//2, mic_top, 
         center_x + mic_width//2, mic_top + mic_height],
        radius=mic_width//2,
        fill=mic_color
    )
    
    # Draw microphone base
    base_width = size // 3
    base_height = size // 10
    base_y = mic_top + mic_height + size // 20
    draw.rounded_rectangle(
        [center_x - base_width//2, base_y,
         center_x + base_width//2, base_y + base_height],
        radius=base_height//2,
        fill=mic_color
    )
    
    img.save(output_path)
    print(f"Created: {output_path}")

# Android icon sizes
icon_sizes = {
    'mipmap-mdpi': 48,
    'mipmap-hdpi': 72,
    'mipmap-xhdpi': 96,
    'mipmap-xxhdpi': 144,
    'mipmap-xxxhdpi': 192,
}

res_dir = os.path.join(os.path.dirname(__file__), 'app', 'src', 'main', 'res')

for folder, size in icon_sizes.items():
    folder_path = os.path.join(res_dir, folder)
    os.makedirs(folder_path, exist_ok=True)
    
    # Create ic_launcher.png
    create_android_icon(size, os.path.join(folder_path, 'ic_launcher.png'))
    
    # Create ic_launcher_round.png (same for now)
    create_android_icon(size, os.path.join(folder_path, 'ic_launcher_round.png'))

print("Android icons generated successfully!")
