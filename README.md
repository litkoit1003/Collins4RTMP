![.](https://cdn.modrinth.com/data/cached_images/cbf9d31899fe7928e15d74e51a10823c287a1fff.png)

[![Github](https://cdn.modrinth.com/data/cached_images/98785f1d471df1a7aceee62cd2c715d80de8e1bd.png)](https://github.com/imsawiq/collins/releases/tag/0.1.0)
[![Discord](https://cdn.modrinth.com/data/cached_images/ce03caef41db32fe933e0a8ec6e8aca70775418d.png)](https://discord.com/invite/nPbxmeTnBQ)
[![Telegram](https://cdn.modrinth.com/data/cached_images/226d7052633712f6e671b07b6180ec6b12d13068.png)](https://t.me/sawiqp)

^^^^^^^^^^^^^^
**Соц. сети автора оригинального мода**

**🚨 Предупреждение 🚨: Это неофициальный порт мода Colins на версию 1.21.10 с добавлением поддержки RTMP протокола!**   
Based on [collins](https://github.com/imsawiq/collins) by [imsawiq], licensed under MIT.

This version updated for Minecraft 1.21.10 and support RTMP.

**Клиентский** мод, который отображает и проигрывает видео на экранах в мире (экраны Collins) **+** **серверный** Paper-плагин, который создаёт и управляет этими экранами.

> Чтобы смотреть видео на сервере, **обязательно нужны обе части**:
> - Сервер: плагин **Collins Paper** — https://modrinth.com/project/collins-paper
> - Клиент: мод **Collins Fabric** — https://modrinth.com/project/collins-fabric

### ✨ Особенности

#### Клиент (Collins Fabric)
- **Проигрывание видео на экранах**: видео отображается прямо в мире.
- **Звук**: звук проигрывается на клиенте, есть локальная регулировка громкости.
- **Таймлайн в actionbar**: опционально показывает прогресс рядом с активными экранами.
- **Меню настроек** (ModMenu): тумблер рендера, локальная громкость, таймлайн в actionbar.
- **Клиентские команды**:
    - `/collinsc time` — пишет текущий таймлайн в чат.

#### Сервер (Collins Paper)
- **Создание и управление экранами**: сервер задаёт что и где проигрывается.
- **Команды управления**: play/stop/seek (включая перемотку назад).
- **Синхронизация состояния**: клиентам отправляется состояние экранов, проигрывание идёт локально.

### 📦 Требования / зависимости

#### Клиент
- **Fabric API** — обязательно.
- **ModMenu** — опционально (рекомендуется для удобного меню настроек).

#### Сервер
- **Paper / совместимый Spigot сервер**

### 🔗 Ссылки
- Collins Fabric (клиентский мод): https://modrinth.com/project/collins-fabric
- Collins Paper (серверный плагин): https://modrinth.com/project/collins-paper
- Source: https://github.com/imsawiq/collins
- Issues: https://github.com/imsawiq/collins/issues

### ⌨ Команды (permission: `collins.admin`)

> Все команды выполняются **только игроком** (не из консоли).

#### Выделение / создание экрана
- `/collins pos1`
    - Ставит **pos1** на блок, на который ты смотришь.
- `/collins pos2`
    - Ставит **pos2** на блок, на который ты смотришь.
    - Если выделение валидное — кратко показывает рамку-превью.
- `/collins create <name>`
    - Создаёт новый экран по выделению pos1/pos2 и сохраняет его.

#### URL / управление воспроизведением
- `/collins seturl <screen> <url>`
    - Устанавливает ссылку на видео для экрана.
    - Сбрасывает позицию, чтобы новый URL не наследовал таймер от прошлого видео.
- `/collins play <screen>`
    - Запускает воспроизведение **с 0:00**.
- `/collins pause <screen>`
    - Останавливает воспроизведение, но **сохраняет** текущую позицию.
- `/collins resume <screen>`
    - Продолжает воспроизведение с сохранённой позиции.
- `/collins stop <screen>`
    - Останавливает воспроизведение и **сбрасывает** позицию на **0:00**.
- `/collins seek <screen> <seconds>`
    - Перемотка относительно текущей позиции на N секунд (можно отрицательные значения).
    - Пишет сообщение: от -> до.
- `/collins back <screen> <seconds>`
    - Удобная команда: перемотка **назад** на N секунд.

#### Глобальные настройки (рассылаются клиентам)
- `/collins volume set <0..2>`
    - Задаёт **глобальный множитель громкости** (0.0..2.0) для всех экранов.
- `/collins volume reset`
    - Сброс глобальной громкости на **1.0**.
- `/collins radius set <1..512>`
    - Задаёт **радиус слышимости** (на каком расстоянии клиент слышит/отслеживает звук).
- `/collins radius reset`
    - Сброс радиуса слышимости на **100**.

#### Управление
- `/collins list`
    - Показывает список всех экранов, URL и состояние (играет/нет).
- `/collins remove <screen>`
    - Удаляет экран и очищает его состояние воспроизведения.

### 👥 Авторы
- **Sawiq** - Главный кодер
- **Zorsh** - Помощник кодера
- **EssciZ** - Дизайнер

Нашёл баг или есть идея? Открой issue на GitHub.

![.](https://cdn.modrinth.com/data/cached_images/47eae40180fb78cffd6a67ad84dfea745d4d598f_0.webp)

## Projects

### `collins-fabric`
Client-side Fabric mod.

- Renders video frames directly in-world on Collins screens.
- Plays audio client-side.
- Has a client config (ModMenu) for local volume / rendering toggle / actionbar timeline.

### `collins-paper`
Server-side Paper plugin.

- Screen selection and creation commands.
- Playback control (play/pause/resume/stop/seek/back).
- Syncs screens and playback state to clients.

## Requirements

- **Java 21**
- **Gradle** (use the included wrapper)

## Build

From the repository root:

```bash
./gradlew :collins-fabric:build
./gradlew :collins-paper:build
```

Artifacts:

- Fabric mod jar:
    - `collins-fabric/build/libs/`
- Paper plugin jar:
    - `collins-paper/build/libs/`

## Quick start (server + client)

1) **Server (Paper)**

- Put the built jar into:
    - `plugins/`
- Start the server.

2) **Client (Fabric)**

- Install Fabric Loader and Fabric API.
- Put the built jar into:
    - `.minecraft/mods/`

3) **Use commands (server admin)**

All plugin commands require permission: `collins.admin`.

- Select screen corners:
    - `/collins pos1`
    - `/collins pos2`
- Create a screen:
    - `/collins create <name>`
- Set video URL:
    - `/collins seturl <screen> <url>`
- Start playback:
    - `/collins play <screen>`

Client helper command:

- `/collinsc time` — prints current timeline to chat.

## Authors

- Sawiq
- Zorsh
- EssciZ
