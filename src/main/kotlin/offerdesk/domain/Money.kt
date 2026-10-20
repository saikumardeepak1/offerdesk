package offerdesk.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

@JvmInline
value class Money(val cents: Long) : Comparable<Money> {
    operator fun plus(other: Money) = Money(Math.addExact(cents, other.cents))

    operator fun minus(other: Money) = Money(Math.subtractExact(cents, other.cents))

    operator fun times(factor: Int) = Money(Math.multiplyExact(cents, factor.toLong()))

    override fun compareTo(other: Money) = cents.compareTo(other.cents)

    fun toBigDecimal(): BigDecimal = BigDecimal.valueOf(cents, 2)

    /** Splits into [parts] whole-cent amounts; any leftover cents go on the first part. */
    fun split(parts: Int): List<Money> {
        require(parts > 0) { "parts must be positive, was $parts" }
        val base = cents / parts
        val leftover = cents % parts
        return List(parts) { index -> Money(if (index == 0) base + leftover else base) }
    }

    override fun toString(): String {
        val sign = if (cents < 0) "-" else ""
        val abs = Math.absExact(cents)
        return String.format(Locale.US, "%s$%,d.%02d", sign, abs / 100, abs % 100)
    }

    companion object {
        val ZERO = Money(0)

        fun of(dollars: BigDecimal): Money =
            Money(dollars.setScale(2, RoundingMode.HALF_EVEN).unscaledValue().longValueExact())
    }
}

fun Iterable<Money>.sum(): Money = fold(Money.ZERO, Money::plus)
