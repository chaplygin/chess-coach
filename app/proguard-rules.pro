# Цель сжатия — размер, а не сокрытие: проект открытый.
-dontobfuscate

# chesslib находит ходы и фигуры через перечисления и их имена.
-keep class com.github.bhlangonijr.chesslib.** { *; }
-dontwarn com.github.bhlangonijr.chesslib.**

# Tink ссылается на аннотации, которых нет во время выполнения, а его сообщения
# читаются через отражение: от этого зависит зашифрованный вход в Lichess.
-dontwarn com.google.errorprone.annotations.**
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { <fields>; }

# Опознание фактов в тренере идёт по имени класса.
-keep class org.chesscoach.coach.model.** { *; }
