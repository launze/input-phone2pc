from PIL import Image, ImageDraw, ImageFont
import os

def create_microphone_icon(size):
    img = Image.new('RGBA', (size, size), (30, 27, 75, 255))
    draw = ImageDraw.Draw(img)
    
    center = size // 2
    
    mic_width = size // 5
    mic_height = size // 2.5
    
    mic_x = center - mic_width // 2
    mic_y = center - mic_height // 2 - size // 8
    
    draw.rounded_rectangle([mic_x, mic_y, mic_x + mic_width, mic_y + mic_height], 
                          radius=mic_width//2, fill=(99, 102, 241, 255))
    
    bar_width = size // 2.5
    bar_height = size // 10
    bar_x = center - bar_width // 2
    bar_y = mic_y + mic_height + size // 20
    
    draw.rounded_rectangle([bar_x, bar_y, bar_x + bar_width, bar_y + bar_height], 
                          radius=bar_height//2, fill=(99, 102, 241, 255))
    
    arc_radius = size // 4
    arc_y = bar_y + bar_height + size // 20
    
    draw.arc([center - arc_radius, arc_y, center + arc_radius, arc_y + arc_radius * 1.5], 
             start=180, end=360, fill=(255, 255, 255, 255), width=size//30)
    
    line_y = arc_y + arc_radius * 1.5 + size // 20
    draw.line([(center - size//3, line_y), (center + size//3, line_y)], 
              fill=(255, 255, 255, 255), width=size//30)
    
    return img

sizes = [32, 128, 256, 512]
icons_dir = os.path.dirname(os.path.abspath(__file__))

for size in sizes:
    icon = create_microphone_icon(size)
    if size == 128:
        icon.save(os.path.join(icons_dir, f'128x128.png'))
        icon_2x = create_microphone_icon(256)
        icon_2x.save(os.path.join(icons_dir, '128x128@2x.png'))
    else:
        icon.save(os.path.join(icons_dir, f'{size}x{size}.png'))

print("Icons generated successfully!")