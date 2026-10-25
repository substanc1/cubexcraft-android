# core — Java on Android (LGPL-3.0)

Библиотека, которая запускает Java-игру на Android: загрузка JVM, окно EGL/OpenGL ES поверх Surface, ввод (касания,
клавиатура, мышь) в эмуляцию GLFW, буфер обмена и AWT для модов. Это **не** код CubeXCraft под нашей лицензией: папка
распространяется по **GNU LGPL v3** (`COPYING.LESSER`, вместе с ней — текст GNU GPL v3 в `COPYING`).

The library that runs a Java game on Android: JVM loading, an EGL/OpenGL ES window over a Surface, input (touch,
keyboard, mouse) into the GLFW emulation, clipboard and AWT for mods. This folder is **not** under the CubeXCraft
license: it is licensed under the **GNU LGPL v3** (`COPYING.LESSER`, with the GNU GPL v3 text in `COPYING`).

## Происхождение / Origin

- **Angel Aura Amethyst (Android)** — https://github.com/AngelAuraMC/Amethyst-Android, LGPL-3.0, commit
  `330c6eae3164df64bdc4828e946a9e62cc5169e4` (2026-09-22): `app_pojavlauncher/src/main/jni` и Java-мост
  (`net.kdt.pojavlaunch.utils.JREUtils`, `org.lwjgl.glfw.CallbackBridge`, `com.oracle.dalvik.VMLauncher` и др.).
- В основе — **PojavLauncher** (https://github.com/PojavLauncherTeam/PojavLauncher, LGPL-3.0) и Boardwalk.
- `jre_launcher.c` содержит код Oracle под GPLv2 с Classpath Exception (см. заголовок файла).

Код Zalith Launcher (GPL-3.0) сюда не входит и не используется.

## Изменения CubeXCraft / CubeXCraft changes

Все правки CubeXCraft в этой папке тоже распространяются по LGPL-3.0. Список изменений — в истории git этой папки
(`git log -- core/`) и в шапках изменённых файлов.

## Замена библиотеки / Replacing the library

LGPL даёт право заменить эту библиотеку своей версией: соберите `core/` с изменениями и приложение из этого репозитория
(`./gradlew :app:assembleRelease`), подпишите своим ключом. Приложение загружает библиотеку как обычные .so и .jar.
