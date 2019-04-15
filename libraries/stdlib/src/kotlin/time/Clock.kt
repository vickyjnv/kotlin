/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package kotlin.time

public interface Clock {

    /**
     * Marks a time point on this clock.
     */
    fun mark(): ClockMark

    companion object {
        val Default: Clock get() = MonoClock
    }

}

public interface ClockMark {
    fun elapsed(): Duration

    operator fun plus(duration: Duration): ClockMark = AdjustedClockMark(this, duration)
    operator fun minus(duration: Duration): ClockMark = plus(-duration)
}


private class AdjustedClockMark(val mark: ClockMark, val adjustment: Duration) : ClockMark {
    override fun elapsed(): Duration = mark.elapsed() - adjustment

    override fun plus(duration: Duration): ClockMark = AdjustedClockMark(mark, adjustment + duration)
}