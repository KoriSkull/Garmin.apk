# Альтернативный подход к распознаванию стрелок Яндекс.Карт

## Проблема
В декомпилированном APK Яндекс.Карт нет растровых изображений стрелок навигации. Это означает, что:

1. **Стрелки векторные** - рисуются программно через Canvas/Path
2. **Стрелки динамические** - генерируются во время работы приложения
3. **Используется другой подход** - возможно, текстовые инструкции вместо иконок

## Решения

### Вариант 1: Захват и обучение на реальных данных

Вместо поиска готовых изображений, давайте:

1. **Запустим Яндекс.Карты** на устройстве
2. **Включим навигацию** 
3. **Используем текущий код** для захвата стрелок (он уже сохраняет их в `debug_arrows/`)
4. **Соберем коллекцию** реальных стрелок
5. **Вычислим хеши** из захваченных изображений
6. **Обновим ArrowDirection** с правильными значениями

### Вариант 2: Использовать текстовые инструкции

Яндекс.Карты могут передавать инструкции в виде текста. Можно:

1. Парсить текстовые инструкции из notification/accessibility
2. Определять направление по ключевым словам:
   - "налево" → LEFT
   - "направо" → RIGHT  
   - "прямо" → STRAIGHT
   - "резко налево" → SHARP_LEFT
   - и т.д.

### Вариант 3: Анализ через UI Automator

Использовать Android UI Automator Viewer для:
1. Просмотра иерархии UI во время навигации
2. Поиска ImageView с стрелками
3. Определения resource ID стрелок

## Рекомендация

**Самый практичный подход** - Вариант 1:

```kotlin
// Код уже есть в NavigationAccessibilityService.kt:
private fun saveDebugImage(bitmap: android.graphics.Bitmap, hash: Long) {
    val dir = java.io.File(getExternalFilesDir(null), "debug_arrows")
    if (!dir.exists()) dir.mkdirs()
    
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    val filename = "arrow_\${timestamp}_\$hash.png"
    val file = java.io.File(dir, filename)
    
    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
}
```

### Шаги:
1. Собрать и установить приложение
2. Включить Accessibility Service
3. Запустить Яндекс.Карты с навигацией
4. Проехать маршрут с разными поворотами
5. Собрать изображения из `/storage/emulated/0/Android/data/iMel9i.garminhud.lite/files/debug_arrows/`
6. Вычислить хеши и обновить код

Хотите попробовать этот подход?
