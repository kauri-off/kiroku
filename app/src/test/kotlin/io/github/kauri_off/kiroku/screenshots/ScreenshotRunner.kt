package io.github.kauri_off.kiroku.screenshots

import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.internal.bytecode.InstrumentationConfiguration
import org.robolectric.internal.bytecode.Interceptor
import org.robolectric.internal.bytecode.MethodRef
import org.robolectric.internal.bytecode.MethodSignature
import org.robolectric.util.Function
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Calendar

class ScreenshotRunner(
    testClass: Class<*>,
) : RobolectricTestRunner(testClass) {
    // Later interceptors take precedence, so this one replaces the stock handling of System.currentTimeMillis.
    override fun findInterceptors(): Collection<Interceptor> = super.findInterceptors() + WallClockInterceptor()

    // Wall-clock calls are redirected only in instrumented code: the app, :core and the date picker's calendar.
    override fun createClassLoaderConfig(method: FrameworkMethod): InstrumentationConfiguration =
        InstrumentationConfiguration
            .Builder(super.createClassLoaderConfig(method))
            .addInstrumentedPackage("io.github.kauri_off.kiroku.")
            .addInstrumentedPackage("androidx.compose.material3.internal.CalendarModel")
            .addInstrumentedPackage("androidx.compose.material3.internal.LegacyCalendarModel")
            .doNotInstrumentPackage("io.github.kauri_off.kiroku.screenshots")
            .build()
}

// Robolectric creates a second copy of the interceptor inside the sandbox, so this holds only JDK values.
object FixedWallClock {
    val instant: Instant = Instant.parse("2026-01-14T09:00:00Z")

    @JvmStatic fun clock(): Clock = Clock.fixed(instant, ZoneId.systemDefault())

    @JvmStatic fun clockIn(zone: ZoneId): Clock = Clock.fixed(instant, zone)

    @JvmStatic fun pin(calendar: Calendar): Calendar = calendar.apply { timeInMillis = instant.toEpochMilli() }
}

private val nowOwners =
    listOf(
        Instant::class.java,
        LocalDate::class.java,
        LocalTime::class.java,
        LocalDateTime::class.java,
        ZonedDateTime::class.java,
        OffsetDateTime::class.java,
        OffsetTime::class.java,
        Year::class.java,
        YearMonth::class.java,
        MonthDay::class.java,
    ).map { MethodRef(it, "now") }

class WallClockInterceptor :
    Interceptor(
        *(
            nowOwners +
                listOf(
                    MethodRef(System::class.java, "currentTimeMillis"),
                    MethodRef(Clock::class.java, "systemDefaultZone"),
                    MethodRef(Clock::class.java, "systemUTC"),
                    MethodRef(Clock::class.java, "system"),
                    MethodRef(Calendar::class.java, "getInstance"),
                )
        ).toTypedArray(),
    ) {
    private val lookup = MethodHandles.publicLookup()
    private val clock = lookup.findStatic(FixedWallClock::class.java, "clock", MethodType.methodType(Clock::class.java))
    private val clockIn =
        lookup.findStatic(FixedWallClock::class.java, "clockIn", MethodType.methodType(Clock::class.java, ZoneId::class.java))

    override fun handle(methodSignature: MethodSignature): Function<Any, Any> =
        Function { _, _, _ -> throw UnsupportedOperationException("Only invokedynamic call sites are redirected: $methodSignature") }

    override fun getMethodHandle(
        methodName: String,
        type: MethodType,
    ): MethodHandle =
        when (methodName) {
            "currentTimeMillis" -> MethodHandles.constant(Long::class.javaPrimitiveType, FixedWallClock.instant.toEpochMilli())
            "systemDefaultZone" -> clock
            "systemUTC" -> MethodHandles.insertArguments(clockIn, 0, ZoneOffset.UTC)
            "system" -> clockIn
            "getInstance" ->
                MethodHandles.filterReturnValue(
                    lookup.findStatic(Calendar::class.java, methodName, type),
                    lookup.findStatic(FixedWallClock::class.java, "pin", MethodType.methodType(Calendar::class.java, Calendar::class.java)),
                )
            "now" -> now(type)
            else -> throw UnsupportedOperationException(methodName)
        }

    private fun now(type: MethodType): MethodHandle {
        val owner = type.returnType()
        val viaClock = lookup.findStatic(owner, "now", MethodType.methodType(owner, Clock::class.java))
        return when {
            type.parameterCount() == 0 -> MethodHandles.collectArguments(viaClock, 0, clock)
            type.parameterType(0) == ZoneId::class.java -> MethodHandles.collectArguments(viaClock, 0, clockIn)
            else -> lookup.findStatic(owner, "now", type)
        }
    }
}
