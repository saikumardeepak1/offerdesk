package offerdesk.domain

enum class PlanKind { PAY_IN_4, MONTHLY_0_APR, MONTHLY_APR }

sealed interface PlanTerms {
    val kind: PlanKind
    val id: String
    val label: String
    val installmentCount: Int
    val apr: Bps

    /** Billing periods in a year: 26 for biweekly, 12 for monthly. */
    val periodsPerYear: Int

    /** Period of the first installment: 0 means due at checkout. */
    val firstDuePeriod: Int

    /** The shopper pays no interest, so the merchant funds the plan. */
    val isPromotional: Boolean get() = apr == Bps.ZERO

    data object PayIn4 : PlanTerms {
        override val kind = PlanKind.PAY_IN_4
        override val id = "pay-in-4"
        override val label = "Pay in 4"
        override val installmentCount = 4
        override val apr = Bps.ZERO
        override val periodsPerYear = 26
        override val firstDuePeriod = 0
    }

    data class Monthly0Apr(val months: Int) : PlanTerms {
        init {
            require(months > 0) { "months must be positive, was $months" }
        }

        override val kind get() = PlanKind.MONTHLY_0_APR
        override val id get() = "0apr-${months}m"
        override val label get() = "0% for $months months"
        override val installmentCount get() = months
        override val apr get() = Bps.ZERO
        override val periodsPerYear get() = 12
        override val firstDuePeriod get() = 1
    }

    data class MonthlyApr(val months: Int, override val apr: Bps) : PlanTerms {
        init {
            require(months > 0) { "months must be positive, was $months" }
            require(apr > Bps.ZERO) { "an APR plan needs a positive APR; use Monthly0Apr for 0%" }
        }

        override val kind get() = PlanKind.MONTHLY_APR
        override val id get() = "apr-${months}m"
        override val label get() = "$apr APR for $months months"
        override val installmentCount get() = months
        override val periodsPerYear get() = 12
        override val firstDuePeriod get() = 1
    }
}
