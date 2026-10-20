package offerdesk.domain

import java.util.SplittableRandom

class SeededGen(seed: Long) {
    private val random = SplittableRandom(seed)

    fun int(from: Int, untilInclusive: Int): Int = random.nextInt(from, untilInclusive + 1)

    fun long(from: Long, untilInclusive: Long): Long = random.nextLong(from, untilInclusive + 1)

    fun <T> pick(options: List<T>): T = options[random.nextInt(options.size)]
}
