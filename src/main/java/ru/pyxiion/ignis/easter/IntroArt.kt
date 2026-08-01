package ru.pyxiion.ignis

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.Month

val taglines = listOf(
    "Enjoy scripting!",
    "Have a nice day!",
    "I work completely fine!",
    "Send help",
    "Ignis est vita",
    "\uD83D\uDD25\uD83D\uDD25\uD83D\uDD25",
    "Powered by Lua\u2122",
    "Probably not buggy",
    "/ignis reload fixes everything",
    "Built with \u2764 and Lua",
    "PxLuaNova?",
    "I wanna Luau types",
    "Try self:heal(100)",
    "NeoForge port when?",
    "Paper port when?",
    "Try Mappet - JS scripting Minecraft (1.12.2 sadly)",
    "I didn't measure, but I must be faster than Skript (i hope)",
    "Have you seen ugly JS promises? I use only green threads.",
    "Сделано в России",
    "// TODO: write better taglines",
    "Do not global variables",
    "No bugs, just undocumented features",
    "Test only in production",
    "Lua: why do arrays start at 1, i wanna cry",
    "Fabric >>> Paper. Change my mind.",
    "stack trace: you're here",
    "yield() is my drug",
    "Uncaught Exception: player is too creative",
    "Don't worry, the GC (Garbage Collector) will clean that up... eventually",
    "Are you a wizard of Lua?",
    "Metatable? More like metababble",
    "if 0 then programmer:cry() end",

    "За решеткой есть жизнь и на кладбище есть плюсы", // C#/C++
    "Отладка даёт представление о вечности",

    "Papa can into C",
    "Dotnet or Java? No thanks, merci!",
    "Python or Ruby? Lord, save us!",
    "C will never die. Re-firmware it!",
    "Only pure C according to the old school precepts"
)

fun getTagline(): String {
    val date = LocalDateTime.now()
    val month = date.month
    val day = date.dayOfMonth
    val yearDay = date.dayOfYear
    val weekDay = date.dayOfWeek
    val hour = date.hour

    // Idk, just added random taglines for specific dates
    return when {
        hour == 3 -> "Coding scripts at 3 AM hits different"

        yearDay == 256 -> "Happy Programmer's Day! Keep coding! \uD83D\uDCBB"

        month == Month.JANUARY && day == 1 -> "Happy New Year! \uD83C\uDF86"
        month == Month.FEBRUARY && day == 23 -> "Happy Defender of the Fatherland Day!"
        month == Month.MARCH && day == 8 -> "Happy International Women's Day! \uD83C\uDF39"
        month == Month.MARCH && day == 14 -> "Happy Pi Day! 3.14159..."
        month == Month.APRIL && day == 1 -> "This tagline is a lie."
        month == Month.MAY && day == 9 -> "Happy Victory Day! \uD83C\uDF3A"
        month == Month.OCTOBER && day == 31 -> "Trick or treat! \uD83C\uDF83"
        month == Month.DECEMBER && day == 31 -> "Get ready for New Year! \uD83C\uDF87"
        weekDay == DayOfWeek.FRIDAY && day == 13 -> "Friday 13th. Stay safe. \uD83D\uDC7B"

        else -> taglines.random()
    }
}

fun introArt(version: String): String = buildString {
    val R = "\u001B[0m"
    val A = "\u001B[48;5;214m\u001B[30m"
    val O = "\u001B[48;5;208m\u001B[97m"
    val Y = "\u001B[48;5;228m\u001B[30m"
    val D = "\u001B[48;5;202m\u001B[97m"
    val C = "\u001B[38;5;220m"
    val V = "\u001B[2m\u001B[38;5;244m"
    val G = "\u001B[38;5;34m"
    val E = "\u001B[38;5;81m"

    val ansi = Regex("\u001B\\[[;0-9]*[mK]")

    fun StringBuilder.addAligned(flame: String, text: String, align: Int = 24) {
        val visible = flame.replace(ansi, "").length
        append(flame)
        repeat((align - visible).coerceAtLeast(1)) { append(' ') }
        append(text)
        appendLine()
    }

    val isRussiaDay = System.getProperty("pxignis.russiaday")?.toBoolean() ?: run {
        val cal = java.util.Calendar.getInstance()
        cal[java.util.Calendar.MONTH] == java.util.Calendar.JUNE && cal[java.util.Calendar.DAY_OF_MONTH] == 12
    }

    appendLine()
    if (isRussiaDay) {
        val W = "\u001B[48;5;255m\u001B[30m"
        val B = "\u001B[48;5;27m\u001B[97m"
        val Rd = "\u001B[48;5;196m\u001B[97m"
        addAligned("           $W $R", "")
        addAligned("          $W   $R", "")
        addAligned("         $W     $R", "$C PxIgnis$R")
        addAligned("        $B       $R", "$V v$version$R")
        addAligned("       $B   $B   $B   $R", "$G Modrinth: https://modrinth.com/mod/pxignis$R")
        addAligned("      $B   $B     $B   $R", " GitHub:   https://github.com/PyXiion/PxIgnis$R")
        addAligned("     $Rd   $Rd       $Rd   $R", "$W Happy$B Russia$Rd day!$R")
        addAligned("      $Rd           $R", "")
        addAligned("       $Rd         $R", "")
    } else {
        addAligned("           $A $R", "")
        addAligned("          $A   $R", "")
        addAligned("         $A     $R", "$C PxIgnis$R")
        addAligned("        $A       $R", "$V v$version$R")
        addAligned("       $A   $Y   $O   $R", "$G Modrinth: https://modrinth.com/mod/pxignis$R")
        addAligned("      $A   $Y     $O   $R", " GitHub:   https://github.com/PyXiion/PxIgnis$R")
        addAligned("     $D   $Y       $O   $R", "$E ${getTagline()}$R")
        addAligned("      $D           $R", "")
        addAligned("       $D         $R", "")
    }
}
