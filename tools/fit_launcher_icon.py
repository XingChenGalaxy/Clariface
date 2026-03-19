from pathlib import Path
from PIL import Image


def trim_to_alpha(img: Image.Image) -> Image.Image:
    rgba = img.convert("RGBA")
    alpha = rgba.split()[-1]
    bbox = alpha.getbbox()
    if bbox is None:
        raise ValueError("image has no visible pixels")
    return rgba.crop(bbox)


def to_square_cover(img: Image.Image, size: int) -> Image.Image:
    # Scale to cover square, then center-crop. This removes residual margins.
    w, h = img.size
    scale = max(size / w, size / h)
    new_w = int(round(w * scale))
    new_h = int(round(h * scale))
    resized = img.resize((new_w, new_h), Image.LANCZOS)
    left = (new_w - size) // 2
    top = (new_h - size) // 2
    return resized.crop((left, top, left + size, top + size))


def main() -> None:
    icon_path = Path(r"D:\Code\Project\Android\Clariface\app\src\main\res\drawable\clariface_icon.png")
    backup_path = icon_path.with_name("clariface_icon_backup_before_fill.png")

    src = Image.open(icon_path).convert("RGBA")
    src.save(backup_path)

    trimmed = trim_to_alpha(src)
    fitted = to_square_cover(trimmed, 1024)
    fitted.save(icon_path)

    print(f"UPDATED {icon_path}")
    print(f"BACKUP  {backup_path}")
    print(f"SOURCE  {src.size}")
    print(f"TRIMMED {trimmed.size}")
    print(f"FINAL   {fitted.size}")


if __name__ == "__main__":
    main()

