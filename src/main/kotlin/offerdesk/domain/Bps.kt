package offerdesk.domain

import java.math.BigDecimal

/** A rate in basis points: 1_599 is 15.99%. Keeps rates exact without floating point. */
@JvmInline
value class Bps(val value: Int) : Comparable<Bps> {
    init {
        require(value >= 0) { "rate cannot be negative, was $value bps" }
    }

    override fun compareTo(other: Bps) = value.compareTo(other.value)

    fun toBigDecimal(): BigDecimal = BigDecimal.valueOf(value.toLong(), 4)

    operator fun times(amount: Money): Money = Money.of(amount.toBigDecimal() * toBigDecimal())

    override fun toString() = "%d.%02d%%".format(value / 100, value % 100)

    companion object {
        val ZERO = Bps(0)
    }
}
