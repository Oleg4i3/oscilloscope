# SoundOscilloscope

Минималистичный осциллограф звука для Android. Один Java-файл, нет зависимостей.

## Возможности

- **Временной домен** — форма волны, не спектр
- **Выбор источника** — встроенный mic, built-in raw (UNPROCESSED), USB-аудио, гарнитура
- **Gain-слайдер** — ±20 dB, вертикальный, левый край
- **Soft clip** — tanh-ограничитель
- **Trigger sync** — rising zero-crossing для стабильной картинки
- **Масштаб** — двойной тап: 1 / 2 / 5 / 10 / 20 мс/дел
- **VU-метр** — 30 сегментов dBFS
- **Фосфорный стиль** — свечение + резкая линия

## Требования

- Android 5.0 (API 21)
- Разрешение `RECORD_AUDIO`

## Сборка

```bash
chmod +x gradlew
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions

Push в `main`/`master` — собирается APK, артефакт `SoundOscilloscope-debug`.

## Структура

```
app/src/main/java/com/osc/MainActivity.java   ← весь код
app/src/main/AndroidManifest.xml
app/build.gradle
build.gradle / settings.gradle
.github/workflows/build.yml
```

Классы внутри `MainActivity.java`:

| Класс | Назначение |
|---|---|
| `MainActivity` | Activity, layout, audio loop |
| `OscilloscopeView` | Ring buffer, trigger, отрисовка |
| `VerticalSeekBar` | Gain-слайдер |
| `VuMeterView` | VU-метр dBFS |
| `AudioSrcItem` | Дескриптор источника |
