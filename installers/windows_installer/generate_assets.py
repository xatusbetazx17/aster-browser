"""Generate aster.ico and aster_logo.png from the vector SVG definition."""
import os
from PIL import Image, ImageDraw

def generate_aster_assets(output_dir: str):
    os.makedirs(output_dir, exist_ok=True)
    
    # Render at 512x512 high resolution
    size = 512
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    
    # Dark rounded background (#1f2430)
    pad = int(size * (2 / 24))
    radius = int(size * (6 / 24))
    draw.rounded_rectangle(
        [pad, pad, size - pad, size - pad],
        radius=radius,
        fill="#1f2430"
    )
    
    # Star coordinates from SVG viewBox="0 0 24 24"
    pts_24 = [
        (12.0, 4.5),
        (13.7, 9.7),
        (19.0, 9.0),
        (15.0, 12.3),
        (19.0, 15.6),
        (13.7, 14.9),
        (12.0, 19.5),
        (10.3, 14.9),
        (5.0, 15.6),
        (9.0, 12.3),
        (5.0, 9.0),
        (10.3, 9.7),
    ]
    scale = size / 24.0
    pts = [(x * scale, y * scale) for x, y in pts_24]
    
    # Cyan star (#8be9fd)
    draw.polygon(pts, fill="#8be9fd")
    
    # Save PNG logo
    png_path = os.path.join(output_dir, "aster_logo.png")
    img.save(png_path, format="PNG")
    print(f"Generated {png_path}")
    
    # Save multi-resolution Windows ICO
    ico_path = os.path.join(output_dir, "aster.ico")
    img.save(
        ico_path,
        format="ICO",
        sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
    )
    print(f"Generated {ico_path}")

if __name__ == "__main__":
    current_dir = os.path.dirname(os.path.abspath(__file__))
    generate_aster_assets(current_dir)
