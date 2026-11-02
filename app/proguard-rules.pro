# Цель сжатия — размер, а не сокрытие: проект открытый.
-dontobfuscate

# chesslib находит ходы и фигуры через перечисления и их имена.
-keep class com.github.bhlangonijr.chesslib.** { *; }
-dontwarn com.github.bhlangonijr.chesslib.**

# Опознание фактов в тренере идёт по имени класса.
-keep class org.chesscoach.coach.model.** { *; }
