#!/usr/bin/env python3
"""
Скрипт для поиска и каталогизации изображений стрелок из Яндекс.Карт
"""

import os
from pathlib import Path
from PIL import Image
import shutil

# Путь к ресурсам Яндекс.Карт
RES_DIR = r"C:\Users\mts88\Documents\GHUD\ghud-lite\src\ru.yandex.yandexmaps_738726680_rs\res"
OUTPUT_DIR = r"C:\Users\mts88\Documents\GHUD\Garmin.apk\ghud-lite\yandex_arrows"

def analyze_images():
    """Анализирует изображения и ищет потенциальные стрелки"""
    
    # Создаем выходную директорию
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    
    # Ищем все PNG и WEBP файлы
    image_files = []
    for ext in ['*.png', '*.webp']:
        image_files.extend(Path(RES_DIR).glob(ext))
    
    print(f"Найдено {len(image_files)} изображений")
    
    # Анализируем каждое изображение
    potential_arrows = []
    
    for img_path in image_files:
        try:
            with Image.open(img_path) as img:
                width, height = img.size
                
                # Фильтруем по размеру (стрелки обычно квадратные и небольшие)
                # Типичные размеры: 32x32, 48x48, 64x64, 96x96, 128x128, 132x132
                if 30 <= width <= 200 and 30 <= height <= 200:
                    aspect_ratio = width / height
                    
                    # Проверяем, что изображение примерно квадратное
                    if 0.8 <= aspect_ratio <= 1.2:
                        # Проверяем наличие альфа-канала (стрелки обычно с прозрачностью)
                        if img.mode in ('RGBA', 'LA') or (img.mode == 'P' and 'transparency' in img.info):
                            potential_arrows.append({
                                'path': img_path,
                                'size': (width, height),
                                'mode': img.mode
                            })
        except Exception as e:
            print(f"Ошибка при обработке {img_path.name}: {e}")
    
    print(f"\nНайдено {len(potential_arrows)} потенциальных стрелок")
    
    # Копируем потенциальные стрелки в выходную директорию
    for idx, arrow in enumerate(potential_arrows):
        src = arrow['path']
        dst = Path(OUTPUT_DIR) / f"{idx:03d}_{src.name}"
        shutil.copy2(src, dst)
        print(f"Скопировано: {src.name} ({arrow['size'][0]}x{arrow['size'][1]})")
    
    # Создаем HTML-галерею
    create_html_gallery(potential_arrows)
    
    print(f"\nРезультаты сохранены в: {OUTPUT_DIR}")
    print(f"Откройте gallery.html для просмотра")

def create_html_gallery(arrows):
    """Создает HTML-галерею изображений"""
    
    html = """<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Yandex Maps Arrow Images</title>
    <style>
        body {
            font-family: Arial, sans-serif;
            background: #f0f0f0;
            padding: 20px;
        }
        .gallery {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
            gap: 20px;
        }
        .item {
            background: white;
            padding: 10px;
            border-radius: 8px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
            text-align: center;
        }
        .item img {
            max-width: 100%;
            height: auto;
            background: repeating-conic-gradient(#ddd 0% 25%, white 0% 50%) 50% / 20px 20px;
        }
        .item .info {
            margin-top: 10px;
            font-size: 12px;
            color: #666;
        }
        h1 {
            color: #333;
        }
    </style>
</head>
<body>
    <h1>Yandex Maps - Potential Arrow Images</h1>
    <p>Found {} potential arrow images</p>
    <div class="gallery">
""".format(len(arrows))
    
    for idx, arrow in enumerate(arrows):
        filename = f"{idx:03d}_{arrow['path'].name}"
        html += f"""
        <div class="item">
            <img src="{filename}" alt="{arrow['path'].name}">
            <div class="info">
                <div><strong>{arrow['path'].name}</strong></div>
                <div>{arrow['size'][0]}x{arrow['size'][1]} - {arrow['mode']}</div>
            </div>
        </div>
"""
    
    html += """
    </div>
</body>
</html>
"""
    
    gallery_path = Path(OUTPUT_DIR) / "gallery.html"
    with open(gallery_path, 'w', encoding='utf-8') as f:
        f.write(html)

if __name__ == '__main__':
    analyze_images()
